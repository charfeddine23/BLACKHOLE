package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.engine.model.DownloadPhase
import com.example.engine.model.DownloadTaskState
import com.example.engine.model.DownloadTelemetry
import com.example.engine.model.ExtractedMediaInfo
import com.example.engine.model.PlatformBadge
import com.example.engine.model.VideoQualityOption
import com.example.engine.updater.UpdateCheckState
import com.example.ui.animation.rememberBlackholeAnimationController
import com.example.ui.components.BlackholeCoreButton
import com.example.ui.components.DownloadTaskCard
import com.example.ui.components.LiquidGlassCard
import com.example.ui.components.QualitySelectionBottomSheet
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanGlowSoft
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.LiquidGlassFill
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.ui.viewmodel.BlackholeUiState
import com.example.ui.viewmodel.BlackholeViewModel
import java.io.File
import java.util.Locale

@Composable
fun BlackholeScreen(
    viewModel: BlackholeViewModel,
    onOpenVault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var pendingDownloadPair by remember {
        mutableStateOf<Pair<ExtractedMediaInfo, VideoQualityOption>?>(null)
    }
    var previewingTask by remember { mutableStateOf<DownloadTaskState?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshClipboardOnLaunch()
                viewModel.checkForUpdatesOnEntry()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel) {
        viewModel.requestInstallPermission.collect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        pendingDownloadPair?.let { (media, option) ->
            viewModel.startDownload(media, option)
            pendingDownloadPair = null
        }
    }

    val initiateDownloadWithPermission: (ExtractedMediaInfo, VideoQualityOption) -> Unit = { media, option ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.startDownload(media, option)
            } else {
                pendingDownloadPair = media to option
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            viewModel.startDownload(media, option)
        }
    }

    BlackholeScreenContent(
        uiState = uiState,
        onUrlChanged = viewModel::onUrlChanged,
        onPasteClick = { viewModel.pasteFromClipboard(autoExtract = false) },
        onExtractClick = { viewModel.extractMediaInfo() },
        onTapBlackhole = { viewModel.extractMediaInfo() },
        onHoldOneSecondVault = onOpenVault,
        onCancelTask = viewModel::cancelTask,
        onRetryTask = viewModel::retryTask,
        onPlayInlineTask = { task -> previewingTask = task },
        onOpenExternalTask = { task -> openInExternalPlayer(context, task) },
        onDeleteTask = viewModel::deleteTask,
        modifier = modifier,
    )

    uiState.extractedMediaForSheet?.let { media ->
        QualitySelectionBottomSheet(
            media = media,
            onDismiss = viewModel::dismissQualitySheet,
            onDownloadSelected = initiateDownloadWithPermission,
        )
    }

    previewingTask?.let { activeTask ->
        InlineTaskPlayerOverlay(
            task = activeTask,
            onDismiss = { previewingTask = null },
        )
    }
}

