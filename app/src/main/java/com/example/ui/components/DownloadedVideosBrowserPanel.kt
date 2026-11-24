package com.example.ui.components

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Environment
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Image
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.data.local.BrowserPinnedSite
import com.example.data.local.BrowserPreferencesStore
import com.example.data.local.BrowserSearchEngine
import com.example.data.local.SiteLogoFetcher
import com.example.engine.network.ClientSpoofer
import com.example.engine.network.CloudflareDnsVpnService
import com.example.vault.browser.InterceptedMediaRequest
import com.example.vault.browser.VaultWebSnapshot
import com.example.vault.browser.VaultWebViewMonitor
import org.json.JSONObject
import com.example.ui.theme.AbyssalSurface
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.DimSlate
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.GlassStrokeSubtle
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val HOME_TAB_URL = "about:blackhole-home"

private enum class BrowserSubTray {
    FIND,
}

private data class BrowserTabState(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New tab",
    val url: String = HOME_TAB_URL,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isIncognito: Boolean = false,
) {
    val isHome: Boolean get() = url == HOME_TAB_URL
}

private data class SpeedDialShortcut(
    val label: String,
    val glyph: String,
    val url: String,
    val tileColors: List<Color>,
    val glyphColor: Color = Color.White,
    val isPlayIcon: Boolean = false,
)

private val StartPageSpeedDials = listOf(
    SpeedDialShortcut("Facebook", "f", "https://m.facebook.com/", listOf(Color(0xFF1877F2), Color(0xFF0E5AD6))),
    SpeedDialShortcut("X", "𝕏", "https://x.com/", listOf(Color(0xFF0C0D10), Color(0xFF040507))),
    SpeedDialShortcut("YouTube", "▶", "https://m.youtube.com/", listOf(Color(0xFFFF0033), Color(0xFFD50026)), isPlayIcon = true),
    SpeedDialShortcut("Instagram", "IG", "https://www.instagram.com/", listOf(Color(0xFFF58529), Color(0xFFDD2A7B), Color(0xFF8134AF))),
    SpeedDialShortcut("TikTok", "♪", "https://www.tiktok.com/", listOf(Color(0xFF14161A), Color(0xFF08090B))),
    SpeedDialShortcut("YT Sign-In", "G", "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F", listOf(Color(0xFF4285F4), Color(0xFF2B6DE0))),
    SpeedDialShortcut("IG Login", "IG+", "https://www.instagram.com/accounts/login/", listOf(Color(0xFFC13584), Color(0xFF833AB4))),
    SpeedDialShortcut("FB Login", "FB+", "https://m.facebook.com/login/", listOf(Color(0xFF2563EB), Color(0xFF1D4ED8))),
    SpeedDialShortcut("Vimeo", "v", "https://vimeo.com/", listOf(Color(0xFF1AB7EA), Color(0xFF0F94C2))),
    SpeedDialShortcut("Reddit", "R", "https://www.reddit.com/", listOf(Color(0xFFFF4500), Color(0xFFD93800))),
    SpeedDialShortcut("IG Reels", "Rls", "https://www.instagram.com/reels/", listOf(Color(0xFFE4405F), Color(0xFFC92A4C))),
    SpeedDialShortcut("FB Watch", "W", "https://m.facebook.com/watch/", listOf(Color(0xFF3B5998), Color(0xFF283E6E))),
    SpeedDialShortcut("YT Shorts", "S", "https://m.youtube.com/shorts/", listOf(Color(0xFFFF1744), Color(0xFFD50000))),
    SpeedDialShortcut("Google", "G", "https://www.google.com/", listOf(Color(0xFF1F2736), Color(0xFF111722))),
    SpeedDialShortcut("Twitch", "Tw", "https://m.twitch.tv/", listOf(Color(0xFF9146FF), Color(0xFF6B28D9))),
)

private const val UNLOCK_VIEWPORT_JS = """
(function(){
  try {
    var m = document.querySelector('meta[name="viewport"]');
    if (m) {
      var c = m.getAttribute('content') || 'width=device-width';
      c = c.replace(/user-scalable\s*=\s*(no|0)/gi, 'user-scalable=yes')
           .replace(/maximum-scale\s*=\s*[0-9.]+/gi, 'maximum-scale=5.0');
      if (!/user-scalable/i.test(c)) c += ', user-scalable=yes';
      if (!/maximum-scale/i.test(c)) c += ', maximum-scale=5.0';
      m.setAttribute('content', c);
    }
  } catch (e) {}
})();
"""

private const val DESKTOP_VIEWPORT_JS = """
(function(){
  try {
    var m = document.querySelector('meta[name="viewport"]');
    if (!m) {
      m = document.createElement('meta');
      m.setAttribute('name', 'viewport');
      document.head && document.head.appendChild(m);
    }
    if (m) {
      m.setAttribute('content', 'width=1280, initial-scale=0.35, user-scalable=yes, maximum-scale=5.0');
    }
  } catch (e) {}
})();
"""

// Cohesive Nocturnal Obsidian & Liquid Glass Browser Palette
private val NightChromeSurface = AbyssalSurface
private val NightAddressPill = Color(0xFF0E1522)
private val NightAddressFocused = Color(0xFF131D2E)
private val NightTabSelected = Color(0xFF141E30)
private val NightTabInactive = Color(0xFF0B101A)
private val NightTrayPill = Color(0xFF111927)
private val NightCardSurface = Color(0xFF0C1320)
private val NightHairline = GlassStrokeSubtle

private const val DOM_MEDIA_PROBE_JS = """
(function() {
  try {
    var urls = [];
    var pushUrl = function(u) {
      if (u && typeof u === 'string' && /^https?:\/\//i.test(u) && urls.indexOf(u) === -1) {
        urls.push(u);
      }
    };
    document.querySelectorAll('video').forEach(function(v) {
      pushUrl(v.currentSrc);
      pushUrl(v.src);
      v.querySelectorAll('source').forEach(function(s) {
        pushUrl(s.src);
        pushUrl(s.getAttribute('data-src'));
      });
    });
    document.querySelectorAll('meta[property*="og:video"], meta[name*="twitter:player:stream"]').forEach(function(m) {
      pushUrl(m.getAttribute('content'));
    });
    if (window.performance && window.performance.getEntriesByType) {
      window.performance.getEntriesByType('resource').forEach(function(r) {
        var name = r && r.name ? r.name : '';
        if (/\.(mp4|m3u8|webm|mov|m4v)(\?|$|#)/i.test(name) || /videoplayback|mime=video/i.test(name)) {
          pushUrl(name);
        }
      });
    }
    return JSON.stringify({ title: document.title || '', urls: urls.slice(0, 16) });
  } catch (e) {
    return JSON.stringify({ title: '', urls: [] });
  }
})();
"""

