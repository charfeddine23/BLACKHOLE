package com.example.vault.browser

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.flow.StateFlow

/**
 * Immutable, fresh capture snapshot produced at the exact instant the user triggers
 * Pre-Download Inspection (never recycled across navigations or video switches).
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
        val activeSrc = activeVideo?.currentSrc?.ifBlank { activeVideo.src } ?: "<none>"
        val playbackTime = activeVideo?.let {
            String.format("%.1fs / %.1fs (paused=%s)", it.currentTimeSeconds, it.durationSeconds, it.paused)
        } ?: "no-video-element"
        return buildString {
            appendLine("pageUrl=$pageUrl")
            appendLine("activeSrc=$activeSrc")
            appendLine("playback=$playbackTime")
            appendLine("domCandidates=${domCandidateUrls.size}, cookiesPresent=${cookies.isNotBlank()}")
        }.trim()
    }
}

/**
 * Session-aware WebView Inspector that guarantees a fresh playback snapshot on trigger,
 * collects active <video> telemetry across main document + accessible frames, captures live
 * Cookie + Referer headers, resets stale captures on navigation, and emits Logcat verification.
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
        Log.d(TAG, "Reset playback snapshot on navigation (tab=$tabId, url=${nextUrl.orEmpty()})")
    }

    suspend fun captureFreshPlaybackSnapshot(
        webView: WebView?,
        currentPageUrl: String,
    ): VaultCaptureSnapshot {
        // Clear any stale active telemetry before probing the live DOM
        monitor.clearActiveTelemetry()

        val live = monitor.captureFreshSnapshot(webView, currentPageUrl)
        val resolvedPageUrl = live.pageUrl.ifBlank { currentPageUrl }.trim()
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
        val pageCookie = runCatching { cookieManager?.getCookie(resolvedPageUrl) }.getOrNull().orEmpty()
        val activeSrcCookie = live.activeVideo?.bestDirectHttpUrl?.let { mediaUrl ->
            runCatching { cookieManager?.getCookie(mediaUrl) }.getOrNull()
        }.orEmpty()
        val combinedCookies = when {
            pageCookie.isNotBlank() && activeSrcCookie.isNotBlank() && pageCookie != activeSrcCookie ->
                "$pageCookie; $activeSrcCookie"
            pageCookie.isNotBlank() -> pageCookie
            else -> activeSrcCookie
        }

        val snapshot = VaultCaptureSnapshot(
            pageUrl = resolvedPageUrl,
            pageTitle = live.pageTitle,
            pagePosterUrl = live.pagePosterUrl,
            activeVideo = live.activeVideo,
            allVideos = live.allVideos,
            domCandidateUrls = live.domCandidateUrls,
            cookies = combinedCookies,
            referer = resolvedPageUrl,
            capturedAtMs = System.currentTimeMillis(),
        )
        Log.d(TAG, "Fresh Pre-Download Snapshot:\n${snapshot.logSummary()}")
        return snapshot
    }

    companion object {
        const val TAG = "BlackholeVaultInspector"
    }
}
