package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.GlassStrokeSubtle
import com.example.ui.theme.LiquidGlassElevated
import com.example.ui.theme.LiquidGlassFill
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.PlasmaViolet

@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    highlighted: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val borderBrush = if (highlighted) {
        Brush.linearGradient(
            colors = listOf(
                NeonCyan.copy(alpha = 0.65f),
                PlasmaViolet.copy(alpha = 0.45f),
                GlassStrokeBright,
            ),
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                GlassStrokeBright.copy(alpha = 0.28f),
                GlassStrokeSubtle,
            ),
        )
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        LiquidGlassElevated,
                        LiquidGlassFill,
                    ),
                ),
            )
            .border(width = 1.dp, brush = borderBrush, shape = shape),
        content = content,
    )
}
