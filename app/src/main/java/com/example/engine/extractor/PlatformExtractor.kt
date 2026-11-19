package com.example.engine.extractor

import android.content.Context
import com.example.engine.UniversalDownloader
import com.example.engine.cookies.CookieVaultManager
import com.example.engine.model.ExtractedMediaInfo
import com.example.engine.model.ExtractedStream
import com.example.engine.model.ExtractionResult
import com.example.engine.model.MediaExtractionResult
import com.example.engine.model.PlatformBadge
import com.example.engine.model.VideoQualityOption
import com.example.engine.network.ClientSpoofer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.net.URLEncoder

/**
 * Strategy Pattern contract for platform-specific and fallback stream extractors.
 */
interface PlatformExtractor {
    val supportedPlatform: PlatformBadge
    fun canHandle(url: String): Boolean
    suspend fun extract(url: String): MediaExtractionResult
}

/**
 * Custom OkHttp-backed NewPipeExtractor Downloader so YouTube extraction shares
 * connection pooling, redirects, timeouts, and Session Vault cookies cleanly.
 */
class OkHttpNewPipeDownloader(
    baseOkHttpClient: OkHttpClient,
) : Downloader() {

    private val okHttpClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    @Throws(IOException::class)
    override fun execute(request: NewPipeRequest): NewPipeResponse {
        val bodyBytes = request.dataToSend()
        val requestBody = bodyBytes?.toRequestBody("application/json; charset=utf-8".toMediaType())

        val headerMap = mutableMapOf<String, String>()
        for ((key, values) in request.headers()) {
            if (values.isNotEmpty()) {
                headerMap[key] = values.joinToString(", ")
            }
        }
        if (!headerMap.containsKey("User-Agent")) {
            headerMap["User-Agent"] = ClientSpoofer.CHROME_DESKTOP_USER_AGENT
        }
        if (!headerMap.containsKey("Accept-Language")) {
            headerMap["Accept-Language"] = "en-US,en;q=0.9"
        }
        val reqUrlLower = request.url().lowercase()
        if (request.httpMethod().equals("GET", ignoreCase = true) &&
            (reqUrlLower.contains("youtube.com/") || reqUrlLower.contains("youtu.be/")) &&
            !reqUrlLower.contains("/youtubei/") &&
            !headerMap.containsKey("Cookie")
        ) {
            headerMap["Cookie"] = "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg; PREF=f6=40000000&hl=en&gl=US"
        }

        val okRequest = okhttp3.Request.Builder()
            .url(request.url())
            .headers(headerMap.toHeaders())
            .method(request.httpMethod(), requestBody)
            .build()

        okHttpClient.newCall(okRequest).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            return NewPipeResponse(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bodyString,
                response.request.url.toString(),
            )
        }
    }

    companion object {
        @Volatile
        private var initialized = false
        private val lock = Any()

        fun ensureNewPipeInitialized(okHttpClient: OkHttpClient) {
            if (initialized) return
            synchronized(lock) {
                if (!initialized) {
                    NewPipe.init(
                        OkHttpNewPipeDownloader(okHttpClient),
                        Localization("en", "US"),
                        ContentCountry("US"),
                    )
                    initialized = true
                }
            }
        }
    }
}

/**
 * YouTube Strategy powered by NewPipeExtractor (Videos & Shorts, no API key required),
 * exposing progressive 360p/720p plus adaptive 1080p/2K/4K video+audio pairs ready for MediaMuxer.
 * Falls back gracefully to client-side Innertube/HTML extraction if NewPipe throws.
 */
