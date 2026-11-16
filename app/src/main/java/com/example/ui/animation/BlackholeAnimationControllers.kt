package com.example.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.engine.model.DownloadPhase

@Stable
class BlackholeAnimationController(
    val waveOneProgress: State<Float>,
    val waveTwoProgress: State<Float>,
    val waveThreeProgress: State<Float>,
    val orbitalAngle: State<Float>,
    val coreBreathingScale: State<Float>,
    val absorptionScale: State<Float>,
    val implosionRingAlpha: State<Float>,
    val animatedDownloadProgress: State<Float>,
    private val absorptionAnimatable: Animatable<Float, *>,
    private val implosionAlphaAnimatable: Animatable<Float, *>,
) {
    suspend fun triggerTapImplosion() {
        implosionAlphaAnimatable.snapTo(1f)
        absorptionAnimatable.animateTo(
            targetValue = 0.84f,
            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        )
        absorptionAnimatable.animateTo(
            targetValue = 1.04f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
        absorptionAnimatable.animateTo(
            targetValue = 1.0f,
            animationSpec = tween(durationMillis = 180),
        )
        implosionAlphaAnimatable.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 320),
        )
    }
}

@Composable
fun rememberBlackholeAnimationController(
    phase: DownloadPhase,
    targetProgress: Float,
): BlackholeAnimationController {
    val infiniteTransition = rememberInfiniteTransition(label = "blackhole_core_waves")

    val waveDuration = when (phase) {
        DownloadPhase.ABSORBING -> 1400
        DownloadPhase.DOWNLOADING -> 1900
        DownloadPhase.CLIPBOARD_ARMED -> 2600
        else -> 3400
    }

    val waveOne = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = waveDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "radar_wave_1",
    )

    val waveTwo = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = waveDuration,
                delayMillis = waveDuration / 3,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "radar_wave_2",
    )

    val waveThree = infiniteRepeatable(
        animation = tween<Float>(
            durationMillis = waveDuration,
            delayMillis = (waveDuration * 2) / 3,
            easing = LinearEasing,
        ),
        repeatMode = RepeatMode.Restart,
    ).let { spec ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = spec,
            label = "radar_wave_3",
        )
    }

    val orbitDuration = when (phase) {
        DownloadPhase.ABSORBING -> 2200
        DownloadPhase.DOWNLOADING -> 3800
        else -> 8000
    }

    val orbitalAngle = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = orbitDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "accretion_orbit",
    )

    val coreBreathing = infiniteTransition.animateFloat(
        initialValue = 0.975f,
        targetValue = 1.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "event_horizon_breath",
    )

    val absorptionAnimatable = remember { Animatable(1f) }
    val implosionAlphaAnimatable = remember { Animatable(0f) }

    val animatedDownloadProgress = animateFloatAsState(
        targetValue = targetProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "download_arc_progress",
    )

    LaunchedEffect(phase) {
        if (phase == DownloadPhase.ABSORBING || phase == DownloadPhase.COMPLETED) {
            implosionAlphaAnimatable.snapTo(0.95f)
            absorptionAnimatable.animateTo(
                targetValue = 0.88f,
                animationSpec = tween(160),
            )
            absorptionAnimatable.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            )
            implosionAlphaAnimatable.animateTo(0f, tween(350))
        }
    }

    return remember(
        waveOne,
        waveTwo,
        waveThree,
        orbitalAngle,
        coreBreathing,
        absorptionAnimatable,
        implosionAlphaAnimatable,
        animatedDownloadProgress,
    ) {
        BlackholeAnimationController(
            waveOneProgress = waveOne,
            waveTwoProgress = waveTwo,
            waveThreeProgress = waveThree,
            orbitalAngle = orbitalAngle,
            coreBreathingScale = coreBreathing,
            absorptionScale = absorptionAnimatable.asState(),
            implosionRingAlpha = implosionAlphaAnimatable.asState(),
            animatedDownloadProgress = animatedDownloadProgress,
            absorptionAnimatable = absorptionAnimatable,
            implosionAlphaAnimatable = implosionAlphaAnimatable,
        )
    }
}
