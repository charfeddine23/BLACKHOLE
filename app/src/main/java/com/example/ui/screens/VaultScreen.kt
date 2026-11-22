package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.rememberSafeScreenInsets
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.ui.viewmodel.BlackholeViewModel
import com.example.vault.data.VaultCategory
import com.example.vault.ui.VaultUiState
import com.example.vault.ui.VaultViewModel

@Suppress("UNUSED_PARAMETER")
@Composable
fun VaultScreen(
    viewModel: BlackholeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vaultViewModel: VaultViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by vaultViewModel.uiState.collectAsStateWithLifecycle()
    val noScreenshotEnabled by vaultViewModel.noScreenshotEnabled.collectAsStateWithLifecycle()
    var showVaultSettings by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isLocked) {
        if (uiState.isLocked) {
            showVaultSettings = false
        }
    }

    // Enforce FLAG_SECURE reactively while VaultScreen is visible when enabled in Settings
    DisposableEffect(context, noScreenshotEnabled) {
        val activity = context.findActivity()
        if (noScreenshotEnabled) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Auto-lock on genuine backgrounding/leaving screen while keeping external system flows unlocked
    DisposableEffect(lifecycleOwner, vaultViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (!vaultViewModel.isAwaitingExternalSystemFlow()) {
                        vaultViewModel.lockVaultNow()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    vaultViewModel.endExternalSystemFlow()
                    vaultViewModel.refreshPermissionAndEntries()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vaultViewModel.lockVaultNow()
        }
    }

    BackHandler {
        when {
            uiState.activePlayback != null -> vaultViewModel.closePlayer()
            uiState.devicePicker.isOpen -> {
                if (!vaultViewModel.navigateUpDeviceDirectory()) {
                    vaultViewModel.closeDevicePicker()
                }
            }
            showVaultSettings -> showVaultSettings = false
            uiState.selectedCategory != null -> vaultViewModel.selectCategory(null)
            else -> {
                vaultViewModel.lockVaultNow()
                onBack()
            }
        }
    }

    var pendingPickCategory by remember { mutableStateOf(VaultCategory.VIDEOS) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { selectedUri: Uri? ->
        vaultViewModel.endExternalSystemFlow()
        if (selectedUri != null) {
            vaultViewModel.closeDevicePicker()
            vaultViewModel.moveMediaToVault(selectedUri, pendingPickCategory)
        }
    }

    val getContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { selectedUri: Uri? ->
        vaultViewModel.endExternalSystemFlow()
        if (selectedUri != null) {
            vaultViewModel.closeDevicePicker()
            vaultViewModel.moveMediaToVault(selectedUri, pendingPickCategory)
        }
    }

    val legacyIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vaultViewModel.endExternalSystemFlow()
        val selectedUri = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data ?: result.data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } else {
            null
        }
        if (selectedUri != null) {
            vaultViewModel.closeDevicePicker()
            vaultViewModel.moveMediaToVault(selectedUri, pendingPickCategory)
        }
    }

    val visualMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { selectedUri: Uri? ->
        vaultViewModel.endExternalSystemFlow()
        if (selectedUri != null) {
            vaultViewModel.closeDevicePicker()
            vaultViewModel.moveMediaToVault(selectedUri, pendingPickCategory)
        }
    }

    val mediaDeleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        vaultViewModel.endExternalSystemFlow()
        vaultViewModel.consumePendingMediaDeleteSender(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(uiState.pendingDeleteRequest?.id) {
        val req = uiState.pendingDeleteRequest ?: return@LaunchedEffect
        vaultViewModel.beginExternalSystemFlow()
        val launched = runCatching {
            mediaDeleteRequestLauncher.launch(IntentSenderRequest.Builder(req.sender).build())
            true
        }.getOrDefault(false)
        if (!launched) {
            vaultViewModel.endExternalSystemFlow()
            vaultViewModel.consumePendingMediaDeleteSender(false)
        }
    }

    val openAppPermissionDetails: () -> Unit = {
        vaultViewModel.beginExternalSystemFlow()
        val detailsCandidates = buildAppDetailsSettingIntents(context.packageName)
        if (!launchSystemSettingsCandidates(context, detailsCandidates)) {
            vaultViewModel.endExternalSystemFlow()
        }
    }

    var lastPermissionPromptMs by remember { mutableStateOf(0L) }

    val runtimeMediaPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        vaultViewModel.endExternalSystemFlow()
        vaultViewModel.refreshPermissionAndEntries()
        val anyGranted = grants.values.any { it }
        if (!anyGranted && !uiState.hasAllFilesAccess) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - lastPermissionPromptMs
            val perms = com.example.util.buildMediaPermissionsForSdk()
            val canShowRationale = com.example.util.hasAnyMissingPermissionRationale(context, perms)
            if (elapsed in 0L..500L || !canShowRationale) {
                // OS suppressed permission dialog ("Don't ask again") -> open App Settings directly
                openAppPermissionDetails()
            } else {
                vaultViewModel.reportStatusError("يرجى منح صلاحية الوصول إلى ملفات وصور الهاتف للمتابعة.")
            }
        }
    }

    val requestMediaAccessPermission: () -> Unit = {
        vaultViewModel.beginExternalSystemFlow()
        lastPermissionPromptMs = android.os.SystemClock.elapsedRealtime()
        val perms = com.example.util.buildMediaPermissionsForSdk()
        val launched = runCatching {
            runtimeMediaPermissionsLauncher.launch(perms)
            true
        }.getOrDefault(false)
        if (!launched) {
            vaultViewModel.endExternalSystemFlow()
            openAppPermissionDetails()
        }
    }

    val requestAllFilesAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            vaultViewModel.beginExternalSystemFlow()
            val candidateIntents = buildAllFilesAccessSettingIntents(context.packageName)
            if (!launchSystemSettingsCandidates(context, candidateIntents)) {
                vaultViewModel.endExternalSystemFlow()
            }
        } else {
            if (uiState.hasMediaAccess && !uiState.hasAllFilesAccess) {
                openAppPermissionDetails()
            } else {
                requestMediaAccessPermission()
            }
        }
    }

    // Auto-request runtime storage/media permission on first Vault unlock if not granted yet
    var autoRequestedMediaOnUnlock by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isLocked) {
        if (!uiState.isLocked && !uiState.hasMediaAccess && !autoRequestedMediaOnUnlock) {
            autoRequestedMediaOnUnlock = true
            requestMediaAccessPermission()
        }
    }

    // Open reliable built-in Device Media & Files Explorer directly inside the Vault
    val openInAppDevicePicker: (VaultCategory, Boolean) -> Unit = { category, browseFolders ->
        pendingPickCategory = category
        vaultViewModel.openDevicePicker(category = category, browseFolders = browseFolders)
        if (!uiState.hasMediaAccess) {
            requestMediaAccessPermission()
        }
    }

    // Safely guarded optional OS picker fallback (never throws or crashes if system picker is missing)
    val launchSystemPickerFallback: (VaultCategory, Boolean) -> Unit = { category, preferFilesExplorer ->
        pendingPickCategory = category
        vaultViewModel.beginExternalSystemFlow()

        val pm = context.packageManager
        val mimeArray = when (category) {
            VaultCategory.PHOTOS -> arrayOf("image/*")
            VaultCategory.VIDEOS -> arrayOf("video/*")
            VaultCategory.FILES -> arrayOf("*/*")
        }
        val singleMime = mimeArray.first()

        val openDocIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = singleMime
            putExtra(Intent.EXTRA_MIME_TYPES, mimeArray)
        }
        val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = singleMime
        }
        val mediaPickIntent = when (category) {
            VaultCategory.PHOTOS -> Intent(
                Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            )
            VaultCategory.VIDEOS -> Intent(
                Intent.ACTION_PICK,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            )
            VaultCategory.FILES -> getContentIntent
        }

        val photoPickerAvailable = runCatching {
            ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)
        }.getOrDefault(false)
        val photoPickerType = when (category) {
            VaultCategory.PHOTOS -> ActivityResultContracts.PickVisualMedia.ImageOnly
            VaultCategory.VIDEOS -> ActivityResultContracts.PickVisualMedia.VideoOnly
            VaultCategory.FILES -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }

        val launched = if (preferFilesExplorer || category == VaultCategory.FILES) {
            when {
                openDocIntent.resolveActivity(pm) != null && runCatching {
                    openDocumentLauncher.launch(mimeArray)
                }.isSuccess -> true
                getContentIntent.resolveActivity(pm) != null && runCatching {
                    getContentLauncher.launch(singleMime)
                }.isSuccess -> true
                mediaPickIntent.resolveActivity(pm) != null && runCatching {
                    legacyIntentLauncher.launch(mediaPickIntent)
                }.isSuccess -> true
                else -> false
            }
        } else {
            when {
                photoPickerAvailable && runCatching {
                    visualMediaPickerLauncher.launch(PickVisualMediaRequest(photoPickerType))
                }.isSuccess -> true
                openDocIntent.resolveActivity(pm) != null && runCatching {
                    openDocumentLauncher.launch(mimeArray)
                }.isSuccess -> true
                mediaPickIntent.resolveActivity(pm) != null && runCatching {
                    legacyIntentLauncher.launch(mediaPickIntent)
                }.isSuccess -> true
                getContentIntent.resolveActivity(pm) != null && runCatching {
                    getContentLauncher.launch(singleMime)
                }.isSuccess -> true
                else -> false
            }
        }

        if (!launched) {
            vaultViewModel.endExternalSystemFlow()
            vaultViewModel.reportStatusError("مختار النظام غير متاح على هذا الجهاز — استخدم المتصفح المدمج بالخزنة.")
        }
    }

    var biometricError by remember { mutableStateOf<String?>(null) }

    val triggerBiometricAuth: () -> Unit = {
        val fragmentActivity = context.findFragmentActivity()
        if (fragmentActivity == null) {
            biometricError = "تعذر تشغيل نافذة البصمة على هذا الجهاز."
        } else {
            val bioManager = BiometricManager.from(fragmentActivity)
            val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            }

            when (bioManager.canAuthenticate(authenticators)) {
                BiometricManager.BIOMETRIC_SUCCESS -> {
                    biometricError = null
                    vaultViewModel.beginExternalSystemFlow()
                    val promptBuilder = BiometricPrompt.PromptInfo.Builder()
                        .setTitle("فتح خزنة BlackHole")
                        .setSubtitle("تحقق ببصمة الإصبع أو قفل الجهاز للدخول")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        promptBuilder.setAllowedAuthenticators(authenticators)
                    } else {
                        @Suppress("DEPRECATION")
                        promptBuilder.setDeviceCredentialAllowed(true)
                    }

                    val launched = runCatching {
                        val promptInfo = promptBuilder.build()
                        val prompt = BiometricPrompt(
                            fragmentActivity,
                            ContextCompat.getMainExecutor(fragmentActivity),
                            object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(
                                    result: BiometricPrompt.AuthenticationResult,
                                ) {
                                    vaultViewModel.endExternalSystemFlow()
                                    biometricError = null
                                    vaultViewModel.unlockAfterBiometricVerification()
                                }

                                override fun onAuthenticationError(
                                    errorCode: Int,
                                    errString: CharSequence,
                                ) {
                                    vaultViewModel.endExternalSystemFlow()
                                    biometricError = errString.toString()
                                }

                                override fun onAuthenticationFailed() {
                                    biometricError = "البصمة غير مطابقة. حاول مجدداً."
                                }
                            },
                        )
                        prompt.authenticate(promptInfo)
                    }

                    if (launched.isFailure) {
                        vaultViewModel.endExternalSystemFlow()
                        biometricError = launched.exceptionOrNull()?.message
                            ?: "تعذر فتح نافذة التحقق بالبصمة."
                    }
                }
                else -> {
                    biometricError = null
                    vaultViewModel.unlockAfterBiometricVerification(
                        noticeMessage = "لا توجد بصمة مسجلة — تم فتح الخزنة المحمية مباشرة.",
                    )
                }
            }
        }
    }

    var autoPromptedOnEntry by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isLocked) {
        if (uiState.isLocked && !autoPromptedOnEntry) {
            autoPromptedOnEntry = true
            triggerBiometricAuth()
        }
    }

    val safeInsets = rememberSafeScreenInsets(cutoutExtraTop = 8.dp, minTop = 36.dp, minBottom = 16.dp)
    val activeCategory = uiState.selectedCategory

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
                                PlasmaViolet.copy(alpha = 0.14f),
                                NeonCyan.copy(alpha = 0.06f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.35f),
                            radius = size.minDimension * 0.78f,
                        ),
                        radius = size.minDimension * 0.78f,
                        center = Offset(size.width * 0.5f, size.height * 0.35f),
                    )
                }
                .padding(top = safeInsets.top, bottom = safeInsets.bottom),
        ) {
            when {
                uiState.isLocked -> {
                    VaultLockedGate(
                        uiState = uiState,
                        noScreenshotEnabled = noScreenshotEnabled,
                        biometricMessage = biometricError,
                        onBack = {
                            vaultViewModel.lockVaultNow()
                            onBack()
                        },
                        onTriggerBiometric = triggerBiometricAuth,
                        onDismissStatus = vaultViewModel::dismissStatus,
                    )
                }
                activeCategory == null -> {
                    VaultHomeCategoriesDashboard(
                        uiState = uiState,
                        onBack = {
                            vaultViewModel.lockVaultNow()
                            onBack()
                        },
                        onOpenSettings = { showVaultSettings = true },
                        onOpenCategory = { cat -> vaultViewModel.selectCategory(cat) },
                        onRequestAllFilesAccess = requestAllFilesAccess,
                        onRetryDeletePendingOriginals = vaultViewModel::retryDeletePendingOriginals,
                        onDismissPendingOriginals = vaultViewModel::dismissPendingUnremovedOriginals,
                        onCancelTransfer = vaultViewModel::cancelActiveTransfer,
                        onDismissStatus = vaultViewModel::dismissStatus,
                    )
                }
                else -> {
                    VaultCategoryDetailScreen(
                        category = activeCategory,
                        uiState = uiState,
                        cipher = vaultViewModel.getCipher(),
                        onBackToCategories = { vaultViewModel.selectCategory(null) },
                        onOpenSettings = { showVaultSettings = true },
                        onPickFromGallery = { openInAppDevicePicker(activeCategory, false) },
                        onPickFromPhoneFiles = { openInAppDevicePicker(activeCategory, true) },
                        onMoveDownloadedVideo = vaultViewModel::moveDownloadedVideoToVault,
                        onCancelTransfer = vaultViewModel::cancelActiveTransfer,
                        onOpenEntry = vaultViewModel::openEntry,
                        onRestoreEntries = vaultViewModel::restoreEntries,
                        onDeleteEntries = vaultViewModel::deleteEntries,
                        onDismissStatus = vaultViewModel::dismissStatus,
                        onRequestAllFilesAccess = requestAllFilesAccess,
                        onRetryDeletePendingOriginals = vaultViewModel::retryDeletePendingOriginals,
                        onDismissPendingOriginals = vaultViewModel::dismissPendingUnremovedOriginals,
                    )
                }
            }

            if (!uiState.isLocked && showVaultSettings) {
                VaultSettingsOverlay(
                    uiState = uiState,
                    noScreenshotEnabled = noScreenshotEnabled,
                    onToggleNoScreenshot = vaultViewModel::setNoScreenshotEnabled,
                    onLockVaultNow = vaultViewModel::lockVaultNow,
                    onRequestMediaPermission = requestMediaAccessPermission,
                    onRequestAllFilesAccess = requestAllFilesAccess,
                    onSelectInternalStorage = vaultViewModel::revertToInternalVaultStorage,
                    onDismissStatus = vaultViewModel::dismissStatus,
                    onClose = { showVaultSettings = false },
                )
            }

            if (!uiState.isLocked && uiState.devicePicker.isOpen) {
                VaultDevicePickerSheet(
                    pickerState = uiState.devicePicker,
                    hasMediaAccess = uiState.hasMediaAccess,
                    hasAllFilesAccess = uiState.hasAllFilesAccess,
                    onClose = vaultViewModel::closeDevicePicker,
                    onNavigateUpDirectory = { vaultViewModel.navigateUpDeviceDirectory() },
                    onOpenDirectory = vaultViewModel::navigateToDeviceDirectory,
                    onSwitchBrowseMode = vaultViewModel::setDevicePickerBrowseMode,
                    onToggleSelect = vaultViewModel::togglePickerSelection,
                    onClearSelection = vaultViewModel::clearPickerSelection,
                    onConfirmMoveSelected = vaultViewModel::moveSelectedDeviceItemsToVault,
                    onRequestMediaPermission = requestMediaAccessPermission,
                    onRequestAllFilesAccess = requestAllFilesAccess,
                    onOpenSystemPickerFallback = {
                        launchSystemPickerFallback(
                            uiState.devicePicker.category,
                            uiState.devicePicker.browseFoldersMode,
                        )
                    },
                )
            }

            uiState.activePlayback?.let { session ->
                when (session.category) {
                    VaultCategory.PHOTOS -> VaultProtectedPhotoViewer(
                        session = session,
                        cipher = vaultViewModel.getCipher(),
                        noScreenshotEnabled = noScreenshotEnabled,
                        onRestore = vaultViewModel::restoreActivePlayback,
                        onDelete = vaultViewModel::deleteActivePlayback,
                        onClose = vaultViewModel::closePlayer,
                    )
                    VaultCategory.VIDEOS -> VaultProtectedVideoPlayer(
                        session = session,
                        cipher = vaultViewModel.getCipher(),
                        noScreenshotEnabled = noScreenshotEnabled,
                        onRestore = vaultViewModel::restoreActivePlayback,
                        onDelete = vaultViewModel::deleteActivePlayback,
                        onClose = vaultViewModel::closePlayer,
                    )
                    VaultCategory.FILES -> VaultProtectedFileViewer(
                        session = session,
                        cipher = vaultViewModel.getCipher(),
                        noScreenshotEnabled = noScreenshotEnabled,
                        onBeforeExternalOpen = vaultViewModel::beginExternalSystemFlow,
                        onReportOpenError = vaultViewModel::reportStatusError,
                        onRestore = vaultViewModel::restoreActivePlayback,
                        onDelete = vaultViewModel::deleteActivePlayback,
                        onClose = vaultViewModel::closePlayer,
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultLockedGate(
    uiState: VaultUiState,
    noScreenshotEnabled: Boolean,
    biometricMessage: String?,
    onBack: () -> Unit,
    onTriggerBiometric: () -> Unit,
    onDismissStatus: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
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
                    contentDescription = "Back",
                    tint = ChromeSilver,
                )
            }

            Surface(
                color = EmeraldPulse.copy(alpha = 0.14f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(0.8.dp, EmeraldPulse.copy(alpha = 0.55f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = EmeraldPulse,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (noScreenshotEnabled) "FLAG_SECURE · BIOMETRIC ONLY" else "BIOMETRIC PROTECTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldPulse,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(52.dp))

        Surface(
            color = Color(0xFF0E1420).copy(alpha = 0.92f),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, GlassStrokeBright.copy(alpha = 0.28f)),
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    NeonCyan.copy(alpha = 0.25f),
                                    PlasmaViolet.copy(alpha = 0.16f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Fingerprint,
                        contentDescription = "Biometric Vault Lock",
                        tint = NeonCyan,
                        modifier = Modifier.size(44.dp),
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "الخزنة المحمية",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = ChromeSilver,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (noScreenshotEnabled) {
                        "خزنة محمية بالبصمة · FLAG_SECURE\nتحقق بالبصمة للدخول ونقل وإخفاء ملفاتك بأمان"
                    } else {
                        "خزنة محمية بالبصمة\nتحقق بالبصمة للدخول ونقل وإخفاء ملفاتك بأمان"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate,
                    textAlign = TextAlign.Center,
                )

                if (!biometricMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = biometricMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = PlasmaViolet,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                Button(
                    onClick = onTriggerBiometric,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = ObsidianVoid,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("vault_biometric_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Fingerprint,
                        contentDescription = "Unlock with biometric",
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "فتح الخزنة بالبصمة",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }

            }
        }
    }
}

internal fun buildAllFilesAccessSettingIntents(packageName: String): List<Intent> {
    val packageUri = Uri.parse("package:$packageName")
    val fromPartsUri = Uri.fromParts("package", packageName, null)
    val list = mutableListOf<Intent>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        list += Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        list += Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = fromPartsUri
        }
        list += Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        list += Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
    list += buildAppDetailsSettingIntents(packageName)
    return list
}

internal fun buildAppDetailsSettingIntents(packageName: String): List<Intent> {
    val packageUri = Uri.parse("package:$packageName")
    return listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            putExtra("extra_pkgname", packageName)
        },
        Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
}

internal fun launchSystemSettingsCandidates(
    context: Context,
    candidates: List<Intent>,
): Boolean {
    val activity = context.findActivity()
    val appContext = context.applicationContext ?: context
    for (candidate in candidates) {
        val launchedFromActivity = if (activity != null) {
            runCatching {
                activity.startActivity(Intent(candidate))
                true
            }.getOrDefault(false)
        } else {
            false
        }
        if (launchedFromActivity) return true

        val launchedNewTask = runCatching {
            appContext.startActivity(Intent(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (launchedNewTask) return true
    }
    return false
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
