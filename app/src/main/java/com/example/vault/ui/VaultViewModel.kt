package com.example.vault.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vault.data.DeviceMediaBrowser
import com.example.vault.data.DeviceMediaItem
import com.example.vault.data.SafVaultRepository
import com.example.vault.data.VaultCategory
import com.example.vault.data.VaultFileEntry
import com.example.vault.data.VaultStorageMode
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
    val storageLabel: String = "Private App Vault (Isolated Sandbox)",
    val entries: List<VaultFileEntry> = emptyList(),
    val availableDownloadedVideos: List<File> = emptyList(),
    val transferState: VaultTransferUiState = VaultTransferUiState.Idle,
    val statusMessage: String? = null,
    val isErrorStatus: Boolean = false,
    val activePlayback: VaultPlaybackSession? = null,
    val devicePicker: VaultDevicePickerUiState = VaultDevicePickerUiState(),
    val pendingMediaDeleteSender: IntentSender? = null,
)

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SafVaultRepository(application.applicationContext)
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
            .getOrDefault("Private App Vault (Isolated Sandbox)")
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
                .getOrDefault("Private App Vault (Isolated Sandbox)")
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
                    // If folder listing isn't permitted yet on scoped storage, fallback to MediaStore documents
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
            val pendingDeleteMediaUris = mutableListOf<Uri>()
            var lastCategory = picker.category

            try {
                for ((index, item) in selectedItems.withIndex()) {
                    val uri = Uri.parse(item.uriString)
                    var itemDeletedOriginal = false
                    repository.moveMediaToVault(
                        sourceUri = uri,
                        fallbackCategory = item.category,
                        deleteOriginal = true,
                    ).collect { progress ->
                        when (progress.stage) {
                            VaultTransferStage.COMPLETED -> {
                                movedCount++
                                itemDeletedOriginal = progress.originalDeleted
                                if (itemDeletedOriginal) {
                                    originalsDeletedCount++
                                } else {
                                    pendingDeleteMediaUris.add(uri)
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

                val deleteSender = if (pendingDeleteMediaUris.isNotEmpty()) {
                    repository.buildMediaStoreDeleteSender(pendingDeleteMediaUris)
                } else {
                    null
                }

                val message = when {
                    movedCount == 0 -> "لم يتم نقل أي ملف."
                    originalsDeletedCount == movedCount ->
                        "تم نقل $movedCount ملف إلى الخزنة وإخفاؤه من المعرض بنجاح."
                    deleteSender != null ->
                        "تم نقل $movedCount ملف إلى الخزنة — أكّد حذف النسخة الأصلية من المعرض."
                    else ->
                        "تم حفظ $movedCount ملف داخل الخزنة (فعّل صلاحية كل الملفات لحذف الأصل تلقائياً)."
                }

                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        selectedCategory = it.selectedCategory ?: lastCategory,
                        statusMessage = message,
                        isErrorStatus = false,
                        pendingMediaDeleteSender = deleteSender,
                    )
                }
                refreshEntries()
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        statusMessage = if (movedCount > 0) {
                            "تم نقل $movedCount ملف ثم إلغاء الباقي."
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
                        statusMessage = t.message ?: "تعذر نقل الملفات المختارة إلى الخزنة.",
                        isErrorStatus = true,
                    )
                }
                refreshEntries()
            }
        }
    }

    fun consumePendingMediaDeleteSender(deletedBySystem: Boolean) {
        _uiState.update {
            it.copy(
                pendingMediaDeleteSender = null,
                statusMessage = if (deletedBySystem) {
                    "تم نقل الملف وحذف النسخة الأصلية من المعرض بنجاح."
                } else {
                    it.statusMessage
                },
                isErrorStatus = false,
            )
        }
    }

    // ---- External Storage Modes & Single-URI Transfer ----

    fun onSafDirectorySelected(treeUri: Uri) {
        viewModelScope.launch {
            val persisted = runCatching { repository.persistSafTreeUri(treeUri) }.getOrDefault(false)
            refreshStorageAndPermissionState(
                noticeMessage = if (persisted) {
                    "تم ربط المجلد الخارجي (.nomedia) لحماية ملفاتك واستمرارها حتى بعد حذف التطبيق."
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
                activated -> "تم تفعيل خزنة الهاتف المحمية (.BlackholeVault)."
                else -> "يرجى منح صلاحية الوصول إلى ملفات الجهاز أولاً."
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
                noticeMessage = "تم التحويل إلى الخزنة الداخلية الخاصة بالتطبيق.",
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
                            } else {
                                null
                            }
                            val msg = when {
                                progress.originalDeleted ->
                                    "تم نقل '$label' إلى الخزنة وحمايته وإخفاؤه من المعرض."
                                deleteSender != null ->
                                    "تم نقل '$label' إلى الخزنة — أكّد حذف الأصل من المعرض."
                                else ->
                                    "تم حفظ '$label' في الخزنة (فعّل صلاحية كل الملفات لحذف الأصل تلقائياً)."
                            }
                            _uiState.update {
                                it.copy(
                                    transferState = VaultTransferUiState.Idle,
                                    selectedCategory = it.selectedCategory ?: resolvedCategory,
                                    statusMessage = msg,
                                    isErrorStatus = false,
                                    pendingMediaDeleteSender = deleteSender,
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
                        statusMessage = "تم إلغاء نقل الملف إلى الخزنة.",
                        isErrorStatus = false,
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        statusMessage = t.message ?: "تعذر نقل الملف المختار إلى الخزنة.",
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
                ),
                statusMessage = null,
            )
        }
    }

    fun closePlayer() {
        _uiState.update { it.copy(activePlayback = null) }
    }

    fun restoreEntry(entry: VaultFileEntry) {
        viewModelScope.launch {
            val restored = repository.restoreEntryToDevice(entry)
            _uiState.update {
                it.copy(
                    statusMessage = if (restored) {
                        "تمت استعادة '${entry.displayTitle}' إلى ملفات الجهاز."
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
    }
}
