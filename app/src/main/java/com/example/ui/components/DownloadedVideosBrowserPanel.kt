package com.example.ui.components

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.engine.network.ClientSpoofer
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NeonCyan
import java.util.UUID

private const val HOME_TAB_URL = "about:blackhole-home"

private data class BrowserTabState(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New tab",
    val url: String = HOME_TAB_URL,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
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
    SpeedDialShortcut("YT Sign-In", "G", "https://accounts.google.com/ServiceLogin?service=youtube", listOf(Color(0xFF4285F4), Color(0xFF2B6DE0))),
    SpeedDialShortcut("IG Login", "IG+", "https://www.instagram.com/accounts/login/", listOf(Color(0xFFC13584), Color(0xFF833AB4))),
    SpeedDialShortcut("FB Login", "FB+", "https://m.facebook.com/login/", listOf(Color(0xFF2563EB), Color(0xFF1D4ED8))),
    SpeedDialShortcut("Vimeo", "v", "https://vimeo.com/", listOf(Color(0xFF1AB7EA), Color(0xFF0F94C2))),
    SpeedDialShortcut("Reddit", "R", "https://www.reddit.com/", listOf(Color(0xFFFF4500), Color(0xFFD93800))),
    SpeedDialShortcut("IG Reels", "Rls", "https://www.instagram.com/reels/", listOf(Color(0xFFE4405F), Color(0xFFC92A4C))),
    SpeedDialShortcut("FB Watch", "W", "https://m.facebook.com/watch/", listOf(Color(0xFF3B5998), Color(0xFF283E6E))),
    SpeedDialShortcut("YT Shorts", "S", "https://m.youtube.com/shorts/", listOf(Color(0xFFFF1744), Color(0xFFD50000))),
    SpeedDialShortcut("Google", "G", "https://www.google.com/", listOf(Color(0xFF7B8088), Color(0xFF5F6368))),
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

// Samsung Internet light chrome + warm dark nebula start page palette
private val ChromeBarSurface = Color(0xFFF3F4F6)
private val ChromePillWhite = Color(0xFFFFFFFF)
private val ChromeTabInactive = Color(0xFFE5E7EB)
private val ChromeTextPrimary = Color(0xFF1A1C1E)
private val ChromeTextMuted = Color(0xFF5F6368)
private val ChromeDivider = Color(0xFFDDE0E4)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DownloadedVideosBrowserPanel(
    syncedDomains: List<String>,
    statusMessage: String?,
    onAutoSyncCookies: (String) -> Unit,
    onManualSyncCookies: (String) -> Unit,
    onPullCurrentPage: (String) -> Unit,
    onDismissStatus: () -> Unit,
    onBindWebViewRef: (WebView?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = remember { mutableStateListOf(BrowserTabState()) }
    var activeTabIndex by remember { mutableIntStateOf(0) }
    val activeTab = tabs.getOrElse(activeTabIndex) { tabs.first() }

    val webViews = remember { mutableStateMapOf<String, WebView>() }
    var addressText by remember { mutableStateOf("") }
    var isUrlFocused by remember { mutableStateOf(false) }
    var showPromoBanner by remember { mutableStateOf(true) }
    var showToolsBar by remember { mutableStateOf(false) }
    var showTabsBarTray by remember { mutableStateOf(false) }

    var zoomPercent by remember { mutableIntStateOf(100) }
    var cssFallbackZoom by remember { mutableIntStateOf(100) }
    var baseScale by remember { mutableStateOf<Float?>(null) }

    val activeWebView = webViews[activeTab.id]

    LaunchedEffect(activeTab.id, activeTab.url, activeWebView) {
        onBindWebViewRef(if (activeTab.isHome) null else activeWebView)
        if (!isUrlFocused) {
            addressText = if (activeTab.isHome) "" else activeTab.url
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onBindWebViewRef(null)
            webViews.values.forEach { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.destroy()
                }
            }
            webViews.clear()
        }
    }

    fun updateTabById(tabId: String, transform: (BrowserTabState) -> BrowserTabState) {
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0) {
            tabs[idx] = transform(tabs[idx])
        }
    }

    fun normalizeInputToUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return HOME_TAB_URL
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${trimmed.replace(" ", "+")}"
        }
    }

    fun openUrlInActiveTab(raw: String) {
        val normalized = normalizeInputToUrl(raw)
        if (normalized == HOME_TAB_URL) {
            updateTabById(activeTab.id) {
                it.copy(title = "New tab", url = HOME_TAB_URL, progress = 0, canGoBack = false, canGoForward = false)
            }
            addressText = ""
            onBindWebViewRef(null)
            return
        }
        val tabId = activeTab.id
        baseScale = null
        zoomPercent = 100
        cssFallbackZoom = 100
        addressText = normalized
        updateTabById(tabId) {
            it.copy(url = normalized, title = deriveHostLabel(normalized), progress = 12)
        }
        webViews[tabId]?.loadUrl(normalized)
    }

    fun openNewTab(initialUrl: String = HOME_TAB_URL) {
        val tab = BrowserTabState(
            title = if (initialUrl == HOME_TAB_URL) "New tab" else deriveHostLabel(initialUrl),
            url = initialUrl,
        )
        tabs.add(tab)
        activeTabIndex = tabs.lastIndex
        baseScale = null
        zoomPercent = 100
        cssFallbackZoom = 100
        showTabsBarTray = false
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val removed = tabs[index]
        webViews.remove(removed.id)?.let { wv ->
            runCatching {
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ChromeBarSurface)
            .testTag("downloaded_videos_browser_panel"),
    ) {
        // 1. Samsung-Style Top Tab Bar + New Tab (+) Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ChromeBarSurface)
                .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = index == activeTabIndex
                    Surface(
                        color = if (isSelected) ChromePillWhite else ChromeTabInactive,
                        shape = RoundedCornerShape(50),
                        shadowElevation = if (isSelected) 1.5.dp else 0.dp,
                        border = BorderStroke(
                            width = 0.6.dp,
                            color = if (isSelected) ChromeDivider else Color.Transparent,
                        ),
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable { activeTabIndex = index }
                            .testTag("browser_tab_${index}"),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Public,
                                contentDescription = null,
                                tint = if (isSelected) ChromeTextPrimary else ChromeTextMuted,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tab.title.ifBlank { "New tab" },
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                color = if (isSelected) ChromeTextPrimary else ChromeTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(88.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .clickable { closeTab(index) }
                                    .testTag("browser_close_tab_${index}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close tab",
                                    tint = ChromeTextMuted,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }

                // + Add New Tab Button
                Surface(
                    color = ChromeTabInactive.copy(alpha = 0.8f),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { openNewTab() }
                        .testTag("browser_new_tab_button"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "New tab",
                            tint = ChromeTextPrimary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }

        // 2. Samsung-Style Rounded Search / URL Pill Bar
        Surface(
            color = ChromePillWhite,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 1.dp,
            border = BorderStroke(0.75.dp, ChromeDivider),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 8.dp)
                .height(44.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!activeTab.isHome || isUrlFocused) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = ChromeTextMuted,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
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
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = ChromeTextPrimary,
                        ),
                        cursorBrush = SolidColor(Color(0xFF2563EB)),
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
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 15.sp,
                                    color = ChromeTextMuted,
                                ),
                                textAlign = if (!isUrlFocused && activeTab.isHome) TextAlign.Center else TextAlign.Start,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            innerTextField()
                        }
                    }
                }

                if (!activeTab.isHome) {
                    if (zoomPercent != 100) {
                        Text(
                            text = "$zoomPercent%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2563EB),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showToolsBar = !showToolsBar }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { webViews[activeTab.id]?.reload() }
                            .testTag("browser_url_reload_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Reload page",
                            tint = ChromeTextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        if (!activeTab.isHome && activeTab.progress in 1..99) {
            LinearProgressIndicator(
                progress = { activeTab.progress / 100f },
                color = Color(0xFF2563EB),
                trackColor = ChromeDivider,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }

        // 3. Browser Viewport: Samsung-Style Start Page OR Live WebView Page
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (activeTab.isHome) {
                SamsungStartPageHome(
                    syncedDomainsCount = syncedDomains.size,
                    showBanner = showPromoBanner,
                    onDismissBanner = { showPromoBanner = false },
                    onOpenShortcut = { shortcutUrl -> openUrlInActiveTab(shortcutUrl) },
                    onToggleTools = { showToolsBar = !showToolsBar },
                    onManualSync = { onManualSyncCookies("https://www.instagram.com/") },
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
                            WebView(ctx).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    setSupportZoom(true)
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    mediaPlaybackRequiresUserGesture = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    userAgentString = ClientSpoofer.ANDROID_CHROME_USER_AGENT
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val nextUrl = request?.url?.toString().orEmpty()
                                        return nextUrl.isNotBlank() &&
                                            !nextUrl.startsWith("http://", ignoreCase = true) &&
                                            !nextUrl.startsWith("https://", ignoreCase = true)
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

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        val loaded = url.orEmpty()
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
                                            onAutoSyncCookies(loaded)
                                        }
                                        view?.evaluateJavascript(UNLOCK_VIEWPORT_JS, null)
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
                                }

                                webViews[tabId] = this
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
        }

        // 4. Inline Cookie Status Toast (Zero Dialog)
        AnimatedVisibility(
            visible = !statusMessage.isNullOrBlank(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            statusMessage?.let { msg ->
                Surface(
                    color = Color(0xFF101622),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 7.dp),
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
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { onDismissStatus() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Dismiss status",
                                tint = Color(0xFF9CA3AF),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        // 5. Inline Tools & Zoom Tray (Zero Popup — toggled via More / Tune)
        AnimatedVisibility(
            visible = showToolsBar,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                color = ChromeBarSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = ChromeDivider, thickness = 0.8.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Zoom Out / % Reset / Zoom In Capsule
                        Surface(
                            color = ChromePillWhite,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(0.8.dp, ChromeDivider),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { zoomPageBy(-20) },
                                    enabled = !activeTab.isHome,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .testTag("browser_zoom_out_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ZoomOut,
                                        contentDescription = "Zoom out page",
                                        tint = if (activeTab.isHome) ChromeTextMuted.copy(alpha = 0.45f) else ChromeTextPrimary,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                                Text(
                                    text = "$zoomPercent%",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (zoomPercent == 100) ChromeTextMuted else Color(0xFF2563EB),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = !activeTab.isHome) { resetZoom() }
                                        .padding(horizontal = 6.dp, vertical = 6.dp)
                                        .testTag("browser_zoom_reset_button"),
                                )
                                IconButton(
                                    onClick = { zoomPageBy(+20) },
                                    enabled = !activeTab.isHome,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .testTag("browser_zoom_in_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ZoomIn,
                                        contentDescription = "Zoom in page",
                                        tint = if (activeTab.isHome) ChromeTextMuted.copy(alpha = 0.45f) else Color(0xFF2563EB),
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }

                        // Sync Cookies Action
                        Surface(
                            color = ChromePillWhite,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(0.8.dp, ChromeDivider),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable {
                                    onManualSyncCookies(
                                        if (activeTab.isHome) "https://www.instagram.com/" else activeTab.url,
                                    )
                                }
                                .testTag("browser_sync_cookies_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Sync,
                                    contentDescription = "Sync cookies",
                                    tint = EmeraldPulse,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "SYNC COOKIES (${syncedDomains.size})",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = ChromeTextPrimary,
                                )
                            }
                        }

                        // Reload Action
                        Surface(
                            color = ChromePillWhite,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(0.8.dp, ChromeDivider),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = !activeTab.isHome) { webViews[activeTab.id]?.reload() }
                                .testTag("browser_reload_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Reload page",
                                    tint = ChromeTextPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "RELOAD",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ChromeTextPrimary,
                                )
                            }
                        }

                        // Pull Video Action
                        if (!activeTab.isHome) {
                            Surface(
                                color = Color(0xFF0E1726),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        val pullTarget = webViews[activeTab.id]?.url ?: activeTab.url
                                        onManualSyncCookies(pullTarget)
                                        onPullCurrentPage(pullTarget)
                                    }
                                    .testTag("browser_pull_video_button"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowDownward,
                                        contentDescription = "Pull video",
                                        tint = NeonCyan,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "PULL VIDEO",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Inline Tab Manager Strip (Zero Popup — toggled via [N] tab counter)
        AnimatedVisibility(
            visible = showTabsBarTray,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                color = ChromeBarSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = ChromeDivider, thickness = 0.8.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tabs.forEachIndexed { idx, t ->
                            val selected = idx == activeTabIndex
                            Surface(
                                color = if (selected) Color(0xFF101622) else ChromePillWhite,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    0.9.dp,
                                    if (selected) NeonCyan else ChromeDivider,
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        activeTabIndex = idx
                                        showTabsBarTray = false
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${idx + 1}. ${t.title}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) Color.White else ChromeTextPrimary,
                                        maxLines = 1,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Close tab",
                                        tint = if (selected) Color.White.copy(alpha = 0.8f) else ChromeTextMuted,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { closeTab(idx) },
                                    )
                                }
                            }
                        }
                        Surface(
                            color = ChromePillWhite,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(0.9.dp, ChromeDivider),
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { openNewTab() },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Add tab",
                                    tint = ChromeTextPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "New tab",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ChromeTextPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 7. Samsung-Style Bottom Navigation Bar (Back, Forward, Home, Pull, Cookies, [Tabs], More)
        HorizontalDivider(color = ChromeDivider, thickness = 0.8.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ChromeBarSurface)
                .padding(horizontal = 6.dp, vertical = 4.dp),
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
                    tint = if (!activeTab.isHome && activeTab.canGoBack) ChromeTextPrimary else ChromeTextMuted.copy(alpha = 0.42f),
                    modifier = Modifier.size(26.dp),
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
                    tint = if (!activeTab.isHome && activeTab.canGoForward) ChromeTextPrimary else ChromeTextMuted.copy(alpha = 0.42f),
                    modifier = Modifier.size(26.dp),
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
                    tint = if (activeTab.isHome) Color(0xFF2563EB) else ChromeTextPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }

            // Pull Current Page Video (1-tap download action in center-right slot)
            IconButton(
                onClick = {
                    if (!activeTab.isHome) {
                        val pullTarget = webViews[activeTab.id]?.url ?: activeTab.url
                        onManualSyncCookies(pullTarget)
                        onPullCurrentPage(pullTarget)
                    } else {
                        showToolsBar = !showToolsBar
                    }
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("browser_bottom_pull_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = "Download video from current page",
                    tint = if (!activeTab.isHome) Color(0xFF2563EB) else ChromeTextPrimary,
                    modifier = Modifier.size(22.dp),
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
                        tint = ChromeTextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                    if (syncedDomains.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .offset(x = 3.dp, y = (-2).dp)
                                .size(7.dp)
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
                        showTabsBarTray = !showTabsBarTray
                        if (showTabsBarTray) showToolsBar = false
                    }
                    .testTag("browser_tabs_count_button"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(21.dp)
                        .border(
                            width = 1.6.dp,
                            color = if (showTabsBarTray) Color(0xFF2563EB) else ChromeTextPrimary,
                            shape = RoundedCornerShape(6.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tabs.size.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = if (showTabsBarTray) Color(0xFF2563EB) else ChromeTextPrimary,
                    )
                }
            }

            // More / Zoom In & Out Controls
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
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Zoom & browser tools",
                        tint = if (showToolsBar || zoomPercent != 100) Color(0xFF2563EB) else ChromeTextPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                    if (syncedDomains.isNotEmpty() || zoomPercent != 100) {
                        Box(
                            modifier = Modifier
                                .offset(x = 2.dp, y = 1.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF97316)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SamsungStartPageHome(
    syncedDomainsCount: Int,
    showBanner: Boolean,
    onDismissBanner: () -> Unit,
    onOpenShortcut: (String) -> Unit,
    onToggleTools: () -> Unit,
    onManualSync: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF31241E),
                        Color(0xFF1D222B),
                        Color(0xFF17181E),
                        Color(0xFF251A16),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(900f, 1600f),
                ),
            )
            .testTag("browser_start_page_home"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // Top-right Controls (Tools/Zoom + Vault Session Badge matching screenshot header)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onToggleTools,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "Browser settings & zoom",
                        tint = Color.White.copy(alpha = 0.88f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    color = if (syncedDomainsCount > 0) EmeraldPulse.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.16f),
                    shape = CircleShape,
                    border = BorderStroke(
                        0.9.dp,
                        if (syncedDomainsCount > 0) EmeraldPulse.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.35f),
                    ),
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable { onManualSync() }
                        .testTag("browser_home_vault_badge"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = syncedDomainsCount.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }
                }
            }

            // Frosted Translucent Feature Banner Card (Dismissible via X, Zero Ads/Popups)
            AnimatedVisibility(visible = showBanner) {
                Surface(
                    color = Color.White.copy(alpha = 0.24f),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        // Subtle glowing ringed planet ornament on right (echoing Samsung Browser card)
                        Canvas(
                            modifier = Modifier
                                .size(92.dp)
                                .align(Alignment.CenterEnd)
                                .offset(x = 12.dp, y = 6.dp),
                        ) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.88f),
                                        Color(0xFF93C5FD).copy(alpha = 0.45f),
                                        Color(0xFF1E293B).copy(alpha = 0.25f),
                                    ),
                                ),
                                radius = size.minDimension * 0.36f,
                            )
                            rotate(-30f) {
                                drawOval(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF67E8F9),
                                            Color(0xFF38BDF8),
                                            Color(0xFF818CF8),
                                        ),
                                    ),
                                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
                                    topLeft = Offset(size.width * 0.04f, size.height * 0.34f),
                                    size = androidx.compose.ui.geometry.Size(size.width * 0.92f, size.height * 0.32f),
                                )
                            }
                        }

                        // Dismiss X top-right
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.25f))
                                .clickable { onDismissBanner() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Dismiss banner",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(15.dp),
                            )
                        }

                        Column(modifier = Modifier.fillMaxWidth(0.72f)) {
                            Text(
                                text = "Blackhole Browser",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                ),
                                color = Color.White,
                            )
                            Text(
                                text = "for Session Vault",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White.copy(alpha = 0.9f),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (syncedDomainsCount > 0) {
                                    "$syncedDomainsCount authenticated domains ready for HD capture."
                                } else {
                                    "Sign in once to unlock private & HD videos with full sound."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.82f),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = Color(0xFF0A0D12),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onManualSync() },
                            ) {
                                Text(
                                    text = if (syncedDomainsCount > 0) "Sync cookies" else "Sync session",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 5-Column Samsung-Style Quick Access Speed Dial Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(StartPageSpeedDials, key = { it.label }) { item ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onOpenShortcut(item.url) }
                            .padding(vertical = 4.dp)
                            .testTag("speed_dial_${item.label.lowercase().replace(" ", "_")}"),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (item.tileColors.size > 1) {
                                        Brush.linearGradient(item.tileColors)
                                    } else {
                                        SolidColor(item.tileColors.first())
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (item.isPlayIcon) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = item.label,
                                    tint = item.glyphColor,
                                    modifier = Modifier.size(28.dp),
                                )
                            } else {
                                Text(
                                    text = item.glyph,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = if (item.glyph.length > 2) 15.sp else 20.sp,
                                    ),
                                    color = item.glyphColor,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = Color.White.copy(alpha = 0.92f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

private fun deriveHostLabel(url: String): String {
    return url.substringAfter("://")
        .substringBefore("/")
        .removePrefix("www.")
        .removePrefix("m.")
        .ifBlank { "Web" }
}
