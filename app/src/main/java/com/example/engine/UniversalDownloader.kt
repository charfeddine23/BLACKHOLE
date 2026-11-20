package com.example.engine

import android.content.Context
import com.example.engine.cookies.CookieVaultManager
import com.example.engine.extractor.VideoStreamExtractor
import com.example.engine.extractor.WebViewStreamSniffer
import com.example.engine.model.ExtractionResult
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class UniversalExtractionOutcome(
    val canonicalUrl: String,
    val platform: PlatformBadge,
    val result: ExtractionResult,
)

/**
 * Universal Smart Router & Multi-Platform Extraction Engine for Blackhole.
 *
 * Resolves shortened links and delegates extraction across YouTube, TikTok, Instagram,
 * Facebook, X/Twitter, Vimeo, Reddit, and direct progressive/HLS media endpoints while
 * sharing Encrypted Cookie Vault sessions.
 */
class UniversalDownloader(
    private val context: Context,
    private val cookieVault: CookieVaultManager = CookieVaultManager.getInstance(context),
    private val httpClient: OkHttpClient = VideoStreamExtractor.createWithVault(cookieVault),
    private val webViewSniffer: WebViewStreamSniffer = WebViewStreamSniffer(context, cookieVault),
    private val streamExtractor: VideoStreamExtractor = VideoStreamExtractor(httpClient, webViewSniffer, cookieVault),
) {

    /**
     * Resolves shortened URLs (vt.tiktok.com, vm.tiktok.com, fb.watch, instagr.am, t.co, etc.)
     * and executes multi-strategy extraction with CookieVault session injection.
     */
    suspend fun routeAndExtract(rawUrl: String): UniversalExtractionOutcome = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        val canonicalUrl = resolveShortUrlIfNeeded(trimmed)
        val platform = PlatformBadge.fromUrl(canonicalUrl)
        val result = streamExtractor.extractHighestQuality(canonicalUrl)
        UniversalExtractionOutcome(
            canonicalUrl = canonicalUrl,
            platform = platform,
            result = result,
        )
    }

    private fun resolveShortUrlIfNeeded(url: String): String {
        val lower = url.lowercase()
        val needsResolve = lower.contains("vt.tiktok.com") ||
            lower.contains("vm.tiktok.com") ||
            lower.contains("tiktok.com/t/") ||
            lower.contains("fb.watch") ||
            lower.contains("facebook.com/share") ||
            lower.contains("fb.com/share") ||
            lower.contains("l.facebook.com") ||
            lower.contains("instagram.com/share") ||
            lower.contains("instagr.am") ||
            lower.contains("t.co/") ||
            lower.contains("bit.ly/") ||
            lower.contains("tinyurl.com/")

        if (!needsResolve) return url

        val profile = ClientSpoofer.profiles.first()
        val headers = ClientSpoofer.buildHeadersForUrl(url, profile)
        val reqBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        return runCatching {
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val resolved = response.request.url.toString()
                if (resolved.startsWith("http") && !resolved.contains("/login", ignoreCase = true)) {
                    resolved
                } else {
                    url
                }
            }
        }.getOrDefault(url)
    }
}
