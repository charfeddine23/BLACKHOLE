package com.example.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
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
import com.example.data.local.AppPreferences
import com.example.ui.theme.AbyssalSurface
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.DimSlate
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeSubtle
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.util.formatByteSize
import com.example.vault.browser.AutoVideoDownloadController
import com.example.vault.browser.AutoVideoDownloadState
import com.example.vault.browser.EphemeralSessionManager
import com.example.vault.browser.SafeWebViewConfig
import com.example.vault.browser.YtDlpAndPublicPageResolver
import com.example.vault.data.SafVaultRepository
import com.example.vault.data.VaultVideoDownloader

/**
 * Vault Browser Auto-Download Screen:
 * - Hosts a platform-safe, session-isolated WebView (no background polling, no cookie forwarding).
 * - Triggers an on-demand active-<video> DOM scan via JavascriptInterface when the user taps
 *   "Download Video", protected by single-flight execution and cooldown / rate-limit backoff.
 * - Direct HTTP(S) videos stream directly via [VaultVideoDownloader] (`OkHttp` + `Flow` progress)
 *   into encrypted Vault storage; `blob:` or missing-src streams fall back to sanitized `page_url`
 *   resolution via [YtDlpAndPublicPageResolver].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VaultAutoDownloadScreen(
    initialUrl: String = "https://www.wikipedia.org/",
    onBack: () -> Unit = {},
    onSavedToVault: (title: String, savedLocation: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val appPreferences = remember(context) { AppPreferences.getInstance(context) }
    val ytDlpBackendUrl by appPreferences.ytDlpBackendUrl.collectAsStateWithLifecycle()

    val vaultRepository = remember(context) { SafVaultRepository(context.applicationContext) }
    val downloader = remember(context, vaultRepository) {
        VaultVideoDownloader(
            context = context.applicationContext,
            vaultRepository = vaultRepository,
        )
    }
    val pageResolver = remember(appPreferences) {
        YtDlpAndPublicPageResolver(
            backendBaseUrlProvider = { appPreferences.ytDlpBackendUrl.value },
        )
    }
    val controller = remember(downloader, pageResolver) {
        AutoVideoDownloadController(
            downloader = downloader,
            pageStreamResolver = pageResolver,
        )
    }
    val sessionManager = remember { EphemeralSessionManager(initialEphemeralMode = true) }

    val downloadState by controller.state.collectAsStateWithLifecycle()
    val cooldownSeconds by controller.cooldownRemainingSeconds.collectAsStateWithLifecycle()
    val isEphemeralMode by sessionManager.isEphemeralMode.collectAsStateWithLifecycle()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var addressInput by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("Vault Browser") }
    var pageProgress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showBackendDialog by remember { mutableStateOf(false) }
    var lastNotifiedCompletionKey by remember { mutableStateOf<String?>(null) }

    val completedState = downloadState as? AutoVideoDownloadState.Completed
    if (completedState != null) {
        val completionKey = "${completedState.pageUrl}|${completedState.savedLocation}"
        if (lastNotifiedCompletionKey != completionKey) {
            lastNotifiedCompletionKey = completionKey
            onSavedToVault(completedState.title, completedState.savedLocation)
        }
    }

    DisposableEffect(controller, sessionManager) {
        onDispose {
            controller.cancelActiveDownload()
            controller.detachFromWebView(webViewRef)
            controller.cleanup()
            sessionManager.disposeWebView(webViewRef)
            webViewRef = null
        }
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    fun loadAddress(raw: String) {
        focusManager.clearFocus()
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return
        val resolved = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> "https://duckduckgo.com/?q=${android.net.Uri.encode(trimmed)}"
        }
        currentUrl = resolved
        addressInput = resolved
        webViewRef?.loadUrl(resolved)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("vault_auto_download_screen"),
        containerColor = ObsidianVoid,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Top Browser Bar
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
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("auto_browser_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = ChromeSilver,
                            )
                        }

                        // Address Field
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ObsidianVoid)
                                .border(0.8.dp, GlassStrokeSubtle, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (currentUrl.startsWith("https://", ignoreCase = true)) {
                                    Icons.Rounded.Lock
                                } else {
                                    Icons.Rounded.Public
                                },
                                contentDescription = null,
                                tint = if (currentUrl.startsWith("https://", ignoreCase = true)) {
                                    EmeraldPulse
                                } else {
                                    MutedSlate
                                },
                                modifier = Modifier.size(16.dp),
                            )
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
                                keyboardActions = KeyboardActions(onGo = { loadAddress(addressInput) }),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("auto_browser_address_input"),
                            )
                            if (addressInput.isNotBlank()) {
                                IconButton(
                                    onClick = { addressInput = "" },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Clear URL",
                                        tint = MutedSlate,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = {
                                if (pageProgress in 1..99) webViewRef?.stopLoading() else webViewRef?.reload()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("auto_browser_reload_button"),
                        ) {
                            Icon(
                                imageVector = if (pageProgress in 1..99) Icons.Rounded.Close else Icons.Rounded.Refresh,
                                contentDescription = "Reload page",
                                tint = NeonCyan,
                            )
                        }
                    }

                    // Navigation & Ephemeral Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { webViewRef?.goBack() },
                                enabled = canGoBack,
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Page back",
                                    tint = if (canGoBack) ChromeSilver else DimSlate,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(
                                onClick = { webViewRef?.goForward() },
                                enabled = canGoForward,
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = "Page forward",
                                    tint = if (canGoForward) ChromeSilver else DimSlate,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                color = if (isEphemeralMode) {
                                    EmeraldPulse.copy(alpha = 0.14f)
                                } else {
                                    PlasmaViolet.copy(alpha = 0.14f)
                                },
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(
                                    0.7.dp,
                                    if (isEphemeralMode) {
                                        EmeraldPulse.copy(alpha = 0.45f)
                                    } else {
                                        PlasmaViolet.copy(alpha = 0.45f)
                                    },
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
                                        text = if (isEphemeralMode) "Isolated" else "Standard",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = ChromeSilver,
                                    )
                                    Switch(
                                        checked = isEphemeralMode,
                                        onCheckedChange = { sessionManager.setEphemeralMode(it, webViewRef) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = ObsidianVoid,
                                            checkedTrackColor = EmeraldPulse,
                                            uncheckedThumbColor = ChromeSilver,
                                            uncheckedTrackColor = DimSlate,
                                        ),
                                    )
                                }
                            }

                            IconButton(
                                onClick = { sessionManager.clearSessionData(webViewRef) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("auto_browser_clear_session_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteSweep,
                                    contentDescription = "Clear WebView cookies and cache",
                                    tint = AmberWarning,
                                )
                            }

                            IconButton(
                                onClick = { showBackendDialog = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("auto_browser_backend_settings_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = "Configure yt-dlp backend endpoint",
                                    tint = if (ytDlpBackendUrl.isNotBlank()) NeonCyan else MutedSlate,
                                )
                            }
                        }
                    }
                }
            }

            if (pageProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { pageProgress / 100f },
                    color = NeonCyan,
                    trackColor = AbyssalSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            } else {
                HorizontalDivider(color = GlassStrokeSubtle, thickness = 0.7.dp)
            }

            // Privacy Notice Banner
            Surface(
                color = AbyssalSurface.copy(alpha = 0.9f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Ephemeral WebView · Cookies are never forwarded · Scans active video only on tap",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MutedSlate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // WebView Viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            SafeWebViewConfig.configure(this, isEphemeralSession = isEphemeralMode)
                            controller.attachToWebView(this)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val target = request?.url?.toString().orEmpty()
                                    return !target.startsWith("http://", ignoreCase = true) &&
                                        !target.startsWith("https://", ignoreCase = true)
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?,
                                ) {
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                        addressInput = url
                                    }
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
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
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    pageProgress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    if (!title.isNullOrBlank()) pageTitle = title
                                }
                            }
                            loadUrl(initialUrl)
                            webViewRef = this
                        }
                    },
                    update = { wv ->
                        webViewRef = wv
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Bottom Auto-Download Dock
            AutoDownloadActionDock(
                state = downloadState,
                cooldownSeconds = cooldownSeconds,
                currentUrl = currentUrl,
                onTriggerDownload = {
                    controller.triggerAutoDownload(
                        webView = webViewRef,
                        fallbackPageUrl = currentUrl,
                    )
                },
                onCancelDownload = controller::cancelActiveDownload,
                onDismissStatus = controller::dismissTerminalMessage,
            )
        }
    }

    if (showBackendDialog) {
        var endpointInput by remember(ytDlpBackendUrl) { mutableStateOf(ytDlpBackendUrl) }
        AlertDialog(
            onDismissRequest = { showBackendDialog = false },
            containerColor = AbyssalSurface,
            titleContentColor = ChromeSilver,
            textContentColor = MutedSlate,
            title = {
                Text(
                    text = "Extractor Backend (Optional)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Used only as a cookie-free { page_url } fallback when a video uses blob:/MSE streaming.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = endpointInput,
                        onValueChange = { endpointInput = it },
                        singleLine = true,
                        label = { Text("https://your-ytdlp-api.example/") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        appPreferences.setYtDlpBackendUrl(endpointInput)
                        showBackendDialog = false
                    },
                ) {
                    Text("Save", color = NeonCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackendDialog = false }) {
                    Text("Cancel", color = MutedSlate)
                }
            },
        )
    }
}

@Composable
private fun AutoDownloadActionDock(
    state: AutoVideoDownloadState,
    cooldownSeconds: Int,
    currentUrl: String,
    onTriggerDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismissStatus: () -> Unit,
) {
    val isBusy = state is AutoVideoDownloadState.Scanning ||
        state is AutoVideoDownloadState.ResolvingPage ||
        state is AutoVideoDownloadState.DownloadingDirect

    val isHttpPage = currentUrl.startsWith("http://", ignoreCase = true) ||
        currentUrl.startsWith("https://", ignoreCase = true)

    val canClickDownload = isHttpPage && !isBusy && cooldownSeconds <= 0

    Surface(
        color = AbyssalSurface,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(visible = state !is AutoVideoDownloadState.Idle) {
                when (state) {
                    is AutoVideoDownloadState.Scanning -> {
                        StatusBannerCard(
                            borderColor = NeonCyan.copy(alpha = 0.45f),
                            leading = {
                                CircularProgressIndicator(
                                    color = NeonCyan,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            title = "Scanning active video on page...",
                            subtitle = state.pageUrl,
                        )
                    }

                    is AutoVideoDownloadState.ResolvingPage -> {
                        StatusBannerCard(
                            borderColor = PlasmaViolet.copy(alpha = 0.5f),
                            leading = {
                                CircularProgressIndicator(
                                    color = PlasmaViolet,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            title = "Resolving protected/blob stream",
                            subtitle = state.reason,
                            trailing = {
                                IconButton(
                                    onClick = onCancelDownload,
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Cancel resolving",
                                        tint = MutedSlate,
                                    )
                                }
                            },
                        )
                    }

                    is AutoVideoDownloadState.DownloadingDirect -> {
                        Surface(
                            color = ObsidianVoid,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(0.8.dp, NeonCyan.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = state.title,
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            color = ChromeSilver,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val sizeLabel = if (state.totalBytes > 0L) {
                                            "${formatByteSize(state.bytesDownloaded)} / ${formatByteSize(state.totalBytes)}"
                                        } else {
                                            formatByteSize(state.bytesDownloaded)
                                        }
                                        Text(
                                            text = "${(state.progress * 100).toInt()}% · $sizeLabel",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NeonCyan,
                                        )
                                    }
                                    IconButton(
                                        onClick = onCancelDownload,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("auto_download_cancel_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Cancel download",
                                            tint = CrimsonAlert,
                                        )
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { state.progress.coerceIn(0f, 1f) },
                                    color = NeonCyan,
                                    trackColor = GlassStrokeSubtle,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .testTag("auto_download_progress_bar"),
                                )
                            }
                        }
                    }

                    is AutoVideoDownloadState.Cooldown -> {
                        StatusBannerCard(
                            borderColor = AmberWarning.copy(alpha = 0.5f),
                            leading = {
                                Icon(
                                    imageVector = Icons.Rounded.Timer,
                                    contentDescription = null,
                                    tint = AmberWarning,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            title = if (state.isRateLimitBackoff) {
                                "Rate-limit backoff: ${state.remainingSeconds}s"
                            } else {
                                "Cooldown active: ${state.remainingSeconds}s"
                            },
                            subtitle = state.note ?: "Wait for the cooldown timer before running another scan.",
                        )
                    }

                    is AutoVideoDownloadState.Completed -> {
                        StatusBannerCard(
                            borderColor = EmeraldPulse.copy(alpha = 0.55f),
                            leading = {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = EmeraldPulse,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            title = "Saved encrypted to Vault: ${state.title}",
                            subtitle = if (cooldownSeconds > 0) {
                                "Cooldown ready in ${cooldownSeconds}s"
                            } else {
                                state.savedLocation
                            },
                            trailing = {
                                IconButton(
                                    onClick = onDismissStatus,
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Dismiss",
                                        tint = MutedSlate,
                                    )
                                }
                            },
                        )
                    }

                    is AutoVideoDownloadState.Error -> {
                        StatusBannerCard(
                            borderColor = CrimsonAlert.copy(alpha = 0.55f),
                            leading = {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = CrimsonAlert,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            title = if (state.isDrmOrRestricted) {
                                "Protected / Restricted Stream"
                            } else {
                                "Download Unavailable"
                            },
                            subtitle = state.message,
                            trailing = {
                                IconButton(
                                    onClick = onDismissStatus,
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Dismiss error",
                                        tint = MutedSlate,
                                    )
                                }
                            },
                        )
                    }

                    AutoVideoDownloadState.Idle -> Unit
                }
            }

            val buttonLabel = when {
                state is AutoVideoDownloadState.Scanning -> "Scanning Active Video..."
                state is AutoVideoDownloadState.ResolvingPage -> "Resolving Page URL..."
                state is AutoVideoDownloadState.DownloadingDirect ->
                    "Downloading ${(state.progress * 100).toInt()}%"
                cooldownSeconds > 0 -> "Cooldown (${cooldownSeconds}s)"
                else -> "Download Video"
            }

            Button(
                onClick = onTriggerDownload,
                enabled = canClickDownload,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = ObsidianVoid,
                    disabledContainerColor = DimSlate.copy(alpha = 0.45f),
                    disabledContentColor = ChromeSilver.copy(alpha = 0.65f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("vault_auto_download_button"),
            ) {
                when {
                    isBusy -> {
                        CircularProgressIndicator(
                            color = ChromeSilver,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    cooldownSeconds > 0 -> {
                        Icon(
                            imageVector = Icons.Rounded.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = buttonLabel,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusBannerCard(
    borderColor: androidx.compose.ui.graphics.Color,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = ObsidianVoid,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.8.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            leading()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ChromeSilver,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}
