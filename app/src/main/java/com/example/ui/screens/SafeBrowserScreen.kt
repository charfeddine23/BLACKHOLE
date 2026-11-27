package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.example.vault.browser.EphemeralSessionManager
import com.example.vault.browser.PublicLinkPreviewResult
import com.example.vault.browser.RateLimitAcquireResult
import com.example.vault.browser.RequestRateLimiter
import com.example.vault.browser.SafeWebViewConfig
import com.example.vault.browser.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Refactored Privacy-First, Session-Isolated & Rate-Limited Embedded Browser Screen:
 * - Standard WebView browsing configured by [SafeWebViewConfig] + [EphemeralSessionManager]
 * - Zero cookie forwarding, third-party cookies blocked, form data disabled, consistent Chrome Mobile UA
 * - Zero background script injection or automatic network scraping on page load
 * - Strict user-initiated link preview action enforced via [RequestRateLimiter] (5s cooldown, single-flight, 429/403 backoff)
 * - Clean separation between WebView browsing and sanitized public oEmbed/OpenGraph metadata preview
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SafeBrowserScreen(
    initialUrl: String = "https://www.wikipedia.org/",
    onBack: () -> Unit = {},
    onOpenSanitizedPublicLink: (sanitizedUrl: String) -> Unit = {},
    sessionManager: EphemeralSessionManager = remember { EphemeralSessionManager(initialEphemeralMode = true) },
    rateLimiter: RequestRateLimiter = remember { RequestRateLimiter() },
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val isEphemeralMode by sessionManager.isEphemeralMode.collectAsStateWithLifecycle()
    val lastSessionClearedAtMs by sessionManager.lastSessionClearedAtMs.collectAsStateWithLifecycle()
    val rateStatus by rateLimiter.status.collectAsStateWithLifecycle()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var addressInput by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("Safe Browser") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showSafeguardBanner by remember { mutableStateOf(true) }
    var sessionBannerMessage by remember { mutableStateOf<String?>(null) }
    var activePreview by remember { mutableStateOf<PublicLinkPreviewResult?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var customFullscreenView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val previewHttpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun exitFullscreenVideo() {
        runCatching { customViewCallback?.onCustomViewHidden() }
        customFullscreenView = null
        customViewCallback = null
    }

    DisposableEffect(sessionManager) {
        onDispose {
            exitFullscreenVideo()
            sessionManager.disposeWebView(webViewRef)
            webViewRef = null
        }
    }

    BackHandler {
        val wv = webViewRef
        when {
            customFullscreenView != null -> exitFullscreenVideo()
            wv != null && wv.canGoBack() -> wv.goBack()
            else -> onBack()
        }
    }

    fun navigateToInput(rawInput: String) {
        focusManager.clearFocus()
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return
        val normalized = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://duckduckgo.com/?q=${Uri.encode(trimmed)}"
        }
        currentUrl = normalized
        addressInput = normalized
        activePreview = null
        previewError = null
        webViewRef?.loadUrl(normalized)
    }

    fun triggerUserInitiatedInspect() {
        val rawUrl = webViewRef?.url?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: addressInput.trim()
        val sanitized = UrlSanitizer.sanitizePublicUrl(rawUrl)
        if (!sanitized.isValidPublicHttpUrl) {
            previewError = "أدخل رابط http أو https عاماً وصالحاً."
            return
        }

        when (val acquired = rateLimiter.tryAcquire()) {
            is RateLimitAcquireResult.Blocked -> {
                previewError = acquired.reason
                return
            }
            RateLimitAcquireResult.Acquired -> Unit
        }

        previewError = null
        coroutineScope.launch {
            var observedStatus: Int? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    fetchCookieFreePublicPreview(
                        client = previewHttpClient,
                        sanitized = sanitized,
                    )
                }
                observedStatus = result.httpStatusCode
                if (result.errorMessage != null && result.title.isBlank()) {
                    previewError = result.errorMessage
                    activePreview = null
                } else {
                    activePreview = result
                    previewError = result.errorMessage
                }
            } catch (t: Throwable) {
                previewError = t.message ?: "تعذر جلب معاينة الرابط العام."
            } finally {
                rateLimiter.releaseAndArmCooldown(observedStatus)
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("safe_browser_screen"),
        containerColor = ObsidianVoid,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianVoid)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // 1. Top Navigation & Session Isolation Header
            Surface(
                color = AbyssalSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("safe_browser_back_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                    tint = ChromeSilver,
                                )
                            }
                            IconButton(
                                onClick = { webViewRef?.let { if (it.canGoForward()) it.goForward() } },
                                enabled = canGoForward,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("safe_browser_forward_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = "Forward",
                                    tint = if (canGoForward) ChromeSilver else DimSlate,
                                )
                            }
                        }

                        // Ephemeral Isolation Toggle + Manual Session Clear
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                color = if (isEphemeralMode) EmeraldPulse.copy(alpha = 0.14f) else PlasmaViolet.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(
                                    0.7.dp,
                                    if (isEphemeralMode) EmeraldPulse.copy(alpha = 0.45f) else PlasmaViolet.copy(alpha = 0.45f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, top = 2.dp, end = 6.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Shield,
                                        contentDescription = null,
                                        tint = if (isEphemeralMode) EmeraldPulse else PlasmaViolet,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = if (isEphemeralMode) "Ephemeral" else "Standard",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = ChromeSilver,
                                    )
                                    Switch(
                                        checked = isEphemeralMode,
                                        onCheckedChange = { enabled ->
                                            sessionManager.setEphemeralMode(enabled, webViewRef)
                                            sessionBannerMessage = if (enabled) {
                                                "Ephemeral isolation enabled — cookies & storage cleared"
                                            } else {
                                                "Standard browsing enabled (cookies still never forwarded)"
                                            }
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = ObsidianVoid,
                                            checkedTrackColor = EmeraldPulse,
                                        ),
                                        modifier = Modifier.testTag("safe_browser_ephemeral_switch"),
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    sessionManager.clearSessionData(webViewRef)
                                    sessionBannerMessage = "Session cookies, cache & WebStorage wiped"
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("safe_browser_clear_session_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteSweep,
                                    contentDescription = "Clear session data",
                                    tint = NeonCyan,
                                )
                            }
                        }
                    }

                    // Address Pill + Reload
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            color = Color(0xFF0E1522),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(0.8.dp, GlassStrokeBright.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = if (currentUrl.startsWith("https://", true)) EmeraldPulse else AmberWarning,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                BasicTextField(
                                    value = addressInput,
                                    onValueChange = { addressInput = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = ChromeSilver),
                                    cursorBrush = SolidColor(NeonCyan),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onGo = { navigateToInput(addressInput) },
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("safe_browser_address_input"),
                                )
                            }
                        }

                        IconButton(
                            onClick = { webViewRef?.reload() },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("safe_browser_reload_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Reload page",
                                tint = ChromeSilver,
                            )
                        }
                    }

                    // Privacy & Anti-Scraping Safeguard Banner
                    AnimatedVisibility(visible = showSafeguardBanner) {
                        Surface(
                            color = Color(0xFF1A1610),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(0.75.dp, AmberWarning.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = AmberWarning,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "Privacy safeguard: Do not sign into sensitive personal accounts inside embedded browsers or trigger rapid repeated requests. Cookies remain isolated and are never forwarded.",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = ChromeSilver,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { showSafeguardBanner = false },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Dismiss warning",
                                        tint = MutedSlate,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }

                    if (!sessionBannerMessage.isNullOrBlank() || lastSessionClearedAtMs > 0L && sessionBannerMessage != null) {
                        sessionBannerMessage?.let { msg ->
                            Surface(
                                color = EmeraldPulse.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
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
                                        color = EmeraldPulse,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = { sessionBannerMessage = null },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Dismiss",
                                            tint = MutedSlate,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = GlassStrokeSubtle, thickness = 0.7.dp)

            // 2. Standard Isolated WebView Viewport (Zero auto-injected scripts)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.parseColor("#050709"))
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            SafeWebViewConfig.configure(this, isEphemeralSession = isEphemeralMode)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val url = request?.url?.toString().orEmpty()
                                    return !(url.startsWith("http://", true) || url.startsWith("https://", true))
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                        addressInput = url
                                    }
                                    pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: pageTitle
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun getDefaultVideoPoster(): Bitmap {
                                    return SafeWebViewConfig.createDarkVideoPosterBitmap()
                                }

                                override fun onPermissionRequest(request: PermissionRequest?) {
                                    val req = request ?: return
                                    val allowedResources = req.resources.filter { res ->
                                        res == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                                    }.toTypedArray()
                                    if (allowedResources.isNotEmpty()) {
                                        runCatching { req.grant(allowedResources) }
                                    } else {
                                        runCatching { req.deny() }
                                    }
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
                                    exitFullscreenVideo()
                                }
                            }
                            onResume()
                            resumeTimers()
                            webViewRef = this
                            loadUrl(currentUrl)
                        }
                    },
                    update = { wv ->
                        wv.onResume()
                        wv.resumeTimers()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("safe_browser_webview"),
                )

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
            }

            // 3. Clean Separation Card: Sanitized Public Metadata Preview + Rate-Limited Action Trigger
            Surface(
                color = AbyssalSurface,
                border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.18f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnimatedVisibility(visible = activePreview != null) {
                        activePreview?.let { preview ->
                            PublicPreviewCard(
                                preview = preview,
                                onOpenSanitized = { onOpenSanitizedPublicLink(preview.sanitizedUrl) },
                                onDismiss = { activePreview = null },
                            )
                        }
                    }

                    AnimatedVisibility(visible = !previewError.isNullOrBlank()) {
                        Surface(
                            color = CrimsonAlert.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(0.7.dp, CrimsonAlert.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = previewError.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = ChromeSilver,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { triggerUserInitiatedInspect() },
                            enabled = rateStatus.canStartRequest,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = ObsidianVoid,
                                disabledContainerColor = Color(0xFF1B273A),
                                disabledContentColor = MutedSlate,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("safe_browser_inspect_public_link_button"),
                        ) {
                            when {
                                rateStatus.isInFlight -> {
                                    CircularProgressIndicator(
                                        color = NeonCyan,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Inspecting Public Link...", fontWeight = FontWeight.Bold)
                                }
                                rateStatus.remainingCooldownSeconds > 0 -> {
                                    Icon(
                                        imageVector = Icons.Rounded.Timer,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (rateStatus.isBackoffActive) {
                                            "Backoff (${rateStatus.remainingCooldownSeconds}s)"
                                        } else {
                                            "Cooldown (${rateStatus.remainingCooldownSeconds}s)"
                                        },
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                else -> {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Inspect Public Link", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { navigateToInput(addressInput) },
                            border = BorderStroke(0.9.dp, GlassStrokeBright.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("safe_browser_go_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = "Open URL",
                                tint = ChromeSilver,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PublicPreviewCard(
    preview: PublicLinkPreviewResult,
    onOpenSanitized: () -> Unit,
    onDismiss: () -> Unit,
) {
    val thumbBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = preview.thumbnailUrl) {
        val url = preview.thumbnailUrl ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3500
                    setRequestProperty("User-Agent", SafeWebViewConfig.STANDARD_ANDROID_CHROME_USER_AGENT)
                }
                try {
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                    } else {
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
    }

    Surface(
        color = Color(0xFF101929),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.85.dp, EmeraldPulse.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("safe_browser_public_preview_card"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF0A1322), Color(0xFF16243B)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbBitmap != null) {
                    Image(
                        bitmap = thumbBitmap!!,
                        contentDescription = preview.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = EmeraldPulse,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = preview.title.ifBlank { preview.cleanOrigin },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = ChromeSilver,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${preview.sourceMethod} · ${preview.strippedParamCount} tracking params stripped",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = EmeraldPulse,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preview.sanitizedUrl,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MutedSlate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(26.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close preview",
                        tint = MutedSlate,
                        modifier = Modifier.size(15.dp),
                    )
                }
                if (preview.sanitizedUrl.isNotBlank()) {
                    OutlinedButton(
                        onClick = onOpenSanitized,
                        border = BorderStroke(0.7.dp, NeonCyan.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text(
                            text = "Use",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan,
                        )
                    }
                }
            }
        }
    }
}

private fun fetchCookieFreePublicPreview(
    client: OkHttpClient,
    sanitized: com.example.vault.browser.SanitizedUrlResult,
): PublicLinkPreviewResult {
    val cleanUrl = sanitized.sanitizedUrl
    val host = runCatching { Uri.parse(cleanUrl).host?.lowercase().orEmpty() }.getOrDefault("")
    val encoded = Uri.encode(cleanUrl)
    val oEmbedEndpoint = when {
        host.contains("youtube.com") || host.contains("youtu.be") ->
            "https://www.youtube.com/oembed?format=json&url=$encoded"
        host.contains("vimeo.com") ->
            "https://vimeo.com/api/oembed.json?url=$encoded"
        host.contains("tiktok.com") ->
            "https://www.tiktok.com/oembed?url=$encoded"
        else -> null
    }

    if (oEmbedEndpoint != null) {
        val req = Request.Builder()
            .url(oEmbedEndpoint)
            .get()
            .header("User-Agent", SafeWebViewConfig.STANDARD_ANDROID_CHROME_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val res = runCatching {
            client.newCall(req).execute().use { resp ->
                val body = if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                resp.code to body
            }
        }.getOrNull()
        if (res != null) {
            val (code, body) = res
            if (code == 429 || code == 403) {
                return PublicLinkPreviewResult(
                    sanitizedUrl = cleanUrl,
                    cleanOrigin = sanitized.cleanOrigin,
                    strippedParamCount = sanitized.strippedParamCount,
                    httpStatusCode = code,
                    errorMessage = "Server rate limit (HTTP $code) — automatic backoff active.",
                )
            }
            if (code in 200..299 && body.isNotBlank()) {
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (json != null) {
                    return PublicLinkPreviewResult(
                        sanitizedUrl = cleanUrl,
                        cleanOrigin = sanitized.cleanOrigin,
                        strippedParamCount = sanitized.strippedParamCount,
                        title = json.optString("title").trim(),
                        thumbnailUrl = json.optString("thumbnail_url").trim().takeIf { it.startsWith("http", true) },
                        authorName = json.optString("author_name").trim().takeIf { it.isNotBlank() },
                        providerName = json.optString("provider_name").trim(),
                        sourceMethod = "Official oEmbed",
                        httpStatusCode = code,
                    )
                }
            }
        }
    }

    val ogReq = Request.Builder()
        .url(cleanUrl)
        .get()
        .header("User-Agent", SafeWebViewConfig.STANDARD_ANDROID_CHROME_USER_AGENT)
        .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
        .build()

    val ogRes = runCatching {
        client.newCall(ogReq).execute().use { resp ->
            val body = if (resp.isSuccessful) resp.body?.string()?.take(96_000).orEmpty() else ""
            resp.code to body
        }
    }.getOrNull() ?: return PublicLinkPreviewResult(
        sanitizedUrl = cleanUrl,
        cleanOrigin = sanitized.cleanOrigin,
        strippedParamCount = sanitized.strippedParamCount,
        errorMessage = "Unable to connect to public URL.",
    )

    val (code, html) = ogRes
    if (code == 429 || code == 403) {
        return PublicLinkPreviewResult(
            sanitizedUrl = cleanUrl,
            cleanOrigin = sanitized.cleanOrigin,
            strippedParamCount = sanitized.strippedParamCount,
            httpStatusCode = code,
            errorMessage = "Server rate limit (HTTP $code) — automatic backoff active.",
        )
    }

    fun meta(name: String): String? {
        val esc = Regex.escape(name)
        val p = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']$esc["'][^>]+content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        return p.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    val title = meta("og:title")
        ?: meta("twitter:title")
        ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
        ?: sanitized.cleanOrigin
    val image = meta("og:image") ?: meta("twitter:image")

    return PublicLinkPreviewResult(
        sanitizedUrl = cleanUrl,
        cleanOrigin = sanitized.cleanOrigin,
        strippedParamCount = sanitized.strippedParamCount,
        title = title,
        thumbnailUrl = image?.takeIf { it.startsWith("http", true) },
        providerName = host,
        sourceMethod = "OpenGraph (Cookie-Free)",
        httpStatusCode = code,
        errorMessage = if (code >= 400) "HTTP $code returned for public metadata." else null,
    )
}
