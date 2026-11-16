package com.example.engine.extractor

import com.example.engine.model.ExtractedStream
import com.example.engine.model.ExtractionResult
import com.example.engine.model.PlatformBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class VideoStreamExtractor(
    private val client: OkHttpClient = defaultOkHttpClient(),
) {

    suspend fun extractHighestQuality(rawUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext ExtractionResult.Error("Invalid URL scheme. Expected http:// or https://")
        }

        val platform = PlatformBadge.fromUrl(cleanUrl)

        return@withContext try {
            // Strategy 1: Direct media URL probe via HEAD / Range GET
            val directCandidate = probeDirectMedia(cleanUrl, platform)
            if (directCandidate != null) {
                return@withContext ExtractionResult.Success(
                    bestStream = directCandidate,
                    candidates = listOf(directCandidate),
                )
            }

            // Strategy 2: Platform-specific JSON endpoints (e.g., Vimeo config, Reddit JSON)
            val platformStreams = when (platform) {
                PlatformBadge.VIMEO -> extractVimeoStreams(cleanUrl)
                PlatformBadge.REDDIT -> extractRedditStreams(cleanUrl)
                else -> emptyList()
            }
            if (platformStreams.isNotEmpty()) {
                val sorted = platformStreams.sortedByDescending { it.resolutionHeight }
                return@withContext ExtractionResult.Success(
                    bestStream = sorted.first(),
                    candidates = sorted,
                )
            }

            // Strategy 3: Webpage HTML5 / OpenGraph / JSON-LD / Player Manifest extraction
            val htmlStreams = extractFromWebPage(cleanUrl, platform)
            if (htmlStreams.isNotEmpty()) {
                val sorted = htmlStreams
                    .distinctBy { it.streamUrl }
                    .sortedWith(
                        compareByDescending<ExtractedStream> { it.resolutionHeight }
                            .thenByDescending { it.estimatedBytes },
                    )
                return@withContext ExtractionResult.Success(
                    bestStream = sorted.first(),
                    candidates = sorted,
                )
            }

            ExtractionResult.Error(
                "No downloadable progressive video stream exposed for this link. " +
                    "Protected DRM or login-gated pages cannot be pulled directly.",
            )
        } catch (e: Exception) {
            ExtractionResult.Error(e.message ?: "Stream extraction failed")
        }
    }

    private fun probeDirectMedia(url: String, platform: PlatformBadge): ExtractedStream? {
        val headRequest = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", MOBILE_USER_AGENT)
            .build()

        runCatching {
            client.newCall(headRequest).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val finalUrl = response.request.url.toString()
                if (response.isSuccessful && isDirectVideoMimeOrExtension(contentType, finalUrl)) {
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
                    )
                }
            }
        }

        // Fallback Range GET for servers that reject HEAD
        val rangeRequest = Request.Builder()
            .url(url)
            .get()
            .header("Range", "bytes=0-1023")
            .header("User-Agent", MOBILE_USER_AGENT)
            .build()

        runCatching {
            client.newCall(rangeRequest).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val finalUrl = response.request.url.toString()
                if ((response.isSuccessful || response.code == 206) &&
                    isDirectVideoMimeOrExtension(contentType, finalUrl)
                ) {
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
                    )
                }
            }
        }
        return null
    }

    private fun extractVimeoStreams(url: String): List<ExtractedStream> {
        val idMatch = Regex("""vimeo\.com/(?:video/)?(\d+)""").find(url) ?: return emptyList()
        val videoId = idMatch.groupValues[1]
        val configUrl = "https://player.vimeo.com/video/$videoId/config"
        val request = Request.Builder()
            .url(configUrl)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Referer", "https://vimeo.com/")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val title = json.optJSONObject("video")?.optString("title").orEmpty()
                    .ifBlank { "Vimeo_$videoId" }
                val progressive = json.optJSONObject("request")
                    ?.optJSONObject("files")
                    ?.optJSONArray("progressive")
                    ?: return emptyList()

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
            .header("User-Agent", "BlackholePulse/1.0 (Android)")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val raw = response.body?.string().orEmpty()
                val rootArray = JSONArray(raw)
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
                listOf(
                    ExtractedStream(
                        streamUrl = fallbackUrl,
                        title = title,
                        platform = PlatformBadge.REDDIT,
                        qualityLabel = formatQualityLabel(height),
                        resolutionHeight = height,
                        mimeType = "video/mp4",
                        estimatedBytes = -1L,
                    ),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun extractFromWebPage(url: String, platform: PlatformBadge): List<ExtractedStream> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val html: String
        val finalPageUrl: String
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            finalPageUrl = response.request.url.toString()
            if (isDirectVideoMimeOrExtension(contentType, finalPageUrl)) {
                val height = inferResolutionHeight(finalPageUrl)
                return listOf(
                    ExtractedStream(
                        streamUrl = finalPageUrl,
                        title = deriveTitleFromUrl(finalPageUrl),
                        platform = platform,
                        qualityLabel = formatQualityLabel(height),
                        resolutionHeight = height,
                        mimeType = resolveMimeType(contentType, finalPageUrl),
                        estimatedBytes = response.header("Content-Length")?.toLongOrNull() ?: -1L,
                    ),
                )
            }
            html = response.body?.string().orEmpty()
        }

        if (html.isBlank()) return emptyList()
        val pageTitle = extractPageTitle(html) ?: deriveTitleFromUrl(finalPageUrl)
        val unescapedHtml = unescapeMarkup(html)
        val candidates = mutableListOf<ExtractedStream>()

        // 1. OpenGraph & Twitter Meta Tags
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
                if (looksLikeDownloadableVideoUrl(found)) {
                    val height = inferResolutionHeight(found)
                    candidates.add(
                        ExtractedStream(
                            streamUrl = found,
                            title = pageTitle,
                            platform = platform,
                            qualityLabel = formatQualityLabel(height),
                            resolutionHeight = height,
                            mimeType = resolveMimeType("", found),
                            estimatedBytes = -1L,
                            headers = mapOf("Referer" to finalPageUrl),
                        ),
                    )
                }
            }
        }

        // 2. HTML5 <video> and <source> elements
        val videoTagPatterns = listOf(
            Regex("""<(?:video|source)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<source[^>]+data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        )
        videoTagPatterns.forEach { regex ->
            regex.findAll(unescapedHtml).forEach { match ->
                val found = resolveRelativeUrl(finalPageUrl, match.groupValues[1])
                if (looksLikeDownloadableVideoUrl(found)) {
                    val height = inferResolutionHeight(found)
                    candidates.add(
                        ExtractedStream(
                            streamUrl = found,
                            title = pageTitle,
                            platform = platform,
                            qualityLabel = formatQualityLabel(height),
                            resolutionHeight = height,
                            mimeType = resolveMimeType("", found),
                            estimatedBytes = -1L,
                            headers = mapOf("Referer" to finalPageUrl),
                        ),
                    )
                }
            }
        }

        // 3. JSON-LD contentUrl and embedded player JSON streams
        val jsonStreamPatterns = listOf(
            Regex(""""(?:contentUrl|hd_src_no_ratelimit|hd_src|sd_src|playAddr|downloadAddr|video_url|playbackUrl|mp4Url|url)"\s*:\s*"(https?://[^"]+\.(?:mp4|webm|mov|m4v)[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s"'<>\\]+\.(?:mp4|webm|mov|m4v)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE),
        )
        jsonStreamPatterns.forEach { regex ->
            regex.findAll(unescapedHtml).forEach { match ->
                val found = match.groupValues[1]
                if (looksLikeDownloadableVideoUrl(found)) {
                    val height = inferResolutionHeight(found)
                    candidates.add(
                        ExtractedStream(
                            streamUrl = found,
                            title = pageTitle,
                            platform = platform,
                            qualityLabel = formatQualityLabel(height),
                            resolutionHeight = height,
                            mimeType = resolveMimeType("", found),
                            estimatedBytes = -1L,
                            headers = mapOf("Referer" to finalPageUrl),
                        ),
                    )
                }
            }
        }

        return candidates.distinctBy { it.streamUrl }
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
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
