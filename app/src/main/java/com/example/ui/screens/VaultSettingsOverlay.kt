package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.vault.ui.VaultUiState

@Composable
fun VaultSettingsOverlay(
    uiState: VaultUiState,
    noScreenshotEnabled: Boolean,
    onToggleNoScreenshot: (Boolean) -> Unit,
    onLockVaultNow: () -> Unit,
    onRequestMediaPermission: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onSelectInternalStorage: () -> Unit,
    onDismissStatus: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = ObsidianVoid,
        modifier = Modifier
            .fillMaxSize()
            .testTag("vault_settings_overlay"),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp),
            ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("vault_settings_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "رجوع إلى الخزنة",
                        tint = ChromeSilver,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "إعدادات الخزنة",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = ChromeSilver,
                    )
                    Text(
                        text = "الأمان، الصلاحيات وموقع التخزين",
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                uiState.statusMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                    val statusAccent = if (uiState.isErrorStatus) com.example.ui.theme.CrimsonAlert else EmeraldPulse
                    Surface(
                        color = statusAccent.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(0.85.dp, statusAccent.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onDismissStatus() },
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusAccent,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }

                // 1. Privacy & Security Section
                VaultSettingsSectionTitle("الأمان والخصوصية")

                val screenshotAccent = if (noScreenshotEnabled) EmeraldPulse else AmberWarning
                Surface(
                    color = Color(0xFF0E1522),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(0.95.dp, screenshotAccent.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onToggleNoScreenshot(!noScreenshotEnabled) }
                        .testTag("vault_no_screenshot_card"),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Surface(
                                color = screenshotAccent.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(0.8.dp, screenshotAccent.copy(alpha = 0.5f)),
                                modifier = Modifier.size(44.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (noScreenshotEnabled) {
                                            Icons.Outlined.NoPhotography
                                        } else {
                                            Icons.Outlined.ScreenshotMonitor
                                        },
                                        contentDescription = "منع التقاط الشاشة",
                                        tint = screenshotAccent,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "منع التقاط الشاشة (No Screenshot)",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = ChromeSilver,
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = if (noScreenshotEnabled) {
                                        "مفعّل — يمنع لقطات وتسجيل الشاشة داخل الخزنة (FLAG_SECURE)."
                                    } else {
                                        "معطّل — يسمح بالتقاط وتسجيل الشاشة داخل الخزنة."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedSlate,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
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
                                .testTag("vault_no_screenshot_switch"),
                        )
                    }
                }

                // Lock Vault Now Action (moved from header into Vault Settings)
                Surface(
                    color = Color(0xFF0E1522),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(0.9.dp, PlasmaViolet.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            onClose()
                            onLockVaultNow()
                        }
                        .minimumInteractiveComponentSize()
                        .testTag("vault_settings_lock_now_button"),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Surface(
                                color = PlasmaViolet.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(0.8.dp, PlasmaViolet.copy(alpha = 0.55f)),
                                modifier = Modifier.size(44.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = "قفل الخزنة الآن",
                                        tint = PlasmaViolet,
                                        modifier = Modifier.size(21.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "قفل الخزنة الآن",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = ChromeSilver,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "إغلاق الخزنة فوراً وتأمينها بالبصمة",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedSlate,
                                )
                            }
                        }
                        Surface(
                            color = PlasmaViolet.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                text = "قفل",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = PlasmaViolet,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 2. Permissions Section
                VaultSettingsSectionTitle("صلاحيات الوصول إلى ملفات الجهاز")

                VaultPermissionSettingRow(
                    title = "الوصول إلى الصور والفيديوهات",
                    description = if (uiState.hasMediaAccess) {
                        "الصلاحية مفعّلة لعرض واستيراد الوسائط من الهاتف."
                    } else {
                        "مطلوبة لقراءة وعرض الصور والفيديوهات من جهازك."
                    },
                    icon = Icons.Outlined.PhotoLibrary,
                    granted = uiState.hasMediaAccess,
                    actionLabel = if (uiState.hasMediaAccess) "مفعّلة" else "منح الصلاحية",
                    onAction = onRequestMediaPermission,
                    testTag = "vault_settings_media_permission",
                )

                VaultPermissionSettingRow(
                    title = "صلاحية الوصول إلى كل الملفات",
                    description = if (uiState.hasAllFilesAccess) {
                        "مفعّلة — يُشفّر الملف ويُحذف الأصل من الهاتف تلقائياً."
                    } else {
                        "مطلوبة لحذف الملفات الأصلية من الهاتف فور تشفيرها."
                    },
                    icon = Icons.Outlined.Shield,
                    granted = uiState.hasAllFilesAccess,
                    actionLabel = if (uiState.hasAllFilesAccess) "إدارة" else "تفعيل",
                    onAction = onRequestAllFilesAccess,
                    testTag = "vault_settings_all_files_permission",
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 3. Vault Storage Mode Section
                VaultSettingsSectionTitle("مكان حفظ ملفات الخزنة المشفرة")

                Text(
                    text = "الوضع الحالي: ${uiState.storageLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonCyan,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                VaultStorageOptionCard(
                    title = "الخزنة الداخلية المعزولة (موصى به)",
                    subtitle = "مخفية كلياً داخل مساحة التطبيق ومشفرة بـ AES-256.",
                    icon = Icons.Rounded.Lock,
                    accent = NeonCyan,
                    selected = true,
                    onClick = onSelectInternalStorage,
                    testTag = "vault_storage_internal",
                )

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
        }
    }
}

@Composable
private fun VaultSettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MutedSlate,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun VaultPermissionSettingRow(
    title: String,
    description: String,
    icon: ImageVector,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    testTag: String,
) {
    val accent = if (granted) EmeraldPulse else AmberWarning
    Surface(
        color = Color(0xFF0E1522),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(0.85.dp, accent.copy(alpha = 0.42f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    color = accent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (granted) {
                OutlinedButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.85.dp, EmeraldPulse.copy(alpha = 0.55f)),
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = EmeraldPulse,
                    )
                }
            } else {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberWarning,
                        contentColor = ObsidianVoid,
                    ),
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultStorageOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Surface(
        color = if (selected) accent.copy(alpha = 0.14f) else Color(0xFF0E1522),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = if (selected) 1.3.dp else 0.8.dp,
            color = if (selected) accent else GlassStrokeBright.copy(alpha = 0.24f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .minimumInteractiveComponentSize()
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    color = accent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = ChromeSilver,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selected) "مختار" else "اختيار",
                tint = if (selected) accent else MutedSlate,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
