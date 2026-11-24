package com.example.vault.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppPreferences
import com.example.vault.data.DeviceMediaBrowser
import com.example.vault.data.DeviceMediaItem
import com.example.vault.data.PendingOriginalDeletion
import com.example.vault.data.SafVaultRepository
import com.example.vault.data.VaultBrowserDownloadEngine
import com.example.vault.data.VaultBrowserDownloadPhase
import com.example.vault.data.VaultBrowserDownloadState
import com.example.vault.data.VaultCategory
import com.example.vault.data.VaultCipher
import com.example.vault.data.VaultFileEntry
import com.example.vault.data.VaultStorageMode
import com.example.vault.browser.InterceptedMediaRequest
import com.example.vault.browser.ResolvedMediaCandidate
import com.example.vault.browser.VaultWebSnapshot
import com.example.vault.data.VaultTempShareHelper
import com.example.vault.data.VaultTransferStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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

data class PendingMediaDeleteRequest(
    val id: Long = System.nanoTime(),
    val sender: IntentSender,
    val candidateUriStrings: List<String>,
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
    val pendingDeleteRequest: PendingMediaDeleteRequest? = null,
    val pendingUnremovedOriginals: List<PendingOriginalDeletion> = emptyList(),
    val isPrivateBrowserOpen: Boolean = false,
    val browserDownloadState: VaultBrowserDownloadState = VaultBrowserDownloadState(),
)

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val appPreferences = AppPreferences.getInstance(application)
    private val vaultCipher = VaultCipher(application.applicationContext)
    private val repository = SafVaultRepository(application.applicationContext, vaultCipher)
    private val deviceBrowser = DeviceMediaBrowser(application.applicationContext)
    private val browserDownloadEngine by lazy {
        VaultBrowserDownloadEngine(application.applicationContext, repository)
    }

    val noScreenshotEnabled: StateFlow<Boolean> = appPreferences.noScreenshotEnabled

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private var activeTransferJob: Job? = null
    private var pickerLoadJob: Job? = null
    private var browserDownloadJob: Job? = null
    private val browserDownloadCancelled = AtomicBoolean(false)

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
            if (_uiState.value.hasAllFilesAccess && _uiState.value.pendingUnremovedOriginals.isNotEmpty()) {
                performPendingOriginalDeletions()
            }
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
        _uiState.update {
            it.copy(
                selectedCategory = category,
                isPrivateBrowserOpen = false,
                statusMessage = null,
            )
        }
    }

    fun openPrivateBrowser() {
        _uiState.update {
            it.copy(
                isPrivateBrowserOpen = true,
                selectedCategory = null,
                statusMessage = null,
            )
        }
    }

    fun closePrivateBrowser() {
        _uiState.update { it.copy(isPrivateBrowserOpen = false) }
    }

    fun downloadPageVideoToVault(
        pageUrl: String,
        pageTitle: String? = null,
        domVideoUrls: List<String> = emptyList(),
        sniffedVideoUrls: List<String> = emptyList(),
        sniffedAudioUrls: List<String> = emptyList(),
        snapshot: VaultWebSnapshot? = null,
        interceptedMedia: List<InterceptedMediaRequest> = emptyList(),
    ) {
        if (_uiState.value.isLocked || browserDownloadJob?.isActive == true) return
        browserDownloadCancelled.set(false)
        browserDownloadJob = viewModelScope.launch {
            try {
                val ok = browserDownloadEngine.downloadPageVideoToVault(
                    pageUrl = pageUrl,
                    pageTitle = pageTitle,
                    domVideoUrls = domVideoUrls,
                    sniffedVideoUrls = sniffedVideoUrls,
                    sniffedAudioUrls = sniffedAudioUrls,
                    snapshot = snapshot,
                    interceptedMedia = interceptedMedia,
                    isCancelled = { browserDownloadCancelled.get() },
                    onState = { dlState ->
                        _uiState.update { it.copy(browserDownloadState = dlState) }
                    },
                )
                if (ok) {
                    refreshEntries()
                    delay(4_000L)
                    if (_uiState.value.browserDownloadState.phase == VaultBrowserDownloadPhase.COMPLETED) {
                        _uiState.update { it.copy(browserDownloadState = VaultBrowserDownloadState()) }
                    }
                }
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(
                        browserDownloadState = VaultBrowserDownloadState(
                            phase = VaultBrowserDownloadPhase.IDLE,
                        ),
                    )
                }
            }
        }
    }

    fun downloadSelectedCandidate(candidate: ResolvedMediaCandidate) {
        if (_uiState.value.isLocked || browserDownloadJob?.isActive == true) return
        val currentDlState = _uiState.value.browserDownloadState
        val pageUrl = currentDlState.activePageUrl.ifBlank { candidate.stream.streamUrl }
        val pageTitle = currentDlState.title.takeIf { it.isNotBlank() } ?: candidate.stream.title
        browserDownloadCancelled.set(false)
        browserDownloadJob = viewModelScope.launch {
            try {
                val ok = browserDownloadEngine.downloadResolvedCandidateToVault(
                    candidate = candidate,
                    pageUrl = pageUrl,
                    pageTitle = pageTitle,
                    isCancelled = { browserDownloadCancelled.get() },
                    onState = { dlState ->
                        _uiState.update { it.copy(browserDownloadState = dlState) }
                    },
                )
                if (ok) {
                    refreshEntries()
                    delay(4_000L)
                    if (_uiState.value.browserDownloadState.phase == VaultBrowserDownloadPhase.COMPLETED) {
                        _uiState.update { it.copy(browserDownloadState = VaultBrowserDownloadState()) }
                    }
                }
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(
                        browserDownloadState = VaultBrowserDownloadState(
                            phase = VaultBrowserDownloadPhase.IDLE,
                        ),
                    )
                }
            }
        }
    }

    fun cancelBrowserDownload() {
        browserDownloadCancelled.set(true)
        browserDownloadJob?.cancel()
        browserDownloadJob = null
        _uiState.update {
            it.copy(
                browserDownloadState = VaultBrowserDownloadState(
                    phase = VaultBrowserDownloadPhase.IDLE,
                ),
            )
        }
    }

    fun dismissBrowserDownloadStatus() {
        if (!_uiState.value.browserDownloadState.isActive) {
            _uiState.update { it.copy(browserDownloadState = VaultBrowserDownloadState()) }
        }
    }

    fun lockVaultNow() {
        awaitingExternalSystemFlow = false
        activeTransferJob?.cancel()
        activeTransferJob = null
        pickerLoadJob?.cancel()
        pickerLoadJob = null
        browserDownloadCancelled.set(true)
        browserDownloadJob?.cancel()
        browserDownloadJob = null
        VaultTempShareHelper.clearTempShareCache(getApplication())
        _uiState.update {
            it.copy(
                isLocked = true,
                biometricVerified = false,
                selectedCategory = null,
                isPrivateBrowserOpen = false,
                browserDownloadState = VaultBrowserDownloadState(),
                entries = emptyList(),
                activePlayback = null,
                transferState = VaultTransferUiState.Idle,
                devicePicker = VaultDevicePickerUiState(),
                pendingDeleteRequest = null,
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
            val pendingMediaUris = LinkedHashSet<Uri>()
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
                                    progress.resolvedMediaUris.forEach { rawUri ->
                                        runCatching { Uri.parse(rawUri) }.getOrNull()?.let { pendingMediaUris.add(it) }
                                    }
                                    pendingOriginals.add(
                                        PendingOriginalDeletion(
                                            uriString = item.uriString,
                                            filePath = progress.resolvedOriginalPath ?: item.filePath,
                                            displayName = progress.metadata.originalName,
                                            category = item.category,
                                            resolvedMediaUris = progress.resolvedMediaUris,
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
                    repository.buildMediaStoreDeleteSender(pendingMediaUris.toList()) ?: recoverableSenderQ
                } else {
                    null
                }

                val deleteRequest = deleteSender?.let { sender ->
                    PendingMediaDeleteRequest(
                        sender = sender,
                        candidateUriStrings = pendingMediaUris.map { it.toString() },
                    )
                }

                val message = when {
                    movedCount == 0 -> "لم يتم نقل أي ملف."
                    originalsDeletedCount == movedCount ->
                        "تم تشفير $movedCount ملف داخل الخزنة وحذفه من الهاتف والمعرض نهائياً."
                    deleteRequest != null ->
                        "تم تشفير $movedCount ملف داخل الخزنة — وافق على نافذة النظام لحذف الأصل من المعرض نهائياً."
                    else ->
                        "تم تشفير $movedCount ملف بالخزنة، لكن تعذر حذف الأصل لعدم تفعيل صلاحية كل الملفات."
                }

                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        selectedCategory = it.selectedCategory ?: lastCategory,
                        statusMessage = message,
                        isErrorStatus = deleteRequest == null && pendingOriginals.isNotEmpty(),
                        pendingDeleteRequest = deleteRequest,
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
            val candidateUriStrings = _uiState.value.pendingDeleteRequest?.candidateUriStrings.orEmpty()
            if (deletedBySystem && candidateUriStrings.isNotEmpty()) {
                repository.cleanupMediaStoreAfterSystemDelete(candidateUriStrings.map(Uri::parse))
            }
            val remainingUnremoved = if (deletedBySystem) {
                _uiState.value.pendingUnremovedOriginals.filterNot { item ->
                    val matchesBatch = item.uriString in candidateUriStrings ||
                        item.resolvedMediaUris.any { it in candidateUriStrings }
                    matchesBatch && repository.isMediaOriginalGone(item.uriString, item.filePath)
                }
            } else {
                _uiState.value.pendingUnremovedOriginals
            }

            val msg = when {
                deletedBySystem && remainingUnremoved.isEmpty() ->
                    "تم تشفير الملف وحذف النسخة الأصلية من الهاتف والمعرض كلياً."
                deletedBySystem ->
                    "تم حذف الوسائط، وبقيت ${remainingUnremoved.size} ملفات تحتاج صلاحية كل الملفات لحذف أصلها."
                else ->
                    "تم التشفير في الخزنة لكن بقيت النسخة الأصلية في الهاتف — يمكنك حذفها أدناه."
            }

            _uiState.update {
                it.copy(
                    pendingDeleteRequest = null,
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
        val mediaUrisForSender = LinkedHashSet<Uri>()

        for (item in pending) {
            val deleted = repository.forceDeletePendingOriginal(item)
            if (deleted) {
                deletedNow++
            } else {
                stillRemaining.add(item)
                mediaUrisForSender.add(Uri.parse(item.uriString))
                item.resolvedMediaUris.forEach { raw ->
                    runCatching { Uri.parse(raw) }.getOrNull()?.let { mediaUrisForSender.add(it) }
                }
            }
        }

        val sender = if (stillRemaining.isNotEmpty()) {
            repository.buildMediaStoreDeleteSender(mediaUrisForSender.toList())
        } else {
            null
        }
        val deleteRequest = sender?.let {
            PendingMediaDeleteRequest(
                sender = it,
                candidateUriStrings = mediaUrisForSender.map { u -> u.toString() },
            )
        }

        val msg = when {
            stillRemaining.isEmpty() && deletedNow > 0 ->
                "تم حذف جميع النسخ الأصلية ($deletedNow) من الهاتف والمعرض نهائياً."
            deletedNow > 0 ->
                "تم حذف $deletedNow نسخة، وبقيت ${stillRemaining.size} ملفات تتطلب صلاحية كل الملفات."
            deleteRequest != null ->
                "وافق على نافذة النظام لإتمام حذف النسخ المتبقية من المعرض."
            else ->
                "تعذر حذف ${stillRemaining.size} ملف من الهاتف — فعّل صلاحية كل الملفات للمتابعة."
        }

        _uiState.update {
            it.copy(
                pendingUnremovedOriginals = stillRemaining,
                pendingDeleteRequest = deleteRequest,
                statusMessage = msg,
                isErrorStatus = stillRemaining.isNotEmpty() && deleteRequest == null,
            )
        }
    }

    // ---- Internal Vault Storage Mode ----

    fun revertToInternalVaultStorage() {
        viewModelScope.launch {
            runCatching { repository.revertToInternalVaultStorage() }
            refreshStorageAndPermissionState(
                noticeMessage = "تم تفعيل الخزنة الداخلية المعزولة المشفرة (AES-256).",
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
                            val candidateUris = (listOf(sourceUri) + progress.resolvedMediaUris.mapNotNull {
                                runCatching { Uri.parse(it) }.getOrNull()
                            }).distinct()
                            val deleteSender = if (!progress.originalDeleted) {
                                repository.buildMediaStoreDeleteSender(candidateUris)
                                    ?: progress.recoverableDeleteSender
                            } else {
                                null
                            }
                            val deleteRequest = deleteSender?.let { sender ->
                                PendingMediaDeleteRequest(
                                    sender = sender,
                                    candidateUriStrings = candidateUris.map { it.toString() },
                                )
                            }
                            val pendingEntry = if (!progress.originalDeleted) {
                                PendingOriginalDeletion(
                                    uriString = sourceUri.toString(),
                                    filePath = progress.resolvedOriginalPath,
                                    displayName = label,
                                    category = resolvedCategory,
                                    resolvedMediaUris = progress.resolvedMediaUris,
                                )
                            } else {
                                null
                            }
                            val msg = when {
                                progress.originalDeleted ->
                                    "تم تشفير '$label' في الخزنة وحذفه من الهاتف والمعرض نهائياً."
                                deleteRequest != null ->
                                    "تم تشفير '$label' في الخزنة — وافق على حذف الأصل من المعرض."
                                else ->
                                    "تم تشفير '$label' في الخزنة، لكن تعذر حذف الأصل لعدم توفر صلاحية كل الملفات."
                            }
                            _uiState.update {
                                it.copy(
                                    transferState = VaultTransferUiState.Idle,
                                    selectedCategory = it.selectedCategory ?: resolvedCategory,
                                    statusMessage = msg,
                                    isErrorStatus = !progress.originalDeleted && deleteRequest == null,
                                    pendingDeleteRequest = deleteRequest,
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
        restoreEntries(listOf(entry))
    }

    fun deleteEntry(entry: VaultFileEntry) {
        deleteEntries(listOf(entry))
    }

    fun restoreEntries(entries: List<VaultFileEntry>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            var restoredCount = 0
            for (entry in entries) {
                if (repository.restoreEntryToDevice(entry)) {
                    restoredCount++
                }
            }
            _uiState.update {
                it.copy(
                    statusMessage = when {
                        restoredCount == entries.size && entries.size == 1 ->
                            "تم فك تشفير واستعادة '${entries.first().displayTitle}' إلى ملفات الجهاز."
                        restoredCount > 0 ->
                            "تم فك تشفير واستعادة $restoredCount عنصر إلى ملفات الجهاز."
                        else ->
                            "تعذرت استعادة العناصر المحددة."
                    },
                    isErrorStatus = restoredCount == 0,
                )
            }
            refreshEntries()
        }
    }

    fun deleteEntries(entries: List<VaultFileEntry>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            var deletedCount = 0
            for (entry in entries) {
                if (repository.deleteEntry(entry)) {
                    deletedCount++
                }
            }
            _uiState.update {
                it.copy(
                    statusMessage = when {
                        deletedCount == entries.size && entries.size == 1 ->
                            "تم حذف '${entries.first().displayTitle}' نهائياً من الخزنة."
                        deletedCount > 0 ->
                            "تم حذف $deletedCount عنصر نهائياً من الخزنة."
                        else ->
                            "تعذر حذف العناصر المحددة."
                    },
                    isErrorStatus = deletedCount == 0,
                )
            }
            refreshEntries()
        }
    }

    fun restoreActivePlayback() {
        val active = _uiState.value.activePlayback ?: return
        val matched = _uiState.value.entries.firstOrNull { it.uriString == active.uriString } ?: return
        closePlayer()
        restoreEntry(matched)
    }

    fun deleteActivePlayback() {
        val active = _uiState.value.activePlayback ?: return
        val matched = _uiState.value.entries.firstOrNull { it.uriString == active.uriString } ?: return
        closePlayer()
        deleteEntry(matched)
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

    fun setNoScreenshotEnabled(enabled: Boolean) {
        appPreferences.setNoScreenshotEnabled(enabled)
    }

    override fun onCleared() {
        super.onCleared()
        activeTransferJob?.cancel()
        pickerLoadJob?.cancel()
        browserDownloadCancelled.set(true)
        browserDownloadJob?.cancel()
        VaultTempShareHelper.clearTempShareCache(getApplication())
    }
}
