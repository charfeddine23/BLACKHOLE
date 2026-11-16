package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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

@Composable
fun BlackholeCoreButton(
    phase: DownloadPhase,
    qualityLabel: String,
    controller: BlackholeAnimationController,
    onTapBlackhole: () -> Unit,
    modifier: Modifier = Modifier,
    coreDiameter: Dp = 264.dp,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }

    val primaryGlow = when (phase) {
        DownloadPhase.COMPLETED -> EmeraldPulse
        DownloadPhase.ERROR -> CrimsonAlert
        DownloadPhase.ABSORBING, DownloadPhase.DOWNLOADING -> NeonCyan
        DownloadPhase.CLIPBOARD_ARMED -> NeonCyan
        DownloadPhase.IDLE -> ElectricBlue
    }

    val secondaryGlow = when (phase) {
        DownloadPhase.COMPLETED -> NeonCyan
        DownloadPhase.ERROR -> PlasmaViolet
        else -> PlasmaViolet
    }

    val coreButtonScale = controller.absorptionScale.value * controller.coreBreathingScale.value

    Box(
        modifier = modifier
            .size(coreDiameter + 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Outer concentric radar / pulse waves + orbital accretion rings
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxWaveRadius = size.minDimension / 2f
            val innerHorizonRadius = (coreDiameter.toPx() / 2f) * 0.68f

            // Staggered Radar Pulse Waves
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

            // Cybernetic Tap Implosion Shockwave
            val implosionAlpha = controller.implosionRingAlpha.value
            if (implosionAlpha > 0.01f) {
                drawCircle(
                    color = NeonCyan.copy(alpha = implosionAlpha * 0.7f),
                    radius = innerHorizonRadius * (1.05f + (1f - implosionAlpha) * 0.55f),
                    center = center,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }

            // Ambient Deep Radial Halo behind the Blackhole
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
        }

        // Interactive Blackhole Sphere
        Box(
            modifier = Modifier
                .size(coreDiameter)
                .graphicsLayer {
                    scaleX = coreButtonScale
                    scaleY = coreButtonScale
                }
                .clip(CircleShape)
                .semantics {
                    contentDescription = when (phase) {
                        DownloadPhase.DOWNLOADING -> "Downloading video at ${(controller.animatedDownloadProgress.value * 100).roundToInt()} percent"
                        DownloadPhase.ABSORBING -> "Absorbing stream link"
                        DownloadPhase.CLIPBOARD_ARMED -> "Tap Blackhole core to pull armed video link"
                        DownloadPhase.COMPLETED -> "Download complete. Tap to pull again"
                        else -> "Tap Blackhole core to detect clipboard and download video"
                    }
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true, color = NeonCyan),
                    role = Role.Button,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch { controller.triggerTapImplosion() }
                        onTapBlackhole()
                    },
                )
                .testTag("blackhole_core_button"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension / 2f - 4.dp.toPx()

                // Rotating Accretion Halo
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
                        style = Stroke(width = 5.dp.toPx()),
                    )
                }

                // Subtle Metallic Brushed Bezel
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

                // Deep Obsidian Singularity Core
                val coreRadius = outerRadius - 14.dp.toPx()
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

                // Inner Event Horizon Rim
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

                // Precision Live Progress Arc when Downloading
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

            // Center Core Readout
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
                                style = MaterialTheme.typography.displaySmall,
                                color = ChromeSilver,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
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
                                modifier = Modifier.size(34.dp),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "ABSORBING",
                                style = MaterialTheme.typography.labelMedium,
                                color = NeonCyan,
                            )
                        }

                        DownloadPhase.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = EmeraldPulse,
                                modifier = Modifier.size(38.dp),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
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
                                modifier = Modifier.size(34.dp),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "PULL MAX",
                                style = MaterialTheme.typography.labelLarge,
                                color = ChromeSilver,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "4K · 1080P",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonCyan.copy(alpha = 0.85f),
                            )
                        }

                        DownloadPhase.ERROR -> {
                            // Keep singularity core clean without RETRY PULL text or arrow
                        }

                        DownloadPhase.IDLE -> {
                            Icon(
                                imageVector = Icons.Rounded.ArrowDownward,
                                contentDescription = null,
                                tint = ChromeSilver.copy(alpha = 0.88f),
                                modifier = Modifier.size(32.dp),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "TAP CORE",
                                style = MaterialTheme.typography.labelLarge,
                                color = ChromeSilver,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "AUTO CLIPBOARD",
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
