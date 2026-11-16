package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val BlackholeDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = ObsidianVoid,
    primaryContainer = Color(0xFF072B36),
    onPrimaryContainer = ChromeSilver,
    secondary = PlasmaViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1D173A),
    onSecondaryContainer = Color(0xFFE0D9FF),
    tertiary = EmeraldPulse,
    onTertiary = ObsidianVoid,
    background = ObsidianVoid,
    onBackground = ChromeSilver,
    surface = AbyssalSurface,
    onSurface = ChromeSilver,
    surfaceVariant = LiquidGlassFill,
    onSurfaceVariant = MutedSlate,
    outline = GlassStrokeSubtle,
    outlineVariant = GlassStrokeBright,
    error = CrimsonAlert,
    onError = Color.White,
)

private val BlackholeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun BlackholeTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = BlackholeDarkColorScheme,
        typography = BlackholeTypography,
        shapes = BlackholeShapes,
        content = content,
    )
}
