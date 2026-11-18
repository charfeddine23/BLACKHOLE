package com.example.engine.extractor

import com.example.engine.cookies.CookieVaultManager
import com.example.engine.model.ExtractedStream
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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
 * 1. Live watch-page Innertube API key, Visitor ID, and base.js signature deciphering
 * 2. Cookie + SAPISIDHASH authenticated Innertube client rotation (IOS, ANDROID_VR, TVHTML5, EMBEDDED, ANDROID, MWEB)
 * 3. Watch-HTML ytInitialPlayerResponse parsing with balanced-brace JSON extraction
 * 4. Public Invidious / Piped API mirror fallback when device IP hits bot/poToken gates
 * 5. Mandatory HTTP 200/206 reachability gate so 403 streams are skipped automatically
 */
internal class YoutubePowerExtractor(
    private val client: OkHttpClient,
    private val cookieVault: CookieVaultManager?,
) {

    private sealed interface CipherOp {
        data object Reverse : CipherOp
        data class Splice(val count: Int) : CipherOp
        data class Swap(val index: Int) : CipherOp
    }

    private val cipherCache = ConcurrentHashMap<String, List<CipherOp>>()

    fun extract(url: String): YoutubeExtractionOutcome {
        val videoId = extractVideoId(url) ?: return YoutubeExtractionOutcome(emptyList(), false)
        var loginRequired = false

        val watchContext = fetchWatchPageContext(videoId)
        val cookieAuthHeader = buildSapisidAuthorizationHeader()

        // 1. Check watch page embedded ytInitialPlayerResponse first (if present + decipherable)
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
                )
                val reachable = filterReachableStreams(decoded)
                if (reachable.isNotEmpty()) {
                    return YoutubeExtractionOutcome(reachable, loginRequired)
                }
            }
        }

        // 2. Multi-client Innertube cascade
        data class YtClientSpec(
            val clientName: String,
            val clientVersion: String,
            val clientId: String,
            val userAgent: String,
            val isEmbedded: Boolean = false,
            val sendCookiesAndAuth: Boolean = false,
            val extraClientFields: Map<String, Any> = emptyMap(),
        )

        val clients = listOf(
            YtClientSpec(
                clientName = "ANDROID_VR",
                clientVersion = "1.60.19",
                clientId = "28",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                extraClientFields = mapOf(
                    "deviceMake" to "Oculus",
                    "deviceModel" to "Quest 3",
                    "osName" to "Android",
                    "osVersion" to "12L",
                    "androidSdkVersion" to 32,
                ),
            ),
            YtClientSpec(
                clientName = "IOS",
                clientVersion = "19.45.4",
                clientId = "5",
                userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X)",
                extraClientFields = mapOf(
                    "deviceMake" to "Apple",
                    "deviceModel" to "iPhone16,2",
                    "osName" to "iPhone",
                    "osVersion" to "18.2.1.22C161",
                ),
            ),
            YtClientSpec(
                clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                clientVersion = "2.0",
                clientId = "85",
                userAgent = "Mozilla/5.0 (PlayStation; PlayStation 5/6.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
                isEmbedded = true,
                sendCookiesAndAuth = true,
            ),
            YtClientSpec(
                clientName = "TVHTML5",
                clientVersion = "7.20250319.10.00",
                clientId = "7",
                userAgent = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) 94.0.4606.31/7.0 TV Safari/537.36",
                sendCookiesAndAuth = true,
            ),
            YtClientSpec(
                clientName = "ANDROID",
                clientVersion = "19.44.38",
                clientId = "3",
                userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 15; Pixel 9 Pro) gzip",
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
                sendCookiesAndAuth = true,
            ),
        )

        val apiKeyQuery = watchContext.apiKey?.let { "?key=$it&prettyPrint=false" } ?: "?prettyPrint=false"
        for (spec in clients) {
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
                                    put("embedUrl", "https://www.youtube.com/")
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
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/watch?v=$videoId")

            if (!watchContext.visitorData.isNullOrBlank()) {
                reqBuilder.header("X-Goog-Visitor-Id", watchContext.visitorData)
            }
            if (spec.sendCookiesAndAuth && !cookieAuthHeader.isNullOrBlank()) {
                reqBuilder.header("Authorization", cookieAuthHeader)
                reqBuilder.header("X-Origin", "https://www.youtube.com")
            }

            val parsedStreams = runCatching {
                client.newCall(reqBuilder.build()).execute().use { response ->
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
                    )
                }
            }.getOrDefault(emptyList())

            val reachable = filterReachableStreams(parsedStreams)
            if (reachable.isNotEmpty()) {
                return YoutubeExtractionOutcome(reachable, loginRequired)
            }
        }

        // 3. Fallback: Resilient public Invidious / Piped API mirrors when Innertube hits poToken/IP gates
        val mirrorStreams = filterReachableStreams(fetchFromPublicMirrors(videoId))
        if (mirrorStreams.isNotEmpty()) {
            return YoutubeExtractionOutcome(mirrorStreams, loginRequired)
        }

        return YoutubeExtractionOutcome(emptyList(), loginRequired)
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
        val watchUrl = "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1"
        val req = Request.Builder()
            .url(watchUrl)
            .header("User-Agent", ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        return runCatching {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return WatchPageContext(null, null, null, null, null)
                }
                val html = response.body?.string().orEmpty()
                val apiKey = Regex(""""INNERTUBE_API_KEY"\s*:\s*"([^"]+)"""")
                    .find(html)?.groupValues?.getOrNull(1)
                val visitorData = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
                    .find(html)?.groupValues?.getOrNull(1)
                val sts = Regex(""""STS"\s*:\s*(\d+)""")
                    .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val jsUrl = Regex(""""jsUrl"\s*:\s*"([^"]+)"""")
                    .find(html)?.groupValues?.getOrNull(1)
                val playerJsonStr = extractJsonObjectAfterMarker(html, "ytInitialPlayerResponse")
                val playerJson = playerJsonStr?.let { runCatching { JSONObject(it) }.getOrNull() }
                WatchPageContext(apiKey, visitorData, sts, jsUrl, playerJson)
            }
        }.getOrDefault(WatchPageContext(null, null, null, null, null))
    }

    private fun parsePlayerJson(
        json: JSONObject,
        videoId: String,
        userAgent: String,
        jsUrl: String?,
    ): List<ExtractedStream> {
        val details = json.optJSONObject("videoDetails")
        val title = details?.optString("title")
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.take(72)
            ?: "YouTube_$videoId"

        val streamingData = json.optJSONObject("streamingData") ?: return emptyList()
        val streamHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "https://www.youtube.com/",
            "Origin" to "https://www.youtube.com",
        )
        val found = mutableListOf<ExtractedStream>()

        // Decipher any direct or signatureCipher format URL cleanly
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
        val videoAdaptive = mutableListOf<Pair<JSONObject, String>>()

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
                videoAdaptive.add(fmt to fmtUrl)
            }
        }

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
        )
        for (base in pipedInstances) {
            val req = Request.Builder()
                .url("$base/streams/$videoId")
                .header("User-Agent", ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val pipedStreams = runCatching {
                client.newCall(req).execute().use { resp ->
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
                    for (i in 0 until videoStreams.length()) {
                        val v = videoStreams.optJSONObject(i) ?: continue
                        val vUrl = v.optString("url")
                        val mime = v.optString("mimeType").lowercase()
                        val videoOnly = v.optBoolean("videoOnly", false)
                        if (!vUrl.startsWith("http") || !mime.contains("mp4")) continue
                        if (videoOnly && bestAudioUrl == null) continue
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
        )
        for (base in invidiousInstances) {
            val req = Request.Builder()
                .url("$base/api/v1/videos/$videoId")
                .header("User-Agent", ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val invStreams = runCatching {
                client.newCall(req).execute().use { resp ->
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
                    for (i in 0 until adaptive.length()) {
                        val v = adaptive.optJSONObject(i) ?: continue
                        val type = v.optString("type").lowercase()
                        val vUrl = v.optString("url")
                        if (!vUrl.startsWith("http") || !type.startsWith("video/mp4") || bestAudioUrl == null) continue
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

    private fun filterReachableStreams(candidates: List<ExtractedStream>): List<ExtractedStream> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedWith(
            compareByDescending<ExtractedStream> { !it.isHls }
                .thenByDescending { it.resolutionHeight }
                .thenByDescending { it.bitrateBps }
                .thenByDescending { it.estimatedBytes },
        )
        // Probe up to top 4 candidates to filter out HTTP 403 / dead URLs quickly
        val verified = mutableListOf<ExtractedStream>()
        for (stream in sorted.take(6)) {
            if (isStreamUrlReachable(stream)) {
                verified.add(stream)
            }
        }
        return verified
    }

    private fun isStreamUrlReachable(stream: ExtractedStream): Boolean {
        val reqBuilder = Request.Builder().url(stream.streamUrl)
        if (!stream.isHls) {
            reqBuilder.header("Range", "bytes=0-0")
        }
        stream.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val mainOk = runCatching {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                resp.isSuccessful || resp.code == 206
            }
        }.getOrDefault(false)
        if (!mainOk) return false

        val audioUrl = stream.audioStreamUrl
        if (!audioUrl.isNullOrBlank()) {
            val audioReq = Request.Builder().url(audioUrl).header("Range", "bytes=0-0")
            stream.headers.forEach { (k, v) -> audioReq.header(k, v) }
            val audioOk = runCatching {
                client.newCall(audioReq.build()).execute().use { resp ->
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
            client.newCall(req).execute().use { resp ->
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
        val fnPattern = Regex("""function\(([a-zA-Z0-9_$]+)\)\{\1=\1\.split\(""\);([\s\S]{10,450}?);return \1\.join\(""\)\}""")
        val fnMatch = fnPattern.find(js) ?: return emptyList()
        val body = fnMatch.groupValues[2]

        val helperCall = Regex("""([a-zA-Z0-9_$]+)\[?['"]?([a-zA-Z0-9_$]+)['"]?\]?\([a-zA-Z0-9_$]+,(\d+)\)""")
            .findAll(body)
            .toList()
        if (helperCall.isEmpty()) return emptyList()

        val helperName = Regex.escape(helperCall.first().groupValues[1])
        val objPattern = Regex("""var $helperName=\{([\s\S]{10,500}?)\};""")
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
            val method = call.groupValues[2]
            val arg = call.groupValues[3].toIntOrNull() ?: 0
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
}
