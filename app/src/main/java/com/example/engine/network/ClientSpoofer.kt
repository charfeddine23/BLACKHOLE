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
            id = "ANDROID_CHROME",
            userAgent = ANDROID_CHROME_USER_AGENT,
            extraHeaders = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,video/*,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Sec-CH-UA" to "\"Chromium\";v=\"131\", \"Google Chrome\";v=\"131\"",
                "Sec-CH-UA-Mobile" to "?1",
                "Sec-CH-UA-Platform" to "\"Android\"",
            ),
        ),
    )

    fun nextRotatedUserAgent(@Suppress("UNUSED_PARAMETER") targetUrl: String): String {
        return ANDROID_CHROME_USER_AGENT
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
        headers["User-Agent"] = profile.userAgent
        headers.putAll(profile.extraHeaders)
        headers["Referer"] = resolvedReferer
        if (!origin.isNullOrBlank()) {
            headers["Origin"] = origin
        }
        return headers
    }

    fun buildDownloadHeaders(
        streamUrl: String,
        streamHeaders: Map<String, String>,
    ): Map<String, String> {
        val sanitizedStreamHeaders = streamHeaders.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        val ua = sanitizedStreamHeaders["User-Agent"].orEmpty()
        if (ua.isNotBlank()) {
            val out = LinkedHashMap<String, String>(sanitizedStreamHeaders)
            out.putIfAbsent("Accept", "*/*")
            out.putIfAbsent("Accept-Language", "en-US,en;q=0.9")
            out.putIfAbsent("Referer", refererForUrl(streamUrl))
            return out
        }
        return buildHeadersForUrl(streamUrl, profiles.first()) + sanitizedStreamHeaders
    }
}
