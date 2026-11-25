package com.example.vault.browser

import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.flow.StateFlow

/**
 * Immutable, privacy-safe snapshot produced strictly on explicit user tap.
 * Never persists stale URLs across navigations and NEVER reads, extracts, or forwards Cookies.
 */
data class VaultCaptureSnapshot(
    val pageUrl: String = "",
    val pageTitle: String? = null,
    val pagePosterUrl: String = "",
    val activeVideo: VideoElementTelemetry? = null,
    val allVideos: List<VideoElementTelemetry> = emptyList(),
    val domCandidateUrls: List<String> = emptyList(),
    val cookies: String = "",
    val referer: String = "",
    val capturedAtMs: Long = System.currentTimeMillis(),
) {
    fun toWebSnapshot(): VaultWebSnapshot = VaultWebSnapshot(
        pageUrl = pageUrl,
        pageTitle = pageTitle,
        pagePosterUrl = pagePosterUrl,
        activeVideo = activeVideo,
        allVideos = allVideos,
        domCandidateUrls = domCandidateUrls,
        capturedAtMs = capturedAtMs,
    )

    fun logSummary(): String {
        val playbackTime = activeVideo?.let {
            String.format("%.1fs / %.1fs (paused=%s)", it.currentTimeSeconds, it.durationSeconds, it.paused)
        } ?: "no-video-element"
        return buildString {
            appendLine("sanitizedPageUrl=$pageUrl")
            appendLine("refererOrigin=$referer")
            appendLine("playback=$playbackTime")
            appendLine("domCandidates=${domCandidateUrls.size}, cookiesForwarded=false")
        }.trim()
    }
}

/**
 * Privacy-first, session-isolated WebView inspector:
 * - Zero cookie extraction or forwarding
 * - Sanitizes tracking/session parameters via UrlSanitizer
 * - Only inspects the current page upon explicit user tap
 * - Clears telemetry cleanly on navigation reset
 */
class VaultWebViewInspector(
    val monitor: VaultWebViewMonitor = VaultWebViewMonitor(),
) {
    val activeVideoTelemetry: StateFlow<VideoElementTelemetry?> = monitor.activeVideoTelemetry
    val trackedVideos: StateFlow<List<VideoElementTelemetry>> = monitor.trackedVideos

    fun attachTo(webView: WebView) {
        monitor.attachTo(webView)
    }

    fun detachFrom(webView: WebView) {
        monitor.detachFrom(webView)
    }

    fun resetOnNavigation(tabId: String, nextUrl: String?) {
        monitor.resetForNavigation(tabId)
        val cleanNext = UrlSanitizer.sanitizePublicUrl(nextUrl.orEmpty()).sanitizedUrl
        Log.d(TAG, "Reset playback snapshot on navigation (tab=$tabId, url=$cleanNext)")
    }

    suspend fun captureFreshPlaybackSnapshot(
        webView: WebView?,
        currentPageUrl: String,
    ): VaultCaptureSnapshot {
        monitor.clearActiveTelemetry()

        val live = monitor.captureFreshSnapshot(webView, currentPageUrl)
        val rawPageUrl = live.pageUrl.ifBlank { currentPageUrl }.trim()
        val sanitized = UrlSanitizer.sanitizePublicUrl(rawPageUrl)
        val safePageUrl = if (sanitized.isValidPublicHttpUrl) sanitized.sanitizedUrl else rawPageUrl

        val sanitizedDomUrls = live.domCandidateUrls
            .mapNotNull { candidate ->
                UrlSanitizer.sanitizePublicUrl(candidate).takeIf { it.isValidPublicHttpUrl }?.sanitizedUrl
            }
            .distinct()

        // Privacy rule: NEVER extract or forward WebView cookies!
        val snapshot = VaultCaptureSnapshot(
            pageUrl = safePageUrl,
            pageTitle = live.pageTitle,
            pagePosterUrl = live.pagePosterUrl,
            activeVideo = live.activeVideo,
            allVideos = live.allVideos,
            domCandidateUrls = sanitizedDomUrls,
            cookies = "",
            referer = sanitized.cleanOrigin,
            capturedAtMs = System.currentTimeMillis(),
        )
        Log.d(TAG, "User-Initiated Sanitized Snapshot:\n${snapshot.logSummary()}")
        return snapshot
    }

    companion object {
        const val TAG = "BlackholeVaultInspector"
    }
}
