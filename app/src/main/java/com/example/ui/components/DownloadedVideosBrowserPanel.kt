package com.example.ui.components

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
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
import androidx.compose.ui.draw.drawBehind
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
    var isDesktopMode by remember { mutableStateOf(false) }
    var confirmClearCookies by remember { mutableStateOf(false) }

    var zoomPercent by remember { mutableIntStateOf(100) }
    var cssFallbackZoom by remember { mutableIntStateOf(100) }
    var baseScale by remember { mutableStateOf<Float?>(null) }

    val activeWebView = webViews[activeTab.id]

    // Ensure AES-256 permanently saved cookies are injected into WebView CookieManager on entry
    LaunchedEffect(Unit) {
        onRestoreCookiesToBrowser()
    }

    LaunchedEffect(activeTab.id, activeTab.url, activeWebView) {
        onBindWebViewRef(if (activeTab.isHome) null else activeWebView)
        if (!isUrlFocused) {
            addressText = if (activeTab.isHome) "" else activeTab.url
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val currentUrl = webViews[activeTab.id]?.url ?: activeTab.url
            if (currentUrl.startsWith("http", ignoreCase = true)) {
                onAutoSyncCookies(currentUrl)
            } else {
                onAutoSyncCookies("https://www.instagram.com/")
            }
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

    fun resolveNativeMobileUserAgent(ctx: android.content.Context): String {
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
        val rawNormalized = normalizeInputToUrl(raw)
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
            .background(ObsidianVoid)
            .testTag("downloaded_videos_browser_panel"),
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
                        Surface(
                            color = if (isSelected) NightTabSelected else NightTabInactive,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                width = 0.7.dp,
                                color = if (isSelected) NeonCyan.copy(alpha = 0.45f) else NightHairline,
                            ),
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable { activeTabIndex = index }
                                .testTag("browser_tab_${index}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 7.dp, end = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Public,
                                    contentDescription = null,
                                    tint = if (isSelected) NeonCyan else MutedSlate,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = tab.title.ifBlank { "New tab" },
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
                            text = "VIDEOS",
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
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .clickable { webViews[activeTab.id]?.reload() }
                                .testTag("browser_url_reload_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Reload page",
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
                    syncedDomainsCount = syncedDomains.size,
                    storedCookieCount = storedCookieCount,
                    isPermanentCookieLock = isPermanentCookieLock,
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
                                    setSupportMultipleWindows(false)
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    cacheMode = WebSettings.LOAD_DEFAULT
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

                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        super.onPageStarted(view, url, favicon)
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

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
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
                                            val lowerLoaded = loaded.lowercase()
                                            val midGoogleAuth = lowerLoaded.contains("accounts.google.") ||
                                                lowerLoaded.contains("accounts.youtube.") ||
                                                lowerLoaded.contains("consent.google.") ||
                                                lowerLoaded.contains("consent.youtube.")
                                            if (!midGoogleAuth) {
                                                onAutoSyncCookies(loaded)
                                            }
                                        }
                                        view?.evaluateJavascript(
                                            if (isDesktopMode) DESKTOP_VIEWPORT_JS else UNLOCK_VIEWPORT_JS,
                                            null,
                                        )
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

        // 4. Inline Cookie Status Toast (Zero Dialog — Nocturnal)
        AnimatedVisibility(
            visible = !statusMessage.isNullOrBlank(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            statusMessage?.let { msg ->
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
                                .clickable { onDismissStatus() },
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

        // 5. Inline Tools & Zoom Tray (Zero Popup — Nocturnal Obsidian)
        AnimatedVisibility(
            visible = showToolsBar,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                color = NightChromeSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = NightHairline, thickness = 0.7.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Zoom Out / % Reset / Zoom In Capsule
                        Surface(
                            color = NightTrayPill,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.2f)),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { zoomPageBy(-20) },
                                    enabled = !activeTab.isHome,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("browser_zoom_out_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ZoomOut,
                                        contentDescription = "Zoom out page",
                                        tint = if (activeTab.isHome) DimSlate else ChromeSilver,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Text(
                                    text = "$zoomPercent%",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (zoomPercent == 100) MutedSlate else NeonCyan,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = !activeTab.isHome) { resetZoom() }
                                        .padding(horizontal = 6.dp, vertical = 5.dp)
                                        .testTag("browser_zoom_reset_button"),
                                )
                                IconButton(
                                    onClick = { zoomPageBy(+20) },
                                    enabled = !activeTab.isHome,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("browser_zoom_in_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ZoomIn,
                                        contentDescription = "Zoom in page",
                                        tint = if (activeTab.isHome) DimSlate else NeonCyan,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }

                        // Desktop View Toggle Action
                        Surface(
                            color = if (isDesktopMode) NeonCyan.copy(alpha = 0.14f) else NightTrayPill,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                0.8.dp,
                                if (isDesktopMode) NeonCyan.copy(alpha = 0.7f) else GlassStrokeBright.copy(alpha = 0.2f),
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { toggleDesktopMode() }
                                .testTag("browser_desktop_mode_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DesktopWindows,
                                    contentDescription = "Toggle desktop view",
                                    tint = if (isDesktopMode) NeonCyan else ChromeSilver,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isDesktopMode) "DESKTOP ON" else "DESKTOP VIEW",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isDesktopMode) NeonCyan else ChromeSilver,
                                )
                            }
                        }

                        // Permanent AES-256 Cookie Lock Pill
                        Surface(
                            color = if (isPermanentCookieLock) EmeraldPulse.copy(alpha = 0.13f) else NightTrayPill,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                0.8.dp,
                                if (isPermanentCookieLock) EmeraldPulse.copy(alpha = 0.65f) else GlassStrokeBright.copy(alpha = 0.2f),
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { onTogglePermanentCookieLock() }
                                .testTag("browser_permanent_cookie_lock_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VerifiedUser,
                                    contentDescription = "Permanent cookie protection",
                                    tint = if (isPermanentCookieLock) EmeraldPulse else MutedSlate,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isPermanentCookieLock) {
                                        "FOREVER LOCK · $storedCookieCount"
                                    } else {
                                        "TEMP SESSION · $storedCookieCount"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isPermanentCookieLock) EmeraldPulse else ChromeSilver,
                                )
                            }
                        }

                        // Sync Cookies Action
                        Surface(
                            color = NightTrayPill,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.2f)),
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
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "SYNC (${syncedDomains.size})",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = ChromeSilver,
                                )
                            }
                        }

                        // Clear Cookies (Inline 2-tap confirm, zero dialog)
                        if (storedCookieCount > 0) {
                            Surface(
                                color = if (confirmClearCookies) CrimsonAlert else CrimsonAlert.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(
                                    0.8.dp,
                                    CrimsonAlert.copy(alpha = if (confirmClearCookies) 1f else 0.55f),
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        if (confirmClearCookies) {
                                            confirmClearCookies = false
                                            onClearCookies()
                                        } else {
                                            confirmClearCookies = true
                                        }
                                    }
                                    .testTag("browser_clear_cookies_button"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Clear cookie vault",
                                        tint = if (confirmClearCookies) Color.White else CrimsonAlert,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (confirmClearCookies) "TAP AGAIN TO CLEAR" else "CLEAR VAULT",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (confirmClearCookies) Color.White else CrimsonAlert,
                                    )
                                }
                            }
                        }

                        // Reload Action
                        Surface(
                            color = NightTrayPill,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.2f)),
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
                                    tint = if (activeTab.isHome) DimSlate else ChromeSilver,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "RELOAD",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (activeTab.isHome) DimSlate else ChromeSilver,
                                )
                            }
                        }

                        // Pull Video Action
                        if (!activeTab.isHome) {
                            Surface(
                                color = NeonCyan.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(0.85.dp, NeonCyan.copy(alpha = 0.7f)),
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
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "PULL VIDEO",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = ChromeSilver,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Inline Tab Manager Strip (Zero Popup — Nocturnal)
        AnimatedVisibility(
            visible = showTabsBarTray,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                color = NightChromeSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = NightHairline, thickness = 0.7.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tabs.forEachIndexed { idx, t ->
                            val selected = idx == activeTabIndex
                            Surface(
                                color = if (selected) NightTabSelected else NightTrayPill,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    0.85.dp,
                                    if (selected) NeonCyan.copy(alpha = 0.7f) else NightHairline,
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        activeTabIndex = idx
                                        showTabsBarTray = false
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${idx + 1}. ${t.title}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) ChromeSilver else MutedSlate,
                                        maxLines = 1,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Close tab",
                                        tint = MutedSlate,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { closeTab(idx) },
                                    )
                                }
                            }
                        }
                        Surface(
                            color = NightTrayPill,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(0.8.dp, GlassStrokeBright.copy(alpha = 0.22f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { openNewTab() },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Add tab",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "New tab",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ChromeSilver,
                                )
                            }
                        }
                    }
                }
            }
        }

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
                    tint = if (!activeTab.isHome) NeonCyan else ChromeSilver,
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
                        tint = if (showToolsBar || zoomPercent != 100) NeonCyan else ChromeSilver,
                        modifier = Modifier.size(22.dp),
                    )
                    if (syncedDomains.isNotEmpty() || zoomPercent != 100) {
                        Box(
                            modifier = Modifier
                                .offset(x = 2.dp, y = 1.dp)
                                .size(5.5.dp)
                                .clip(CircleShape)
                                .background(AmberWarning),
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
    storedCookieCount: Int,
    isPermanentCookieLock: Boolean,
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
            // Top-right Controls (Tools/Zoom + Vault Session Badge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onToggleTools,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "Browser settings, desktop & zoom",
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
                                .size(48.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .background(
                                    if (item.tileColors.size > 1) {
                                        Brush.linearGradient(item.tileColors)
                                    } else {
                                        SolidColor(item.tileColors.first())
                                    },
                                )
                                .border(
                                    width = 0.75.dp,
                                    color = GlassStrokeBright.copy(alpha = 0.24f),
                                    shape = RoundedCornerShape(15.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (item.isPlayIcon) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = item.label,
                                    tint = item.glyphColor,
                                    modifier = Modifier.size(26.dp),
                                )
                            } else {
                                Text(
                                    text = item.glyph,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = if (item.glyph.length > 2) 14.sp else 19.sp,
                                    ),
                                    color = item.glyphColor,
                                )
                            }
                        }
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
