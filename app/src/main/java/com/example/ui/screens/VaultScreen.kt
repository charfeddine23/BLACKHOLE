package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
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

    // Enforce FLAG_SECURE while VaultScreen is visible to block screenshots & screen capture
    DisposableEffect(context) {
        val activity = context.findActivity()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
            vaultViewModel.moveMediaToVault(selectedUri, pendingPickCategory)
        }
    }

    val getContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { selectedUri: Uri? ->
        vaultViewModel.endExternalSystemFlow()
        if (selectedUri != null) {
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
            vaultViewModel.moveMediaToVault(selectedUri, pendingPickCategory)
        }
    }

    val visualMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { selectedUri: Uri? ->
        vaultViewModel.endExternalSystemFlow()
        if (selectedUri != null) {
            vaultViewModel.moveMediaToVault(selectedUri, pendingPickCategory)
        }
    }

    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri: Uri? ->
        vaultViewModel.endExternalSystemFlow()
        if (treeUri != null) {
            vaultViewModel.onSafDirectorySelected(treeUri)
        }
    }

    val safTreeIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vaultViewModel.endExternalSystemFlow()
        val treeUri = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data
        } else {
            null
        }
        if (treeUri != null) {
            vaultViewModel.onSafDirectorySelected(treeUri)
        }
    }

    val allFilesSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        vaultViewModel.endExternalSystemFlow()
        vaultViewModel.refreshPermissionAndEntries()
    }

    val legacyStoragePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        vaultViewModel.endExternalSystemFlow()
        val writeGranted = grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        vaultViewModel.refreshPermissionAndEntries()
        if (!writeGranted) {
            vaultViewModel.reportStatusError("تم رفض صلاحية الوصول إلى ملفات الجهاز.")
        }
    }

    val requestAllFilesAccess: () -> Unit = {
        vaultViewModel.beginExternalSystemFlow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettingsIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            val fallbackSettingsIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            val launched = runCatching {
                allFilesSettingsLauncher.launch(appSettingsIntent)
                true
            }.getOrElse {
                runCatching {
                    allFilesSettingsLauncher.launch(fallbackSettingsIntent)
                    true
                }.getOrDefault(false)
            }
            if (!launched) {
                vaultViewModel.endExternalSystemFlow()
                vaultViewModel.reportStatusError("تعذر فتح إعدادات صلاحية الوصول إلى الملفات.")
            }
        } else {
            legacyStoragePermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    var pendingPhoneFallbackNotice by remember { mutableStateOf<String?>(null) }

    val legacyWritePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted: Boolean ->
        vaultViewModel.endExternalSystemFlow()
        val notice = pendingPhoneFallbackNotice
        pendingPhoneFallbackNotice = null
        if (granted) {
            vaultViewModel.activatePhoneDownloadsVault(notice)
        } else {
            vaultViewModel.reportStatusError(
                "تم رفض صلاحية الكتابة في الهاتف. سيبقى التخزين الداخلي قيد الاستخدام.",
            )
        }
    }

    val switchToPhoneDownloadsVault: (String?) -> Unit = { autoNotice ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vaultViewModel.activatePhoneDownloadsVault(autoNotice)
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                vaultViewModel.activatePhoneDownloadsVault(autoNotice)
            } else {
                pendingPhoneFallbackNotice = autoNotice
                vaultViewModel.beginExternalSystemFlow()
                legacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    val launchCategoryPicker: (VaultCategory, Boolean) -> Unit = { category, preferFilesExplorer ->
        pendingPickCategory = category
        vaultViewModel.beginExternalSystemFlow()

        val mimeArray = when (category) {
            VaultCategory.PHOTOS -> arrayOf("image/*")
            VaultCategory.VIDEOS -> arrayOf("video/*")
            VaultCategory.FILES -> arrayOf("*/*")
        }
        val singleMime = mimeArray.first()

        val mediaPickIntent = when (category) {
            VaultCategory.PHOTOS -> Intent(
                Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            )
            VaultCategory.VIDEOS -> Intent(
                Intent.ACTION_PICK,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            )
            VaultCategory.FILES -> Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        }

        val getContentChooserIntent = Intent.createChooser(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = singleMime
            },
            "اختر من ملفات الهاتف",
        )

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
                runCatching { openDocumentLauncher.launch(mimeArray) }.isSuccess -> true
                runCatching { getContentLauncher.launch(singleMime) }.isSuccess -> true
                runCatching { legacyIntentLauncher.launch(getContentChooserIntent) }.isSuccess -> true
                photoPickerAvailable && runCatching {
                    visualMediaPickerLauncher.launch(PickVisualMediaRequest(photoPickerType))
                }.isSuccess -> true
                else -> false
            }
        } else {
            when {
                photoPickerAvailable && runCatching {
                    visualMediaPickerLauncher.launch(PickVisualMediaRequest(photoPickerType))
                }.isSuccess -> true
                runCatching { openDocumentLauncher.launch(mimeArray) }.isSuccess -> true
                runCatching { getContentLauncher.launch(singleMime) }.isSuccess -> true
                runCatching { legacyIntentLauncher.launch(mediaPickIntent) }.isSuccess -> true
                runCatching { legacyIntentLauncher.launch(getContentChooserIntent) }.isSuccess -> true
                else -> false
            }
        }

        if (!launched) {
            vaultViewModel.endExternalSystemFlow()
            vaultViewModel.reportStatusError("تعذر فتح معرض أو ملفات الهاتف على هذا الجهاز.")
        }
    }

    val launchSafFolderSelection: () -> Unit = {
        vaultViewModel.beginExternalSystemFlow()

        val initialTreeHint = runCatching {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Documents",
            )
        }.getOrNull()

        val treeIntentFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        fun buildBaseTreeIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(treeIntentFlags)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && initialTreeHint != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialTreeHint)
            }
        }

        val launched = when {
            runCatching { safFolderLauncher.launch(initialTreeHint) }.isSuccess -> true
            runCatching { safFolderLauncher.launch(null) }.isSuccess -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
                val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                val smIntent = storageManager?.primaryStorageVolume?.createOpenDocumentTreeIntent()?.apply {
                    addFlags(treeIntentFlags)
                } ?: return@runCatching false
                safTreeIntentLauncher.launch(smIntent)
                true
            }.getOrDefault(false) -> true
            runCatching {
                safTreeIntentLauncher.launch(
                    buildBaseTreeIntent().setPackage("com.google.android.documentsui"),
                )
            }.isSuccess -> true
            runCatching {
                safTreeIntentLauncher.launch(
                    buildBaseTreeIntent().setPackage("com.android.documentsui"),
                )
            }.isSuccess -> true
            runCatching {
                safTreeIntentLauncher.launch(
                    Intent.createChooser(buildBaseTreeIntent(), "Select External Vault Folder"),
                )
            }.isSuccess -> true
            else -> false
        }

        if (!launched) {
            vaultViewModel.endExternalSystemFlow()
            switchToPhoneDownloadsVault(
                "مختار SAF غير متاح — تم تفعيل مجلد الخزنة على الهاتف تلقائياً.",
            )
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
        floatingActionButton = {
            if (!uiState.isLocked && activeCategory != null && uiState.activePlayback == null) {
                val fabAccent = when (activeCategory) {
                    VaultCategory.PHOTOS -> NeonCyan
                    VaultCategory.VIDEOS -> PlasmaViolet
                    VaultCategory.FILES -> EmeraldPulse
                }
                ExtendedFloatingActionButton(
                    onClick = { launchCategoryPicker(activeCategory, false) },
                    containerColor = fabAccent,
                    contentColor = ObsidianVoid,
                    modifier = Modifier
                        .padding(bottom = safeInsets.bottom)
                        .testTag("vault_add_video_fab"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddPhotoAlternate,
                        contentDescription = "إضافة من المعرض",
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "إضافة من المعرض",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        },
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
                        onLockNow = vaultViewModel::lockVaultNow,
                        onOpenCategory = { cat -> vaultViewModel.selectCategory(cat) },
                        onRequestAllFilesAccess = requestAllFilesAccess,
                        onSelectPhoneDownloads = { switchToPhoneDownloadsVault(null) },
                        onSelectSafFolder = launchSafFolderSelection,
                        onRevertInternalStorage = vaultViewModel::revertToInternalVaultStorage,
                        onCancelTransfer = vaultViewModel::cancelActiveTransfer,
                        onDismissStatus = vaultViewModel::dismissStatus,
                    )
                }
                else -> {
                    VaultCategoryDetailScreen(
                        category = activeCategory,
                        uiState = uiState,
                        onBackToCategories = { vaultViewModel.selectCategory(null) },
                        onLockNow = vaultViewModel::lockVaultNow,
                        onPickFromGallery = { launchCategoryPicker(activeCategory, false) },
                        onPickFromPhoneFiles = { launchCategoryPicker(activeCategory, true) },
                        onMoveDownloadedVideo = vaultViewModel::moveDownloadedVideoToVault,
                        onCancelTransfer = vaultViewModel::cancelActiveTransfer,
                        onOpenEntry = vaultViewModel::openEntry,
                        onRestoreEntry = vaultViewModel::restoreEntry,
                        onDeleteEntry = vaultViewModel::deleteEntry,
                        onDismissStatus = vaultViewModel::dismissStatus,
                    )
                }
            }

            uiState.activePlayback?.let { session ->
                when (session.category) {
                    VaultCategory.PHOTOS -> VaultProtectedPhotoViewer(
                        session = session,
                        onClose = vaultViewModel::closePlayer,
                    )
                    VaultCategory.VIDEOS -> VaultProtectedVideoPlayer(
                        session = session,
                        onClose = vaultViewModel::closePlayer,
                    )
                    VaultCategory.FILES -> VaultProtectedFileViewer(
                        session = session,
                        onBeforeExternalOpen = vaultViewModel::beginExternalSystemFlow,
                        onReportOpenError = vaultViewModel::reportStatusError,
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
                        text = "FLAG_SECURE · BIOMETRIC ONLY",
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
                    text = "خزنة محمية بالبصمة · FLAG_SECURE\nتحقق بالبصمة للدخول ونقل وإخفاء ملفاتك بأمان",
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

                AnimatedVisibility(visible = !uiState.statusMessage.isNullOrBlank()) {
                    uiState.statusMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            color = (if (uiState.isErrorStatus) CrimsonAlert else EmeraldPulse).copy(alpha = 0.14f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                0.8.dp,
                                (if (uiState.isErrorStatus) CrimsonAlert else EmeraldPulse).copy(alpha = 0.55f),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDismissStatus() },
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.isErrorStatus) CrimsonAlert else EmeraldPulse,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
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