class YouTubeNewPipeExtractor(
    private val okHttpClient: OkHttpClient,
    private val fallbackExtractor: VideoStreamExtractor,
) : PlatformExtractor {

    override val supportedPlatform: PlatformBadge = PlatformBadge.YOUTUBE

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    override suspend fun extract(url: String): MediaExtractionResult = withContext(Dispatchers.IO) {
        val canonicalUrl = normalizeYouTubeUrl(url)
        OkHttpNewPipeDownloader.ensureNewPipeInitialized(okHttpClient)

        val newPipeAttempt = runCatching {
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, canonicalUrl)
            val title = streamInfo.name.orEmpty().ifBlank { "YouTube_Video" }
            val durationSec = streamInfo.duration.coerceAtLeast(0L)
            val thumbnailUrl = streamInfo.thumbnails
                .maxByOrNull { it.width * it.height }
                ?.url
                ?: extractYouTubeId(canonicalUrl)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

            val bestAudio = selectBestAudioStream(streamInfo.audioStreams)
            val options = mutableListOf<VideoQualityOption>()

            // 1. Progressive video+audio streams (typically 360p / 720p)
            streamInfo.videoStreams
                .filter { it.isUrl && it.content.isNotBlank() }
                .forEach { stream ->
                    val height = parseResolutionHeight(stream)
                    val format = stream.format
                    val mime = format?.mimeType ?: "video/mp4"
                    val estBytes = estimateStreamSize(
                        contentLength = stream.itagItem?.contentLength ?: -1L,
                        bitrate = stream.bitrate.toLong(),
                        durationSeconds = durationSec,
                    )
                    options.add(
                        VideoQualityOption(
                            id = "yt_prog_${height}_${stream.itag}",
                            qualityLabel = formatLabel(height, muxed = false),
                            resolutionHeight = height,
                            videoUrl = stream.content,
                            audioUrl = null,
                            mimeType = mime,
                            fileSizeBytes = estBytes,
                            headers = mapOf("User-Agent" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT),
                            bitrateBps = stream.bitrate.toLong(),
                            fps = stream.fps.coerceAtLeast(30),
                        ),
                    )
                }

            // 2. Adaptive high-resolution video-only streams (1080p, 1440p, 4K) paired with best audio for MediaMuxer
            val audioUrl = bestAudio?.takeIf { it.isUrl && it.content.isNotBlank() }?.content
            val audioSizeBytes = bestAudio?.let {
                estimateStreamSize(
                    contentLength = it.itagItem?.contentLength ?: -1L,
                    bitrate = (it.averageBitrate * 1000L),
                    durationSeconds = durationSec,
                )
            } ?: 0L

            if (!audioUrl.isNullOrBlank()) {
                streamInfo.videoOnlyStreams
                    .filter { it.isUrl && it.content.isNotBlank() && isMp4CompatibleVideo(it) }
                    .forEach { vStream ->
                        val height = parseResolutionHeight(vStream)
                        if (height >= 360) {
                            val videoBytes = estimateStreamSize(
                                contentLength = vStream.itagItem?.contentLength ?: -1L,
                                bitrate = vStream.bitrate.toLong(),
                                durationSeconds = durationSec,
                            )
                            val totalEst = when {
                                videoBytes > 0L && audioSizeBytes > 0L -> videoBytes + audioSizeBytes
                                videoBytes > 0L -> videoBytes
                                else -> -1L
                            }
                            options.add(
                                VideoQualityOption(
                                    id = "yt_mux_${height}_${vStream.itag}",
                                    qualityLabel = formatLabel(height, muxed = true),
                                    resolutionHeight = height,
                                    videoUrl = vStream.content,
                                    audioUrl = audioUrl,
                                    mimeType = "video/mp4",
                                    fileSizeBytes = totalEst,
                                    headers = mapOf("User-Agent" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT),
                                    bitrateBps = vStream.bitrate.toLong(),
                                    fps = vStream.fps.coerceAtLeast(30),
                                ),
                            )
                        }
                    }
            }

            val deduped = deduplicateQualityOptions(options)
            if (deduped.isEmpty()) {
                throw IllegalStateException("No playable YouTube streams returned by NewPipeExtractor")
            }

            ExtractedMediaInfo(
                sourceUrl = url,
                canonicalUrl = canonicalUrl,
                platform = PlatformBadge.YOUTUBE,
                title = title,
                author = streamInfo.uploaderName.orEmpty(),
                thumbnailUrl = thumbnailUrl,
                durationSeconds = durationSec,
                qualityOptions = deduped,
            )
        }

        newPipeAttempt.getOrNull()?.let { media ->
            return@withContext MediaExtractionResult.Success(media)
        }

        // Secondary fallback to existing multi-client Innertube/HTML extractor
        when (val fallback = fallbackExtractor.extractHighestQuality(canonicalUrl)) {
            is ExtractionResult.Success -> {
                val thumb = extractYouTubeId(canonicalUrl)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
                MediaExtractionResult.Success(
                    media = streamsToMediaInfo(
                        sourceUrl = url,
                        canonicalUrl = canonicalUrl,
                        platform = PlatformBadge.YOUTUBE,
                        title = fallback.bestStream.title,
                        thumbnailUrl = thumb,
                        streams = fallback.candidates,
                    ),
                )
            }
            is ExtractionResult.NeedsAuth -> MediaExtractionResult.NeedsAuth(fallback.url, fallback.reason)
            is ExtractionResult.Error -> MediaExtractionResult.Error(
                newPipeAttempt.exceptionOrNull()?.message ?: fallback.message,
            )
        }
    }

    private fun selectBestAudioStream(streams: List<AudioStream>): AudioStream? {
        return streams
            .filter { it.isUrl && it.content.isNotBlank() }
            .sortedWith(
                compareByDescending<AudioStream> {
                    it.format == MediaFormat.M4A || it.format?.mimeType?.contains("mp4", true) == true
                }.thenByDescending { it.averageBitrate },
            )
            .firstOrNull()
    }

    private fun isMp4CompatibleVideo(stream: VideoStream): Boolean {
        val format = stream.format
        val codec = stream.codec.orEmpty().lowercase()
        val isMp4 = format == MediaFormat.MPEG_4 || format?.mimeType?.contains("mp4", true) == true
        val isAvcOrHevc = codec.isBlank() || codec.contains("avc") || codec.contains("h264") || codec.contains("hev")
        return isMp4 && isAvcOrHevc
    }

    private fun parseResolutionHeight(stream: VideoStream): Int {
        if (stream.height > 0) return stream.height
        val match = Regex("""(\d{3,4})p""").find(stream.resolution.orEmpty())
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun normalizeYouTubeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val id = extractYouTubeId(trimmed) ?: return trimmed
        return "https://www.youtube.com/watch?v=$id"
    }

    private fun extractYouTubeId(url: String): String? {
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})"""),
        )
        for (pattern in patterns) {
            val found = pattern.find(url)?.groupValues?.getOrNull(1)
            if (!found.isNullOrBlank()) return found
        }
        return null
    }
}

/**
 * Instagram Strategy (Reels, Posts & IGTV) using OkHttp with mobile/desktop User-Agents,
 * redirect handling, embedded JSON parsing, and OpenGraph (og:video / og:image) metadata.
 */
class InstagramOkHttpExtractor(
    private val okHttpClient: OkHttpClient,
    private val fallbackExtractor: VideoStreamExtractor,
) : PlatformExtractor {

    override val supportedPlatform: PlatformBadge = PlatformBadge.INSTAGRAM

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("instagram.com") || lower.contains("instagr.am")
    }

    override suspend fun extract(url: String): MediaExtractionResult = withContext(Dispatchers.IO) {
        val pageMeta = scrapeOpenGraphMeta(
            okHttpClient = okHttpClient,
            targetUrl = url,
            userAgent = ClientSpoofer.TIKTOK_IOS_USER_AGENT,
            referer = "https://www.instagram.com/",
        )

        when (val result = fallbackExtractor.extractHighestQuality(url)) {
            is ExtractionResult.Success -> {
                MediaExtractionResult.Success(
                    streamsToMediaInfo(
                        sourceUrl = url,
                        canonicalUrl = pageMeta.finalUrl ?: url,
                        platform = PlatformBadge.INSTAGRAM,
                        title = pageMeta.title ?: result.bestStream.title,
                        thumbnailUrl = pageMeta.thumbnailUrl,
                        durationSeconds = pageMeta.durationSeconds,
                        streams = result.candidates,
                    ),
                )
            }
            is ExtractionResult.NeedsAuth -> MediaExtractionResult.NeedsAuth(result.url, result.reason)
            is ExtractionResult.Error -> MediaExtractionResult.Error(result.message)
        }
    }
}

/**
 * Facebook Strategy (Videos & Reels, fb.watch, share links) using OkHttp with mobile/desktop
 * User-Agents, redirect resolution, embedded JSON parsing, and OpenGraph (og:video) extraction.
 */
class FacebookOkHttpExtractor(
    private val okHttpClient: OkHttpClient,
    private val fallbackExtractor: VideoStreamExtractor,
) : PlatformExtractor {

    override val supportedPlatform: PlatformBadge = PlatformBadge.FACEBOOK

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com")
    }

    override suspend fun extract(url: String): MediaExtractionResult = withContext(Dispatchers.IO) {
        val pageMeta = scrapeOpenGraphMeta(
            okHttpClient = okHttpClient,
            targetUrl = url,
            userAgent = ClientSpoofer.ANDROID_CHROME_USER_AGENT,
            referer = "https://www.facebook.com/",
        )

        when (val result = fallbackExtractor.extractHighestQuality(pageMeta.finalUrl ?: url)) {
            is ExtractionResult.Success -> {
                MediaExtractionResult.Success(
                    streamsToMediaInfo(
                        sourceUrl = url,
                        canonicalUrl = pageMeta.finalUrl ?: url,
                        platform = PlatformBadge.FACEBOOK,
                        title = pageMeta.title ?: result.bestStream.title,
                        thumbnailUrl = pageMeta.thumbnailUrl,
                        durationSeconds = pageMeta.durationSeconds,
                        streams = result.candidates,
                    ),
                )
            }
            is ExtractionResult.NeedsAuth -> MediaExtractionResult.NeedsAuth(result.url, result.reason)
            is ExtractionResult.Error -> MediaExtractionResult.Error(result.message)
        }
    }
}

/**
 * Fallback strategy for TikTok, X/Twitter, Vimeo, Reddit, and Direct Video links.
 */
class UniversalFallbackExtractor(
    private val universalDownloader: UniversalDownloader,
) : PlatformExtractor {

    override val supportedPlatform: PlatformBadge = PlatformBadge.WEB_MEDIA

    override fun canHandle(url: String): Boolean = url.startsWith("http://", true) || url.startsWith("https://", true)

    override suspend fun extract(url: String): MediaExtractionResult = withContext(Dispatchers.IO) {
        val outcome = universalDownloader.routeAndExtract(url)
        when (val res = outcome.result) {
            is ExtractionResult.Success -> {
                MediaExtractionResult.Success(
                    streamsToMediaInfo(
                        sourceUrl = url,
                        canonicalUrl = outcome.canonicalUrl,
                        platform = outcome.platform,
                        title = res.bestStream.title,
                        thumbnailUrl = null,
                        streams = res.candidates,
                    ),
                )
            }
            is ExtractionResult.NeedsAuth -> MediaExtractionResult.NeedsAuth(res.url, res.reason)
            is ExtractionResult.Error -> MediaExtractionResult.Error(res.message)
        }
    }
}

/**
 * Extensible Strategy Pattern fallback targeting a configurable self-hosted yt-dlp microservice.
 * Activated seamlessly when an endpoint is configured and primary client extractors fail.
 */
class YtDlpFallbackExtractor(
    private val okHttpClient: OkHttpClient,
    private val endpointBaseUrl: String? = null,
) : PlatformExtractor {

    override val supportedPlatform: PlatformBadge = PlatformBadge.WEB_MEDIA

    override fun canHandle(url: String): Boolean = !endpointBaseUrl.isNullOrBlank() &&
        (url.startsWith("http://", true) || url.startsWith("https://", true))

    override suspend fun extract(url: String): MediaExtractionResult = withContext(Dispatchers.IO) {
        val base = endpointBaseUrl?.trimEnd('/')
            ?: return@withContext MediaExtractionResult.Error("Self-hosted yt-dlp fallback endpoint is not configured.")

        val encoded = URLEncoder.encode(url, "UTF-8")
        val req = okhttp3.Request.Builder()
            .url("$base/extract?url=$encoded")
            .header("Accept", "application/json")
            .get()
            .build()

        runCatching {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext MediaExtractionResult.Error("yt-dlp API error HTTP ${resp.code}")
                }
                val root = JSONObject(resp.body?.string().orEmpty())
                val title = root.optString("title").ifBlank { "Blackhole_Video" }
                val thumb = root.optString("thumbnail").takeIf { it.startsWith("http") }
                val duration = root.optLong("duration", 0L)
                val formats = root.optJSONArray("formats")
                val options = mutableListOf<VideoQualityOption>()

                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.optJSONObject(i) ?: continue
                        val streamUrl = f.optString("url")
                        if (!streamUrl.startsWith("http")) continue
                        val height = f.optInt("height", 0)
                        val ext = f.optString("ext", "mp4")
                        if (ext != "mp4" && ext != "webm" && !streamUrl.contains(".mp4")) continue
                        val filesize = f.optLong("filesize", -1L).takeIf { it > 0L }
                            ?: f.optLong("filesize_approx", -1L)
                        options.add(
                            VideoQualityOption(
                                id = "ytdlp_${height}_$i",
                                qualityLabel = if (height > 0) "${height}p" else "Best HD",
                                resolutionHeight = height,
                                videoUrl = streamUrl,
                                mimeType = if (ext == "webm") "video/webm" else "video/mp4",
                                fileSizeBytes = filesize,
                            ),
                        )
                    }
                }

                val deduped = deduplicateQualityOptions(options)
                if (deduped.isEmpty()) {
                    MediaExtractionResult.Error("Self-hosted yt-dlp returned no playable streams.")
                } else {
                    MediaExtractionResult.Success(
                        ExtractedMediaInfo(
                            sourceUrl = url,
                            canonicalUrl = url,
                            platform = PlatformBadge.fromUrl(url),
                            title = title,
                            thumbnailUrl = thumb,
                            durationSeconds = duration,
                            qualityOptions = deduped,
                        ),
                    )
                }
            }
        }.getOrElse { err ->
            MediaExtractionResult.Error(err.message ?: "yt-dlp fallback failed")
        }
    }
}

/**
 * Strategy Router that selects the best PlatformExtractor for YouTube, Instagram, Facebook,
 * and universal links, with optional self-hosted yt-dlp API fallback.
 */
class MultiPlatformExtractorRouter(
    context: Context,
    ytDlpEndpointUrl: String? = null,
) {
    private val appContext = context.applicationContext
    private val cookieVault = CookieVaultManager.getInstance(appContext)
    private val okHttpClient = VideoStreamExtractor.createWithVault(cookieVault)
    private val sniffer = WebViewStreamSniffer(appContext, cookieVault)
    private val streamExtractor = VideoStreamExtractor(okHttpClient, sniffer, cookieVault)
    private val universalDownloader = UniversalDownloader(
        context = appContext,
        cookieVault = cookieVault,
        httpClient = okHttpClient,
        webViewSniffer = sniffer,
        streamExtractor = streamExtractor,
    )

    private val primaryStrategies: List<PlatformExtractor> = listOf(
        YouTubeNewPipeExtractor(okHttpClient, streamExtractor),
        InstagramOkHttpExtractor(okHttpClient, streamExtractor),
        FacebookOkHttpExtractor(okHttpClient, streamExtractor),
        UniversalFallbackExtractor(universalDownloader),
    )

    private val ytDlpFallback = YtDlpFallbackExtractor(okHttpClient, ytDlpEndpointUrl)

    suspend fun extractMedia(rawUrl: String): MediaExtractionResult = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext MediaExtractionResult.Error("Please enter a valid http:// or https:// video link.")
        }

        val strategy = primaryStrategies.firstOrNull { it.canHandle(cleanUrl) }
            ?: UniversalFallbackExtractor(universalDownloader)

        val primaryResult = strategy.extract(cleanUrl)
        if (primaryResult is MediaExtractionResult.Success) {
            return@withContext primaryResult
        }

        if (ytDlpFallback.canHandle(cleanUrl)) {
            val fallbackResult = ytDlpFallback.extract(cleanUrl)
            if (fallbackResult is MediaExtractionResult.Success) {
                return@withContext fallbackResult
            }
        }
        primaryResult
    }
}

private data class ScrapedOpenGraphMeta(
    val finalUrl: String? = null,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long = 0L,
)

private fun scrapeOpenGraphMeta(
    okHttpClient: OkHttpClient,
    targetUrl: String,
    userAgent: String,
    referer: String,
): ScrapedOpenGraphMeta {
    return runCatching {
        val req = okhttp3.Request.Builder()
            .url(targetUrl)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        okHttpClient.newCall(req).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            if (!resp.isSuccessful) {
                return@use ScrapedOpenGraphMeta(finalUrl = finalUrl)
            }
            val html = resp.body?.string().orEmpty()
            val ogTitle = extractMetaContent(html, "og:title")
                ?: extractMetaContent(html, "twitter:title")
            val ogImage = extractMetaContent(html, "og:image:secure_url")
                ?: extractMetaContent(html, "og:image")
            val durationStr = extractMetaContent(html, "video:duration")
            val durationSec = durationStr?.toLongOrNull() ?: 0L

            ScrapedOpenGraphMeta(
                finalUrl = finalUrl,
                title = ogTitle?.trim()?.take(80)?.ifBlank { null },
                thumbnailUrl = ogImage?.replace("&amp;", "&")?.takeIf { it.startsWith("http") },
                durationSeconds = durationSec,
            )
        }
    }.getOrDefault(ScrapedOpenGraphMeta())
}

private fun extractMetaContent(html: String, propertyName: String): String? {
    val patterns = listOf(
        Regex("""<meta[^>]+(?:property|name)=["']${Regex.escape(propertyName)}["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']${Regex.escape(propertyName)}["']""", RegexOption.IGNORE_CASE),
    )
    for (p in patterns) {
        val found = p.find(html)?.groupValues?.getOrNull(1)
        if (!found.isNullOrBlank()) return found
    }
    return null
}

private fun streamsToMediaInfo(
    sourceUrl: String,
    canonicalUrl: String,
    platform: PlatformBadge,
    title: String,
    thumbnailUrl: String?,
    durationSeconds: Long = 0L,
    streams: List<ExtractedStream>,
): ExtractedMediaInfo {
    val options = streams.mapIndexed { idx, s ->
        VideoQualityOption(
            id = "stream_${s.resolutionHeight}_$idx",
            qualityLabel = s.qualityLabel,
            resolutionHeight = s.resolutionHeight,
            videoUrl = s.streamUrl,
            audioUrl = s.audioStreamUrl,
            mimeType = s.mimeType,
            fileSizeBytes = s.estimatedBytes,
            headers = s.headers,
            isHls = s.isHls,
            bitrateBps = s.bitrateBps,
        )
    }
    return ExtractedMediaInfo(
        sourceUrl = sourceUrl,
        canonicalUrl = canonicalUrl,
        platform = platform,
        title = title,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        qualityOptions = deduplicateQualityOptions(options),
    )
}

private fun deduplicateQualityOptions(options: List<VideoQualityOption>): List<VideoQualityOption> {
    return options
        .sortedWith(
            compareByDescending<VideoQualityOption> { it.resolutionHeight }
                .thenBy { it.requiresMuxing } // Prefer progressive when same resolution exists
                .thenByDescending { it.bitrateBps }
                .thenByDescending { it.fileSizeBytes },
        )
        .distinctBy { "${it.resolutionHeight}_${it.requiresMuxing}" }
}

private fun formatLabel(height: Int, muxed: Boolean): String = when {
    height >= 2160 -> if (muxed) "4K 2160p · Muxed" else "4K 2160p"
    height >= 1440 -> if (muxed) "2K 1440p · Muxed" else "2K 1440p"
    height >= 1080 -> if (muxed) "1080p FHD · Muxed" else "1080p FHD"
    height >= 720 -> if (muxed) "720p HD · Muxed" else "720p HD"
    height > 0 -> "${height}p"
    else -> "Best Available"
}

private fun estimateStreamSize(
    contentLength: Long,
    bitrate: Long,
    durationSeconds: Long,
): Long {
    if (contentLength > 0L) return contentLength
    if (bitrate > 0L && durationSeconds > 0L) {
        return (bitrate * durationSeconds) / 8L
    }
    return -1L
}
