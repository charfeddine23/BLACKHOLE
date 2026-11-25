package com.example.vault.browser

import android.net.Uri
import android.util.Log
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
import org.json.JSONObject
import retrofit2.HttpException
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
 * Retrofit contract for an optional companion yt-dlp extraction backend receiving ONLY
 * sanitized public URL, candidate stream hint, and clean Origin Referer (never user cookies).
 */
interface YtDlpBackendApi {
    @POST("inspect")
    suspend fun inspectStreamMetadata(
        @Body request: YtDlpInspectRequest,
    ): YtDlpInspectResponse
}

data class PublicLinkPreviewResult(
    val sanitizedUrl: String,
    val cleanOrigin: String,
    val strippedParamCount: Int,
    val title: String = "",
    val description: String = "",
    val thumbnailUrl: String? = null,
    val authorName: String? = null,
    val providerName: String = "",
    val sourceMethod: String = "OpenGraph",
    val httpStatusCode: Int? = null,
    val errorMessage: String? = null,
)

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
    val previewProvider: String = "",
    val strippedTrackingParams: Int = 0,
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
 * Cleanly separates browsing from public metadata inspection:
 * - Sanitizes URLs via UrlSanitizer (strips tracking/session tokens)
 * - ZERO cookie extraction or forwarding (cookies = null / empty everywhere)
 * - Uses official public oEmbed + cookie-less OpenGraph metadata before falling back to local candidate resolver
 * - Reports HTTP 429/403 status codes to RequestRateLimiter for automatic exponential backoff
 */