fun collectLiveWebViewMediaCandidates(
    webView: WebView?,
    onResult: (pageTitle: String?, domVideoUrls: List<String>) -> Unit,
) {
    if (webView == null) {
        onResult(null, emptyList())
        return
    }
    runCatching {
        webView.evaluateJavascript(DOM_MEDIA_PROBE_JS) { raw ->
            val parsedTitleAndUrls = runCatching {
                val cleaned = raw?.trim().orEmpty()
                if (cleaned.isBlank() || cleaned == "null") {
                    null to emptyList()
                } else {
                    val unquoted = if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                        org.json.JSONTokener(cleaned).nextValue()?.toString() ?: cleaned
                    } else {
                        cleaned
                    }
                    val obj = JSONObject(unquoted)
                    val title = obj.optString("title").takeIf { it.isNotBlank() }
                    val arr = obj.optJSONArray("urls")
                    val list = buildList {
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val u = arr.optString(i)
                                if (u.startsWith("http", ignoreCase = true)) add(u)
                            }
                        }
                    }
                    title to list
                }
            }.getOrElse { webView.title to emptyList() }
            onResult(parsedTitleAndUrls.first ?: webView.title, parsedTitleAndUrls.second)
        }
    }.onFailure {
        onResult(webView.title, emptyList())
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DownloadedVideosBrowserPanel(
    syncedDomains: List<String>,
    storedCookieCount: Int,
    isPermanentCookieLock: Boolean,
    statusMessage: String?,
    onAutoSyncCookies: (String) -> Unit,
    onManualSyncCookies: (String) -> Unit,
    onRestoreCookiesToBrowser: () -> Unit,
    onTogglePermanentCookieLock: () -> Unit,
    onClearCookies: () -> Unit,
    onPullCurrentPage: (String) -> Unit,
    onExitBrowser: () -> Unit,
    onDismissStatus: () -> Unit,
    onBindWebViewRef: (WebView?) -> Unit,
    modifier: Modifier = Modifier,
    enableEditableSpeedDials: Boolean = false,
    exitButtonLabel: String = "VIDEOS",
    onBeforeExternalFlow: () -> Unit = {},
    onDirectDownloadIntercepted: ((url: String, mimeType: String?) -> Boolean)? = null,
    webViewMonitor: VaultWebViewMonitor = remember { VaultWebViewMonitor() },
    onPullCurrentPageWithMedia: ((
        pageUrl: String,
        activeWebView: WebView?,
        sniffedVideoUrls: List<String>,
        sniffedAudioUrls: List<String>,
        snapshot: VaultWebSnapshot?,
        interceptedMedia: List<InterceptedMediaRequest>,
    ) -> Unit)? = null,
    viewportOverlayContent: @Composable BoxScope.(
        currentPageUrl: String,
        isHomeTab: Boolean,
        activeWebView: WebView?,
        sniffedVideoUrls: List<String>,
        sniffedAudioUrls: List<String>,
        getInterceptedMedia: () -> List<InterceptedMediaRequest>,
    ) -> Unit = { _, _, _, _, _, _ -> },
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val browserPrefs = remember(context) { BrowserPreferencesStore.getInstance(context) }
    val bookmarks by browserPrefs.bookmarks.collectAsStateWithLifecycle()
    val historyEntries by browserPrefs.history.collectAsStateWithLifecycle()
    val adBlockEnabled by browserPrefs.adBlockEnabled.collectAsStateWithLifecycle()
    val searchEngine by browserPrefs.searchEngine.collectAsStateWithLifecycle()
    val pinnedSites by browserPrefs.pinnedSites.collectAsStateWithLifecycle()
    val isVpnActive by CloudflareDnsVpnService.isVpnActive.collectAsStateWithLifecycle()

    val tabs = remember { mutableStateListOf(BrowserTabState()) }
    var activeTabIndex by remember { mutableIntStateOf(0) }
    val activeTab = tabs.getOrElse(activeTabIndex) { tabs.first() }

    val webViews = remember { mutableStateMapOf<String, WebView>() }
    val tabThumbnails = remember { mutableStateMapOf<String, Bitmap>() }
    val tabFavicons = remember { mutableStateMapOf<String, Bitmap>() }
    val sniffedVideoUrlsByTab = remember { mutableStateMapOf<String, List<String>>() }
    val sniffedAudioUrlsByTab = remember { mutableStateMapOf<String, List<String>>() }
    var addressText by remember { mutableStateOf("") }
    var isUrlFocused by remember { mutableStateOf(false) }
    var showPromoBanner by remember { mutableStateOf(true) }
    var showToolsBar by remember { mutableStateOf(false) }
    var showTabsBarTray by remember { mutableStateOf(false) }
    var activeSubTray by remember { mutableStateOf<BrowserSubTray?>(null) }
    val featureWindowStack = remember { mutableStateListOf<BrowserFeatureWindow>() }
    val activeFeatureWindow = featureWindowStack.lastOrNull()
    val activeDetectedVideo by webViewMonitor.activeVideoTelemetry.collectAsStateWithLifecycle()
    var isDesktopMode by remember { mutableStateOf(false) }
    var localStatusBanner by remember { mutableStateOf<String?>(null) }

    fun openFeatureWindow(window: BrowserFeatureWindow) {
        showToolsBar = false
        showTabsBarTray = false
        if (featureWindowStack.lastOrNull() != window) {
            featureWindowStack.add(window)
        }
    }

    fun popFeatureWindow() {
        if (featureWindowStack.isNotEmpty()) {
            featureWindowStack.removeAt(featureWindowStack.lastIndex)
        }
    }

    fun closeAllFeatureWindows() {
        featureWindowStack.clear()
    }

    val blockedAdsCounter = remember { AtomicInteger(0) }
    var blockedAdsCount by remember { mutableIntStateOf(0) }

    var findQuery by remember { mutableStateOf("") }
    var findMatchIndex by remember { mutableIntStateOf(0) }
    var findTotalMatches by remember { mutableIntStateOf(0) }

    var customFullscreenView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    var zoomPercent by remember { mutableIntStateOf(100) }
    var cssFallbackZoom by remember { mutableIntStateOf(100) }
    var baseScale by remember { mutableStateOf<Float?>(null) }

    val activeWebView = webViews[activeTab.id]
    val isCurrentBookmarked = !activeTab.isHome && browserPrefs.isBookmarked(activeTab.url)
    val isCurrentPinnedToHome = !activeTab.isHome &&
        enableEditableSpeedDials &&
        browserPrefs.isPinnedSite(activeWebView?.url ?: activeTab.url)

    // Automatically fetch & cache website logos for any pinned sites on the start page
    LaunchedEffect(pinnedSites, enableEditableSpeedDials) {
        if (!enableEditableSpeedDials) return@LaunchedEffect
        val semaphore = Semaphore(4)
        pinnedSites.forEach { site ->
            val hasValidFile = !site.logoPath.isNullOrBlank() && File(site.logoPath).exists()
            if (!hasValidFile) {
                launch {
                    semaphore.withPermit {
                        val cached = SiteLogoFetcher.fetchAndCacheLogoDeduplicated(context, site.url)
                        if (!cached.isNullOrBlank()) {
                            browserPrefs.updatePinnedSiteLogo(site.id, cached)
                        }
                    }
                }
            }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            CloudflareDnsVpnService.start(context)
            localStatusBanner = "1.1.1.1 Cloudflare DNS VPN active"
        } else {
            localStatusBanner = "1.1.1.1 VPN permission was declined"
        }
    }

    fun toggleCloudflareVpn() {
        if (isVpnActive) {
            CloudflareDnsVpnService.stop(context)
            localStatusBanner = "1.1.1.1 VPN disconnected"
            return
        }
        val prepareIntent = runCatching { VpnService.prepare(context) }.getOrNull()
        if (prepareIntent != null) {
            onBeforeExternalFlow()
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            CloudflareDnsVpnService.start(context)
            localStatusBanner = "1.1.1.1 Cloudflare DNS VPN active"
        }
    }

    LaunchedEffect(localStatusBanner) {
        if (!localStatusBanner.isNullOrBlank()) {
            delay(3200L)
            localStatusBanner = null
        }
    }

    // Ensure AES-256 permanently saved cookies are injected into WebView CookieManager on entry
    LaunchedEffect(Unit) {
        onRestoreCookiesToBrowser()
    }

    LaunchedEffect(activeTab.id, activeTab.url, activeWebView) {
        onBindWebViewRef(if (activeTab.isHome) null else activeWebView)
        if (!isUrlFocused) {
            addressText = if (activeTab.isHome) "" else activeTab.url
        }
        if (activeTab.isHome) {
            webViewMonitor.clearActiveTelemetry()
            if (activeSubTray == BrowserSubTray.FIND) {
                activeSubTray = null
            }
        } else {
            webViewMonitor.injectMonitor(activeWebView)
        }
    }

    DisposableEffect(webViewMonitor) {
        onDispose {
            runCatching { customViewCallback?.onCustomViewHidden() }
            customFullscreenView = null
            customViewCallback = null
            val currentTab = tabs.getOrNull(activeTabIndex)
            if (currentTab != null && !currentTab.isIncognito) {
                val currentUrl = webViews[currentTab.id]?.url ?: currentTab.url
                if (currentUrl.startsWith("http", ignoreCase = true)) {
                    onAutoSyncCookies(currentUrl)
                } else {
                    onAutoSyncCookies("https://www.instagram.com/")
                }
            }
            onBindWebViewRef(null)
            webViews.forEach { (id, wv) ->
                runCatching {
                    webViewMonitor.detachFrom(wv)
                    webViewMonitor.clearTab(id)
                    wv.stopLoading()
                    wv.destroy()
                }
            }
            webViews.clear()
            tabThumbnails.clear()
            tabFavicons.clear()
        }
    }

    fun captureTabThumbnail(tabId: String, wv: WebView?) {
        if (wv == null) return
        val w = wv.width
        val h = wv.height
        if (w <= 0 || h <= 0) return
        runCatching {
            val targetWidth = 420
            val scale = (targetWidth.toFloat() / w.toFloat()).coerceAtMost(1f)
            val targetHeight = (h * scale).toInt().coerceIn(120, 960)
            val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
            val canvas = AndroidCanvas(bmp)
            canvas.scale(scale, scale)
            canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(canvas)
            tabThumbnails[tabId] = bmp
        }
    }

    fun exitFullscreenVideo() {
        runCatching { customViewCallback?.onCustomViewHidden() }
        customFullscreenView = null
        customViewCallback = null
    }

    BackHandler(
        enabled = customFullscreenView != null ||
            activeFeatureWindow != null ||
            showTabsBarTray ||
            showToolsBar ||
            activeSubTray != null ||
            (!activeTab.isHome && activeTab.canGoBack),
    ) {
        when {
            customFullscreenView != null -> exitFullscreenVideo()
            activeFeatureWindow != null -> popFeatureWindow()
            showTabsBarTray -> showTabsBarTray = false
            showToolsBar -> showToolsBar = false
            activeSubTray != null -> {
                if (activeSubTray == BrowserSubTray.FIND) {
                    webViews[activeTab.id]?.clearMatches()
                    findQuery = ""
                    findMatchIndex = 0
                    findTotalMatches = 0
                }
                activeSubTray = null
            }
            !activeTab.isHome && activeTab.canGoBack -> webViews[activeTab.id]?.goBack()
        }
    }

    fun updateTabById(tabId: String, transform: (BrowserTabState) -> BrowserTabState) {
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0) {
            tabs[idx] = transform(tabs[idx])
        }
    }

    fun normalizeInputToUrl(raw: String, engine: BrowserSearchEngine = searchEngine): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return HOME_TAB_URL
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "${engine.searchTemplate}${Uri.encode(trimmed)}"
        }
    }

    fun resolveNativeMobileUserAgent(ctx: Context): String {
        return runCatching {
            WebSettings.getDefaultUserAgent(ctx)
                .replace(Regex(";\\s*wv\\b"), "")
                .replace("Version/4.0 ", "")
        }.getOrElse { ClientSpoofer.ANDROID_CHROME_USER_AGENT }
    }

    fun applyDesktopSettings(wv: WebView, desktop: Boolean) {
        wv.settings.apply {
            userAgentString = if (desktop) {
                ClientSpoofer.CHROME_DESKTOP_USER_AGENT
            } else {
                resolveNativeMobileUserAgent(wv.context)
            }
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    }

    fun resolveUrlForDesktopMode(url: String, desktop: Boolean): String {
        if (!desktop) return url
        return when {
            url.startsWith("https://m.youtube.com", ignoreCase = true) ->
                url.replaceFirst("https://m.youtube.com", "https://www.youtube.com", ignoreCase = true)
            url.startsWith("https://m.facebook.com", ignoreCase = true) ->
                url.replaceFirst("https://m.facebook.com", "https://www.facebook.com", ignoreCase = true)
            url.startsWith("https://m.twitch.tv", ignoreCase = true) ->
                url.replaceFirst("https://m.twitch.tv", "https://www.twitch.tv", ignoreCase = true)
            else -> url
        }
    }

    fun toggleDesktopMode() {
        val next = !isDesktopMode
        isDesktopMode = next
        webViews.values.forEach { wv -> applyDesktopSettings(wv, next) }
        val currentWv = webViews[activeTab.id]
        if (!activeTab.isHome && currentWv != null) {
            val currentUrl = currentWv.url ?: activeTab.url
            val mappedUrl = resolveUrlForDesktopMode(currentUrl, next)
            if (mappedUrl != currentUrl) {
                currentWv.loadUrl(mappedUrl)
            } else {
                currentWv.reload()
            }
        }
    }

    fun openUrlInActiveTab(raw: String) {
        val rawNormalized = normalizeInputToUrl(raw, searchEngine)
        if (rawNormalized == HOME_TAB_URL) {
            updateTabById(activeTab.id) {
                it.copy(title = "New tab", url = HOME_TAB_URL, progress = 0, canGoBack = false, canGoForward = false)
            }
            addressText = ""
            onBindWebViewRef(null)
            return
        }
        val normalized = resolveUrlForDesktopMode(rawNormalized, isDesktopMode)
        val tabId = activeTab.id
        baseScale = null
        zoomPercent = 100
        cssFallbackZoom = 100
        addressText = normalized
        updateTabById(tabId) {
            it.copy(url = normalized, title = deriveHostLabel(normalized), progress = 12)
        }
        webViews[tabId]?.let { wv ->
            applyDesktopSettings(wv, isDesktopMode)
            wv.loadUrl(normalized)
        }
    }

    fun openNewTab(initialUrl: String = HOME_TAB_URL, incognito: Boolean = false) {
        val currentActive = tabs.getOrNull(activeTabIndex)
        if (currentActive != null && !currentActive.isHome) {
            captureTabThumbnail(currentActive.id, webViews[currentActive.id])
        }
        val tab = BrowserTabState(
            title = if (initialUrl == HOME_TAB_URL) {
                if (incognito) "Private" else "New tab"
            } else {
                deriveHostLabel(initialUrl)
            },
            url = initialUrl,
            isIncognito = incognito,
        )
        tabs.add(tab)
        activeTabIndex = tabs.lastIndex
        baseScale = null
        zoomPercent = 100
        cssFallbackZoom = 100
        showTabsBarTray = false
        if (incognito && initialUrl == HOME_TAB_URL) {
            localStatusBanner = "Private tab opened — history not saved"
        }
    }

    fun extractDirectUrlFromAddressBar(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == HOME_TAB_URL) return null
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> null
        }
    }

    fun urlsMatchSamePage(first: String?, second: String?): Boolean {
        if (first.isNullOrBlank() || second.isNullOrBlank()) return false
        return first.trim().trimEnd('/').equals(second.trim().trimEnd('/'), ignoreCase = true)
    }

    fun resolveTopBarDownloadUrl(): String? {
        val barUrl = extractDirectUrlFromAddressBar(addressText)
        val loadedUrl = webViews[activeTab.id]?.url?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: activeTab.url.takeIf { it.startsWith("http", ignoreCase = true) }
        return when {
            barUrl != null && (loadedUrl == null || isUrlFocused || !urlsMatchSamePage(barUrl, loadedUrl)) -> barUrl
            loadedUrl != null -> loadedUrl
            else -> barUrl
        }
    }

    fun triggerPullFromTopUrl(explicitUrl: String? = null) {
        val targetUrl = explicitUrl?.let { extractDirectUrlFromAddressBar(it) ?: it.trim().takeIf { s -> s.startsWith("http", ignoreCase = true) } }
            ?: resolveTopBarDownloadUrl()
        if (targetUrl.isNullOrBlank()) {
            if (onPullCurrentPageWithMedia != null) {
                onPullCurrentPageWithMedia("", null, emptyList(), emptyList(), null, emptyList())
            } else {
                localStatusBanner = "أدخل رابط الفيديو في الشريط بالأعلى أو افتح صفحة أولاً"
            }
            return
        }
        focusManager.clearFocus()
        isUrlFocused = false
        if (explicitUrl == null) {
            addressText = targetUrl
        }
        onAutoSyncCookies(targetUrl)

        val tabId = activeTab.id
        val loadedUrl = webViews[tabId]?.url?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: activeTab.url.takeIf { it.startsWith("http", ignoreCase = true) }
        val matchesLiveWebView = !activeTab.isHome &&
            activeWebView != null &&
            urlsMatchSamePage(targetUrl, loadedUrl)

        val matchedWebView = if (matchesLiveWebView) activeWebView else null
        val sniffedVideos = if (matchesLiveWebView) sniffedVideoUrlsByTab[tabId].orEmpty() else emptyList()
        val sniffedAudios = if (matchesLiveWebView) sniffedAudioUrlsByTab[tabId].orEmpty() else emptyList()
        val intercepted = if (matchesLiveWebView) webViewMonitor.getInterceptedRequests(tabId) else emptyList()

        if (onPullCurrentPageWithMedia != null) {
            coroutineScope.launch {
                val snapshot = if (matchedWebView != null) {
                    webViewMonitor.captureFreshSnapshot(matchedWebView, targetUrl)
                } else {
                    null
                }
                onPullCurrentPageWithMedia(
                    targetUrl,
                    matchedWebView,
                    sniffedVideos,
                    sniffedAudios,
                    snapshot,
                    intercepted,
                )
            }
        } else {
            onPullCurrentPage(targetUrl)
        }
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val removed = tabs[index]
        sniffedVideoUrlsByTab.remove(removed.id)
        sniffedAudioUrlsByTab.remove(removed.id)
        tabThumbnails.remove(removed.id)
        tabFavicons.remove(removed.id)
        webViewMonitor.clearTab(removed.id)
        webViews.remove(removed.id)?.let { wv ->
            runCatching {
                webViewMonitor.detachFrom(wv)
                wv.stopLoading()
                wv.destroy()
            }
        }
        if (tabs.size == 1) {
            tabs[0] = BrowserTabState()
            activeTabIndex = 0
            addressText = ""
            zoomPercent = 100
            cssFallbackZoom = 100
            return
        }
        tabs.removeAt(index)
        if (activeTabIndex >= tabs.size) {
            activeTabIndex = tabs.lastIndex
        } else if (activeTabIndex > index) {
            activeTabIndex -= 1
        }
    }

    fun closeAllTabs(incognitoOnly: Boolean) {
        val toRemove = if (incognitoOnly) {
            tabs.filter { it.isIncognito }
        } else {
            tabs.toList()
        }
        toRemove.forEach { removed ->
            sniffedVideoUrlsByTab.remove(removed.id)
            sniffedAudioUrlsByTab.remove(removed.id)
            tabThumbnails.remove(removed.id)
            tabFavicons.remove(removed.id)
            webViewMonitor.clearTab(removed.id)
            webViews.remove(removed.id)?.let { wv ->
                runCatching {
                    webViewMonitor.detachFrom(wv)
                    wv.stopLoading()
                    wv.destroy()
                }
            }
        }
        if (incognitoOnly) {
            tabs.removeAll { it.isIncognito }
            if (tabs.isEmpty()) {
                tabs.add(BrowserTabState())
                activeTabIndex = 0
                addressText = ""
            } else if (activeTabIndex >= tabs.size) {
                activeTabIndex = tabs.lastIndex
            }
        } else {
            tabs.clear()
            tabs.add(BrowserTabState())
            activeTabIndex = 0
            addressText = ""
            zoomPercent = 100
            cssFallbackZoom = 100
            showTabsBarTray = false
        }
    }

    fun zoomPageBy(stepPercent: Int) {
        val wv = webViews[activeTab.id] ?: return
        wv.evaluateJavascript(UNLOCK_VIEWPORT_JS, null)
        val nativeChanged = if (stepPercent > 0) wv.zoomIn() else wv.zoomOut()
        val nextPercent = (zoomPercent + stepPercent).coerceIn(50, 250)
        zoomPercent = nextPercent
        if (!nativeChanged) {
            val nextCss = (cssFallbackZoom + stepPercent).coerceIn(50, 250)
            cssFallbackZoom = nextCss
            wv.evaluateJavascript(
                "document.documentElement.style.zoom = '${nextCss}%';",
                null,
            )
        }
    }

    fun applyZoomPercent(targetPercent: Int) {
        val clamped = targetPercent.coerceIn(50, 250)
        zoomPercent = clamped
        cssFallbackZoom = clamped
        val wv = webViews[activeTab.id] ?: return
        wv.evaluateJavascript(UNLOCK_VIEWPORT_JS, null)
        wv.evaluateJavascript(
            "document.documentElement.style.zoom = '${clamped}%';",
            null,
        )
    }

    fun resetZoom() {
        val wv = webViews[activeTab.id] ?: return
        cssFallbackZoom = 100
        zoomPercent = 100
        wv.evaluateJavascript(
            "document.documentElement.style.zoom = '100%';",
            null,
        )
        var guard = 0
        while (wv.zoomOut() && guard < 16) {
            guard++
        }
    }

    fun runFindInPage(query: String) {
        findQuery = query
        val wv = webViews[activeTab.id] ?: return
        if (query.isBlank()) {
            wv.clearMatches()
            findMatchIndex = 0
            findTotalMatches = 0
        } else {
            wv.findAllAsync(query)
        }
    }

    fun shareActiveUrl() {
        val target = webViews[activeTab.id]?.url ?: activeTab.url
        if (target == HOME_TAB_URL || target.isBlank()) return
        runCatching {
            onBeforeExternalFlow()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, activeTab.title)
                putExtra(Intent.EXTRA_TEXT, target)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share link"))
        }
    }

    fun copyActiveUrl() {
        val target = webViews[activeTab.id]?.url ?: activeTab.url
        if (target == HOME_TAB_URL || target.isBlank()) return
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("URL", target))
            localStatusBanner = "Link copied"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .testTag("downloaded_videos_browser_panel"),
    ) {
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // 1. Compact Nocturnal Top Tab Strip + Exit Pill
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(NightChromeSurface, Color(0xFF070B11)),
                    ),
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val isSelected = index == activeTabIndex
                        val selectedAccent = if (tab.isIncognito) PlasmaViolet else NeonCyan
                        Surface(
                            color = if (isSelected) NightTabSelected else NightTabInactive,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                width = 0.7.dp,
                                color = when {
                                    isSelected -> selectedAccent.copy(alpha = 0.5f)
                                    tab.isIncognito -> PlasmaViolet.copy(alpha = 0.35f)
                                    else -> NightHairline
                                },
                            ),
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable {
                                    if (!activeTab.isHome) {
                                        captureTabThumbnail(activeTab.id, webViews[activeTab.id])
                                    }
                                    activeTabIndex = index
                                }
                                .testTag("browser_tab_${index}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 7.dp, end = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (tab.isIncognito) Icons.Rounded.VisibilityOff else Icons.Outlined.Public,
                                    contentDescription = if (tab.isIncognito) "Private tab" else null,
                                    tint = if (isSelected) selectedAccent else if (tab.isIncognito) PlasmaViolet.copy(alpha = 0.8f) else MutedSlate,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = tab.title.ifBlank { if (tab.isIncognito) "Private" else "New tab" },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    ),
                                    color = if (isSelected) ChromeSilver else MutedSlate,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(74.dp),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .clickable { closeTab(index) }
                                        .testTag("browser_close_tab_${index}"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Close tab",
                                        tint = MutedSlate,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                    }

                    // + Add New Tab Button (compact)
                    Surface(
                        color = NightTabInactive,
                        shape = CircleShape,
                        border = BorderStroke(0.7.dp, GlassStrokeBright.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .clickable { openNewTab() }
                            .testTag("browser_new_tab_button"),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "New tab",
                                tint = ChromeSilver,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(5.dp))

                // Compact Exit Full-Screen Browser -> Downloaded Videos button
                Surface(
                    color = NightAddressPill,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(0.75.dp, NeonCyan.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { onExitBrowser() }
                        .testTag("browser_exit_fullscreen_button"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Movie,
                            contentDescription = "Back to downloaded videos",
                            tint = NeonCyan,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = exitButtonLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = ChromeSilver,
                        )
                    }
                }
            }

            // 2. Ultra-Compact Nocturnal Search / URL Pill Bar
            Surface(
                color = if (isUrlFocused) NightAddressFocused else NightAddressPill,
                shape = RoundedCornerShape(17.dp),
                border = BorderStroke(
                    width = if (isUrlFocused) 0.9.dp else 0.7.dp,
                    color = if (isUrlFocused) {
                        NeonCyan.copy(alpha = 0.65f)
                    } else {
                        GlassStrokeBright.copy(alpha = 0.22f)
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .padding(bottom = 5.dp)
                    .height(34.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!activeTab.isHome || isUrlFocused) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = if (activeTab.url.startsWith("https://", true)) EmeraldPulse else MutedSlate,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = if (activeTab.isHome && addressText.isEmpty() && !isUrlFocused) {
                            Alignment.Center
                        } else {
                            Alignment.CenterStart
                        },
                    ) {
                        BasicTextField(
                            value = addressText,
                            onValueChange = { addressText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.5.sp,
                                color = ChromeSilver,
                            ),
                            cursorBrush = SolidColor(NeonCyan),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    isUrlFocused = false
                                    openUrlInActiveTab(addressText)
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    isUrlFocused = focusState.isFocused
                                    if (focusState.isFocused && activeTab.isHome && addressText == HOME_TAB_URL) {
                                        addressText = ""
                                    }
                                }
                                .testTag("browser_address_input"),
                        ) { innerTextField ->
                            if (addressText.isEmpty()) {
                                Text(
                                    text = "Search or URL",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.5.sp,
                                        color = MutedSlate,
                                    ),
                                    textAlign = if (!isUrlFocused && activeTab.isHome) TextAlign.Center else TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                innerTextField()
                            }
                        }
                    }

                    if (isVpnActive) {
                        Surface(
                            color = EmeraldPulse.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.6.dp, EmeraldPulse.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { toggleCloudflareVpn() }
                                .testTag("browser_address_vpn_pill"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VpnLock,
                                    contentDescription = "1.1.1.1 VPN active",
                                    tint = EmeraldPulse,
                                    modifier = Modifier.size(10.dp),
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "1.1.1.1",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = EmeraldPulse,
                                )
                            }
                        }
                    }

                    if (isDesktopMode) {
                        Surface(
                            color = NeonCyan.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.6.dp, NeonCyan.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { toggleDesktopMode() }
                                .padding(end = 3.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DesktopWindows,
                                    contentDescription = "Desktop view enabled",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(11.dp),
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "PC",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = NeonCyan,
                                )
                            }
                        }
                    }

                    if (!activeTab.isHome) {
                        if (zoomPercent != 100) {
                            Text(
                                text = "$zoomPercent%",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = NeonCyan,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showToolsBar = !showToolsBar }
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                        val isLoadingPage = activeTab.progress in 1..99
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val wv = webViews[activeTab.id]
                                    if (isLoadingPage) wv?.stopLoading() else wv?.reload()
                                }
                                .testTag("browser_url_reload_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (isLoadingPage) Icons.Rounded.Close else Icons.Rounded.Refresh,
                                contentDescription = if (isLoadingPage) "Stop loading" else "Reload page",
                                tint = ChromeSilver.copy(alpha = 0.85f),
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }

            if (!activeTab.isHome && activeTab.progress in 1..99) {
                LinearProgressIndicator(
                    progress = { activeTab.progress / 100f },
                    color = NeonCyan,
                    trackColor = NightHairline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.5.dp),
                )
            } else {
                HorizontalDivider(color = NightHairline, thickness = 0.6.dp)
            }
        }

        // 3. Browser Viewport: Samsung-Style Start Page OR Live WebView Page
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (activeTab.isHome) {
                SamsungStartPageHome(
                    pinnedSites = pinnedSites,
                    enableEditableSpeedDials = enableEditableSpeedDials,
                    syncedDomainsCount = syncedDomains.size,
                    storedCookieCount = storedCookieCount,
                    isPermanentCookieLock = isPermanentCookieLock,
                    showBanner = showPromoBanner,
                    onDismissBanner = { showPromoBanner = false },
                    onOpenShortcut = { shortcutUrl -> openUrlInActiveTab(shortcutUrl) },
                    onToggleTools = { showToolsBar = !showToolsBar },
                    onManualSync = { onManualSyncCookies("https://www.instagram.com/") },
                    onAddPinnedSite = { label, url, previewLogoPath ->
                        val added = browserPrefs.addPinnedSite(label, url, logoPath = previewLogoPath)
                        if (added != null) {
                            localStatusBanner = "تمت إضافة الموقع وجلب الشعار تلقائياً"
                            if (added.logoPath.isNullOrBlank()) {
                                coroutineScope.launch {
                                    val fetched = SiteLogoFetcher.fetchAndCacheLogo(
                                        context = context,
                                        siteUrl = added.url,
                                        forceRefresh = true,
                                    )
                                    if (!fetched.isNullOrBlank()) {
                                        browserPrefs.updatePinnedSiteLogo(added.id, fetched)
                                    }
                                }
                            }
                            true
                        } else {
                            false
                        }
                    },
                    onUpdatePinnedSite = { id, label, url, previewLogoPath ->
                        val updated = browserPrefs.updatePinnedSite(id, label, url, logoPath = previewLogoPath)
                        if (updated) {
                            localStatusBanner = "تم تحديث الموقع المثبت"
                            val refreshed = browserPrefs.pinnedSites.value.firstOrNull { it.id == id }
                            if (refreshed != null && refreshed.logoPath.isNullOrBlank()) {
                                coroutineScope.launch {
                                    val fetched = SiteLogoFetcher.fetchAndCacheLogo(
                                        context = context,
                                        siteUrl = refreshed.url,
                                        forceRefresh = true,
                                    )
                                    if (!fetched.isNullOrBlank()) {
                                        browserPrefs.updatePinnedSiteLogo(refreshed.id, fetched)
                                    }
                                }
                            }
                        }
                        updated
                    },
                    onDeletePinnedSite = { id ->
                        val deleted = browserPrefs.deletePinnedSite(id)
                        if (deleted) localStatusBanner = "تم حذف الموقع من القائمة المثبتة"
                        deleted
                    },
                )
            } else {
                key(activeTab.id) {
                    val tabId = activeTab.id
                    AndroidView(
                        factory = { ctx ->
                            webViews[tabId]?.let { existing ->
                                (existing.parent as? ViewGroup)?.removeView(existing)
                                return@AndroidView existing
                            }
                            CookieManager.getInstance().setAcceptCookie(true)
                            WebView(ctx).apply {
                                setBackgroundColor(android.graphics.Color.parseColor("#050709"))
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                settings.apply {
                                    javaScriptEnabled = true
                                    javaScriptCanOpenWindowsAutomatically = true
                                    setSupportMultipleWindows(true)
                                    domStorageEnabled = true
                                    databaseEnabled = !activeTab.isIncognito
                                    saveFormData = !activeTab.isIncognito
                                    cacheMode = if (activeTab.isIncognito) {
                                        WebSettings.LOAD_NO_CACHE
                                    } else {
                                        WebSettings.LOAD_DEFAULT
                                    }
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    setSupportZoom(true)
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    mediaPlaybackRequiresUserGesture = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    userAgentString = if (isDesktopMode) {
                                        ClientSpoofer.CHROME_DESKTOP_USER_AGENT
                                    } else {
                                        resolveNativeMobileUserAgent(ctx)
                                    }
                                }

                                setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                                    if (tabId == tabs.getOrNull(activeTabIndex)?.id) {
                                        findMatchIndex = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
                                        findTotalMatches = numberOfMatches
                                    }
                                }

                                setDownloadListener { dlUrl, userAgent, contentDisposition, mimetype, _ ->
                                    if (dlUrl.isNullOrBlank()) return@setDownloadListener
                                    if (onDirectDownloadIntercepted?.invoke(dlUrl, mimetype) == true) {
                                        return@setDownloadListener
                                    }
                                    val fileName = URLUtil.guessFileName(dlUrl, contentDisposition, mimetype)
                                        .ifBlank { "download.bin" }
                                    val queued = runCatching {
                                        val req = DownloadManager.Request(Uri.parse(dlUrl)).apply {
                                            setTitle(fileName)
                                            setMimeType(mimetype)
                                            if (!userAgent.isNullOrBlank()) {
                                                addRequestHeader("User-Agent", userAgent)
                                            }
                                            CookieManager.getInstance().getCookie(dlUrl)?.let { cookie ->
                                                addRequestHeader("Cookie", cookie)
                                            }
                                            setNotificationVisibility(
                                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                                            )
                                            setDestinationInExternalPublicDir(
                                                Environment.DIRECTORY_DOWNLOADS,
                                                fileName,
                                            )
                                        }
                                        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                        dm.enqueue(req)
                                        true
                                    }.getOrDefault(false)
                                    localStatusBanner = if (queued) {
                                        "Downloading: $fileName"
                                    } else {
                                        "Unable to start download"
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): WebResourceResponse? {
                                        if (adBlockEnabled && isAdOrTrackerRequest(request)) {
                                            val updated = blockedAdsCounter.incrementAndGet()
                                            view?.post { blockedAdsCount = updated }
                                            return WebResourceResponse(
                                                "text/plain",
                                                "utf-8",
                                                ByteArrayInputStream(ByteArray(0)),
                                            )
                                        }
                                        val reqUrl = request?.url?.toString().orEmpty()
                                        if (reqUrl.isNotBlank()) {
                                            val activePage = tabs.firstOrNull { it.id == tabId }?.url.orEmpty()
                                            webViewMonitor.recordInterceptedRequest(tabId, request, activePage)
                                            when {
                                                isInterceptableAudioStream(reqUrl) -> {
                                                    view?.post {
                                                        val prev = sniffedAudioUrlsByTab[tabId].orEmpty()
                                                        if (reqUrl !in prev) {
                                                            sniffedAudioUrlsByTab[tabId] = (listOf(reqUrl) + prev).take(10)
                                                        }
                                                    }
                                                }
                                                isInterceptableVideoStream(reqUrl) -> {
                                                    view?.post {
                                                        val prev = sniffedVideoUrlsByTab[tabId].orEmpty()
                                                        if (reqUrl !in prev) {
                                                            sniffedVideoUrlsByTab[tabId] = (listOf(reqUrl) + prev).take(16)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        return super.shouldInterceptRequest(view, request)
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val nextUrl = request?.url?.toString().orEmpty()
                                        if (nextUrl.isBlank()) return false
                                        if (nextUrl.startsWith("http://", ignoreCase = true) ||
                                            nextUrl.startsWith("https://", ignoreCase = true)
                                        ) {
                                            return false
                                        }
                                        if (nextUrl.startsWith("mailto:", ignoreCase = true) ||
                                            nextUrl.startsWith("tel:", ignoreCase = true)
                                        ) {
                                            runCatching {
                                                ctx.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(nextUrl)).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    },
                                                )
                                            }
                                            return true
                                        }
                                        if (nextUrl.startsWith("intent://", ignoreCase = true)) {
                                            runCatching {
                                                val parsed = Intent.parseUri(nextUrl, Intent.URI_INTENT_SCHEME).apply {
                                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                                    component = null
                                                    selector = null
                                                }
                                                ctx.startActivity(parsed)
                                            }
                                            return true
                                        }
                                        return true
                                    }

                                    override fun onScaleChanged(
                                        view: WebView?,
                                        oldScale: Float,
                                        newScale: Float,
                                    ) {
                                        super.onScaleChanged(view, oldScale, newScale)
                                        if (newScale > 0f && tabId == tabs.getOrNull(activeTabIndex)?.id) {
                                            val ref = baseScale ?: oldScale.takeIf { it > 0f } ?: newScale
                                            if (baseScale == null) baseScale = ref
                                            if (ref > 0f) {
                                                zoomPercent = ((newScale / ref) * 100f).toInt().coerceIn(50, 300)
                                            }
                                        }
                                    }

                                    private var retriedGoogleMismatch = false

                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        super.onPageStarted(view, url, favicon)
                                        sniffedVideoUrlsByTab.remove(tabId)
                                        sniffedAudioUrlsByTab.remove(tabId)
                                        webViewMonitor.resetForNavigation(tabId)
                                        if (!url.isNullOrBlank()) {
                                            updateTabById(tabId) {
                                                it.copy(
                                                    url = url,
                                                    canGoBack = view?.canGoBack() == true,
                                                    canGoForward = view?.canGoForward() == true,
                                                )
                                            }
                                        }
                                        webViewMonitor.injectMonitor(view)
                                    }

                                    override fun doUpdateVisitedHistory(
                                        view: WebView?,
                                        url: String?,
                                        isReload: Boolean,
                                    ) {
                                        super.doUpdateVisitedHistory(view, url, isReload)
                                        if (!url.isNullOrBlank() && url.startsWith("http", ignoreCase = true)) {
                                            val previousUrl = tabs.firstOrNull { it.id == tabId }?.url.orEmpty()
                                            val prevIdentity = canonicalRouteIdentity(previousUrl)
                                            val nextIdentity = canonicalRouteIdentity(url)
                                            if (prevIdentity.isNotBlank() && prevIdentity != nextIdentity) {
                                                sniffedVideoUrlsByTab.remove(tabId)
                                                sniffedAudioUrlsByTab.remove(tabId)
                                                webViewMonitor.resetForNavigation(tabId)
                                            }
                                            updateTabById(tabId) {
                                                it.copy(
                                                    url = url,
                                                    canGoBack = view?.canGoBack() == true,
                                                    canGoForward = view?.canGoForward() == true,
                                                )
                                            }
                                            if (tabId == tabs.getOrNull(activeTabIndex)?.id && !isUrlFocused) {
                                                addressText = url
                                            }
                                            webViewMonitor.injectMonitor(view)
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        val isPrivate = tabs.firstOrNull { it.id == tabId }?.isIncognito == true
                                        val cm = CookieManager.getInstance()
                                        cm.setAcceptCookie(true)
                                        view?.let { cm.setAcceptThirdPartyCookies(it, true) }
                                        cm.flush()
                                        val loaded = url.orEmpty()
                                        if (loaded.contains("CookieMismatch", ignoreCase = true)) {
                                            if (!retriedGoogleMismatch) {
                                                retriedGoogleMismatch = true
                                                cm.removeAllCookies {
                                                    runCatching { WebStorage.getInstance().deleteAllData() }
                                                    cm.setAcceptCookie(true)
                                                    view?.let { cm.setAcceptThirdPartyCookies(it, true) }
                                                    cm.flush()
                                                    onRestoreCookiesToBrowser()
                                                    view?.clearCache(true)
                                                    view?.postDelayed({
                                                        view.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F")
                                                    }, 280L)
                                                }
                                            }
                                            return
                                        }
                                        if (loaded.isNotBlank()) {
                                            val pageTitle = view?.title?.takeIf { it.isNotBlank() }
                                                ?: deriveHostLabel(loaded)
                                            updateTabById(tabId) {
                                                it.copy(
                                                    url = loaded,
                                                    title = pageTitle,
                                                    canGoBack = view?.canGoBack() == true,
                                                    canGoForward = view?.canGoForward() == true,
                                                )
                                            }
                                            if (tabId == tabs.getOrNull(activeTabIndex)?.id && !isUrlFocused) {
                                                addressText = loaded
                                            }
                                            if (!isPrivate) {
                                                browserPrefs.recordHistory(loaded, pageTitle)
                                                val lowerLoaded = loaded.lowercase()
                                                val midGoogleAuth = lowerLoaded.contains("accounts.google.") ||
                                                    lowerLoaded.contains("accounts.youtube.") ||
                                                    lowerLoaded.contains("consent.google.") ||
                                                    lowerLoaded.contains("consent.youtube.")
                                                if (!midGoogleAuth) {
                                                    onAutoSyncCookies(loaded)
                                                }
                                            }
                                        }
                                        view?.evaluateJavascript(
                                            if (isDesktopMode) DESKTOP_VIEWPORT_JS else UNLOCK_VIEWPORT_JS,
                                            null,
                                        )
                                        webViewMonitor.injectMonitor(view)
                                        view?.postDelayed({
                                            captureTabThumbnail(tabId, view)
                                        }, 250L)
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        updateTabById(tabId) {
                                            it.copy(
                                                progress = newProgress,
                                                canGoBack = view?.canGoBack() == true,
                                                canGoForward = view?.canGoForward() == true,
                                            )
                                        }
                                    }

                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        if (!title.isNullOrBlank()) {
                                            updateTabById(tabId) { it.copy(title = title) }
                                        }
                                    }

                                    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                                        if (icon != null) {
                                            tabFavicons[tabId] = icon
                                        }
                                    }

                                    override fun onCreateWindow(
                                        view: WebView?,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: Message?,
                                    ): Boolean {
                                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                        val isPrivate = tabs.firstOrNull { it.id == tabId }?.isIncognito == true
                                        val popupCatcher = WebView(ctx)
                                        popupCatcher.webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                v: WebView?,
                                                request: WebResourceRequest?,
                                            ): Boolean {
                                                val target = request?.url?.toString().orEmpty()
                                                if (target.startsWith("http", ignoreCase = true)) {
                                                    openNewTab(initialUrl = target, incognito = isPrivate)
                                                }
                                                runCatching { popupCatcher.destroy() }
                                                return true
                                            }
                                        }
                                        transport.webView = popupCatcher
                                        resultMsg.sendToTarget()
                                        return true
                                    }

                                    override fun onShowCustomView(
                                        view: View?,
                                        callback: CustomViewCallback?,
                                    ) {
                                        if (view == null) return
                                        customViewCallback?.onCustomViewHidden()
                                        customFullscreenView = view
                                        customViewCallback = callback
                                    }

                                    override fun onHideCustomView() {
                                        customFullscreenView = null
                                        customViewCallback = null
                                    }
                                }

                                webViews[tabId] = this
                                webViewMonitor.attachTo(this)
                                onBindWebViewRef(this)
                                loadUrl(activeTab.url)
                            }
                        },
                        update = { wv ->
                            updateTabById(tabId) {
                                if (it.canGoBack != wv.canGoBack() || it.canGoForward != wv.canGoForward()) {
                                    it.copy(canGoBack = wv.canGoBack(), canGoForward = wv.canGoForward())
                                } else {
                                    it
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("browser_webview"),
                    )
                }
            }

            // Fullscreen HTML5 Video Overlay
            val fsView = customFullscreenView
            if (fsView != null) {
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            (fsView.parent as? ViewGroup)?.removeView(fsView)
                            addView(
                                fsView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val resolvedOverlayUrl = resolveTopBarDownloadUrl()
            val loadedOverlayUrl = activeWebView?.url?.takeIf { it.startsWith("http", ignoreCase = true) }
                ?: activeTab.url.takeIf { it.startsWith("http", ignoreCase = true) }
            val overlayMatchesWebView = !activeTab.isHome &&
                activeWebView != null &&
                resolvedOverlayUrl != null &&
                urlsMatchSamePage(resolvedOverlayUrl, loadedOverlayUrl)

            viewportOverlayContent(
                resolvedOverlayUrl.orEmpty(),
                resolvedOverlayUrl == null,
                if (overlayMatchesWebView) activeWebView else null,
                if (overlayMatchesWebView) sniffedVideoUrlsByTab[activeTab.id].orEmpty() else emptyList(),
                if (overlayMatchesWebView) sniffedAudioUrlsByTab[activeTab.id].orEmpty() else emptyList(),
                {
                    if (overlayMatchesWebView) {
                        webViewMonitor.getInterceptedRequests(activeTab.id)
                    } else {
                        emptyList()
                    }
                },
            )
        }

        // 4. Inline Cookie & Browser Status Toast (Zero Dialog — Nocturnal)
        val combinedStatus = statusMessage ?: localStatusBanner
        AnimatedVisibility(
            visible = !combinedStatus.isNullOrBlank(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            combinedStatus?.let { msg ->
                Surface(
                    color = Color(0xFF0C1320),
                    border = BorderStroke(0.6.dp, NeonCyan.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .clickable {
                                    localStatusBanner = null
                                    onDismissStatus()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Dismiss status",
                                tint = MutedSlate,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        }

        // 4b. Inline Browser Sub-Trays (Find-in-Page, Bookmarks, History — Nocturnal Obsidian)
        AnimatedVisibility(
            visible = activeSubTray != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                color = NightChromeSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = NightHairline, thickness = 0.7.dp)
                    when (activeSubTray) {
                        BrowserSubTray.FIND -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Surface(
                                    color = NightAddressPill,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(0.75.dp, NeonCyan.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Search,
                                            contentDescription = null,
                                            tint = NeonCyan,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        BasicTextField(
                                            value = findQuery,
                                            onValueChange = { runFindInPage(it) },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 12.sp,
                                                color = ChromeSilver,
                                            ),
                                            cursorBrush = SolidColor(NeonCyan),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("browser_find_input"),
                                        ) { inner ->
                                            if (findQuery.isEmpty()) {
                                                Text(
                                                    text = "Find in page...",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                                    color = MutedSlate,
                                                )
                                            } else {
                                                inner()
                                            }
                                        }
                                        if (findQuery.isNotEmpty()) {
                                            Text(
                                                text = "$findMatchIndex/$findTotalMatches",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = if (findTotalMatches > 0) NeonCyan else MutedSlate,
                                            )
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = { webViews[activeTab.id]?.findNext(false) },
                                    enabled = findTotalMatches > 0,
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowUp,
                                        contentDescription = "Previous match",
                                        tint = if (findTotalMatches > 0) ChromeSilver else DimSlate,
                                    )
                                }
                                IconButton(
                                    onClick = { webViews[activeTab.id]?.findNext(true) },
                                    enabled = findTotalMatches > 0,
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "Next match",
                                        tint = if (findTotalMatches > 0) ChromeSilver else DimSlate,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        webViews[activeTab.id]?.clearMatches()
                                        findQuery = ""
                                        findMatchIndex = 0
                                        findTotalMatches = 0
                                        activeSubTray = null
                                    },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Close find",
                                        tint = MutedSlate,
                                    )
                                }
                            }
                        }

                        null -> Unit
                    }
                }
            }
        }

        // (Settings popup & Samsung-Browser-style Tabs overview are rendered at root Box level)

        // 7. Compact Nocturnal Bottom Navigation Bar (Back, Forward, Home, Pull, Cookies, [Tabs], More)
        HorizontalDivider(color = NightHairline, thickness = 0.7.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NightChromeSurface)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(
                onClick = { webViews[activeTab.id]?.let { if (it.canGoBack()) it.goBack() } },
                enabled = !activeTab.isHome && activeTab.canGoBack,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("browser_back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = "Back",
                    tint = if (!activeTab.isHome && activeTab.canGoBack) ChromeSilver else DimSlate,
                    modifier = Modifier.size(24.dp),
                )
            }

            IconButton(
                onClick = { webViews[activeTab.id]?.let { if (it.canGoForward()) it.goForward() } },
                enabled = !activeTab.isHome && activeTab.canGoForward,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("browser_forward_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Forward",
                    tint = if (!activeTab.isHome && activeTab.canGoForward) ChromeSilver else DimSlate,
                    modifier = Modifier.size(24.dp),
                )
            }

            IconButton(
                onClick = {
                    openUrlInActiveTab(HOME_TAB_URL)
                    showTabsBarTray = false
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("browser_home_button"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Home,
                    contentDescription = "Browser Home",
                    tint = if (activeTab.isHome) NeonCyan else ChromeSilver,
                    modifier = Modifier.size(22.dp),
                )
            }

            val canPullTopUrl = resolveTopBarDownloadUrl() != null

            // Pull Current Page / Top Bar URL Video (1-tap highest-quality download)
            IconButton(
                onClick = { triggerPullFromTopUrl() },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("browser_bottom_pull_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = "Download video from top URL in highest quality",
                    tint = if (canPullTopUrl) NeonCyan else ChromeSilver,
                    modifier = Modifier.size(21.dp),
                )
            }

            // Cookie Sync & Vault Status
            IconButton(
                onClick = {
                    onManualSyncCookies(if (activeTab.isHome) "https://www.instagram.com/" else activeTab.url)
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("browser_bottom_sync_button"),
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "Sync cookies",
                        tint = ChromeSilver,
                        modifier = Modifier.size(20.dp),
                    )
                    if (syncedDomains.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .offset(x = 3.dp, y = (-2).dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(EmeraldPulse),
                        )
                    }
                }
            }

            // Tab Count Badge [N]
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        if (!showTabsBarTray && !activeTab.isHome) {
                            captureTabThumbnail(activeTab.id, webViews[activeTab.id])
                        }
                        showTabsBarTray = !showTabsBarTray
                        if (showTabsBarTray) showToolsBar = false
                    }
                    .testTag("browser_tabs_count_button"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .border(
                            width = 1.5.dp,
                            color = if (showTabsBarTray) NeonCyan else ChromeSilver,
                            shape = RoundedCornerShape(6.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tabs.size.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = if (showTabsBarTray) NeonCyan else ChromeSilver,
                    )
                }
            }

            // Browser Settings & Features Menu Button (Samsung-style Popup Trigger)
            IconButton(
                onClick = {
                    showToolsBar = !showToolsBar
                    if (showToolsBar) showTabsBarTray = false
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("browser_more_button"),
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Browser settings & features",
                        tint = if (showToolsBar || zoomPercent != 100 || isDesktopMode || isVpnActive) NeonCyan else ChromeSilver,
                        modifier = Modifier.size(21.dp),
                    )
                    if (syncedDomains.isNotEmpty() || zoomPercent != 100 || isVpnActive) {
                        Box(
                            modifier = Modifier
                                .offset(x = 2.dp, y = (-1).dp)
                                .size(5.5.dp)
                                .clip(CircleShape)
                                .background(if (isVpnActive) EmeraldPulse else AmberWarning),
                        )
                    }
                }
            }
        }
    }

    // Dimmed backdrop + Samsung-Browser-Style Floating Vertical Features/Settings Card
    AnimatedVisibility(
        visible = showToolsBar,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    showToolsBar = false
                },
        )
    }

    AnimatedVisibility(
        visible = showToolsBar,
        enter = fadeIn() + scaleIn(
            initialScale = 0.9f,
            transformOrigin = TransformOrigin(0.92f, 1f),
        ),
        exit = fadeOut() + scaleOut(
            targetScale = 0.9f,
            transformOrigin = TransformOrigin(0.92f, 1f),
        ),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 10.dp, bottom = 58.dp),
    ) {
        Surface(
            color = Color(0xFF1A2130),
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(0.85.dp, GlassStrokeBright.copy(alpha = 0.22f)),
            shadowElevation = 18.dp,
            modifier = Modifier
                .width(262.dp)
                .heightIn(max = 520.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .testTag("browser_settings_popup_menu"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                // --- Section 1: Navigation, Downloads, Bookmarks & Settings ---
                BrowserMenuFeatureRow(
                    icon = Icons.Rounded.History,
                    label = "History",
                    badgeText = historyEntries.size.takeIf { it > 0 }?.toString(),
                    onClick = { openFeatureWindow(BrowserFeatureWindow.HISTORY) },
                    modifier = Modifier.testTag("browser_history_tray_button"),
                )

                val canPullFromMenu = resolveTopBarDownloadUrl() != null
                BrowserMenuFeatureRow(
                    icon = Icons.Rounded.ArrowDownward,
                    label = "Downloads",
                    iconTint = if (canPullFromMenu) NeonCyan else ChromeSilver,
                    onClick = { openFeatureWindow(BrowserFeatureWindow.DOWNLOADS) },
                    modifier = Modifier.testTag("browser_pull_video_button"),
                )

                BrowserMenuFeatureRow(
                    icon = Icons.Rounded.Bookmarks,
                    label = "Saved pages",
                    badgeText = bookmarks.size.takeIf { it > 0 }?.toString(),
                    onClick = { openFeatureWindow(BrowserFeatureWindow.BOOKMARKS) },
                    modifier = Modifier.testTag("browser_bookmarks_tray_button"),
                )

                BrowserMenuFeatureRow(
                    icon = if (isCurrentBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.Add,
                    label = "Add page to",
                    enabled = !activeTab.isHome,
                    isActive = isCurrentBookmarked || isCurrentPinnedToHome,
                    onClick = { openFeatureWindow(BrowserFeatureWindow.ADD_PAGE_TO) },
                    modifier = Modifier.testTag("browser_bookmark_toggle_button"),
                )

                BrowserMenuFeatureRow(
                    icon = Icons.Outlined.Settings,
                    label = "Settings",
                    onClick = { openFeatureWindow(BrowserFeatureWindow.SETTINGS) },
                    modifier = Modifier.testTag("browser_open_settings_window_button"),
                )

                if (enableEditableSpeedDials) {
                    BrowserMenuFeatureRow(
                        icon = Icons.Rounded.PushPin,
                        label = if (isCurrentPinnedToHome) "Pinned to home" else "Pin to home",
                        enabled = !activeTab.isHome,
                        isActive = isCurrentPinnedToHome,
                        activeAccent = EmeraldPulse,
                        onClick = {
                            val targetUrl = webViews[activeTab.id]?.url ?: activeTab.url
                            val normalizedTarget = targetUrl.trim().trimEnd('/')
                            val existingPinned = pinnedSites.firstOrNull {
                                it.url.trim().trimEnd('/').equals(normalizedTarget, ignoreCase = true)
                            }
                            if (existingPinned != null) {
                                browserPrefs.deletePinnedSite(existingPinned.id)
                                localStatusBanner = "تمت إزالة الموقع من الواجهة الرئيسية"
                            } else {
                                val addedSite = browserPrefs.addPinnedSite(activeTab.title, targetUrl)
                                if (addedSite != null) {
                                    localStatusBanner = "تم تثبيت الموقع في الواجهة الرئيسية مع جلب الشعار"
                                    if (addedSite.logoPath.isNullOrBlank()) {
                                        coroutineScope.launch {
                                            val fetched = SiteLogoFetcher.fetchAndCacheLogo(
                                                context = context,
                                                siteUrl = addedSite.url,
                                                forceRefresh = true,
                                            )
                                            if (!fetched.isNullOrBlank()) {
                                                browserPrefs.updatePinnedSiteLogo(addedSite.id, fetched)
                                            }
                                        }
                                    }
                                }
                            }
                            showToolsBar = false
                        },
                        modifier = Modifier.testTag("browser_pin_to_home_button"),
                    )
                }

                // Dotted Separator (matching Samsung Browser popup menu)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp)
                        .height(1.5.dp),
                ) {
                    drawLine(
                        color = GlassStrokeBright.copy(alpha = 0.28f),
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 10f), 0f),
                    )
                }

                // --- Section 2: Site View, Privacy & Page Tools ---
                BrowserMenuFeatureRow(
                    icon = Icons.Rounded.DesktopWindows,
                    label = "Desktop site",
                    isActive = isDesktopMode,
                    badgeText = if (isDesktopMode) "ON" else null,
                    onClick = {
                        toggleDesktopMode()
                        showToolsBar = false
                    },
                    modifier = Modifier.testTag("browser_desktop_mode_button"),
                )

                BrowserMenuFeatureRow(
                    icon = Icons.Rounded.VisibilityOff,
                    label = "Private tab",
                    iconTint = PlasmaViolet,
                    onClick = {
                        openNewTab(incognito = true)
                        showToolsBar = false
                    },
                    modifier = Modifier.testTag("browser_new_private_tab_button"),
                )

                BrowserMenuFeatureRow(
                    icon = Icons.Rounded.Block,
                    label = "Ad blockers",
                    isActive = adBlockEnabled,
                    badgeText = when {
                        !adBlockEnabled -> "OFF"
                        blockedAdsCount > 0 -> "$blockedAdsCount"
                        else -> "ON"
                    },
                    badgeColor = if (adBlockEnabled) NeonCyan else MutedSlate,
                    onClick = { openFeatureWindow(BrowserFeatureWindow.AD_BLOCKERS) },
                    modifier = Modifier.testTag("browser_adblock_button"),
                )

                BrowserMenuFeatureRow(
                    icon = Icons.Rounded.VpnLock,
                    label = "1.1.1.1 VPN",
                    isActive = isVpnActive,
                    activeAccent = EmeraldPulse,
                    badgeText = if (isVpnActive) "ON" else null,
                    badgeColor = EmeraldPulse,
                    onClick = { openFeatureWindow(BrowserFeatureWindow.VPN) },
                    modifier = Modifier.testTag("browser_vpn_1111_button"),
                )

                BrowserMenuFeatureRow(
                    icon = Icons.Rounded.VerifiedUser,
                    label = "Privacy",
                    isActive = isPermanentCookieLock || syncedDomains.isNotEmpty(),
                    activeAccent = EmeraldPulse,
                    badgeText = if (storedCookieCount > 0) "$storedCookieCount" else if (isPermanentCookieLock) "AES" else null,
                    badgeColor = EmeraldPulse,
                    onClick = { openFeatureWindow(BrowserFeatureWindow.PRIVACY) },
                    modifier = Modifier.testTag("browser_permanent_cookie_lock_button"),
                )

                BrowserMenuFeatureRow(
                    icon = Icons.Outlined.Public,
                    label = "Search engine",
                    badgeText = searchEngine.label,
                    onClick = { openFeatureWindow(BrowserFeatureWindow.SEARCH_ENGINE) },
                    modifier = Modifier.testTag("browser_search_engine_button"),
                )

                BrowserMenuFeatureRow(
                    icon = Icons.Rounded.Search,
                    label = "Find on page",
                    enabled = !activeTab.isHome,
                    isActive = activeSubTray == BrowserSubTray.FIND,
                    onClick = {
                        activeSubTray = if (activeSubTray == BrowserSubTray.FIND) {
                            webViews[activeTab.id]?.clearMatches()
                            null
                        } else {
                            BrowserSubTray.FIND
                        }
                        showToolsBar = false
                    },
                    modifier = Modifier.testTag("browser_find_button"),
                )

                // Text Size / Page Zoom Row (tap label for full Text Size window, or use quick steppers)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { openFeatureWindow(BrowserFeatureWindow.TEXT_SIZE) }
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ZoomIn,
                            contentDescription = "Text size",
                            tint = if (activeTab.isHome) DimSlate else if (zoomPercent != 100) NeonCyan else ChromeSilver,
                            modifier = Modifier.size(21.dp),
                        )
                        Spacer(modifier = Modifier.width(15.dp))
                        Text(
                            text = "Text size",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = if (activeTab.isHome) DimSlate else ChromeSilver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (activeTab.isHome) 0.03f else 0.08f))
                                .clickable(enabled = !activeTab.isHome) { zoomPageBy(-20) }
                                .testTag("browser_zoom_out_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ZoomOut,
                                contentDescription = "Zoom out page",
                                tint = if (activeTab.isHome) DimSlate else ChromeSilver,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Text(
                            text = "$zoomPercent%",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = when {
                                activeTab.isHome -> DimSlate
                                zoomPercent != 100 -> NeonCyan
                                else -> ChromeSilver
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !activeTab.isHome) { resetZoom() }
                                .padding(horizontal = 5.dp, vertical = 4.dp)
                                .testTag("browser_zoom_reset_button"),
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (activeTab.isHome) 0.03f else 0.12f))
                                .clickable(enabled = !activeTab.isHome) { zoomPageBy(+20) }
                                .testTag("browser_zoom_in_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ZoomIn,
                                contentDescription = "Zoom in page",
                                tint = if (activeTab.isHome) DimSlate else NeonCyan,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }

                BrowserMenuFeatureRow(
                    icon = Icons.Rounded.Refresh,
                    label = "Reload",
                    enabled = !activeTab.isHome,
                    onClick = {
                        webViews[activeTab.id]?.reload()
                        showToolsBar = false
                    },
                    modifier = Modifier.testTag("browser_reload_button"),
                )

                BrowserMenuFeatureRow(
                    icon = Icons.Rounded.ContentCopy,
                    label = "Copy link",
                    enabled = !activeTab.isHome,
                    onClick = {
                        copyActiveUrl()
                        showToolsBar = false
                    },
                    modifier = Modifier.testTag("browser_copy_url_button"),
                )

                BrowserMenuFeatureRow(
                    icon = Icons.Rounded.Share,
                    label = "Share",
                    enabled = !activeTab.isHome,
                    onClick = {
                        shareActiveUrl()
                        showToolsBar = false
                    },
                    modifier = Modifier.testTag("browser_share_button"),
                )
            }
        }
    }

    // Full-screen Samsung-Browser-Style Tabs Overview Overlay
    val tabPreviewItems = tabs.mapIndexed { idx, tab ->
        BrowserTabPreviewItem(
            index = idx,
            id = tab.id,
            title = tab.title,
            url = tab.url,
            isHome = tab.isHome,
            isIncognito = tab.isIncognito,
            isActive = idx == activeTabIndex,
            thumbnail = tabThumbnails[tab.id],
            favicon = tabFavicons[tab.id],
        )
    }
    BrowserTabsOverviewOverlay(
        visible = showTabsBarTray,
        tabs = tabPreviewItems,
        activeThumbnail = tabThumbnails[activeTab.id],
        onSelectTab = { idx ->
            if (!activeTab.isHome && activeTabIndex != idx) {
                captureTabThumbnail(activeTab.id, webViews[activeTab.id])
            }
            activeTabIndex = idx
            showTabsBarTray = false
        },
        onCloseTab = { idx -> closeTab(idx) },
        onAddTab = { incognito -> openNewTab(incognito = incognito) },
        onCloseAllTabs = { incognitoOnly -> closeAllTabs(incognitoOnly) },
        onDismiss = { showTabsBarTray = false },
    )

    // Real Modal Windows for Every Browser Feature & Setting
    val activePageLiveUrl = webViews[activeTab.id]?.url ?: activeTab.url
    BrowserFeatureWindowHost(
        currentWindow = activeFeatureWindow,
        canNavigateBackInWindow = featureWindowStack.size > 1,
        onNavigateToWindow = { next -> openFeatureWindow(next) },
        onBackWindow = { popFeatureWindow() },
        onCloseAllWindows = { closeAllFeatureWindows() },
        isHomeTab = activeTab.isHome,
        activePageUrl = activePageLiveUrl,
        activePageTitle = activeTab.title,
        isCurrentBookmarked = isCurrentBookmarked,
        isCurrentPinnedToHome = isCurrentPinnedToHome,
        enableEditableSpeedDials = enableEditableSpeedDials,
        activeDetectedVideo = if (activeTab.isHome) null else activeDetectedVideo,
        statusMessage = statusMessage ?: localStatusBanner,
        historyEntries = historyEntries,
        bookmarks = bookmarks,
        adBlockEnabled = adBlockEnabled,
        blockedAdsCount = blockedAdsCount,
        isVpnActive = isVpnActive,
        isDesktopMode = isDesktopMode,
        isPermanentCookieLock = isPermanentCookieLock,
        syncedDomains = syncedDomains,
        storedCookieCount = storedCookieCount,
        searchEngine = searchEngine,
        zoomPercent = zoomPercent,
        onOpenUrl = { url -> openUrlInActiveTab(url) },
        onRemoveHistoryEntry = { url -> browserPrefs.removeHistoryEntry(url) },
        onClearHistory = { browserPrefs.clearHistory() },
        onRemoveBookmark = { url -> browserPrefs.removeBookmark(url) },
        onToggleBookmark = {
            val targetUrl = webViews[activeTab.id]?.url ?: activeTab.url
            val added = browserPrefs.toggleBookmark(targetUrl, activeTab.title)
            localStatusBanner = if (added) "Bookmark saved" else "Bookmark removed"
        },
        onTogglePinToHome = {
            val targetUrl = webViews[activeTab.id]?.url ?: activeTab.url
            val normalizedTarget = targetUrl.trim().trimEnd('/')
            val existingPinned = pinnedSites.firstOrNull {
                it.url.trim().trimEnd('/').equals(normalizedTarget, ignoreCase = true)
            }
            if (existingPinned != null) {
                browserPrefs.deletePinnedSite(existingPinned.id)
                localStatusBanner = "تمت إزالة الموقع من الواجهة الرئيسية"
            } else {
                val addedSite = browserPrefs.addPinnedSite(activeTab.title, targetUrl)
                if (addedSite != null) {
                    localStatusBanner = "تم تثبيت الموقع في الواجهة الرئيسية مع جلب الشعار"
                    if (addedSite.logoPath.isNullOrBlank()) {
                        coroutineScope.launch {
                            val fetched = SiteLogoFetcher.fetchAndCacheLogo(
                                context = context,
                                siteUrl = addedSite.url,
                                forceRefresh = true,
                            )
                            if (!fetched.isNullOrBlank()) {
                                browserPrefs.updatePinnedSiteLogo(addedSite.id, fetched)
                            }
                        }
                    }
                }
            }
        },
        onResetPinnedSites = {
            browserPrefs.resetPinnedSitesToDefault()
            localStatusBanner = "تمت استعادة المواقع المثبتة الافتراضية"
        },
        onPullCurrentPage = { triggerPullFromTopUrl() },
        onPullCustomUrl = { custom -> triggerPullFromTopUrl(explicitUrl = custom) },
        onCopyUrl = { copyActiveUrl() },
        onShareUrl = { shareActiveUrl() },
        onToggleDesktopMode = { toggleDesktopMode() },
        onOpenPrivateTab = { openNewTab(incognito = true) },
        onSetAdBlockEnabled = { enabled ->
            browserPrefs.setAdBlockEnabled(enabled)
            localStatusBanner = if (enabled) "Ad blocker enabled" else "Ad blocker paused"
        },
        onReloadCurrentPage = { webViews[activeTab.id]?.reload() },
        onToggleVpn = { toggleCloudflareVpn() },
        onTogglePermanentCookieLock = { onTogglePermanentCookieLock() },
        onManualSyncCookies = {
            onManualSyncCookies(if (activeTab.isHome) "https://www.instagram.com/" else activePageLiveUrl)
        },
        onClearCookies = { onClearCookies() },
        onClearBrowserCache = {
            webViews.values.forEach { wv -> runCatching { wv.clearCache(true) } }
            browserPrefs.clearHistory()
            localStatusBanner = "Browser cache & history cleared"
        },
        onSelectSearchEngine = { engine ->
            browserPrefs.setSearchEngine(engine)
            localStatusBanner = "Search engine: ${engine.label}"
        },
        onSetZoomPercent = { pct -> applyZoomPercent(pct) },
        onResetZoom = { resetZoom() },
    )
    }
}

