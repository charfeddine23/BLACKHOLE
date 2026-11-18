package com.example.engine

import android.content.Context
import com.example.engine.cookies.CookieVaultManager
import com.example.engine.extractor.VideoStreamExtractor
import com.example.engine.extractor.WebViewStreamSniffer
import com.example.engine.model.ExtractionResult
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
import com.pulse.blackhole.data.vault.SessionVaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class UniversalExtractionOutcome(
    val canonicalUrl: String,
    val platform: PlatformBadge,
    val routerCommand: List<String>,
    val result: ExtractionResult,
)

/**
 * Universal Smart Router & Multi-Platform Extraction Engine for Blackhole.
 *
 * Dynamically routes URLs across YouTube, TikTok, Instagram, Facebook, X/Twitter,
 * Vimeo, Reddit, and direct progressive/HLS media endpoints while enforcing
 * anti-bot header rotation, short-link resolution, and Encrypted Session Vault
 * Netscape cookie injection.
 */
class UniversalDownloader(
    private val context: Context,
    private val cookieVault: CookieVaultManager = CookieVaultManager.getInstance(context),
    private val httpClient: OkHttpClient = VideoStreamExtractor.createWithVault(cookieVault),
    private val webViewSniffer: WebViewStreamSniffer = WebViewStreamSniffer(context, cookieVault),
    private val streamExtractor: VideoStreamExtractor = VideoStreamExtractor(httpClient, webViewSniffer),
) {

    val sessionVault: SessionVaultManager
        get() = cookieVault.sessionVault

    /**
     * Smart Extractor Router that dynamically builds extraction flags and
     * platform-specific client rules for any video URL.
     */
    fun getUniversalExtractionCommand(
        targetUrl: String,
        sessionVault: SessionVaultManager = this.sessionVault,
    ): List<String> {
        val cleanUrl = targetUrl.trim()
        val platform = PlatformBadge.fromUrl(cleanUrl)
        val rotatedUa = ClientSpoofer.nextRotatedUserAgent(cleanUrl)

        val command = mutableListOf("yt-dlp")

        // General Anti-Bot & Geo/TLS flags for all URLs
        command.add("--user-agent")
        command.add(rotatedUa)
        command.add("--no-check-certificate")
        command.add("--geo-bypass")

        // Encrypted Session Vault Cookie Injection (Netscape cookies.txt)
        if (sessionVault.hasActiveSession()) {
            val cookieFile = sessionVault.writeTempNetscapeCookiesFile(context.cacheDir)
            if (cookieFile != null && cookieFile.exists()) {
                command.add("--cookies")
                command.add(cookieFile.absolutePath)
            }
        }

        // Platform-Specific Extraction Rules
        when (platform) {
            PlatformBadge.YOUTUBE -> {
                command.add("--extractor-args")
                command.add("youtube:player_client=ios,web")
                command.add("-f")
                command.add("bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4] / bv*+ba/b")
            }

            PlatformBadge.TIKTOK -> {
                command.add("--referer")
                command.add("https://www.tiktok.com/")
                command.add("-f")
                command.add("best")
            }

            PlatformBadge.INSTAGRAM -> {
                command.add("--referer")
                command.add("https://www.instagram.com/")
                command.add("--add-header")
                command.add("X-IG-App-ID:936619743392459")
                command.add("-f")
                command.add("best")
            }

            PlatformBadge.FACEBOOK -> {
                command.add("--referer")
                command.add("https://www.facebook.com/")
                command.add("-f")
                command.add("best")
            }

            else -> {
                command.add("--referer")
                command.add(ClientSpoofer.refererForUrl(cleanUrl))
                command.add("-f")
                command.add("best")
            }
        }

        command.add(cleanUrl)
        return command
    }

    /**
     * Strict Bulletproof Extraction Command builder requiring a valid Netscape cookies file path
     * from SessionVaultManager to bypass login-gated Instagram and YouTube restrictions.
     */
    fun getBulletproofExtractionCommand(
        targetUrl: String,
        cookieFilePath: String? = sessionVault.writeTempNetscapeCookiesFile(context.cacheDir)?.absolutePath,
    ): List<String> {
        val command = mutableListOf("yt-dlp")

        // 1. Mandatory Netscape cookies injection when session is present
        if (!cookieFilePath.isNullOrBlank()) {
            command.add("--cookies")
            command.add(cookieFilePath)
        } else {
            throw IllegalStateException("Session Vault is empty! Please import cookies.txt to bypass authentication.")
        }

        // 2. Anti-bot spoofing headers & bypass flags
        command.add("--user-agent")
        command.add(ClientSpoofer.TIKTOK_IOS_USER_AGENT)
        command.add("--no-check-certificate")
        command.add("--geo-bypass")

        // 3. Smart platform-specific routing
        val lower = targetUrl.lowercase()
        when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> {
                command.add("--extractor-args")
                command.add("youtube:player_client=ios,web")
                command.add("-f")
                command.add("bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4] / bv*+ba/b")
            }
            lower.contains("instagram.com") || lower.contains("instagr.am") -> {
                command.add("--referer")
                command.add("https://www.instagram.com/")
                command.add("-f")
                command.add("best")
            }
            lower.contains("tiktok.com") -> {
                command.add("--referer")
                command.add("https://www.tiktok.com/")
                command.add("-f")
                command.add("best")
            }
            else -> {
                command.add("-f")
                command.add("best")
            }
        }

        command.add(targetUrl)
        return command
    }

    /**
     * Resolves shortened URLs (vt.tiktok.com, vm.tiktok.com, fb.watch, instagr.am, t.co, etc.)
     * and executes multi-strategy extraction with CookieVault session injection.
     */
    suspend fun routeAndExtract(rawUrl: String): UniversalExtractionOutcome = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        val canonicalUrl = resolveShortUrlIfNeeded(trimmed)
        val platform = PlatformBadge.fromUrl(canonicalUrl)
        val commandPlan = runCatching {
            getBulletproofExtractionCommand(canonicalUrl)
        }.getOrElse {
            getUniversalExtractionCommand(canonicalUrl, sessionVault)
        }

        val result = streamExtractor.extractHighestQuality(canonicalUrl)
        UniversalExtractionOutcome(
            canonicalUrl = canonicalUrl,
            platform = platform,
            routerCommand = commandPlan,
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
