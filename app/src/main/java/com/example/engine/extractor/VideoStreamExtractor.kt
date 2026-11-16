package com.example.engine.extractor

import com.example.engine.cookies.CookieVaultManager
import com.example.engine.model.ExtractedStream
import com.example.engine.model.ExtractionResult
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
import com.example.engine.network.SpoofedClientProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class VideoStreamExtractor(
    private val client: OkHttpClient = defaultOkHttpClient(),
    private val webViewSniffer: WebViewStreamSniffer? = null,
) {

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

            // Strategy 2: Platform-specific endpoints (YouTube InnerTube, TikTok iOS Safari, Vimeo, Reddit)
            val platformStreams = when (platform) {
                PlatformBadge.YOUTUBE -> {
                    val ytResult = extractYoutubeStreams(cleanUrl)
                    if (ytResult.authChallenge) sawAuthChallenge = true
                    ytResult.streams
                }
                PlatformBadge.TIKTOK -> {
                    val ttResult = extractTikTokStreams(cleanUrl)
                    if (ttResult.authChallenge) sawAuthChallenge = true
                    ttResult.streams
                }
                PlatformBadge.VIMEO -> extractVimeoStreams(cleanUrl)
                PlatformBadge.REDDIT -> extractRedditStreams(cleanUrl)
                else -> emptyList()
            }
            if (platformStreams.isNotEmpty()) {
                val sorted = rankBestStreams(platformStreams)
                return@withContext ExtractionResult.Success(
                    bestStream = sorted.first(),
                    candidates = sorted,
                )
            }

            // Strategy 3: Webpage HTML5 / OpenGraph / JSON-LD / Player Config with Client Spoofing Rotation
            val allHtmlCandidates = mutableListOf<ExtractedStream>()
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

            if (allHtmlCandidates.isNotEmpty()) {
                // Upgrade any captured master .m3u8 playlist to its highest-resolution variant
                val expanded = expandHlsCandidates(allHtmlCandidates)
                val sorted = rankBestStreams(expanded)
                return@withContext ExtractionResult.Success(
                    bestStream = sorted.first(),
                    candidates = sorted,
                )
            }

            // Strategy 4: Offscreen WebView DOM & Network Sniffer (with synced CookieVault cookies)
            if (webViewSniffer != null) {
                val sniffed = webViewSniffer.sniffPageMedia(cleanUrl)
                if (sniffed.capturedUrls.isNotEmpty()) {
                    val sniffedStreams = mutableListOf<ExtractedStream>()
                    val defaultHeaders = ClientSpoofer.buildHeadersForUrl(
                        cleanUrl,
                        ClientSpoofer.profiles.first(),
                        refererOverride = cleanUrl,
                    )
                    for (capturedUrl in sniffed.capturedUrls) {
                        val direct = probeDirectMediaOrHls(capturedUrl, platform, defaultHeaders)
                        if (direct != null) {
                            sniffedStreams.add(
                                direct.copy(
                                    title = sniffed.pageTitle?.takeIf { it.isNotBlank() } ?: direct.title,
                                ),
                            )
                        } else if (looksLikeHlsUrl(capturedUrl) || looksLikeDownloadableVideoUrl(capturedUrl)) {
                            val isHls = looksLikeHlsUrl(capturedUrl)
                            val height = inferResolutionHeight(capturedUrl)
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
                return@withContext ExtractionResult.NeedsAuth(
                    url = cleanUrl,
                    reason = "Session authentication required. Sign in via WebView or import cookies.txt to unlock this stream.",
                )
            }

            ExtractionResult.Error(
                "Unable to isolate a downloadable stream. Try importing browser cookies or signing in via the Session Vault.",
            )
        } catch (e: Exception) {
            ExtractionResult.Error(e.message ?: "Stream extraction failed")
        }
    }

    private fun rankBestStreams(streams: List<ExtractedStream>): List<ExtractedStream> {
        return streams
            .distinctBy { it.streamUrl }
            .sortedWith(
                compareByDescending<ExtractedStream> { !it.isHls }
                    .thenByDescending { it.resolutionHeight }
                    .thenByDescending { it.bitrateBps }
                    .thenByDescending { it.estimatedBytes },
            )
    }

    private fun probeDirectMediaOrHls(
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

    private fun expandHlsCandidates(candidates: List<ExtractedStream>): List<ExtractedStream> {
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
            var bestHeight = 0
            var bestBandwidth = 0L
            var bestVariantUrl: String? = null

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val resMatch = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                    val bwMatch = Regex("""BANDWIDTH=(\d+)""").find(line)
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

    private fun extractYoutubeStreams(url: String): HtmlProbeOutcome {
        val videoId = extractYoutubeVideoId(url) ?: return HtmlProbeOutcome(emptyList(), false)
        var loginRequired = false

        val clientConfigs = listOf(
            Triple(
                "ANDROID",
                "19.44.38",
                "com.google.android.youtube/19.44.38 (Linux; U; Android 15; Pixel 9 Pro) gzip",
            ),
            Triple(
                "IOS",
                "19.45.4",
                "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X)",
            ),
        )

        for ((clientName, clientVersion, userAgent) in clientConfigs) {
            val payload = JSONObject().apply {
                put("videoId", videoId)
                put(
                    "context",
                    JSONObject().apply {
                        put(
                            "client",
                            JSONObject().apply {
                                put("clientName", clientName)
                                put("clientVersion", clientVersion)
                                put("hl", "en")
                                put("gl", "US")
                                if (clientName == "ANDROID") {
                                    put("androidSdkVersion", 35)
                                }
                            },
                        )
                    },
                )
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", if (clientName == "ANDROID") "3" else "5")
                .header("X-YouTube-Client-Version", clientVersion)
                .header("Origin", "https://www.youtube.com")
                .build()

            val outcome = runCatching {
                client.newCall(req).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        loginRequired = true
                        return@use emptyList<ExtractedStream>()
                    }
                    if (!response.isSuccessful) return@use emptyList<ExtractedStream>()
                    val json = JSONObject(response.body?.string().orEmpty())
                    val playability = json.optJSONObject("playabilityStatus")
                    val status = playability?.optString("status").orEmpty()
                    if (status.equals("LOGIN_REQUIRED", ignoreCase = true)) {
                        loginRequired = true
                        return@use emptyList<ExtractedStream>()
                    }

                    val title = json.optJSONObject("videoDetails")
                        ?.optString("title")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::sanitizeTitle)
                        ?: "YouTube_$videoId"

                    val streamingData = json.optJSONObject("streamingData")
                        ?: return@use emptyList<ExtractedStream>()

                    val streamHeaders = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "https://www.youtube.com/",
                    )
                    val found = mutableListOf<ExtractedStream>()

                    // Best MP4A audio track for splitting/muxing with high-res adaptive video
                    val adaptive = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
                    var bestAudioUrl: String? = null
                    var bestAudioBitrate = -1L
                    val videoAdaptive = mutableListOf<JSONObject>()

                    for (i in 0 until adaptive.length()) {
                        val fmt = adaptive.optJSONObject(i) ?: continue
                        val fmtUrl = fmt.optString("url")
                        if (fmtUrl.isBlank()) continue
                        val mime = fmt.optString("mimeType").lowercase()
                        if (mime.startsWith("audio/mp4")) {
                            val bitrate = fmt.optLong("bitrate", 0L)
                            if (bitrate > bestAudioBitrate) {
                                bestAudioBitrate = bitrate
                                bestAudioUrl = fmtUrl
                            }
                        } else if (mime.startsWith("video/mp4")) {
                            videoAdaptive.add(fmt)
                        }
                    }

                    videoAdaptive.forEach { vFmt ->
                        val vUrl = vFmt.optString("url")
                        val height = vFmt.optInt("height", 1080)
                        val bytes = vFmt.optString("contentLength").toLongOrNull() ?: -1L
                        if (vUrl.isNotBlank() && bestAudioUrl != null) {
                            found.add(
                                ExtractedStream(
                                    streamUrl = vUrl,
                                    title = title,
                                    platform = PlatformBadge.YOUTUBE,
                                    qualityLabel = formatQualityLabel(height),
                                    resolutionHeight = height,
                                    mimeType = "video/mp4",
                                    estimatedBytes = bytes,
                                    headers = streamHeaders,
                                    audioStreamUrl = bestAudioUrl,
                                ),
                            )
                        }
                    }

                    // Progressive muxed formats
                    val formats = streamingData.optJSONArray("formats") ?: JSONArray()
                    for (i in 0 until formats.length()) {
                        val fmt = formats.optJSONObject(i) ?: continue
                        val fUrl = fmt.optString("url")
                        if (fUrl.isBlank()) continue
                        val height = fmt.optInt("height", 720)
                        val bytes = fmt.optString("contentLength").toLongOrNull() ?: -1L
                        found.add(
                            ExtractedStream(
                                streamUrl = fUrl,
                                title = title,
                                platform = PlatformBadge.YOUTUBE,
                                qualityLabel = formatQualityLabel(height),
                                resolutionHeight = height,
                                mimeType = "video/mp4",
                                estimatedBytes = bytes,
                                headers = streamHeaders,
                            ),
                        )
                    }

                    // HLS manifest fallback
                    val hlsUrl = streamingData.optString("hlsManifestUrl")
                    if (hlsUrl.isNotBlank()) {
                        parseBestHlsVariant(hlsUrl, PlatformBadge.YOUTUBE, title, streamHeaders)
                            ?.let { found.add(it) }
                    }

                    found
                }
            }.getOrDefault(emptyList())

            if (outcome.isNotEmpty()) {
                return HtmlProbeOutcome(outcome, loginRequired)
            }
        }

        return HtmlProbeOutcome(emptyList(), loginRequired)
    }

    private fun extractYoutubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})"""),
        )
        for (pattern in patterns) {
            val id = pattern.find(url)?.groupValues?.getOrNull(1)
            if (!id.isNullOrBlank()) return id
        }
        return null
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
                addCandidateIfMedia(found, pageTitle, platform, streamHeaders, candidates)
            }
        }

        val videoTagPatterns = listOf(
            Regex("""<(?:video|source)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<source[^>]+data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        )
        videoTagPatterns.forEach { regex ->
            regex.findAll(unescapedHtml).forEach { match ->
                val found = resolveRelativeUrl(finalPageUrl, match.groupValues[1])
                addCandidateIfMedia(found, pageTitle, platform, streamHeaders, candidates)
            }
        }

        val jsonStreamPatterns = listOf(
            Regex(""""(?:contentUrl|hd_src_no_ratelimit|hd_src|sd_src|playAddr|downloadAddr|video_url|playbackUrl|hlsUrl|mp4Url|url)"\s*:\s*"(https?://[^"]+\.(?:mp4|m3u8|webm|mov|m4v)[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s"'<>\\]+\.(?:mp4|m3u8|webm|mov|m4v)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE),
        )
        jsonStreamPatterns.forEach { regex ->
            regex.findAll(unescapedHtml).forEach { match ->
                addCandidateIfMedia(match.groupValues[1], pageTitle, platform, streamHeaders, candidates)
            }
        }

        val looksGated = finalPageUrl.lowercase().contains("/login") ||
            html.contains("login_required", ignoreCase = true)
        return HtmlProbeOutcome(
            streams = candidates.distinctBy { it.streamUrl },
            authChallenge = authChallenge || (looksGated && candidates.isEmpty()),
        )
    }

    private fun addCandidateIfMedia(
        url: String,
        title: String,
        platform: PlatformBadge,
        headers: Map<String, String>,
        out: MutableList<ExtractedStream>,
    ) {
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

    private fun deriveTitleFromUrl(url: String): String {
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
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
    }

    private fun resolveRelativeUrl(baseUrl: String, candidate: String): String {
        return runCatching {
            URI(baseUrl).resolve(candidate).toString()
        }.getOrDefault(candidate)
    }

    private fun looksLikeHlsUrl(url: String): Boolean {
        val clean = url.substringBefore("?").lowercase()
        return clean.endsWith(".m3u8") || url.lowercase().contains(".m3u8?")
    }

    private fun isHlsMime(contentType: String, url: String): Boolean {
        return contentType.contains("mpegurl") || looksLikeHlsUrl(url)
    }

    private fun isDirectVideoMimeOrExtension(contentType: String, url: String): Boolean {
        if (contentType.startsWith("video/")) return true
        val clean = url.substringBefore("?").lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".webm") ||
            clean.endsWith(".mov") || clean.endsWith(".mkv") || clean.endsWith(".m4v")
    }

    private fun looksLikeDownloadableVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val pathOnly = lower.substringBefore("?")
        return pathOnly.endsWith(".mp4") || pathOnly.endsWith(".webm") ||
            pathOnly.endsWith(".mov") || pathOnly.endsWith(".m4v") ||
            lower.contains("mime=video") || lower.contains("mime_type=video") ||
            lower.contains("video_mp4") || lower.contains(".mp4?")
    }

    private fun resolveMimeType(contentType: String, url: String): String {
        if (contentType.startsWith("video/")) return contentType.substringBefore(";").trim()
        val lower = url.substringBefore("?").lowercase()
        return when {
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".mkv") -> "video/x-matroska"
            else -> "video/mp4"
        }
    }

    private fun inferResolutionHeight(url: String): Int {
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

    private fun formatQualityLabel(height: Int): String = when {
        height >= 2160 -> "4K 2160P"
        height >= 1440 -> "2K 1440P"
        height >= 1080 -> "1080P FHD"
        height >= 720 -> "720P HD"
        height > 0 -> "${height}P"
        else -> "BEST HD"
    }

    companion object {
        fun defaultOkHttpClient(cookieJar: CookieJar = CookieJar.NO_COOKIES): OkHttpClient {
            return OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        fun createWithVault(cookieVault: CookieVaultManager): OkHttpClient {
            return defaultOkHttpClient(cookieVault.okHttpCookieJar)
        }
    }
}
