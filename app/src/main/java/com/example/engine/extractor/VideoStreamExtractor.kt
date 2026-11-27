package com.example.engine.extractor

import com.example.engine.cookies.CookieVaultManager
import com.example.engine.model.ExtractedStream
import com.example.engine.model.ExtractionResult
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
import com.example.util.formatQualityLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class VideoStreamExtractor(
    private val client: OkHttpClient = defaultOkHttpClient(),
    private val webViewSniffer: WebViewStreamSniffer? = null,
    private val cookieVault: CookieVaultManager? = null,
) {

    private val youtubePower by lazy { YoutubePowerExtractor(client, cookieVault) }
    private val metaPower by lazy { MetaVideoPowerExtractor(client, cookieVault) }

    suspend fun extractHighestQuality(rawUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext ExtractionResult.Error("Invalid URL scheme. Expected http:// or https://")
        }

        val platform = PlatformBadge.fromUrl(cleanUrl)
        var sawAuthChallenge = false

        try {
            // Strategy 1: Direct Progressive or HLS (.m3u8) Probe with rotating client signatures
            for (profile in ClientSpoofer.profiles) {
                val headers = ClientSpoofer.buildHeadersForUrl(cleanUrl, profile)
                val direct = probeDirectMediaOrHls(cleanUrl, platform, headers)
                if (direct != null) {
                    return@withContext ExtractionResult.Success(
                        bestStream = direct,
                        candidates = listOf(direct),
                    )
                }
            }

            // Strategy 2: Specialized Multi-Tier Platform Engines
            val platformStreams = when (platform) {
                PlatformBadge.YOUTUBE -> {
                    val ytResult = youtubePower.extract(cleanUrl)
                    if (ytResult.authChallenge) sawAuthChallenge = true
                    ytResult.streams
                }
                PlatformBadge.TIKTOK -> {
                    val ttResult = extractTikTokStreams(cleanUrl)
                    if (ttResult.authChallenge) sawAuthChallenge = true
                    ttResult.streams
                }
                PlatformBadge.INSTAGRAM -> {
                    val igResult = metaPower.extractInstagram(cleanUrl)
                    if (igResult.authChallenge) sawAuthChallenge = true
                    igResult.streams
                }
                PlatformBadge.FACEBOOK -> {
                    val fbResult = metaPower.extractFacebook(cleanUrl)
                    if (fbResult.authChallenge) sawAuthChallenge = true
                    fbResult.streams
                }
                PlatformBadge.X_TWITTER -> extractXTwitterStreams(cleanUrl)
                PlatformBadge.VIMEO -> extractVimeoStreams(cleanUrl)
                PlatformBadge.REDDIT -> extractRedditStreams(cleanUrl)
                else -> emptyList()
            }
            if (platformStreams.isNotEmpty()) {
                val expanded = expandHlsCandidates(platformStreams)
                val sorted = rankBestStreams(expanded)
                return@withContext ExtractionResult.Success(
                    bestStream = sorted.first(),
                    candidates = sorted,
                )
            }

            // Strategy 3: Webpage HTML5 / OpenGraph / JSON-LD / Player Config (skipped for Facebook to prevent grabbing unrelated feed clips)
            val allHtmlCandidates = mutableListOf<ExtractedStream>()
            if (platform != PlatformBadge.FACEBOOK) {
                for (profile in ClientSpoofer.profiles) {
                    val headers = ClientSpoofer.buildHeadersForUrl(cleanUrl, profile)
                    val probe = extractFromWebPage(cleanUrl, platform, headers)
                    if (probe.authChallenge) {
                        sawAuthChallenge = true
                    }
                    if (probe.streams.isNotEmpty()) {
                        allHtmlCandidates.addAll(probe.streams)
                        break
                    }
                }
            }

            if (allHtmlCandidates.isNotEmpty()) {
                val expanded = expandHlsCandidates(allHtmlCandidates)
                val sorted = rankBestStreams(expanded)
                return@withContext ExtractionResult.Success(
                    bestStream = sorted.first(),
                    candidates = sorted,
                )
            }

            // Strategy 4: Offscreen WebView DOM, ytInitialPlayerResponse, Meta JSON & Network Sniffer
            if (webViewSniffer != null) {
                val sniffed = webViewSniffer.sniffPageMedia(cleanUrl)
                if (sniffed.capturedUrls.isNotEmpty()) {
                    val sniffedStreams = mutableListOf<ExtractedStream>()
                    val fallbackAudio = sniffed.audioCandidateUrls.firstOrNull()
                    val snifferProfile = if (platform == PlatformBadge.YOUTUBE) {
                        ClientSpoofer.profiles.getOrNull(1) ?: ClientSpoofer.profiles.first()
                    } else {
                        ClientSpoofer.profiles.first()
                    }
                    val defaultHeaders = ClientSpoofer.buildHeadersForUrl(
                        cleanUrl,
                        snifferProfile,
                        refererOverride = cleanUrl,
                    )
                    for (capturedUrl in sniffed.capturedUrls) {
                        val direct = probeDirectMediaOrHls(capturedUrl, platform, defaultHeaders)
                        if (direct != null) {
                            if (direct.estimatedBytes in 1L..16_383L) continue
                            if (MetaVideoPowerExtractor.isMetaAudioOnlyUrl(capturedUrl)) continue
                            val needsAudio = fallbackAudio != null && !direct.isHls && (
                                capturedUrl.contains("mime=video") ||
                                    platform == PlatformBadge.YOUTUBE ||
                                    MetaVideoPowerExtractor.isMetaVideoOnlyDashUrl(capturedUrl) ||
                                    ((platform == PlatformBadge.INSTAGRAM || platform == PlatformBadge.FACEBOOK) &&
                                        !MetaVideoPowerExtractor.isMetaProgressiveUrl(capturedUrl))
                                )
                            sniffedStreams.add(
                                direct.copy(
                                    title = sniffed.pageTitle?.takeIf { it.isNotBlank() } ?: direct.title,
                                    audioStreamUrl = if (needsAudio) fallbackAudio else direct.audioStreamUrl,
                                ),
                            )
                        } else if (looksLikeHlsUrl(capturedUrl) || looksLikeDownloadableVideoUrl(capturedUrl)) {
                            if (MetaVideoPowerExtractor.isMetaAudioOnlyUrl(capturedUrl)) continue
                            val isHls = looksLikeHlsUrl(capturedUrl)
                            val height = inferResolutionHeight(capturedUrl)
                            val needsAudio = !isHls && fallbackAudio != null && (
                                capturedUrl.contains("mime=video") ||
                                    platform == PlatformBadge.YOUTUBE ||
                                    MetaVideoPowerExtractor.isMetaVideoOnlyDashUrl(capturedUrl) ||
                                    ((platform == PlatformBadge.INSTAGRAM || platform == PlatformBadge.FACEBOOK) &&
                                        !MetaVideoPowerExtractor.isMetaProgressiveUrl(capturedUrl))
                                )
                            sniffedStreams.add(
                                ExtractedStream(
                                    streamUrl = capturedUrl,
                                    title = sniffed.pageTitle ?: deriveTitleFromUrl(cleanUrl),
                                    platform = platform,
                                    qualityLabel = formatQualityLabel(height),
                                    resolutionHeight = height,
                                    mimeType = if (isHls) "application/vnd.apple.mpegurl" else resolveMimeType("", capturedUrl),
                                    estimatedBytes = -1L,
                                    headers = defaultHeaders,
                                    audioStreamUrl = if (needsAudio) fallbackAudio else null,
                                    isHls = isHls,
                                ),
                            )
                        }
                    }
                    if (sniffedStreams.isNotEmpty()) {
                        val sorted = rankBestStreams(sniffedStreams)
                        return@withContext ExtractionResult.Success(
                            bestStream = sorted.first(),
                            candidates = sorted,
                        )
                    }
                }
                if (sniffed.loginRequiredDetected) {
                    sawAuthChallenge = true
                }
            }

            if (sawAuthChallenge) {
                val authTargetUrl = when (platform) {
                    PlatformBadge.YOUTUBE -> "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F"
                    PlatformBadge.INSTAGRAM -> "https://www.instagram.com/accounts/login/"
                    PlatformBadge.FACEBOOK -> "https://m.facebook.com/login/"
                    else -> cleanUrl
                }
                return@withContext ExtractionResult.NeedsAuth(
                    url = authTargetUrl,
                    reason = "Session authentication required for ${platform.label}. Sign in via DOWNLOADED VIDEOS → Browser or Vault.",
                )
            }

            ExtractionResult.Error(
                "Unable to isolate a downloadable stream. Swipe up to DOWNLOADED VIDEOS → Browser and sign in, or verify the link.",
            )
        } catch (e: Exception) {
            ExtractionResult.Error(e.message ?: "Stream extraction failed")
        }
    }

    internal fun rankBestStreams(streams: List<ExtractedStream>): List<ExtractedStream> {
        return streams
            .filterNot { MetaVideoPowerExtractor.isMetaAudioOnlyUrl(it.streamUrl) }
            .distinctBy { it.streamUrl }
            .sortedWith(
                compareByDescending<ExtractedStream> {
                    when {
                        !it.audioStreamUrl.isNullOrBlank() || MetaVideoPowerExtractor.isMetaProgressiveUrl(it.streamUrl) -> 2
                        !MetaVideoPowerExtractor.isMetaVideoOnlyDashUrl(it.streamUrl) -> 1
                        else -> 0
                    }
                }
                    .thenByDescending { !it.isHls }
                    .thenByDescending { it.resolutionHeight }
                    .thenByDescending { it.bitrateBps }
                    .thenByDescending { it.estimatedBytes },
            )
    }

    internal fun probeDirectMediaOrHls(
        url: String,
        platform: PlatformBadge,
        headers: Map<String, String>,
    ): ExtractedStream? {
        if (looksLikeHlsUrl(url)) {
            return parseBestHlsVariant(url, platform, deriveTitleFromUrl(url), headers)
        }

        val headBuilder = Request.Builder().url(url).head()
        headers.forEach { (k, v) -> headBuilder.header(k, v) }

        runCatching {
            client.newCall(headBuilder.build()).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val finalUrl = response.request.url.toString()
                if (response.isSuccessful) {
                    if (isHlsMime(contentType, finalUrl)) {
                        return parseBestHlsVariant(finalUrl, platform, deriveTitleFromUrl(finalUrl), headers)
                    }
                    if (isDirectVideoMimeOrExtension(contentType, finalUrl)) {
                        val mime = resolveMimeType(contentType, finalUrl)
                        val height = inferResolutionHeight(finalUrl)
                        return ExtractedStream(
                            streamUrl = finalUrl,
                            title = deriveTitleFromUrl(finalUrl),
                            platform = platform,
                            qualityLabel = formatQualityLabel(height),
                            resolutionHeight = height,
                            mimeType = mime,
                            estimatedBytes = contentLength,
                            headers = headers,
                        )
                    }
                }
            }
        }

        // Range GET fallback for CDNs blocking HEAD
        val rangeBuilder = Request.Builder().url(url).get().header("Range", "bytes=0-1023")
        headers.forEach { (k, v) -> rangeBuilder.header(k, v) }

        runCatching {
            client.newCall(rangeBuilder.build()).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val finalUrl = response.request.url.toString()
                if (response.isSuccessful || response.code == 206) {
                    if (isHlsMime(contentType, finalUrl)) {
                        return parseBestHlsVariant(finalUrl, platform, deriveTitleFromUrl(finalUrl), headers)
                    }
                    if (isDirectVideoMimeOrExtension(contentType, finalUrl)) {
                        val totalFromRange = response.header("Content-Range")
                            ?.substringAfterLast("/")
                            ?.toLongOrNull()
                            ?: response.header("Content-Length")?.toLongOrNull()
                            ?: -1L
                        val mime = resolveMimeType(contentType, finalUrl)
                        val height = inferResolutionHeight(finalUrl)
                        return ExtractedStream(
                            streamUrl = finalUrl,
                            title = deriveTitleFromUrl(finalUrl),
                            platform = platform,
                            qualityLabel = formatQualityLabel(height),
                            resolutionHeight = height,
                            mimeType = mime,
                            estimatedBytes = totalFromRange,
                            headers = headers,
                        )
                    }
                }
            }
        }
        return null
    }

    internal fun expandHlsCandidates(candidates: List<ExtractedStream>): List<ExtractedStream> {
        return candidates.map { candidate ->
            if (candidate.isHls) {
                parseBestHlsVariant(
                    playlistUrl = candidate.streamUrl,
                    platform = candidate.platform,
                    title = candidate.title,
                    headers = candidate.headers,
                ) ?: candidate
            } else {
                candidate
            }
        }
    }

    private fun parseBestHlsVariant(
        playlistUrl: String,
        platform: PlatformBadge,
        title: String,
        headers: Map<String, String>,
    ): ExtractedStream? {
        val reqBuilder = Request.Builder().url(playlistUrl)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val body = runCatching {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull() ?: return null

        if (!body.contains("#EXTM3U")) return null

        // Master playlist with variants?
        if (body.contains("#EXT-X-STREAM-INF")) {
            val baseUri = runCatching { URI(playlistUrl) }.getOrNull() ?: return null
            val lines = body.lines()

            // Parse demuxed #EXT-X-MEDIA:TYPE=AUDIO tracks so split HLS streams keep audio
            val audioGroupMap = linkedMapOf<String, String>()
            var defaultAudioUri: String? = null
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) &&
                    Regex("""TYPE=AUDIO""", RegexOption.IGNORE_CASE).containsMatchIn(line)
                ) {
                    val uriAttr = Regex("""URI=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.getOrNull(1)
                    if (!uriAttr.isNullOrBlank()) {
                        val resolvedAudio = runCatching { baseUri.resolve(uriAttr).toString() }.getOrNull()
                        if (!resolvedAudio.isNullOrBlank()) {
                            val groupId = Regex("""GROUP-ID=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                                .find(line)?.groupValues?.getOrNull(1)
                            if (!groupId.isNullOrBlank() && !audioGroupMap.containsKey(groupId)) {
                                audioGroupMap[groupId] = resolvedAudio
                            }
                            if (defaultAudioUri == null ||
                                line.contains("DEFAULT=YES", ignoreCase = true) ||
                                line.contains("AUTOSELECT=YES", ignoreCase = true)
                            ) {
                                defaultAudioUri = resolvedAudio
                            }
                        }
                    }
                }
            }

            var bestHeight = 0
            var bestBandwidth = 0L
            var bestVariantUrl: String? = null
            var bestVariantAudioUrl: String? = null

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val resMatch = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                    val bwMatch = Regex("""BANDWIDTH=(\d+)""").find(line)
                    val audioGroup = Regex("""AUDIO=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.getOrNull(1)
                    val height = resMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 720
                    val bandwidth = bwMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    val nextUrl = lines.drop(i + 1)
                        .firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }
                        ?.trim()
                    if (!nextUrl.isNullOrBlank()) {
                        if (height > bestHeight || (height == bestHeight && bandwidth > bestBandwidth)) {
                            bestHeight = height
                            bestBandwidth = bandwidth
                            bestVariantUrl = baseUri.resolve(nextUrl).toString()
                            bestVariantAudioUrl = audioGroup?.let { audioGroupMap[it] } ?: defaultAudioUri
                        }
                    }
                }
            }

            if (!bestVariantUrl.isNullOrBlank()) {
                return ExtractedStream(
                    streamUrl = bestVariantUrl,
                    title = title,
                    platform = platform,
                    qualityLabel = formatQualityLabel(bestHeight),
                    resolutionHeight = bestHeight,
                    mimeType = "video/mp4",
                    estimatedBytes = -1L,
                    headers = headers,
                    audioStreamUrl = bestVariantAudioUrl,
                    isHls = true,
                )
            }
        }

        // Media playlist directly
        val inferred = inferResolutionHeight(playlistUrl)
        return ExtractedStream(
            streamUrl = playlistUrl,
            title = title,
            platform = platform,
            qualityLabel = formatQualityLabel(inferred),
            resolutionHeight = inferred,
            mimeType = "video/mp4",
            estimatedBytes = -1L,
            headers = headers,
            isHls = true,
        )
    }

    private fun extractTikTokStreams(url: String): HtmlProbeOutcome {
        val found = mutableListOf<ExtractedStream>()
        var authChallenge = false
        var resolvedTitle: String? = null

        val tiktokHeaders = mapOf(
            "User-Agent" to ClientSpoofer.TIKTOK_IOS_USER_AGENT,
            "Referer" to "https://www.tiktok.com/",
            "Origin" to "https://www.tiktok.com",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
        )

        val desktopUa = ClientSpoofer.profiles.first().userAgent
        val probeHeaderSets = listOf(
            // Desktop Chrome UA typically unlocks full 1080p bitrateInfo in SSR hydration JSON
            mapOf(
                "User-Agent" to desktopUa,
                "Referer" to "https://www.tiktok.com/",
                "Origin" to "https://www.tiktok.com",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
            ),
            tiktokHeaders,
        )

        var canonicalUrl = url
        for (headers in probeHeaderSets) {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            runCatching {
                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        authChallenge = true
                        return@use
                    }
                    if (!response.isSuccessful) return@use

                    val finalUrl = response.request.url.toString()
                    canonicalUrl = finalUrl
                    val html = response.body?.string().orEmpty()
                    if (html.isBlank()) return@use

                    val pageTitle = extractPageTitle(html) ?: "TikTok_Video"
                    if (resolvedTitle == null) resolvedTitle = pageTitle

                    // 1. Parse __UNIVERSAL_DATA_FOR_REHYDRATION__ JSON script payload
                    val universalMatch = Regex(
                        """<script[^>]+id=["']__UNIVERSAL_DATA_FOR_REHYDRATION__["'][^>]*>([\s\S]*?)</script>""",
                        RegexOption.IGNORE_CASE,
                    ).find(html)?.groupValues?.getOrNull(1)

                    if (!universalMatch.isNullOrBlank()) {
                        val root = runCatching { JSONObject(universalMatch) }.getOrNull()
                        val defaultScope = root?.optJSONObject("__DEFAULT_SCOPE__")
                        val videoDetail = defaultScope?.optJSONObject("webapp.video-detail")
                            ?.optJSONObject("itemInfo")
                            ?.optJSONObject("itemStruct")
                        if (videoDetail != null) {
                            val desc = videoDetail.optString("desc")
                                .takeIf { it.isNotBlank() }
                                ?.let(::sanitizeTitle)
                                ?: pageTitle
                            resolvedTitle = desc
                            val videoObj = videoDetail.optJSONObject("video")
                            if (videoObj != null) {
                                extractTikTokVideoObjectStreams(videoObj, desc, tiktokHeaders, found)
                            }
                        }
                    }

                    // 2. Fallback: parse legacy SIGI_STATE JSON payload
                    val sigiMatch = Regex(
                        """<script[^>]+id=["']SIGI_STATE["'][^>]*>([\s\S]*?)</script>""",
                        RegexOption.IGNORE_CASE,
                    ).find(html)?.groupValues?.getOrNull(1)

                    if (!sigiMatch.isNullOrBlank()) {
                        val sigiRoot = runCatching { JSONObject(sigiMatch) }.getOrNull()
                        val itemModule = sigiRoot?.optJSONObject("ItemModule")
                        val keys = itemModule?.keys()
                        if (keys != null) {
                            while (keys.hasNext()) {
                                val itemObj = itemModule.optJSONObject(keys.next()) ?: continue
                                val desc = itemObj.optString("desc")
                                    .takeIf { it.isNotBlank() }
                                    ?.let(::sanitizeTitle)
                                    ?: pageTitle
                                resolvedTitle = desc
                                val videoObj = itemObj.optJSONObject("video") ?: continue
                                extractTikTokVideoObjectStreams(videoObj, desc, tiktokHeaders, found)
                            }
                        }
                    }

                    if (finalUrl.contains("/login", ignoreCase = true) ||
                        html.contains("verify-bar-close", ignoreCase = true)
                    ) {
                        authChallenge = true
                    }
                }
            }
            if (found.any { it.resolutionHeight >= 1080 }) break
        }

        // 3. Query TikWM HD endpoint (hd=1) to guarantee true watermark-free Full HD / original quality
        val hdStreams = fetchTikTokHdStreamsFromApi(canonicalUrl, resolvedTitle ?: "TikTok_Video")
        found.addAll(hdStreams)

        return HtmlProbeOutcome(
            streams = found.distinctBy { it.streamUrl },
            authChallenge = authChallenge && found.isEmpty(),
        )
    }

    private fun fetchTikTokHdStreamsFromApi(
        targetUrl: String,
        fallbackTitle: String,
    ): List<ExtractedStream> {
        val encoded = runCatching { URLEncoder.encode(targetUrl, "UTF-8") }.getOrNull()
            ?: return emptyList()
        val apiUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1"
        val req = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", ClientSpoofer.TIKTOK_IOS_USER_AGENT)
            .header("Accept", "application/json")
            .build()

        return runCatching {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) return emptyList()
                val root = JSONObject(raw)
                if (root.optInt("code", -1) != 0) return emptyList()
                val data = root.optJSONObject("data") ?: return emptyList()

                val title = data.optString("title")
                    .takeIf { it.isNotBlank() }
                    ?.let(::sanitizeTitle)
                    ?: fallbackTitle
                val out = mutableListOf<ExtractedStream>()
                val baseUri = URI("https://www.tikwm.com")
                val cdnHeaders = mapOf(
                    "User-Agent" to ClientSpoofer.TIKTOK_IOS_USER_AGENT,
                    "Referer" to "https://www.tikwm.com/",
                )

                val hdRaw = data.optString("hdplay")
                if (hdRaw.isNotBlank()) {
                    val hdUrl = if (hdRaw.startsWith("http")) hdRaw else baseUri.resolve(hdRaw).toString()
                    val verifiedBytes = probeVerifiedContentLength(hdUrl, cdnHeaders)
                    if (verifiedBytes != null) {
                        val reportedSize = data.optLong("hd_size", -1L).takeIf { it > 0L } ?: verifiedBytes
                        out.add(
                            ExtractedStream(
                                streamUrl = hdUrl,
                                title = title,
                                platform = PlatformBadge.TIKTOK,
                                qualityLabel = "1080P HD",
                                resolutionHeight = 1080,
                                mimeType = "video/mp4",
                                estimatedBytes = reportedSize,
                                headers = cdnHeaders,
                                bitrateBps = 4_000_000L,
                            ),
                        )
                    }
                }

                val sdRaw = data.optString("play")
                if (sdRaw.isNotBlank()) {
                    val sdUrl = if (sdRaw.startsWith("http")) sdRaw else baseUri.resolve(sdRaw).toString()
                    val verifiedBytes = probeVerifiedContentLength(sdUrl, cdnHeaders)
                    if (verifiedBytes != null) {
                        val reportedSize = data.optLong("size", -1L).takeIf { it > 0L } ?: verifiedBytes
                        out.add(
                            ExtractedStream(
                                streamUrl = sdUrl,
                                title = title,
                                platform = PlatformBadge.TIKTOK,
                                qualityLabel = "720P HD",
                                resolutionHeight = 720,
                                mimeType = "video/mp4",
                                estimatedBytes = reportedSize,
                                headers = cdnHeaders,
                                bitrateBps = 1_500_000L,
                            ),
                        )
                    }
                }

                out
            }
        }.getOrDefault(emptyList())
    }

    private fun extractTikTokVideoObjectStreams(
        videoObj: JSONObject,
        title: String,
        headers: Map<String, String>,
        out: MutableList<ExtractedStream>,
    ) {
        val rawObjW = videoObj.optInt("width", 0)
        val rawObjH = videoObj.optInt("height", 0)
        val defaultQualityTier = computeShortSideResolution(rawObjW, rawObjH, fallback = 540)

        val bitrateInfo = videoObj.optJSONArray("bitrateInfo")
        if (bitrateInfo != null) {
            for (i in 0 until bitrateInfo.length()) {
                val entry = bitrateInfo.optJSONObject(i) ?: continue
                val bitrate = entry.optLong("Bitrate", entry.optLong("bitrate", 0L))
                val playAddr = entry.optJSONObject("PlayAddr")
                    ?: entry.optJSONObject("playAddr")
                    ?: entry.optJSONObject("play_addr")
                    ?: continue
                val urlList = playAddr.optJSONArray("UrlList")
                    ?: playAddr.optJSONArray("urlList")
                    ?: playAddr.optJSONArray("url_list")
                    ?: continue

                val w = playAddr.optInt("Width", playAddr.optInt("width", rawObjW))
                val h = playAddr.optInt("Height", playAddr.optInt("height", rawObjH))
                val qualityTier = computeShortSideResolution(w, h, fallback = defaultQualityTier)
                val sizeBytes = playAddr.optLong("DataSize", playAddr.optLong("dataSize", playAddr.optLong("data_size", -1L)))

                for (j in 0 until urlList.length()) {
                    val candidateUrl = urlList.optString(j)
                    if (candidateUrl.startsWith("http")) {
                        out.add(
                            ExtractedStream(
                                streamUrl = candidateUrl,
                                title = title,
                                platform = PlatformBadge.TIKTOK,
                                qualityLabel = formatQualityLabel(qualityTier),
                                resolutionHeight = qualityTier,
                                mimeType = "video/mp4",
                                estimatedBytes = sizeBytes,
                                headers = headers,
                                bitrateBps = bitrate,
                            ),
                        )
                    }
                }
            }
        }

        // Only consider generic playAddr if bitrateInfo gave nothing, or attach accurate low-tier fallback weight
        val playAddr = videoObj.optString("playAddr")
        if (playAddr.startsWith("http")) {
            val bytes = probeContentLength(playAddr, headers)
            out.add(
                ExtractedStream(
                    streamUrl = playAddr,
                    title = title,
                    platform = PlatformBadge.TIKTOK,
                    qualityLabel = formatQualityLabel(defaultQualityTier),
                    resolutionHeight = defaultQualityTier,
                    mimeType = "video/mp4",
                    estimatedBytes = bytes,
                    headers = headers,
                    bitrateBps = 1L,
                ),
            )
        }

        if (out.isEmpty()) {
            val downloadAddr = videoObj.optString("downloadAddr")
            if (downloadAddr.startsWith("http")) {
                out.add(
                    ExtractedStream(
                        streamUrl = downloadAddr,
                        title = title,
                        platform = PlatformBadge.TIKTOK,
                        qualityLabel = formatQualityLabel(defaultQualityTier),
                        resolutionHeight = defaultQualityTier,
                        mimeType = "video/mp4",
                        estimatedBytes = -1L,
                        headers = headers,
                    ),
                )
            }
        }
    }

    private fun computeShortSideResolution(width: Int, height: Int, fallback: Int): Int {
        val raw = when {
            width > 0 && height > 0 -> minOf(width, height)
            height > 0 -> height
            width > 0 -> width
            else -> fallback
        }
        // Normalize minor encoding quirks like 576x1024 -> 576p, 1088x1920 -> 1080p
        return when {
            raw >= 2100 -> 2160
            raw >= 1400 -> 1440
            raw >= 1050 -> 1080
            raw >= 700 -> 720
            else -> raw
        }
    }

    private fun probeContentLength(url: String, headers: Map<String, String>): Long {
        return probeVerifiedContentLength(url, headers) ?: -1L
    }

    /**
     * Verifies a candidate media URL responds cleanly via HEAD or a 1-byte Range GET,
     * returning the Content-Length (or -1L if reachable without a length header),
     * or null when the server rejects or fails the request.
     */
    private fun probeVerifiedContentLength(url: String, headers: Map<String, String>): Long? {
        val headBuilder = Request.Builder().url(url).head()
        headers.forEach { (k, v) -> headBuilder.header(k, v) }
        val headSize = runCatching {
            client.newCall(headBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                } else {
                    null
                }
            }
        }.getOrNull()
        if (headSize != null) return headSize

        val rangeBuilder = Request.Builder().url(url).header("Range", "bytes=0-0")
        headers.forEach { (k, v) -> rangeBuilder.header(k, v) }
        return runCatching {
            client.newCall(rangeBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 206) {
                    resp.header("Content-Range")
                        ?.substringAfterLast("/")
                        ?.toLongOrNull()
                        ?: resp.header("Content-Length")?.toLongOrNull()
                        ?: -1L
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun extractVimeoStreams(url: String): List<ExtractedStream> {
        val idMatch = Regex("""vimeo\.com/(?:video/)?(\d+)""").find(url) ?: return emptyList()
        val videoId = idMatch.groupValues[1]
        val configUrl = "https://player.vimeo.com/video/$videoId/config"
        val headers = ClientSpoofer.buildHeadersForUrl(
            configUrl,
            ClientSpoofer.profiles.first(),
            refererOverride = "https://vimeo.com/",
        )
        val reqBuilder = Request.Builder().url(configUrl)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        return runCatching {
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val json = JSONObject(response.body?.string().orEmpty())
                val title = json.optJSONObject("video")?.optString("title").orEmpty()
                    .ifBlank { "Vimeo_$videoId" }
                val files = json.optJSONObject("request")?.optJSONObject("files") ?: return emptyList()
                val progressive = files.optJSONArray("progressive") ?: JSONArray()

                buildList {
                    for (i in 0 until progressive.length()) {
                        val item = progressive.optJSONObject(i) ?: continue
                        val streamUrl = item.optString("url")
                        if (streamUrl.isBlank()) continue
                        val height = item.optInt("height", 720)
                        val mime = item.optString("mime", "video/mp4")
                        add(
                            ExtractedStream(
                                streamUrl = streamUrl,
                                title = title,
                                platform = PlatformBadge.VIMEO,
                                qualityLabel = formatQualityLabel(height),
                                resolutionHeight = height,
                                mimeType = mime,
                                estimatedBytes = -1L,
                                headers = mapOf("Referer" to "https://vimeo.com/"),
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun extractRedditStreams(url: String): List<ExtractedStream> {
        val cleanBase = url.substringBefore("?").trimEnd('/')
        val jsonUrl = if (cleanBase.endsWith(".json")) cleanBase else "$cleanBase.json"
        val request = Request.Builder()
            .url(jsonUrl)
            .header("User-Agent", "BlackholePulse/2.0 (Android)")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val rootArray = JSONArray(response.body?.string().orEmpty())
                val postData = rootArray.optJSONObject(0)
                    ?.optJSONObject("data")
                    ?.optJSONArray("children")
                    ?.optJSONObject(0)
                    ?.optJSONObject("data")
                    ?: return emptyList()

                val title = postData.optString("title").ifBlank { "Reddit_Video" }
                val redditVideo = postData.optJSONObject("secure_media")
                    ?.optJSONObject("reddit_video")
                    ?: postData.optJSONObject("media")
                        ?.optJSONObject("reddit_video")
                    ?: return emptyList()

                val fallbackUrl = redditVideo.optString("fallback_url")
                if (fallbackUrl.isBlank()) return emptyList()
                val height = redditVideo.optInt("height", inferResolutionHeight(fallbackUrl))

                // Probe matching Reddit companion audio track for local muxing
                val baseMediaPath = fallbackUrl.substringBefore("?").substringBeforeLast("/")
                val audioCandidates = listOf(
                    "$baseMediaPath/DASH_AUDIO_128.mp4",
                    "$baseMediaPath/DASH_AUDIO_64.mp4",
                    "$baseMediaPath/DASH_audio.mp4",
                    "$baseMediaPath/audio",
                )
                val validAudio = audioCandidates.firstOrNull { candidate ->
                    runCatching {
                        client.newCall(Request.Builder().url(candidate).head().build())
                            .execute()
                            .use { it.isSuccessful }
                    }.getOrDefault(false)
                }

                listOf(
                    ExtractedStream(
                        streamUrl = fallbackUrl,
                        title = title,
                        platform = PlatformBadge.REDDIT,
                        qualityLabel = formatQualityLabel(height),
                        resolutionHeight = height,
                        mimeType = "video/mp4",
                        estimatedBytes = -1L,
                        audioStreamUrl = validAudio,
                    ),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun extractXTwitterStreams(url: String): List<ExtractedStream> {
        val match = Regex("""(?:twitter\.com|x\.com)/([^/]+)/status/(\d+)""").find(url)
            ?: return emptyList()
        val user = match.groupValues[1]
        val tweetId = match.groupValues[2]

        val endpoints = listOf(
            "https://api.vxtwitter.com/$user/status/$tweetId",
            "https://api.fxtwitter.com/$user/status/$tweetId",
        )
        for (apiUrl in endpoints) {
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .build()
            val streams = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<ExtractedStream>()
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val out = mutableListOf<ExtractedStream>()
                    val title = sanitizeTitle(
                        root.optString("text").ifBlank {
                            root.optJSONObject("tweet")?.optString("text").orEmpty()
                        }.ifBlank { "X_$tweetId" },
                    )

                    // vxtwitter format
                    val mediaExtended = root.optJSONArray("media_extended")
                    if (mediaExtended != null) {
                        for (i in 0 until mediaExtended.length()) {
                            val m = mediaExtended.optJSONObject(i) ?: continue
                            val mUrl = m.optString("url")
                            if (mUrl.startsWith("http") && (m.optString("type") == "video" || mUrl.contains(".mp4"))) {
                                val size = m.optJSONObject("size")
                                val tier = computeShortSideResolution(
                                    size?.optInt("width", 0) ?: 0,
                                    size?.optInt("height", 0) ?: 0,
                                    fallback = inferResolutionHeight(mUrl).takeIf { it > 0 } ?: 1080,
                                )
                                out.add(
                                    ExtractedStream(
                                        streamUrl = mUrl,
                                        title = title,
                                        platform = PlatformBadge.X_TWITTER,
                                        qualityLabel = formatQualityLabel(tier),
                                        resolutionHeight = tier,
                                        mimeType = "video/mp4",
                                        estimatedBytes = -1L,
                                    ),
                                )
                            }
                        }
                    }

                    // fxtwitter format
                    val videos = root.optJSONObject("tweet")?.optJSONObject("media")?.optJSONArray("videos")
                    if (videos != null) {
                        for (i in 0 until videos.length()) {
                            val v = videos.optJSONObject(i) ?: continue
                            val variants = v.optJSONArray("variants")
                            if (variants != null) {
                                for (j in 0 until variants.length()) {
                                    val vr = variants.optJSONObject(j) ?: continue
                                    val vrUrl = vr.optString("url")
                                    if (vrUrl.startsWith("http") && vr.optString("content_type").contains("mp4")) {
                                        val tier = inferResolutionHeight(vrUrl).takeIf { it > 0 } ?: 720
                                        out.add(
                                            ExtractedStream(
                                                streamUrl = vrUrl,
                                                title = title,
                                                platform = PlatformBadge.X_TWITTER,
                                                qualityLabel = formatQualityLabel(tier),
                                                resolutionHeight = tier,
                                                mimeType = "video/mp4",
                                                estimatedBytes = -1L,
                                                bitrateBps = vr.optLong("bitrate", 0L),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    out
                }
            }.getOrDefault(emptyList())
            if (streams.isNotEmpty()) return streams
        }
        return emptyList()
    }

    private fun extractOgAndVideoTagStreams(
        unescapedHtml: String,
        baseUrl: String,
        title: String,
        platform: PlatformBadge,
        headers: Map<String, String>,
        out: MutableList<ExtractedStream>,
    ) {
        val metaPatterns = listOf(
            Regex("""<meta[^>]+(?:property|name)=["']og:video:secure_url["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+(?:property|name)=["']og:video:url["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+(?:property|name)=["']og:video["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:video(?::secure_url|:url)?["']""", RegexOption.IGNORE_CASE),
        )
        metaPatterns.forEach { regex ->
            regex.findAll(unescapedHtml).forEach { match ->
                val found = resolveRelativeUrl(baseUrl, match.groupValues[1])
                addCandidateIfMedia(found, title, platform, headers, out)
            }
        }
        val videoTagPatterns = listOf(
            Regex("""<(?:video|source)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<source[^>]+data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        )
        videoTagPatterns.forEach { regex ->
            regex.findAll(unescapedHtml).forEach { match ->
                val found = resolveRelativeUrl(baseUrl, match.groupValues[1])
                addCandidateIfMedia(found, title, platform, headers, out)
            }
        }
    }

    private data class HtmlProbeOutcome(
        val streams: List<ExtractedStream>,
        val authChallenge: Boolean,
    )

    private fun extractFromWebPage(
        url: String,
        platform: PlatformBadge,
        headers: Map<String, String>,
    ): HtmlProbeOutcome {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        val html: String
        val finalPageUrl: String
        var authChallenge = false

        client.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                authChallenge = true
            }
            if (!response.isSuccessful) {
                return HtmlProbeOutcome(emptyList(), authChallenge)
            }
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            finalPageUrl = response.request.url.toString()
            if (isHlsMime(contentType, finalPageUrl)) {
                val hls = parseBestHlsVariant(finalPageUrl, platform, deriveTitleFromUrl(finalPageUrl), headers)
                return HtmlProbeOutcome(listOfNotNull(hls), false)
            }
            if (isDirectVideoMimeOrExtension(contentType, finalPageUrl)) {
                val height = inferResolutionHeight(finalPageUrl)
                return HtmlProbeOutcome(
                    streams = listOf(
                        ExtractedStream(
                            streamUrl = finalPageUrl,
                            title = deriveTitleFromUrl(finalPageUrl),
                            platform = platform,
                            qualityLabel = formatQualityLabel(height),
                            resolutionHeight = height,
                            mimeType = resolveMimeType(contentType, finalPageUrl),
                            estimatedBytes = response.header("Content-Length")?.toLongOrNull() ?: -1L,
                            headers = headers,
                        ),
                    ),
                    authChallenge = false,
                )
            }
            html = response.body?.string().orEmpty()
        }

        if (html.isBlank()) return HtmlProbeOutcome(emptyList(), authChallenge)
        val pageTitle = extractPageTitle(html) ?: deriveTitleFromUrl(finalPageUrl)
        val unescapedHtml = unescapeMarkup(html)
        val candidates = mutableListOf<ExtractedStream>()
        val audioPool = mutableListOf<String>()
        val streamHeaders = headers + ("Referer" to finalPageUrl)

        val metaPatterns = listOf(
            Regex("""<meta[^>]+(?:property|name)=["']og:video:secure_url["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+(?:property|name)=["']og:video:url["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+(?:property|name)=["']og:video["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+(?:property|name)=["']twitter:player:stream["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:video(?::secure_url|:url)?["']""", RegexOption.IGNORE_CASE),
        )
        metaPatterns.forEach { regex ->
            regex.findAll(unescapedHtml).forEach { match ->
                val found = resolveRelativeUrl(finalPageUrl, match.groupValues[1])
                addCandidateIfMedia(found, pageTitle, platform, streamHeaders, candidates, audioPool)
            }
        }

        val videoTagPatterns = listOf(
            Regex("""<(?:video|source)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<source[^>]+data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        )
        videoTagPatterns.forEach { regex ->
            regex.findAll(unescapedHtml).forEach { match ->
                val found = resolveRelativeUrl(finalPageUrl, match.groupValues[1])
                addCandidateIfMedia(found, pageTitle, platform, streamHeaders, candidates, audioPool)
            }
        }

        Regex(""""audio_url"\s*:\s*"(https?://[^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(unescapedHtml)
            .forEach { m -> audioPool.add(m.groupValues[1]) }

        Regex("""<BaseURL[^>]*>\s*(https?://[^<\s]+)\s*</BaseURL>""", RegexOption.IGNORE_CASE)
            .findAll(unescapedHtml)
            .forEach { m ->
                addCandidateIfMedia(m.groupValues[1], pageTitle, platform, streamHeaders, candidates, audioPool)
            }

        val jsonStreamPatterns = listOf(
            Regex(""""(?:contentUrl|browser_native_hd_url|playable_url_quality_hd|hd_src_no_ratelimit|hd_src|browser_native_sd_url|playable_url|playback_url|sd_src|playAddr|downloadAddr|video_url|videoUrl|playbackUrl|hlsUrl|mp4Url|url)"\s*:\s*"(https?://[^"]+\.(?:mp4|m4a|aac|m3u8|webm|mov|m4v)[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s"'<>\\]+\.(?:mp4|m4a|aac|m3u8|webm|mov|m4v)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE),
        )
        jsonStreamPatterns.forEach { regex ->
            regex.findAll(unescapedHtml).forEach { match ->
                addCandidateIfMedia(match.groupValues[1], pageTitle, platform, streamHeaders, candidates, audioPool)
            }
        }

        val fallbackAudio = audioPool.firstOrNull()
        val enriched = if (fallbackAudio != null) {
            candidates.map { cand ->
                if (!cand.isHls && cand.audioStreamUrl == null && !MetaVideoPowerExtractor.isMetaProgressiveUrl(cand.streamUrl)) {
                    cand.copy(audioStreamUrl = fallbackAudio)
                } else {
                    cand
                }
            }
        } else {
            candidates
        }

        val looksGated = finalPageUrl.lowercase().contains("/login") ||
            html.contains("login_required", ignoreCase = true)
        return HtmlProbeOutcome(
            streams = enriched.distinctBy { it.streamUrl },
            authChallenge = authChallenge || (looksGated && enriched.isEmpty()),
        )
    }

    private fun addCandidateIfMedia(
        url: String,
        title: String,
        platform: PlatformBadge,
        headers: Map<String, String>,
        out: MutableList<ExtractedStream>,
        audioPool: MutableList<String>? = null,
    ) {
        val lower = url.lowercase()
        val pathOnly = lower.substringBefore("?")
        if (MetaVideoPowerExtractor.isMetaAudioOnlyUrl(url) ||
            pathOnly.endsWith(".m4a") ||
            pathOnly.endsWith(".aac")
        ) {
            audioPool?.add(url)
            return
        }
        val isHls = looksLikeHlsUrl(url)
        if (!isHls && !looksLikeDownloadableVideoUrl(url)) return
        val height = inferResolutionHeight(url)
        out.add(
            ExtractedStream(
                streamUrl = url,
                title = title,
                platform = platform,
                qualityLabel = formatQualityLabel(height),
                resolutionHeight = height,
                mimeType = if (isHls) "application/vnd.apple.mpegurl" else resolveMimeType("", url),
                estimatedBytes = -1L,
                headers = headers,
                isHls = isHls,
            ),
        )
    }

    private fun extractPageTitle(html: String): String? {
        val ogTitle = Regex(
            """<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)
        if (!ogTitle.isNullOrBlank()) return sanitizeTitle(unescapeMarkup(ogTitle))

        val titleTag = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        return titleTag?.takeIf { it.isNotBlank() }?.let { sanitizeTitle(unescapeMarkup(it)) }
    }

    private fun sanitizeTitle(raw: String): String {
        return raw.replace(Regex("""\s+"""), " ")
            .trim()
            .take(72)
            .ifBlank { "Blackhole_Capture" }
    }

    internal fun deriveTitleFromUrl(url: String): String {
        return runCatching {
            val path = URI(url).path.orEmpty().trimEnd('/')
            val last = path.substringAfterLast('/').substringBeforeLast('.')
            last.replace(Regex("""[^A-Za-z0-9_\- ]"""), " ")
                .trim()
                .take(64)
                .ifBlank { "Blackhole_Stream" }
        }.getOrDefault("Blackhole_Stream")
    }

    private fun unescapeMarkup(input: String): String {
        return input
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\\\u0026", "&", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\\\u0025", "%", ignoreCase = true)
            .replace("\\u0025", "%", ignoreCase = true)
            .replace("\\\\u002f", "/", ignoreCase = true)
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("\\\\u003c", "<", ignoreCase = true)
            .replace("\\u003c", "<", ignoreCase = true)
            .replace("\\\\u003e", ">", ignoreCase = true)
            .replace("\\u003e", ">", ignoreCase = true)
            .replace("\\\\u003d", "=", ignoreCase = true)
            .replace("\\u003d", "=", ignoreCase = true)
            .replace("\\\\u003f", "?", ignoreCase = true)
            .replace("\\u003f", "?", ignoreCase = true)
            .replace("\\\\u003a", ":", ignoreCase = true)
            .replace("\\u003a", ":", ignoreCase = true)
            .replace("\\\\u002d", "-", ignoreCase = true)
            .replace("\\u002d", "-", ignoreCase = true)
            .replace("\\\\\"", "\"")
            .replace("\\\"", "\"")
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
    }

    private fun resolveRelativeUrl(baseUrl: String, candidate: String): String {
        return runCatching {
            URI(baseUrl).resolve(candidate).toString()
        }.getOrDefault(candidate)
    }

    internal fun looksLikeHlsUrl(url: String): Boolean {
        val clean = url.substringBefore("?").lowercase()
        return clean.endsWith(".m3u8") || url.lowercase().contains(".m3u8?")
    }

    internal fun isHlsMime(contentType: String, url: String): Boolean {
        return contentType.contains("mpegurl") || looksLikeHlsUrl(url)
    }

    internal fun isDirectVideoMimeOrExtension(contentType: String, url: String): Boolean {
        if (contentType.startsWith("video/")) return true
        val clean = url.substringBefore("?").lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".webm") ||
            clean.endsWith(".mov") || clean.endsWith(".mkv") || clean.endsWith(".m4v")
    }

    internal fun looksLikeDownloadableVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val pathOnly = lower.substringBefore("?")
        return pathOnly.endsWith(".mp4") || pathOnly.endsWith(".webm") ||
            pathOnly.endsWith(".mov") || pathOnly.endsWith(".m4v") ||
            lower.contains("mime=video") || lower.contains("mime_type=video") ||
            lower.contains("video_mp4") || lower.contains(".mp4?")
    }

    internal fun resolveMimeType(contentType: String, url: String): String {
        if (contentType.startsWith("video/")) return contentType.substringBefore(";").trim()
        val lower = url.substringBefore("?").lowercase()
        return when {
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".mkv") -> "video/x-matroska"
            else -> "video/mp4"
        }
    }

    internal fun inferResolutionHeight(url: String): Int {
        val lower = url.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> 2160
            lower.contains("1440") || lower.contains("2k") -> 1440
            lower.contains("1080") || lower.contains("fhd") || lower.contains("fullhd") -> 1080
            lower.contains("720") || lower.contains("_hd") || lower.contains("hdplay") -> 720
            lower.contains("540") -> 540
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            else -> 0
        }
    }

    companion object {
        fun defaultOkHttpClient(cookieJar: CookieJar = CookieJar.NO_COOKIES): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 24
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
                .cookieJar(cookieJar)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        fun createWithVault(cookieVault: CookieVaultManager): OkHttpClient {
            return defaultOkHttpClient(cookieVault.okHttpCookieJar)
        }
    }
}
