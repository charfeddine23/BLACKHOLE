package com.example.vault.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

data class VaultUiState(
    val isLocked: Boolean = true,
    val biometricVerified: Boolean = false,
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
)

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SafVaultRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private var activeTransferJob: Job? = null

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
            }
        }
    }

    private suspend fun refreshStorageAndPermissionState(
        noticeMessage: String? = _uiState.value.statusMessage,
        unlock: Boolean = false,
    ) {
        val hasFilesAccess = repository.hasAllFilesAccess()
        val mode = runCatching { repository.getActiveStorageMode() }.getOrDefault(VaultStorageMode.INTERNAL)
        val label = runCatching { repository.getStorageDisplayLabel() }
            .getOrDefault("Private App Vault (Isolated Sandbox)")
        _uiState.update {
            it.copy(
                isLocked = if (unlock) false else it.isLocked,
                biometricVerified = if (unlock) true else it.biometricVerified,
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
        _uiState.update {
            it.copy(
                isLocked = true,
                biometricVerified = false,
                selectedCategory = null,
                entries = emptyList(),
                activePlayback = null,
                transferState = VaultTransferUiState.Idle,
            )
        }
    }

    fun refreshEntries() {
        viewModelScope.launch {
            val items = runCatching { repository.listVaultEntries() }.getOrDefault(emptyList())
            val downloadedVideos = runCatching { repository.listBlackholeDownloadedVideos() }.getOrDefault(emptyList())
            val hasFilesAccess = repository.hasAllFilesAccess()
            val mode = runCatching { repository.getActiveStorageMode() }.getOrDefault(VaultStorageMode.INTERNAL)
            val label = runCatching { repository.getStorageDisplayLabel() }
                .getOrDefault("Private App Vault (Isolated Sandbox)")
            _uiState.update {
                it.copy(
                    entries = items,
                    availableDownloadedVideos = downloadedVideos,
                    hasAllFilesAccess = hasFilesAccess,
                    isExternalSafActive = mode != VaultStorageMode.INTERNAL,
                    storageMode = mode,
                    storageLabel = label,
                )
            }
        }
    }

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
                            val msg = if (progress.originalDeleted) {
                                "تم نقل '$label' إلى الخزنة وحمايته وإخفاؤه من المعرض."
                            } else {
                                "تم حفظ '$label' في الخزنة (امنح صلاحية ملفات الجهاز لنقل وحذف الأصل تلقائياً)."
                            }
                            _uiState.update {
                                it.copy(
                                    transferState = VaultTransferUiState.Idle,
                                    selectedCategory = it.selectedCategory ?: resolvedCategory,
                                    statusMessage = msg,
                                    isErrorStatus = false,
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
    }
}
