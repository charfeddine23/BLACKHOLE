package com.example.ui.screens

import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.example.util.buildMediaPermissionsForSdk
import com.example.util.hasAnyMissingPermissionRationale
import com.example.util.hasStoragePermission
import com.example.util.hasStrictMediaPermission

@Composable
fun SettingsScreen(
    noScreenshotEnabled: Boolean,
    onToggleNoScreenshot: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasMediaAccess by remember { mutableStateOf(hasStrictMediaPermission(context)) }
    var hasStorageAccess by remember { mutableStateOf(hasStoragePermission(context)) }
    var lastPermissionPromptMs by remember { mutableLongStateOf(0L) }

    val refreshPermissions = {
        hasMediaAccess = hasStrictMediaPermission(context)
        hasStorageAccess = hasStoragePermission(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mediaPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshPermissions()
        if (!hasMediaAccess) {
            val elapsed = SystemClock.elapsedRealtime() - lastPermissionPromptMs
            val perms = buildMediaPermissionsForSdk()
            val canShowRationale = hasAnyMissingPermissionRationale(context, perms)
            if (elapsed in 0L..500L || !canShowRationale) {
                launchSystemSettingsCandidates(
                    context,
                    buildAppDetailsSettingIntents(context.packageName),
                )
            }
        }
    }

    val requestMediaPermission: () -> Unit = {
        lastPermissionPromptMs = SystemClock.elapsedRealtime()
        val perms = buildMediaPermissionsForSdk()
        val launched = runCatching {
            mediaPermissionsLauncher.launch(perms)
            true
        }.getOrDefault(false)
        if (!launched) {
            launchSystemSettingsCandidates(
                context,
                buildAppDetailsSettingIntents(context.packageName),
            )
        }
    }

    val requestStoragePermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            launchSystemSettingsCandidates(
                context,
                buildAllFilesAccessSettingIntents(context.packageName),
            )
        } else {
            requestMediaPermission()
        }
    }

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
                            text = "إعدادات الخصوصية والصلاحيات",
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
                        text = "صلاحيات الوسائط والتخزين (Media & Storage)",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MutedSlate,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    SettingsPermissionCard(
                        title = "صلاحية الوسائط (Media)",
                        description = if (hasMediaAccess) {
                            "مفعّلة — وصول كامل للصور والفيديوهات والصوتيات."
                        } else {
                            "مطلوبة لعرض واستيراد الصور والفيديوهات من المعرض."
                        },
                        icon = Icons.Outlined.PhotoLibrary,
                        granted = hasMediaAccess,
                        actionLabel = if (hasMediaAccess) "مفعّلة" else "منح الصلاحية",
                        onAction = requestMediaPermission,
                        testTag = "settings_media_permission_card",
                    )

                    SettingsPermissionCard(
                        title = "صلاحية التخزين (Storage)",
                        description = if (hasStorageAccess) {
                            "مفعّلة — حفظ التنزيلات وإدارة الملفات وإخفاء الأصل من الهاتف."
                        } else {
                            "مطلوبة لإدارة الملفات وإخفاء الأصل من الهاتف تلقائياً."
                        },
                        icon = Icons.Outlined.FolderOpen,
                        granted = hasStorageAccess,
                        actionLabel = if (hasStorageAccess) "إدارة" else "تفعيل",
                        onAction = requestStoragePermission,
                        testTag = "settings_storage_permission_card",
                    )

                    Spacer(modifier = Modifier.height(2.dp))

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

@Composable
private fun SettingsPermissionCard(
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
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.9.dp, accent.copy(alpha = 0.42f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
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
                    color = accent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.5f)),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = ChromeSilver,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
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
