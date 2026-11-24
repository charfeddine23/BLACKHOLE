package com.example.vault.browser

import android.net.Uri
import android.webkit.CookieManager
import com.example.engine.UniversalDownloader
import com.example.engine.cookies.CookieVaultManager
import com.example.engine.extractor.MetaVideoPowerExtractor
import com.example.engine.extractor.VideoStreamExtractor
import com.example.engine.model.ExtractedStream
import com.example.engine.model.ExtractionResult
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
import com.example.util.formatQualityLabel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

data class ResolvedMediaCandidate(
    val id: String = UUID.randomUUID().toString(),
    val stream: ExtractedStream,
    val matchScore: Int,
    val matchReason: String,
    val isFromBlobFallback: Boolean = false,
    val isActiveVideoMatch: Boolean = false,
    val distinctGroupKey: String = stream.streamUrl.substringBefore("?").substringBefore("#"),
)

sealed interface CandidateResolutionOutcome {
    data class Resolved(
        val primary: ResolvedMediaCandidate,
        val fallbacks: List<ResolvedMediaCandidate>,
    ) : CandidateResolutionOutcome

    data class NeedsPicker(
        val candidates: List<ResolvedMediaCandidate>,
    ) : CandidateResolutionOutcome

    data class NeedsAuth(
        val reason: String,
    ) : CandidateResolutionOutcome

    data class Failed(
        val reason: String,
    ) : CandidateResolutionOutcome
}

/**
 * Deterministic WebView Media & Stream Resolver:
 * - Correlates active <video> telemetry (currentSrc, playback timestamp, resolution, ad filtering)
 *   with shouldInterceptRequest captures (.mp4, .m3u8, .mpd, audio streams).
 * - Resolves blob:/MSE players cleanly using active pageUrl + intercepted manifests + StreamResolver
 *   (UniversalDownloader / NewPipeExtractor fallback) with session Cookie + Referer headers.
 * - Detects genuine multi-video ambiguity and surfaces CandidatePicker only when distinct
 *   non-ad streams compete without a locked active playback match.
 */
