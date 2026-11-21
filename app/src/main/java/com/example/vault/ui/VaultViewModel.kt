package com.example.vault.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vault.data.DeviceMediaBrowser
import com.example.vault.data.DeviceMediaItem
import com.example.vault.data.PendingOriginalDeletion
import com.example.vault.data.SafVaultRepository
import com.example.vault.data.VaultCategory
import com.example.vault.data.VaultCipher
import com.example.vault.data.VaultFileEntry
import com.example.vault.data.VaultStorageMode
import com.example.vault.data.VaultTempShareHelper
import com.example.vault.data.VaultTransferStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface VaultTransferUiState {
    data object Idle : VaultTransferUiState
    data class Running(
        val progress: Float,
        val bytesProcessed: Long,
        val totalBytes: Long,
        val stage: VaultTransferStage,
        val label: String,
    ) : VaultTransferUiState
}

data class VaultPlaybackSession(
    val uriString: String,
    val localFile: File?,
    val displayTitle: String,
    val sizeBytes: Long,
    val mimeType: String,
    val category: VaultCategory,
    val isEncrypted: Boolean = true,
)

data class VaultDevicePickerUiState(
    val isOpen: Boolean = false,
    val category: VaultCategory = VaultCategory.VIDEOS,
    val browseFoldersMode: Boolean = false,
    val isLoading: Boolean = false,
    val items: List<DeviceMediaItem> = emptyList(),
    val selectedUris: Set<String> = emptySet(),
    val currentDirectoryPath: String? = null,
    val canNavigateUp: Boolean = false,
)

