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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

data class SniffedMediaResult(
    val capturedUrls: List<String>,
    val audioCandidateUrls: List<String> = emptyList(),
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
        timeoutMs: Long = 13_000L,
    ): SniffedMediaResult = withContext(Dispatchers.Main) {
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<SniffedMediaResult> { continuation ->
                val capturedVideos = CopyOnWriteArrayList<String>()
                val capturedAudios = CopyOnWriteArrayList<String>()
                var resolvedTitle: String? = null
                var resumed = false

                val webView = WebView(context.applicationContext)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                // Inject stored cookies from CookieVaultManager into WebView before loading
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
                                capturedUrls = capturedVideos.distinct(),
                                audioCandidateUrls = capturedAudios.distinct(),
                                pageTitle = resolvedTitle,
                                loginRequiredDetected = loginGate && capturedVideos.isEmpty(),
                            ),
                        )
                    }
                }

                val isTikTok = targetUrl.contains("tiktok.com", ignoreCase = true)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = if (isTikTok) {
                        ClientSpoofer.TIKTOK_IOS_USER_AGENT
                    } else {
                        ClientSpoofer.ANDROID_CHROME_USER_AGENT
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString().orEmpty()
                        if (isCandidateMediaNetworkUrl(reqUrl)) {
                            val lower = reqUrl.lowercase()
                            // Never capture chunked MSE bytestart segments as-is without stripping range params
                            val cleaned = stripRangeQueryParams(reqUrl)
                            if (lower.contains("mime=audio") || lower.contains("audio_mp4")) {
                                capturedAudios.add(cleaned)
                            } else {
                                capturedVideos.add(cleaned)
                            }
                            if (cleaned.contains(".m3u8", ignoreCase = true) ||
                                (!lower.contains("fbcdn.net") && cleaned.contains(".mp4", ignoreCase = true))
                            ) {
                                view?.postDelayed({ finish(loginGate = false) }, 2_000L)
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

                        // Probe hydrated DOM <video>, YouTube playerData, and Meta/IG JSON blobs inside live V8
                        view?.evaluateJavascript(
                            """
                            (function() {
                              var out = { videos: [], audios: [], title: document.title || '' };
                              try {
                                document.querySelectorAll('video, source').forEach(function(el) {
                                  if (el.currentSrc && el.currentSrc.startsWith('http')) out.videos.push(el.currentSrc);
                                  if (el.src && el.src.startsWith('http')) out.videos.push(el.src);
                                });
                                if (window.ytInitialPlayerResponse && window.ytInitialPlayerResponse.streamingData) {
                                  var sd = window.ytInitialPlayerResponse.streamingData;
                                  if (sd.hlsManifestUrl) out.videos.push(sd.hlsManifestUrl);
                                  (sd.formats || []).forEach(function(f) { if (f.url) out.videos.push(f.url); });
                                  (sd.adaptiveFormats || []).forEach(function(f) {
                                    if (!f.url) return;
                                    var m = (f.mimeType || '').toLowerCase();
                                    if (m.indexOf('audio/mp4') === 0) out.audios.push(f.url);
                                    else if (m.indexOf('video/mp4') === 0) out.videos.push(f.url);
                                  });
                                }
                                var scripts = document.querySelectorAll('script[type="application/json"], script:not([src])');
                                var urlRegex = /"((?:browser_native_hd_url|playable_url_quality_hd|hd_src|video_url|playable_url|contentUrl))":"(https?:\\\/\\\/[^"]+)"/g;
                                for (var i = 0; i < Math.min(scripts.length, 40); i++) {
                                  var txt = scripts[i].textContent || '';
                                  if (txt.length > 0 && txt.length < 350000) {
                                    var match;
                                    while ((match = urlRegex.exec(txt)) !== null) {
                                      var clean = match[2].replace(/\\\//g, '/').replace(/\\u0026/g, '&');
                                      if (clean.indexOf('http') === 0) out.videos.push(clean);
                                    }
                                  }
                                }
                              } catch (e) {}
                              return JSON.stringify(out);
                            })();
                            """.trimIndent(),
                        ) { jsonRaw ->
                            runCatching {
                                val cleaned = decodeEvaluateJsString(jsonRaw)
                                if (cleaned.startsWith("{")) {
                                    val obj = JSONObject(cleaned)
                                    val domTitle = obj.optString("title").takeIf { it.isNotBlank() }
                                    if (domTitle != null) resolvedTitle = domTitle
                                    val vArr = obj.optJSONArray("videos") ?: JSONArray()
                                    for (i in 0 until vArr.length()) {
                                        val candidate = vArr.optString(i)
                                        if (candidate.startsWith("http") && !candidate.startsWith("blob:")) {
                                            capturedVideos.add(stripRangeQueryParams(candidate))
                                        }
                                    }
                                    val aArr = obj.optJSONArray("audios") ?: JSONArray()
                                    for (i in 0 until aArr.length()) {
                                        val candidate = aArr.optString(i)
                                        if (candidate.startsWith("http") && !candidate.startsWith("blob:")) {
                                            capturedAudios.add(stripRangeQueryParams(candidate))
                                        }
                                    }
                                }
                            }
                            view.postDelayed({ finish(loginGate = looksLikeLogin) }, 1_800L)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    runCatching {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }

                if (isTikTok) {
                    webView.loadUrl(targetUrl, mapOf("Referer" to "https://www.tiktok.com/"))
                } else {
                    webView.loadUrl(targetUrl)
                }
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

    private fun stripRangeQueryParams(rawUrl: String): String {
        val httpUrl = rawUrl.toHttpUrlOrNull() ?: return rawUrl
        val builder = httpUrl.newBuilder()
        listOf("bytestart", "byteend", "range", "rbuf", "rn").forEach { param ->
            builder.removeAllQueryParameters(param)
        }
        return builder.build().toString()
    }

    private fun decodeEvaluateJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            // JSONArray("[<raw>]") cleanly unescapes Chrome WebView's evaluateJavascript JSON string
            JSONArray("[$raw]").optString(0)
        }.getOrDefault(raw.removeSurrounding("\"").replace("\\\"", "\""))
    }

    private fun isCandidateMediaNetworkUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (lower.endsWith(".js") || lower.endsWith(".css") ||
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".svg")
        ) {
            return false
        }
        // Ignore tiny Meta/YouTube DASH MSE segment pings that include explicit partial byte slices unless audio/video
        val pathOnly = lower.substringBefore("?")
        return pathOnly.endsWith(".mp4") ||
            pathOnly.endsWith(".m3u8") ||
            pathOnly.endsWith(".webm") ||
            pathOnly.endsWith(".mov") ||
            lower.contains("mime=video") ||
            lower.contains("mime=audio") ||
            lower.contains("mime_type=video") ||
            lower.contains("video_mp4") ||
            lower.contains("/master.m3u8") ||
            lower.contains("/playlist.m3u8")
    }
}
