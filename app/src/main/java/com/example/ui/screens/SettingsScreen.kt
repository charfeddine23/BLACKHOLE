package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.ui.components.rememberSafeScreenInsets
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet

@Composable
fun SettingsScreen(
    noScreenshotEnabled: Boolean,
    onToggleNoScreenshot: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val safeInsets = rememberSafeScreenInsets(cutoutExtraTop = 8.dp, minTop = 36.dp, minBottom = 16.dp)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
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
                                NeonCyan.copy(alpha = 0.11f),
                                PlasmaViolet.copy(alpha = 0.06f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.2f),
                            radius = size.minDimension * 0.72f,
                        ),
                        radius = size.minDimension * 0.72f,
                        center = Offset(size.width * 0.5f, size.height * 0.2f),
                    )
                }
                .padding(top = safeInsets.top, bottom = safeInsets.bottom),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("settings_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "رجوع",
                            tint = ChromeSilver,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "الإعدادات",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = ChromeSilver,
                        )
                        Text(
                            text = "إعدادات الخصوصية والحماية",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "الحماية والخصوصية",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MutedSlate,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    val accent = if (noScreenshotEnabled) EmeraldPulse else AmberWarning
                    Surface(
                        color = Color(0xFF0E1522),
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(0.95.dp, accent.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .clickable { onToggleNoScreenshot(!noScreenshotEnabled) }
                            .testTag("no_screenshot_setting_card"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Surface(
                                    color = accent.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.5f)),
                                    modifier = Modifier.size(46.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (noScreenshotEnabled) {
                                                Icons.Outlined.NoPhotography
                                            } else {
                                                Icons.Outlined.ScreenshotMonitor
                                            },
                                            contentDescription = "منع التقاط الشاشة",
                                            tint = accent,
                                            modifier = Modifier.size(23.dp),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "منع التقاط الشاشة (No Screenshot)",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = ChromeSilver,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (noScreenshotEnabled) {
                                            "مفعّل — يمنع لقطات الشاشة وتسجيل الفيديو داخل الخزنة (FLAG_SECURE)."
                                        } else {
                                            "معطّل — يُسمح بالتقاط لقطات الشاشة وتسجيلها داخل الخزنة."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MutedSlate,
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Switch(
                                checked = noScreenshotEnabled,
                                onCheckedChange = onToggleNoScreenshot,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = ObsidianVoid,
                                    checkedTrackColor = EmeraldPulse,
                                    uncheckedThumbColor = ChromeSilver,
                                    uncheckedTrackColor = Color(0xFF1E283A),
                                    uncheckedBorderColor = GlassStrokeBright.copy(alpha = 0.35f),
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("no_screenshot_switch"),
                            )
                        }
                    }

                    Surface(
                        color = Color(0xFF0C121D),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(0.8.dp, GlassStrokeBright.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = PlasmaViolet,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "حماية الخزنة المشفرة",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = ChromeSilver,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "تشغّل الخزنة تشفير AES-256 وقفل البصمة مع إخفاء الأصل عن الهاتف.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedSlate,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MutedSlate,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "BlackHole · الإصدار ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlate,
                        )
                    }
                }
            }
        }
    }
}
