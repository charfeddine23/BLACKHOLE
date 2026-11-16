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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.data.local.DownloadedVideoEntity
import com.example.engine.model.DownloadPhase
import com.example.engine.updater.UpdateCheckState
import com.example.ui.animation.rememberBlackholeAnimationController
import com.example.ui.components.BlackholeCoreButton
import com.example.ui.components.CookieVaultDialog
import com.example.ui.components.DownloadedVideosSwipeUpSheet
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
import com.example.ui.viewmodel.BlackholeUiState
import com.example.ui.viewmodel.BlackholeViewModel
import kotlinx.coroutines.launch

@Composable
fun BlackholeScreen(
    viewModel: BlackholeViewModel,
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

    // Handle one-time OS "Allow install from this source" toggle only if system requires it for PackageInstaller
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
        // Foreground service still runs cleanly regardless of notification prompt choice
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
        onAbortPull = viewModel::abortGravityPull,
        onDeleteVideo = viewModel::deleteVideo,
        onTapUpdatePill = viewModel::onUpdatePillTap,
        onOpenCookieVault = { viewModel.openCookieVault(null) },
        onSignInToUnlock = { authUrl -> viewModel.openCookieVault(authUrl) },
        modifier = modifier,
    )

    if (uiState.showCookieVault) {
        CookieVaultDialog(
            initialAuthUrl = uiState.authWebViewUrl,
            syncedDomains = uiState.syncedDomains,
            statusMessage = uiState.cookieStatusMessage,
            onImportCookiesUri = viewModel::importCookiesFromUri,
            onSyncWebViewSession = viewModel::syncWebViewSession,
            onClearCookies = viewModel::clearAllCookies,
            onDismiss = viewModel::dismissCookieVault,
        )
    }
}

@Composable
private fun BlackholeScreenContent(
    uiState: BlackholeUiState,
    videos: List<DownloadedVideoEntity>,
    onTapBlackhole: () -> Unit,
    onAbortPull: () -> Unit,
    onDeleteVideo: (DownloadedVideoEntity) -> Unit,
    onTapUpdatePill: () -> Unit,
    onOpenCookieVault: () -> Unit,
    onSignInToUnlock: (String) -> Unit,
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

            val snapDrawer: (Boolean) -> Unit = remember(coroutineScope) {
                { shouldOpen ->
                    coroutineScope.launch {
                        drawerProgress.animateTo(
                            targetValue = if (shouldOpen) 1f else 0f,
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }
            }

            val applyDragDeltaPx: (Float) -> Unit = remember(coroutineScope, maxHeightPx) {
                { deltaY ->
                    coroutineScope.launch {
                        val next = (drawerProgress.value - deltaY / maxHeightPx).coerceIn(0f, 1f)
                        drawerProgress.snapTo(next)
                    }
                }
            }

            val finishDragWithVelocity: (Float) -> Unit = remember(snapDrawer) {
                { velocityY ->
                    val current = drawerProgress.value
                    val shouldOpen = when {
                        velocityY < -420f -> true
                        velocityY > 420f -> false
                        else -> current > 0.25f
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
                            onDragStart = { velocityTracker.resetTracking() },
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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

                        val updateState = uiState.updateState
                        val isCheckingUpdate = updateState is UpdateCheckState.Checking
                        val isDownloadingUpdate = updateState is UpdateCheckState.Downloading
                        val isUpdateReady = updateState is UpdateCheckState.ReadyToInstall
                        val highlightUpdate = isDownloadingUpdate || isUpdateReady
                        Surface(
                            color = if (highlightUpdate) CyanGlowSoft.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.04f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                0.75.dp,
                                if (highlightUpdate) NeonCyan.copy(alpha = 0.75f) else GlassStrokeBright.copy(alpha = 0.24f),
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onTapUpdatePill)
                                .testTag("update_check_pill"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isCheckingUpdate) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(10.dp),
                                        strokeWidth = 1.5.dp,
                                        color = NeonCyan,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.SystemUpdate,
                                        contentDescription = "Check for app updates",
                                        tint = if (highlightUpdate) NeonCyan else MutedSlate,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(5.dp))
                                val pillText = when (updateState) {
                                    is UpdateCheckState.Downloading -> "${(updateState.progress * 100).toInt()}%"
                                    is UpdateCheckState.ReadyToInstall -> "INSTALL"
                                    else -> "v${BuildConfig.VERSION_NAME}"
                                }
                                Text(
                                    text = pillText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (highlightUpdate) NeonCyan else MutedSlate,
                                )
                            }
                        }

                        val hasCookies = uiState.syncedDomains.isNotEmpty()
                        Surface(
                            color = if (hasCookies) CyanGlowSoft.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                0.75.dp,
                                if (hasCookies) NeonCyan.copy(alpha = 0.5f) else GlassStrokeBright.copy(alpha = 0.24f),
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onOpenCookieVault)
                                .testTag("session_vault_pill"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Cookie,
                                    contentDescription = "Session and Cookie Vault",
                                    tint = if (hasCookies) NeonCyan else MutedSlate,
                                    modifier = Modifier.size(12.dp),
                                )
                                if (hasCookies) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${uiState.syncedDomains.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeonCyan,
                                    )
                                }
                            }
                        }
                    }
                }

                // Centerpiece Blackhole Core + Live Telemetry
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
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    AnimatedVisibility(
                        visible = telemetry.phase == DownloadPhase.ABSORBING ||
                            telemetry.phase == DownloadPhase.DOWNLOADING ||
                            telemetry.phase == DownloadPhase.MERGING ||
                            telemetry.phase == DownloadPhase.COMPLETED ||
                            telemetry.phase == DownloadPhase.NEEDS_AUTH ||
                            telemetry.phase == DownloadPhase.ERROR ||
                            !uiState.clipboardFeedback.isNullOrBlank(),
                        enter = fadeIn() + slideInVertically { it / 3 },
                        exit = fadeOut() + slideOutVertically { it / 3 },
                    ) {
                        TelemetryStatusPanel(
                            uiState = uiState,
                            onAbortPull = onAbortPull,
                            onOpenVideosSheet = { snapDrawer(true) },
                            onSignInToUnlock = onSignInToUnlock,
                        )
                    }
                }

                // Minimalist Swipe-Up Indicator
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
                    onDragByPx = applyDragDeltaPx,
                    onDragFinish = finishDragWithVelocity,
                    onCollapse = { snapDrawer(false) },
                    onDeleteVideo = onDeleteVideo,
                )
            }
        }
    }
}

