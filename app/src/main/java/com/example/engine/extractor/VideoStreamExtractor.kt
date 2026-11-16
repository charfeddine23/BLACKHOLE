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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
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

            // Strategy 2: Platform-specific endpoints (Vimeo config, Reddit video+audio, etc.)
            val platformStreams = when (platform) {
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
            lower.contains("mime=video") || lower.contains(".mp4?")
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
            lower.contains("720") || lower.contains("hd") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            else -> 1080
        }
    }

    private fun formatQualityLabel(height: Int): String = when {
        height >= 2160 -> "4K 2160P"
        height >= 1440 -> "2K 1440P"
        height >= 1080 -> "1080P FHD"
        height >= 720 -> "720P HD"
        else -> "${height}P"
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
