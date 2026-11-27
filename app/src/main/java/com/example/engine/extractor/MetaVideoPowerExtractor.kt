package com.example.engine.extractor

import android.util.Base64
import com.example.engine.cookies.CookieVaultManager
import com.example.engine.model.ExtractedStream
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
import com.example.util.formatQualityLabel
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class MetaExtractionOutcome(
    val streams: List<ExtractedStream>,
    val authChallenge: Boolean,
    val canonicalUrl: String,
    val targetVideoId: String?,
)

/**
 * Multi-layer Instagram & Facebook extractor:
 * - Instagram: Mobile/Web /api/v1/media/info with X-CSRFToken, Public Polaris GraphQL doc_id,
 *   __a=1&__d=dis, embed captioned pages, data-sjs balanced JSON, video_versions, and DASH MPD muxing.
 * - Facebook: Share redirect canonicalization, single-video plugins/video.php isolation,
 *   mbasic video_redirect/?src extraction, strict target-video-ID anchored JSON scoping,
 *   per-MPD DASH muxing, and partial byte-range URL scrubbing so feed/suggested/partial clips never replace the real video.
 */
internal class MetaVideoPowerExtractor(
    private val client: OkHttpClient,
    private val cookieVault: CookieVaultManager?,
) {

    fun extractInstagram(rawUrl: String): MetaExtractionOutcome {
        val rawResolved = resolveRedirects(rawUrl, "https://www.instagram.com/")
        val found = mutableListOf<ExtractedStream>()
        var authChallenge = false
        val shortcodePattern = Regex(
            """(?:instagram\.com|instagr\.am)/(?:[^/]+/)?(?:share/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""",
            RegexOption.IGNORE_CASE,
        )
        val shortcode = shortcodePattern.find(rawResolved)?.groupValues?.getOrNull(1)
            ?: shortcodePattern.find(rawUrl)?.groupValues?.getOrNull(1)
        val canonicalUrl = if (!shortcode.isNullOrBlank()) {
            val isReel = rawResolved.contains("/reel", ignoreCase = true) ||
                rawUrl.contains("/reel", ignoreCase = true)
            if (isReel) "https://www.instagram.com/reel/$shortcode/" else "https://www.instagram.com/p/$shortcode/"
        } else {
            stripInstagramTrackingParams(rawResolved)
        }

        var csrfToken = cookieVault?.getCookieValue("https://www.instagram.com/", "csrftoken")
            .orEmpty()
        var lsdToken = ""

        val mediaId = shortcode?.let(::shortcodeToInstagramMediaId)
        val hasSessionCookie = !cookieVault?.getCookieValue("https://www.instagram.com/", "sessionid").isNullOrBlank()

        fun buildIgApiHeaders(csrf: String, lsd: String): Map<String, String> = linkedMapOf<String, String>().apply {
            put("Referer", "https://www.instagram.com/")
            put("Origin", "https://www.instagram.com")
            put("X-IG-App-ID", "936619743392459")
            put("X-ASBD-ID", "129477")
            put("X-IG-WWW-Claim", "0")
            put("X-CSRFToken", csrf.ifBlank { "missing" })
            put("Accept-Language", "en-US,en;q=0.9")
            put("Sec-Fetch-Site", "same-origin")
            put("Sec-Fetch-Mode", "cors")
            put("Sec-Fetch-Dest", "empty")
            if (lsd.isNotBlank()) put("X-FB-LSD", lsd)
        }

        // Layer 1 & 2: /api/v1/media/{id}/info/ (run first when session cookie exists; otherwise warm embed first for csrftoken/lsd)
        if (!mediaId.isNullOrBlank() && hasSessionCookie) {
            val apiEndpoints = listOf(
                "https://i.instagram.com/api/v1/media/$mediaId/info/" to ClientSpoofer.INSTAGRAM_ANDROID_USER_AGENT,
                "https://www.instagram.com/api/v1/media/$mediaId/info/" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
            )
            val apiHeaders = buildIgApiHeaders(csrfToken, lsdToken)
            for ((endpoint, ua) in apiEndpoints) {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", ua)
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .apply { apiHeaders.forEach { (k, v) -> header(k, v) } }
                    .build()

                runCatching {
                    client.newCall(req).execute().use { resp ->
                        if (resp.code == 401 || resp.code == 403) {
                            authChallenge = true
                            return@use
                        }
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string().orEmpty()
                        parseInstagramPayload(
                            rawBody = body,
                            baseUrl = endpoint,
                            fallbackTitle = "Instagram_$shortcode",
                            ua = ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
                            out = found,
                        )
                    }
                }
                if (found.any { it.resolutionHeight >= 1080 && hasConfirmedAudio(it) }) break
            }
        }

        // Layer 2b: Public Embed & Captioned Embed pages first (yields direct video_url AND seeds anonymous csrftoken/lsd)
        if (found.none { hasConfirmedAudio(it) } && !shortcode.isNullOrBlank()) {
            val embedTargets = listOf(
                "https://www.instagram.com/reel/$shortcode/embed/" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
                "https://www.instagram.com/p/$shortcode/embed/captioned/" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
                "https://www.instagram.com/reel/$shortcode/embed/captioned/" to ClientSpoofer.ANDROID_CHROME_USER_AGENT,
                "https://www.instagram.com/p/$shortcode/embed/" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
            )
            for ((target, ua) in embedTargets) {
                val req = Request.Builder()
                    .url(target)
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://www.instagram.com/")
                    .header("Sec-Fetch-Dest", "iframe")
                    .header("Sec-Fetch-Mode", "navigate")
                    .build()

                runCatching {
                    client.newCall(req).execute().use { resp ->
                        val finalUrl = resp.request.url.toString()
                        if (resp.code == 401 || resp.code == 403 || finalUrl.contains("/accounts/login", ignoreCase = true)) {
                            authChallenge = true
                            return@use
                        }
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string().orEmpty()
                        if (body.isBlank()) return@use

                        if (csrfToken.isBlank()) {
                            csrfToken = cookieVault?.getCookieValue("https://www.instagram.com/", "csrftoken")
                                ?: Regex(""""csrf_token"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1).orEmpty()
                        }
                        if (lsdToken.isBlank()) {
                            lsdToken = Regex("""\["LSD",\s*\[\],\s*\{"token"\s*:\s*"([^"]+)"""")
                                .find(body)?.groupValues?.getOrNull(1)
                                ?: Regex(""""lsd"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1).orEmpty()
                        }

                        parseInstagramPayload(
                            rawBody = body,
                            baseUrl = finalUrl,
                            fallbackTitle = "Instagram_$shortcode",
                            ua = ua,
                            out = found,
                        )
                    }
                }
                if (found.any { it.resolutionHeight >= 1080 && hasConfirmedAudio(it) }) break
            }
        }

        // Layer 3: Instagram Public GraphQL Endpoint (PolarisPostActionLoadPostQueryQuery) with bootstrapped csrftoken/lsd
        if (found.none { hasConfirmedAudio(it) } && !shortcode.isNullOrBlank()) {
            if (csrfToken.isBlank() || lsdToken.isBlank()) {
                runCatching {
                    val warmReq = Request.Builder()
                        .url(canonicalUrl)
                        .header("User-Agent", ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()
                    client.newCall(warmReq).execute().use { resp ->
                        val warmHtml = if (resp.isSuccessful) resp.body?.string()?.take(200_000).orEmpty() else ""
                        if (csrfToken.isBlank()) {
                            csrfToken = cookieVault?.getCookieValue("https://www.instagram.com/", "csrftoken")
                                ?: Regex(""""csrf_token"\s*:\s*"([^"]+)"""").find(warmHtml)?.groupValues?.getOrNull(1).orEmpty()
                        }
                        if (lsdToken.isBlank()) {
                            lsdToken = Regex("""\["LSD",\s*\[\],\s*\{"token"\s*:\s*"([^"]+)"""")
                                .find(warmHtml)?.groupValues?.getOrNull(1).orEmpty()
                        }
                        if (warmHtml.isNotBlank() && !resp.request.url.toString().contains("/accounts/login", ignoreCase = true)) {
                            parseInstagramPayload(
                                rawBody = warmHtml,
                                baseUrl = resp.request.url.toString(),
                                fallbackTitle = "Instagram_$shortcode",
                                ua = ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
                                out = found,
                            )
                        }
                    }
                }
            }

            if (found.none { hasConfirmedAudio(it) }) {
                val docIds = listOf("8845758582119845", "10015901848480474", "24368985919402291", "27451289061152180")
                val graphqlEndpoints = listOf(
                    "https://www.instagram.com/graphql/query",
                    "https://www.instagram.com/api/graphql",
                )
                val gqlHeaders = buildIgApiHeaders(csrfToken, lsdToken)

                outerGql@ for (docId in docIds) {
                    val variablesJson = JSONObject().apply {
                        put("shortcode", shortcode)
                        put("fetch_comment_count", 0)
                        put("fetch_related_profile_media_count", 0)
                        put("parent_comment_count", 0)
                        put("child_comment_count", 0)
                        put("fetch_like_count", 0)
                        put("fetch_tagged_user_count", null)
                        put("fetch_preview_comment_count", 0)
                        put("has_threaded_comments", false)
                        put("hoisted_comment_id", null)
                        put("hoisted_reply_id", null)
                    }.toString()

                    for (gqlEndpoint in graphqlEndpoints) {
                        val formBuilder = FormBody.Builder()
                            .add("variables", variablesJson)
                            .add("doc_id", docId)
                        if (lsdToken.isNotBlank()) {
                            formBuilder.add("lsd", lsdToken)
                        }

                        val req = Request.Builder()
                            .url(gqlEndpoint)
                            .post(formBuilder.build())
                            .header("User-Agent", ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                            .header("Accept", "*/*")
                            .header("X-FB-Friendly-Name", "PolarisPostActionLoadPostQueryQuery")
                            .apply { gqlHeaders.forEach { (k, v) -> header(k, v) } }
                            .build()

                        runCatching {
                            client.newCall(req).execute().use { resp ->
                                if (resp.code == 401 || resp.code == 403) {
                                    authChallenge = true
                                    return@use
                                }
                                if (!resp.isSuccessful) return@use
                                val body = resp.body?.string().orEmpty()
                                if (body.contains("login_required") || body.contains("require_login")) {
                                    authChallenge = true
                                }
                                parseInstagramPayload(
                                    rawBody = body,
                                    baseUrl = canonicalUrl,
                                    fallbackTitle = "Instagram_$shortcode",
                                    ua = ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
                                    out = found,
                                )
                            }
                        }
                        if (found.any { it.resolutionHeight >= 1080 && hasConfirmedAudio(it) }) break@outerGql
                        if (found.isNotEmpty()) break@outerGql
                    }
                }
            }
        }

        // Layer 4 & 5: ?__a=1&__d=dis, /api/v1/media/{id}/info/ fallback, and Direct HTML Pages
        if (found.none { hasConfirmedAudio(it) }) {
            val apiHeaders = buildIgApiHeaders(csrfToken, lsdToken)
            val fallbackTargets = buildList {
                if (!shortcode.isNullOrBlank()) {
                    add("https://www.instagram.com/reel/$shortcode/?__a=1&__d=dis" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                    add("https://www.instagram.com/p/$shortcode/?__a=1&__d=dis" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                }
                if (!mediaId.isNullOrBlank() && !hasSessionCookie) {
                    add("https://www.instagram.com/api/v1/media/$mediaId/info/" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                }
                add(canonicalUrl to ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                add(canonicalUrl to ClientSpoofer.ANDROID_CHROME_USER_AGENT)
                add(canonicalUrl to "facebookexternalhit/1.1")
            }.distinctBy { it.first to it.second }

            for ((target, ua) in fallbackTargets) {
                val isJson = target.contains("__a=1") || target.contains("/api/v1/")
                val req = Request.Builder()
                    .url(target)
                    .header("User-Agent", ua)
                    .header(
                        "Accept",
                        if (isJson) "application/json" else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    )
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://www.instagram.com/")
                    .apply {
                        if (isJson) {
                            header("X-Requested-With", "XMLHttpRequest")
                            apiHeaders.forEach { (k, v) -> header(k, v) }
                        }
                    }
                    .build()

                runCatching {
                    client.newCall(req).execute().use { resp ->
                        val finalUrl = resp.request.url.toString()
                        if (resp.code == 401 || resp.code == 403 || finalUrl.contains("/accounts/login", ignoreCase = true)) {
                            authChallenge = true
                            return@use
                        }
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string().orEmpty()
                        if (body.isBlank()) return@use
                        if (body.contains("\"require_login\":true") ||
                            body.contains("\"login_required\"") ||
                            body.contains("accounts/login")
                        ) {
                            authChallenge = true
                        }
                        parseInstagramPayload(
                            rawBody = body,
                            baseUrl = finalUrl,
                            fallbackTitle = shortcode?.let { "Instagram_$it" } ?: "Instagram_Video",
                            ua = ua,
                            out = found,
                        )
                    }
                }
                if (found.any { it.resolutionHeight >= 1080 && hasConfirmedAudio(it) }) break
            }
        }

        // Share any discovered audio track across layers if an earlier layer had video-only candidates
        val globalAudio = found.firstNotNullOfOrNull { it.audioStreamUrl }
        val audioEnriched = if (globalAudio != null) {
            found.map { stream ->
                if (stream.audioStreamUrl == null && !isMetaProgressiveUrl(stream.streamUrl)) {
                    stream.copy(audioStreamUrl = globalAudio)
                } else {
                    stream
                }
            }
        } else {
            found
        }

        val cleanVerified = verifyAndDedupe(audioEnriched)
        return MetaExtractionOutcome(
            streams = cleanVerified,
            authChallenge = authChallenge && cleanVerified.isEmpty(),
            canonicalUrl = canonicalUrl,
            targetVideoId = shortcode,
        )
    }

    private fun parseInstagramPayload(
        rawBody: String,
        baseUrl: String,
        fallbackTitle: String,
        ua: String,
        out: MutableList<ExtractedStream>,
    ) {
        val streamHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to "https://www.instagram.com/",
            "Origin" to "https://www.instagram.com",
        )
        val unescaped = unescapeMetaMarkup(rawBody)
        val title = extractHtmlTitle(unescaped) ?: fallbackTitle
        val localCandidates = mutableListOf<ExtractedStream>()
        val audioPool = mutableListOf<String>()

        // 1. Parse JSON directly if payload is structured API/GraphQL response
        if (rawBody.trimStart().startsWith("{")) {
            runCatching {
                val root = JSONObject(rawBody)
                extractInstagramFromJsonTree(root, title, streamHeaders, localCandidates, audioPool)
            }
        }

        // 2. Extract embedded <script type="application/json" data-sjs> JSON blobs cleanly
        val sjsRegex = Regex("""<script[^>]+type=["']application/json["'][^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
        sjsRegex.findAll(rawBody).forEach { match ->
            val blob = match.groupValues[1].trim()
            if (blob.contains("video_versions") ||
                blob.contains("video_url") ||
                blob.contains("dash_manifest") ||
                blob.contains("playable_url") ||
                blob.contains("audio_url") ||
                blob.contains("contextJSON")
            ) {
                runCatching {
                    val obj = JSONObject(blob)
                    extractInstagramFromJsonTree(obj, title, streamHeaders, localCandidates, audioPool)
                }
            }
        }

        // 2b. Extract Instagram /embed/ "contextJSON" escaped string or object payload
        val contextJsonRegex = Regex(""""contextJSON"\s*:\s*"((?:[^"\\]|\\.)+)"""")
        contextJsonRegex.findAll(rawBody).forEach { match ->
            val escapedInner = "\"${match.groupValues[1]}\""
            runCatching {
                val decodedJson = org.json.JSONTokener(escapedInner).nextValue()?.toString().orEmpty()
                if (decodedJson.trimStart().startsWith("{")) {
                    extractInstagramFromJsonTree(JSONObject(decodedJson), title, streamHeaders, localCandidates, audioPool)
                }
            }
        }

        // 3. Balanced JSON array capture for "video_versions": [...]
        var searchFrom = 0
        while (true) {
            val idx = unescaped.indexOf("\"video_versions\"", searchFrom)
            if (idx < 0) break
            val arrStart = unescaped.indexOf('[', idx)
            if (arrStart < 0) break
            val arrText = extractBalancedBracket(unescaped, arrStart, '[', ']')
            if (!arrText.isNullOrBlank()) {
                runCatching {
                    val arr = JSONArray(arrText)
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val vUrl = sanitizeCdnUrl(item.optString("url"))
                        if (vUrl.startsWith("http")) {
                            if (isMetaAudioOnlyUrl(vUrl)) {
                                audioPool.add(vUrl)
                                continue
                            }
                            val w = item.optInt("width", 0)
                            val h = item.optInt("height", 0)
                            val tier = computeShortSideTier(w, h, fallback = 1080)
                            localCandidates.add(
                                ExtractedStream(
                                    streamUrl = vUrl,
                                    title = title,
                                    platform = PlatformBadge.INSTAGRAM,
                                    qualityLabel = formatQualityLabel(tier),
                                    resolutionHeight = tier,
                                    mimeType = "video/mp4",
                                    estimatedBytes = -1L,
                                    headers = streamHeaders,
                                    bitrateBps = if (tier >= 1080) 3_500_000L else 1_600_000L,
                                ),
                            )
                        }
                    }
                }
            }
            searchFrom = idx + 16
        }

        // 4. Direct fields (video_url, browser_native_hd_url, playable_url, playback_url, contentUrl, audio_url)
        val directRegex = Regex(
            """"(video_url|videoUrl|browser_native_hd_url|browser_native_sd_url|playable_url_quality_hd|playable_url|playback_url|hd_url|sd_url|media_url|contentUrl|audio_url)"\s*:\s*"(https?://[^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
        directRegex.findAll(unescaped).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val mediaUrl = sanitizeCdnUrl(match.groupValues[2])
            if (mediaUrl.startsWith("http") &&
                (mediaUrl.contains(".mp4", ignoreCase = true) ||
                    mediaUrl.contains("cdninstagram", ignoreCase = true) ||
                    mediaUrl.contains("fbcdn.net", ignoreCase = true))
            ) {
                if (key == "audio_url" || isMetaAudioOnlyUrl(mediaUrl)) {
                    audioPool.add(mediaUrl)
                    return@forEach
                }
                val tier = if (key.contains("hd") || key == "video_url" || key == "videourl") 1080 else 720
                localCandidates.add(
                    ExtractedStream(
                        streamUrl = mediaUrl,
                        title = title,
                        platform = PlatformBadge.INSTAGRAM,
                        qualityLabel = formatQualityLabel(tier),
                        resolutionHeight = tier,
                        mimeType = "video/mp4",
                        estimatedBytes = -1L,
                        headers = streamHeaders,
                        bitrateBps = if (tier >= 1080) 3_000_000L else 1_200_000L,
                    ),
                )
            }
        }

        // 4b. Broad CDN .mp4 URL fallback inside unescaped embed/GraphQL JSON ("url":"https://scontent...mp4...")
        val cdnMp4Regex = Regex(
            """"url"\s*:\s*"(https?://[^"]*(?:cdninstagram\.com|fbcdn\.net)[^"]*\.mp4[^"]*)"""",
            RegexOption.IGNORE_CASE,
        )
        cdnMp4Regex.findAll(unescaped).forEach { match ->
            val mediaUrl = sanitizeCdnUrl(match.groupValues[1])
            if (mediaUrl.startsWith("http")) {
                if (isMetaAudioOnlyUrl(mediaUrl)) {
                    audioPool.add(mediaUrl)
                } else if (localCandidates.none { it.streamUrl == mediaUrl }) {
                    localCandidates.add(
                        ExtractedStream(
                            streamUrl = mediaUrl,
                            title = title,
                            platform = PlatformBadge.INSTAGRAM,
                            qualityLabel = "1080p",
                            resolutionHeight = 1080,
                            mimeType = "video/mp4",
                            estimatedBytes = -1L,
                            headers = streamHeaders,
                            bitrateBps = 2_600_000L,
                        ),
                    )
                }
            }
        }

        // 5. DASH XML MPD inside HTML/JSON
        extractScopedMpdStreams(unescaped, title, PlatformBadge.INSTAGRAM, streamHeaders, localCandidates, audioPool)

        // 6. og:video / <video> tags inside embed HTML
        extractOgAndVideoTags(unescaped, baseUrl, title, PlatformBadge.INSTAGRAM, streamHeaders, localCandidates)

        // Pair pooled audio to any non-progressive Instagram candidate missing audioStreamUrl.
        // LocalMediaMuxer safely preserves any native audio track already inside videoFile.
        val pooledAudio = audioPool.firstOrNull { !it.endsWith(".mp3", ignoreCase = true) }
            ?: audioPool.firstOrNull()
        localCandidates.forEach { candidate ->
            if (candidate.audioStreamUrl == null &&
                pooledAudio != null &&
                !isMetaProgressiveUrl(candidate.streamUrl)
            ) {
                out.add(candidate.copy(audioStreamUrl = pooledAudio))
            } else {
                out.add(candidate)
            }
        }
    }

    private fun extractInstagramFromJsonTree(
        node: Any?,
        title: String,
        headers: Map<String, String>,
        out: MutableList<ExtractedStream>,
        audioPool: MutableList<String>,
        depth: Int = 0,
    ) {
        if (node == null || depth > 14) return
        when (node) {
            is JSONObject -> {
                val audioUrlField = sanitizeCdnUrl(node.optString("audio_url"))
                if (audioUrlField.startsWith("http")) {
                    audioPool.add(audioUrlField)
                }

                val videoUrl = node.optString("video_url")
                if (videoUrl.startsWith("http")) {
                    val cleanV = sanitizeCdnUrl(videoUrl)
                    if (isMetaAudioOnlyUrl(cleanV)) {
                        audioPool.add(cleanV)
                    } else {
                        val dims = node.optJSONObject("dimensions")
                        val w = dims?.optInt("width", 0) ?: node.optInt("original_width", 0)
                        val h = dims?.optInt("height", 0) ?: node.optInt("original_height", 0)
                        val tier = computeShortSideTier(w, h, fallback = 1080)
                        out.add(
                            ExtractedStream(
                                streamUrl = cleanV,
                                title = title,
                                platform = PlatformBadge.INSTAGRAM,
                                qualityLabel = formatQualityLabel(tier),
                                resolutionHeight = tier,
                                mimeType = "video/mp4",
                                estimatedBytes = -1L,
                                headers = headers,
                                bitrateBps = 3_200_000L,
                            ),
                        )
                    }
                }

                val versions = node.optJSONArray("video_versions")
                if (versions != null) {
                    for (i in 0 until versions.length()) {
                        val v = versions.optJSONObject(i) ?: continue
                        val u = sanitizeCdnUrl(v.optString("url"))
                        if (u.startsWith("http")) {
                            if (isMetaAudioOnlyUrl(u)) {
                                audioPool.add(u)
                                continue
                            }
                            val tier = computeShortSideTier(v.optInt("width", 0), v.optInt("height", 0), 1080)
                            out.add(
                                ExtractedStream(
                                    streamUrl = u,
                                    title = title,
                                    platform = PlatformBadge.INSTAGRAM,
                                    qualityLabel = formatQualityLabel(tier),
                                    resolutionHeight = tier,
                                    mimeType = "video/mp4",
                                    estimatedBytes = -1L,
                                    headers = headers,
                                    bitrateBps = if (tier >= 1080) 3_600_000L else 1_600_000L,
                                ),
                            )
                        }
                    }
                }

                val rawDashXml = node.optString("video_dash_manifest")
                    .ifBlank { node.optString("dash_manifest") }
                    .ifBlank { node.optString("all_video_dash_manifest") }
                val unescapedDash = if (rawDashXml.isNotBlank()) unescapeMetaMarkup(rawDashXml) else ""
                if (unescapedDash.contains("<MPD", ignoreCase = true)) {
                    extractScopedMpdStreams(
                        markup = unescapedDash,
                        title = title,
                        platform = PlatformBadge.INSTAGRAM,
                        headers = headers,
                        out = out,
                        audioPoolOut = audioPool,
                    )
                }

                // Capture Instagram Reel sound progressive download URL as backup audio source
                val soundFallback = sanitizeCdnUrl(
                    node.optString("progressive_download_url")
                        .ifBlank { node.optString("fast_start_progressive_download_url") },
                )
                if (soundFallback.startsWith("http") &&
                    (soundFallback.contains("cdninstagram", true) || soundFallback.contains("fbcdn.net", true))
                ) {
                    audioPool.add(soundFallback)
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    extractInstagramFromJsonTree(node.opt(key), title, headers, out, audioPool, depth + 1)
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractInstagramFromJsonTree(node.opt(i), title, headers, out, audioPool, depth + 1)
                }
            }
        }
    }

    fun extractFacebook(rawUrl: String): MetaExtractionOutcome {
        val canonicalUrl = resolveRedirects(rawUrl, "https://www.facebook.com/")
        val targetVideoId = extractFacebookVideoId(canonicalUrl) ?: extractFacebookVideoId(rawUrl)
        val found = mutableListOf<ExtractedStream>()
        var authChallenge = false

        val encodedCanonical = runCatching { URLEncoder.encode(canonicalUrl, "UTF-8") }.getOrDefault("")
        val mbasicTarget = buildMbasicFacebookUrl(canonicalUrl, targetVideoId)
        val mobileWatchTarget = canonicalUrl
            .replace("://www.facebook.com", "://m.facebook.com", ignoreCase = true)
            .replace("://facebook.com", "://m.facebook.com", ignoreCase = true)

        // Step 1: Strictly single-video endpoints first so suggested/related feed videos never pollute results!
        val isolatedEndpoints = buildList {
            if (encodedCanonical.isNotBlank()) {
                add(
                    "https://www.facebook.com/plugins/video.php?href=$encodedCanonical&show_text=false" to
                        ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
                )
            }
            if (!mbasicTarget.isNullOrBlank()) {
                add(mbasicTarget to ClientSpoofer.ANDROID_CHROME_USER_AGENT)
            }
            if (!targetVideoId.isNullOrBlank()) {
                add("https://www.facebook.com/watch/?v=$targetVideoId" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
            }
            add(canonicalUrl to ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
            add(mobileWatchTarget to ClientSpoofer.ANDROID_CHROME_USER_AGENT)
        }.distinctBy { it.first }

        for ((target, ua) in isolatedEndpoints) {
            val isPluginOrSinglePost = target.contains("plugins/video.php", ignoreCase = true) ||
                target.contains("mbasic.facebook.com", ignoreCase = true)

            val req = Request.Builder()
                .url(target)
                .header("User-Agent", ua)
                .header("Referer", "https://www.facebook.com/")
                .header("Origin", "https://www.facebook.com")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .build()

            val streamHeaders = mapOf(
                "User-Agent" to ua,
                "Referer" to "https://www.facebook.com/",
                "Origin" to "https://www.facebook.com",
            )

            runCatching {
                client.newCall(req).execute().use { resp ->
                    val finalUrl = resp.request.url.toString()
                    if (resp.code == 401 || resp.code == 403 ||
                        finalUrl.contains("/login", ignoreCase = true) ||
                        finalUrl.contains("/checkpoint", ignoreCase = true)
                    ) {
                        authChallenge = true
                        return@use
                    }
                    if (!resp.isSuccessful) return@use
                    val rawHtml = resp.body?.string().orEmpty()
                    if (rawHtml.isBlank()) return@use

                    val title = extractHtmlTitle(rawHtml) ?: targetVideoId?.let { "Facebook_$it" } ?: "Facebook_Video"
                    val unescaped = unescapeMetaMarkup(rawHtml)

                    if (unescaped.contains("login_form") ||
                        unescaped.contains("You must log in to continue", ignoreCase = true)
                    ) {
                        authChallenge = true
                    }

                    // A. mbasic direct /video_redirect/?src=... (always 100% the single target video!)
                    val redirectRegex = Regex("""/video_redirect/\?src=([^"'&\s<>]+)""", RegexOption.IGNORE_CASE)
                    redirectRegex.findAll(unescaped).forEach { match ->
                        val decodedSrc = sanitizeCdnUrl(
                            runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }
                                .getOrDefault(match.groupValues[1]),
                        )
                        if (decodedSrc.startsWith("http")) {
                            found.add(
                                ExtractedStream(
                                    streamUrl = decodedSrc,
                                    title = title,
                                    platform = PlatformBadge.FACEBOOK,
                                    qualityLabel = "BEST HD",
                                    resolutionHeight = 720,
                                    mimeType = "video/mp4",
                                    estimatedBytes = -1L,
                                    headers = streamHeaders,
                                    bitrateBps = 2_000_000L,
                                ),
                            )
                        }
                    }

                    // B. If targetVideoId is known & this isn't an isolated plugin page, scope extraction to targetVideoId first
                    val scopedHtml = if (!isPluginOrSinglePost && !targetVideoId.isNullOrBlank()) {
                        extractScopedFacebookBlock(unescaped, targetVideoId) ?: ""
                    } else {
                        unescaped
                    }

                    val parseTarget = if (scopedHtml.isNotBlank()) scopedHtml else if (isPluginOrSinglePost) unescaped else ""
                    if (parseTarget.isNotBlank()) {
                        extractFacebookFieldsFromMarkup(parseTarget, finalUrl, title, streamHeaders, found)
                    }

                    // Only fall back to unscoped page if scoped matched nothing AND it's a direct single-video URL
                    if (found.isEmpty() && isPluginOrSinglePost) {
                        extractOgAndVideoTags(unescaped, finalUrl, title, PlatformBadge.FACEBOOK, streamHeaders, found)
                    }
                }
            }

            if (found.any { it.resolutionHeight >= 1080 && hasConfirmedAudio(it) }) {
                break
            }
            if (isPluginOrSinglePost && found.any { hasConfirmedAudio(it) && it.resolutionHeight >= 720 }) {
                // Single-video endpoints already found A+V HD streams for the exact requested video
                break
            }
        }

        val globalAudio = found.firstNotNullOfOrNull { it.audioStreamUrl }
        val audioEnriched = if (globalAudio != null) {
            found.map { stream ->
                if (stream.audioStreamUrl == null && !isMetaProgressiveUrl(stream.streamUrl)) {
                    stream.copy(audioStreamUrl = globalAudio)
                } else {
                    stream
                }
            }
        } else {
            found
        }

        val cleanVerified = verifyAndDedupe(audioEnriched)
        return MetaExtractionOutcome(
            streams = cleanVerified,
            authChallenge = authChallenge && cleanVerified.isEmpty(),
            canonicalUrl = canonicalUrl,
            targetVideoId = targetVideoId,
        )
    }

    private fun extractScopedFacebookBlock(unescapedHtml: String, videoId: String): String? {
        val candidates = listOf(
            "\"video_id\":\"$videoId\"",
            "\"id\":\"$videoId\"",
            "\"videoId\":\"$videoId\"",
        )
        val collectedSlices = mutableListOf<String>()
        for (marker in candidates) {
            var pos = 0
            while (true) {
                val idx = unescapedHtml.indexOf(marker, pos)
                if (idx < 0) break
                val start = (idx - 3500).coerceAtLeast(0)
                val end = (idx + 12_000).coerceAtMost(unescapedHtml.length)
                collectedSlices.add(unescapedHtml.substring(start, end))
                pos = idx + marker.length
            }
        }
        return if (collectedSlices.isNotEmpty()) collectedSlices.joinToString("\n") else null
    }

    private fun extractFacebookFieldsFromMarkup(
        markup: String,
        baseUrl: String,
        title: String,
        streamHeaders: Map<String, String>,
        out: MutableList<ExtractedStream>,
    ) {
        val localCandidates = mutableListOf<ExtractedStream>()
        val audioPool = mutableListOf<String>()

        // Capture standalone audio_url fields if present
        Regex(""""audio_url"\s*:\s*"(https?://[^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(markup)
            .forEach { m ->
                val aUrl = sanitizeCdnUrl(m.groupValues[1])
                if (aUrl.startsWith("http")) audioPool.add(aUrl)
            }

        // 1. Progressive full-file HD & SD fields (contain BOTH audio and video unless marked DASH video-only)
        val fbJsonKeys = listOf(
            "browser_native_hd_url" to 1080,
            "playable_url_quality_hd" to 1080,
            "hd_src_no_ratelimit" to 1080,
            "hd_src" to 1080,
            "browser_native_sd_url" to 720,
            "playable_url" to 720,
            "sd_src_no_ratelimit" to 720,
            "sd_src" to 720,
        )
        fbJsonKeys.forEach { (key, heightTier) ->
            val regex = Regex(""""$key"\s*:\s*"(https?://[^"]+)"""")
            regex.findAll(markup).forEach { match ->
                val cleanUrl = sanitizeCdnUrl(match.groupValues[1])
                if (cleanUrl.startsWith("http")) {
                    if (isMetaAudioOnlyUrl(cleanUrl)) {
                        audioPool.add(cleanUrl)
                    } else {
                        localCandidates.add(
                            ExtractedStream(
                                streamUrl = cleanUrl,
                                title = title,
                                platform = PlatformBadge.FACEBOOK,
                                qualityLabel = formatQualityLabel(heightTier),
                                resolutionHeight = heightTier,
                                mimeType = "video/mp4",
                                estimatedBytes = -1L,
                                headers = streamHeaders,
                                bitrateBps = if (heightTier >= 1080) 4_000_000L else 1_600_000L,
                            ),
                        )
                    }
                }
            }
        }

        // 2. Per-MPD DASH manifest extraction (pairs video & audio strictly within the SAME <MPD> block)
        extractScopedMpdStreams(markup, title, PlatformBadge.FACEBOOK, streamHeaders, localCandidates, audioPool)

        // 3. OpenGraph / <video> tags inside isolated block
        extractOgAndVideoTags(markup, baseUrl, title, PlatformBadge.FACEBOOK, streamHeaders, localCandidates)

        val pooledAudio = audioPool.firstOrNull()
        localCandidates.forEach { cand ->
            if (cand.audioStreamUrl == null && pooledAudio != null && !isMetaProgressiveUrl(cand.streamUrl)) {
                out.add(cand.copy(audioStreamUrl = pooledAudio))
            } else {
                out.add(cand)
            }
        }
    }

    private fun extractScopedMpdStreams(
        markup: String,
        title: String,
        platform: PlatformBadge,
        headers: Map<String, String>,
        out: MutableList<ExtractedStream>,
        audioPoolOut: MutableList<String>? = null,
    ) {
        val mpdRegex = Regex("""<MPD[\s\S]*?</MPD>""", RegexOption.IGNORE_CASE)
        mpdRegex.findAll(markup).forEach { mpdMatch ->
            val mpdXml = mpdMatch.value
            val adaptationRegex = Regex("""<AdaptationSet([\s\S]*?)</AdaptationSet>""", RegexOption.IGNORE_CASE)
            val repRegex = Regex("""<Representation([^>]*)>([\s\S]*?)</Representation>""", RegexOption.IGNORE_CASE)
            val baseRegex = Regex("""<BaseURL[^>]*>\s*(https?://[^<\s]+)\s*</BaseURL>""", RegexOption.IGNORE_CASE)

            var bestAudioUrl: String? = null
            var bestAudioIsAac = false
            var bestAudioBandwidth = -1L
            // Tuple/data: (url, tier, bandwidth, isH264Compat)
            val videoCandidates = mutableListOf<MpdVideoCandidate>()

            adaptationRegex.findAll(mpdXml).forEach { adaptMatch ->
                val adaptBlock = adaptMatch.value
                val adaptHeader = adaptBlock.substringBefore(">")
                val adaptIsAudio = Regex("""(?:contentType|mimeType)=["']audio""", RegexOption.IGNORE_CASE).containsMatchIn(adaptHeader) ||
                    adaptHeader.contains("mp4a.", ignoreCase = true)
                val adaptIsVideo = Regex("""(?:contentType|mimeType)=["']video""", RegexOption.IGNORE_CASE).containsMatchIn(adaptHeader)

                repRegex.findAll(adaptBlock).forEach { repMatch ->
                    val attrs = repMatch.groupValues[1]
                    val inner = repMatch.groupValues[2]
                    val rawBase = baseRegex.find(inner)?.groupValues?.getOrNull(1) ?: return@forEach
                    val cleanBase = sanitizeCdnUrl(rawBase)
                    if (!cleanBase.startsWith("http")) return@forEach

                    val codecs = Regex("""codecs=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(attrs)?.groupValues?.getOrNull(1)
                        ?: Regex("""codecs=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            .find(adaptHeader)?.groupValues?.getOrNull(1)
                        ?: ""
                    val repMime = Regex("""mimeType=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(attrs)?.groupValues?.getOrNull(1).orEmpty()
                    val bandwidth = Regex("""bandwidth=["'](\d+)["']""").find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

                    val isAudioRep = adaptIsAudio ||
                        repMime.startsWith("audio", ignoreCase = true) ||
                        codecs.contains("mp4a", ignoreCase = true) ||
                        codecs.contains("opus", ignoreCase = true) ||
                        attrs.contains("audioSamplingRate", ignoreCase = true) ||
                        inner.contains("AudioChannelConfiguration", ignoreCase = true) ||
                        isMetaAudioOnlyUrl(cleanBase)

                    if (isAudioRep) {
                        audioPoolOut?.add(cleanBase)
                        val isAac = codecs.contains("mp4a", ignoreCase = true) || !codecs.contains("opus", ignoreCase = true)
                        if (bestAudioUrl == null || (isAac && !bestAudioIsAac) || (isAac == bestAudioIsAac && bandwidth >= bestAudioBandwidth)) {
                            bestAudioBandwidth = bandwidth
                            bestAudioIsAac = isAac
                            bestAudioUrl = cleanBase
                        }
                    } else if (adaptIsVideo || repMime.startsWith("video", ignoreCase = true) || attrs.contains("height=", ignoreCase = true)) {
                        val width = Regex("""width=["'](\d+)["']""").find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        val height = Regex("""height=["'](\d+)["']""").find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        val fbQuality = Regex("""FBQualityLabel=["'](\d+)p["']""", RegexOption.IGNORE_CASE)
                            .find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        val tier = maxOf(
                            computeShortSideTier(width, height, fallback = 720),
                            fbQuality,
                        )
                        val isH264 = codecs.contains("avc", ignoreCase = true) ||
                            (codecs.isBlank() && isAvcH264MetaUrl(cleanBase)) ||
                            (!codecs.contains("vp0", true) && !codecs.contains("vp9", true) && !codecs.contains("av01", true) && isAvcH264MetaUrl(cleanBase))
                        videoCandidates.add(MpdVideoCandidate(cleanBase, tier, bandwidth, isH264))
                    }
                }
            }

            videoCandidates
                .sortedWith(
                    compareByDescending<MpdVideoCandidate> { it.isH264Compat }
                        .thenByDescending { it.tier }
                        .thenByDescending { it.bandwidth },
                )
                .forEach { cand ->
                    out.add(
                        ExtractedStream(
                            streamUrl = cand.url,
                            title = title,
                            platform = platform,
                            qualityLabel = formatQualityLabel(cand.tier),
                            resolutionHeight = cand.tier,
                            mimeType = "video/mp4",
                            estimatedBytes = -1L,
                            headers = headers,
                            audioStreamUrl = bestAudioUrl,
                            bitrateBps = cand.bandwidth,
                        ),
                    )
                }
        }
    }

    private data class MpdVideoCandidate(
        val url: String,
        val tier: Int,
        val bandwidth: Long,
        val isH264Compat: Boolean,
    )

    private fun extractOgAndVideoTags(
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
                val resolved = sanitizeCdnUrl(resolveRelativeUrl(baseUrl, match.groupValues[1]))
                if (looksLikeMediaUrl(resolved)) {
                    out.add(
                        ExtractedStream(
                            streamUrl = resolved,
                            title = title,
                            platform = platform,
                            qualityLabel = "BEST HD",
                            resolutionHeight = 720,
                            mimeType = "video/mp4",
                            estimatedBytes = -1L,
                            headers = headers,
                        ),
                    )
                }
            }
        }
        val tagRegex = Regex("""<(?:video|source)[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        tagRegex.findAll(unescapedHtml).forEach { match ->
            val resolved = sanitizeCdnUrl(resolveRelativeUrl(baseUrl, match.groupValues[1]))
            if (looksLikeMediaUrl(resolved)) {
                out.add(
                    ExtractedStream(
                        streamUrl = resolved,
                        title = title,
                        platform = platform,
                        qualityLabel = "BEST HD",
                        resolutionHeight = 720,
                        mimeType = "video/mp4",
                        estimatedBytes = -1L,
                        headers = headers,
                    ),
                )
            }
        }
    }

    private fun hasConfirmedAudio(stream: ExtractedStream): Boolean {
        return !stream.audioStreamUrl.isNullOrBlank() || isMetaProgressiveUrl(stream.streamUrl)
    }

    private fun hasUsableAudio(stream: ExtractedStream): Boolean {
        return !stream.audioStreamUrl.isNullOrBlank() || !isMetaVideoOnlyDashUrl(stream.streamUrl)
    }

    private fun verifyAndDedupe(candidates: List<ExtractedStream>): List<ExtractedStream> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates
            .filterNot { isMetaAudioOnlyUrl(it.streamUrl) }
            .distinctBy { it.streamUrl }
            .sortedWith(
                compareByDescending<ExtractedStream> { hasUsableAudio(it) } // NEVER pick silent DASH video-only over A+V!
                    .thenByDescending { it.audioStreamUrl == null || isAvcH264MetaUrl(it.streamUrl) } // Prefer mux-safe H.264/progressive
                    .thenByDescending { it.audioStreamUrl == null && isMetaProgressiveUrl(it.streamUrl) }
                    .thenByDescending { it.resolutionHeight }
                    .thenByDescending { it.bitrateBps }
                    .thenByDescending { it.estimatedBytes },
            )

        val verified = mutableListOf<ExtractedStream>()
        for (stream in sorted.take(8)) {
            val fullBytes = probeVerifiedContentLength(stream.streamUrl, stream.headers)
            if (fullBytes != null && (fullBytes == -1L || fullBytes >= 4_096L)) {
                val audioOk = stream.audioStreamUrl.isNullOrBlank() ||
                    probeVerifiedContentLength(stream.audioStreamUrl, stream.headers) != null
                if (audioOk) {
                    verified.add(stream.copy(estimatedBytes = if (fullBytes > 0L) fullBytes else stream.estimatedBytes))
                } else {
                    // Retain video even if separate audio probe returned 403/timeout so download pipeline can still fallback
                    verified.add(stream.copy(estimatedBytes = if (fullBytes > 0L) fullBytes else stream.estimatedBytes))
                }
            }
        }
        return if (verified.isNotEmpty()) verified else sorted.take(4)
    }

    private fun probeVerifiedContentLength(url: String, headers: Map<String, String>): Long? {
        val cleanHeaders = ClientSpoofer.buildDownloadHeaders(url, headers)
        val rangeBuilder = Request.Builder().url(url).header("Range", "bytes=0-0")
        cleanHeaders.forEach { (k, v) -> rangeBuilder.header(k, v) }
        val rangeRes = runCatching {
            client.newCall(rangeBuilder.build()).execute().use { resp ->
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.startsWith("text/html")) return@use null
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
        if (rangeRes != null) return rangeRes

        val headBuilder = Request.Builder().url(url).head()
        cleanHeaders.forEach { (k, v) -> headBuilder.header(k, v) }
        return runCatching {
            client.newCall(headBuilder.build()).execute().use { resp ->
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.startsWith("text/html")) return@use null
                if (resp.isSuccessful) {
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    fun extractFacebookVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:facebook\.com|fb\.com)/[^/]+/videos/(?:[^/]+/)?(\d{6,})"""),
            Regex("""(?:facebook\.com|fb\.com)/(?:reel|reels|videos)/(\d{6,})"""),
            Regex("""[?&]v=(\d{6,})"""),
            Regex("""[?&]story_fbid=(\d{6,})"""),
            Regex("""[?&]fbid=(\d{6,})"""),
        )
        for (p in patterns) {
            val id = p.find(url)?.groupValues?.getOrNull(1)
            if (!id.isNullOrBlank()) return id
        }
        return null
    }

    private fun buildMbasicFacebookUrl(canonicalUrl: String, videoId: String?): String? {
        if (!videoId.isNullOrBlank()) {
            return "https://mbasic.facebook.com/watch/?v=$videoId"
        }
        return canonicalUrl
            .replace("://www.facebook.com", "://mbasic.facebook.com", ignoreCase = true)
            .replace("://m.facebook.com", "://mbasic.facebook.com", ignoreCase = true)
            .replace("://facebook.com", "://mbasic.facebook.com", ignoreCase = true)
            .takeIf { it.contains("mbasic.facebook.com", ignoreCase = true) }
    }

    private fun stripInstagramTrackingParams(rawUrl: String): String {
        val parsed = rawUrl.toHttpUrlOrNull() ?: return rawUrl
        val builder = parsed.newBuilder()
        listOf("igsh", "igshid", "utm_source", "utm_medium", "utm_campaign", "fbclid")
            .forEach { builder.removeAllQueryParameters(it) }
        return builder.build().toString()
    }

    private fun resolveRedirects(url: String, referer: String): String {
        val noRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val uas = listOf(ClientSpoofer.ANDROID_CHROME_USER_AGENT, "facebookexternalhit/1.1")
        for (ua in uas) {
            var current = url
            for (hop in 0 until 6) {
                val req = Request.Builder()
                    .url(current)
                    .header("User-Agent", ua)
                    .header("Referer", referer)
                    .build()
                val nextTarget: String? = runCatching {
                    noRedirectClient.newCall(req).execute().use { resp ->
                        val location = resp.header("Location")?.trim()
                        if (resp.code in 300..399 && !location.isNullOrBlank()) {
                            val absolute = runCatching { URI(current).resolve(location).toString() }.getOrDefault(location)
                            if (absolute.contains("/accounts/login", ignoreCase = true) ||
                                absolute.contains("/login", ignoreCase = true)
                            ) {
                                val nextParam = absolute.toHttpUrlOrNull()?.queryParameter("next")
                                if (!nextParam.isNullOrBlank()) {
                                    val decoded = runCatching { URLDecoder.decode(nextParam, "UTF-8") }.getOrDefault(nextParam)
                                    val normalized = when {
                                        decoded.startsWith("http", ignoreCase = true) -> decoded
                                        decoded.startsWith("/") -> "${resp.request.url.scheme}://${resp.request.url.host}$decoded"
                                        else -> ""
                                    }
                                    if (normalized.startsWith("http", ignoreCase = true) &&
                                        !normalized.contains("/login", ignoreCase = true)
                                    ) {
                                        return@use normalized
                                    }
                                }
                                return@use null
                            }
                            return@use absolute
                        }

                        if (current.contains("/share/", ignoreCase = true) && resp.isSuccessful) {
                            val snippet = resp.body?.string()?.take(65_536).orEmpty()
                            val canonicalMatch = Regex(
                                """(?:property=["']og:url["'][^>]+content=["']|rel=["']canonical["'][^>]+href=["'])(https?://[^"']+)["']""",
                                RegexOption.IGNORE_CASE,
                            ).find(snippet)?.groupValues?.getOrNull(1)
                            if (!canonicalMatch.isNullOrBlank() &&
                                !canonicalMatch.contains("/login", ignoreCase = true) &&
                                !canonicalMatch.contains("/share/", ignoreCase = true)
                            ) {
                                return@use canonicalMatch
                            }
                        }
                        null
                    }
                }.getOrNull()

                if (nextTarget.isNullOrBlank() || nextTarget == current) break
                current = nextTarget
                if (Regex("""instagram\.com/(?:[^/]+/)?(?:p|reel|reels|tv)/[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(current)
                ) {
                    return current
                }
            }
            if (!current.contains("/share/", ignoreCase = true) && current != url) {
                return current
            }
        }
        return url
    }

    private fun sanitizeCdnUrl(raw: String): String {
        val trimmed = unescapeMetaMarkup(raw).trim()
        if (!trimmed.startsWith("http")) return trimmed
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return trimmed
        val cleanBuilder = httpUrl.newBuilder()
        // Strip partial chunk range params so downloads always fetch the entire video, not a 2-second segment
        listOf("bytestart", "byteend", "range", "rbuf", "rn").forEach { param ->
            cleanBuilder.removeAllQueryParameters(param)
        }
        return cleanBuilder.build().toString()
    }

    private fun unescapeMetaMarkup(input: String): String {
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
            .replace("\\n", "\n")
            .replace("\\\\\"", "\"")
            .replace("\\\"", "\"")
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
    }

    private fun shortcodeToInstagramMediaId(shortcode: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return runCatching {
            var id = BigInteger.ZERO
            val base = BigInteger.valueOf(64L)
            for (ch in shortcode) {
                val idx = alphabet.indexOf(ch)
                if (idx < 0) return null
                id = id.multiply(base).add(BigInteger.valueOf(idx.toLong()))
            }
            id.toString()
        }.getOrNull()
    }

    private fun extractBalancedBracket(text: String, startIdx: Int, open: Char, close: Char): String? {
        if (startIdx < 0 || startIdx >= text.length || text[startIdx] != open) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in startIdx until text.length) {
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
                if (ch == open) depth++
                else if (ch == close) {
                    depth--
                    if (depth == 0) return text.substring(startIdx, i + 1)
                }
            }
        }
        return null
    }

    private fun extractHtmlTitle(html: String): String? {
        val og = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        if (!og.isNullOrBlank()) return og.replace(Regex("""\s+"""), " ").trim().take(72)
        val t = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        return t?.takeIf { it.isNotBlank() }?.replace(Regex("""\s+"""), " ")?.trim()?.take(72)
    }

    private fun looksLikeMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        val path = lower.substringBefore("?")
        return path.endsWith(".mp4") || path.endsWith(".m3u8") ||
            lower.contains("cdninstagram") || lower.contains("fbcdn.net")
    }

    private fun resolveRelativeUrl(baseUrl: String, candidate: String): String {
        return runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrDefault(candidate)
    }

    private fun computeShortSideTier(width: Int, height: Int, fallback: Int): Int {
        val raw = when {
            width > 0 && height > 0 -> minOf(width, height)
            height > 0 -> height
            width > 0 -> width
            else -> fallback
        }
        return when {
            raw >= 2100 -> 2160
            raw >= 1400 -> 1440
            raw >= 1050 -> 1080
            raw >= 700 -> 720
            else -> raw
        }
    }

    companion object {
        fun decodeMetaEfgTag(url: String): String? {
            val httpUrl = url.toHttpUrlOrNull() ?: return null
            val efg = httpUrl.queryParameter("efg")?.takeIf { it.isNotBlank() } ?: return null
            val flagsList = listOf(
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                Base64.URL_SAFE or Base64.NO_WRAP,
                Base64.DEFAULT,
            )
            for (flags in flagsList) {
                val decoded = runCatching {
                    String(Base64.decode(efg, flags), StandardCharsets.UTF_8)
                }.getOrNull()
                if (!decoded.isNullOrBlank() && (decoded.contains("{") || decoded.contains("vencode"))) {
                    return decoded.lowercase()
                }
            }
            return null
        }

        fun isMetaAudioOnlyUrl(url: String): Boolean {
            val lower = url.lowercase()
            val pathOnly = lower.substringBefore("?")
            if (pathOnly.endsWith(".m4a") ||
                pathOnly.endsWith(".aac") ||
                lower.contains("mime=audio") ||
                lower.contains("mime_type=audio") ||
                lower.contains("audio_mp4")
            ) {
                return true
            }
            val efg = decodeMetaEfgTag(url) ?: return false
            val hasAudioMarker = efg.contains("audio") ||
                efg.contains("heaac") ||
                efg.contains("mp4a") ||
                efg.contains("aac_") ||
                efg.contains("opus")
            val hasVideoMarker = efg.contains("h264") ||
                efg.contains("avc") ||
                efg.contains("vp9") ||
                efg.contains("vp09") ||
                efg.contains("hevc") ||
                efg.contains("av01") ||
                efg.contains("720p") ||
                efg.contains("1080p") ||
                efg.contains("480p") ||
                efg.contains("360p")
            return hasAudioMarker && !hasVideoMarker
        }

        fun isMetaProgressiveUrl(url: String): Boolean {
            val efg = decodeMetaEfgTag(url) ?: return false
            return efg.contains("progressive") && !isMetaAudioOnlyUrl(url)
        }

        fun isMetaVideoOnlyDashUrl(url: String): Boolean {
            if (isMetaAudioOnlyUrl(url)) return false
            val lower = url.lowercase()
            if (lower.contains("mime=video")) return true
            val efg = decodeMetaEfgTag(url) ?: return false
            if (efg.contains("progressive")) return false
            return efg.contains("dash") ||
                efg.contains("xpv_") ||
                efg.contains("sve_") ||
                efg.contains("vp9") ||
                efg.contains("vp09") ||
                efg.contains("av01") ||
                efg.contains("hevc") ||
                efg.contains("vencode")
        }

        fun isAvcH264MetaUrl(url: String): Boolean {
            val efg = decodeMetaEfgTag(url) ?: return true
            return !efg.contains("vp9") &&
                !efg.contains("vp09") &&
                !efg.contains("av01") &&
                !efg.contains("hev1") &&
                !efg.contains("hvc1")
        }
    }
}
