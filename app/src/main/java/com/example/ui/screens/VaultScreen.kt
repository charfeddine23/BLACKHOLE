package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.ui.viewmodel.BlackholeViewModel
import com.example.vault.crypto.VaultOperationStage
import com.example.vault.data.VaultFileEntry
import com.example.vault.ui.VaultPlaybackSession
import com.example.vault.ui.VaultTransferUiState
import com.example.vault.ui.VaultUiState
import com.example.vault.ui.VaultViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // Auto-lock and scrub decrypted temp cache on genuine backgrounding or leaving screen,
    // while ignoring temporary ON_STOP transitions caused by system file/biometric pickers.
    DisposableEffect(lifecycleOwner, vaultViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (!vaultViewModel.isAwaitingExternalSystemFlow()) {
                        vaultViewModel.lockVaultAndWipe()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    vaultViewModel.endExternalSystemFlow()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vaultViewModel.lockVaultAndWipe()
        }
    }

    BackHandler {
        if (uiState.activePlayback != null) {
            vaultViewModel.closePlayerAndWipeTemp()
        } else {
            vaultViewModel.lockVaultAndWipe()
            onBack()
        }
    }

    val openVideoDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { selectedUri: Uri? ->
        vaultViewModel.endExternalSystemFlow()
        if (selectedUri != null) {
            vaultViewModel.encryptVideoFromPicker(selectedUri)
        }
    }

    val getContentVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { selectedUri: Uri? ->
        vaultViewModel.endExternalSystemFlow()
        if (selectedUri != null) {
            vaultViewModel.encryptVideoFromPicker(selectedUri)
        }
    }

    val legacyVideoIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vaultViewModel.endExternalSystemFlow()
        val selectedUri = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data ?: result.data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } else {
            null
        }
        if (selectedUri != null) {
            vaultViewModel.encryptVideoFromPicker(selectedUri)
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { selectedUri: Uri? ->
        vaultViewModel.endExternalSystemFlow()
        if (selectedUri != null) {
            vaultViewModel.encryptVideoFromPicker(selectedUri)
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

    val launchVideoSelection: () -> Unit = {
        val pm = context.packageManager
        vaultViewModel.beginExternalSystemFlow()

        fun canResolve(intent: Intent): Boolean =
            runCatching { intent.resolveActivity(pm) != null }.getOrDefault(false)

        val openDocIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        val getContentVideoIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        val actionPickVideoIntent = Intent(
            Intent.ACTION_PICK,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        val getContentAnyIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }

        val launched = when {
            canResolve(openDocIntent) && runCatching {
                openVideoDocumentLauncher.launch(arrayOf("video/*"))
            }.isSuccess -> true

            canResolve(getContentVideoIntent) && runCatching {
                getContentVideoLauncher.launch("video/*")
            }.isSuccess -> true

            canResolve(actionPickVideoIntent) && runCatching {
                legacyVideoIntentLauncher.launch(actionPickVideoIntent)
            }.isSuccess -> true

            canResolve(getContentAnyIntent) && runCatching {
                legacyVideoIntentLauncher.launch(
                    Intent.createChooser(getContentAnyIntent, "Select Video"),
                )
            }.isSuccess -> true

            runCatching {
                ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)
            }.getOrDefault(false) && runCatching {
                videoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                )
            }.isSuccess -> true

            else -> runCatching {
                legacyVideoIntentLauncher.launch(
                    Intent.createChooser(getContentVideoIntent, "Select Video"),
                )
            }.isSuccess
        }

        if (!launched) {
            vaultViewModel.endExternalSystemFlow()
            vaultViewModel.reportStatusError("Unable to open phone file picker on this device.")
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
            // 1. Standard ActivityResultContracts.OpenDocumentTree (never pre-blocked by resolveActivity)
            runCatching { safFolderLauncher.launch(initialTreeHint) }.isSuccess -> true
            runCatching { safFolderLauncher.launch(null) }.isSuccess -> true

            // 2. Android 10+ StorageManager primary volume open-document-tree intent
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
                val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                val smIntent = storageManager?.primaryStorageVolume?.createOpenDocumentTreeIntent()?.apply {
                    addFlags(treeIntentFlags)
                } ?: return@runCatching false
                safTreeIntentLauncher.launch(smIntent)
                true
            }.getOrDefault(false) -> true

            // 3. Explicit Google / AOSP DocumentsUI packages
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

            // 4. Raw ACTION_OPEN_DOCUMENT_TREE chooser fallback
            runCatching {
                safTreeIntentLauncher.launch(
                    Intent.createChooser(buildBaseTreeIntent(), "Select External Vault Folder"),
                )
            }.isSuccess -> true

            else -> false
        }

        if (!launched) {
            vaultViewModel.endExternalSystemFlow()
            vaultViewModel.reportStatusError(
                "No system Files/Documents app found to pick an external folder — Private Internal Vault remains active.",
            )
        }
    }

    var biometricError by remember { mutableStateOf<String?>(null) }

    val triggerBiometricAuth: () -> Unit = {
        val fragmentActivity = context.findFragmentActivity()
        if (fragmentActivity == null) {
            biometricError = "Biometric prompt unavailable in current host."
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
                        .setTitle("Unlock BlackHole Vault")
                        .setSubtitle("Verify your fingerprint or biometric")

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
                                    biometricError = "Biometric did not match. Try again."
                                }
                            },
                        )
                        prompt.authenticate(promptInfo)
                    }

                    if (launched.isFailure) {
                        vaultViewModel.endExternalSystemFlow()
                        biometricError = launched.exceptionOrNull()?.message
                            ?: "Unable to open biometric prompt."
                    }
                }
                else -> {
                    biometricError = null
                    vaultViewModel.unlockAfterBiometricVerification(
                        noticeMessage = "No biometric enrolled on this device — unlocked with local key.",
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

    val density = LocalDensity.current
    val cutoutTop = with(density) { WindowInsets.displayCutout.getTop(density).toDp() }
    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val safeTopInset = maxOf(cutoutTop + 8.dp, statusTop, 36.dp)
    val cutoutBottom = with(density) { WindowInsets.displayCutout.getBottom(density).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val safeBottomInset = maxOf(cutoutBottom, navBottom, 16.dp)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("vault_screen"),
        containerColor = ObsidianVoid,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (!uiState.isLocked && uiState.activePlayback == null) {
                ExtendedFloatingActionButton(
                    onClick = launchVideoSelection,
                    containerColor = NeonCyan,
                    contentColor = ObsidianVoid,
                    modifier = Modifier
                        .padding(bottom = safeBottomInset)
                        .testTag("vault_add_video_fab"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Add video to encrypted vault",
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ENCRYPT VIDEO",
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
                .padding(top = safeTopInset, bottom = safeBottomInset),
        ) {
            if (uiState.isLocked) {
                VaultLockedGate(
                    uiState = uiState,
                    biometricMessage = biometricError,
                    onBack = {
                        vaultViewModel.lockVaultAndWipe()
                        onBack()
                    },
                    onTriggerBiometric = triggerBiometricAuth,
                    onDismissStatus = vaultViewModel::dismissStatus,
                )
            } else {
                VaultUnlockedDashboard(
                    uiState = uiState,
                    onBack = {
                        vaultViewModel.lockVaultAndWipe()
                        onBack()
                    },
                    onLockNow = vaultViewModel::lockVaultAndWipe,
                    onSelectSafFolder = launchSafFolderSelection,
                    onRevertInternalStorage = vaultViewModel::revertToInternalVaultStorage,
                    onCancelTransfer = vaultViewModel::cancelActiveTransfer,
                    onPlayEntry = vaultViewModel::decryptAndPlayEntry,
                    onDeleteEntry = vaultViewModel::deleteEntry,
                    onDismissStatus = vaultViewModel::dismissStatus,
                )
            }

            uiState.activePlayback?.let { session ->
                VaultZeroFootprintPlayer(
                    session = session,
                    onCloseAndWipe = vaultViewModel::closePlayerAndWipeTemp,
                )
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
                    text = "BIOMETRIC VAULT",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = ChromeSilver,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "AES-256-GCM Chunked Vault · Hardware Keystore\nVerify fingerprint to enter",
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
                        text = "UNLOCK WITH BIOMETRIC",
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

@Composable
private fun VaultUnlockedDashboard(
    uiState: VaultUiState,
    onBack: () -> Unit,
    onLockNow: () -> Unit,
    onSelectSafFolder: () -> Unit,
    onRevertInternalStorage: () -> Unit,
    onCancelTransfer: () -> Unit,
    onPlayEntry: (VaultFileEntry) -> Unit,
    onDeleteEntry: (VaultFileEntry) -> Unit,
    onDismissStatus: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
                Column {
                    Text(
                        text = "BLACKHOLE VAULT",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "BHVAULT2 · AES-256-GCM · ${uiState.entries.size} ENCRYPTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            OutlinedButton(
                onClick = onLockNow,
                shape = RoundedCornerShape(50),
                border = BorderStroke(0.85.dp, PlasmaViolet.copy(alpha = 0.7f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("vault_lock_now_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = "Lock vault",
                    tint = PlasmaViolet,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "LOCK",
                    style = MaterialTheme.typography.labelSmall,
                    color = ChromeSilver,
                )
            }
        }

        // Persistent SAF Folder Banner Card
        Surface(
            color = Color(0xFF0E1420),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(
                0.85.dp,
                if (uiState.isExternalSafActive) EmeraldPulse.copy(alpha = 0.55f) else GlassStrokeBright.copy(alpha = 0.25f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
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
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = "Vault storage location",
                        tint = if (uiState.isExternalSafActive) EmeraldPulse else NeonCyan,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = uiState.storageLabel,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = ChromeSilver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (uiState.isExternalSafActive) {
                                "External SAF folder keeps .bhv files intact."
                            } else {
                                "Pick an external SAF folder or use private storage."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlate,
                            maxLines = 2,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = onSelectSafFolder,
                        modifier = Modifier.testTag("vault_choose_saf_folder_button"),
                    ) {
                        Text(
                            text = if (uiState.isExternalSafActive) "CHANGE" else "CHOOSE SAF",
                            color = NeonCyan,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    if (uiState.isExternalSafActive) {
                        Text(
                            text = "Use Internal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlate,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onRevertInternalStorage() }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        // Inline Transfer Progress Card (Encrypting / Decrypting chunks)
        val transfer = uiState.transferState
        if (transfer is VaultTransferUiState.Running) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = Color(0xFF101928),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(0.9.dp, NeonCyan.copy(alpha = 0.65f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("vault_transfer_progress_card"),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val actionTitle = when {
                            transfer.stage == VaultOperationStage.DERIVING_KEY -> "Preparing AES-256-GCM key..."
                            transfer.isEncrypting -> "Encrypting: ${transfer.label}"
                            else -> "Decrypting & Verifying AAD: ${transfer.label}"
                        }
                        Text(
                            text = actionTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = ChromeSilver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${(transfer.progress * 100f).toInt()}%",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = NeonCyan,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { transfer.progress.coerceIn(0f, 1f) },
                        color = NeonCyan,
                        trackColor = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${formatVaultMb(transfer.bytesProcessed)} / ${formatVaultMb(transfer.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlate,
                        )
                        Text(
                            text = "CANCEL",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = CrimsonAlert,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onCancelTransfer() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // Inline Dismissible Status / Error Banner (Zero Popups)
        AnimatedVisibility(visible = !uiState.statusMessage.isNullOrBlank()) {
            uiState.statusMessage?.let { msg ->
                Surface(
                    color = (if (uiState.isErrorStatus) CrimsonAlert else EmeraldPulse).copy(alpha = 0.14f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        0.8.dp,
                        (if (uiState.isErrorStatus) CrimsonAlert else EmeraldPulse).copy(alpha = 0.55f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.isErrorStatus) CrimsonAlert else EmeraldPulse,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = onDismissStatus,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Dismiss message",
                                tint = MutedSlate,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        // Encrypted .bhv Media List
        if (uiState.entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = NeonCyan.copy(alpha = 0.65f),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Vault Empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = ChromeSilver,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap ENCRYPT VIDEO to seal large videos in 1 MB AES-256-GCM authenticated chunks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(uiState.entries, key = { it.uriString }) { item ->
                    EncryptedVaultFileCard(
                        entry = item,
                        onPlay = { onPlayEntry(item) },
                        onDelete = { onDeleteEntry(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EncryptedVaultFileCard(
    entry: VaultFileEntry,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(entry.uriString) { mutableStateOf(false) }

    Surface(
        color = Color(0xFF0E1420),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(0.85.dp, GlassStrokeBright.copy(alpha = 0.24f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onPlay() }
            .testTag("vault_entry_${entry.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    color = NeonCyan.copy(alpha = 0.14f),
                    shape = CircleShape,
                    border = BorderStroke(0.8.dp, NeonCyan.copy(alpha = 0.55f)),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Decrypt and play ${entry.displayTitle}",
                            tint = NeonCyan,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${if (entry.isSafExternal) "SAF .bhv" else "Internal .bhv"} · ${formatVaultMb(entry.sizeBytes)} · ${formatVaultDate(entry.lastModifiedMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (confirmDelete) {
                Surface(
                    color = CrimsonAlert,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            confirmDelete = false
                            onDelete()
                        }
                        .minimumInteractiveComponentSize(),
                ) {
                    Text(
                        text = "CONFIRM",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete encrypted file",
                        tint = CrimsonAlert,
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VaultZeroFootprintPlayer(
    session: VaultPlaybackSession,
    onCloseAndWipe: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(session.tempFile.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(session.tempFile)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Zero-Footprint Cache · FLAG_SECURE Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldPulse,
                    )
                }
                Surface(
                    color = CrimsonAlert.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(0.9.dp, CrimsonAlert),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onCloseAndWipe() }
                        .minimumInteractiveComponentSize()
                        .testTag("vault_close_player_button"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close and wipe cache",
                            tint = CrimsonAlert,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "CLOSE & WIPE",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
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

private fun formatVaultMb(bytes: Long): String {
    if (bytes <= 0L) return "0.0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}

private fun formatVaultDate(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    return SimpleDateFormat("MMM d", Locale.US).format(Date(timestampMs))
}
