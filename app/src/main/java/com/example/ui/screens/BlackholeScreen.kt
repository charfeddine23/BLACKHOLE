package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.DownloadedVideoEntity
import com.example.engine.model.DownloadPhase
import com.example.engine.model.DownloadTelemetry
import com.example.engine.updater.UpdateCheckState
import com.example.ui.animation.rememberBlackholeAnimationController
import com.example.ui.components.BlackholeCoreButton
import com.example.ui.components.DownloadedVideosSwipeUpSheet
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.ui.viewmodel.BlackholeUiState
import com.example.ui.viewmodel.BlackholeViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun BlackholeScreen(
    viewModel: BlackholeViewModel,
    onNavigateToVault: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Check clipboard and check for updates every time the user enters / resumes the app
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

    // Handle one-time OS install permission handoff if required by PackageInstaller
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
        viewModel.triggerGravityPull()
    }

    val initiatePullWithPermissionCheck = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.triggerGravityPull()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            viewModel.triggerGravityPull()
        }
    }

    BlackholeScreenContent(
        uiState = uiState,
        videos = videos,
        onTapBlackhole = initiatePullWithPermissionCheck,
        onHoldVault = onNavigateToVault,
        onDeleteVideo = viewModel::deleteVideo,
        onAutoSyncCookies = { url -> viewModel.syncWebViewSession(url, silent = true) },
        onManualSyncCookies = { url -> viewModel.syncWebViewSession(url, silent = false) },
        onRestoreCookiesToBrowser = viewModel::restoreBrowserCookies,
        onTogglePermanentCookieLock = viewModel::togglePermanentCookieLock,
        onClearCookies = viewModel::clearAllCookies,
        onPullFromBrowser = viewModel::triggerGravityPullForUrl,
        onDismissVaultStatus = viewModel::clearVaultStatusMessage,
        modifier = modifier,
    )
}

@Composable
private fun BlackholeScreenContent(
    uiState: BlackholeUiState,
    videos: List<DownloadedVideoEntity>,
    onTapBlackhole: () -> Unit,
    onHoldVault: () -> Unit,
    onDeleteVideo: (DownloadedVideoEntity) -> Unit,
    onAutoSyncCookies: (String) -> Unit,
    onManualSyncCookies: (String) -> Unit,
    onRestoreCookiesToBrowser: () -> Unit,
    onTogglePermanentCookieLock: () -> Unit,
    onClearCookies: () -> Unit,
    onPullFromBrowser: (String) -> Unit,
    onDismissVaultStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val telemetry = uiState.telemetry
    val isBusy = telemetry.phase == DownloadPhase.ABSORBING ||
        telemetry.phase == DownloadPhase.DOWNLOADING ||
        telemetry.phase == DownloadPhase.MERGING

    val animationController = rememberBlackholeAnimationController(
        phase = telemetry.phase,
        targetProgress = telemetry.progressFraction,
    )

    val drawerProgress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ObsidianVoid,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianVoid),
        ) {
            val density = LocalDensity.current
            val maxHeightPx = remember(maxHeight, density) {
                with(density) { maxHeight.toPx().coerceAtLeast(1f) }
            }

            var liveDragProgress by remember { mutableFloatStateOf(0f) }
            var dragStartedOpen by remember { mutableStateOf(false) }

            val snapDrawer: (Boolean) -> Unit = remember(coroutineScope) {
                { shouldOpen ->
                    val target = if (shouldOpen) 1f else 0f
                    liveDragProgress = target
                    coroutineScope.launch {
                        drawerProgress.animateTo(
                            targetValue = target,
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }
            }

            val beginDrag: () -> Unit = remember {
                {
                    liveDragProgress = drawerProgress.value
                    dragStartedOpen = liveDragProgress > 0.5f
                }
            }

            val applyDragDeltaPx: (Float) -> Unit = remember(coroutineScope, maxHeightPx) {
                { deltaY ->
                    val next = (liveDragProgress - deltaY / maxHeightPx).coerceIn(0f, 1f)
                    liveDragProgress = next
                    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        drawerProgress.snapTo(next)
                    }
                }
            }

            val finishDragWithVelocity: (Float) -> Unit = remember(snapDrawer) {
                { velocityY ->
                    val current = liveDragProgress
                    val shouldOpen = when {
                        velocityY < -220f -> true
                        velocityY > 220f -> false
                        dragStartedOpen -> current > 0.82f
                        else -> current > 0.18f
                    }
                    snapDrawer(shouldOpen)
                }
            }

            val scrollState = rememberScrollState()
            val homeNestedScroll = remember(maxHeightPx, scrollState) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source != NestedScrollSource.UserInput) return Offset.Zero
                        val atBottom = scrollState.maxValue == 0 || scrollState.value >= scrollState.maxValue
                        if (drawerProgress.value > 0.001f || (available.y < 0f && atBottom)) {
                            applyDragDeltaPx(available.y)
                            return Offset(0f, available.y)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (drawerProgress.value > 0.001f) {
                            finishDragWithVelocity(available.y)
                            return available
                        }
                        return Velocity.Zero
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = drawerProgress.value
                        val scale = 1f - 0.05f * p
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.42f * p
                    }
                    .pointerInput(maxHeightPx) {
                        val velocityTracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = {
                                velocityTracker.resetTracking()
                                beginDrag()
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                applyDragDeltaPx(dragAmount)
                            },
                            onDragEnd = {
                                finishDragWithVelocity(velocityTracker.calculateVelocity().y)
                            },
                            onDragCancel = {
                                finishDragWithVelocity(0f)
                            },
                        )
                    }
                    .drawBehind {
                        // Deep atmospheric obsidian & subtle cyan/violet singularity aura
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    NeonCyan.copy(alpha = 0.08f),
                                    PlasmaViolet.copy(alpha = 0.05f),
                                    Color.Transparent,
                                ),
                                center = Offset(size.width * 0.5f, size.height * 0.48f),
                                radius = size.minDimension * 0.72f,
                            ),
                            radius = size.minDimension * 0.72f,
                            center = Offset(size.width * 0.5f, size.height * 0.48f),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 600.dp)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Minimalist Header
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
                                    text = "PULSE SUITE · CHARFEDDINE KA",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedSlate,
                                )
                            }
                        }
                    }

                    // Centerpiece Blackhole Core (with silent perimeter update ring)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .nestedScroll(homeNestedScroll)
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val isUpdateActive = uiState.updateState is UpdateCheckState.Downloading ||
                            uiState.updateState is UpdateCheckState.ReadyToInstall
                        val updateRingProgress = when (val st = uiState.updateState) {
                            is UpdateCheckState.Downloading -> st.progress
                            is UpdateCheckState.ReadyToInstall -> 1f
                            else -> 0f
                        }
                        BlackholeCoreButton(
                            phase = telemetry.phase,
                            qualityLabel = telemetry.qualityLabel,
                            controller = animationController,
                            onTapBlackhole = onTapBlackhole,
                            isUpdateActive = isUpdateActive,
                            updateProgress = updateRingProgress,
                            onHoldVault = onHoldVault,
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        BlackholeBareStatusCaption(telemetry = telemetry)
                    }

                    // Swipe-Up Downloaded Videos Page Indicator (only secondary page in app)
                    SwipeUpVaultIndicator(
                        videoCount = uiState.videoCount,
                        onSwipeTap = { snapDrawer(true) },
                    )
                }
            }

            if (drawerProgress.value > 0.001f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ObsidianVoid.copy(alpha = 0.72f * drawerProgress.value)),
                )
                DownloadedVideosSwipeUpSheet(
                    videos = videos,
                    drawerProgress = drawerProgress.value,
                    maxHeightPx = maxHeightPx,
                    onDragStart = beginDrag,
                    onDragByPx = applyDragDeltaPx,
                    onDragFinish = finishDragWithVelocity,
                    onCollapse = { snapDrawer(false) },
                    onDeleteVideo = onDeleteVideo,
                    syncedDomains = uiState.syncedDomains,
                    storedCookieCount = uiState.storedCookieCount,
                    isPermanentCookieLock = uiState.isPermanentCookieLock,
                    vaultStatusMessage = uiState.vaultStatusMessage,
                    onAutoSyncCookies = onAutoSyncCookies,
                    onManualSyncCookies = onManualSyncCookies,
                    onRestoreCookiesToBrowser = onRestoreCookiesToBrowser,
                    onTogglePermanentCookieLock = onTogglePermanentCookieLock,
                    onClearCookies = onClearCookies,
                    onPullFromBrowser = onPullFromBrowser,
                    onDismissVaultStatus = onDismissVaultStatus,
                )
            }
        }
    }
}

