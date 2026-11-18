package com.example.engine.extractor

import com.example.engine.cookies.CookieVaultManager
import com.example.engine.model.ExtractedStream
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
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
        val canonicalUrl = resolveRedirects(rawUrl, "https://www.instagram.com/")
        val found = mutableListOf<ExtractedStream>()
        var authChallenge = false
        val shortcode = Regex("""(?:instagram\.com|instagr\.am)/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""")
            .find(canonicalUrl)?.groupValues?.getOrNull(1)

        val csrfToken = cookieVault?.getCookieValue("https://www.instagram.com/", "csrftoken")
            ?: "missing"
        val igHeadersBase = linkedMapOf(
            "Referer" to "https://www.instagram.com/",
            "Origin" to "https://www.instagram.com",
            "X-IG-App-ID" to "936619743392459",
            "X-ASBD-ID" to "129477",
            "X-IG-WWW-Claim" to "0",
            "X-CSRFToken" to csrfToken,
            "Accept-Language" to "en-US,en;q=0.9",
        )

        val mediaId = shortcode?.let(::shortcodeToInstagramMediaId)
        val hasSessionCookie = !cookieVault?.getCookieValue("https://www.instagram.com/", "sessionid").isNullOrBlank()

        // Layer 1 & 2: Authenticated /api/v1/media/{id}/info/ (Android App + Web)
        if (!mediaId.isNullOrBlank()) {
            val apiEndpoints = if (hasSessionCookie) {
                listOf(
                    "https://i.instagram.com/api/v1/media/$mediaId/info/" to ClientSpoofer.INSTAGRAM_ANDROID_USER_AGENT,
                    "https://www.instagram.com/api/v1/media/$mediaId/info/" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
                )
            } else {
                listOf(
                    "https://www.instagram.com/api/v1/media/$mediaId/info/" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
                )
            }
            for ((endpoint, ua) in apiEndpoints) {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", ua)
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .apply { igHeadersBase.forEach { (k, v) -> header(k, v) } }
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
                            fallbackTitle = shortcode.let { "Instagram_$it" },
                            ua = ClientSpoofer.CHROME_DESKTOP_USER_AGENT,
                            out = found,
                        )
                    }
                }
                if (found.any { it.resolutionHeight >= 1080 }) break
            }
        }

        // Layer 3: Instagram Public GraphQL Endpoint (PolarisPostActionLoadPostQueryQuery)
        if (found.isEmpty() && !shortcode.isNullOrBlank()) {
            val docIds = listOf("8845758582119845", "10015901848480474", "24368985919402291")
            for (docId in docIds) {
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

                val formBody = FormBody.Builder()
                    .add("variables", variablesJson)
                    .add("doc_id", docId)
                    .build()

                val req = Request.Builder()
                    .url("https://www.instagram.com/graphql/query")
                    .post(formBody)
                    .header("User-Agent", ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                    .header("Accept", "*/*")
                    .header("X-FB-Friendly-Name", "PolarisPostActionLoadPostQueryQuery")
                    .apply { igHeadersBase.forEach { (k, v) -> header(k, v) } }
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
                if (found.isNotEmpty()) break
            }
        }

        // Layer 4 & 5: ?__a=1&__d=dis + Captioned Embed + Direct HTML Pages
        if (found.isEmpty()) {
            val htmlTargets = buildList {
                if (!shortcode.isNullOrBlank()) {
                    add("https://www.instagram.com/reel/$shortcode/embed/captioned/" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                    add("https://www.instagram.com/p/$shortcode/embed/captioned/" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                    add("https://www.instagram.com/reel/$shortcode/?__a=1&__d=dis" to ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                }
                add(canonicalUrl to ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
                add(canonicalUrl to ClientSpoofer.ANDROID_CHROME_USER_AGENT)
            }

            for ((target, ua) in htmlTargets) {
                val isJson = target.contains("__a=1")
                val req = Request.Builder()
                    .url(target)
                    .header("User-Agent", ua)
                    .header(
                        "Accept",
                        if (isJson) "application/json" else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    )
                    .apply { igHeadersBase.forEach { (k, v) -> header(k, v) } }
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
                if (found.any { it.resolutionHeight >= 1080 }) break
            }
        }

        val cleanVerified = verifyAndDedupe(found)
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

        // 1. Parse JSON directly if payload is structured API/GraphQL response
        if (rawBody.trimStart().startsWith("{")) {
            runCatching {
                val root = JSONObject(rawBody)
                extractInstagramFromJsonTree(root, title, streamHeaders, out)
            }
        }

        // 2. Extract embedded <script type="application/json" data-sjs> JSON blobs cleanly
        val sjsRegex = Regex("""<script[^>]+type=["']application/json["'][^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
        sjsRegex.findAll(rawBody).forEach { match ->
            val blob = match.groupValues[1].trim()
            if (blob.contains("video_versions") ||
                blob.contains("video_url") ||
                blob.contains("dash_manifest") ||
                blob.contains("playable_url")
            ) {
                runCatching {
                    val obj = JSONObject(blob)
                    extractInstagramFromJsonTree(obj, title, streamHeaders, out)
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
                            val w = item.optInt("width", 0)
                            val h = item.optInt("height", 0)
                            val tier = computeShortSideTier(w, h, fallback = 1080)
                            out.add(
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

        // 4. Direct fields (video_url, playable_url_quality_hd, playable_url, contentUrl)
        val directRegex = Regex(
            """"(video_url|playable_url_quality_hd|playable_url|contentUrl)"\s*:\s*"(https?://[^"]+)"""",
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
                val tier = if (key.contains("hd") || key == "video_url") 1080 else 720
                out.add(
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

        // 5. DASH XML MPD inside HTML/JSON
        extractScopedMpdStreams(unescaped, title, PlatformBadge.INSTAGRAM, streamHeaders, out)

        // 6. og:video / <video> tags inside embed HTML
        extractOgAndVideoTags(unescaped, baseUrl, title, PlatformBadge.INSTAGRAM, streamHeaders, out)
    }

    private fun extractInstagramFromJsonTree(
        node: Any?,
        title: String,
        headers: Map<String, String>,
        out: MutableList<ExtractedStream>,
        depth: Int = 0,
    ) {
        if (node == null || depth > 14) return
        when (node) {
            is JSONObject -> {
                val videoUrl = node.optString("video_url")
                if (videoUrl.startsWith("http")) {
                    val dims = node.optJSONObject("dimensions")
                    val w = dims?.optInt("width", 0) ?: node.optInt("original_width", 0)
                    val h = dims?.optInt("height", 0) ?: node.optInt("original_height", 0)
                    val tier = computeShortSideTier(w, h, fallback = 1080)
                    out.add(
                        ExtractedStream(
                            streamUrl = sanitizeCdnUrl(videoUrl),
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

                val versions = node.optJSONArray("video_versions")
                if (versions != null) {
                    for (i in 0 until versions.length()) {
                        val v = versions.optJSONObject(i) ?: continue
                        val u = sanitizeCdnUrl(v.optString("url"))
                        if (u.startsWith("http")) {
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

                val dashXml = node.optString("video_dash_manifest")
                    .ifBlank { node.optString("dash_manifest") }
                if (dashXml.contains("<MPD", ignoreCase = true)) {
                    extractScopedMpdStreams(unescapeMetaMarkup(dashXml), title, PlatformBadge.INSTAGRAM, headers, out)
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    extractInstagramFromJsonTree(node.opt(key), title, headers, out, depth + 1)
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractInstagramFromJsonTree(node.opt(i), title, headers, out, depth + 1)
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

            if (found.any { it.resolutionHeight >= 1080 && it.audioStreamUrl == null }) {
                break
            }
            if (isPluginOrSinglePost && found.isNotEmpty()) {
                // Single-video endpoints already found progressive/MPD streams for the exact requested video!
                val hasHd = found.any { it.resolutionHeight >= 720 }
                if (hasHd) break
            }
        }

        val cleanVerified = verifyAndDedupe(found)
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
        // 1. Progressive full-file HD & SD fields (contain BOTH audio and video!)
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
                    out.add(
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

        // 2. Per-MPD DASH manifest extraction (pairs video & audio strictly within the SAME <MPD> block)
        extractScopedMpdStreams(markup, title, PlatformBadge.FACEBOOK, streamHeaders, out)

        // 3. OpenGraph / <video> tags inside isolated block
        extractOgAndVideoTags(markup, baseUrl, title, PlatformBadge.FACEBOOK, streamHeaders, out)
    }

    private fun extractScopedMpdStreams(
        markup: String,
        title: String,
        platform: PlatformBadge,
        headers: Map<String, String>,
        out: MutableList<ExtractedStream>,
    ) {
        val mpdRegex = Regex("""<MPD[\s\S]*?</MPD>""", RegexOption.IGNORE_CASE)
        mpdRegex.findAll(markup).forEach { mpdMatch ->
            val mpdXml = mpdMatch.value
            val adaptationRegex = Regex("""<AdaptationSet([\s\S]*?)</AdaptationSet>""", RegexOption.IGNORE_CASE)
            val repRegex = Regex("""<Representation([^>]*)>([\s\S]*?)</Representation>""", RegexOption.IGNORE_CASE)
            val baseRegex = Regex("""<BaseURL[^>]*>(https?://[^<]+)</BaseURL>""", RegexOption.IGNORE_CASE)

            var bestAudioUrl: String? = null
            var bestAudioBandwidth = -1L
            val videoCandidates = mutableListOf<Triple<String, Int, Long>>()

            adaptationRegex.findAll(mpdXml).forEach { adaptMatch ->
                val adaptBlock = adaptMatch.value
                val isAudioSet = adaptBlock.contains("contentType=\"audio\"", ignoreCase = true) ||
                    adaptBlock.contains("audio/mp4", ignoreCase = true) ||
                    adaptBlock.contains("mp4a.", ignoreCase = true)
                val isVideoSet = adaptBlock.contains("contentType=\"video\"", ignoreCase = true) ||
                    adaptBlock.contains("video/mp4", ignoreCase = true)

                repRegex.findAll(adaptBlock).forEach { repMatch ->
                    val attrs = repMatch.groupValues[1]
                    val inner = repMatch.groupValues[2]
                    val rawBase = baseRegex.find(inner)?.groupValues?.getOrNull(1) ?: return@forEach
                    val cleanBase = sanitizeCdnUrl(rawBase)
                    if (!cleanBase.startsWith("http")) return@forEach
                    val bandwidth = Regex("""bandwidth=["'](\d+)["']""").find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

                    if (isAudioSet || cleanBase.contains("audio", ignoreCase = true)) {
                        if (bandwidth >= bestAudioBandwidth) {
                            bestAudioBandwidth = bandwidth
                            bestAudioUrl = cleanBase
                        }
                    } else if (isVideoSet || attrs.contains("height=", ignoreCase = true)) {
                        val width = Regex("""width=["'](\d+)["']""").find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        val height = Regex("""height=["'](\d+)["']""").find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        val fbQuality = Regex("""FBQualityLabel=["'](\d+)p["']""", RegexOption.IGNORE_CASE)
                            .find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        val tier = maxOf(
                            computeShortSideTier(width, height, fallback = 720),
                            fbQuality,
                        )
                        videoCandidates.add(Triple(cleanBase, tier, bandwidth))
                    }
                }
            }

            videoCandidates.forEach { (vUrl, tier, bandwidth) ->
                out.add(
                    ExtractedStream(
                        streamUrl = vUrl,
                        title = title,
                        platform = platform,
                        qualityLabel = formatQualityLabel(tier),
                        resolutionHeight = tier,
                        mimeType = "video/mp4",
                        estimatedBytes = -1L,
                        headers = headers,
                        audioStreamUrl = bestAudioUrl,
                        bitrateBps = bandwidth,
                    ),
                )
            }
        }
    }

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

    private fun verifyAndDedupe(candidates: List<ExtractedStream>): List<ExtractedStream> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates
            .distinctBy { it.streamUrl }
            .sortedWith(
                compareByDescending<ExtractedStream> { it.audioStreamUrl == null } // Prefer complete progressive MP4 over split DASH
                    .thenByDescending { it.resolutionHeight }
                    .thenByDescending { it.bitrateBps }
                    .thenByDescending { it.estimatedBytes },
            )

        val verified = mutableListOf<ExtractedStream>()
        for (stream in sorted.take(6)) {
            val fullBytes = probeVerifiedContentLength(stream.streamUrl, stream.headers)
            // Skip unreachable (403/404) or partial initialization fragments (< 16 KB)
            if (fullBytes != null && (fullBytes == -1L || fullBytes >= 16_384L)) {
                verified.add(stream.copy(estimatedBytes = if (fullBytes > 0L) fullBytes else stream.estimatedBytes))
            }
        }
        return verified
    }

    private fun probeVerifiedContentLength(url: String, headers: Map<String, String>): Long? {
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
            .takeIf { it.contains("mbasic.facebook.com", ignoreCase = true) }
    }

    private fun resolveRedirects(url: String, referer: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ClientSpoofer.CHROME_DESKTOP_USER_AGENT)
            .header("Referer", referer)
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val resolved = resp.request.url.toString()
                if (resolved.startsWith("http") &&
                    !resolved.contains("/login", ignoreCase = true) &&
                    !resolved.contains("/accounts/login", ignoreCase = true)
                ) {
                    resolved
                } else {
                    url
                }
            }
        }.getOrDefault(url)
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
            .replace("\\\\u0026", "&")
            .replace("\\u0026", "&")
            .replace("\\\\u0025", "%")
            .replace("\\u0025", "%")
            .replace("\\\\u002F", "/")
            .replace("\\u002F", "/")
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
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

    private fun formatQualityLabel(height: Int): String = when {
        height >= 2160 -> "4K 2160P"
        height >= 1440 -> "2K 1440P"
        height >= 1080 -> "1080P FHD"
        height >= 720 -> "720P HD"
        height > 0 -> "${height}P"
        else -> "BEST HD"
    }
}
