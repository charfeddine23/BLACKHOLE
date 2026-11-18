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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.engine.network.ClientSpoofer
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CyanGlowSoft
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.PlasmaViolet

private data class BrowserPlatformShortcut(
    val shortLabel: String,
    val fullName: String,
    val url: String,
    val hostMatch: String,
)

private val BrowserShortcuts = listOf(
    BrowserPlatformShortcut("YT SIGN-IN", "YouTube Login", "https://accounts.google.com/ServiceLogin?service=youtube", "google.com"),
    BrowserPlatformShortcut("YT", "YouTube", "https://m.youtube.com/", "youtube.com"),
    BrowserPlatformShortcut("IG", "Instagram", "https://www.instagram.com/accounts/login/", "instagram.com"),
    BrowserPlatformShortcut("FB", "Facebook", "https://m.facebook.com/login/", "facebook.com"),
    BrowserPlatformShortcut("TT", "TikTok", "https://www.tiktok.com/login", "tiktok.com"),
    BrowserPlatformShortcut("X", "X", "https://x.com/i/flow/login", "x.com"),
)

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
    var addressText by remember { mutableStateOf(BrowserShortcuts.first().url) }
    var currentUrl by remember { mutableStateOf(BrowserShortcuts.first().url) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            onBindWebViewRef(null)
        }
    }

    fun navigateTo(raw: String) {
        val trimmed = raw.trim()
        val normalized = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains(".") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${trimmed.replace(" ", "+")}"
        }
        addressText = normalized
        currentUrl = normalized
        webViewRef?.loadUrl(normalized)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .testTag("downloaded_videos_browser_panel"),
    ) {
        // Platform Quick-Login Strip + Cookie Sync Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BrowserShortcuts.forEach { shortcut ->
                val isSelected = currentUrl.contains(shortcut.hostMatch, ignoreCase = true)
                Surface(
                    color = if (isSelected) NeonCyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(
                        width = 0.8.dp,
                        color = if (isSelected) NeonCyan.copy(alpha = 0.72f) else GlassStrokeBright.copy(alpha = 0.26f),
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { navigateTo(shortcut.url) }
                        .testTag("browser_shortcut_${shortcut.shortLabel.lowercase()}"),
                ) {
                    Text(
                        text = shortcut.shortLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) NeonCyan else ChromeSilver,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    )
                }
            }

            Surface(
                color = if (syncedDomains.isNotEmpty()) {
                    EmeraldPulse.copy(alpha = 0.16f)
                } else {
                    Color.White.copy(alpha = 0.05f)
                },
                shape = RoundedCornerShape(50),
                border = BorderStroke(
                    width = 0.8.dp,
                    color = if (syncedDomains.isNotEmpty()) {
                        EmeraldPulse.copy(alpha = 0.6f)
                    } else {
                        GlassStrokeBright.copy(alpha = 0.22f)
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = if (syncedDomains.isNotEmpty()) EmeraldPulse else MutedSlate,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${syncedDomains.size} COOKIES",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (syncedDomains.isNotEmpty()) EmeraldPulse else MutedSlate,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Navigation + Address Bar + Sync & Pull Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() } },
                modifier = Modifier
                    .size(40.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("browser_back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Browser Back",
                    tint = ChromeSilver,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { webViewRef?.let { if (it.canGoForward()) it.goForward() } },
                modifier = Modifier
                    .size(40.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("browser_forward_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = "Browser Forward",
                    tint = ChromeSilver,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { webViewRef?.reload() },
                modifier = Modifier
                    .size(40.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("browser_reload_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Reload Page",
                    tint = ChromeSilver,
                    modifier = Modifier.size(18.dp),
                )
            }

            Surface(
                color = Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.28f)),
                modifier = Modifier.weight(1f),
            ) {
                BasicTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelSmall.copy(color = ChromeSilver),
                    cursorBrush = SolidColor(NeonCyan),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { navigateTo(addressText) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 11.dp)
                        .testTag("browser_address_input"),
                )
            }

            Button(
                onClick = {
                    val liveUrl = webViewRef?.url ?: currentUrl
                    onManualSyncCookies(liveUrl)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPulse.copy(alpha = 0.2f),
                    contentColor = EmeraldPulse,
                ),
                border = BorderStroke(0.85.dp, EmeraldPulse.copy(alpha = 0.65f)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(42.dp)
                    .testTag("browser_sync_cookies_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = "Sync Cookies",
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "SYNC",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }

            Button(
                onClick = {
                    val liveUrl = webViewRef?.url ?: currentUrl
                    onAutoSyncCookies(liveUrl)
                    onPullCurrentPage(liveUrl)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanGlowSoft.copy(alpha = 0.35f),
                    contentColor = NeonCyan,
                ),
                border = BorderStroke(0.85.dp, NeonCyan.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(42.dp)
                    .testTag("browser_pull_page_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = "Pull video from current browser page",
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = !statusMessage.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            statusMessage?.let { msg ->
                Surface(
                    color = PlasmaViolet.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.75.dp, PlasmaViolet.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = ChromeSilver,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = onDismissStatus,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Dismiss cookie status",
                                tint = MutedSlate,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        if (loadProgress in 1..99) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { loadProgress / 100f },
                color = NeonCyan,
                trackColor = Color.White.copy(alpha = 0.08f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .clip(CircleShape),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Embedded Interactive Browser Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0A0F17)),
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.userAgentString = ClientSpoofer.ANDROID_CHROME_USER_AGENT
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = false

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (!url.isNullOrBlank()) {
                                    addressText = url
                                    currentUrl = url
                                    onAutoSyncCookies(url)
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                            }
                        }
                        loadUrl(currentUrl)
                        webViewRef = this
                        onBindWebViewRef(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
