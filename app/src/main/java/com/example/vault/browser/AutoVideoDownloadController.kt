package com.example.vault.browser

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.vault.data.VaultDownloadEvent
import com.example.vault.data.VaultVideoDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

sealed interface AutoVideoDownloadState {
    data object Idle : AutoVideoDownloadState

    data class Scanning(
        val pageUrl: String,
    ) : AutoVideoDownloadState

    data class DownloadingDirect(
        val streamUrl: String,
        val pageUrl: String,
        val title: String,
        val progress: Float,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = -1L,
    ) : AutoVideoDownloadState

    data class ResolvingPage(
        val pageUrl: String,
        val reason: String,
    ) : AutoVideoDownloadState

    data class Cooldown(
        val remainingSeconds: Int,
        val isRateLimitBackoff: Boolean = false,
        val note: String? = null,
    ) : AutoVideoDownloadState

    data class Completed(
        val title: String,
        val savedLocation: String,
        val pageUrl: String,
    ) : AutoVideoDownloadState

    data class Error(
        val message: String,
        val pageUrl: String = "",
        val isDrmOrRestricted: Boolean = false,
    ) : AutoVideoDownloadState
}

sealed interface PageStreamResolution {
    data class Resolved(
        val streamUrl: String,
        val title: String,
        val qualityLabel: String = "",
    ) : PageStreamResolution

    data class UnsupportedOrDrm(
        val reason: String,
    ) : PageStreamResolution

    data class Failed(
        val reason: String,
        val httpStatusCode: Int? = null,
    ) : PageStreamResolution
}

fun interface PageStreamResolver {
    suspend fun resolvePageUrl(
        sanitizedPageUrl: String,
        cleanOrigin: String,
    ): PageStreamResolution
}

/**
 * Cookie-free resolver for `blob:` or missing-src pages:
 * 1. Sends `{ page_url }` (never user cookies) to a configured yt-dlp backend endpoint if set.
 * 2. Falls back to a cookie-free public HTML metadata check (`og:video` / direct `<video src>`).
 */
