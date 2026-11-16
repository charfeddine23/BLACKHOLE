package com.example.engine.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.engine.cookies.CookieVaultManager
import com.example.engine.network.ClientSpoofer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

data class SniffedMediaResult(
    val capturedUrls: List<String>,
    val pageTitle: String?,
    val loginRequiredDetected: Boolean,
)

class WebViewStreamSniffer(
    private val context: Context,
    private val cookieVault: CookieVaultManager,
) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniffPageMedia(
        targetUrl: String,
        timeoutMs: Long = 10_000L,
    ): SniffedMediaResult = withContext(Dispatchers.Main) {
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<SniffedMediaResult> { continuation ->
                val captured = CopyOnWriteArrayList<String>()
                var resolvedTitle: String? = null
                var resumed = false

                val webView = WebView(context.applicationContext)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                // Inject any stored cookies from CookieVaultManager into WebView before loading
                val storedHeader = cookieVault.getCookieHeaderForUrl(targetUrl)
                if (storedHeader.isNotBlank()) {
                    storedHeader.split(";").forEach { cookiePair ->
                        cookieManager.setCookie(targetUrl, cookiePair.trim())
                    }
                    cookieManager.flush()
                }

                fun finish(loginGate: Boolean) {
                    if (resumed) return
                    resumed = true
                    runCatching {
                        webView.stopLoading()
                        webView.destroy()
                    }
                    if (continuation.isActive) {
                        continuation.resume(
                            SniffedMediaResult(
                                capturedUrls = captured.distinct(),
                                pageTitle = resolvedTitle,
                                loginRequiredDetected = loginGate && captured.isEmpty(),
                            ),
                        )
                    }
                }

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = ClientSpoofer.profiles.first().userAgent
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString().orEmpty()
                        if (isCandidateMediaNetworkUrl(reqUrl)) {
                            captured.add(reqUrl)
                            // Prefer completing quickly if we captured an explicit mp4 or m3u8
                            if (reqUrl.contains(".mp4", ignoreCase = true) ||
                                reqUrl.contains(".m3u8", ignoreCase = true)
                            ) {
                                view?.postDelayed({ finish(loginGate = false) }, 900L)
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        resolvedTitle = view?.title?.takeIf { it.isNotBlank() }
                        val currentUrl = url.orEmpty().lowercase()
                        val looksLikeLogin = currentUrl.contains("/login") ||
                            currentUrl.contains("/signin") ||
                            currentUrl.contains("accounts/login")

                        // Probe DOM video elements after hydration
                        view?.evaluateJavascript(
                            """
                            (function() {
                              var urls = [];
                              document.querySelectorAll('video, source').forEach(function(el) {
                                if (el.currentSrc && el.currentSrc.startsWith('http')) urls.push(el.currentSrc);
                                if (el.src && el.src.startsWith('http')) urls.push(el.src);
                              });
                              return JSON.stringify(urls);
                            })();
                            """.trimIndent(),
                        ) { jsonRaw ->
                            runCatching {
                                val cleaned = jsonRaw
                                    .removeSurrounding("\"")
                                    .replace("\\\"", "\"")
                                if (cleaned.startsWith("[")) {
                                    val arr = org.json.JSONArray(cleaned)
                                    for (i in 0 until arr.length()) {
                                        val found = arr.optString(i)
                                        if (found.startsWith("http") && !found.startsWith("blob:")) {
                                            captured.add(found)
                                        }
                                    }
                                }
                            }
                            view.postDelayed({ finish(loginGate = looksLikeLogin) }, 1400L)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    runCatching {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }

                webView.loadUrl(targetUrl)
            }
        }

        // Persist any cookies acquired during sniffing
        cookieVault.syncFromWebView(targetUrl)

        result ?: SniffedMediaResult(
            capturedUrls = emptyList(),
            pageTitle = null,
            loginRequiredDetected = false,
        )
    }

    private fun isCandidateMediaNetworkUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (lower.endsWith(".js") || lower.endsWith(".css") ||
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".svg")
        ) {
            return false
        }
        val pathOnly = lower.substringBefore("?")
        return pathOnly.endsWith(".mp4") ||
            pathOnly.endsWith(".m3u8") ||
            pathOnly.endsWith(".webm") ||
            pathOnly.endsWith(".mov") ||
            lower.contains("mime=video") ||
            lower.contains("videoplayback") ||
            lower.contains("/master.m3u8") ||
            lower.contains("/playlist.m3u8")
    }
}
