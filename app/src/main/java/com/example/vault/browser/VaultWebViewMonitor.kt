package com.example.vault.browser

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

enum class InterceptedMediaKind {
    VIDEO_DIRECT,
    HLS_M3U8,
    DASH_MPD,
    AUDIO_STREAM,
}

data class VideoElementTelemetry(
    val elementId: String = "",
    val currentSrc: String = "",
    val src: String = "",
    val sourceUrls: List<String> = emptyList(),
    val durationSeconds: Double = 0.0,
    val currentTimeSeconds: Double = 0.0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val paused: Boolean = true,
    val ended: Boolean = false,
    val muted: Boolean = false,
    val isAdvancing: Boolean = false,
    val isVisible: Boolean = false,
    val viewportArea: Double = 0.0,
    val lastPlayEventMs: Long = 0L,
    val lastAdvanceMs: Long = 0L,
    val lastUpdatedMs: Long = 0L,
    val isLikelyAd: Boolean = false,
    val score: Double = 0.0,
    val pageUrl: String = "",
    val pageTitle: String = "",
    val posterUrl: String = "",
    val titleHint: String = "",
) {
    val isBlobOrMediaSource: Boolean
        get() = currentSrc.startsWith("blob:", ignoreCase = true) ||
            src.startsWith("blob:", ignoreCase = true) ||
            currentSrc.startsWith("mediasource:", ignoreCase = true)

    val bestDirectHttpUrl: String?
        get() = (listOf(currentSrc, src) + sourceUrls)
            .firstOrNull { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

    val resolutionLabel: String
        get() = when {
            videoHeight >= 2160 -> "4K"
            videoHeight >= 1440 -> "1440p"
            videoHeight >= 1080 -> "1080p"
            videoHeight >= 720 -> "720p"
            videoHeight >= 480 -> "480p"
            videoHeight > 0 -> "${videoHeight}p"
            else -> ""
        }
}

data class InterceptedMediaRequest(
    val url: String,
    val pageUrl: String,
    val referer: String,
    val mimeHint: String,
    val mediaKind: InterceptedMediaKind,
    val capturedAtMs: Long,
    val isLikelyAd: Boolean,
)

data class VaultWebSnapshot(
    val pageUrl: String = "",
    val pageTitle: String? = null,
    val pagePosterUrl: String = "",
    val activeVideo: VideoElementTelemetry? = null,
    val allVideos: List<VideoElementTelemetry> = emptyList(),
    val domCandidateUrls: List<String> = emptyList(),
    val capturedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Deterministic WebView active-<video> telemetry & shouldInterceptRequest media correlator.
 * Tracks play, playing, timeupdate, and loadedmetadata across document, accessible same-origin
 * iframes, and shadow DOMs, ignoring muted/short ad clips while a main video is active.
 */
class VaultWebViewMonitor {

    private val _activeVideoTelemetry = MutableStateFlow<VideoElementTelemetry?>(null)
    val activeVideoTelemetry: StateFlow<VideoElementTelemetry?> = _activeVideoTelemetry.asStateFlow()

    private val _trackedVideos = MutableStateFlow<List<VideoElementTelemetry>>(emptyList())
    val trackedVideos: StateFlow<List<VideoElementTelemetry>> = _trackedVideos.asStateFlow()

    private val interceptedByTab = ConcurrentHashMap<String, CopyOnWriteArrayList<InterceptedMediaRequest>>()

    @SuppressLint("JavascriptInterface")
    fun attachTo(webView: WebView) {
        runCatching {
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
        }
        runCatching {
            webView.addJavascriptInterface(this, JS_BRIDGE_NAME)
        }
    }

    fun detachFrom(webView: WebView) {
        runCatching {
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun injectMonitor(webView: WebView?) {
        // Privacy & platform-compliance policy: never auto-inject background scripts on page load.
    }

    fun clearTab(tabId: String) {
        interceptedByTab.remove(tabId)
        _activeVideoTelemetry.value = null
        _trackedVideos.value = emptyList()
    }

    fun resetForNavigation(tabId: String) {
        interceptedByTab.remove(tabId)
        _activeVideoTelemetry.value = null
        _trackedVideos.value = emptyList()
        android.util.Log.d(VaultWebViewInspector.TAG, "Cleared stale intercepted & video telemetry for tab=$tabId")
    }

    fun clearActiveTelemetry() {
        _activeVideoTelemetry.value = null
        _trackedVideos.value = emptyList()
    }

    fun recordInterceptedRequest(
        tabId: String,
        request: WebResourceRequest?,
        currentPageUrl: String,
    ): InterceptedMediaRequest? {
        if (request == null) return null
        val url = request.url?.toString()?.trim().orEmpty()
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }

        val headers = request.requestHeaders.orEmpty()
        val acceptHeader = headers["Accept"] ?: headers["accept"] ?: ""
        val referer = headers["Referer"] ?: headers["referer"] ?: currentPageUrl
        val mediaKind = classifyMediaUrl(url, acceptHeader) ?: return null

        val isAd = isLikelyAdMediaUrl(url)
        val entry = InterceptedMediaRequest(
            url = url,
            pageUrl = currentPageUrl,
            referer = referer,
            mimeHint = acceptHeader,
            mediaKind = mediaKind,
            capturedAtMs = System.currentTimeMillis(),
            isLikelyAd = isAd,
        )

        val bucket = interceptedByTab.getOrPut(tabId) { CopyOnWriteArrayList() }
        bucket.removeAll { it.url == url }
        bucket.add(0, entry)
        while (bucket.size > MAX_INTERCEPTED_PER_TAB) {
            runCatching { bucket.removeAt(bucket.lastIndex) }
        }
        return entry
    }

    fun getInterceptedRequests(tabId: String): List<InterceptedMediaRequest> {
        return interceptedByTab[tabId]?.toList().orEmpty()
    }

    @JavascriptInterface
    fun onVideoTelemetry(payloadJson: String) {
        val parsed = parseTelemetryPayload(payloadJson) ?: return
        _trackedVideos.value = parsed.allVideos
        _activeVideoTelemetry.value = parsed.activeVideo
    }

    suspend fun captureFreshSnapshot(
        webView: WebView?,
        fallbackPageUrl: String,
    ): VaultWebSnapshot {
        if (webView == null) {
            return VaultWebSnapshot(
                pageUrl = fallbackPageUrl,
                activeVideo = _activeVideoTelemetry.value,
                allVideos = _trackedVideos.value,
            )
        }

        val liveSnapshot = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<VaultWebSnapshot?> { cont ->
                    runCatching {
                        webView.evaluateJavascript(USER_INITIATED_SNAPSHOT_SCRIPT) { raw ->
                            val parsed = parseEvaluateResult(raw, fallbackPageUrl)
                            if (parsed != null) {
                                _trackedVideos.value = parsed.allVideos
                                _activeVideoTelemetry.value = parsed.activeVideo
                            }
                            if (cont.isActive) cont.resume(parsed)
                        }
                    }.onFailure {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }

        return liveSnapshot ?: VaultWebSnapshot(
            pageUrl = fallbackPageUrl,
            activeVideo = _activeVideoTelemetry.value,
            allVideos = _trackedVideos.value,
        )
    }

    private fun parseEvaluateResult(raw: String?, fallbackPageUrl: String): VaultWebSnapshot? {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isBlank() || cleaned == "null") return null
        val unquoted = if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            runCatching { org.json.JSONTokener(cleaned).nextValue()?.toString() }.getOrNull() ?: cleaned
        } else {
            cleaned
        }
        return parseTelemetryPayload(unquoted, fallbackPageUrl)
    }

    internal fun parseTelemetryPayload(
        jsonString: String,
        fallbackPageUrl: String = "",
    ): VaultWebSnapshot? = runCatching {
        val obj = JSONObject(jsonString)
        val pageUrl = obj.optString("pageUrl").ifBlank { fallbackPageUrl }
        val pageTitle = obj.optString("pageTitle").takeIf { it.isNotBlank() }
        val pagePosterUrl = obj.optString("pagePosterUrl").trim()
        val videosArr = obj.optJSONArray("videos") ?: JSONArray()
        val parsedVideos = buildList {
            for (i in 0 until videosArr.length()) {
                val item = videosArr.optJSONObject(i) ?: continue
                val sourcesArr = item.optJSONArray("sourceUrls")
                val sourceUrls = buildList {
                    if (sourcesArr != null) {
                        for (j in 0 until sourcesArr.length()) {
                            val s = sourcesArr.optString(j).trim()
                            if (s.isNotBlank()) add(s)
                        }
                    }
                }
                add(
                    VideoElementTelemetry(
                        elementId = item.optString("elementId"),
                        currentSrc = item.optString("currentSrc"),
                        src = item.optString("src"),
                        sourceUrls = sourceUrls,
                        durationSeconds = item.optDouble("duration", 0.0).takeIf { !it.isNaN() && !it.isInfinite() } ?: 0.0,
                        currentTimeSeconds = item.optDouble("currentTime", 0.0).takeIf { !it.isNaN() && !it.isInfinite() } ?: 0.0,
                        videoWidth = item.optInt("videoWidth", 0),
                        videoHeight = item.optInt("videoHeight", 0),
                        paused = item.optBoolean("paused", true),
                        ended = item.optBoolean("ended", false),
                        muted = item.optBoolean("muted", false),
                        isAdvancing = item.optBoolean("isAdvancing", false),
                        isVisible = item.optBoolean("isVisible", false),
                        viewportArea = item.optDouble("viewportArea", 0.0),
                        lastPlayEventMs = item.optLong("lastPlayEventMs", 0L),
                        lastAdvanceMs = item.optLong("lastAdvanceMs", 0L),
                        lastUpdatedMs = item.optLong("lastUpdatedMs", System.currentTimeMillis()),
                        isLikelyAd = item.optBoolean("isLikelyAd", false),
                        score = item.optDouble("score", 0.0),
                        pageUrl = pageUrl,
                        pageTitle = pageTitle.orEmpty(),
                        posterUrl = item.optString("posterUrl").trim(),
                        titleHint = item.optString("titleHint").trim(),
                    ),
                )
            }
        }

        val domUrlsArr = obj.optJSONArray("domUrls") ?: JSONArray()
        val domUrls = buildList {
            for (i in 0 until domUrlsArr.length()) {
                val u = domUrlsArr.optString(i).trim()
                if (u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)) {
                    add(u)
                }
            }
        }

        val activeIndex = obj.optInt("activeIndex", -1)
        val active = parsedVideos.getOrNull(activeIndex)
            ?: parsedVideos.maxByOrNull { it.score }

        VaultWebSnapshot(
            pageUrl = pageUrl,
            pageTitle = pageTitle,
            pagePosterUrl = pagePosterUrl,
            activeVideo = active,
            allVideos = parsedVideos,
            domCandidateUrls = domUrls,
            capturedAtMs = System.currentTimeMillis(),
        )
    }.getOrNull()

    companion object {
        const val JS_BRIDGE_NAME = "BlackholeVaultMonitor"
        private const val MAX_INTERCEPTED_PER_TAB = 64
        private const val SNAPSHOT_TIMEOUT_MS = 750L

        internal fun classifyMediaUrl(url: String, acceptOrMimeHint: String = ""): InterceptedMediaKind? {
            val lower = url.lowercase()
            val pathOnly = lower.substringBefore("?").substringBefore("#")

            // Skip individual HLS/DASH segments & non-media assets so we capture manifests/full videos cleanly
            if (pathOnly.endsWith(".ts") ||
                pathOnly.endsWith(".m4s") ||
                pathOnly.endsWith(".vtt") ||
                pathOnly.endsWith(".srt") ||
                pathOnly.endsWith(".jpg") ||
                pathOnly.endsWith(".jpeg") ||
                pathOnly.endsWith(".png") ||
                pathOnly.endsWith(".webp") ||
                pathOnly.endsWith(".gif") ||
                pathOnly.endsWith(".css") ||
                pathOnly.endsWith(".js") ||
                pathOnly.endsWith(".woff2")
            ) {
                return null
            }

            return when {
                pathOnly.endsWith(".m3u8") || lower.contains(".m3u8?") || lower.contains("format=m3u8") ->
                    InterceptedMediaKind.HLS_M3U8
                pathOnly.endsWith(".mpd") || lower.contains(".mpd?") || lower.contains("application/dash+xml") ->
                    InterceptedMediaKind.DASH_MPD
                pathOnly.endsWith(".m4a") ||
                    pathOnly.endsWith(".aac") ||
                    lower.contains("mime=audio") ||
                    lower.contains("mime_type=audio") ->
                    InterceptedMediaKind.AUDIO_STREAM
                pathOnly.endsWith(".mp4") ||
                    pathOnly.endsWith(".m4v") ||
                    pathOnly.endsWith(".webm") ||
                    pathOnly.endsWith(".mov") ||
                    lower.contains(".mp4?") ||
                    lower.contains("mime=video") ||
                    lower.contains("mime_type=video") ||
                    lower.contains("video/mp4") ||
                    lower.contains("videoplayback") ||
                    acceptOrMimeHint.startsWith("video/", ignoreCase = true) ->
                    InterceptedMediaKind.VIDEO_DIRECT
                else -> null
            }
        }

        internal fun isLikelyAdMediaUrl(url: String): Boolean {
            val lower = url.lowercase()
            val host = lower.substringAfter("://").substringBefore("/").substringBefore(":")
            if (AD_MEDIA_HOSTS.any { adHost -> host == adHost || host.endsWith(".$adHost") }) {
                return true
            }
            return AD_PATH_TOKENS.any { token -> lower.contains(token) }
        }

        private val AD_MEDIA_HOSTS = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "imasdk.googleapis.com",
            "2mdn.net",
            "adnxs.com",
            "rubiconproject.com",
            "pubmatic.com",
            "spotxchange.com",
            "springserve.com",
            "teads.tv",
            "taboola.com",
            "outbrain.com",
            "fwmrm.net",
            "innovid.com",
        )

        private val AD_PATH_TOKENS = listOf(
            "/vast",
            "vast.xml",
            "/vmap",
            "/preroll",
            "pre-roll",
            "/midroll",
            "/postroll",
            "/pagead/",
            "adserver",
            "adsystem",
            "ad_break",
            "doubleclick",
        )

        /**
         * Strictly one-shot, read-only script executed ONLY on explicit user tap.
         * Zero MutationObservers, zero persistent listeners, zero multi-frame scraping.
         */
        val USER_INITIATED_SNAPSHOT_SCRIPT: String = """
            (function() {
              try {
                var videos = document.querySelectorAll ? document.querySelectorAll('video') : [];
                var entries = [];
                var now = Date.now();
                for (var i = 0; i < videos.length && i < 8; i++) {
                  var v = videos[i];
                  var rect = v.getBoundingClientRect ? v.getBoundingClientRect() : { width: 0, height: 0, top: 0, bottom: 0 };
                  var area = Math.max(0, Number(rect.width || 0)) * Math.max(0, Number(rect.height || 0));
                  var isVisible = area > 400 && rect.bottom > 0 && rect.top < (window.innerHeight || 1000);
                  var dur = (v.duration && isFinite(v.duration)) ? Number(v.duration) : 0;
                  var curTime = Number(v.currentTime || 0);
                  var vw = Number(v.videoWidth || 0);
                  var vh = Number(v.videoHeight || 0);
                  var poster = v.poster || v.getAttribute('poster') || '';
                  var titleHint = (v.getAttribute('title') || v.getAttribute('aria-label') || '').trim();
                  var sourceUrls = [];
                  try {
                    var srcs = v.querySelectorAll('source');
                    for (var s = 0; s < srcs.length; s++) {
                      var su = srcs[s].src || srcs[s].getAttribute('data-src') || '';
                      if (su && sourceUrls.indexOf(su) === -1) sourceUrls.push(su);
                    }
                  } catch (e1) {}
                  var score = 0;
                  if (!v.paused && !v.ended) score += 8000;
                  if (isVisible) score += 1200;
                  if (vh > 0) score += Math.min(vh, 2160);
                  if (dur > 0) score += Math.min(dur * 15, 1800);
                  if (!v.muted) score += 600;
                  if (v.currentSrc) score += 400;
                  entries.push({
                    elementId: 'v_' + (i + 1),
                    currentSrc: v.currentSrc || '',
                    src: v.getAttribute('src') || v.src || '',
                    sourceUrls: sourceUrls,
                    duration: dur,
                    currentTime: curTime,
                    videoWidth: vw,
                    videoHeight: vh,
                    paused: !!v.paused,
                    ended: !!v.ended,
                    muted: !!v.muted,
                    isAdvancing: !v.paused && !v.ended,
                    isVisible: isVisible,
                    viewportArea: area,
                    lastPlayEventMs: 0,
                    lastAdvanceMs: 0,
                    lastUpdatedMs: now,
                    isLikelyAd: false,
                    score: score,
                    posterUrl: poster || '',
                    titleHint: titleHint || ''
                  });
                }
                var activeIndex = -1;
                var bestScore = -999999;
                for (var idx = 0; idx < entries.length; idx++) {
                  if (entries[idx].score > bestScore) {
                    bestScore = entries[idx].score;
                    activeIndex = idx;
                  }
                }
                var domUrls = [];
                var pushDom = function(u) {
                  if (u && typeof u === 'string' && /^https?:\/\//i.test(u) && domUrls.indexOf(u) === -1) {
                    domUrls.push(u);
                  }
                };
                if (activeIndex >= 0) {
                  pushDom(entries[activeIndex].currentSrc);
                  pushDom(entries[activeIndex].src);
                  entries[activeIndex].sourceUrls.forEach(pushDom);
                }
                var pagePosterUrl = '';
                try {
                  var ogImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"], link[rel="image_src"]');
                  if (ogImg) pagePosterUrl = ogImg.getAttribute('content') || ogImg.getAttribute('href') || '';
                } catch (e2) {}
                return JSON.stringify({
                  pageUrl: window.location.href || '',
                  pageTitle: document.title || '',
                  pagePosterUrl: pagePosterUrl || '',
                  activeIndex: activeIndex,
                  videos: entries,
                  domUrls: domUrls.slice(0, 10)
                });
              } catch (err) {
                return null;
              }
            })();
        """.trimIndent()
    }
}
