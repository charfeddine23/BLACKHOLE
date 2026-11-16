package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.CheckCircleOutline
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
import androidx.compose.ui.window.Dialog
import com.example.BuildConfig
import com.example.engine.updater.AppUpdateManager
import com.example.engine.updater.UpdateCheckState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.engine.model.DownloadPhase
import com.example.ui.animation.rememberBlackholeAnimationController
import com.example.ui.components.BlackholeCoreButton
import com.example.ui.components.CookieVaultDialog
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

@Composable
fun BlackholeScreen(
    viewModel: BlackholeViewModel,
    onOpenVideosDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Auto-detect clipboard whenever app launches or returns to foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshClipboardOnLaunch()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
        onTapBlackhole = initiatePullWithPermissionCheck,
        onAbortPull = viewModel::abortGravityPull,
        onOpenVideosDrawer = onOpenVideosDrawer,
        onOpenUpdateDialog = viewModel::openUpdateDialog,
        onOpenCookieVault = { viewModel.openCookieVault(null) },
        onSignInToUnlock = { authUrl -> viewModel.openCookieVault(authUrl) },
        modifier = modifier,
    )

    if (uiState.showUpdateDialog) {
        LiquidGlassUpdateDialog(
            updateState = uiState.updateState,
            onDownloadUpdate = { url ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                viewModel.dismissUpdateDialog()
            },
            onRetryCheck = { viewModel.checkForUpdates(silent = false) },
            onDismiss = viewModel::dismissUpdateDialog,
        )
    }

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
    onTapBlackhole: () -> Unit,
    onAbortPull: () -> Unit,
    onOpenVideosDrawer: () -> Unit,
    onOpenUpdateDialog: () -> Unit,
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

                        val hasUpdate = uiState.updateState is UpdateCheckState.UpdateAvailable
                        val isCheckingUpdate = uiState.updateState is UpdateCheckState.Checking
                        Surface(
                            color = if (hasUpdate) CyanGlowSoft.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.04f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                0.75.dp,
                                if (hasUpdate) NeonCyan.copy(alpha = 0.75f) else GlassStrokeBright.copy(alpha = 0.24f),
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onOpenUpdateDialog)
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
                                        tint = if (hasUpdate) NeonCyan else MutedSlate,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (hasUpdate) {
                                        "UPDATE"
                                    } else {
                                        "v${BuildConfig.VERSION_NAME}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (hasUpdate) NeonCyan else MutedSlate,
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
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BlackholeCoreButton(
                        phase = telemetry.phase,
                        qualityLabel = telemetry.qualityLabel,
                        controller = animationController,
                        onTapBlackhole = onTapBlackhole,
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
                            onOpenVideosDrawer = onOpenVideosDrawer,
                            onSignInToUnlock = onSignInToUnlock,
                        )
                    }
                }

                // Minimalist Bottom Footer Drawer Trigger
                VideosDrawerFooterButton(
                    videoCount = uiState.videoCount,
                    onClick = onOpenVideosDrawer,
                )
            }
        }
    }
}

@Composable
private fun TelemetryStatusPanel(
    uiState: BlackholeUiState,
    onAbortPull: () -> Unit,
    onOpenVideosDrawer: () -> Unit,
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
                            onClick = onOpenVideosDrawer,
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
private fun VideosDrawerFooterButton(
    videoCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, GlassStrokeBright.copy(alpha = 0.25f)),
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .testTag("videos_drawer_button"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = null,
                tint = MutedSlate,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Outlined.VideoLibrary,
                contentDescription = "Open Videos Gallery Drawer",
                tint = NeonCyan,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "VIDEOS",
                style = MaterialTheme.typography.labelLarge,
                color = ChromeSilver,
            )
            if (videoCount > 0) {
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    color = CyanGlowSoft.copy(alpha = 0.35f),
                    shape = CircleShape,
                    border = BorderStroke(0.75.dp, NeonCyan.copy(alpha = 0.6f)),
                ) {
                    Text(
                        text = videoCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
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

@Composable
private fun LiquidGlassUpdateDialog(
    updateState: UpdateCheckState,
    onDownloadUpdate: (String) -> Unit,
    onRetryCheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        LiquidGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            highlighted = updateState is UpdateCheckState.UpdateAvailable,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
            ) {
                when (updateState) {
                    is UpdateCheckState.UpdateAvailable -> {
                        val release = updateState.release
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.SystemUpdate,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "UPDATE AVAILABLE",
                                style = MaterialTheme.typography.labelLarge,
                                color = NeonCyan,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}  ->  ${release.tagName}",
                            style = MaterialTheme.typography.titleMedium,
                            color = ChromeSilver,
                        )
                        if (!release.apkFileName.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val sizeLabel = if (release.apkSizeBytes > 0L) {
                                " · ${formatMb(release.apkSizeBytes)}"
                            } else {
                                ""
                            }
                            Text(
                                text = "${release.apkFileName}$sizeLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSlate,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = release.releaseNotes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ChromeSilver.copy(alpha = 0.88f),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Text(
                                    text = "LATER",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedSlate,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onDownloadUpdate(release.preferredLaunchUrl) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonCyan,
                                    contentColor = ObsidianVoid,
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("download_update_button"),
                            ) {
                                Text(
                                    text = "DOWNLOAD APK",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ObsidianVoid,
                                )
                            }
                        }
                    }

                    is UpdateCheckState.UpToDate -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircleOutline,
                                contentDescription = null,
                                tint = EmeraldPulse,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "BLACKHOLE IS UP TO DATE",
                                style = MaterialTheme.typography.labelLarge,
                                color = EmeraldPulse,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Running latest version v${updateState.currentVersion} from github.com/charfeddine23/BLACKHOLE.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ChromeSilver,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = { onDownloadUpdate(AppUpdateManager.REPO_URL) },
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Text(
                                    text = "OPEN GITHUB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeonCyan,
                                )
                            }
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Text(
                                    text = "CLOSE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ChromeSilver,
                                )
                            }
                        }
                    }

                    is UpdateCheckState.Checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = NeonCyan,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "CHECKING RELEASES...",
                                style = MaterialTheme.typography.labelLarge,
                                color = ChromeSilver,
                            )
                        }
                    }

                    else -> {
                        val err = (updateState as? UpdateCheckState.Error)?.message
                            ?: "Unable to verify latest release."
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = CrimsonAlert,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "UPDATE CHECK FAILED",
                                style = MaterialTheme.typography.labelLarge,
                                color = CrimsonAlert,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ChromeSilver,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = { onDownloadUpdate(AppUpdateManager.REPO_RELEASES_FALLBACK_URL) },
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Text(
                                    text = "RELEASES PAGE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeonCyan,
                                )
                            }
                            TextButton(
                                onClick = onRetryCheck,
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Text(
                                    text = "RETRY",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ChromeSilver,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
