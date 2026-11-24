package com.example.vault.browser

import android.util.Log
import android.webkit.CookieManager
import com.example.engine.model.ExtractedStream
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
import com.example.util.formatQualityLabel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

@Serializable
data class YtDlpInspectRequest(
    @SerialName("page_url") val pageUrl: String,
    @SerialName("candidate_url") val candidateUrl: String? = null,
    @SerialName("cookies") val cookies: String? = null,
    @SerialName("referer") val referer: String? = null,
)

@Serializable
data class YtDlpFormatEntry(
    @SerialName("url") val url: String,
    @SerialName("quality_label") val qualityLabel: String = "",
    @SerialName("height") val height: Int = 0,
    @SerialName("ext") val ext: String = "mp4",
    @SerialName("filesize") val filesize: Long = -1L,
    @SerialName("is_hls") val isHls: Boolean = false,
    @SerialName("is_drm") val isDrm: Boolean = false,
)

@Serializable
data class YtDlpInspectResponse(
    @SerialName("title") val title: String? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
    @SerialName("duration") val duration: Double = 0.0,
    @SerialName("stream_url") val streamUrl: String? = null,
    @SerialName("quality_label") val qualityLabel: String? = null,
    @SerialName("ext") val ext: String? = null,
    @SerialName("is_hls") val isHls: Boolean = false,
    @SerialName("is_drm") val isDrm: Boolean = false,
    @SerialName("drm_scheme") val drmScheme: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("formats") val formats: List<YtDlpFormatEntry> = emptyList(),
)

/**
 * Retrofit contract for an optional companion yt-dlp extraction backend receiving live session
 * URL, candidate stream hint, Cookie header, and Referer header from the Vault WebView.
 */
interface YtDlpBackendApi {
    @POST("inspect")
    suspend fun inspectStreamMetadata(
        @Body request: YtDlpInspectRequest,
    ): YtDlpInspectResponse
}

data class InspectedStreamMetadata(
    val pageUrl: String,
    val resolvedStreamUrl: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Double,
    val qualityLabel: String,
    val mimeType: String,
    val referer: String,
    val cookies: String,
    val candidates: List<ResolvedMediaCandidate>,
    val selectedCandidateIndex: Int = 0,
    val isFromYtDlpBackend: Boolean = false,
    val debugTrace: String = "",
) {
    val selectedCandidate: ResolvedMediaCandidate?
        get() = candidates.getOrNull(selectedCandidateIndex) ?: candidates.firstOrNull()

    fun withSelectedIndex(index: Int): InspectedStreamMetadata {
        val safeIdx = index.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
        val cand = candidates.getOrNull(safeIdx) ?: return this
        return copy(
            selectedCandidateIndex = safeIdx,
            resolvedStreamUrl = cand.stream.streamUrl,
            title = cand.stream.title.ifBlank { title },
            thumbnailUrl = cand.thumbnailUrl ?: thumbnailUrl,
            durationSeconds = cand.durationSeconds.takeIf { it > 0.0 } ?: durationSeconds,
            qualityLabel = cand.stream.qualityLabel.ifBlank { qualityLabel },
            mimeType = cand.stream.mimeType.ifBlank { mimeType },
        )
    }
}

sealed interface VaultInspectorUiState {
    data object Idle : VaultInspectorUiState

    data class Inspecting(
        val pageUrl: String,
        val snapshot: VaultCaptureSnapshot? = null,
    ) : VaultInspectorUiState

    data class PreviewReady(
        val metadata: InspectedStreamMetadata,
    ) : VaultInspectorUiState

    data class Downloading(
        val metadata: InspectedStreamMetadata,
        val selectedCandidate: ResolvedMediaCandidate,
        val progress: Float = 0f,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = -1L,
        val speedBytesPerSec: Long = 0L,
        val stageLabel: String = "",
    ) : VaultInspectorUiState

    data class Completed(
        val title: String,
        val pageUrl: String,
    ) : VaultInspectorUiState

