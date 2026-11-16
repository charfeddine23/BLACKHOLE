package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.engine.model.PlatformBadge
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CyanGlowSoft
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.GlassStrokeSubtle
import com.example.ui.theme.LiquidGlassElevated
import com.example.ui.theme.LiquidGlassFill
import com.example.ui.theme.MutedSlate
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

@Composable
fun ClipboardTargetCapsule(
    url: String,
    platform: PlatformBadge,
    isArmed: Boolean,
    isBusy: Boolean,
    onUrlChange: (String) -> Unit,
    onPasteClick: () -> Unit,
    onClearClick: () -> Unit,
    onSubmitUrl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor by animateColorAsState(
        targetValue = if (isArmed) NeonCyan else MutedSlate,
        animationSpec = tween(250),
        label = "capsule_accent",
    )

    LiquidGlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        highlighted = isArmed,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isArmed) NeonCyan else MutedSlate.copy(alpha = 0.5f)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isArmed) "CLIPBOARD LINK ARMED" else "TARGET STREAM URL",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                    )
                }

                AnimatedVisibility(
                    visible = url.isNotBlank(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(
                        color = CyanGlowSoft.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(0.75.dp, NeonCyan.copy(alpha = 0.45f)),
                    ) {
                        Text(
                            text = platform.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = "Video link icon",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (url.isEmpty()) {
                        Text(
                            text = "Copy or paste video URL (MP4, Vimeo, Reddit, Web)...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedSlate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = url,
                        onValueChange = onUrlChange,
                        enabled = !isBusy,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelMedium.copy(
                            color = ChromeSilver,
                        ),
                        cursorBrush = SolidColor(NeonCyan),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = { onSubmitUrl() },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("url_input_field"),
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                if (url.isNotEmpty() && !isBusy) {
                    IconButton(
                        onClick = onClearClick,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("clear_url_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Clear URL",
                            tint = MutedSlate,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Surface(
                    color = Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = !isBusy, onClick = onPasteClick)
                        .minimumInteractiveComponentSize()
                        .testTag("paste_clipboard_button"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = "Paste from clipboard",
                            tint = NeonCyan,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "PASTE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ChromeSilver,
                        )
                    }
                }
            }
        }
    }
}
