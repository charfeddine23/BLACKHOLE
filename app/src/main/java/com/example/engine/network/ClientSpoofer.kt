package com.example.engine.network

import java.net.URI

data class SpoofedClientProfile(
    val id: String,
    val userAgent: String,
    val extraHeaders: Map<String, String>,
)

object ClientSpoofer {

    val profiles: List<SpoofedClientProfile> = listOf(
        SpoofedClientProfile(
            id = "CHROME_DESKTOP",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
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
            userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.49 Mobile Safari/537.36",
            extraHeaders = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,video/*,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Sec-CH-UA" to "\"Chromium\";v=\"133\", \"Google Chrome\";v=\"133\"",
                "Sec-CH-UA-Mobile" to "?1",
                "Sec-CH-UA-Platform" to "\"Android\"",
                "X-Requested-With" to "com.android.chrome",
            ),
        ),
        SpoofedClientProfile(
            id = "SAFARI_IOS_HLS",
            userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Mobile/15E148 Safari/604.1",
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

    fun buildHeadersForUrl(
        targetUrl: String,
        profile: SpoofedClientProfile,
        refererOverride: String? = null,
    ): Map<String, String> {
        val origin = runCatching {
            val uri = URI(targetUrl)
            "${uri.scheme}://${uri.authority}"
        }.getOrNull()

        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = profile.userAgent
        headers.putAll(profile.extraHeaders)
        if (!refererOverride.isNullOrBlank()) {
            headers["Referer"] = refererOverride
        } else if (!origin.isNullOrBlank()) {
            headers["Referer"] = "$origin/"
        }
        if (!origin.isNullOrBlank()) {
            headers["Origin"] = origin
        }
        return headers
    }
}
