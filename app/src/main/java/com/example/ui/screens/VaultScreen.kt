package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet

@Composable
fun VaultScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val pulseTransition = rememberInfiniteTransition(label = "vault_aura")
    val auraAlpha by pulseTransition.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "vault_aura_alpha",
    )

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
                                PlasmaViolet.copy(alpha = auraAlpha),
                                NeonCyan.copy(alpha = auraAlpha * 0.45f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.5f),
                            radius = size.minDimension * 0.65f,
                        ),
                        radius = size.minDimension * 0.65f,
                        center = Offset(size.width * 0.5f, size.height * 0.5f),
                    )
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .align(Alignment.Center)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("vault_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to Blackhole",
                            tint = ChromeSilver,
                        )
                    }

                    Text(
                        text = "VAULT",
                        style = MaterialTheme.typography.headlineMedium,
                        color = ChromeSilver,
                        letterSpacing = 4.sp,
                    )

                    Spacer(modifier = Modifier.size(48.dp))
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        modifier = Modifier.size(88.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    PlasmaViolet.copy(alpha = 0.7f),
                                    NeonCyan.copy(alpha = 0.5f),
                                ),
                            ),
                        ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = "Vault icon",
                                tint = NeonCyan,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "soon",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 8.sp,
                        ),
                        color = ChromeSilver,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("vault_soon_text"),
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        color = Color.White.copy(alpha = 0.03f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.2f)),
                    ) {
                        Text(
                            text = "VAULT · BLACKHOLE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlate,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
