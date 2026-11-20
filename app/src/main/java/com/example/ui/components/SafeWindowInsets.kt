package com.example.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class SafeScreenInsets(
    val top: Dp,
    val bottom: Dp,
)

@Composable
fun rememberSafeScreenInsets(
    cutoutExtraTop: Dp = 0.dp,
    minTop: Dp = 32.dp,
    minBottom: Dp = 12.dp,
): SafeScreenInsets {
    val density = LocalDensity.current
    val cutoutTop = with(density) { WindowInsets.displayCutout.getTop(density).toDp() } + cutoutExtraTop
    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val cutoutBottom = with(density) { WindowInsets.displayCutout.getBottom(density).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    return SafeScreenInsets(
        top = maxOf(cutoutTop, statusTop, minTop),
        bottom = maxOf(cutoutBottom, navBottom, minBottom),
    )
}