@Composable
private fun SwipeUpVaultIndicator(
    videoCount: Int,
    onSwipeTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSwipeTap)
            .padding(vertical = 6.dp)
            .testTag("swipe_up_videos_indicator"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowUp,
            contentDescription = "Swipe up to view downloaded videos",
            tint = if (videoCount > 0) NeonCyan.copy(alpha = 0.72f) else MutedSlate.copy(alpha = 0.65f),
            modifier = Modifier.size(18.dp),
        )
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(3.5.dp)
                .clip(CircleShape)
                .background(
                    if (videoCount > 0) {
                        NeonCyan.copy(alpha = 0.55f)
                    } else {
                        GlassStrokeBright.copy(alpha = 0.35f)
                    },
                ),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (videoCount > 0) "SWIPE UP · $videoCount" else "SWIPE UP",
            style = MaterialTheme.typography.labelSmall,
            color = MutedSlate.copy(alpha = 0.72f),
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
            "Extracting highest quality ${telemetry.platform.label} stream..." to NeonCyan
        }
        DownloadPhase.DOWNLOADING -> {
            val sizePart = if (telemetry.totalBytes > 0L) {
                "${formatCaptionMb(telemetry.bytesDownloaded)} / ${formatCaptionMb(telemetry.totalBytes)}"
            } else {
                formatCaptionMb(telemetry.bytesDownloaded)
            }
            val speedPart = formatCaptionSpeed(telemetry.speedBytesPerSec)
            val titlePart = telemetry.title.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
            "$titlePart${telemetry.qualityLabel} · $sizePart · $speedPart" to ChromeSilver
        }
        DownloadPhase.MERGING -> {
            "Merging ${telemetry.qualityLabel} video and audio tracks..." to PlasmaViolet
        }
        DownloadPhase.COMPLETED -> {
            "Captured: ${telemetry.title} (${telemetry.qualityLabel})" to EmeraldPulse
        }
        DownloadPhase.CLIPBOARD_ARMED -> {
            "${telemetry.platform.label} link detected · tap core to download" to MutedSlate
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
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .testTag("blackhole_status_caption"),
            )
        }
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
