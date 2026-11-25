package com.example.vault.browser

import android.content.Context
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Privacy-first WebView configuration enforcing consistent User-Agent identity,
 * form-data suppression, third-party cookie isolation, and ephemeral caching.
 */
object SafeWebViewConfig {

    /**
     * Consistent, standard Android Chrome Mobile User-Agent (zero randomized spoofing).
     */
    const val STANDARD_ANDROID_CHROME_USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    fun resolveConsistentUserAgent(context: Context): String {
        val systemUa = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull().orEmpty()
        return if (systemUa.contains("Chrome/", ignoreCase = true) && systemUa.contains("Mobile", ignoreCase = true)) {
            systemUa.replace("; wv)", ")")
        } else {
            STANDARD_ANDROID_CHROME_USER_AGENT
        }
    }

    fun configure(
        webView: WebView,
        isEphemeralSession: Boolean = true,
    ) {
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
        runCatching {
            cookieManager?.setAcceptCookie(!isEphemeralSession)
            cookieManager?.setAcceptThirdPartyCookies(webView, false)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            domStorageEnabled = !isEphemeralSession
            databaseEnabled = !isEphemeralSession
            @Suppress("DEPRECATION")
            saveFormData = false
            cacheMode = if (isEphemeralSession) {
                WebSettings.LOAD_NO_CACHE
            } else {
                WebSettings.LOAD_DEFAULT
            }
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = resolveConsistentUserAgent(webView.context)
        }
    }
}

/**
 * Manages ephemeral, session-isolated WebView state.
 * Guarantees zero cookie forwarding and deterministic cleanup on toggle or lifecycle disposal.
 */
class EphemeralSessionManager(
    initialEphemeralMode: Boolean = true,
) {
    private val _isEphemeralMode = MutableStateFlow(initialEphemeralMode)
    val isEphemeralMode: StateFlow<Boolean> = _isEphemeralMode.asStateFlow()

    private val _lastSessionClearedAtMs = MutableStateFlow(0L)
    val lastSessionClearedAtMs: StateFlow<Long> = _lastSessionClearedAtMs.asStateFlow()

    fun setEphemeralMode(
        enabled: Boolean,
        activeWebView: WebView? = null,
    ) {
        _isEphemeralMode.value = enabled
        if (enabled) {
            clearSessionData(activeWebView)
        } else if (activeWebView != null) {
            SafeWebViewConfig.configure(activeWebView, isEphemeralSession = false)
        }
    }

    fun clearSessionData(webView: WebView? = null) {
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
        runCatching {
            cookieManager?.removeSessionCookies(null)
            cookieManager?.removeAllCookies(null)
            cookieManager?.flush()
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
        webView?.let { wv ->
            runCatching {
                wv.clearCache(true)
                wv.clearHistory()
                wv.clearFormData()
                wv.clearSslPreferences()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    SafeWebViewConfig.configure(wv, isEphemeralSession = _isEphemeralMode.value)
                }
            }
        }
        _lastSessionClearedAtMs.value = System.currentTimeMillis()
    }

    fun disposeWebView(webView: WebView?) {
        if (webView == null) return
        if (_isEphemeralMode.value) {
            clearSessionData(webView)
        }
        runCatching {
            webView.stopLoading()
            webView.onPause()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
}
