package com.example.engine.extractor

import com.example.engine.cookies.CookieVaultManager
import com.example.engine.model.ExtractedStream
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal data class YoutubeExtractionOutcome(
    val streams: List<ExtractedStream>,
    val authChallenge: Boolean,
)

/**
 * Multi-tier YouTube extraction engine combining:
 * 1. NewPipeExtractor v0.26.0 (full Rhino JS signature & n-param deobfuscation, cookieless + EU SOCS bypass)
 * 2. Live watch & embed page context with EU consent (SOCS) bypass for Innertube API key + Visitor ID
 * 3. Strictly isolated native (cookieless, zero Web Origin/Referer) & web/TV (SAPISIDHASH) Innertube cascade
 * 4. Watch-HTML ytInitialPlayerResponse parsing with balanced-brace JSON extraction
 * 5. Resilient Piped / Invidious public mirror fallback
 * 6. Balanced per-tier reachability gate (progressive + adaptive + HLS) so high-res 403s never hide working streams
 */
internal class YoutubePowerExtractor(
    private val client: OkHttpClient,
    private val cookieVault: CookieVaultManager?,
) {

    private val cookielessClient: OkHttpClient by lazy {
        client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }

    private sealed interface CipherOp {
        data object Reverse : CipherOp
        data class Splice(val count: Int) : CipherOp
        data class Swap(val index: Int) : CipherOp
    }

    private val cipherCache = ConcurrentHashMap<String, List<CipherOp>>()

    fun extract(url: String): YoutubeExtractionOutcome {
        val videoId = extractVideoId(url) ?: return YoutubeExtractionOutcome(emptyList(), false)
        var loginRequired = false

        // Tier 1: NewPipeExtractor (cookieless + EU SOCS consent cookie)
        val newPipeStreams = extractViaNewPipe(videoId)
        val reachableNewPipe = filterReachableStreams(newPipeStreams)
        if (reachableNewPipe.isNotEmpty()) {
            return YoutubeExtractionOutcome(reachableNewPipe, false)
        }

        // Tier 2: Watch & Embed context (bypasses EU consent.youtube.com wall with SOCS cookie)
        val watchContext = fetchWatchPageContext(videoId)
        val cookieAuthHeader = buildSapisidAuthorizationHeader()

        // Tier 3: Watch page embedded ytInitialPlayerResponse (deciphered)
        if (watchContext.playerResponse != null) {
            val status = watchContext.playerResponse.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
            if (status.equals("LOGIN_REQUIRED", ignoreCase = true)) {
                loginRequired = true
            } else {
                val decoded = parsePlayerJson(
                    json = watchContext.playerResponse,
                    videoId = videoId,
                    userAgent = ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
                    jsUrl = watchContext.jsUrl,
                    isWebOrigin = true,
                )
                val reachable = filterReachableStreams(decoded)
                if (reachable.isNotEmpty()) {
                    return YoutubeExtractionOutcome(reachable, loginRequired)
                }
            }
        }

        // Tier 4: Multi-client Innertube cascade with strict cookie & Origin/Referer isolation
        data class YtClientSpec(
            val clientName: String,
            val clientVersion: String,
            val clientId: String,
            val userAgent: String,
            val isEmbedded: Boolean = false,
            val isWebOrigin: Boolean = false,
            val sendCookiesAndAuth: Boolean = false,
            val extraClientFields: Map<String, Any> = emptyMap(),
        )

        val clients = listOf(
            YtClientSpec(
                clientName = "ANDROID_VR",
                clientVersion = "1.61.48",
                clientId = "28",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 14; Quest 3 Build/UP1A.231005.007) gzip",
                extraClientFields = mapOf(
                    "deviceMake" to "Oculus",
                    "deviceModel" to "Quest 3",
                    "osName" to "Android",
                    "osVersion" to "14",
                    "androidSdkVersion" to 34,
                ),
            ),
            YtClientSpec(
                clientName = "IOS",
                clientVersion = "20.10.4",
                clientId = "5",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)",
                extraClientFields = mapOf(
                    "deviceMake" to "Apple",
                    "deviceModel" to "iPhone16,2",
                    "osName" to "iPhone",
                    "osVersion" to "18.3.2.22D82",
                ),
            ),
            YtClientSpec(
                clientName = "WEB_EMBEDDED_PLAYER",
                clientVersion = "1.20250319.01.00",
                clientId = "56",
                userAgent = ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
                isEmbedded = true,
                isWebOrigin = true,
                sendCookiesAndAuth = true,
            ),
            YtClientSpec(
                clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                clientVersion = "2.0",
                clientId = "85",
                userAgent = "Mozilla/5.0 (PlayStation; PlayStation 5/6.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
                isEmbedded = true,
                isWebOrigin = true,
                sendCookiesAndAuth = true,
            ),
            YtClientSpec(
                clientName = "TVHTML5",
                clientVersion = "7.20250319.10.00",
                clientId = "7",
                userAgent = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) 94.0.4606.31/7.0 TV Safari/537.36",
                isWebOrigin = true,
                sendCookiesAndAuth = true,
            ),
            YtClientSpec(
                clientName = "ANDROID_TESTSUITE",
                clientVersion = "1.9",
                clientId = "30",
                userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 14; Pixel 8) gzip",
                extraClientFields = mapOf(
                    "androidSdkVersion" to 34,
                    "osName" to "Android",
                    "osVersion" to "14",
                ),
            ),
            YtClientSpec(
                clientName = "ANDROID",
                clientVersion = "20.10.38",
                clientId = "3",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 15; Pixel 9 Pro) gzip",
                extraClientFields = mapOf(
                    "androidSdkVersion" to 35,
                    "osName" to "Android",
                    "osVersion" to "15",
                ),
            ),
            YtClientSpec(
                clientName = "MWEB",
                clientVersion = "2.20250319.01.00",
                clientId = "2",
                userAgent = ClientSpoofer.TIKTOK_IOS_USER_AGENT,
                isWebOrigin = true,
                sendCookiesAndAuth = true,
            ),
        )

        val apiKeyQuery = watchContext.apiKey?.let { "?key=$it&prettyPrint=false" } ?: "?prettyPrint=false"
        for (spec in clients) {
            val useAuthSession = spec.sendCookiesAndAuth && !cookieAuthHeader.isNullOrBlank()
            val callClient = if (useAuthSession) client else cookielessClient

            val payload = JSONObject().apply {
                put("videoId", videoId)
                put(
                    "context",
                    JSONObject().apply {
                        put(
                            "client",
                            JSONObject().apply {
                                put("clientName", spec.clientName)
                                put("clientVersion", spec.clientVersion)
                                put("hl", "en")
                                put("gl", "US")
                                if (!watchContext.visitorData.isNullOrBlank()) {
                                    put("visitorData", watchContext.visitorData)
                                }
                                spec.extraClientFields.forEach { (k, v) -> put(k, v) }
                            },
                        )
                        if (spec.isEmbedded) {
                            put(
                                "thirdParty",
                                JSONObject().apply {
                                    put("embedUrl", "https://www.youtube.com/watch?v=$videoId")
                                },
                            )
                        }
                    },
                )
                if (watchContext.sts != null) {
                    put(
                        "playbackContext",
                        JSONObject().apply {
                            put(
                                "contentPlaybackContext",
                                JSONObject().apply {
                                    put("signatureTimestamp", watchContext.sts)
                                },
                            )
                        },
                    )
                }
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player$apiKeyQuery")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", spec.userAgent)
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", spec.clientId)
                .header("X-YouTube-Client-Version", spec.clientVersion)

            if (spec.isWebOrigin) {
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/watch?v=$videoId")
            }

            if (!watchContext.visitorData.isNullOrBlank()) {
                reqBuilder.header("X-Goog-Visitor-Id", watchContext.visitorData)
            }
            if (useAuthSession && !cookieAuthHeader.isNullOrBlank()) {
                reqBuilder.header("Authorization", cookieAuthHeader)
                reqBuilder.header("X-Origin", "https://www.youtube.com")
            }

            val parsedStreams = runCatching {
                callClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        loginRequired = true
                        return@use emptyList()
                    }
                    if (!response.isSuccessful) return@use emptyList()
                    val json = JSONObject(response.body?.string().orEmpty())
                    val status = json.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                    if (status.equals("LOGIN_REQUIRED", ignoreCase = true)) {
                        loginRequired = true
                        return@use emptyList()
                    }
                    parsePlayerJson(
                        json = json,
                        videoId = videoId,
                        userAgent = spec.userAgent,
                        jsUrl = watchContext.jsUrl,
                        isWebOrigin = spec.isWebOrigin,
                    )
                }
            }.getOrDefault(emptyList())

            val reachable = filterReachableStreams(parsedStreams)
            if (reachable.isNotEmpty()) {
                return YoutubeExtractionOutcome(reachable, loginRequired)
            }
        }

        // Tier 5: Public Invidious / Piped API mirrors when device IP hits bot/poToken gates
        val mirrorStreams = filterReachableStreams(fetchFromPublicMirrors(videoId))
        if (mirrorStreams.isNotEmpty()) {
            return YoutubeExtractionOutcome(mirrorStreams, loginRequired)
        }

        return YoutubeExtractionOutcome(emptyList(), loginRequired)
    }

    private fun extractViaNewPipe(videoId: String): List<ExtractedStream> {
        return runCatching {
            OkHttpNewPipeDownloader.ensureNewPipeInitialized(cookielessClient)
            val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"
            val info = StreamInfo.getInfo(ServiceList.YouTube, canonicalUrl)
            val title = info.name.orEmpty()
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(72)
                .ifBlank { "YouTube_$videoId" }
            val durationSec = info.duration.coerceAtLeast(0L)
            val headers = mapOf("User-Agent" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
            val out = mutableListOf<ExtractedStream>()

            info.videoStreams
                .filter { it.isUrl && it.content.isNotBlank() }
                .forEach { stream ->
                    val height = parseNewPipeResolution(stream)
                    val mime = stream.format?.mimeType ?: "video/mp4"
                    val estBytes = estimateStreamSize(
                        contentLength = stream.itagItem?.contentLength ?: -1L,
                        bitrate = stream.bitrate.toLong(),
                        durationSec = durationSec,
                    )
                    out.add(
                        ExtractedStream(
                            streamUrl = stream.content,
                            title = title,
                            platform = PlatformBadge.YOUTUBE,
                            qualityLabel = formatQualityLabel(height),
                            resolutionHeight = height,
                            mimeType = mime,
                            estimatedBytes = estBytes,
                            headers = headers,
                            bitrateBps = stream.bitrate.toLong(),
                        ),
                    )
                }

            val bestAudio = selectBestNewPipeAudio(info.audioStreams)
            val audioUrl = bestAudio?.takeIf { it.isUrl && it.content.isNotBlank() }?.content
            val audioBytes = bestAudio?.let {
                estimateStreamSize(
                    contentLength = it.itagItem?.contentLength ?: -1L,
                    bitrate = it.averageBitrate.toLong() * 1000L,
                    durationSec = durationSec,
                )
            } ?: 0L

            if (!audioUrl.isNullOrBlank()) {
                info.videoOnlyStreams
                    .filter { it.isUrl && it.content.isNotBlank() && isMp4CompatibleNewPipeVideo(it) }
                    .forEach { vStream ->
                        val height = parseNewPipeResolution(vStream)
                        if (height >= 360) {
                            val vBytes = estimateStreamSize(
                                contentLength = vStream.itagItem?.contentLength ?: -1L,
                                bitrate = vStream.bitrate.toLong(),
                                durationSec = durationSec,
                            )
                            val totalBytes = when {
                                vBytes > 0L && audioBytes > 0L -> vBytes + audioBytes
                                vBytes > 0L -> vBytes
                                else -> -1L
                            }
                            out.add(
                                ExtractedStream(
                                    streamUrl = vStream.content,
                                    title = title,
                                    platform = PlatformBadge.YOUTUBE,
                                    qualityLabel = formatQualityLabel(height),
                                    resolutionHeight = height,
                                    mimeType = "video/mp4",
                                    estimatedBytes = totalBytes,
                                    headers = headers,
                                    audioStreamUrl = audioUrl,
                                    bitrateBps = vStream.bitrate.toLong(),
                                ),
                            )
                        }
                    }
            }

            val hlsUrl = runCatching { info.hlsUrl }.getOrNull()
            if (!hlsUrl.isNullOrBlank() && hlsUrl.startsWith("http")) {
                out.add(
                    ExtractedStream(
                        streamUrl = hlsUrl,
                        title = title,
                        platform = PlatformBadge.YOUTUBE,
                        qualityLabel = "BEST HD",
                        resolutionHeight = 1080,
                        mimeType = "application/vnd.apple.mpegurl",
                        estimatedBytes = -1L,
                        headers = headers,
                        isHls = true,
                    ),
                )
            }

            out
        }.getOrDefault(emptyList())
    }

    private fun selectBestNewPipeAudio(streams: List<AudioStream>): AudioStream? {
        return streams
            .filter { it.isUrl && it.content.isNotBlank() }
            .sortedWith(
                compareByDescending<AudioStream> {
                    it.format == MediaFormat.M4A || it.format?.mimeType?.contains("mp4", true) == true
                }.thenByDescending { it.averageBitrate },
            )
            .firstOrNull()
    }

    private fun isMp4CompatibleNewPipeVideo(stream: VideoStream): Boolean {
        val format = stream.format
        val codec = stream.codec.orEmpty().lowercase()
        val isMp4 = format == MediaFormat.MPEG_4 || format?.mimeType?.contains("mp4", true) == true
        val isAvcOrHevc = codec.isBlank() || codec.contains("avc") || codec.contains("h264") || codec.contains("hev")
        return isMp4 && isAvcOrHevc
    }

    private fun parseNewPipeResolution(stream: VideoStream): Int {
        if (stream.height > 0) return stream.height
        return Regex("""(\d{3,4})p""").find(stream.resolution.orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 720
    }

    private fun estimateStreamSize(contentLength: Long, bitrate: Long, durationSec: Long): Long {
        if (contentLength > 0L) return contentLength
        if (bitrate > 0L && durationSec > 0L) return (bitrate * durationSec) / 8L
        return -1L
    }

    fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/(?:shorts|live|embed|reel|reels|v)/([A-Za-z0-9_-]{11})"""),
        )
        for (pattern in patterns) {
            val id = pattern.find(url)?.groupValues?.getOrNull(1)
            if (!id.isNullOrBlank()) return id
        }
        return null
    }

    private data class WatchPageContext(
        val apiKey: String?,
        val visitorData: String?,
        val sts: Int?,
        val jsUrl: String?,
        val playerResponse: JSONObject?,
    )

    private fun fetchWatchPageContext(videoId: String): WatchPageContext {
        val candidatePages = listOf(
            "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
            "https://www.youtube.com/embed/$videoId" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
            "https://m.youtube.com/watch?v=$videoId" to ClientSpoofer.ANDROID_CHROME_USER_AGENT,
        )

        var resolvedApiKey: String? = null
        var resolvedVisitorData: String? = null
        var resolvedSts: Int? = null
        var resolvedJsUrl: String? = null
        var resolvedPlayer: JSONObject? = null

        val useAuthenticatedFirst = !buildSapisidAuthorizationHeader().isNullOrBlank()
        val fetchClient = if (useAuthenticatedFirst) client else cookielessClient

        for ((pageUrl, ua) in candidatePages) {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", EU_SOCS_CONSENT_COOKIE)
                .build()

            runCatching {
                fetchClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val html = response.body?.string().orEmpty()
                    if (html.isBlank()) return@use

                    if (resolvedApiKey.isNullOrBlank()) {
                        resolvedApiKey = Regex(""""INNERTUBE_API_KEY"\s*:\s*"([^"]+)"""")
                            .find(html)?.groupValues?.getOrNull(1)
                    }
                    if (resolvedVisitorData.isNullOrBlank()) {
                        resolvedVisitorData = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
                            .find(html)?.groupValues?.getOrNull(1)
                    }
                    if (resolvedSts == null) {
                        resolvedSts = Regex(""""STS"\s*:\s*(\d+)""")
                            .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    }
                    if (resolvedJsUrl.isNullOrBlank()) {
                        resolvedJsUrl = Regex(""""jsUrl"\s*:\s*"([^"]+)"""")
                            .find(html)?.groupValues?.getOrNull(1)
                    }
                    if (resolvedPlayer == null) {
                        val playerJsonStr = extractJsonObjectAfterMarker(html, "ytInitialPlayerResponse")
                        resolvedPlayer = playerJsonStr?.let { runCatching { JSONObject(it) }.getOrNull() }
                    }
                }
            }

            if (!resolvedApiKey.isNullOrBlank() &&
                !resolvedVisitorData.isNullOrBlank() &&
                resolvedPlayer != null
            ) {
                break
            }
        }

        // If visitorData is still missing despite obtaining apiKey, fetch a fresh visitorData via visitor_id endpoint
        if (resolvedVisitorData.isNullOrBlank() && !resolvedApiKey.isNullOrBlank()) {
            resolvedVisitorData = fetchVisitorData(resolvedApiKey!!)
        }

        return WatchPageContext(
            apiKey = resolvedApiKey,
            visitorData = resolvedVisitorData,
            sts = resolvedSts,
            jsUrl = resolvedJsUrl,
            playerResponse = resolvedPlayer,
        )
    }

    private fun fetchVisitorData(apiKey: String): String? {
        return runCatching {
            val payload = JSONObject().apply {
                put(
                    "context",
                    JSONObject().apply {
                        put(
                            "client",
                            JSONObject().apply {
                                put("clientName", "WEB")
                                put("clientVersion", "2.20250319.01.00")
                                put("hl", "en")
                                put("gl", "US")
                            },
                        )
                    },
                )
            }
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/visitor_id?key=$apiKey&prettyPrint=false")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                .header("Content-Type", "application/json")
                .build()
            cookielessClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                json.optJSONObject("responseContext")?.optString("visitorData")
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun parsePlayerJson(
        json: JSONObject,
        videoId: String,
        userAgent: String,
        jsUrl: String?,
        isWebOrigin: Boolean,
    ): List<ExtractedStream> {
        val details = json.optJSONObject("videoDetails")
        val title = details?.optString("title")
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.take(72)
            ?: "YouTube_$videoId"

        val streamingData = json.optJSONObject("streamingData") ?: return emptyList()
        val streamHeaders = if (isWebOrigin) {
            mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com",
            )
        } else {
            mapOf("User-Agent" to userAgent)
        }
        val found = mutableListOf<ExtractedStream>()

        fun resolveFormatUrl(fmt: JSONObject): String? {
            val direct = fmt.optString("url")
            if (direct.startsWith("http")) return direct

            val cipherQuery = fmt.optString("signatureCipher")
                .ifBlank { fmt.optString("cipher") }
            if (cipherQuery.isBlank()) return null

            val params = parseQueryString(cipherQuery)
            val baseUrl = params["url"] ?: return null
            val rawSig = params["s"] ?: return baseUrl
            val sp = params["sp"] ?: "signature"

            val ops = if (!jsUrl.isNullOrBlank()) getCipherOps(jsUrl) else emptyList()
            val decipheredSig = if (ops.isNotEmpty()) applyCipherOps(rawSig, ops) else rawSig
            val separator = if (baseUrl.contains("?")) "&" else "?"
            return "$baseUrl$separator${URLEncoder.encode(sp, "UTF-8")}=${URLEncoder.encode(decipheredSig, "UTF-8")}"
        }

        val adaptive = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
        var bestAudioUrl: String? = null
        var bestAudioBitrate = -1L
        val videoAdaptiveAll = mutableListOf<Pair<JSONObject, String>>()

        for (i in 0 until adaptive.length()) {
            val fmt = adaptive.optJSONObject(i) ?: continue
            val fmtUrl = resolveFormatUrl(fmt) ?: continue
            val mime = fmt.optString("mimeType").lowercase()
            if (mime.startsWith("audio/mp4")) {
                val bitrate = fmt.optLong("bitrate", 0L)
                if (bitrate > bestAudioBitrate) {
                    bestAudioBitrate = bitrate
                    bestAudioUrl = fmtUrl
                }
            } else if (mime.startsWith("video/mp4")) {
                videoAdaptiveAll.add(fmt to fmtUrl)
            }
        }

        // Prefer H.264 (avc1) over AV1/VP9 so Android's MPEG_4 MediaMuxer always muxes A+V reliably
        val h264Adaptive = videoAdaptiveAll.filter { (fmt, _) ->
            val m = fmt.optString("mimeType").lowercase()
            m.contains("avc1") || (!m.contains("av01") && !m.contains("vp9") && !m.contains("vp09"))
        }
        val videoAdaptive = h264Adaptive.ifEmpty { videoAdaptiveAll }

        videoAdaptive.forEach { (vFmt, vUrl) ->
            val height = computeQualityTier(vFmt.optInt("width", 0), vFmt.optInt("height", 1080))
            val bytes = vFmt.optString("contentLength").toLongOrNull() ?: -1L
            val bitrate = vFmt.optLong("bitrate", 0L)
            if (bestAudioUrl != null) {
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
                        bitrateBps = bitrate,
                    ),
                )
            }
        }

        val formats = streamingData.optJSONArray("formats") ?: JSONArray()
        for (i in 0 until formats.length()) {
            val fmt = formats.optJSONObject(i) ?: continue
            val fUrl = resolveFormatUrl(fmt) ?: continue
            val height = computeQualityTier(fmt.optInt("width", 0), fmt.optInt("height", 720))
            val bytes = fmt.optString("contentLength").toLongOrNull() ?: -1L
            val bitrate = fmt.optLong("bitrate", 0L)
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
                    bitrateBps = bitrate,
                ),
            )
        }

        val hlsUrl = streamingData.optString("hlsManifestUrl")
        if (hlsUrl.startsWith("http")) {
            found.add(
                ExtractedStream(
                    streamUrl = hlsUrl,
                    title = title,
                    platform = PlatformBadge.YOUTUBE,
                    qualityLabel = "BEST HD",
                    resolutionHeight = 1080,
                    mimeType = "application/vnd.apple.mpegurl",
                    estimatedBytes = -1L,
                    headers = streamHeaders,
                    isHls = true,
                ),
            )
        }

        return found
    }

    private fun fetchFromPublicMirrors(videoId: String): List<ExtractedStream> {
        val titleFallback = "YouTube_$videoId"
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.private.coffee",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.leptons.xyz",
        )
        for (base in pipedInstances) {
            val req = Request.Builder()
                .url("$base/streams/$videoId")
                .header("User-Agent", ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val pipedStreams = runCatching {
                cookielessClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<ExtractedStream>()
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val title = root.optString("title").ifBlank { titleFallback }.take(72)
                    val out = mutableListOf<ExtractedStream>()
                    val audioStreams = root.optJSONArray("audioStreams") ?: JSONArray()
                    var bestAudioUrl: String? = null
                    var bestAudioBitrate = -1L
                    for (i in 0 until audioStreams.length()) {
                        val a = audioStreams.optJSONObject(i) ?: continue
                        val mime = a.optString("mimeType").lowercase()
                        val aUrl = a.optString("url")
                        val br = a.optLong("bitrate", 0L)
                        if (aUrl.startsWith("http") && mime.contains("mp4") && br > bestAudioBitrate) {
                            bestAudioBitrate = br
                            bestAudioUrl = aUrl
                        }
                    }

                    val videoStreams = root.optJSONArray("videoStreams") ?: JSONArray()
                    val pipedMp4 = mutableListOf<JSONObject>()
                    for (i in 0 until videoStreams.length()) {
                        val v = videoStreams.optJSONObject(i) ?: continue
                        val vUrl = v.optString("url")
                        val mime = v.optString("mimeType").lowercase()
                        val videoOnly = v.optBoolean("videoOnly", false)
                        if (!vUrl.startsWith("http") || !mime.contains("mp4")) continue
                        if (videoOnly && bestAudioUrl == null) continue
                        pipedMp4.add(v)
                    }
                    val pipedH264 = pipedMp4.filter {
                        val c = (it.optString("codec") + " " + it.optString("mimeType")).lowercase()
                        !c.contains("av01") && !c.contains("vp9")
                    }
                    (pipedH264.ifEmpty { pipedMp4 }).forEach { v ->
                        val vUrl = v.optString("url")
                        val videoOnly = v.optBoolean("videoOnly", false)
                        val h = computeQualityTier(v.optInt("width", 0), v.optInt("height", 720))
                        out.add(
                            ExtractedStream(
                                streamUrl = vUrl,
                                title = title,
                                platform = PlatformBadge.YOUTUBE,
                                qualityLabel = formatQualityLabel(h),
                                resolutionHeight = h,
                                mimeType = "video/mp4",
                                estimatedBytes = v.optLong("contentLength", -1L),
                                headers = mapOf("User-Agent" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT),
                                audioStreamUrl = if (videoOnly) bestAudioUrl else null,
                                bitrateBps = v.optLong("bitrate", 0L),
                            ),
                        )
                    }

                    val hls = root.optString("hls")
                    if (hls.startsWith("http")) {
                        out.add(
                            ExtractedStream(
                                streamUrl = hls,
                                title = title,
                                platform = PlatformBadge.YOUTUBE,
                                qualityLabel = "BEST HD",
                                resolutionHeight = 1080,
                                mimeType = "application/vnd.apple.mpegurl",
                                estimatedBytes = -1L,
                                headers = mapOf("User-Agent" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT),
                                isHls = true,
                            ),
                        )
                    }
                    out
                }
            }.getOrDefault(emptyList())

            if (pipedStreams.isNotEmpty()) return pipedStreams
        }

        val invidiousInstances = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yewtu.be",
            "https://inv.tux.pizza",
        )
        for (base in invidiousInstances) {
            val req = Request.Builder()
                .url("$base/api/v1/videos/$videoId")
                .header("User-Agent", ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val invStreams = runCatching {
                cookielessClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<ExtractedStream>()
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val title = root.optString("title").ifBlank { titleFallback }.take(72)
                    val out = mutableListOf<ExtractedStream>()
                    val adaptive = root.optJSONArray("adaptiveFormats") ?: JSONArray()
                    var bestAudioUrl: String? = null
                    var bestAudioBr = -1L
                    for (i in 0 until adaptive.length()) {
                        val a = adaptive.optJSONObject(i) ?: continue
                        val type = a.optString("type").lowercase()
                        val aUrl = a.optString("url")
                        val br = a.optString("bitrate").toLongOrNull() ?: 0L
                        if (aUrl.startsWith("http") && type.startsWith("audio/mp4") && br > bestAudioBr) {
                            bestAudioBr = br
                            bestAudioUrl = aUrl
                        }
                    }
                    val invAdaptive = mutableListOf<JSONObject>()
                    for (i in 0 until adaptive.length()) {
                        val v = adaptive.optJSONObject(i) ?: continue
                        val type = v.optString("type").lowercase()
                        val vUrl = v.optString("url")
                        if (!vUrl.startsWith("http") || !type.startsWith("video/mp4") || bestAudioUrl == null) continue
                        invAdaptive.add(v)
                    }
                    val invH264 = invAdaptive.filter {
                        val t = it.optString("type").lowercase()
                        !t.contains("av01") && !t.contains("vp9")
                    }
                    (invH264.ifEmpty { invAdaptive }).forEach { v ->
                        val vUrl = v.optString("url")
                        val h = Regex("""(\d+)p""").find(v.optString("qualityLabel"))?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 720
                        out.add(
                            ExtractedStream(
                                streamUrl = vUrl,
                                title = title,
                                platform = PlatformBadge.YOUTUBE,
                                qualityLabel = formatQualityLabel(h),
                                resolutionHeight = h,
                                mimeType = "video/mp4",
                                estimatedBytes = v.optString("clen").toLongOrNull() ?: -1L,
                                headers = mapOf("User-Agent" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT),
                                audioStreamUrl = bestAudioUrl,
                                bitrateBps = v.optString("bitrate").toLongOrNull() ?: 0L,
                            ),
                        )
                    }
                    val formatStreams = root.optJSONArray("formatStreams") ?: JSONArray()
                    for (i in 0 until formatStreams.length()) {
                        val f = formatStreams.optJSONObject(i) ?: continue
                        val fUrl = f.optString("url")
                        if (!fUrl.startsWith("http")) continue
                        val h = Regex("""(\d+)p""").find(f.optString("qualityLabel"))?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 720
                        out.add(
                            ExtractedStream(
                                streamUrl = fUrl,
                                title = title,
                                platform = PlatformBadge.YOUTUBE,
                                qualityLabel = formatQualityLabel(h),
                                resolutionHeight = h,
                                mimeType = "video/mp4",
                                estimatedBytes = -1L,
                                headers = mapOf("User-Agent" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT),
                            ),
                        )
                    }
                    out
                }
            }.getOrDefault(emptyList())

            if (invStreams.isNotEmpty()) return invStreams
        }

        return emptyList()
    }

    /**
     * Probing budget covers ALL 3 tiers (adaptive video+audio, progressive muxed MP4, AND HLS)
     * so high-res 403s never starve progressive or HLS streams.
     */
    private fun filterReachableStreams(candidates: List<ExtractedStream>): List<ExtractedStream> {
        if (candidates.isEmpty()) return emptyList()

        val adaptive = candidates.filter { !it.isHls && !it.audioStreamUrl.isNullOrBlank() }
            .sortedWith(compareByDescending<ExtractedStream> { it.resolutionHeight }.thenByDescending { it.bitrateBps })
            .distinctBy { it.resolutionHeight }
            .take(4)
        val progressive = candidates.filter { !it.isHls && it.audioStreamUrl.isNullOrBlank() }
            .sortedWith(compareByDescending<ExtractedStream> { it.resolutionHeight }.thenByDescending { it.bitrateBps })
            .distinctBy { it.resolutionHeight }
            .take(2)
        val hls = candidates.filter { it.isHls }.take(2)

        val probePool = (adaptive + progressive + hls).distinctBy { it.streamUrl }
        val verified = mutableListOf<ExtractedStream>()
        for (stream in probePool) {
            if (isStreamUrlReachable(stream)) {
                verified.add(stream)
            }
        }
        return verified.sortedWith(
            compareByDescending<ExtractedStream> { !it.isHls }
                .thenByDescending { it.resolutionHeight }
                .thenByDescending { it.bitrateBps }
                .thenByDescending { it.estimatedBytes },
        )
    }

    private fun isStreamUrlReachable(stream: ExtractedStream): Boolean {
        val probeClient = if (stream.headers.containsKey("Origin")) client else cookielessClient
        val reqBuilder = Request.Builder().url(stream.streamUrl)
        if (!stream.isHls) {
            reqBuilder.header("Range", "bytes=0-1023")
        }
        stream.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val mainOk = runCatching {
            probeClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return@use false
                if (stream.isHls) {
                    val body = resp.body?.string().orEmpty()
                    body.contains("#EXTM3U")
                } else {
                    true
                }
            }
        }.getOrDefault(false)
        if (!mainOk) return false

        val audioUrl = stream.audioStreamUrl
        if (!audioUrl.isNullOrBlank()) {
            val audioReq = Request.Builder().url(audioUrl).header("Range", "bytes=0-1023")
            stream.headers.forEach { (k, v) -> audioReq.header(k, v) }
            val audioOk = runCatching {
                probeClient.newCall(audioReq.build()).execute().use { resp ->
                    resp.isSuccessful || resp.code == 206
                }
            }.getOrDefault(false)
            if (!audioOk) return false
        }
        return true
    }

    private fun buildSapisidAuthorizationHeader(): String? {
        val vault = cookieVault ?: return null
        val sapisid = vault.getCookieValue("https://www.youtube.com/", "SAPISID")
            ?: vault.getCookieValue("https://www.youtube.com/", "__Secure-3PAPISID")
            ?: vault.getCookieValue("https://www.youtube.com/", "__Secure-1PAPISID")
            ?: return null
        val ts = System.currentTimeMillis() / 1000L
        val raw = "$ts $sapisid https://www.youtube.com"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$hex"
    }

    private fun getCipherOps(jsUrl: String): List<CipherOp> {
        cipherCache[jsUrl]?.let { return it }
        val fullUrl = if (jsUrl.startsWith("http")) jsUrl else "https://www.youtube.com$jsUrl"
        val req = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
            .build()
        val jsCode = runCatching {
            cookielessClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull() ?: return emptyList()

        val parsed = parseCipherOpsFromPlayerJs(jsCode)
        if (parsed.isNotEmpty()) {
            cipherCache[jsUrl] = parsed
        }
        return parsed
    }

    private fun parseCipherOpsFromPlayerJs(js: String): List<CipherOp> {
        val fnPattern = Regex("""(?:function\(([a-zA-Z0-9_$]+)\)|=\s*function\(([a-zA-Z0-9_$]+)\))\{\1?([a-zA-Z0-9_$]+)=[a-zA-Z0-9_$]+\.split\(""\);([\s\S]{10,550}?);return [a-zA-Z0-9_$]+\.join\(""\)\}""")
        val altPattern = Regex("""function\(([a-zA-Z0-9_$]+)\)\{\1=\1\.split\(""\);([\s\S]{10,450}?);return \1\.join\(""\)\}""")
        val altMatch = altPattern.find(js)
        val body = altMatch?.groupValues?.getOrNull(2) ?: fnPattern.find(js)?.groupValues?.getOrNull(4) ?: return emptyList()

        val helperCall = Regex("""([a-zA-Z0-9_$]+)(?:\[['"]([a-zA-Z0-9_$]+)['"]\]|\.([a-zA-Z0-9_$]+))\([a-zA-Z0-9_$]+,(\d+)\)""")
            .findAll(body)
            .toList()
        if (helperCall.isEmpty()) return emptyList()

        val helperName = Regex.escape(helperCall.first().groupValues[1])
        val objPattern = Regex("""(?:var|let|const)\s+$helperName=\{([\s\S]{10,600}?)\};""")
        val objBody = objPattern.find(js)?.groupValues?.getOrNull(1) ?: return emptyList()

        val opMap = mutableMapOf<String, String>()
        Regex("""([a-zA-Z0-9_$]+)\s*:\s*function\([^)]*\)\{([^}]*)\}""").findAll(objBody).forEach { m ->
            val methodName = m.groupValues[1]
            val methodBody = m.groupValues[2]
            when {
                methodBody.contains(".reverse()") -> opMap[methodName] = "reverse"
                methodBody.contains(".splice(") -> opMap[methodName] = "splice"
                methodBody.contains("[0]") -> opMap[methodName] = "swap"
            }
        }

        val ops = mutableListOf<CipherOp>()
        for (call in helperCall) {
            val method = call.groupValues[2].ifBlank { call.groupValues[3] }
            val arg = call.groupValues[4].toIntOrNull() ?: 0
            when (opMap[method]) {
                "reverse" -> ops.add(CipherOp.Reverse)
                "splice" -> ops.add(CipherOp.Splice(arg))
                "swap" -> ops.add(CipherOp.Swap(arg))
            }
        }
        return ops
    }

    private fun applyCipherOps(sig: String, ops: List<CipherOp>): String {
        val chars = sig.toMutableList()
        for (op in ops) {
            when (op) {
                CipherOp.Reverse -> chars.reverse()
                is CipherOp.Splice -> {
                    val count = op.count.coerceIn(0, chars.size)
                    repeat(count) { if (chars.isNotEmpty()) chars.removeAt(0) }
                }
                is CipherOp.Swap -> {
                    if (chars.isNotEmpty()) {
                        val idx = op.index % chars.size
                        val tmp = chars[0]
                        chars[0] = chars[idx]
                        chars[idx] = tmp
                    }
                }
            }
        }
        return chars.joinToString("")
    }

    private fun extractJsonObjectAfterMarker(text: String, marker: String): String? {
        val markerIdx = text.indexOf(marker)
        if (markerIdx < 0) return null
        val startBrace = text.indexOf('{', markerIdx)
        if (startBrace < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in startBrace until text.length) {
            val ch = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\' && inString) {
                escape = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) {
                        return text.substring(startBrace, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault("")
                val value = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault("")
                if (key.isNotBlank()) map[key] = value
            }
        }
        return map
    }

    private fun computeQualityTier(width: Int, height: Int): Int {
        val raw = when {
            width > 0 && height > 0 -> minOf(width, height)
            height > 0 -> height
            width > 0 -> width
            else -> 720
        }
        return when {
            raw >= 2100 -> 2160
            raw >= 1400 -> 1440
            raw >= 1050 -> 1080
            raw >= 700 -> 720
            else -> raw
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
        private const val EU_SOCS_CONSENT_COOKIE = "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg; PREF=f6=40000000&hl=en&gl=US"
    }
}
