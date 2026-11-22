package com.example.ui.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.local.AppPreferences
import com.example.ui.screens.BlackholeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.screens.buildAllFilesAccessSettingIntents
import com.example.ui.screens.launchSystemSettingsCandidates
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.viewmodel.BlackholeViewModel
import kotlinx.serialization.Serializable

@Serializable
object BlackholeRoute

@Serializable
object VaultRoute

@Serializable
object SettingsRoute

@Composable
fun BlackholeNavGraph(
    blackholeViewModel: BlackholeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appPrefs = remember(context) { AppPreferences.getInstance(context) }
    val navController = rememberNavController()

    var showAllFilesStartupPrompt by remember { mutableStateOf(false) }

    val startupPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            showAllFilesStartupPrompt = true
        }
    }

    LaunchedEffect(Unit) {
        if (!appPrefs.hasRequestedStartupPermissions()) {
            appPrefs.markStartupPermissionsRequested()
            val missing = buildMissingStartupRuntimePermissions(context)
            if (missing.isNotEmpty()) {
                val launched = runCatching {
                    startupPermissionsLauncher.launch(missing)
                    true
                }.getOrDefault(false)
                if (!launched && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    showAllFilesStartupPrompt = true
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                showAllFilesStartupPrompt = true
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()
            ) {
                showAllFilesStartupPrompt = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = BlackholeRoute,
        ) {
            composable<BlackholeRoute>(
                enterTransition = { fadeIn(tween(240)) },
                exitTransition = { fadeOut(tween(220)) },
            ) {
                BlackholeScreen(
                    viewModel = blackholeViewModel,
                    onNavigateToVault = {
                        navController.navigate(VaultRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate(SettingsRoute) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable<VaultRoute>(
                enterTransition = { fadeIn(tween(240)) },
                exitTransition = { fadeOut(tween(220)) },
            ) {
                VaultScreen(
                    viewModel = blackholeViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<SettingsRoute>(
                enterTransition = { fadeIn(tween(220)) },
                exitTransition = { fadeOut(tween(200)) },
            ) {
                val noScreenshotEnabled by blackholeViewModel.noScreenshotEnabled.collectAsStateWithLifecycle()
                SettingsScreen(
                    noScreenshotEnabled = noScreenshotEnabled,
                    onToggleNoScreenshot = blackholeViewModel::setNoScreenshotEnabled,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        if (showAllFilesStartupPrompt) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianVoid.copy(alpha = 0.78f))
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color(0xFF0E1522),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 420.dp)
                        .testTag("startup_all_files_prompt"),
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            color = NeonCyan.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(0.85.dp, NeonCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.size(56.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Shield,
                                    contentDescription = "صلاحيات التشغيل",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "تفعيل صلاحية إدارة كل الملفات",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = ChromeSilver,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "مطلوبة لتشغيل الخزنة، وحفظ التنزيلات، وإخفاء الفيديوهات والصور من الهاتف بسلاسة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedSlate,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = { showAllFilesStartupPrompt = false },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(0.85.dp, GlassStrokeBright.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("startup_all_files_skip_button"),
                            ) {
                                Text("لاحقاً", color = ChromeSilver)
                            }
                            Button(
                                onClick = {
                                    showAllFilesStartupPrompt = false
                                    launchSystemSettingsCandidates(
                                        context,
                                        buildAllFilesAccessSettingIntents(context.packageName),
                                    )
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonCyan,
                                    contentColor = ObsidianVoid,
                                ),
                                modifier = Modifier
                                    .weight(1.4f)
                                    .height(48.dp)
                                    .testTag("startup_all_files_grant_button"),
                            ) {
                                Text(
                                    text = "تفعيل الآن",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun buildMissingStartupRuntimePermissions(context: Context): Array<String> {
    val required = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        required += Manifest.permission.POST_NOTIFICATIONS
    }
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            required += Manifest.permission.READ_MEDIA_IMAGES
            required += Manifest.permission.READ_MEDIA_VIDEO
            required += Manifest.permission.READ_MEDIA_AUDIO
            required += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            required += Manifest.permission.READ_MEDIA_IMAGES
            required += Manifest.permission.READ_MEDIA_VIDEO
            required += Manifest.permission.READ_MEDIA_AUDIO
        }
        else -> {
            required += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                required += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
    }
    return required.filter { perm ->
        ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()
}
