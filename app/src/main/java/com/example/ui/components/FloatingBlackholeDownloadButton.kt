package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.util.formatTransferSpeed
import com.example.vault.data.VaultBrowserDownloadPhase
import com.example.vault.data.VaultBrowserDownloadState
import kotlin.math.roundToInt

/**
 * Floating, draggable Blackhole download sphere overlaid on top of the Vault Private Browser.
 * Tapping it captures the video from the active web page in highest quality and stores it
 * directly encrypted (AES-256) inside the Vault Videos folder.
 */
@Composable
fun FloatingBlackholeDownloadOverlay(
    downloadState: VaultBrowserDownloadState,
    sniffedCandidatesCount: Int,
    isPageActive: Boolean,
    onTapBlackhole: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val orbContainerDp = 82.dp
    val orbContainerPx = with(density) { orbContainerDp.toPx() }
    val edgeMarginPx = with(density) { 12.dp.toPx() }
    val bottomClearancePx = with(density) { 24.dp.toPx() }

    val infiniteTransition = rememberInfiniteTransition(label = "floating_bh_orbit")
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (downloadState.isActive) 1100 else 3600,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "floating_bh_angle",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floating_bh_pulse",
    )
    val smoothProgress by animateFloatAsState(
        targetValue = downloadState.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220, easing = LinearEasing),
        label = "floating_bh_progress",
    )

    val primaryGlow = when (downloadState.phase) {
        VaultBrowserDownloadPhase.COMPLETED -> EmeraldPulse
        VaultBrowserDownloadPhase.ERROR -> CrimsonAlert
        VaultBrowserDownloadPhase.EXTRACTING,
        VaultBrowserDownloadPhase.DOWNLOADING,
        VaultBrowserDownloadPhase.MERGING,
        VaultBrowserDownloadPhase.ENCRYPTING,
        -> NeonCyan
        VaultBrowserDownloadPhase.IDLE -> if (sniffedCandidatesCount > 0 || isPageActive) NeonCyan else ElectricBlue
    }
    val secondaryGlow = when (downloadState.phase) {
        VaultBrowserDownloadPhase.COMPLETED -> NeonCyan
        VaultBrowserDownloadPhase.ERROR -> PlasmaViolet
        else -> PlasmaViolet
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }

        var offsetX by remember { mutableFloatStateOf(-1f) }
        var offsetY by remember { mutableFloatStateOf(-1f) }

        val minX = edgeMarginPx
        val maxX = (maxWidthPx - orbContainerPx - edgeMarginPx).coerceAtLeast(minX)
        val minY = edgeMarginPx
        val maxY = (maxHeightPx - orbContainerPx - bottomClearancePx).coerceAtLeast(minY)

        LaunchedEffect(maxWidthPx, maxHeightPx) {
            if (offsetX < 0f || offsetY < 0f) {
                offsetX = maxX
                offsetY = maxY
            } else {
                offsetX = offsetX.coerceIn(minX, maxX)
                offsetY = offsetY.coerceIn(minY, maxY)
            }
        }

        val resolvedX = (if (offsetX < 0f) maxX else offsetX).coerceIn(minX, maxX)
        val resolvedY = (if (offsetY < 0f) maxY else offsetY).coerceIn(minY, maxY)

        // Top compact telemetry pill whenever downloading, completed, or reporting status
        AnimatedVisibility(
            visible = downloadState.phase != VaultBrowserDownloadPhase.IDLE,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Surface(
                color = Color(0xFF0A111D).copy(alpha = 0.94f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(0.9.dp, primaryGlow.copy(alpha = 0.55f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_browser_download_status_pill"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = if (downloadState.phase == VaultBrowserDownloadPhase.COMPLETED) {
                                    Icons.Rounded.Lock
                                } else {
                                    Icons.Rounded.ArrowDownward
                                },
                                contentDescription = null,
                                tint = primaryGlow,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = downloadState.message
                                    ?: downloadState.title.ifBlank { "خزنة الفيديو" },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = ChromeSilver,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (downloadState.isActive) {
                            val pctText = "${(smoothProgress * 100f).roundToInt()}%"
                            val speedText = if (downloadState.speedBytesPerSec > 0L) {
                                " · ${formatTransferSpeed(downloadState.speedBytesPerSec)}"
                            } else {
                                ""
                            }
                            Text(
                                text = "$pctText$speedText",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = NeonCyan,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(CrimsonAlert.copy(alpha = 0.16f))
                                    .clickable { onCancelDownload() }
                                    .testTag("vault_browser_cancel_download_button"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "إلغاء التحميل",
                                    tint = CrimsonAlert,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable { onDismissStatus() }
                                    .testTag("vault_browser_dismiss_download_status"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "إغلاق التنبيه",
                                    tint = MutedSlate,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }

                    if (downloadState.isActive) {
                        LinearProgressIndicator(
                            progress = { smoothProgress.coerceAtLeast(0.05f) },
                            color = primaryGlow,
                            trackColor = GlassStrokeBright.copy(alpha = 0.16f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        // Draggable Floating Blackhole Circle Button
        Box(
            modifier = Modifier
                .offset { IntOffset(resolvedX.roundToInt(), resolvedY.roundToInt()) }
                .size(orbContainerDp)
                .pointerInput(maxWidthPx, maxHeightPx) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = ((if (offsetX < 0f) maxX else offsetX) + dragAmount.x)
                            .coerceIn(minX, maxX)
                        offsetY = ((if (offsetY < 0f) maxY else offsetY) + dragAmount.y)
                            .coerceIn(minY, maxY)
                    }
                }
                .clip(CircleShape)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (downloadState.isActive) {
                        onCancelDownload()
                    } else {
                        onTapBlackhole()
                    }
                }
                .semantics {
                    contentDescription = "زر الثقب الأسود العائم لتحميل الفيديو إلى الخزنة"
                }
                .testTag("floating_blackhole_download_button"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val coreRadius = (size.minDimension * 0.34f) * (if (downloadState.isActive) pulseScale else 1f)
                val haloRadius = size.minDimension * 0.47f

                // Outer gravity ambient glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = if (downloadState.isActive) 0.45f else 0.28f),
                            secondaryGlow.copy(alpha = 0.14f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = haloRadius,
                    ),
                    radius = haloRadius,
                    center = center,
                )

                // Tilted rotating accretion ring
                rotate(degrees = orbitAngle, pivot = center) {
                    drawOval(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                primaryGlow.copy(alpha = 0.95f),
                                secondaryGlow.copy(alpha = 0.85f),
                                primaryGlow.copy(alpha = 0.35f),
                            ),
                        ),
                        topLeft = Offset(center.x - coreRadius * 1.38f, center.y - coreRadius * 0.56f),
                        size = Size(coreRadius * 2.76f, coreRadius * 1.12f),
                        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
                    )
                }

                // Event horizon blackhole sphere
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF020408),
                            ObsidianVoid,
                            Color(0xFF0E182A),
                        ),
                        center = center,
                        radius = coreRadius,
                    ),
                    radius = coreRadius,
                    center = center,
                )

                // Crisp Neon event-horizon rim
                drawCircle(
                    color = primaryGlow.copy(alpha = 0.78f),
                    radius = coreRadius,
                    center = center,
                    style = Stroke(width = 1.6.dp.toPx()),
                )

                // Determinate progress arc while downloading/encrypting
                if (downloadState.isActive) {
                    val arcInset = center.x - coreRadius * 1.16f
                    val arcTop = center.y - coreRadius * 1.16f
                    val arcDim = coreRadius * 2.32f
                    drawArc(
                        color = primaryGlow,
                        startAngle = -90f,
                        sweepAngle = (smoothProgress.coerceAtLeast(0.06f)) * 360f,
                        useCenter = false,
                        topLeft = Offset(arcInset, arcTop),
                        size = Size(arcDim, arcDim),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            // Center Glyph inside Blackhole core
            AnimatedContent(
                targetState = downloadState.phase,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "floating_bh_center_icon",
            ) { phase ->
                when (phase) {
                    VaultBrowserDownloadPhase.COMPLETED -> {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "تم التحميل",
                            tint = EmeraldPulse,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    VaultBrowserDownloadPhase.EXTRACTING,
                    VaultBrowserDownloadPhase.DOWNLOADING,
                    VaultBrowserDownloadPhase.MERGING,
                    VaultBrowserDownloadPhase.ENCRYPTING,
                    -> {
                        Text(
                            text = "${(smoothProgress * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = ChromeSilver,
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.ArrowDownward,
                            contentDescription = "تحميل الفيديو إلى الخزنة",
                            tint = primaryGlow,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // Small detected media counter badge if streams detected on page
            if (sniffedCandidatesCount > 0 && downloadState.phase == VaultBrowserDownloadPhase.IDLE) {
                Surface(
                    color = EmeraldPulse,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = sniffedCandidatesCount.coerceAtMost(9).toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = ObsidianVoid,
                        )
                    }
                }
            }
        }
    }
}