class StreamMetadataResolver(
    private val okHttpClient: OkHttpClient,
    private val candidateResolver: MediaCandidateResolver,
    private val backendBaseUrlProvider: () -> String = { "" },
    private val rateLimiter: RequestRateLimiter? = null,
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

    /**
     * Fetches public link metadata strictly without cookies using official oEmbed endpoints
     * where supported, or clean OpenGraph <meta> tags as fallback.
     */
    fun fetchPublicLinkPreview(rawUrl: String): PublicLinkPreviewResult {
        val sanitized = UrlSanitizer.sanitizePublicUrl(rawUrl)
        if (!sanitized.isValidPublicHttpUrl) {
            return PublicLinkPreviewResult(
                sanitizedUrl = "",
                cleanOrigin = "",
                strippedParamCount = 0,
                errorMessage = "أدخل رابط http أو https عاماً وصالحاً للمعاينة.",
            )
        }

        val cleanUrl = sanitized.sanitizedUrl
        // 1. Try official public oEmbed endpoint when available (zero scraping, zero cookies)
        val oEmbedUrl = buildOfficialOEmbedUrl(cleanUrl)
        if (oEmbedUrl != null) {
            val oEmbedResult = executeCleanGet(oEmbedUrl, accept = "application/json")
            if (oEmbedResult.statusCode == 429 || oEmbedResult.statusCode == 403) {
                rateLimiter?.recordHttpStatus(oEmbedResult.statusCode)
                return PublicLinkPreviewResult(
                    sanitizedUrl = cleanUrl,
                    cleanOrigin = sanitized.cleanOrigin,
                    strippedParamCount = sanitized.strippedParamCount,
                    httpStatusCode = oEmbedResult.statusCode,
                    errorMessage = "تم تقييد الطلبات مؤقتاً من الخادم (HTTP ${oEmbedResult.statusCode}) — تم تفعيل التهدئة التلقائية.",
                )
            }
            if (oEmbedResult.body.isNotBlank() && oEmbedResult.statusCode in 200..299) {
                val parsed = runCatching { JSONObject(oEmbedResult.body) }.getOrNull()
                if (parsed != null) {
                    val title = parsed.optString("title").trim()
                    val thumb = parsed.optString("thumbnail_url").trim().takeIf { it.startsWith("http", ignoreCase = true) }
                    val author = parsed.optString("author_name").trim().takeIf { it.isNotBlank() }
                    val provider = parsed.optString("provider_name").trim().ifBlank {
                        PlatformBadge.fromUrl(cleanUrl).label
                    }
                    if (title.isNotBlank() || thumb != null) {
                        return PublicLinkPreviewResult(
                            sanitizedUrl = cleanUrl,
                            cleanOrigin = sanitized.cleanOrigin,
                            strippedParamCount = sanitized.strippedParamCount,
                            title = title,
                            thumbnailUrl = thumb,
                            authorName = author,
                            providerName = provider,
                            sourceMethod = "Official oEmbed",
                            httpStatusCode = oEmbedResult.statusCode,
                        )
                    }
                }
            }
        }

        // 2. Clean cookie-less OpenGraph meta inspection
        val ogResult = executeCleanGet(cleanUrl, accept = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
        if (ogResult.statusCode == 429 || ogResult.statusCode == 403) {
            rateLimiter?.recordHttpStatus(ogResult.statusCode)
            return PublicLinkPreviewResult(
                sanitizedUrl = cleanUrl,
                cleanOrigin = sanitized.cleanOrigin,
                strippedParamCount = sanitized.strippedParamCount,
                httpStatusCode = ogResult.statusCode,
                errorMessage = "رفض الخادم الطلب المؤقت (HTTP ${ogResult.statusCode}) — يرجى الانتظار خلال فترة التهدئة.",
            )
        }
        val html = ogResult.body
        val ogTitle = extractMetaContent(html, "og:title")
            ?: extractMetaContent(html, "twitter:title")
            ?: extractHtmlTitle(html)
            ?: PlatformBadge.fromUrl(cleanUrl).label
        val ogImage = extractMetaContent(html, "og:image")
            ?: extractMetaContent(html, "twitter:image")
        val ogDesc = extractMetaContent(html, "og:description")
            ?: extractMetaContent(html, "description")
            ?: ""
        val ogSite = extractMetaContent(html, "og:site_name")
            ?: runCatching { Uri.parse(cleanUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("")

        return PublicLinkPreviewResult(
            sanitizedUrl = cleanUrl,
            cleanOrigin = sanitized.cleanOrigin,
            strippedParamCount = sanitized.strippedParamCount,
            title = ogTitle,
            description = ogDesc,
            thumbnailUrl = ogImage?.takeIf { it.startsWith("http", ignoreCase = true) },
            providerName = ogSite,
            sourceMethod = "OpenGraph (Cookie-Free)",
            httpStatusCode = ogResult.statusCode,
            errorMessage = if (ogResult.statusCode != null && ogResult.statusCode >= 400 && ogTitle.isBlank()) {
                "تعذر جلب المعاينة (HTTP ${ogResult.statusCode})"
            } else {
                null
            },
        )
    }

    suspend fun inspect(
        targetUrl: String,
        snapshot: VaultCaptureSnapshot?,
        interceptedMedia: List<InterceptedMediaRequest>,
        extraSniffedVideos: List<String> = emptyList(),
        extraSniffedAudios: List<String> = emptyList(),
    ): VaultInspectorUiState {
        val sanitized = UrlSanitizer.sanitizePublicUrl(targetUrl)
        if (!sanitized.isValidPublicHttpUrl) {
            return VaultInspectorUiState.Failed(
                reason = "الرابط غير صالح — أدخل رابط http أو https عاماً أو افتح صفحة الفيديو أولاً.",
                pageUrl = targetUrl.trim(),
                lastUrlInput = targetUrl.trim(),
            )
        }

        val cleanPageUrl = sanitized.sanitizedUrl
        val referer = sanitized.cleanOrigin.ifBlank { cleanPageUrl }
        val candidateHint = snapshot?.activeVideo?.bestDirectHttpUrl
            ?: interceptedMedia.firstOrNull { !it.isLikelyAd }?.url
            ?: extraSniffedVideos.firstOrNull()

        // 1. Check for known DRM streams or protected manifests right away (cookie-free)
        val interceptedUrls = interceptedMedia.map { it.url } + extraSniffedVideos
        val drmHit = detectDrmProtection(cleanPageUrl, interceptedUrls, referer)
        if (drmHit != null) {
            val trace = buildDebugTrace(
                pageUrl = cleanPageUrl,
                resolvedUrl = drmHit.detectedUrl,
                referer = referer,
                sourceLabel = "DRM Detector (${drmHit.scheme})",
                candidateCount = interceptedUrls.size,
                strippedParams = sanitized.strippedParamCount,
            )
            Log.w(VaultWebViewInspector.TAG, "Unsupported/DRM stream detected:\n$trace")
            return VaultInspectorUiState.UnsupportedOrDrm(
                reason = "هذا البث محمي بتشفير حقوق رقمية (${drmHit.scheme}) ولا يمكن تحميله أو فك تشفيره.",
                pageUrl = cleanPageUrl,
                detectedUrl = drmHit.detectedUrl,
                debugDetails = trace,
            )
        }

        // 2. Fetch public oEmbed / cookie-less OpenGraph metadata preview
        val publicPreview = runCatching { fetchPublicLinkPreview(cleanPageUrl) }.getOrNull()
        if (publicPreview?.httpStatusCode == 429 || publicPreview?.httpStatusCode == 403) {
            return VaultInspectorUiState.Failed(
                reason = publicPreview.errorMessage
                    ?: "تلقى التطبيق قيود حماية (HTTP ${publicPreview.httpStatusCode}) — انتظر انتهاء فترة التهدئة التلقائية.",
                pageUrl = cleanPageUrl,
                lastUrlInput = cleanPageUrl,
            )
        }

        // 3. Query optional Retrofit yt-dlp backend endpoint (strictly zero cookies forwarded)
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
                        cookies = null,
                        referer = referer,
                    ),
                )
            }
            val ex = backendResult.exceptionOrNull()
            if (ex is HttpException && (ex.code() == 429 || ex.code() == 403)) {
                rateLimiter?.recordHttpStatus(ex.code())
                return VaultInspectorUiState.Failed(
                    reason = "خادم الاستخراج أرجع HTTP ${ex.code()} — تم تفعيل التهدئة التلقائية.",
                    pageUrl = cleanPageUrl,
                    lastUrlInput = cleanPageUrl,
                )
            }
            val resp = backendResult.getOrNull()
            if (resp != null) {
                if (resp.isDrm || resp.formats.any { it.isDrm }) {
                    val scheme = resp.drmScheme?.takeIf { it.isNotBlank() } ?: "DRM"
                    val trace = buildDebugTrace(
                        pageUrl = cleanPageUrl,
                        resolvedUrl = resp.streamUrl.orEmpty(),
                        referer = referer,
                        sourceLabel = "yt-dlp Retrofit Backend ($scheme)",
                        candidateCount = resp.formats.size,
                        strippedParams = sanitized.strippedParamCount,
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
                val headers = buildCookieFreeHeaderMap(cleanPageUrl, referer)
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
                        title = backendTitle ?: publicPreview?.title?.takeIf { it.isNotBlank() }
                            ?: snapshot?.pageTitle ?: "فيديو من المتصفح",
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
                            matchReason = "مستخرج عبر yt-dlp Backend (بدون كوكيز)",
                            isActiveVideoMatch = idx == 0,
                            thumbnailUrl = backendThumbnail
                                ?: publicPreview?.thumbnailUrl
                                ?: snapshot?.activeVideo?.posterUrl?.takeIf { it.isNotBlank() },
                            durationSeconds = backendDuration,
                        ),
                    )
                }
                backendUsed = backendCandidates.isNotEmpty()
            } else if (ex != null) {
                Log.w(
                    VaultWebViewInspector.TAG,
                    "yt-dlp backend inspect failed; falling back to local resolver: ${ex.message}",
                )
            }
        }

        // 4. Run deterministic on-device MediaCandidateResolver (cookie-free, active <video> + intercepted + UniversalDownloader)
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
                        reason = "يتطلب هذا الرابط جلسة خاصة غير مدعومة — استخدم فقط الروابط العامة المتاحة بدون تسجيل دخول.",
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
                reason = "لم يتم العثور على رابط بث عام صالح — تأكد أن الفيديو عام وقابل للتشغيل ثم أعد الفحص بعد فترة التهدئة.",
                pageUrl = cleanPageUrl,
                lastUrlInput = cleanPageUrl,
            )
        }

        // Verify resolved manifest/stream URL is not DRM protected
        val primary = mergedCandidates.first()
        val manifestDrm = detectDrmProtection(
            pageUrl = cleanPageUrl,
            candidateUrls = listOf(primary.stream.streamUrl),
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
            ?: publicPreview?.title?.takeIf { it.isNotBlank() }
            ?: primary.stream.title.takeIf { it.isNotBlank() }
            ?: snapshot?.pageTitle?.takeIf { it.isNotBlank() }
            ?: "فيديو من متصفح الخزنة"
        val finalThumb = primary.thumbnailUrl
            ?: backendThumbnail
            ?: publicPreview?.thumbnailUrl
            ?: snapshot?.activeVideo?.posterUrl?.takeIf { it.isNotBlank() }
            ?: snapshot?.pagePosterUrl?.takeIf { it.isNotBlank() }
        val finalDuration = primary.durationSeconds.takeIf { it > 0.0 }
            ?: backendDuration.takeIf { it > 0.0 }
            ?: snapshot?.activeVideo?.durationSeconds?.takeIf { it > 0.0 }
            ?: 0.0

        val sourceLabel = buildString {
            append(if (backendUsed) "yt-dlp + Local" else "Local Correlator")
            if (publicPreview != null && publicPreview.sourceMethod.isNotBlank()) {
                append(" + ${publicPreview.sourceMethod}")
            }
        }
        val trace = buildDebugTrace(
            pageUrl = cleanPageUrl,
            resolvedUrl = primary.stream.streamUrl,
            referer = referer,
            sourceLabel = sourceLabel,
            candidateCount = mergedCandidates.size,
            strippedParams = sanitized.strippedParamCount,
        )
        Log.d(VaultWebViewInspector.TAG, "Pre-Download Inspection Ready (Cookie-Free):\n$trace")

        val metadata = InspectedStreamMetadata(
            pageUrl = cleanPageUrl,
            resolvedStreamUrl = primary.stream.streamUrl,
            title = finalTitle,
            thumbnailUrl = finalThumb,
            durationSeconds = finalDuration,
            qualityLabel = primary.stream.qualityLabel,
            mimeType = primary.stream.mimeType,
            referer = referer,
            cookies = "",
            candidates = mergedCandidates,
            selectedCandidateIndex = 0,
            isFromYtDlpBackend = backendUsed,
            debugTrace = trace,
            previewProvider = publicPreview?.sourceMethod.orEmpty(),
            strippedTrackingParams = sanitized.strippedParamCount,
        )
        return VaultInspectorUiState.PreviewReady(metadata = metadata)
    }

    private fun buildCookieFreeHeaderMap(
        pageUrl: String,
        referer: String,
    ): Map<String, String> {
        return ClientSpoofer.buildHeadersForUrl(
            targetUrl = pageUrl,
            profile = ClientSpoofer.profiles.first(),
            refererOverride = referer,
        ).filterKeys { !it.equals("Cookie", ignoreCase = true) }
            .toMutableMap()
            .apply {
                put("User-Agent", SafeWebViewConfig.STANDARD_ANDROID_CHROME_USER_AGENT)
                put("Referer", referer)
            }
    }

    private data class CleanHttpResponse(
        val statusCode: Int?,
        val body: String,
    )

    private fun executeCleanGet(url: String, accept: String): CleanHttpResponse {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", SafeWebViewConfig.STANDARD_ANDROID_CHROME_USER_AGENT)
                .header("Accept", accept)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val text = if (resp.isSuccessful) resp.body?.string()?.take(96_000).orEmpty() else ""
                CleanHttpResponse(statusCode = resp.code, body = text)
            }
        }.getOrElse {
            CleanHttpResponse(statusCode = null, body = "")
        }
    }

    private fun buildOfficialOEmbedUrl(cleanUrl: String): String? {
        val host = runCatching { Uri.parse(cleanUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        val encoded = Uri.encode(cleanUrl)
        return when {
            host.contains("youtube.com") || host.contains("youtu.be") ->
                "https://www.youtube.com/oembed?format=json&url=$encoded"
            host.contains("vimeo.com") ->
                "https://vimeo.com/api/oembed.json?url=$encoded"
            host.contains("tiktok.com") ->
                "https://www.tiktok.com/oembed?url=$encoded"
            else -> null
        }
    }

    private fun extractMetaContent(html: String, propertyOrName: String): String? {
        if (html.isBlank()) return null
        val escaped = Regex.escape(propertyOrName)
        val p1 = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']$escaped["'][^>]+content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        val p2 = Regex(
            """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+(?:property|name)\s*=\s*["']$escaped["']""",
            RegexOption.IGNORE_CASE,
        )
        val raw = p1.find(html)?.groupValues?.getOrNull(1)
            ?: p2.find(html)?.groupValues?.getOrNull(1)
        return raw?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractHtmlTitle(html: String): String? {
        if (html.isBlank()) return null
        return Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private data class DrmHit(val scheme: String, val detectedUrl: String)

    private fun detectDrmProtection(
        pageUrl: String,
        candidateUrls: List<String>,
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

        // Quick cookie-free manifest DRM tag inspection for .mpd / .m3u8 URLs
        val manifestUrl = candidateUrls.firstOrNull { url ->
            val path = url.lowercase().substringBefore("?")
            path.endsWith(".mpd") || path.endsWith(".m3u8")
        } ?: return null

        val manifestSnippet = runCatching {
            val req = Request.Builder().url(manifestUrl).get()
                .header("User-Agent", SafeWebViewConfig.STANDARD_ANDROID_CHROME_USER_AGENT)
                .header("Referer", referer)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                rateLimiter?.recordHttpStatus(resp.code)
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
        referer: String,
        sourceLabel: String,
        candidateCount: Int,
        strippedParams: Int,
    ): String = buildString {
        appendLine("source=$sourceLabel")
        appendLine("sanitizedPageUrl=$pageUrl (strippedParams=$strippedParams)")
        appendLine("resolvedUrl=$resolvedUrl")
        appendLine("refererOrigin=$referer")
        appendLine("cookieHeader=disabled (ephemeral session-isolated)")
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