@Composable
private fun BrowserMenuFeatureRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isActive: Boolean = false,
    activeAccent: Color = NeonCyan,
    iconTint: Color? = null,
    textColor: Color? = null,
    badgeText: String? = null,
    badgeColor: Color = NeonCyan,
) {
    val resolvedIconTint = when {
        !enabled -> DimSlate
        iconTint != null -> iconTint
        isActive -> activeAccent
        else -> ChromeSilver
    }
    val resolvedTextColor = when {
        !enabled -> DimSlate
        textColor != null -> textColor
        isActive -> activeAccent
        else -> ChromeSilver
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = resolvedIconTint,
                modifier = Modifier.size(21.dp),
            )
            Spacer(modifier = Modifier.width(15.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = resolvedTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!badgeText.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = badgeColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(0.6.dp, badgeColor.copy(alpha = 0.45f)),
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SamsungStartPageHome(
    pinnedSites: List<BrowserPinnedSite>,
    enableEditableSpeedDials: Boolean,
    syncedDomainsCount: Int,
    storedCookieCount: Int,
    isPermanentCookieLock: Boolean,
    showBanner: Boolean,
    onDismissBanner: () -> Unit,
    onOpenShortcut: (String) -> Unit,
    onToggleTools: () -> Unit,
    onManualSync: () -> Unit,
    onAddPinnedSite: (label: String, url: String, logoPath: String?) -> Boolean,
    onUpdatePinnedSite: (id: String, label: String, url: String, logoPath: String?) -> Boolean,
    onDeletePinnedSite: (id: String) -> Boolean,
) {
    val context = LocalContext.current
    var isEditingPinnedSites by remember { mutableStateOf(false) }
    var editingSite by remember { mutableStateOf<BrowserPinnedSite?>(null) }
    var showSiteEditorDialog by remember { mutableStateOf(false) }
    var siteLabelInput by remember { mutableStateOf("") }
    var siteUrlInput by remember { mutableStateOf("") }
    var siteEditorError by remember { mutableStateOf<String?>(null) }
    var previewLogoPath by remember { mutableStateOf<String?>(null) }
    var isFetchingPreviewLogo by remember { mutableStateOf(false) }

    fun openAddSiteModal() {
        editingSite = null
        siteLabelInput = ""
        siteUrlInput = "https://"
        siteEditorError = null
        previewLogoPath = null
        isFetchingPreviewLogo = false
        showSiteEditorDialog = true
    }

    fun openEditSiteModal(site: BrowserPinnedSite) {
        editingSite = site
        siteLabelInput = site.label
        siteUrlInput = site.url
        siteEditorError = null
        previewLogoPath = site.logoPath
        isFetchingPreviewLogo = false
        showSiteEditorDialog = true
    }

    // Live automatic logo preview while typing/pasting site URL in the add/edit dialog
    LaunchedEffect(showSiteEditorDialog, siteUrlInput) {
        if (!showSiteEditorDialog) return@LaunchedEffect
        val trimmed = siteUrlInput.trim()
        val candidateUrl = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> null
        }
        val hostPart = candidateUrl?.substringAfter("://")?.substringBefore("/")?.trim().orEmpty()
        if (candidateUrl == null || !hostPart.contains(".") || hostPart.endsWith(".")) {
            previewLogoPath = editingSite?.takeIf { it.url.equals(candidateUrl, ignoreCase = true) }?.logoPath
            isFetchingPreviewLogo = false
            return@LaunchedEffect
        }
        val immediateCache = SiteLogoFetcher.getCachedLogoFile(context, candidateUrl)?.absolutePath
        if (immediateCache != null) {
            previewLogoPath = immediateCache
            isFetchingPreviewLogo = false
            return@LaunchedEffect
        }
        isFetchingPreviewLogo = true
        delay(420L)
        val fetched = SiteLogoFetcher.fetchAndCacheLogoDeduplicated(context, candidateUrl)
        previewLogoPath = fetched
        isFetchingPreviewLogo = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF060911),
                        Color(0xFF0A0F1A),
                        ObsidianVoid,
                    ),
                ),
            )
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            PlasmaViolet.copy(alpha = 0.14f),
                            NeonCyan.copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.82f, size.height * 0.18f),
                        radius = size.minDimension * 0.65f,
                    ),
                    radius = size.minDimension * 0.65f,
                    center = Offset(size.width * 0.82f, size.height * 0.18f),
                )
            }
            .testTag("browser_start_page_home"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            // Top Controls (Editable Pinned Sites Actions on Left + Tools/Zoom & Vault Badge on Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (enableEditableSpeedDials) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            color = NeonCyan.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(0.8.dp, NeonCyan.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { openAddSiteModal() }
                                .testTag("browser_add_pinned_site_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "إضافة موقع مثبت",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "إضافة موقع",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = NeonCyan,
                                )
                            }
                        }

                        Surface(
                            color = if (isEditingPinnedSites) PlasmaViolet.copy(alpha = 0.22f) else NightCardSurface,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                0.8.dp,
                                if (isEditingPinnedSites) PlasmaViolet.copy(alpha = 0.7f) else GlassStrokeBright.copy(alpha = 0.25f),
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { isEditingPinnedSites = !isEditingPinnedSites }
                                .testTag("browser_edit_pinned_sites_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = "تعديل المواقع المثبتة",
                                    tint = if (isEditingPinnedSites) PlasmaViolet else ChromeSilver,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isEditingPinnedSites) "تم" else "تعديل",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isEditingPinnedSites) PlasmaViolet else ChromeSilver,
                                )
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleTools,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("browser_start_settings_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "Browser settings & features",
                            tint = ChromeSilver,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = if (syncedDomainsCount > 0) EmeraldPulse.copy(alpha = 0.16f) else NightCardSurface,
                        shape = CircleShape,
                        border = BorderStroke(
                            0.85.dp,
                            if (syncedDomainsCount > 0) EmeraldPulse.copy(alpha = 0.7f) else GlassStrokeBright.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { onManualSync() }
                            .testTag("browser_home_vault_badge"),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = syncedDomainsCount.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (syncedDomainsCount > 0) EmeraldPulse else ChromeSilver,
                            )
                        }
                    }
                }
            }

            // Nocturnal Liquid Glass Feature Banner Card (Dismissible via X, Zero Ads/Popups)
            AnimatedVisibility(visible = showBanner) {
                Surface(
                    color = NightCardSurface.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(0.8.dp, GlassStrokeBright.copy(alpha = 0.24f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        // Subtle glowing ringed blackhole ornament on right
                        Canvas(
                            modifier = Modifier
                                .size(86.dp)
                                .align(Alignment.CenterEnd)
                                .offset(x = 10.dp, y = 4.dp),
                        ) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        ObsidianVoid,
                                        Color(0xFF10192B),
                                        NeonCyan.copy(alpha = 0.22f),
                                    ),
                                ),
                                radius = size.minDimension * 0.34f,
                            )
                            drawCircle(
                                color = NeonCyan.copy(alpha = 0.4f),
                                radius = size.minDimension * 0.34f,
                                style = Stroke(width = 1.2.dp.toPx()),
                            )
                            rotate(-28f) {
                                drawOval(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            NeonCyan.copy(alpha = 0.85f),
                                            PlasmaViolet.copy(alpha = 0.85f),
                                            EmeraldPulse.copy(alpha = 0.55f),
                                        ),
                                    ),
                                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                                    topLeft = Offset(size.width * 0.05f, size.height * 0.35f),
                                    size = androidx.compose.ui.geometry.Size(size.width * 0.9f, size.height * 0.3f),
                                )
                            }
                        }

                        // Dismiss X top-right
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.07f))
                                .clickable { onDismissBanner() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Dismiss banner",
                                tint = MutedSlate,
                                modifier = Modifier.size(14.dp),
                            )
                        }

                        Column(modifier = Modifier.fillMaxWidth(0.72f)) {
                            Text(
                                text = "Blackhole Browser",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                ),
                                color = ChromeSilver,
                            )
                            Text(
                                text = if (isPermanentCookieLock) "AES-256 Forever Vault" else "for Session Vault",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = NeonCyan,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (storedCookieCount > 0) {
                                    "$storedCookieCount cookies encrypted & locked forever across $syncedDomainsCount domains."
                                } else {
                                    "Sign in once — AES-256 encrypted & saved forever for effortless downloads."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedSlate,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = NeonCyan.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(0.8.dp, NeonCyan.copy(alpha = 0.55f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onManualSync() },
                            ) {
                                Text(
                                    text = if (storedCookieCount > 0) "Sync forever vault" else "Sync session",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = NeonCyan,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            val displayedSpeedDials = remember(pinnedSites, enableEditableSpeedDials) {
                if (enableEditableSpeedDials) {
                    pinnedSites
                } else {
                    StartPageSpeedDials.map { item ->
                        BrowserPinnedSite(
                            id = item.label,
                            label = item.label,
                            url = item.url,
                            glyph = item.glyph,
                            colorStartHex = item.tileColors.first().value.toLong(),
                            colorEndHex = item.tileColors.last().value.toLong(),
                            isPlayIcon = item.isPlayIcon,
                        )
                    }
                }
            }

            // 5-Column Nocturnal Quick Access Speed Dial Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(displayedSpeedDials, key = { it.id }) { item ->
                    val tileColors = if (enableEditableSpeedDials) {
                        listOf(Color(item.colorStartHex), Color(item.colorEndHex))
                    } else {
                        StartPageSpeedDials.firstOrNull { it.label == item.label }?.tileColors
                            ?: listOf(Color(item.colorStartHex), Color(item.colorEndHex))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = {
                                    if (enableEditableSpeedDials && isEditingPinnedSites) {
                                        openEditSiteModal(item)
                                    } else {
                                        onOpenShortcut(item.url)
                                    }
                                },
                                onLongClick = if (enableEditableSpeedDials) {
                                    { openEditSiteModal(item) }
                                } else {
                                    null
                                },
                            )
                            .padding(vertical = 4.dp)
                            .testTag("speed_dial_${item.label.lowercase().replace(" ", "_")}"),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SpeedDialSiteIconBox(
                                label = item.label,
                                glyph = item.glyph,
                                isPlayIcon = item.isPlayIcon,
                                logoPath = item.logoPath,
                                tileColors = tileColors,
                                isEditing = isEditingPinnedSites && enableEditableSpeedDials,
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                color = ChromeSilver.copy(alpha = 0.9f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }

                        if (enableEditableSpeedDials && isEditingPinnedSites) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(CrimsonAlert)
                                    .clickable { onDeletePinnedSite(item.id) }
                                    .testTag("speed_dial_delete_${item.id}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "حذف ${item.label}",
                                    tint = Color.White,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(NeonCyan)
                                    .clickable { openEditSiteModal(item) }
                                    .testTag("speed_dial_edit_${item.id}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = "تعديل ${item.label}",
                                    tint = ObsidianVoid,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                    }
                }

                if (enableEditableSpeedDials) {
                    item(key = "add_new_pinned_site_tile") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { openAddSiteModal() }
                                .padding(vertical = 4.dp)
                                .testTag("speed_dial_add_tile"),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(NeonCyan.copy(alpha = 0.12f))
                                    .border(
                                        width = 1.dp,
                                        color = NeonCyan.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(15.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "إضافة موقع جديد",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = "إضافة موقع",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                color = NeonCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        // Inline Nocturnal Pinned Site Add/Edit Sheet
        if (enableEditableSpeedDials && showSiteEditorDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianVoid.copy(alpha = 0.78f))
                    .clickable { showSiteEditorDialog = false }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color(0xFF0E1522),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 460.dp)
                        .clickable(enabled = false) {},
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    val previewTitle = siteLabelInput.trim().ifBlank {
                                        siteUrlInput.substringAfter("://")
                                            .substringBefore("/")
                                            .removePrefix("www.")
                                            .substringBefore(".")
                                            .ifBlank { "Web" }
                                    }
                                    SpeedDialSiteIconBox(
                                        label = previewTitle,
                                        glyph = previewTitle.take(2).replaceFirstChar { it.uppercaseChar() },
                                        isPlayIcon = false,
                                        logoPath = previewLogoPath,
                                        tileColors = listOf(Color(0xFF152238), Color(0xFF0E1727)),
                                        isEditing = false,
                                    )
                                    if (isFetchingPreviewLogo) {
                                        CircularProgressIndicator(
                                            color = NeonCyan,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                                Column {
                                    Text(
                                        text = if (editingSite == null) "إضافة موقع جديد" else "تعديل الموقع المثبت",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = ChromeSilver,
                                    )
                                    Text(
                                        text = when {
                                            isFetchingPreviewLogo -> "جاري جلب شعار الموقع تلقائياً..."
                                            !previewLogoPath.isNullOrBlank() -> "تم جلب الشعار تلقائياً"
                                            else -> "سيتم جلب الشعار تلقائياً عند إدخال الرابط"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (!previewLogoPath.isNullOrBlank()) EmeraldPulse else MutedSlate,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showSiteEditorDialog = false },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "إغلاق",
                                    tint = MutedSlate,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = siteLabelInput,
                            onValueChange = {
                                siteLabelInput = it
                                siteEditorError = null
                            },
                            label = { Text("اسم الموقع") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = GlassStrokeBright.copy(alpha = 0.35f),
                                focusedTextColor = ChromeSilver,
                                unfocusedTextColor = ChromeSilver,
                                focusedLabelColor = NeonCyan,
                                unfocusedLabelColor = MutedSlate,
                                cursorColor = NeonCyan,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pinned_site_name_input"),
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = siteUrlInput,
                            onValueChange = {
                                siteUrlInput = it
                                siteEditorError = null
                            },
                            label = { Text("رابط الموقع (URL)") },
                            placeholder = { Text("https://example.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = GlassStrokeBright.copy(alpha = 0.35f),
                                focusedTextColor = ChromeSilver,
                                unfocusedTextColor = ChromeSilver,
                                focusedLabelColor = NeonCyan,
                                unfocusedLabelColor = MutedSlate,
                                cursorColor = NeonCyan,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pinned_site_url_input"),
                        )

                        if (!siteEditorError.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = siteEditorError!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = CrimsonAlert,
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val currentEdit = editingSite
                            if (currentEdit != null) {
                                OutlinedButton(
                                    onClick = {
                                        onDeletePinnedSite(currentEdit.id)
                                        showSiteEditorDialog = false
                                    },
                                    border = BorderStroke(0.9.dp, CrimsonAlert.copy(alpha = 0.7f)),
                                    modifier = Modifier.testTag("pinned_site_delete_button"),
                                ) {
                                    Text("حذف", color = CrimsonAlert)
                                }
                            }
                            OutlinedButton(
                                onClick = { showSiteEditorDialog = false },
                                border = BorderStroke(0.9.dp, GlassStrokeBright.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("pinned_site_cancel_button"),
                            ) {
                                Text("إلغاء", color = ChromeSilver)
                            }
                            Button(
                                onClick = {
                                    val saved = if (currentEdit == null) {
                                        onAddPinnedSite(siteLabelInput, siteUrlInput, previewLogoPath)
                                    } else {
                                        onUpdatePinnedSite(currentEdit.id, siteLabelInput, siteUrlInput, previewLogoPath)
                                    }
                                    if (saved) {
                                        showSiteEditorDialog = false
                                    } else {
                                        siteEditorError = "أدخل رابط موقع صحيح مثل https://example.com"
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonCyan,
                                    contentColor = ObsidianVoid,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("pinned_site_save_button"),
                            ) {
                                Text("حفظ", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DecodedSiteLogo(
    val bitmap: Bitmap,
    val hasTransparentEdges: Boolean,
    val needsLightCardBacking: Boolean,
)

@Composable
private fun SpeedDialSiteIconBox(
    label: String,
    glyph: String,
    isPlayIcon: Boolean,
    logoPath: String?,
    tileColors: List<Color>,
    isEditing: Boolean,
) {
    var decodedLogo by remember(logoPath) { mutableStateOf<DecodedSiteLogo?>(null) }

    LaunchedEffect(logoPath) {
        if (logoPath.isNullOrBlank()) {
            decodedLogo = null
            return@LaunchedEffect
        }
        decodedLogo = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(logoPath)
                if (!file.exists() || file.length() <= 100L) return@runCatching null
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
                analyzeDecodedSiteLogo(bmp)
            }.getOrNull()
        }
    }

    val logo = decodedLogo
    val tileShape = RoundedCornerShape(15.dp)
    val borderColor = if (isEditing) NeonCyan.copy(alpha = 0.65f) else GlassStrokeBright.copy(alpha = 0.24f)
    val borderWidth = if (isEditing) 1.2.dp else 0.75.dp

    if (logo != null) {
        val backdropColor = when {
            !logo.hasTransparentEdges -> Color.Transparent
            logo.needsLightCardBacking -> Color(0xFFF3F6FA)
            else -> Color(0xFF131D2E)
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(tileShape)
                .background(backdropColor)
                .border(width = borderWidth, color = borderColor, shape = tileShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = logo.bitmap.asImageBitmap(),
                contentDescription = label,
                contentScale = if (logo.hasTransparentEdges) ContentScale.Fit else ContentScale.Crop,
                modifier = if (logo.hasTransparentEdges) {
                    Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                } else {
                    Modifier.fillMaxSize()
                },
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(tileShape)
                .background(
                    if (tileColors.size > 1) {
                        Brush.linearGradient(tileColors)
                    } else {
                        SolidColor(tileColors.first())
                    },
                )
                .border(width = borderWidth, color = borderColor, shape = tileShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isPlayIcon) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            } else {
                Text(
                    text = glyph,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = if (glyph.length > 2) 14.sp else 19.sp,
                    ),
                    color = Color.White,
                )
            }
        }
    }
}

private fun analyzeDecodedSiteLogo(bitmap: Bitmap): DecodedSiteLogo {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) {
        return DecodedSiteLogo(bitmap = bitmap, hasTransparentEdges = true, needsLightCardBacking = true)
    }
    var transparentSamples = 0
    val corners = listOf(
        0 to 0,
        (w - 1) to 0,
        0 to (h - 1),
        (w - 1) to (h - 1),
    )
    for ((cx, cy) in corners) {
        val alpha = android.graphics.Color.alpha(bitmap.getPixel(cx, cy))
        if (alpha < 210) transparentSamples++
    }
    val hasTransparentEdges = transparentSamples >= 2
    if (!hasTransparentEdges) {
        return DecodedSiteLogo(bitmap = bitmap, hasTransparentEdges = false, needsLightCardBacking = false)
    }

    var lumSum = 0.0
    var opaqueCount = 0
    val stepX = (w / 10).coerceAtLeast(1)
    val stepY = (h / 10).coerceAtLeast(1)
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val px = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(px) > 120) {
                val r = android.graphics.Color.red(px) / 255.0
                val g = android.graphics.Color.green(px) / 255.0
                val b = android.graphics.Color.blue(px) / 255.0
                lumSum += (0.299 * r + 0.587 * g + 0.114 * b)
                opaqueCount++
            }
            x += stepX
        }
        y += stepY
    }
    val avgLum = if (opaqueCount > 0) lumSum / opaqueCount else 0.5
    return DecodedSiteLogo(
        bitmap = bitmap,
        hasTransparentEdges = true,
        needsLightCardBacking = avgLum < 0.72,
    )
}

private val AD_TRACKER_HOSTS = setOf(
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "adservice.google.com",
    "pagead2.googlesyndication.com",
    "google-analytics.com",
    "googletagmanager.com",
    "scorecardresearch.com",
    "taboola.com",
    "outbrain.com",
    "ads.twitter.com",
    "analytics.tiktok.com",
    "adnxs.com",
    "pubmatic.com",
    "criteo.com",
    "quantserve.com",
    "moatads.com",
    "rubiconproject.com",
    "openx.net",
    "adsrvr.org",
    "amazon-adsystem.com",
    "advertising.com",
)

private fun isAdOrTrackerRequest(request: WebResourceRequest?): Boolean {
    if (request == null || request.isForMainFrame) return false
    val uri = request.url ?: return false
    val host = uri.host?.lowercase() ?: return false
    if (host == "connect.facebook.net" && uri.path.orEmpty().contains("signals")) return true
    return AD_TRACKER_HOSTS.any { blocked ->
        host == blocked || host.endsWith(".$blocked")
    }
}

private fun isInterceptableVideoStream(url: String): Boolean {
    val lower = url.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val pathOnly = lower.substringBefore("?").substringBefore("#")
    if (pathOnly.endsWith(".ts") || pathOnly.endsWith(".m4s") || pathOnly.endsWith(".vtt") ||
        pathOnly.endsWith(".jpg") || pathOnly.endsWith(".jpeg") || pathOnly.endsWith(".png") ||
        pathOnly.endsWith(".webp") || pathOnly.endsWith(".css") || pathOnly.endsWith(".js")
    ) {
        return false
    }
    return pathOnly.endsWith(".m3u8") ||
        pathOnly.endsWith(".mp4") ||
        pathOnly.endsWith(".m4v") ||
        pathOnly.endsWith(".webm") ||
        pathOnly.endsWith(".mov") ||
        lower.contains(".m3u8?") ||
        lower.contains(".mp4?") ||
        lower.contains("mime=video") ||
        lower.contains("mime_type=video") ||
        lower.contains("video/mp4")
}

private fun isInterceptableAudioStream(url: String): Boolean {
    val lower = url.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val pathOnly = lower.substringBefore("?").substringBefore("#")
    return pathOnly.endsWith(".m4a") ||
        pathOnly.endsWith(".aac") ||
        lower.contains("mime=audio") ||
        lower.contains("mime_type=audio")
}

private fun deriveHostLabel(url: String): String {
    return url.substringAfter("://")
        .substringBefore("/")
        .removePrefix("www.")
        .removePrefix("m.")
        .ifBlank { "Web" }
}

private fun canonicalRouteIdentity(url: String): String {
    val clean = url.trim().substringBefore("#")
    if (!clean.startsWith("http://", ignoreCase = true) &&
        !clean.startsWith("https://", ignoreCase = true)
    ) {
        return ""
    }
    val uri = runCatching { Uri.parse(clean) }.getOrNull() ?: return clean.substringBefore("?")
    val host = uri.host?.lowercase().orEmpty().removePrefix("www.")
    val path = uri.encodedPath.orEmpty().trimEnd('/')
    val keyParams = listOf("v", "id", "vid", "video_id", "story_fbid", "p", "q", "list")
        .mapNotNull { key ->
            uri.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let { "$key=$it" }
        }
        .joinToString("&")
    return if (keyParams.isNotBlank()) "$host$path?$keyParams" else "$host$path"
}