@Composable
private fun BlackholeScreenContent(
    uiState: BlackholeUiState,
    onUrlChanged: (String) -> Unit,
    onPasteClick: () -> Unit,
    onExtractClick: () -> Unit,
    onTapBlackhole: () -> Unit,
    onHoldOneSecondVault: () -> Unit,
    onCancelTask: (String) -> Unit,
    onRetryTask: (DownloadTaskState) -> Unit,
    onPlayInlineTask: (DownloadTaskState) -> Unit,
    onOpenExternalTask: (DownloadTaskState) -> Unit,
    onDeleteTask: (DownloadTaskState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val telemetry = uiState.telemetry
    val focusManager = LocalFocusManager.current
    val isBusy = telemetry.phase == DownloadPhase.ABSORBING ||
        telemetry.phase == DownloadPhase.DOWNLOADING ||
        telemetry.phase == DownloadPhase.MERGING ||
        uiState.isExtracting

    val animationController = rememberBlackholeAnimationController(
        phase = telemetry.phase,
        targetProgress = telemetry.progressFraction,
    )

    val isUpdateActive = uiState.updateState is UpdateCheckState.Downloading ||
        uiState.updateState is UpdateCheckState.ReadyToInstall
    val updateRingProgress = when (val st = uiState.updateState) {
        is UpdateCheckState.Downloading -> st.progress
        is UpdateCheckState.ReadyToInstall -> 1f
        else -> 0f
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
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
                                NeonCyan.copy(alpha = 0.08f),
                                PlasmaViolet.copy(alpha = 0.05f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.25f),
                            radius = size.minDimension * 0.72f,
                        ),
                        radius = size.minDimension * 0.72f,
                        center = Offset(size.width * 0.5f, size.height * 0.25f),
                    )
                },
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Header Branding
                item(key = "header") {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "BLACKHOLE",
                            style = MaterialTheme.typography.displaySmall,
                            color = ChromeSilver,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = Color.White.copy(alpha = 0.04f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.24f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isBusy) NeonCyan else EmeraldPulse),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "YOUTUBE · INSTAGRAM · FACEBOOK · PULSE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedSlate,
                                )
                            }
                        }
                    }
                }

                // Hypnotic Blackhole Core (Tap to Extract · Hold 1s for Vault)
                item(key = "blackhole_core") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BlackholeCoreButton(
                            phase = telemetry.phase,
                            qualityLabel = telemetry.qualityLabel,
                            controller = animationController,
                            onTapBlackhole = {
                                focusManager.clearFocus()
                                onTapBlackhole()
                            },
                            onHoldOneSecondVault = onHoldOneSecondVault,
                            isUpdateActive = isUpdateActive,
                            updateProgress = updateRingProgress,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        BlackholeBareStatusCaption(telemetry = telemetry)
                    }
                }

                // URL Input Card with One-Tap Paste & Automatic Platform Detection Badge
                item(key = "url_input_card") {
                    LiquidGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "VIDEO LINK",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MutedSlate,
                                )
                                AnimatedVisibility(
                                    visible = uiState.inputUrl.isNotBlank(),
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                ) {
                                    PlatformDetectionBadge(platform = uiState.detectedPlatform)
                                }
                            }

                            OutlinedTextField(
                                value = uiState.inputUrl,
                                onValueChange = onUrlChanged,
                                singleLine = true,
                                placeholder = {
                                    Text(
                                        text = "Paste YouTube, Instagram, or Facebook link...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MutedSlate.copy(alpha = 0.7f),
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Link,
                                        contentDescription = null,
                                        tint = NeonCyan,
                                    )
                                },
                                trailingIcon = {
                                    if (uiState.inputUrl.isNotBlank()) {
                                        IconButton(
                                            onClick = { onUrlChanged("") },
                                            modifier = Modifier.minimumInteractiveComponentSize(),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Clear,
                                                contentDescription = "Clear URL",
                                                tint = MutedSlate,
                                            )
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Search,
                                ),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        focusManager.clearFocus()
                                        onExtractClick()
                                    },
                                ),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonCyan,
                                    unfocusedBorderColor = GlassStrokeBright.copy(alpha = 0.25f),
                                    focusedTextColor = ChromeSilver,
                                    unfocusedTextColor = ChromeSilver,
                                    cursorColor = NeonCyan,
                                    focusedContainerColor = ObsidianVoid.copy(alpha = 0.55f),
                                    unfocusedContainerColor = ObsidianVoid.copy(alpha = 0.45f),
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("url_input_field"),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        focusManager.clearFocus()
                                        onPasteClick()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("paste_clipboard_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, GlassStrokeBright.copy(alpha = 0.35f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = ChromeSilver,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentPaste,
                                        contentDescription = "Paste from clipboard",
                                        modifier = Modifier.size(18.dp),
                                        tint = NeonCyan,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "PASTE",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }

                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        onExtractClick()
                                    },
                                    enabled = !uiState.isExtracting,
                                    modifier = Modifier
                                        .weight(1.3f)
                                        .height(48.dp)
                                        .testTag("extract_quality_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NeonCyan,
                                        contentColor = ObsidianVoid,
                                    ),
                                ) {
                                    if (uiState.isExtracting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = ObsidianVoid,
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "EXTRACTING",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "GET VIDEO",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Active & Completed Downloads Header
                item(key = "downloads_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "DOWNLOADS",
                            style = MaterialTheme.typography.labelLarge,
                            color = ChromeSilver,
                        )
                        Text(
                            text = "${uiState.downloadTasks.size} ${if (uiState.downloadTasks.size == 1) "ITEM" else "ITEMS"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlate,
                        )
                    }
                }

                if (uiState.downloadTasks.isEmpty()) {
                    item(key = "empty_downloads") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = LiquidGlassFill.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.16f)),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DownloadForOffline,
                                    contentDescription = null,
                                    tint = MutedSlate.copy(alpha = 0.7f),
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No downloads yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ChromeSilver,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Paste a link or share directly from YouTube, Instagram, or Facebook. Videos save to Movies/BlackHole.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MutedSlate,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                } else {
                    items(uiState.downloadTasks, key = { it.taskId }) { task ->
                        DownloadTaskCard(
                            task = task,
                            onCancel = { onCancelTask(task.taskId) },
                            onRetry = { onRetryTask(task) },
                            onPlayInline = { onPlayInlineTask(task) },
                            onOpenExternal = { onOpenExternalTask(task) },
                            onDelete = { onDeleteTask(task) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformDetectionBadge(platform: PlatformBadge) {
    val badgeColor = when (platform) {
        PlatformBadge.YOUTUBE -> CrimsonAlert
        PlatformBadge.INSTAGRAM -> PlasmaViolet
        PlatformBadge.FACEBOOK -> Color(0xFF4C8DFF)
        PlatformBadge.TIKTOK -> NeonCyan
        else -> EmeraldPulse
    }
    Surface(
        color = badgeColor.copy(alpha = 0.16f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(0.75.dp, badgeColor.copy(alpha = 0.65f)),
        modifier = Modifier.testTag("platform_detection_badge"),
    ) {
        Text(
            text = platform.label,
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun BlackholeBareStatusCaption(
    telemetry: DownloadTelemetry,
) {
    val captionData: Pair<String, Color>? = when (telemetry.phase) {
        DownloadPhase.ERROR -> {
            (telemetry.errorMessage?.takeIf { it.isNotBlank() }
                ?: "Unable to download stream from this link.") to CrimsonAlert
        }
        DownloadPhase.NEEDS_AUTH -> {
            (telemetry.errorMessage?.takeIf { it.isNotBlank() }
                ?: "This video requires account session authentication.") to PlasmaViolet
        }
        DownloadPhase.ABSORBING -> {
            "Extracting ${telemetry.platform.label} resolutions..." to NeonCyan
        }
        DownloadPhase.DOWNLOADING -> {
            val sizePart = if (telemetry.totalBytes > 0L) {
                "${formatCaptionMb(telemetry.bytesDownloaded)} / ${formatCaptionMb(telemetry.totalBytes)}"
            } else {
                formatCaptionMb(telemetry.bytesDownloaded)
            }
            val speedPart = if (telemetry.speedBytesPerSec > 0L) {
                " · ${formatCaptionSpeed(telemetry.speedBytesPerSec)}"
            } else {
                ""
            }
            "${telemetry.qualityLabel} · $sizePart$speedPart" to ChromeSilver
        }
        DownloadPhase.MERGING -> {
            "Merging ${telemetry.qualityLabel} video + audio via MediaMuxer..." to PlasmaViolet
        }
        DownloadPhase.COMPLETED -> {
            "Saved: ${telemetry.title} (${telemetry.qualityLabel})" to EmeraldPulse
        }
        DownloadPhase.CLIPBOARD_ARMED -> {
            "${telemetry.platform.label} detected · tap core or GET VIDEO" to MutedSlate
        }
        DownloadPhase.IDLE -> null
    }

    AnimatedVisibility(
        visible = captionData != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        captionData?.let { (message, color) ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .testTag("blackhole_status_caption"),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun InlineTaskPlayerOverlay(
    task: DownloadTaskState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler(onBack = onDismiss)

    val playableUri = remember(task.taskId) {
        val local = task.localFilePath?.let { File(it) }
        if (local != null && local.exists()) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", local)
        } else {
            Uri.parse(task.mediaStoreUri.orEmpty())
        }
    }

    val exoPlayer = remember(playableUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(playableUri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
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
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${task.platform.label} · ${task.qualityLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close player",
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

private fun openInExternalPlayer(context: Context, task: DownloadTaskState) {
    runCatching {
        val local = task.localFilePath?.let { File(it) }
        val uri = if (local != null && local.exists()) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", local)
        } else {
            Uri.parse(task.mediaStoreUri.orEmpty())
        }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(viewIntent, "Play Video"))
    }
}

private fun formatCaptionMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}

private fun formatCaptionSpeed(bytesPerSec: Long): String {
    val mb = bytesPerSec / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB/s", mb)
    } else {
        "${(bytesPerSec / 1024L).coerceAtLeast(1L)} KB/s"
    }
}
