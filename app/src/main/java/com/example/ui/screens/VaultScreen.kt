package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.ui.viewmodel.BlackholeViewModel

@Suppress("UNUSED_PARAMETER")
@Composable
fun VaultScreen(
    viewModel: BlackholeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("vault_screen"),
        containerColor = ObsidianVoid,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianVoid)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                PlasmaViolet.copy(alpha = 0.12f),
                                NeonCyan.copy(alpha = 0.05f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.5f),
                            radius = size.minDimension * 0.65f,
                        ),
                        radius = size.minDimension * 0.65f,
                        center = Offset(size.width * 0.5f, size.height * 0.5f),
                    )
                }
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("vault_back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = ChromeSilver.copy(alpha = 0.72f),
                )
            }

            Text(
                text = "soon",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Light,
                    letterSpacing = 6.sp,
                ),
                color = ChromeSilver.copy(alpha = 0.88f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .testTag("vault_soon_label"),
            )
        }
    }
}