@Composable
private fun TelemetryStatusPanel(
    uiState: BlackholeUiState,
    onAbortPull: () -> Unit,
    onOpenVideosSheet: () -> Unit,
    onSignInToUnlock: (String) -> Unit,
) {
    val telemetry = uiState.telemetry

    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        highlighted = telemetry.phase == DownloadPhase.DOWNLOADING ||
            telemetry.phase == DownloadPhase.MERGING ||
            telemetry.phase == DownloadPhase.COMPLETED,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            when {
                telemetry.phase == DownloadPhase.ABSORBING -> {
                    Text(
                        text = "RUNNING MULTI-STRATEGY STREAM EXTRACTION...",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(50)),
                        color = NeonCyan,
                        trackColor = Color.White.copy(alpha = 0.08f),
                    )
                }

                telemetry.phase == DownloadPhase.MERGING -> {
                    Text(
                        text = "MUXING HIGHEST VIDEO & AUDIO TRACKS...",
                        style = MaterialTheme.typography.labelSmall,
                        color = PlasmaViolet,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(50)),
                        color = PlasmaViolet,
                        trackColor = Color.White.copy(alpha = 0.08f),
                    )
                }

                telemetry.phase == DownloadPhase.DOWNLOADING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = telemetry.title.ifBlank { "Gravity Pull Active" },
                            style = MaterialTheme.typography.titleMedium,
                            color = ChromeSilver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = formatTransferSpeed(telemetry.speedBytesPerSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (telemetry.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { telemetry.progressFraction.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(50)),
                            color = NeonCyan,
                            trackColor = Color.White.copy(alpha = 0.08f),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(50)),
                            color = NeonCyan,
                            trackColor = Color.White.copy(alpha = 0.08f),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val sizeText = if (telemetry.totalBytes > 0L) {
                            "${formatMb(telemetry.bytesDownloaded)} / ${formatMb(telemetry.totalBytes)}"
                        } else {
                            "${formatMb(telemetry.bytesDownloaded)} pulled"
                        }
                        val etaText = if (telemetry.etaSeconds > 0L) {
                            " · ETA ${formatEtaText(telemetry.etaSeconds)}"
                        } else {
                            ""
                        }
                        Text(
                            text = "$sizeText · ${telemetry.qualityLabel}$etaText",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlate,
                        )
                        TextButton(
                            onClick = onAbortPull,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("abort_download_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Abort download",
                                tint = CrimsonAlert,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "ABORT",
                                style = MaterialTheme.typography.labelSmall,
                                color = CrimsonAlert,
                            )
                        }
                    }
                }

                telemetry.phase == DownloadPhase.COMPLETED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CAPTURED IN LOCAL VAULT",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldPulse,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${telemetry.title} (${telemetry.qualityLabel})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ChromeSilver,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = onOpenVideosSheet,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("open_vault_cta"),
                        ) {
                            Text(
                                text = "OPEN",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonCyan,
                            )
                        }
                    }
                }

                telemetry.phase == DownloadPhase.NEEDS_AUTH -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "SESSION AUTHENTICATION REQUIRED",
                            style = MaterialTheme.typography.labelSmall,
                            color = PlasmaViolet,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = telemetry.errorMessage ?: "Sign in via WebView or import cookies.txt to bypass login gate.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ChromeSilver,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = {
                                    onSignInToUnlock(telemetry.needsAuthUrl ?: telemetry.sourceUrl)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonCyan,
                                    contentColor = ObsidianVoid,
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("sign_in_unlock_button"),
                            ) {
                                Text(
                                    text = "SIGN IN & SYNC",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ObsidianVoid,
                                )
                            }
                        }
                    }
                }

                telemetry.phase == DownloadPhase.ERROR || !uiState.clipboardFeedback.isNullOrBlank() -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = "Extraction error",
                            tint = CrimsonAlert,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = telemetry.errorMessage
                                ?: uiState.clipboardFeedback
                                ?: "Unable to extract stream.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ChromeSilver,
                        )
                    }
                }
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

private fun formatMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return String.format("%.1f MB", mb)
}

private fun formatTransferSpeed(bytesPerSec: Long): String {
    val mb = bytesPerSec / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format("%.1f MB/s", mb)
    } else {
        "${(bytesPerSec / 1024L).coerceAtLeast(1L)} KB/s"
    }
}

private fun formatEtaText(seconds: Long): String {
    val mins = seconds / 60L
    val secs = seconds % 60L
    return String.format("%02d:%02d", mins, secs)
}