    data class Failed(
        val reason: String,
        val pageUrl: String,
        val lastUrlInput: String = pageUrl,
        val debugDetails: String = "",
    ) : VaultInspectorUiState

    data class UnsupportedOrDrm(
        val reason: String,
        val pageUrl: String,
        val detectedUrl: String = "",
        val debugDetails: String = "",
    ) : VaultInspectorUiState
}

/**
 * Coordinates Pre-Download inspection across:
 * 1) Optional yt-dlp backend via Retrofit ({ page_url, candidate_url, cookies, referer })
 * 2) On-device MediaCandidateResolver (active <video> telemetry, shouldInterceptRequest, UniversalDownloader)
 * 3) DRM / Widevine / encrypted manifest protection checks
 */
class StreamMetadataResolver(
    private val okHttpClient: OkHttpClient,
    private val candidateResolver: MediaCandidateResolver,
    private val backendBaseUrlProvider: () -> String = { "" },
) {
    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedBaseUrl: String = ""

    @Volatile
    private var cachedApi: YtDlpBackendApi? = null

    private fun getBackendApiOrNull(): YtDlpBackendApi? {
        val raw = backendBaseUrlProvider().trim()
        if (!raw.startsWith("http://", ignoreCase = true) &&
            !raw.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val normalized = if (raw.endsWith("/")) raw else "$raw/"
        val existing = cachedApi
        if (existing != null && cachedBaseUrl == normalized) return existing
        return runCatching {
            val retrofit = Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttpClient)
                .addConverterFactory(jsonFormat.asConverterFactory("application/json".toMediaType()))
                .build()
            retrofit.create(YtDlpBackendApi::class.java).also { api ->
                cachedBaseUrl = normalized
                cachedApi = api
            }
        }.getOrNull()
    }

    suspend fun inspect(
        targetUrl: String,
        snapshot: VaultCaptureSnapshot?,
        interceptedMedia: List<InterceptedMediaRequest>,
        extraSniffedVideos: List<String> = emptyList(),
        extraSniffedAudios: List<String> = emptyList(),
    ): VaultInspectorUiState {
        val cleanPageUrl = targetUrl.trim()
        if (!cleanPageUrl.startsWith("http://", ignoreCase = true) &&
            !cleanPageUrl.startsWith("https://", ignoreCase = true)
        ) {
            return VaultInspectorUiState.Failed(
                reason = "الرابط غير صالح — أدخل رابط http أو https أو افتح صفحة الفيديو أولاً.",
                pageUrl = cleanPageUrl,
                lastUrlInput = cleanPageUrl,
            )
        }

        val freshCookies = snapshot?.cookies?.takeIf { it.isNotBlank() }
            ?: runCatching { CookieManager.getInstance().getCookie(cleanPageUrl) }.getOrNull().orEmpty()
        val referer = snapshot?.referer?.takeIf { it.isNotBlank() } ?: cleanPageUrl
        val candidateHint = snapshot?.activeVideo?.bestDirectHttpUrl
            ?: interceptedMedia.firstOrNull { !it.isLikelyAd }?.url
            ?: extraSniffedVideos.firstOrNull()

        // 1. Check for known DRM streams or protected manifests right away
        val interceptedUrls = interceptedMedia.map { it.url } + extraSniffedVideos
        val drmHit = detectDrmProtection(cleanPageUrl, interceptedUrls, freshCookies, referer)
        if (drmHit != null) {
            val trace = buildDebugTrace(
                pageUrl = cleanPageUrl,
                resolvedUrl = drmHit.detectedUrl,
                cookies = freshCookies,
                referer = referer,
                sourceLabel = "DRM Detector (${drmHit.scheme})",
                candidateCount = interceptedUrls.size,
            )
            Log.w(VaultWebViewInspector.TAG, "Unsupported/DRM stream detected:\n$trace")
            return VaultInspectorUiState.UnsupportedOrDrm(
                reason = "هذا البث محمي بتشفير حقوق رقمية (${drmHit.scheme}) ولا يمكن تحميله أو فك تشفيره.",
                pageUrl = cleanPageUrl,
                detectedUrl = drmHit.detectedUrl,
                debugDetails = trace,
            )
        }

        // 2. Query optional Retrofit yt-dlp backend endpoint if configured
        val backendCandidates = mutableListOf<ResolvedMediaCandidate>()
        var backendTitle: String? = null
        var backendThumbnail: String? = null
        var backendDuration = 0.0
        var backendUsed = false
        val backendApi = getBackendApiOrNull()
        if (backendApi != null) {
            val backendResult = runCatching {
                backendApi.inspectStreamMetadata(
                    YtDlpInspectRequest(
                        pageUrl = cleanPageUrl,
                        candidateUrl = candidateHint,
                        cookies = freshCookies.ifBlank { null },
                        referer = referer,
                    ),
                )
            }
            val resp = backendResult.getOrNull()
            if (resp != null) {
                if (resp.isDrm || resp.formats.any { it.isDrm }) {
                    val scheme = resp.drmScheme?.takeIf { it.isNotBlank() } ?: "DRM"
                    val trace = buildDebugTrace(
                        pageUrl = cleanPageUrl,
                        resolvedUrl = resp.streamUrl.orEmpty(),
                        cookies = freshCookies,
                        referer = referer,
                        sourceLabel = "yt-dlp Retrofit Backend ($scheme)",
                        candidateCount = resp.formats.size,
                    )
                    return VaultInspectorUiState.UnsupportedOrDrm(
                        reason = "أبلغ خادم الاستخراج أن الفيديو محمي بنظام ($scheme) غير المدعوم للتحميل.",
                        pageUrl = cleanPageUrl,
                        detectedUrl = resp.streamUrl.orEmpty(),
                        debugDetails = trace,
                    )
                }

                backendTitle = resp.title?.takeIf { it.isNotBlank() }
                backendThumbnail = resp.thumbnail?.takeIf { it.isNotBlank() }
                backendDuration = resp.duration.takeIf { it > 0.0 } ?: 0.0
                val headers = buildSessionHeaderMap(cleanPageUrl, freshCookies, referer)
                val platform = PlatformBadge.fromUrl(cleanPageUrl)

                val allBackendFormats = buildList {
                    if (!resp.streamUrl.isNullOrBlank()) {
                        add(
                            YtDlpFormatEntry(
                                url = resp.streamUrl,
                                qualityLabel = resp.qualityLabel.orEmpty(),
                                ext = resp.ext ?: "mp4",
                                isHls = resp.isHls,
                            ),
                        )
                    }
                    addAll(resp.formats)
                }.filterNot { it.isDrm || it.url.startsWith("blob:", ignoreCase = true) }
                    .distinctBy { it.url }

                allBackendFormats.forEachIndexed { idx, fmt ->
                    val resolvedQuality = fmt.qualityLabel.ifBlank {
                        formatQualityLabel(fmt.height)
                    }
                    val stream = ExtractedStream(
                        streamUrl = fmt.url,
                        title = backendTitle ?: snapshot?.pageTitle ?: "فيديو من المتصفح",
                        platform = platform,
                        qualityLabel = resolvedQuality,
                        resolutionHeight = fmt.height,
                        mimeType = if (fmt.isHls) "application/vnd.apple.mpegurl" else "video/${fmt.ext.ifBlank { "mp4" }}",
                        estimatedBytes = fmt.filesize,
                        headers = headers,
                        isHls = fmt.isHls || fmt.url.contains(".m3u8", ignoreCase = true),
                    )
                    backendCandidates.add(
                        ResolvedMediaCandidate(
                            stream = stream,
                            matchScore = 15_000 - (idx * 100),
                            matchReason = "مستخرج عبر yt-dlp Backend",
                            isActiveVideoMatch = idx == 0,
                            thumbnailUrl = backendThumbnail ?: snapshot?.activeVideo?.posterUrl?.takeIf { it.isNotBlank() },
                            durationSeconds = backendDuration,
                        ),
                    )
                }
                backendUsed = backendCandidates.isNotEmpty()
            } else {
                Log.w(
                    VaultWebViewInspector.TAG,
                    "yt-dlp backend inspect failed; falling back to local resolver: ${backendResult.exceptionOrNull()?.message}",
                )
            }
        }

        // 3. Run deterministic on-device MediaCandidateResolver (DOM telemetry + intercepted requests + UniversalDownloader)
        val localOutcome = candidateResolver.resolveForInspection(
            targetPageUrl = cleanPageUrl,
            snapshot = snapshot?.toWebSnapshot(),
            interceptedMedia = interceptedMedia,
            extraSniffedVideos = extraSniffedVideos,
            extraSniffedAudios = extraSniffedAudios,
        )

        val localCandidates: List<ResolvedMediaCandidate> = when (localOutcome) {
            is CandidateResolutionOutcome.NeedsPicker -> localOutcome.candidates
            is CandidateResolutionOutcome.Resolved -> listOf(localOutcome.primary) + localOutcome.fallbacks
            is CandidateResolutionOutcome.NeedsAuth -> {
                if (backendCandidates.isEmpty()) {
                    return VaultInspectorUiState.Failed(
                        reason = localOutcome.reason,
                        pageUrl = cleanPageUrl,
                        lastUrlInput = cleanPageUrl,
                    )
                } else {
                    emptyList()
                }
            }
            is CandidateResolutionOutcome.Failed -> {
                if (backendCandidates.isEmpty()) {
                    return VaultInspectorUiState.Failed(
                        reason = localOutcome.reason,
                        pageUrl = cleanPageUrl,
                        lastUrlInput = cleanPageUrl,
                    )
                } else {
                    emptyList()
                }
            }
        }

        val mergedCandidates = (backendCandidates + localCandidates)
            .distinctBy { it.distinctGroupKey }
            .take(12)

        if (mergedCandidates.isEmpty()) {
            return VaultInspectorUiState.Failed(
                reason = "لم يتم العثور على رابط بث مباشر صالح — شغّل الفيديو في الصفحة ثم أعد الفحص أو الصق رابطاً يدوياً.",
                pageUrl = cleanPageUrl,
                lastUrlInput = cleanPageUrl,
            )
        }

        // Verify resolved manifest/stream URL is not DRM protected
        val primary = mergedCandidates.first()
        val manifestDrm = detectDrmProtection(
            pageUrl = cleanPageUrl,
            candidateUrls = listOf(primary.stream.streamUrl),
            cookies = freshCookies,
            referer = referer,
        )
        if (manifestDrm != null) {
            return VaultInspectorUiState.UnsupportedOrDrm(
                reason = "البث المستخرج محمي بنظام (${manifestDrm.scheme}) ولا يسمح بالتحميل المباشر.",
                pageUrl = cleanPageUrl,
                detectedUrl = manifestDrm.detectedUrl,
            )
        }

        val finalTitle = backendTitle
            ?: primary.stream.title.takeIf { it.isNotBlank() }
            ?: snapshot?.pageTitle?.takeIf { it.isNotBlank() }
            ?: "فيديو من متصفح الخزنة"
        val finalThumb = primary.thumbnailUrl
            ?: backendThumbnail
            ?: snapshot?.activeVideo?.posterUrl?.takeIf { it.isNotBlank() }
            ?: snapshot?.pagePosterUrl?.takeIf { it.isNotBlank() }
        val finalDuration = primary.durationSeconds.takeIf { it > 0.0 }
            ?: backendDuration.takeIf { it > 0.0 }
            ?: snapshot?.activeVideo?.durationSeconds?.takeIf { it > 0.0 }
            ?: 0.0

        val trace = buildDebugTrace(
            pageUrl = cleanPageUrl,
            resolvedUrl = primary.stream.streamUrl,
            cookies = freshCookies,
            referer = referer,
            sourceLabel = if (backendUsed) "yt-dlp Retrofit + Local Correlator" else "Local Inspector + StreamResolver",
            candidateCount = mergedCandidates.size,
        )
        Log.d(VaultWebViewInspector.TAG, "Pre-Download Inspection Ready:\n$trace")

        val metadata = InspectedStreamMetadata(
            pageUrl = cleanPageUrl,
            resolvedStreamUrl = primary.stream.streamUrl,
            title = finalTitle,
            thumbnailUrl = finalThumb,
            durationSeconds = finalDuration,
            qualityLabel = primary.stream.qualityLabel,
            mimeType = primary.stream.mimeType,
            referer = referer,
            cookies = freshCookies,
            candidates = mergedCandidates,
            selectedCandidateIndex = 0,
            isFromYtDlpBackend = backendUsed,
            debugTrace = trace,
        )
        return VaultInspectorUiState.PreviewReady(metadata = metadata)
    }

    private fun buildSessionHeaderMap(
        pageUrl: String,
        cookies: String,
        referer: String,
    ): Map<String, String> {
        return ClientSpoofer.buildHeadersForUrl(
            targetUrl = pageUrl,
            profile = ClientSpoofer.profiles.first(),
            refererOverride = referer,
        ).toMutableMap().apply {
            put("Referer", referer)
            if (cookies.isNotBlank()) put("Cookie", cookies)
        }
    }

    private data class DrmHit(val scheme: String, val detectedUrl: String)

    private fun detectDrmProtection(
        pageUrl: String,
        candidateUrls: List<String>,
        cookies: String,
        referer: String,
    ): DrmHit? {
        val lowerPage = pageUrl.lowercase()
        if (DRM_HOST_HINTS.any { lowerPage.contains(it) }) {
            return DrmHit("Widevine / Protected Platform", pageUrl)
        }
        val licenseUrl = candidateUrls.firstOrNull { url ->
            val l = url.lowercase()
            l.contains("widevine") || l.contains("playready") || l.contains("fairplay") || l.contains("/drm/")
        }
        if (licenseUrl != null) {
            return DrmHit("Widevine / FairPlay DRM", licenseUrl)
        }

        // Quick manifest DRM tag inspection for .mpd / .m3u8 URLs
        val manifestUrl = candidateUrls.firstOrNull { url ->
            val path = url.lowercase().substringBefore("?")
            path.endsWith(".mpd") || path.endsWith(".m3u8")
        } ?: return null

        val manifestSnippet = runCatching {
            val req = Request.Builder().url(manifestUrl).get().apply {
                header("Referer", referer)
                if (cookies.isNotBlank()) header("Cookie", cookies)
            }.build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.take(24_000).orEmpty() else ""
            }
        }.getOrDefault("")

        if (manifestSnippet.isNotBlank()) {
            val lowerManifest = manifestSnippet.lowercase()
            if (lowerManifest.contains("urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed") ||
                lowerManifest.contains("<contentprotection") ||
                lowerManifest.contains("com.widevine.alpha")
            ) {
                return DrmHit("Widevine CENC", manifestUrl)
            }
            if (lowerManifest.contains("#ext-x-key") &&
                (lowerManifest.contains("sample-aes") || lowerManifest.contains("skd://") || lowerManifest.contains("fairplay"))
            ) {
                return DrmHit("HLS SAMPLE-AES / FairPlay", manifestUrl)
            }
        }
        return null
    }

    private fun buildDebugTrace(
        pageUrl: String,
        resolvedUrl: String,
        cookies: String,
        referer: String,
        sourceLabel: String,
        candidateCount: Int,
    ): String = buildString {
        appendLine("source=$sourceLabel")
        appendLine("pageUrl=$pageUrl")
        appendLine("resolvedUrl=$resolvedUrl")
        appendLine("referer=$referer")
        appendLine("cookieHeader=${if (cookies.isNotBlank()) "present (${cookies.length} chars)" else "empty"}")
        appendLine("candidates=$candidateCount")
    }.trim()

    companion object {
        private val DRM_HOST_HINTS = listOf(
            "netflix.com",
            "primevideo.com",
            "disneyplus.com",
            "hbomax.com",
            "max.com",
            "shahid.mbc.net",
        )
    }
}