data class VaultUiState(
    val isLocked: Boolean = true,
    val biometricVerified: Boolean = false,
    val hasMediaAccess: Boolean = false,
    val hasAllFilesAccess: Boolean = false,
    val selectedCategory: VaultCategory? = null,
    val isExternalSafActive: Boolean = false,
    val storageMode: VaultStorageMode = VaultStorageMode.INTERNAL,
    val storageLabel: String = "خزنة التطبيق المشفرة المعزولة (AES-256)",
    val entries: List<VaultFileEntry> = emptyList(),
    val availableDownloadedVideos: List<File> = emptyList(),
    val transferState: VaultTransferUiState = VaultTransferUiState.Idle,
    val statusMessage: String? = null,
    val isErrorStatus: Boolean = false,
    val activePlayback: VaultPlaybackSession? = null,
    val devicePicker: VaultDevicePickerUiState = VaultDevicePickerUiState(),
    val pendingMediaDeleteSender: IntentSender? = null,
    val pendingDeleteCandidateUris: List<String> = emptyList(),
    val pendingUnremovedOriginals: List<PendingOriginalDeletion> = emptyList(),
)

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val vaultCipher = VaultCipher(application.applicationContext)
    private val repository = SafVaultRepository(application.applicationContext, vaultCipher)
    private val deviceBrowser = DeviceMediaBrowser(application.applicationContext)

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private var activeTransferJob: Job? = null
    private var pickerLoadJob: Job? = null

    @Volatile
    private var awaitingExternalSystemFlow: Boolean = false

    init {
        viewModelScope.launch {
            refreshStorageAndPermissionState()
        }
    }

    fun getCipher(): VaultCipher = vaultCipher

    fun beginExternalSystemFlow() {
        awaitingExternalSystemFlow = true
    }

    fun endExternalSystemFlow() {
        awaitingExternalSystemFlow = false
    }

    fun isAwaitingExternalSystemFlow(): Boolean = awaitingExternalSystemFlow

    fun unlockAfterBiometricVerification(noticeMessage: String? = null) {
        viewModelScope.launch {
            refreshStorageAndPermissionState(noticeMessage = noticeMessage, unlock = true)
            refreshEntries()
        }
    }

    fun refreshPermissionAndEntries() {
        viewModelScope.launch {
            refreshStorageAndPermissionState()
            if (!_uiState.value.isLocked) {
                if (_uiState.value.hasAllFilesAccess && _uiState.value.pendingUnremovedOriginals.isNotEmpty()) {
                    performPendingOriginalDeletions()
                }
                refreshEntries()
                if (_uiState.value.devicePicker.isOpen) {
                    reloadDevicePickerItems()
                }
            }
        }
    }

    private suspend fun refreshStorageAndPermissionState(
        noticeMessage: String? = _uiState.value.statusMessage,
        unlock: Boolean = false,
    ) {
        val hasFilesAccess = repository.hasAllFilesAccess()
        val hasMedia = repository.hasMediaReadAccess()
        val mode = runCatching { repository.getActiveStorageMode() }.getOrDefault(VaultStorageMode.INTERNAL)
        val label = runCatching { repository.getStorageDisplayLabel() }
            .getOrDefault("خزنة التطبيق المشفرة المعزولة (AES-256)")
        _uiState.update {
            it.copy(
                isLocked = if (unlock) false else it.isLocked,
                biometricVerified = if (unlock) true else it.biometricVerified,
                hasMediaAccess = hasMedia,
                hasAllFilesAccess = hasFilesAccess,
                isExternalSafActive = mode != VaultStorageMode.INTERNAL,
                storageMode = mode,
                storageLabel = label,
                statusMessage = noticeMessage,
                isErrorStatus = if (unlock) false else it.isErrorStatus,
            )
        }
    }

    fun selectCategory(category: VaultCategory?) {
        _uiState.update { it.copy(selectedCategory = category, statusMessage = null) }
    }

    fun lockVaultNow() {
        awaitingExternalSystemFlow = false
        activeTransferJob?.cancel()
        activeTransferJob = null
        pickerLoadJob?.cancel()
        pickerLoadJob = null
        VaultTempShareHelper.clearTempShareCache(getApplication())
        _uiState.update {
            it.copy(
                isLocked = true,
                biometricVerified = false,
                selectedCategory = null,
                entries = emptyList(),
                activePlayback = null,
                transferState = VaultTransferUiState.Idle,
                devicePicker = VaultDevicePickerUiState(),
                pendingMediaDeleteSender = null,
                pendingDeleteCandidateUris = emptyList(),
            )
        }
    }

    fun refreshEntries() {
        viewModelScope.launch {
            val items = runCatching { repository.listVaultEntries() }.getOrDefault(emptyList())
            val downloadedVideos = runCatching { repository.listBlackholeDownloadedVideos() }.getOrDefault(emptyList())
            val hasFilesAccess = repository.hasAllFilesAccess()
            val hasMedia = repository.hasMediaReadAccess()
            val mode = runCatching { repository.getActiveStorageMode() }.getOrDefault(VaultStorageMode.INTERNAL)
            val label = runCatching { repository.getStorageDisplayLabel() }
                .getOrDefault("خزنة التطبيق المشفرة المعزولة (AES-256)")
            _uiState.update {
                it.copy(
                    entries = items,
                    availableDownloadedVideos = downloadedVideos,
                    hasMediaAccess = hasMedia,
                    hasAllFilesAccess = hasFilesAccess,
                    isExternalSafActive = mode != VaultStorageMode.INTERNAL,
                    storageMode = mode,
                    storageLabel = label,
                )
            }
        }
    }

    // ---- Built-In In-App Device Media & Files Explorer ----

    fun openDevicePicker(
        category: VaultCategory,
        browseFolders: Boolean = false,
    ) {
        val rootPath = deviceBrowser.getExternalStorageRoot().absolutePath
        _uiState.update {
            it.copy(
                devicePicker = VaultDevicePickerUiState(
                    isOpen = true,
                    category = category,
                    browseFoldersMode = browseFolders || category == VaultCategory.FILES,
                    isLoading = true,
                    items = emptyList(),
                    selectedUris = emptySet(),
                    currentDirectoryPath = if (browseFolders || category == VaultCategory.FILES) rootPath else null,
                    canNavigateUp = false,
                ),
            )
        }
        reloadDevicePickerItems()
    }

    fun setDevicePickerBrowseMode(browseFolders: Boolean) {
        val current = _uiState.value.devicePicker
        val rootPath = deviceBrowser.getExternalStorageRoot().absolutePath
        _uiState.update {
            it.copy(
                devicePicker = current.copy(
                    browseFoldersMode = browseFolders,
                    isLoading = true,
                    selectedUris = emptySet(),
                    currentDirectoryPath = if (browseFolders) (current.currentDirectoryPath ?: rootPath) else null,
                    canNavigateUp = browseFolders && current.currentDirectoryPath != null &&
                        current.currentDirectoryPath != rootPath,
                ),
            )
        }
        reloadDevicePickerItems()
    }

    fun navigateToDeviceDirectory(dirPath: String) {
        val rootPath = deviceBrowser.getExternalStorageRoot().absolutePath
        _uiState.update {
            it.copy(
                devicePicker = it.devicePicker.copy(
                    browseFoldersMode = true,
                    isLoading = true,
                    currentDirectoryPath = dirPath,
                    canNavigateUp = dirPath != rootPath,
                ),
            )
        }
        reloadDevicePickerItems()
    }

    fun navigateUpDeviceDirectory(): Boolean {
        val picker = _uiState.value.devicePicker
        if (!picker.isOpen || !picker.browseFoldersMode) return false
        val rootPath = deviceBrowser.getExternalStorageRoot().absolutePath
        val currPath = picker.currentDirectoryPath ?: return false
        if (currPath == rootPath) return false
        val parent = File(currPath).parentFile ?: return false
        val targetPath = if (parent.absolutePath.startsWith(rootPath)) parent.absolutePath else rootPath
        navigateToDeviceDirectory(targetPath)
        return true
    }

    fun reloadDevicePickerItems() {
        pickerLoadJob?.cancel()
        pickerLoadJob = viewModelScope.launch {
            val snapshot = _uiState.value.devicePicker
            if (!snapshot.isOpen) return@launch
            _uiState.update {
                it.copy(
                    hasMediaAccess = repository.hasMediaReadAccess(),
                    hasAllFilesAccess = repository.hasAllFilesAccess(),
                    devicePicker = it.devicePicker.copy(isLoading = true),
                )
            }
            val loadedItems = if (snapshot.browseFoldersMode) {
                val root = deviceBrowser.getExternalStorageRoot()
                val dir = snapshot.currentDirectoryPath?.let(::File) ?: root
                val dirItems = if (repository.hasAllFilesAccess() || dir.canRead()) {
                    deviceBrowser.listDeviceDirectory(dir)
                } else {
                    emptyList()
                }
                dirItems.ifEmpty {
                    if (dir.absolutePath == root.absolutePath && !repository.hasAllFilesAccess()) {
                        deviceBrowser.queryDeviceMedia(VaultCategory.FILES)
                    } else {
                        emptyList()
                    }
                }
            } else {
                deviceBrowser.queryDeviceMedia(snapshot.category)
            }
            _uiState.update {
                it.copy(
                    devicePicker = it.devicePicker.copy(
                        isLoading = false,
                        items = loadedItems,
                    ),
                )
            }
        }
    }

    fun togglePickerSelection(uriString: String) {
        _uiState.update { state ->
            val curr = state.devicePicker.selectedUris
            val next = if (uriString in curr) curr - uriString else curr + uriString
            state.copy(devicePicker = state.devicePicker.copy(selectedUris = next))
        }
    }

    fun clearPickerSelection() {
        _uiState.update { it.copy(devicePicker = it.devicePicker.copy(selectedUris = emptySet())) }
    }

    fun closeDevicePicker() {
        pickerLoadJob?.cancel()
        pickerLoadJob = null
        _uiState.update {
            it.copy(devicePicker = VaultDevicePickerUiState())
        }
    }

    fun moveSelectedDeviceItemsToVault() {
        if (activeTransferJob?.isActive == true) return
        val picker = _uiState.value.devicePicker
        val selectedItems = picker.items.filter { !it.isDirectory && it.uriString in picker.selectedUris }
        if (selectedItems.isEmpty()) return

        _uiState.update {
            it.copy(devicePicker = it.devicePicker.copy(isOpen = false, selectedUris = emptySet()))
        }

        activeTransferJob = viewModelScope.launch {
            var movedCount = 0
            var originalsDeletedCount = 0
            val pendingMediaUris = mutableListOf<Uri>()
            val pendingOriginals = mutableListOf<PendingOriginalDeletion>()
            var recoverableSenderQ: IntentSender? = null
            var lastCategory = picker.category

            try {
                for ((index, item) in selectedItems.withIndex()) {
                    val uri = Uri.parse(item.uriString)
                    repository.moveMediaToVault(
                        sourceUri = uri,
                        fallbackCategory = item.category,
                        deleteOriginal = true,
                    ).collect { progress ->
                        when (progress.stage) {
                            VaultTransferStage.COMPLETED -> {
                                movedCount++
                                if (progress.originalDeleted) {
                                    originalsDeletedCount++
                                } else {
                                    pendingMediaUris.add(uri)
                                    pendingOriginals.add(
                                        PendingOriginalDeletion(
                                            uriString = item.uriString,
                                            filePath = progress.resolvedOriginalPath ?: item.filePath,
                                            displayName = progress.metadata.originalName,
                                            category = item.category,
                                        ),
                                    )
                                    if (recoverableSenderQ == null && progress.recoverableDeleteSender != null) {
                                        recoverableSenderQ = progress.recoverableDeleteSender
                                    }
                                }
                                lastCategory = SafVaultRepository.classifyCategory(
                                    progress.metadata.originalName,
                                    progress.metadata.mimeType,
                                )
                            }
                            else -> {
                                val stepLabel = if (selectedItems.size > 1) {
                                    "(${index + 1}/${selectedItems.size}) ${progress.metadata.originalName}"
                                } else {
                                    progress.metadata.originalName
                                }
                                _uiState.update {
                                    it.copy(
                                        transferState = VaultTransferUiState.Running(
                                            progress = progress.fraction,
                                            bytesProcessed = progress.bytesProcessed,
                                            totalBytes = progress.totalBytes,
                                            stage = progress.stage,
                                            label = stepLabel,
                                        ),
                                        statusMessage = null,
                                    )
                                }
                            }
                        }
                    }
                }

                val deleteSender = if (pendingMediaUris.isNotEmpty()) {
                    repository.buildMediaStoreDeleteSender(pendingMediaUris) ?: recoverableSenderQ
                } else {
                    null
                }

                val message = when {
                    movedCount == 0 -> "لم يتم نقل أي ملف."
                    originalsDeletedCount == movedCount ->
                        "تم تشفير $movedCount ملف داخل الخزنة وحذفه من الهاتف نهائياً."
                    deleteSender != null ->
                        "تم تشفير $movedCount ملف داخل الخزنة — وافق على نافذة النظام لحذف الأصل من الهاتف."
                    else ->
                        "تم تشفير $movedCount ملف بالخزنة، لكن تعذر حذف الأصل لعدم تفعيل صلاحية كل الملفات."
                }

                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        selectedCategory = it.selectedCategory ?: lastCategory,
                        statusMessage = message,
                        isErrorStatus = deleteSender == null && pendingOriginals.isNotEmpty(),
                        pendingMediaDeleteSender = deleteSender,
                        pendingDeleteCandidateUris = if (deleteSender != null) {
                            pendingMediaUris.map { u -> u.toString() }
                        } else {
                            emptyList()
                        },
                        pendingUnremovedOriginals = (it.pendingUnremovedOriginals + pendingOriginals)
                            .distinctBy { entry -> entry.uriString },
                    )
                }
                refreshEntries()
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        statusMessage = if (movedCount > 0) {
                            "تم تشفير ونقل $movedCount ملف ثم إلغاء الباقي."
                        } else {
                            "تم إلغاء نقل الملفات."
                        },
                        isErrorStatus = false,
                    )
                }
                refreshEntries()
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        statusMessage = t.message ?: "تعذر نقل وتشفير الملفات المختارة إلى الخزنة.",
                        isErrorStatus = true,
                    )
                }
                refreshEntries()
            }
        }
    }

    fun consumePendingMediaDeleteSender(deletedBySystem: Boolean) {
        viewModelScope.launch {
            val candidateUriStrings = _uiState.value.pendingDeleteCandidateUris
            if (deletedBySystem && candidateUriStrings.isNotEmpty()) {
                repository.cleanupMediaStoreAfterSystemDelete(candidateUriStrings.map(Uri::parse))
            }
            val remainingUnremoved = if (deletedBySystem) {
                _uiState.value.pendingUnremovedOriginals.filterNot { item ->
                    val goneByDelete = item.uriString in candidateUriStrings &&
                        (item.filePath == null || !File(item.filePath).exists())
                    goneByDelete
                }
            } else {
                _uiState.value.pendingUnremovedOriginals
            }

            val msg = when {
                deletedBySystem && remainingUnremoved.isEmpty() ->
                    "تم تشفير الملف وحذف النسخة الأصلية من الهاتف كلياً."
                deletedBySystem ->
                    "تم حذف الوسائط، وبقيت ${remainingUnremoved.size} ملفات تحتاج صلاحية كل الملفات لحذف أصلها."
                else ->
                    "تم التشفير في الخزنة لكن بقيت النسخة الأصلية على الهاتف — يمكنك حذفها أدناه."
            }

            _uiState.update {
                it.copy(
                    pendingMediaDeleteSender = null,
                    pendingDeleteCandidateUris = emptyList(),
                    pendingUnremovedOriginals = remainingUnremoved,
                    statusMessage = msg,
                    isErrorStatus = !deletedBySystem || remainingUnremoved.isNotEmpty(),
                )
            }
            refreshEntries()
        }
    }

    fun retryDeletePendingOriginals() {
        viewModelScope.launch {
            performPendingOriginalDeletions()
            refreshEntries()
        }
    }

    fun dismissPendingUnremovedOriginals() {
        _uiState.update { it.copy(pendingUnremovedOriginals = emptyList()) }
    }

    private suspend fun performPendingOriginalDeletions() {
        val pending = _uiState.value.pendingUnremovedOriginals
        if (pending.isEmpty()) return

        var deletedNow = 0
        val stillRemaining = mutableListOf<PendingOriginalDeletion>()
        val mediaUrisForSender = mutableListOf<Uri>()
        var recoverableSender: IntentSender? = null

        for (item in pending) {
            val deleted = repository.forceDeletePendingOriginal(item)
            if (deleted) {
                deletedNow++
            } else {
                stillRemaining.add(item)
                mediaUrisForSender.add(Uri.parse(item.uriString))
            }
        }

        val sender = if (stillRemaining.isNotEmpty() && !repository.hasAllFilesAccess()) {
            repository.buildMediaStoreDeleteSender(mediaUrisForSender) ?: recoverableSender
        } else {
            null
        }

        val msg = when {
            stillRemaining.isEmpty() && deletedNow > 0 ->
                "تم حذف جميع النسخ الأصلية ($deletedNow) من الهاتف نهائياً."
            deletedNow > 0 ->
                "تم حذف $deletedNow نسخة، وبقيت ${stillRemaining.size} ملفات تتطلب صلاحية كل الملفات."
            sender != null ->
                "وافق على نافذة النظام لإتمام حذف النسخ المتبقية من الهاتف."
            else ->
                "تعذر حذف ${stillRemaining.size} ملف من الهاتف — فعّل صلاحية كل الملفات للمتابعة."
        }

        _uiState.update {
            it.copy(
                pendingUnremovedOriginals = stillRemaining,
                pendingMediaDeleteSender = sender,
                pendingDeleteCandidateUris = if (sender != null) {
                    stillRemaining.map { entry -> entry.uriString }
                } else {
                    emptyList()
                },
                statusMessage = msg,
                isErrorStatus = stillRemaining.isNotEmpty() && sender == null,
            )
        }
    }

    // ---- External Storage Modes & Single-URI Transfer ----

    fun onSafDirectorySelected(treeUri: Uri) {
        viewModelScope.launch {
            val persisted = runCatching { repository.persistSafTreeUri(treeUri) }.getOrDefault(false)
            refreshStorageAndPermissionState(
                noticeMessage = if (persisted) {
                    "تم ربط المجلد الخارجي المشفر (.bhlock) لاستمرار ملفاتك المحمية حتى بعد حذف التطبيق."
                } else {
                    "المجلد المختار غير قابل للكتابة. تم الإبقاء على التخزين السابق."
                },
            )
            _uiState.update { it.copy(isErrorStatus = !persisted) }
            refreshEntries()
        }
    }

    fun activatePhoneDownloadsVault(autoFallbackNotice: String? = null) {
        viewModelScope.launch {
            val activated = runCatching { repository.activatePhoneDownloadsVault() }.getOrDefault(false)
            val msg = when {
                activated && !autoFallbackNotice.isNullOrBlank() -> autoFallbackNotice
                activated -> "تم تفعيل خزنة الهاتف المخفية المشفرة (.BlackholeVault)."
                else -> "فعّل صلاحية الوصول إلى كل ملفات الهاتف أولاً لاستخدام المجلد المخفي .BlackholeVault."
            }
            refreshStorageAndPermissionState(noticeMessage = msg)
            _uiState.update { it.copy(isErrorStatus = !activated) }
            refreshEntries()
        }
    }

    fun revertToInternalVaultStorage() {
        viewModelScope.launch {
            runCatching { repository.revertToInternalVaultStorage() }
            refreshStorageAndPermissionState(
                noticeMessage = "تم التحويل إلى الخزنة الداخلية المعزولة المشفرة (AES-256).",
            )
            _uiState.update { it.copy(isErrorStatus = false) }
            refreshEntries()
        }
    }

    fun moveDownloadedVideoToVault(file: File) {
        if (!file.exists()) {
            reportStatusError("ملف الفيديو لم يعد موجوداً.")
            return
        }
        moveMediaToVault(Uri.fromFile(file), VaultCategory.VIDEOS)
    }

    fun moveMediaToVault(
        sourceUri: Uri,
        fallbackCategory: VaultCategory = _uiState.value.selectedCategory ?: VaultCategory.VIDEOS,
    ) {
        if (activeTransferJob?.isActive == true) return
        if (_uiState.value.isLocked) {
            _uiState.update {
                it.copy(
                    statusMessage = "افتح الخزنة بالبصمة أولاً.",
                    isErrorStatus = true,
                )
            }
            return
        }

        activeTransferJob = viewModelScope.launch {
            try {
                repository.moveMediaToVault(
                    sourceUri = sourceUri,
                    fallbackCategory = fallbackCategory,
                    deleteOriginal = true,
                ).collect { progress ->
                    val label = progress.metadata.originalName
                    when (progress.stage) {
                        VaultTransferStage.COMPLETED -> {
                            val resolvedCategory = SafVaultRepository.classifyCategory(
                                progress.metadata.originalName,
                                progress.metadata.mimeType,
                            )
                            val deleteSender = if (!progress.originalDeleted) {
                                repository.buildMediaStoreDeleteSender(listOf(sourceUri))
                                    ?: progress.recoverableDeleteSender
                            } else {
                                null
                            }
                            val pendingEntry = if (!progress.originalDeleted) {
                                PendingOriginalDeletion(
                                    uriString = sourceUri.toString(),
                                    filePath = progress.resolvedOriginalPath,
                                    displayName = label,
                                    category = resolvedCategory,
                                )
                            } else {
                                null
                            }
                            val msg = when {
                                progress.originalDeleted ->
                                    "تم تشفير '$label' في الخزنة وحذفه من الهاتف نهائياً."
                                deleteSender != null ->
                                    "تم تشفير '$label' في الخزنة — وافق على حذف الأصل من الهاتف."
                                else ->
                                    "تم تشفير '$label' في الخزنة، لكن تعذر حذف الأصل لعدم توفر صلاحية كل الملفات."
                            }
                            _uiState.update {
                                it.copy(
                                    transferState = VaultTransferUiState.Idle,
                                    selectedCategory = it.selectedCategory ?: resolvedCategory,
                                    statusMessage = msg,
                                    isErrorStatus = !progress.originalDeleted && deleteSender == null,
                                    pendingMediaDeleteSender = deleteSender,
                                    pendingDeleteCandidateUris = if (deleteSender != null) {
                                        listOf(sourceUri.toString())
                                    } else {
                                        emptyList()
                                    },
                                    pendingUnremovedOriginals = if (pendingEntry != null) {
                                        (it.pendingUnremovedOriginals + pendingEntry)
                                            .distinctBy { entry -> entry.uriString }
                                    } else {
                                        it.pendingUnremovedOriginals
                                    },
                                )
                            }
                            refreshEntries()
                        }
                        else -> {
                            _uiState.update {
                                it.copy(
                                    transferState = VaultTransferUiState.Running(
                                        progress = progress.fraction,
                                        bytesProcessed = progress.bytesProcessed,
                                        totalBytes = progress.totalBytes,
                                        stage = progress.stage,
                                        label = label,
                                    ),
                                    statusMessage = null,
                                )
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        statusMessage = "تم إلغاء تشفير ونقل الملف إلى الخزنة.",
                        isErrorStatus = false,
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        statusMessage = t.message ?: "تعذر نقل وتشفير الملف المختار إلى الخزنة.",
                        isErrorStatus = true,
                    )
                }
            }
        }
    }

    fun openEntry(entry: VaultFileEntry) {
        if (_uiState.value.isLocked) {
            _uiState.update {
                it.copy(
                    statusMessage = "الخزنة مقفلة — تحقق بالبصمة أولاً.",
                    isErrorStatus = true,
                )
            }
            return
        }
        val localFile = entry.localFilePath?.let(::File)?.takeIf { it.exists() }
        _uiState.update {
            it.copy(
                activePlayback = VaultPlaybackSession(
                    uriString = entry.uriString,
                    localFile = localFile,
                    displayTitle = entry.displayTitle,
                    sizeBytes = entry.sizeBytes,
                    mimeType = entry.mimeType,
                    category = entry.category,
                    isEncrypted = entry.isEncrypted,
                ),
                statusMessage = null,
            )
        }
    }

    fun closePlayer() {
        VaultTempShareHelper.clearTempShareCache(getApplication())
        _uiState.update { it.copy(activePlayback = null) }
    }

    fun restoreEntry(entry: VaultFileEntry) {
        viewModelScope.launch {
            val restored = repository.restoreEntryToDevice(entry)
            _uiState.update {
                it.copy(
                    statusMessage = if (restored) {
                        "تم فك تشفير واستعادة '${entry.displayTitle}' إلى ملفات الجهاز."
                    } else {
                        "تعذرت استعادة '${entry.displayTitle}'."
                    },
                    isErrorStatus = !restored,
                )
            }
            refreshEntries()
        }
    }

    fun deleteEntry(entry: VaultFileEntry) {
        viewModelScope.launch {
            val deleted = repository.deleteEntry(entry)
            _uiState.update {
                it.copy(
                    statusMessage = if (deleted) {
                        "تم حذف '${entry.displayTitle}' نهائياً من الخزنة."
                    } else {
                        "تعذر حذف '${entry.displayTitle}'."
                    },
                    isErrorStatus = !deleted,
                )
            }
            refreshEntries()
        }
    }

    fun cancelActiveTransfer() {
        activeTransferJob?.cancel()
    }

    fun reportStatusError(message: String) {
        _uiState.update { it.copy(statusMessage = message, isErrorStatus = true) }
    }

    fun dismissStatus() {
        _uiState.update { it.copy(statusMessage = null, isErrorStatus = false) }
    }

    override fun onCleared() {
        super.onCleared()
        activeTransferJob?.cancel()
        pickerLoadJob?.cancel()
        VaultTempShareHelper.clearTempShareCache(getApplication())
    }
}