class MediaCandidateResolver(
    private val okHttpClient: OkHttpClient,
    private val extractor: VideoStreamExtractor,
    private val universalDownloader: UniversalDownloader,
    private val cookieVault: CookieVaultManager,
) {

    suspend fun resolve(
        targetPageUrl: String,
        snapshot: VaultWebSnapshot?,
        interceptedMedia: List<InterceptedMediaRequest>,
        extraSniffedVideos: List<String> = emptyList(),
        extraSniffedAudios: List<String> = emptyList(),
        actionTimestampMs: Long = System.currentTimeMillis(),
    ): CandidateResolutionOutcome {
        val cleanPageUrl = targetPageUrl.trim()
        val platform = PlatformBadge.fromUrl(cleanPageUrl)
        val activeVideo = snapshot?.activeVideo
        val resolvedTitle = snapshot?.pageTitle
            ?.takeIf { it.isNotBlank() }
            ?: activeVideo?.pageTitle?.takeIf { it.isNotBlank() }

        val sessionHeaders = buildSessionHeaders(cleanPageUrl)
        val nonAdIntercepted = interceptedMedia.filterNot { it.isLikelyAd }
        val nonAdAudioUrls = (
            nonAdIntercepted.filter { it.mediaKind == InterceptedMediaKind.AUDIO_STREAM }.map { it.url } +
                extraSniffedAudios
            ).filterNot { VaultWebViewMonitor.isLikelyAdMediaUrl(it) }
            .distinct()

        // 1. Collect candidate raw URLs prioritized around active <video> & playback window
        val prioritizedVideoUrls = buildList {
            if (activeVideo != null && !activeVideo.isLikelyAd && !activeVideo.isBlobOrMediaSource) {
                activeVideo.bestDirectHttpUrl?.let { add(it) }
                activeVideo.sourceUrls.forEach { srcUrl ->
                    if (srcUrl.startsWith("http", ignoreCase = true) && !VaultWebViewMonitor.isLikelyAdMediaUrl(srcUrl)) {
                        add(srcUrl)
                    }
                }
            }
            // Intercepted requests ordered by temporal proximity to active playback / download tap
            val playAnchorMs = activeVideo?.let {
                maxOf(it.lastPlayEventMs, it.lastAdvanceMs)
            }?.takeIf { it > 0L } ?: actionTimestampMs

            nonAdIntercepted
                .filter { it.mediaKind != InterceptedMediaKind.AUDIO_STREAM }
                .sortedBy { kotlin.math.abs(it.capturedAtMs - playAnchorMs) }
                .forEach { add(it.url) }

            snapshot?.domCandidateUrls.orEmpty()
                .filterNot { VaultWebViewMonitor.isLikelyAdMediaUrl(it) }
                .forEach { add(it) }

            extraSniffedVideos
                .filterNot { VaultWebViewMonitor.isLikelyAdMediaUrl(it) }
                .forEach { add(it) }
        }.distinct().take(18)

        // 2. Run StreamResolver (UniversalDownloader: platform extractors + yt-dlp/NewPipe + offscreen sniffer)
        val routedOutcome = runCatching { universalDownloader.routeAndExtract(cleanPageUrl) }.getOrNull()
        val routedStreams = when (val res = routedOutcome?.result) {
            is ExtractionResult.Success -> res.candidates.ifEmpty { listOf(res.bestStream) }
            else -> emptyList()
        }.filterNot {
            VaultWebViewMonitor.isLikelyAdMediaUrl(it.streamUrl) ||
                MetaVideoPowerExtractor.isMetaAudioOnlyUrl(it.streamUrl)
        }.map { stream ->
            val merged = mergeStreamHeaders(stream, cleanPageUrl, sessionHeaders)
            if (resolvedTitle != null && stream.title.isBlank()) {
                merged.copy(title = resolvedTitle)
            } else {
                merged
            }
        }

        // 3. Probe live DOM & intercepted network streams (including HLS & MPD BaseURL fallback)
        val probedLiveStreams = probeCapturedUrls(
            pageUrl = routedOutcome?.canonicalUrl ?: cleanPageUrl,
            pageTitle = resolvedTitle,
            platform = routedOutcome?.platform ?: platform,
            candidateUrls = prioritizedVideoUrls,
            audioUrls = nonAdAudioUrls,
            baseHeaders = sessionHeaders,
        )

        val allStreams = (routedStreams + probedLiveStreams)
            .filterNot { it.streamUrl.startsWith("blob:", ignoreCase = true) }
            .filterNot { it.streamUrl.startsWith("mediasource:", ignoreCase = true) }
            .distinctBy { it.streamUrl }

        if (allStreams.isEmpty()) {
            return when (routedOutcome?.result) {
                is ExtractionResult.NeedsAuth -> CandidateResolutionOutcome.NeedsAuth(
                    "يتطلب هذا الفيديو تسجيل الدخول في المتصفح ثم الضغط مجدداً على التحميل.",
                )
                else -> {
                    val msg = if (activeVideo?.isBlobOrMediaSource == true) {
                        "الفيديو يستخدم بثاً مشفراً (blob/MSE) — شغّل الفيديو لثوانٍ حتى يتم التقاط البث (.m3u8) أو افتح صفحة الفيديو مباشرة."
                    } else {
                        "تعذر العثور على رابط فيديو قابل للتحميل — شغّل الفيديو أولاً ثم اضغط زر التحميل."
                    }
                    CandidateResolutionOutcome.Failed(msg)
                }
            }
        }

        // 4. Score & correlate every stream deterministically against active <video> & timing
        val interceptedMap = nonAdIntercepted.associateBy { it.url }
        val activePlayAnchorMs = activeVideo?.let { maxOf(it.lastPlayEventMs, it.lastAdvanceMs) }
            ?.takeIf { it > 0L } ?: actionTimestampMs
        val isBlobActive = activeVideo?.isBlobOrMediaSource == true
        val activeTokens = extractCorrelationTokens(activeVideo?.bestDirectHttpUrl)
        val pageHost = runCatching { Uri.parse(cleanPageUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("")

        val scored = allStreams.map { stream ->
            var score = 0
            val reasons = mutableListOf<String>()
            var exactVideoMatch = false

            // Direct match to active <video>.currentSrc / src
            val activeDirect = activeVideo?.bestDirectHttpUrl
            if (!activeDirect.isNullOrBlank() &&
                stream.streamUrl.substringBefore("#").equals(activeDirect.substringBefore("#"), ignoreCase = true)
            ) {
                score += 10_000
                exactVideoMatch = true
                reasons += "مطابق تماماً للفيديو قيد التشغيل"
            } else if (activeTokens.isNotEmpty()) {
                val streamTokens = extractCorrelationTokens(stream.streamUrl)
                val shared = activeTokens.intersect(streamTokens)
                if (shared.isNotEmpty()) {
                    score += 4_200 + (shared.size * 400).coerceAtMost(1_600)
                    exactVideoMatch = true
                    reasons += "مرتبط بعنصر الفيديو النشط"
                }
            }

            // StreamResolver (UniversalDownloader / yt-dlp) match on active pageUrl (crucial for blob:/MSE)
            val isFromStreamResolver = routedStreams.any { it.streamUrl == stream.streamUrl }
            if (isFromStreamResolver) {
                score += if (isBlobActive) 4_800 else 3_200
                if (isBlobActive) {
                    reasons += "بث مستخرج لصفحة blob/MSE النشطة"
                } else {
                    reasons += "مستخرج من رابط الصفحة"
                }
            }

            // Intercepted request timing correlation
            val intercepted = interceptedMap[stream.streamUrl]
            if (intercepted != null) {
                val deltaMs = kotlin.math.abs(intercepted.capturedAtMs - activePlayAnchorMs)
                if (deltaMs <= 30_000L) {
                    val recencyBoost = ((30_000L - deltaMs) / 10L).toInt().coerceIn(200, 3_000)
                    score += recencyBoost
                    reasons += if (intercepted.mediaKind == InterceptedMediaKind.HLS_M3U8) {
                        "بث HLS التُقط وقت التشغيل"
                    } else {
                        "التُقط وقت التشغيل"
                    }
                }
                if (pageHost.isNotBlank() && intercepted.referer.contains(pageHost, ignoreCase = true)) {
                    score += 900
                }
            }

            // Resolution & quality matching (ensuring highest quality wins within the same video)
            if (activeVideo != null && activeVideo.videoHeight > 0 && stream.resolutionHeight > 0) {
                val diff = kotlin.math.abs(activeVideo.videoHeight - stream.resolutionHeight)
                if (diff <= 40) {
                    score += 1_200
                    exactVideoMatch = true
                }
            }
            score += (stream.resolutionHeight.coerceIn(0, 4320) / 2)
            if (!stream.audioStreamUrl.isNullOrBlank() || MetaVideoPowerExtractor.isMetaProgressiveUrl(stream.streamUrl)) {
                score += 650
            }
            if (!stream.isHls) {
                score += 180
            }

            // Penalize short preview/ad paths when active video is longer
            val lowerUrl = stream.streamUrl.lowercase()
            if (activeVideo != null && activeVideo.durationSeconds > 25.0 &&
                (lowerUrl.contains("preview") || lowerUrl.contains("trailer") || lowerUrl.contains("thumb"))
            ) {
                score -= 3_500
            }

            val groupKey = deriveDistinctStreamGroupKey(stream.streamUrl)
            ResolvedMediaCandidate(
                stream = stream,
                matchScore = score,
                matchReason = reasons.firstOrNull() ?: "أعلى جودة متاحة (${stream.qualityLabel})",
                isFromBlobFallback = isBlobActive && (isFromStreamResolver || stream.isHls),
                isActiveVideoMatch = exactVideoMatch || (isBlobActive && isFromStreamResolver),
                distinctGroupKey = groupKey,
            )
        }.sortedWith(
            compareByDescending<ResolvedMediaCandidate> { it.matchScore }
                .thenByDescending { it.stream.resolutionHeight }
                .thenByDescending { it.stream.bitrateBps }
                .thenByDescending { it.stream.estimatedBytes },
        )

        val top = scored.first()
        val distinctGroups = scored.distinctBy { it.distinctGroupKey }

        // 5. Decide between deterministic auto-download vs CandidatePicker for genuine multi-video ambiguity
        val hasPlaybackLock = top.isActiveVideoMatch ||
            (activeVideo != null && !activeVideo.paused && activeVideo.isAdvancing && distinctGroups.size == 1)

        if (!hasPlaybackLock && distinctGroups.size >= 2) {
            val runnerUp = distinctGroups[1]
            val scoreGap = top.matchScore - runnerUp.matchScore
            val looksAmbiguous = scoreGap < 1_800 && top.matchScore < 9_000
            if (looksAmbiguous) {
                return CandidateResolutionOutcome.NeedsPicker(
                    candidates = distinctGroups.take(5),
                )
            }
        }

        val fallbacks = scored.drop(1).take(5)
        return CandidateResolutionOutcome.Resolved(
            primary = top,
            fallbacks = fallbacks,
        )
    }

    private fun buildSessionHeaders(pageUrl: String): MutableMap<String, String> {
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
        val liveCookie = runCatching { cookieManager?.getCookie(pageUrl) }.getOrNull()
        val vaultCookie = runCatching { cookieVault.getCookieHeaderForUrl(pageUrl) }.getOrNull()
        val combinedCookie = liveCookie?.takeIf { it.isNotBlank() } ?: vaultCookie

        return ClientSpoofer.buildHeadersForUrl(
            targetUrl = pageUrl,
            profile = ClientSpoofer.profiles.first(),
            refererOverride = pageUrl,
        ).toMutableMap().apply {
            put("Referer", pageUrl)
            if (!combinedCookie.isNullOrBlank()) {
                put("Cookie", combinedCookie)
            }
        }
    }

    private fun mergeStreamHeaders(
        stream: ExtractedStream,
        pageUrl: String,
        sessionHeaders: Map<String, String>,
    ): ExtractedStream {
        val streamCookie = runCatching { CookieManager.getInstance().getCookie(stream.streamUrl) }.getOrNull()
        val merged = sessionHeaders.toMutableMap().apply {
            putAll(stream.headers)
            if (!containsKey("Referer")) put("Referer", pageUrl)
            if (!streamCookie.isNullOrBlank()) {
                put("Cookie", streamCookie)
            }
        }
        return stream.copy(headers = merged)
    }

    private fun probeCapturedUrls(
        pageUrl: String,
        pageTitle: String?,
        platform: PlatformBadge,
        candidateUrls: List<String>,
        audioUrls: List<String>,
        baseHeaders: Map<String, String>,
    ): List<ExtractedStream> {
        if (candidateUrls.isEmpty()) return emptyList()
        val out = mutableListOf<ExtractedStream>()
        val fallbackAudio = audioUrls.firstOrNull()

        for (rawUrl in candidateUrls) {
            if (!rawUrl.startsWith("http://", ignoreCase = true) &&
                !rawUrl.startsWith("https://", ignoreCase = true)
            ) {
                continue
            }
            if (MetaVideoPowerExtractor.isMetaAudioOnlyUrl(rawUrl)) continue

            val streamCookie = runCatching { CookieManager.getInstance().getCookie(rawUrl) }.getOrNull()
            val perUrlHeaders = baseHeaders.toMutableMap().apply {
                if (!streamCookie.isNullOrBlank()) {
                    put("Cookie", streamCookie)
                }
            }

            if (rawUrl.substringBefore("?").endsWith(".mpd", ignoreCase = true)) {
                val mpdStreams = parseDashBaseUrls(rawUrl, pageTitle, platform, perUrlHeaders, fallbackAudio)
                out.addAll(mpdStreams)
                continue
            }

            val probed = extractor.probeDirectMediaOrHls(rawUrl, platform, perUrlHeaders)
            if (probed != null) {
                if (probed.estimatedBytes in 1L..16_383L) continue
                val needsAudio = fallbackAudio != null && !probed.isHls && (
                    rawUrl.contains("mime=video", ignoreCase = true) ||
                        platform == PlatformBadge.YOUTUBE ||
                        MetaVideoPowerExtractor.isMetaVideoOnlyDashUrl(rawUrl) ||
                        ((platform == PlatformBadge.INSTAGRAM || platform == PlatformBadge.FACEBOOK) &&
                            !MetaVideoPowerExtractor.isMetaProgressiveUrl(rawUrl))
                    )
                out.add(
                    probed.copy(
                        title = pageTitle?.takeIf { it.isNotBlank() } ?: probed.title,
                        headers = perUrlHeaders,
                        audioStreamUrl = if (needsAudio) fallbackAudio else probed.audioStreamUrl,
                    ),
                )
            } else if (extractor.looksLikeHlsUrl(rawUrl) || extractor.looksLikeDownloadableVideoUrl(rawUrl)) {
                val isHls = extractor.looksLikeHlsUrl(rawUrl)
                val height = extractor.inferResolutionHeight(rawUrl)
                val needsAudio = !isHls && fallbackAudio != null && (
                    rawUrl.contains("mime=video", ignoreCase = true) ||
                        platform == PlatformBadge.YOUTUBE ||
                        MetaVideoPowerExtractor.isMetaVideoOnlyDashUrl(rawUrl)
                    )
                out.add(
                    ExtractedStream(
                        streamUrl = rawUrl,
                        title = pageTitle?.takeIf { it.isNotBlank() } ?: extractor.deriveTitleFromUrl(pageUrl),
                        platform = platform,
                        qualityLabel = formatQualityLabel(height),
                        resolutionHeight = height,
                        mimeType = if (isHls) "application/vnd.apple.mpegurl" else extractor.resolveMimeType("", rawUrl),
                        estimatedBytes = -1L,
                        headers = perUrlHeaders,
                        audioStreamUrl = if (needsAudio) fallbackAudio else null,
                        isHls = isHls,
                    ),
                )
            }
        }
        return extractor.expandHlsCandidates(out)
    }

    private fun parseDashBaseUrls(
        mpdUrl: String,
        pageTitle: String?,
        platform: PlatformBadge,
        headers: Map<String, String>,
        fallbackAudio: String?,
    ): List<ExtractedStream> = runCatching {
        val reqBuilder = Request.Builder().url(mpdUrl).get()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val xml = okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching emptyList()
            resp.body?.string()?.take(128_000).orEmpty()
        }
        if (xml.isBlank()) return@runCatching emptyList()

        val baseUri = runCatching { java.net.URI(mpdUrl) }.getOrNull()
        val baseUrlMatches = Regex("""<BaseURL[^>]*>([^<]+)</BaseURL>""", RegexOption.IGNORE_CASE)
            .findAll(xml)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.endsWith(".mpd", ignoreCase = true) }
            .distinct()
            .toList()

        baseUrlMatches.mapNotNull { rawRef ->
            val resolved = runCatching { baseUri?.resolve(rawRef)?.toString() ?: rawRef }.getOrNull()
                ?: return@mapNotNull null
            if (!resolved.startsWith("http", ignoreCase = true)) return@mapNotNull null
            if (MetaVideoPowerExtractor.isMetaAudioOnlyUrl(resolved)) return@mapNotNull null
            val height = extractor.inferResolutionHeight(resolved)
            ExtractedStream(
                streamUrl = resolved,
                title = pageTitle?.takeIf { it.isNotBlank() } ?: extractor.deriveTitleFromUrl(mpdUrl),
                platform = platform,
                qualityLabel = formatQualityLabel(height),
                resolutionHeight = height,
                mimeType = "video/mp4",
                estimatedBytes = -1L,
                headers = headers,
                audioStreamUrl = fallbackAudio,
                isHls = extractor.looksLikeHlsUrl(resolved),
            )
        }
    }.getOrDefault(emptyList())

    private fun extractCorrelationTokens(url: String?): Set<String> {
        if (url.isNullOrBlank()) return emptySet()
        return url.substringAfter("://")
            .split('/', '?', '&', '=', '.', '-', '_')
            .map { it.trim().lowercase() }
            .filter { token ->
                token.length >= 6 &&
                    token !in IGNORED_CORRELATION_TOKENS &&
                    !token.all { it.isDigit() && token.length < 7 }
            }
            .toSet()
    }

    private fun deriveDistinctStreamGroupKey(url: String): String {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        // Group HLS resolution playlists / quality suffixes under one logical video when under the same path
        val parent = clean.substringBeforeLast("/", clean)
        val filePart = clean.substringAfterLast("/")
            .replace(Regex("""(1080p|720p|480p|360p|240p|hd|sd|low|high|master|playlist)"""), "")
        return "$parent/$filePart"
    }

    companion object {
        private val IGNORED_CORRELATION_TOKENS = setOf(
            "https",
            "http",
            "video",
            "videos",
            "stream",
            "streams",
            "index",
            "master",
            "playlist",
            "manifest",
            "playback",
            "media",
            "cdn",
            "watch",
        )
    }
}
