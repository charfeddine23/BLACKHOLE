package com.example.engine.network

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

data class SpoofedClientProfile(
    val id: String,
    val userAgent: String,
    val extraHeaders: Map<String, String>,
)

object ClientSpoofer {

    const val TIKTOK_IOS_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1"

    const val CHROME_DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    const val ANDROID_CHROME_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.49 Mobile Safari/537.36"

    const val INSTAGRAM_ANDROID_USER_AGENT =
        "Instagram 317.0.0.34.109 Android (34/14; 420dpi; 1080x2400; Google/google; Pixel 8; shiba; shiba; en_US; 562413684)"

    private val rotationIndex = AtomicInteger(0)

    val profiles: List<SpoofedClientProfile> = listOf(
        SpoofedClientProfile(
            id = "CHROME_DESKTOP",
            userAgent = CHROME_DESKTOP_USER_AGENT,
            extraHeaders = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,video/*,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Sec-CH-UA" to "\"Chromium\";v=\"133\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"133\"",
                "Sec-CH-UA-Mobile" to "?0",
                "Sec-CH-UA-Platform" to "\"Windows\"",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Upgrade-Insecure-Requests" to "1",
            ),
        ),
        SpoofedClientProfile(
            id = "ANDROID_CHROME",
            userAgent = ANDROID_CHROME_USER_AGENT,
            extraHeaders = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,video/*,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Sec-CH-UA" to "\"Chromium\";v=\"133\", \"Google Chrome\";v=\"133\"",
                "Sec-CH-UA-Mobile" to "?1",
                "Sec-CH-UA-Platform" to "\"Android\"",
            ),
        ),
        SpoofedClientProfile(
            id = "SAFARI_IOS_MOBILE",
            userAgent = TIKTOK_IOS_USER_AGENT,
            extraHeaders = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        ),
        SpoofedClientProfile(
            id = "EMBED_PLAYER_BOT",
            userAgent = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
            extraHeaders = mapOf(
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        ),
    )

    private val rotatingUserAgents = listOf(
        TIKTOK_IOS_USER_AGENT,
        CHROME_DESKTOP_USER_AGENT,
        ANDROID_CHROME_USER_AGENT,
    )

    fun nextRotatedUserAgent(targetUrl: String): String {
        val lower = targetUrl.lowercase()
        if (lower.contains("tiktok.com") || lower.contains("tiktokcdn")) {
            return TIKTOK_IOS_USER_AGENT
        }
        val idx = (rotationIndex.getAndIncrement() and Int.MAX_VALUE) % rotatingUserAgents.size
        return rotatingUserAgents[idx]
    }

    fun refererForUrl(targetUrl: String): String {
        val lower = targetUrl.lowercase()
        return when {
            lower.contains("tiktok.com") || lower.contains("tiktokcdn") -> "https://www.tiktok.com/"
            lower.contains("instagram.com") || lower.contains("instagr.am") || lower.contains("cdninstagram") ->
                "https://www.instagram.com/"
            lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") || lower.contains("fbcdn") ->
                "https://www.facebook.com/"
            lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("googlevideo") ->
                "https://www.youtube.com/"
            lower.contains("twitter.com") || lower.contains("x.com") || lower.contains("twimg") ->
                "https://x.com/"
            lower.contains("vimeo.com") -> "https://vimeo.com/"
            lower.contains("reddit.com") || lower.contains("redd.it") -> "https://www.reddit.com/"
            else -> runCatching {
                val uri = URI(targetUrl)
                "${uri.scheme}://${uri.authority}/"
            }.getOrDefault("https://www.google.com/")
        }
    }

    fun buildHeadersForUrl(
        targetUrl: String,
        profile: SpoofedClientProfile = profiles.first(),
        refererOverride: String? = null,
    ): Map<String, String> {
        val lower = targetUrl.lowercase()
        val isTikTok = lower.contains("tiktok.com") || lower.contains("tiktokcdn")
        val isInstagram = lower.contains("instagram.com") || lower.contains("instagr.am") || lower.contains("cdninstagram")
        val isFacebook = lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") || lower.contains("fbcdn")

        val resolvedReferer = refererOverride?.takeIf { it.isNotBlank() } ?: refererForUrl(targetUrl)
        val origin = when {
            isTikTok -> "https://www.tiktok.com"
            isInstagram -> "https://www.instagram.com"
            isFacebook -> "https://www.facebook.com"
            else -> runCatching {
                val uri = URI(targetUrl)
                "${uri.scheme}://${uri.authority}"
            }.getOrNull()
        }

        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = if (isTikTok) TIKTOK_IOS_USER_AGENT else profile.userAgent
        if (!isTikTok) {
            headers.putAll(profile.extraHeaders)
        } else {
            headers["Accept"] = "*/*"
            headers["Accept-Language"] = "en-US,en;q=0.9"
        }
        headers["Referer"] = resolvedReferer
        if (!origin.isNullOrBlank()) {
            headers["Origin"] = origin
        }
        if (isInstagram && !lower.contains("cdninstagram")) {
            headers["X-IG-App-ID"] = "936619743392459"
        }
        return headers
    }

    fun buildDownloadHeaders(
        streamUrl: String,
        streamHeaders: Map<String, String>,
    ): Map<String, String> {
        if (streamHeaders.containsKey("User-Agent")) {
            val out = LinkedHashMap<String, String>(streamHeaders)
            out.putIfAbsent("Accept", "*/*")
            out.putIfAbsent("Accept-Language", "en-US,en;q=0.9")
            out.putIfAbsent("Referer", refererForUrl(streamUrl))
            return out
        }
        return buildHeadersForUrl(streamUrl, profiles.first()) + streamHeaders
    }
}
