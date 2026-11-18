package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.data.local.DownloadedVideoEntity
import com.example.engine.network.ClientSpoofer
import com.example.ui.components.LiquidGlassCard
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanGlowSoft
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.ui.viewmodel.BlackholeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class VaultPlatformLoginTarget(
    val label: String,
    val url: String,
)

private val VaultQuickLoginTargets = listOf(
    VaultPlatformLoginTarget("IG", "https://www.instagram.com/accounts/login/"),
    VaultPlatformLoginTarget("FB", "https://m.facebook.com/login/"),
    VaultPlatformLoginTarget("YT", "https://accounts.google.com/ServiceLogin?service=youtube"),
    VaultPlatformLoginTarget("TT", "https://www.tiktok.com/login"),
    VaultPlatformLoginTarget("X", "https://x.com/i/flow/login"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultScreen(
    viewModel: BlackholeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showInlineWebLogin by remember { mutableStateOf(false) }
    var webLoginUrl by remember { mutableStateOf(VaultQuickLoginTargets.first().url) }
    var previewingVideo by remember { mutableStateOf<DownloadedVideoEntity?>(null) }

    val cookiesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importCookiesFromUri(uri)
        }
    }

    BackHandler {
        when {
            previewingVideo != null -> previewingVideo = null
            showInlineWebLogin -> showInlineWebLogin = false
            else -> onBack()
        }
    }

    val totalSizeBytes = remember(videos) {
        videos.sumOf { it.fileSizeBytes.coerceAtLeast(0L) }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("vault_screen"),
        containerColor = ObsidianVoid,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianVoid)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                PlasmaViolet.copy(alpha = 0.14f),
                                NeonCyan.copy(alpha = 0.06f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.22f),
                            radius = size.minDimension * 0.78f,
                        ),
                        radius = size.minDimension * 0.78f,
                        center = Offset(size.width * 0.5f, size.height * 0.22f),
                    )
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                // Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        IconButton(
                            onClick = {
                                when {
                                    previewingVideo != null -> previewingVideo = null
                                    showInlineWebLogin -> showInlineWebLogin = false
                                    else -> onBack()
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("vault_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Return to Blackhole",
                                tint = ChromeSilver,
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "VAULT",
                                style = MaterialTheme.typography.titleLarge,
                                color = ChromeSilver,
                            )
                            Text(
                                text = "ENCRYPTED SESSION & MEDIA VAULT",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSlate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    val hasSessions = uiState.syncedDomains.isNotEmpty()
                    Surface(
                        color = if (hasSessions) {
                            EmeraldPulse.copy(alpha = 0.14f)
                        } else {
                            Color.White.copy(alpha = 0.05f)
                        },
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            width = 0.85.dp,
                            color = if (hasSessions) {
                                EmeraldPulse.copy(alpha = 0.65f)
                            } else {
                                GlassStrokeBright.copy(alpha = 0.3f)
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = if (hasSessions) EmeraldPulse else MutedSlate,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (hasSessions) "AES-256 · ARMED" else "AES-256",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hasSessions) EmeraldPulse else MutedSlate,
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Section 1: Encrypted Session Cookies Vault
                    item(key = "session_vault_card") {
                        LiquidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            highlighted = uiState.syncedDomains.isNotEmpty(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.Cookie,
                                            contentDescription = null,
                                            tint = PlasmaViolet,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "SESSION COOKIES",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = ChromeSilver,
                                        )
                                    }
                                    Text(
                                        text = "${uiState.syncedDomains.size} DOMAINS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (uiState.syncedDomains.isNotEmpty()) NeonCyan else MutedSlate,
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "Store Netscape cookies.txt or authenticate inline to bypass Instagram, Facebook, and YouTube restrictions.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedSlate,
                                )

                                if (uiState.syncedDomains.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        uiState.syncedDomains.forEach { domain ->
                                            Surface(
                                                color = CyanGlowSoft.copy(alpha = 0.25f),
                                                shape = RoundedCornerShape(50),
                                                border = BorderStroke(
                                                    width = 0.75.dp,
                                                    color = NeonCyan.copy(alpha = 0.5f),
                                                ),
                                            ) {
                                                Text(
                                                    text = domain,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = NeonCyan,
                                                    modifier = Modifier.padding(
                                                        horizontal = 10.dp,
                                                        vertical = 5.dp,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }

                                AnimatedVisibility(
                                    visible = !uiState.vaultStatusMessage.isNullOrBlank(),
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                ) {
                                    uiState.vaultStatusMessage?.let { statusMsg ->
                                        Column {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Surface(
                                                color = Color.White.copy(alpha = 0.05f),
                                                shape = RoundedCornerShape(14.dp),
                                                border = BorderStroke(
                                                    width = 0.75.dp,
                                                    color = NeonCyan.copy(alpha = 0.4f),
                                                ),
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                ) {
                                                    Text(
                                                        text = statusMsg,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = ChromeSilver,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    IconButton(
                                                        onClick = viewModel::clearVaultStatusMessage,
                                                        modifier = Modifier.size(32.dp),
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Close,
                                                            contentDescription = "Dismiss status",
                                                            tint = MutedSlate,
                                                            modifier = Modifier.size(16.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            cookiesPickerLauncher.launch(
                                                arrayOf("text/*", "application/json", "*/*"),
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NeonCyan.copy(alpha = 0.18f),
                                            contentColor = NeonCyan,
                                        ),
                                        border = BorderStroke(0.9.dp, NeonCyan.copy(alpha = 0.65f)),
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("vault_import_cookies_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.FileOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "COOKIES.TXT",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { showInlineWebLogin = !showInlineWebLogin },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = if (showInlineWebLogin) NeonCyan else ChromeSilver,
                                        ),
                                        border = BorderStroke(
                                            width = 0.9.dp,
                                            color = if (showInlineWebLogin) {
                                                NeonCyan.copy(alpha = 0.7f)
                                            } else {
                                                GlassStrokeBright.copy(alpha = 0.45f)
                                            },
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("vault_web_login_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Public,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (showInlineWebLogin) "HIDE WEB" else "WEB LOGIN",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                        )
                                    }

                                    IconButton(
                                        onClick = viewModel::clearAllCookies,
                                        enabled = uiState.syncedDomains.isNotEmpty(),
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                if (uiState.syncedDomains.isNotEmpty()) {
                                                    CrimsonAlert.copy(alpha = 0.14f)
                                                } else {
                                                    Color.White.copy(alpha = 0.04f)
                                                },
                                            )
                                            .testTag("vault_clear_cookies_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = "Clear all session cookies",
                                            tint = if (uiState.syncedDomains.isNotEmpty()) {
                                                CrimsonAlert
                                            } else {
                                                MutedSlate.copy(alpha = 0.45f)
                                            },
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Inline Expandable WebView Login Panel (Zero Popups)
                    if (showInlineWebLogin) {
                        item(key = "inline_web_login_panel") {
                            InlineWebLoginPanel(
                                initialUrl = webLoginUrl,
                                onUrlChanged = { webLoginUrl = it },
                                onSyncCookies = { currentUrl ->
                                    viewModel.syncWebViewSession(currentUrl)
                                },
                                onClose = { showInlineWebLogin = false },
                            )
                        }
                    }

                    // Section 2: Local Media Vault Summary Header
                    item(key = "media_vault_header") {
                        LiquidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Movie,
                                        contentDescription = null,
                                        tint = NeonCyan,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "CAPTURED MEDIA",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = ChromeSilver,
                                        )
                                        Text(
                                            text = "${videos.size} ${if (videos.size == 1) "VIDEO" else "VIDEOS"} STORED",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MutedSlate,
                                        )
                                    }
                                }

                                Surface(
                                    color = Color.White.copy(alpha = 0.05f),
                                    shape = RoundedCornerShape(50),
                                    border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.3f)),
                                ) {
                                    Text(
                                        text = formatVaultFileSize(totalSizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeonCyan,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                    }

                    if (videos.isEmpty()) {
                        item(key = "empty_media_vault") {
                            LiquidGlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(22.dp),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = "No Captured Videos Yet",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = ChromeSilver,
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Tap the Blackhole core with a copied video link to download directly into the vault.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MutedSlate,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    } else {
                        items(videos, key = { it.id }) { video ->
                            VaultMediaItemCard(
                                video = video,
                                onPlay = { previewingVideo = video },
                                onShare = {
                                    val shareUri = resolveVaultVideoUri(context, video)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = video.mimeType.ifBlank { "video/mp4" }
                                        putExtra(Intent.EXTRA_STREAM, shareUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "Share ${video.title}"),
                                    )
                                },
                                onDelete = { viewModel.deleteVideo(video) },
                            )
                        }
                    }
                }
            }

            // Inline Media Playback Overlay inside VaultScreen (Zero Dialog)
            previewingVideo?.let { activeVideo ->
                VaultInlinePlayerView(
                    video = activeVideo,
                    onDismiss = { previewingVideo = null },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InlineWebLoginPanel(
    initialUrl: String,
    onUrlChanged: (String) -> Unit,
    onSyncCookies: (String) -> Unit,
    onClose: () -> Unit,
) {
    var addressText by remember(initialUrl) { mutableStateOf(initialUrl) }
    var activeLoadUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var zoomPercent by remember { mutableIntStateOf(100) }
    var cssFallbackZoom by remember { mutableIntStateOf(100) }
    var baseScale by remember { mutableStateOf<Float?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val unlockViewportScript = """
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
    """.trimIndent()

    fun zoomVaultPageBy(step: Int) {
        val wv = webViewRef ?: return
        wv.evaluateJavascript(unlockViewportScript, null)
        val nativeChanged = if (step > 0) wv.zoomIn() else wv.zoomOut()
        val nextPercent = (zoomPercent + step).coerceIn(50, 250)
        zoomPercent = nextPercent
        if (!nativeChanged) {
            val nextCss = (cssFallbackZoom + step).coerceIn(50, 250)
            cssFallbackZoom = nextCss
            wv.evaluateJavascript("document.documentElement.style.zoom = '${nextCss}%';", null)
        }
    }

    fun resetVaultZoom() {
        val wv = webViewRef ?: return
        cssFallbackZoom = 100
        zoomPercent = 100
        wv.evaluateJavascript("document.documentElement.style.zoom = '100%';", null)
        var guard = 0
        while (wv.zoomOut() && guard < 16) {
            guard++
        }
    }

    LiquidGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vault_inline_webview_panel"),
        shape = RoundedCornerShape(22.dp),
        highlighted = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VaultQuickLoginTargets.forEach { target ->
                        val isSelected = addressText.contains(
                            target.url.substringAfter("://").substringBefore("/").removePrefix("www.").removePrefix("m."),
                            ignoreCase = true,
                        )
                        Surface(
                            color = if (isSelected) NeonCyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                0.75.dp,
                                if (isSelected) NeonCyan.copy(alpha = 0.7f) else GlassStrokeBright.copy(alpha = 0.25f),
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable {
                                    addressText = target.url
                                    activeLoadUrl = target.url
                                    onUrlChanged(target.url)
                                    webViewRef?.loadUrl(target.url)
                                },
                        ) {
                            Text(
                                text = target.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) NeonCyan else ChromeSilver,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { zoomVaultPageBy(-20) },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("vault_zoom_out_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ZoomOut,
                            contentDescription = "Zoom out page",
                            tint = ChromeSilver,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = "$zoomPercent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (zoomPercent == 100) MutedSlate else NeonCyan,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { resetVaultZoom() }
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                            .testTag("vault_zoom_reset_button"),
                    )
                    IconButton(
                        onClick = { zoomVaultPageBy(+20) },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("vault_zoom_in_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ZoomIn,
                            contentDescription = "Zoom in page",
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("vault_close_webview_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close inline login browser",
                            tint = ChromeSilver,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.25f)),
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
                            onGo = {
                                val normalized = if (addressText.startsWith("http")) addressText else "https://$addressText"
                                activeLoadUrl = normalized
                                onUrlChanged(normalized)
                                webViewRef?.loadUrl(normalized)
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }

                Button(
                    onClick = {
                        val currentUrl = webViewRef?.url ?: activeLoadUrl
                        onSyncCookies(currentUrl)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPulse.copy(alpha = 0.22f),
                        contentColor = EmeraldPulse,
                    ),
                    border = BorderStroke(0.85.dp, EmeraldPulse.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("vault_sync_webview_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "SYNC",
                        style = MaterialTheme.typography.labelSmall,
                    )
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
                        .height(2.dp)
                        .clip(CircleShape),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0A0F17)),
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
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

                                override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                                    super.onScaleChanged(view, oldScale, newScale)
                                    val base = baseScale ?: oldScale.takeIf { it > 0f } ?: newScale
                                    if (baseScale == null && base > 0f) {
                                        baseScale = base
                                    }
                                    if (base > 0f && newScale > 0f) {
                                        zoomPercent = ((newScale / base) * 100f).toInt().coerceIn(50, 300)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    view?.evaluateJavascript(unlockViewportScript, null)
                                    if (!url.isNullOrBlank()) {
                                        addressText = url
                                        onUrlChanged(url)
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                }
                            }
                            loadUrl(activeLoadUrl)
                            webViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun VaultMediaItemCard(
    video: DownloadedVideoEntity,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumbnailBitmap by produceState<ImageBitmap?>(initialValue = null, video.thumbnailPath) {
        value = withContext(Dispatchers.IO) {
            val path = video.thumbnailPath ?: return@withContext null
            val file = File(path)
            if (!file.exists()) return@withContext null
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
    }

    LiquidGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .testTag("vault_video_card_${video.id}"),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0A0F17)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap!!,
                        contentDescription = "Thumbnail for ${video.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Surface(
                    color = ObsidianVoid.copy(alpha = 0.72f),
                    shape = CircleShape,
                    border = BorderStroke(0.85.dp, NeonCyan.copy(alpha = 0.75f)),
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play ${video.title}",
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = ChromeSilver,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${video.platform} · ${video.qualityLabel} · ${formatVaultFileSize(video.fileSizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonCyan.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatVaultDate(video.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("vault_share_video_${video.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Share video",
                        tint = ChromeSilver,
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("vault_delete_video_${video.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete video",
                        tint = CrimsonAlert,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VaultInlinePlayerView(
    video: DownloadedVideoEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playableUri = remember(video.id) { resolveVaultVideoUri(context, video) }

    val exoPlayer = remember(playableUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(playableUri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${video.qualityLabel} · ${video.resolution} · ${formatVaultFileSize(video.fileSizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("vault_close_player_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close media player",
                        tint = ChromeSilver,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun resolveVaultVideoUri(context: Context, video: DownloadedVideoEntity): Uri {
    val file = File(video.filePath)
    if (file.exists()) {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }
    return Uri.parse(video.contentUri)
}

private fun formatVaultFileSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        "${(bytes / 1024L).coerceAtLeast(0L)} KB"
    }
}

private fun formatVaultDate(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM d · HH:mm", Locale.US)
    return formatter.format(Date(timestamp))
}