class YtDlpAndPublicPageResolver(
    private val okHttpClient: OkHttpClient = VaultVideoDownloader.createDefaultClient(),
    private val backendBaseUrlProvider: () -> String = { "" },
) : PageStreamResolver {

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedBaseUrl: String = ""

    @Volatile
    private var cachedApi: YtDlpBackendApi? = null

    override suspend fun resolvePageUrl(
        sanitizedPageUrl: String,
        cleanOrigin: String,
    ): PageStreamResolution = withContext(Dispatchers.IO) {
        val backendEndpoint = backendBaseUrlProvider().trim()
        if (backendEndpoint.startsWith("http://", ignoreCase = true) ||
            backendEndpoint.startsWith("https://", ignoreCase = true)
        ) {
            val api = getOrCreateApi(backendEndpoint)
            if (api != null) {
                try {
                    val response = api.inspectStreamMetadata(
                        YtDlpInspectRequest(
                            pageUrl = sanitizedPageUrl,
                            candidateUrl = null,
                            cookies = null,
                            referer = cleanOrigin.ifBlank { null },
                        ),
                    )

                    if (response.isDrm || !response.drmScheme.isNullOrBlank()) {
                        return@withContext PageStreamResolution.UnsupportedOrDrm(
                            "Stream uses DRM protection (${response.drmScheme ?: "encrypted media"}) and cannot be downloaded.",
                        )
                    }

                    val bestFormat = response.formats
                        .filter { !it.isDrm && !it.isHls && it.url.startsWith("http", ignoreCase = true) }
                        .maxByOrNull { it.height }

                    val resolvedUrl = bestFormat?.url
                        ?: response.streamUrl?.takeIf {
                            !response.isHls && it.startsWith("http", ignoreCase = true)
                        }

                    if (!resolvedUrl.isNullOrBlank()) {
                        return@withContext PageStreamResolution.Resolved(
                            streamUrl = resolvedUrl,
                            title = response.title?.takeIf { it.isNotBlank() } ?: "Vault_Video",
                            qualityLabel = bestFormat?.qualityLabel ?: response.qualityLabel.orEmpty(),
                        )
                    }

                    if (response.isHls || response.formats.any { it.isHls && !it.isDrm }) {
                        return@withContext PageStreamResolution.UnsupportedOrDrm(
                            "Page only provides segmented HLS streams; configure direct MP4 output on the extractor backend.",
                        )
                    }

                    val backendError = response.error?.takeIf { it.isNotBlank() }
                    if (backendError != null) {
                        return@withContext PageStreamResolution.Failed(backendError)
                    }
                } catch (httpEx: HttpException) {
                    val code = httpEx.code()
                    if (code == 429 || code == 403) {
                        return@withContext PageStreamResolution.Failed(
                            reason = "Extractor backend rate-limited (HTTP $code). Cooldown applied.",
                            httpStatusCode = code,
                        )
                    }
                } catch (_: Throwable) {
                    // Fall through to cookie-free public HTML metadata check.
                }
            }
        }

        // Cookie-free public HTML inspection for OpenGraph / direct <video> URLs
        val request = Request.Builder()
            .url(sanitizedPageUrl)
            .get()
            .header("User-Agent", SafeWebViewConfig.STANDARD_ANDROID_CHROME_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .build()

        val result = runCatching {
            okHttpClient.newCall(request).execute().use { resp ->
                val html = if (resp.isSuccessful) resp.body?.string()?.take(180_000).orEmpty() else ""
                resp.code to html
            }
        }.getOrNull() ?: return@withContext PageStreamResolution.Failed(
            reason = "Unable to reach page URL for stream resolution.",
        )

        val (statusCode, html) = result
        if (statusCode == 429 || statusCode == 403) {
            return@withContext PageStreamResolution.Failed(
                reason = "Host returned HTTP $statusCode. Automatic rate-limit cooldown applied.",
                httpStatusCode = statusCode,
            )
        }
        if (statusCode !in 200..299 || html.isBlank()) {
            return@withContext PageStreamResolution.Failed(
                reason = if (backendEndpoint.isBlank()) {
                    "Stream uses blob/MSE playback. Configure a yt-dlp extractor endpoint to resolve this page URL."
                } else {
                    "Page resolution failed (HTTP $statusCode)."
                },
                httpStatusCode = statusCode,
            )
        }

        if (html.contains("widevine", ignoreCase = true) ||
            html.contains("com.widevine.alpha", ignoreCase = true) ||
            html.contains("playready", ignoreCase = true)
        ) {
            return@withContext PageStreamResolution.UnsupportedOrDrm(
                "Detected DRM-protected player configuration on this page.",
            )
        }

        val ogVideo = extractMetaContent(html, "og:video:secure_url")
            ?: extractMetaContent(html, "og:video:url")
            ?: extractMetaContent(html, "og:video")
            ?: extractMetaContent(html, "twitter:player:stream")
            ?: extractDirectMp4FromHtml(html)

        val pageTitle = extractMetaContent(html, "og:title")
            ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: "Vault_Video"

        val cleanMediaUrl = ogVideo?.trim()?.replace("&amp;", "&").orEmpty()
        if (cleanMediaUrl.startsWith("http://", ignoreCase = true) ||
            cleanMediaUrl.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext PageStreamResolution.Resolved(
                streamUrl = cleanMediaUrl,
                title = pageTitle,
            )
        }

        PageStreamResolution.Failed(
            reason = if (backendEndpoint.isBlank()) {
                "Video uses blob/MSE streaming with no public direct URL. Configure your yt-dlp backend endpoint to resolve it."
            } else {
                "No downloadable non-DRM stream could be resolved from this page URL."
            },
        )
    }

    private fun extractMetaContent(html: String, propertyName: String): String? {
        val escaped = Regex.escape(propertyName)
        val firstPattern = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']$escaped["'][^>]+content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        firstPattern.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }

        val reversedPattern = Regex(
            """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+(?:property|name)\s*=\s*["']$escaped["']""",
            RegexOption.IGNORE_CASE,
        )
        return reversedPattern.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractDirectMp4FromHtml(html: String): String? {
        val videoSrcPattern = Regex(
            """https?://[^\s"'<>\\]+\.(?:mp4|webm|mov)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE,
        )
        return videoSrcPattern.find(html)?.value
    }

    private fun getOrCreateApi(rawBaseUrl: String): YtDlpBackendApi? {
        val normalized = if (rawBaseUrl.endsWith('/')) rawBaseUrl else "$rawBaseUrl/"
        cachedApi?.let { existing ->
            if (cachedBaseUrl == normalized) return existing
        }
        val created = runCatching {
            Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttpClient)
                .addConverterFactory(jsonFormat.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(YtDlpBackendApi::class.java)
        }.getOrNull()
        if (created != null) {
            cachedBaseUrl = normalized
            cachedApi = created
        }
        return created
    }
}

private data class OnDemandVideoCandidate(
    val currentSrc: String,
    val src: String,
    val sourceUrls: List<String>,
    val paused: Boolean,
    val ended: Boolean,
    val currentTime: Double,
    val duration: Double,
    val videoWidth: Int,
    val videoHeight: Int,
    val hasMediaKeys: Boolean,
    val score: Double,
) {
    val bestDirectHttpUrl: String?
        get() = (listOf(currentSrc, src) + sourceUrls)
            .map { it.trim() }
            .firstOrNull { url ->
                (url.startsWith("http://", ignoreCase = true) ||
                    url.startsWith("https://", ignoreCase = true)) &&
                    !url.startsWith("blob:", ignoreCase = true)
            }

    val isBlobOrMediaSource: Boolean
        get() = currentSrc.startsWith("blob:", ignoreCase = true) ||
            src.startsWith("blob:", ignoreCase = true) ||
            currentSrc.startsWith("mediasource:", ignoreCase = true)
}

private data class OnDemandPageScanResult(
    val pageUrl: String,
    val pageTitle: String,
    val bestVideo: OnDemandVideoCandidate?,
    val anyDrmDetected: Boolean,
)

/**
 * On-demand, single-flight, cooldown-protected WebView auto-download controller.
 * - Never polls in the background and never forwards personal account Cookie headers.
 * - Executes a single DOM/video scan via JavascriptInterface only when the user taps Download.
 * - Routes direct HTTP/HTTPS streams to [VaultVideoDownloader] and falls back to sanitized
 *   `page_url` resolution for `blob:` / MSE or missing-src streams.
 */
class AutoVideoDownloadController(
    private val downloader: VaultVideoDownloader,
    private val pageStreamResolver: PageStreamResolver,
    private val minCooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executionMutex = Mutex()
    private val pendingScans = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private val _state = MutableStateFlow<AutoVideoDownloadState>(AutoVideoDownloadState.Idle)
    val state: StateFlow<AutoVideoDownloadState> = _state.asStateFlow()

    private val _cooldownRemainingSeconds = MutableStateFlow(0)
    val cooldownRemainingSeconds: StateFlow<Int> = _cooldownRemainingSeconds.asStateFlow()

    private var activeJob: Job? = null
    private var cooldownJob: Job? = null

    @Volatile
    private var cooldownUntilMs: Long = 0L

    @Volatile
    private var backoffUntilMs: Long = 0L

    @Volatile
    private var consecutiveRateLimitHits: Int = 0

    private val jsBridge = object {
        @JavascriptInterface
        fun onVideoScanResult(scanId: String, payloadJson: String) {
            pendingScans.remove(scanId)?.complete(payloadJson)
        }
    }

    @SuppressLint("JavascriptInterface")
    fun attachToWebView(webView: WebView) {
        runCatching { webView.removeJavascriptInterface(JS_BRIDGE_NAME) }
        runCatching { webView.addJavascriptInterface(jsBridge, JS_BRIDGE_NAME) }
    }

    fun detachFromWebView(webView: WebView?) {
        webView?.let { wv ->
            runCatching { wv.removeJavascriptInterface(JS_BRIDGE_NAME) }
        }
        pendingScans.values.forEach { it.cancel() }
        pendingScans.clear()
    }

    fun triggerAutoDownload(
        webView: WebView?,
        fallbackPageUrl: String,
    ) {
        val now = System.currentTimeMillis()
        if (executionMutex.isLocked || activeJob?.isActive == true) {
            return
        }

        if (now < cooldownUntilMs) {
            val remaining = ceil((cooldownUntilMs - now) / 1000.0).toInt().coerceAtLeast(1)
            val isBackoff = now < backoffUntilMs
            _cooldownRemainingSeconds.value = remaining
            _state.value = AutoVideoDownloadState.Cooldown(
                remainingSeconds = remaining,
                isRateLimitBackoff = isBackoff,
                note = if (isBackoff) {
                    "Automatic rate-limit backoff active — please wait ${remaining}s."
                } else {
                    "Cooldown protection active — please wait ${remaining}s before scanning again."
                },
            )
            startCooldownTicker()
            return
        }

        if (!executionMutex.tryLock()) return

        activeJob = controllerScope.launch {
            var observedHttpCode: Int? = null
            var keepTerminalStateAfterUnlock = false

            try {
                val initialUrl = fallbackPageUrl.trim()
                _state.value = AutoVideoDownloadState.Scanning(initialUrl)

                val scan = performOnDemandScan(webView, initialUrl)
                val rawPageUrl = scan.pageUrl.ifBlank { initialUrl }
                val sanitized = UrlSanitizer.sanitizePublicUrl(rawPageUrl)

                if (!sanitized.isValidPublicHttpUrl) {
                    _state.value = AutoVideoDownloadState.Error(
                        message = "Open an HTTP or HTTPS video page before downloading.",
                        pageUrl = rawPageUrl,
                    )
                    keepTerminalStateAfterUnlock = true
                    return@launch
                }

                val cleanPageUrl = sanitized.sanitizedUrl
                val cleanOrigin = sanitized.cleanOrigin
                val bestVideo = scan.bestVideo

                if (scan.anyDrmDetected || bestVideo?.hasMediaKeys == true) {
                    _state.value = AutoVideoDownloadState.Error(
                        message = "DRM-protected stream detected (Encrypted Media Extensions). Download is not supported.",
                        pageUrl = cleanPageUrl,
                        isDrmOrRestricted = true,
                    )
                    keepTerminalStateAfterUnlock = true
                    return@launch
                }

                val directStreamUrl = bestVideo?.bestDirectHttpUrl
                val preferredTitle = scan.pageTitle.ifBlank { "Vault_Video" }

                if (!directStreamUrl.isNullOrBlank() && !isAdaptiveManifest(directStreamUrl)) {
                    var manifestFallbackNeeded = false
                    var downloadCompleted = false

                    downloader.downloadVideoStream(
                        streamUrl = directStreamUrl,
                        refererOrigin = cleanOrigin,
                        suggestedTitle = preferredTitle,
                    ).collect { event ->
                        when (event) {
                            is VaultDownloadEvent.Starting -> {
                                _state.value = AutoVideoDownloadState.DownloadingDirect(
                                    streamUrl = event.streamUrl,
                                    pageUrl = cleanPageUrl,
                                    title = event.title,
                                    progress = 0f,
                                )
                            }
                            is VaultDownloadEvent.Progress -> {
                                _state.value = AutoVideoDownloadState.DownloadingDirect(
                                    streamUrl = directStreamUrl,
                                    pageUrl = cleanPageUrl,
                                    title = preferredTitle,
                                    progress = event.fraction.coerceIn(0f, 1f),
                                    bytesDownloaded = event.bytesDownloaded,
                                    totalBytes = event.totalBytes,
                                )
                            }
                            is VaultDownloadEvent.SavingToVault -> {
                                _state.value = AutoVideoDownloadState.DownloadingDirect(
                                    streamUrl = directStreamUrl,
                                    pageUrl = cleanPageUrl,
                                    title = event.title,
                                    progress = 0.99f,
                                    bytesDownloaded = event.sizeBytes,
                                    totalBytes = event.sizeBytes,
                                )
                            }
                            is VaultDownloadEvent.Completed -> {
                                observedHttpCode = 200
                                downloadCompleted = true
                                _state.value = AutoVideoDownloadState.Completed(
                                    title = event.title,
                                    savedLocation = event.savedLocation,
                                    pageUrl = cleanPageUrl,
                                )
                                keepTerminalStateAfterUnlock = true
                            }
                            is VaultDownloadEvent.Failed -> {
                                observedHttpCode = event.httpStatusCode
                                if (event.isManifestPlaylist && !event.isDrmOrRestricted) {
                                    manifestFallbackNeeded = true
                                } else {
                                    _state.value = AutoVideoDownloadState.Error(
                                        message = event.message,
                                        pageUrl = cleanPageUrl,
                                        isDrmOrRestricted = event.isDrmOrRestricted,
                                    )
                                    keepTerminalStateAfterUnlock = true
                                }
                            }
                        }
                    }

                    if (downloadCompleted || !manifestFallbackNeeded) {
                        return@launch
                    }
                }

                // Fallback for blob:, mediasource:, adaptive manifests, or missing direct <video src>
                val resolveReason = when {
                    bestVideo?.isBlobOrMediaSource == true ->
                        "Detected blob/MSE playback — resolving via sanitized page URL..."
                    directStreamUrl != null && isAdaptiveManifest(directStreamUrl) ->
                        "Adaptive manifest detected — resolving via sanitized page URL..."
                    else ->
                        "No direct progressive <video> src found — resolving via sanitized page URL..."
                }
                _state.value = AutoVideoDownloadState.ResolvingPage(
                    pageUrl = cleanPageUrl,
                    reason = resolveReason,
                )

                when (val resolved = pageStreamResolver.resolvePageUrl(cleanPageUrl, cleanOrigin)) {
                    is PageStreamResolution.Resolved -> {
                        val resolvedUrl = resolved.streamUrl.trim()
                        if (!resolvedUrl.startsWith("http://", ignoreCase = true) &&
                            !resolvedUrl.startsWith("https://", ignoreCase = true)
                        ) {
                            _state.value = AutoVideoDownloadState.Error(
                                message = "Resolver did not return a downloadable HTTP/HTTPS stream URL.",
                                pageUrl = cleanPageUrl,
                            )
                            keepTerminalStateAfterUnlock = true
                            return@launch
                        }

                        val resolvedTitle = resolved.title.ifBlank { preferredTitle }
                        downloader.downloadVideoStream(
                            streamUrl = resolvedUrl,
                            refererOrigin = cleanOrigin,
                            suggestedTitle = resolvedTitle,
                        ).collect { event ->
                            when (event) {
                                is VaultDownloadEvent.Starting -> {
                                    _state.value = AutoVideoDownloadState.DownloadingDirect(
                                        streamUrl = event.streamUrl,
                                        pageUrl = cleanPageUrl,
                                        title = event.title,
                                        progress = 0f,
                                    )
                                }
                                is VaultDownloadEvent.Progress -> {
                                    _state.value = AutoVideoDownloadState.DownloadingDirect(
                                        streamUrl = resolvedUrl,
                                        pageUrl = cleanPageUrl,
                                        title = resolvedTitle,
                                        progress = event.fraction.coerceIn(0f, 1f),
                                        bytesDownloaded = event.bytesDownloaded,
                                        totalBytes = event.totalBytes,
                                    )
                                }
                                is VaultDownloadEvent.SavingToVault -> {
                                    _state.value = AutoVideoDownloadState.DownloadingDirect(
                                        streamUrl = resolvedUrl,
                                        pageUrl = cleanPageUrl,
                                        title = event.title,
                                        progress = 0.99f,
                                        bytesDownloaded = event.sizeBytes,
                                        totalBytes = event.sizeBytes,
                                    )
                                }
                                is VaultDownloadEvent.Completed -> {
                                    observedHttpCode = 200
                                    _state.value = AutoVideoDownloadState.Completed(
                                        title = event.title,
                                        savedLocation = event.savedLocation,
                                        pageUrl = cleanPageUrl,
                                    )
                                    keepTerminalStateAfterUnlock = true
                                }
                                is VaultDownloadEvent.Failed -> {
                                    observedHttpCode = event.httpStatusCode
                                    _state.value = AutoVideoDownloadState.Error(
                                        message = event.message,
                                        pageUrl = cleanPageUrl,
                                        isDrmOrRestricted = event.isDrmOrRestricted,
                                    )
                                    keepTerminalStateAfterUnlock = true
                                }
                            }
                        }
                    }
                    is PageStreamResolution.UnsupportedOrDrm -> {
                        _state.value = AutoVideoDownloadState.Error(
                            message = resolved.reason,
                            pageUrl = cleanPageUrl,
                            isDrmOrRestricted = true,
                        )
                        keepTerminalStateAfterUnlock = true
                    }
                    is PageStreamResolution.Failed -> {
                        observedHttpCode = resolved.httpStatusCode
                        _state.value = AutoVideoDownloadState.Error(
                            message = resolved.reason,
                            pageUrl = cleanPageUrl,
                            isDrmOrRestricted = resolved.httpStatusCode == 403 || resolved.httpStatusCode == 401,
                        )
                        keepTerminalStateAfterUnlock = true
                    }
                }
            } catch (ce: CancellationException) {
                _state.value = AutoVideoDownloadState.Idle
                throw ce
            } catch (t: Throwable) {
                _state.value = AutoVideoDownloadState.Error(
                    message = t.message?.takeIf { it.isNotBlank() } ?: "Unexpected scan error.",
                    pageUrl = fallbackPageUrl,
                )
                keepTerminalStateAfterUnlock = true
            } finally {
                armCooldown(observedHttpCode)
                if (!keepTerminalStateAfterUnlock && _state.value !is AutoVideoDownloadState.Error &&
                    _state.value !is AutoVideoDownloadState.Completed
                ) {
                    val remaining = _cooldownRemainingSeconds.value
                    _state.value = if (remaining > 0) {
                        AutoVideoDownloadState.Cooldown(
                            remainingSeconds = remaining,
                            isRateLimitBackoff = System.currentTimeMillis() < backoffUntilMs,
                        )
                    } else {
                        AutoVideoDownloadState.Idle
                    }
                }
                if (executionMutex.isLocked) {
                    runCatching { executionMutex.unlock() }
                }
            }
        }
    }

    fun cancelActiveDownload() {
        activeJob?.cancel()
        activeJob = null
        downloader.cleanupTempFiles()
        val remaining = _cooldownRemainingSeconds.value
        _state.value = if (remaining > 0) {
            AutoVideoDownloadState.Cooldown(
                remainingSeconds = remaining,
                isRateLimitBackoff = System.currentTimeMillis() < backoffUntilMs,
            )
        } else {
            AutoVideoDownloadState.Idle
        }
    }

    fun dismissTerminalMessage() {
        if (_state.value is AutoVideoDownloadState.Completed || _state.value is AutoVideoDownloadState.Error) {
            val remaining = _cooldownRemainingSeconds.value
            _state.value = if (remaining > 0) {
                AutoVideoDownloadState.Cooldown(
                    remainingSeconds = remaining,
                    isRateLimitBackoff = System.currentTimeMillis() < backoffUntilMs,
                )
            } else {
                AutoVideoDownloadState.Idle
            }
        }
    }

    fun cleanup() {
        activeJob?.cancel()
        cooldownJob?.cancel()
        pendingScans.values.forEach { it.cancel() }
        pendingScans.clear()
        downloader.cleanupTempFiles()
        controllerScope.cancel()
    }

    private suspend fun performOnDemandScan(
        webView: WebView?,
        fallbackPageUrl: String,
    ): OnDemandPageScanResult {
        if (webView == null) {
            return OnDemandPageScanResult(
                pageUrl = fallbackPageUrl,
                pageTitle = "",
                bestVideo = null,
                anyDrmDetected = false,
            )
        }

        val scanId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingScans[scanId] = deferred

        val safeScanId = JSONObject.quote(scanId)
        val script = buildOnDemandScanScript(safeScanId)

        withContext(Dispatchers.Main) {
            runCatching {
                attachToWebView(webView)
                webView.evaluateJavascript(script, null)
            }.onFailure {
                pendingScans.remove(scanId)?.cancel()
            }
        }

        val rawPayload = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            deferred.await()
        }
        pendingScans.remove(scanId)

        val livePageUrl = withContext(Dispatchers.Main) {
            runCatching { webView.url }.getOrNull().orEmpty()
        }.ifBlank { fallbackPageUrl }
        val liveTitle = withContext(Dispatchers.Main) {
            runCatching { webView.title }.getOrNull().orEmpty()
        }

        if (rawPayload.isNullOrBlank()) {
            return OnDemandPageScanResult(
                pageUrl = livePageUrl,
                pageTitle = liveTitle,
                bestVideo = null,
                anyDrmDetected = false,
            )
        }

        return parseScanPayload(
            payloadJson = rawPayload,
            fallbackPageUrl = livePageUrl,
            fallbackTitle = liveTitle,
        )
    }

    private fun parseScanPayload(
        payloadJson: String,
        fallbackPageUrl: String,
        fallbackTitle: String,
    ): OnDemandPageScanResult {
        val root = runCatching { JSONObject(payloadJson) }.getOrNull()
            ?: return OnDemandPageScanResult(fallbackPageUrl, fallbackTitle, null, false)

        val pageUrl = root.optString("pageUrl").trim().ifBlank { fallbackPageUrl }
        val pageTitle = root.optString("pageTitle").trim().ifBlank { fallbackTitle }
        val drmFlag = root.optBoolean("anyDrm", false)
        val videosJson = root.optJSONArray("videos") ?: JSONArray()

        val candidates = ArrayList<OnDemandVideoCandidate>(videosJson.length())
        for (i in 0 until videosJson.length()) {
            val item = videosJson.optJSONObject(i) ?: continue
            val sourcesArr = item.optJSONArray("sources") ?: JSONArray()
            val sources = buildList {
                for (j in 0 until sourcesArr.length()) {
                    val s = sourcesArr.optString(j).trim()
                    if (s.isNotBlank()) add(s)
                }
            }
            candidates += OnDemandVideoCandidate(
                currentSrc = item.optString("currentSrc").trim(),
                src = item.optString("src").trim(),
                sourceUrls = sources,
                paused = item.optBoolean("paused", true),
                ended = item.optBoolean("ended", false),
                currentTime = item.optDouble("currentTime", 0.0),
                duration = item.optDouble("duration", 0.0),
                videoWidth = item.optInt("videoWidth", 0),
                videoHeight = item.optInt("videoHeight", 0),
                hasMediaKeys = item.optBoolean("hasMediaKeys", false),
                score = item.optDouble("score", 0.0),
            )
        }

        val best = candidates.maxByOrNull { it.score }
        return OnDemandPageScanResult(
            pageUrl = pageUrl,
            pageTitle = pageTitle,
            bestVideo = best,
            anyDrmDetected = drmFlag || (best?.hasMediaKeys == true),
        )
    }

    private fun armCooldown(httpStatusCode: Int?) {
        val now = System.currentTimeMillis()
        val isRateLimited = httpStatusCode == 429 || httpStatusCode == 403
        val cooldownDurationMs = if (isRateLimited) {
            consecutiveRateLimitHits = (consecutiveRateLimitHits + 1).coerceAtMost(4)
            val backoff = (15_000L shl (consecutiveRateLimitHits - 1)).coerceAtMost(120_000L)
            backoffUntilMs = now + backoff
            backoff
        } else {
            if (httpStatusCode != null && httpStatusCode in 200..399) {
                consecutiveRateLimitHits = 0
                backoffUntilMs = 0L
            }
            minCooldownMs
        }
        cooldownUntilMs = maxOf(cooldownUntilMs, now + cooldownDurationMs)
        startCooldownTicker()
    }

    private fun startCooldownTicker() {
        cooldownJob?.cancel()
        cooldownJob = controllerScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val remainingMs = (cooldownUntilMs - now).coerceAtLeast(0L)
                val remainingSec = ceil(remainingMs / 1000.0).toInt()
                _cooldownRemainingSeconds.value = remainingSec

                val currentState = _state.value
                if (currentState is AutoVideoDownloadState.Cooldown) {
                    if (remainingSec <= 0) {
                        _state.value = AutoVideoDownloadState.Idle
                    } else {
                        _state.value = currentState.copy(
                            remainingSeconds = remainingSec,
                            isRateLimitBackoff = now < backoffUntilMs,
                        )
                    }
                }
                if (remainingMs <= 0L) break
                delay(400L)
            }
        }
    }

    private fun isAdaptiveManifest(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains(".m3u8") || lower.contains(".mpd")
    }

    companion object {
        const val JS_BRIDGE_NAME = "BlackholeAutoVideoBridge"
        const val DEFAULT_COOLDOWN_MS = 5_000L
        private const val SCAN_TIMEOUT_MS = 3_500L

        private fun buildOnDemandScanScript(quotedScanId: String): String = """
            (function() {
              try {
                var results = [];
                var anyDrm = false;

                function inspectRoot(root) {
                  if (!root || !root.querySelectorAll) return;
                  var videos = root.querySelectorAll('video');
                  for (var i = 0; i < videos.length; i++) {
                    var v = videos[i];
                    var srcList = [];
                    var children = v.querySelectorAll('source');
                    for (var j = 0; j < children.length; j++) {
                      var sUrl = children[j].src || children[j].getAttribute('src') || '';
                      if (sUrl) srcList.push(sUrl);
                    }
                    var rect = v.getBoundingClientRect ? v.getBoundingClientRect() : { width: 0, height: 0 };
                    var area = Math.max(0, rect.width || 0) * Math.max(0, rect.height || 0);
                    var hasMediaKeys = false;
                    try { hasMediaKeys = !!v.mediaKeys; } catch (_) {}
                    if (hasMediaKeys) anyDrm = true;

                    var currentSrc = v.currentSrc || '';
                    var directSrc = v.src || '';
                    var isPaused = !!v.paused;
                    var isEnded = !!v.ended;
                    var isMuted = !!v.muted;
                    var currTime = Number.isFinite(v.currentTime) ? v.currentTime : 0;
                    var dur = Number.isFinite(v.duration) ? v.duration : 0;
                    var vw = v.videoWidth || 0;
                    var vh = v.videoHeight || 0;

                    var score = 0;
                    if (!isPaused && !isEnded) score += 1000;
                    if (currTime > 0.25) score += 250;
                    if (currentSrc || directSrc || srcList.length > 0) score += 200;
                    if (currentSrc && !currentSrc.startsWith('blob:')) score += 120;
                    if (!isMuted) score += 80;
                    if (vw > 0 && vh > 0) score += 60;
                    if (dur >= 5) score += Math.min(dur, 120);
                    if (area > 0) score += Math.min(area / 2000, 100);

                    results.push({
                      currentSrc: currentSrc,
                      src: directSrc,
                      sources: srcList,
                      paused: isPaused,
                      ended: isEnded,
                      muted: isMuted,
                      currentTime: currTime,
                      duration: dur,
                      videoWidth: vw,
                      videoHeight: vh,
                      hasMediaKeys: hasMediaKeys,
                      score: score
                    });
                  }

                  var all = root.querySelectorAll('*');
                  for (var k = 0; k < all.length; k++) {
                    if (all[k].shadowRoot) {
                      inspectRoot(all[k].shadowRoot);
                    }
                  }
                }

                inspectRoot(document);

                var iframes = document.querySelectorAll('iframe');
                for (var f = 0; f < iframes.length; f++) {
                  try {
                    var doc = iframes[f].contentDocument || (iframes[f].contentWindow && iframes[f].contentWindow.document);
                    if (doc) inspectRoot(doc);
                  } catch (_) {}
                }

                var payload = JSON.stringify({
                  pageUrl: window.location.href || '',
                  pageTitle: document.title || '',
                  anyDrm: anyDrm,
                  videos: results
                });
                if (window.$JS_BRIDGE_NAME && window.$JS_BRIDGE_NAME.onVideoScanResult) {
                  window.$JS_BRIDGE_NAME.onVideoScanResult($quotedScanId, payload);
                }
              } catch (e) {
                if (window.$JS_BRIDGE_NAME && window.$JS_BRIDGE_NAME.onVideoScanResult) {
                  window.$JS_BRIDGE_NAME.onVideoScanResult($quotedScanId, JSON.stringify({
                    pageUrl: window.location.href || '',
                    pageTitle: document.title || '',
                    anyDrm: false,
                    videos: []
                  }));
                }
              }
            })();
        """.trimIndent()
    }
}
