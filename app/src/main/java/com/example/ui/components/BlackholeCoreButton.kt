package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.engine.model.DownloadPhase
import com.example.ui.animation.BlackholeAnimationController
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val VAULT_HOLD_DURATION_MS = 1000L

@Composable
fun BlackholeCoreButton(
    phase: DownloadPhase,
    qualityLabel: String,
    controller: BlackholeAnimationController,
    onTapBlackhole: () -> Unit,
    onHoldOneSecondVault: () -> Unit,
    modifier: Modifier = Modifier,
    coreDiameter: Dp = 200.dp,
    isUpdateActive: Boolean = false,
    updateProgress: Float = 0f,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val vaultChargeProgress = remember { Animatable(0f) }

    val smoothUpdateProgress by animateFloatAsState(
        targetValue = updateProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 280, easing = LinearEasing),
        label = "update_perimeter_progress",
    )
    val updateInfinite = rememberInfiniteTransition(label = "update_perimeter_orbit")
    val updateOrbitAngle by updateInfinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "update_orbit_angle",
    )

    val primaryGlow = when (phase) {
        DownloadPhase.COMPLETED -> EmeraldPulse
        DownloadPhase.ERROR -> CrimsonAlert
        DownloadPhase.ABSORBING, DownloadPhase.DOWNLOADING, DownloadPhase.MERGING -> NeonCyan
        DownloadPhase.CLIPBOARD_ARMED -> NeonCyan
        DownloadPhase.NEEDS_AUTH -> PlasmaViolet
        DownloadPhase.IDLE -> ElectricBlue
    }

    val secondaryGlow = when (phase) {
        DownloadPhase.COMPLETED -> NeonCyan
        DownloadPhase.ERROR -> PlasmaViolet
        else -> PlasmaViolet
    }

    val holdScale = 1f - (vaultChargeProgress.value * 0.08f)
    val coreButtonScale = controller.absorptionScale.value * controller.coreBreathingScale.value * holdScale

    Box(
        modifier = modifier
            .size(coreDiameter + 52.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Outer concentric radar / pulse waves + orbital accretion rings + 1s Vault Hold charge ring
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxWaveRadius = size.minDimension / 2f
            val innerHorizonRadius = (coreDiameter.toPx() / 2f) * 0.68f

            val waves = listOf(
                controller.waveOneProgress.value,
                controller.waveTwoProgress.value,
                controller.waveThreeProgress.value,
            )
            waves.forEach { progress ->
                val waveRadius = innerHorizonRadius + (maxWaveRadius - innerHorizonRadius) * progress
                val alpha = ((1f - progress) * if (phase == DownloadPhase.IDLE) 0.24f else 0.42f)
                    .coerceIn(0f, 1f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            primaryGlow.copy(alpha = alpha * 0.45f),
                            secondaryGlow.copy(alpha = alpha * 0.22f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = waveRadius,
                    ),
                    radius = waveRadius,
                    center = center,
                    style = Stroke(width = 1.6.dp.toPx()),
                )
            }

            val implosionAlpha = controller.implosionRingAlpha.value
            if (implosionAlpha > 0.01f) {
                drawCircle(
                    color = NeonCyan.copy(alpha = implosionAlpha * 0.7f),
                    radius = innerHorizonRadius * (1.05f + (1f - implosionAlpha) * 0.55f),
                    center = center,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryGlow.copy(alpha = 0.24f),
                        secondaryGlow.copy(alpha = 0.14f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = coreDiameter.toPx() * 0.62f,
                ),
                radius = coreDiameter.toPx() * 0.62f,
                center = center,
            )

            // 1-second Hold Progress Ring for Vault entry
            if (vaultChargeProgress.value > 0.02f) {
                val holdRadius = (coreDiameter.toPx() / 2f) + 10.dp.toPx()
                val arcSize = Size(holdRadius * 2f, holdRadius * 2f)
                val arcTopLeft = Offset(center.x - holdRadius, center.y - holdRadius)
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(PlasmaViolet, NeonCyan, EmeraldPulse, PlasmaViolet),
                        center = center,
                    ),
                    startAngle = -90f,
                    sweepAngle = 360f * vaultChargeProgress.value,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            if (isUpdateActive) {
                val perimeterRadius = (coreDiameter.toPx() / 2f) + 6.dp.toPx()
                val arcSize = Size(perimeterRadius * 2f, perimeterRadius * 2f)
                val arcTopLeft = Offset(
                    center.x - perimeterRadius,
                    center.y - perimeterRadius,
                )

                drawCircle(
                    color = NeonCyan.copy(alpha = 0.14f),
                    radius = perimeterRadius,
                    center = center,
                    style = Stroke(width = 3.dp.toPx()),
                )

                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(NeonCyan, ElectricBlue, EmeraldPulse, NeonCyan),
                        center = center,
                    ),
                    startAngle = -90f,
                    sweepAngle = (360f * smoothUpdateProgress).coerceIn(0f, 360f),
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )

                if (smoothUpdateProgress < 0.995f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                Color.Transparent,
                                NeonCyan.copy(alpha = 0.9f),
                                Color.White.copy(alpha = 0.95f),
                                Color.Transparent,
                            ),
                            center = center,
                        ),
                        startAngle = updateOrbitAngle - 90f,
                        sweepAngle = 72f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }

        // Interactive Blackhole Sphere supporting Tap + 1-Second Press to enter Vault
        Box(
            modifier = Modifier
                .size(coreDiameter)
                .graphicsLayer {
                    scaleX = coreButtonScale
                    scaleY = coreButtonScale
                }
                .clip(CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = when (phase) {
                        DownloadPhase.DOWNLOADING -> "Downloading video at ${(controller.animatedDownloadProgress.value * 100).roundToInt()} percent. Hold 1 second for Vault."
                        DownloadPhase.ABSORBING -> "Extracting stream link. Hold 1 second for Vault."
                        else -> "Tap Blackhole core to extract link, or press and hold for 1 second to open Vault"
                    }
                    onLongClick(label = "Open Vault") {
                        onHoldOneSecondVault()
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var vaultTriggered = false
                        val chargeJob = scope.launch {
                            vaultChargeProgress.snapTo(0f)
                            vaultChargeProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = VAULT_HOLD_DURATION_MS.toInt(),
                                    easing = LinearEasing,
                                ),
                            )
                            vaultTriggered = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onHoldOneSecondVault()
                            vaultChargeProgress.snapTo(0f)
                        }

                        val upChange = waitForUpOrCancellation()
                        chargeJob.cancel()

                        if (!vaultTriggered) {
                            scope.launch { vaultChargeProgress.animateTo(0f, tween(150)) }
                            if (upChange != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch { controller.triggerTapImplosion() }
                                onTapBlackhole()
                            }
                        }
                    }
                }
                .testTag("blackhole_core_button"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension / 2f - 4.dp.toPx()

                rotate(degrees = controller.orbitalAngle.value, pivot = center) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                primaryGlow.copy(alpha = 0.95f),
                                secondaryGlow.copy(alpha = 0.7f),
                                Color.Transparent,
                                ElectricBlue.copy(alpha = 0.55f),
                                primaryGlow.copy(alpha = 0.95f),
                            ),
                            center = center,
                        ),
                        radius = outerRadius - 6.dp.toPx(),
                        center = center,
                        style = Stroke(width = 4.5.dp.toPx()),
                    )
                }

                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.42f),
                            Color(0xFF2B3544),
                            Color.White.copy(alpha = 0.18f),
                            Color(0xFF141A22),
                        ),
                    ),
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )

                val coreRadius = outerRadius - 12.dp.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF010203),
                            ObsidianVoid,
                            Color(0xFF0B111A),
                        ),
                        center = center,
                        radius = coreRadius,
                    ),
                    radius = coreRadius,
                    center = center,
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            primaryGlow.copy(alpha = 0.25f),
                        ),
                        center = center,
                        radius = coreRadius,
                    ),
                    radius = coreRadius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )

                if (phase == DownloadPhase.DOWNLOADING) {
                    val arcDiameter = (outerRadius - 6.dp.toPx()) * 2f
                    val topLeft = Offset(
                        center.x - (outerRadius - 6.dp.toPx()),
                        center.y - (outerRadius - 6.dp.toPx()),
                    )
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(NeonCyan, PlasmaViolet, EmeraldPulse, NeonCyan),
                            center = center,
                        ),
                        startAngle = -90f,
                        sweepAngle = 360f * controller.animatedDownloadProgress.value,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(arcDiameter, arcDiameter),
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "blackhole_center_state",
            ) { currentPhase ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (currentPhase) {
                        DownloadPhase.DOWNLOADING -> {
                            val percent = (controller.animatedDownloadProgress.value * 100f).roundToInt()
                            Text(
                                text = "$percent%",
                                style = MaterialTheme.typography.headlineMedium,
                                color = ChromeSilver,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = qualityLabel.ifBlank { "PULLING" },
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonCyan,
                            )
                        }

                        DownloadPhase.ABSORBING -> {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(30.dp),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "EXTRACTING",
                                style = MaterialTheme.typography.labelMedium,
                                color = NeonCyan,
                            )
                        }

                        DownloadPhase.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = EmeraldPulse,
                                modifier = Modifier.size(34.dp),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "CAPTURED",
                                style = MaterialTheme.typography.labelMedium,
                                color = EmeraldPulse,
                            )
                        }

                        DownloadPhase.CLIPBOARD_ARMED -> {
                            Icon(
                                imageVector = Icons.Rounded.ArrowDownward,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(30.dp),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "PULL MAX",
                                style = MaterialTheme.typography.labelLarge,
                                color = ChromeSilver,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "HOLD 1S · VAULT",
                                style = MaterialTheme.typography.labelSmall,
                                color = PlasmaViolet.copy(alpha = 0.85f),
                            )
                        }

                        DownloadPhase.MERGING -> {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                                tint = PlasmaViolet,
                                modifier = Modifier.size(30.dp),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "MUXING A/V",
                                style = MaterialTheme.typography.labelMedium,
                                color = ChromeSilver,
                            )
                        }

                        DownloadPhase.NEEDS_AUTH -> {
                            Text(
                                text = "AUTH NEEDED",
                                style = MaterialTheme.typography.labelMedium,
                                color = PlasmaViolet,
                            )
                        }

                        DownloadPhase.ERROR -> {
                            Text(
                                text = "TAP TO RETRY",
                                style = MaterialTheme.typography.labelSmall,
                                color = CrimsonAlert,
                            )
                        }

                        DownloadPhase.IDLE -> {
                            Icon(
                                imageVector = Icons.Rounded.ArrowDownward,
                                contentDescription = null,
                                tint = ChromeSilver.copy(alpha = 0.88f),
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "TAP CORE",
                                style = MaterialTheme.typography.labelLarge,
                                color = ChromeSilver,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "HOLD 1S · VAULT",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSlate,
                            )
                        }
                    }
                }
            }
        }
    }
}
