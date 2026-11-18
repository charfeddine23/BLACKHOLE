package com.example.ui.viewmodel

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.BlackholeDatabase
import com.example.data.local.DownloadedVideoEntity
import com.example.data.repository.VideoVaultRepository
import com.example.engine.clipboard.ClipboardDetector
import com.example.engine.extractor.MultiPlatformExtractorRouter
import com.example.engine.model.DownloadPhase
import com.example.engine.model.DownloadStatus
import com.example.engine.model.DownloadTaskState
import com.example.engine.model.DownloadTelemetry
import com.example.engine.model.ExtractedMediaInfo
import com.example.engine.model.MediaExtractionResult
import com.example.engine.model.PlatformBadge
import com.example.engine.model.VideoQualityOption
import com.example.engine.service.DownloadProgressManager
import com.example.engine.service.DownloadStateCoordinator
import com.example.engine.updater.AppUpdateManager
import com.example.engine.updater.InAppInstallOutcome
import com.example.engine.updater.UpdateCheckState
import com.example.engine.updater.UpdateInstallStatusBus
import com.example.worker.VideoDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class BlackholeUiState(
    val inputUrl: String = "",
    val detectedPlatform: PlatformBadge = PlatformBadge.WEB_MEDIA,
    val isExtracting: Boolean = false,
    val extractionError: String? = null,
    val extractedMediaForSheet: ExtractedMediaInfo? = null,
    val downloadTasks: List<DownloadTaskState> = emptyList(),
    val telemetry: DownloadTelemetry = DownloadTelemetry(),
    val videoCount: Int = 0,
    val updateState: UpdateCheckState = UpdateCheckState.Idle,
)

class BlackholeViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val clipboardDetector = ClipboardDetector(context)
    private val extractorRouter = MultiPlatformExtractorRouter(context)
    private val appUpdateManager = AppUpdateManager()
    private val repository = VideoVaultRepository(
        context = context,
        videoDao = BlackholeDatabase.getInstance(context).downloadedVideoDao(),
    )

    private val _inputUrl = MutableStateFlow("")
    private val _isExtracting = MutableStateFlow(false)
    private val _extractionError = MutableStateFlow<String?>(null)
    private val _extractedMediaForSheet = MutableStateFlow<ExtractedMediaInfo?>(null)
    private val _updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    private val _requestInstallPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestInstallPermission: SharedFlow<Unit> = _requestInstallPermission.asSharedFlow()

    private var lastUpdateCheckElapsedMs: Long = 0L
    private var activeUpdateJob: Job? = null
    private var activeExtractJob: Job? = null
    private var pendingInstallApk: File? = null
    private var hasPromptedAutoInstallForTag: String? = null

    val videos: StateFlow<List<DownloadedVideoEntity>> = repository.allVideos
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val coreInputsFlow = combine(
        _inputUrl,
        _isExtracting,
        _extractionError,
        _extractedMediaForSheet,
    ) { url, extracting, error, media ->
        CoreUiInputs(url, extracting, error, media)
    }

    val uiState: StateFlow<BlackholeUiState> = combine(
        coreInputsFlow,
        DownloadProgressManager.tasks,
        DownloadStateCoordinator.telemetry,
        videos,
        _updateState,
    ) { core, tasks, baseTelemetry, videoList, update ->
        val syncedTelemetry = deriveBlackholeTelemetry(
            core = core,
            tasks = tasks,
            fallbackTelemetry = baseTelemetry,
        )
        BlackholeUiState(
            inputUrl = core.inputUrl,
            detectedPlatform = PlatformBadge.fromUrl(core.inputUrl),
            isExtracting = core.isExtracting,
            extractionError = core.extractionError,
            extractedMediaForSheet = core.extractedMediaForSheet,
            downloadTasks = tasks,
            telemetry = syncedTelemetry,
            videoCount = videoList.size,
            updateState = update,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BlackholeUiState(),
    )

    init {
        UpdateInstallStatusBus.onStatusCallback = { installed, error ->
            if (installed) {
                pendingInstallApk = null
                _updateState.value = UpdateCheckState.Idle
            } else if (!error.isNullOrBlank()) {
                _updateState.value = UpdateCheckState.Error(error)
            }
        }
        viewModelScope.launch {
            repository.allVideos.collect { persisted ->
                DownloadProgressManager.syncPersistedCompletedVideos(persisted)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            appUpdateManager.cleanupInstalledUpdates(context)
        }
        refreshClipboardOnLaunch()
        checkForUpdates(force = true)
    }

    override fun onCleared() {
        super.onCleared()
        UpdateInstallStatusBus.onStatusCallback = null
    }

    fun onUrlChanged(newUrl: String) {
        _inputUrl.value = newUrl
        _extractionError.value = null
        val platform = PlatformBadge.fromUrl(newUrl)
        if (!_isExtracting.value) {
            DownloadStateCoordinator.armClipboardUrl(newUrl.trim(), platform)
        }
    }

    fun pasteFromClipboard(autoExtract: Boolean = false) {
        val detected = clipboardDetector.readClipboardUrl()
        if (detected != null) {
            _inputUrl.value = detected.url
            _extractionError.value = null
            DownloadStateCoordinator.armClipboardUrl(detected.url, detected.platform)
            if (autoExtract) {
                extractMediaInfo(detected.url)
            }
        } else {
            _extractionError.value = "No valid video URL found in clipboard."
        }
    }

    fun onSharedUrlReceived(sharedText: String) {
        val extractedUrl = clipboardDetector.extractFirstValidUrl(sharedText) ?: return
        _inputUrl.value = extractedUrl
        _extractionError.value = null
        DownloadStateCoordinator.armClipboardUrl(extractedUrl, PlatformBadge.fromUrl(extractedUrl))
        extractMediaInfo(extractedUrl)
    }

    fun extractMediaInfo(urlOverride: String? = null) {
        val candidateRaw = (urlOverride ?: _inputUrl.value).trim().ifBlank {
            clipboardDetector.readClipboardUrl()?.url.orEmpty()
        }
        val validUrl = clipboardDetector.extractFirstValidUrl(candidateRaw)
        if (validUrl == null) {
            val msg = "Enter or paste a valid YouTube, Instagram, Facebook, or video URL."
            _extractionError.value = msg
            DownloadStateCoordinator.setError(candidateRaw, PlatformBadge.WEB_MEDIA, msg)
            return
        }

        _inputUrl.value = validUrl
        _extractionError.value = null
        _isExtracting.value = true
        val platform = PlatformBadge.fromUrl(validUrl)
        DownloadStateCoordinator.setAbsorbing(validUrl, platform)

        activeExtractJob?.cancel()
        activeExtractJob = viewModelScope.launch {
            when (val result = extractorRouter.extractMedia(validUrl)) {
                is MediaExtractionResult.Success -> {
                    _isExtracting.value = false
                    _extractedMediaForSheet.value = result.media
                    DownloadStateCoordinator.armClipboardUrl(result.media.canonicalUrl, result.media.platform)
                }
                is MediaExtractionResult.NeedsAuth -> {
                    _isExtracting.value = false
                    _extractionError.value = result.reason
                    DownloadStateCoordinator.setNeedsAuth(validUrl, platform, result.reason, result.url)
                }
                is MediaExtractionResult.Error -> {
                    _isExtracting.value = false
                    _extractionError.value = result.message
                    DownloadStateCoordinator.setError(validUrl, platform, result.message)
                }
            }
        }
    }

    fun dismissQualitySheet() {
        _extractedMediaForSheet.value = null
    }

    fun startDownload(media: ExtractedMediaInfo, option: VideoQualityOption) {
        _extractedMediaForSheet.value = null
        _extractionError.value = null
        VideoDownloadWorker.enqueueDownload(context, media, option)
    }

    fun cancelTask(taskId: String) {
        VideoDownloadWorker.cancelDownload(context, taskId)
    }

    fun retryTask(task: DownloadTaskState) {
        VideoDownloadWorker.retryDownload(context, task)
    }

    fun deleteTask(task: DownloadTaskState) {
        DownloadProgressManager.removeTask(task.taskId)
        val savedId = task.savedVideoId
        if (savedId != null) {
            viewModelScope.launch {
                repository.getVideoById(savedId)?.let { entity ->
                    repository.deleteVideo(entity)
                }
            }
        }
    }

    fun deleteVideo(video: DownloadedVideoEntity) {
        DownloadProgressManager.removeBySavedVideoId(video.id)
        viewModelScope.launch {
            repository.deleteVideo(video)
        }
    }

    fun refreshClipboardOnLaunch() {
        if (_isExtracting.value) return
        val detected = clipboardDetector.readClipboardUrl()
        if (detected != null && _inputUrl.value.isBlank()) {
            _inputUrl.value = detected.url
            DownloadStateCoordinator.armClipboardUrl(detected.url, detected.platform)
        }
    }

    fun checkForUpdatesOnEntry() {
        val waitingApk = pendingInstallApk
        if (waitingApk != null && waitingApk.exists() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls())
        ) {
            pendingInstallApk = null
            viewModelScope.launch { triggerApkInstall(waitingApk) }
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (lastUpdateCheckElapsedMs != 0L && now - lastUpdateCheckElapsedMs < ENTRY_CHECK_COOLDOWN_MS) {
            return
        }
        checkForUpdates(force = false)
    }

    fun checkForUpdates(force: Boolean = false) {
        val current = _updateState.value
        if (current is UpdateCheckState.Checking ||
            current is UpdateCheckState.Downloading ||
            (!force && current is UpdateCheckState.ReadyToInstall)
        ) {
            return
        }
        if (activeUpdateJob?.isActive == true) return

        lastUpdateCheckElapsedMs = SystemClock.elapsedRealtime()
        activeUpdateJob = viewModelScope.launch {
            _updateState.value = UpdateCheckState.Checking
            val result = appUpdateManager.checkLatestRelease()
            when (result) {
                is UpdateCheckState.UpdateAvailable -> {
                    val release = result.release
                    if (release.apkDownloadUrl.isNullOrBlank()) {
                        _updateState.value = UpdateCheckState.Error("Release APK asset missing.")
                        return@launch
                    }

                    _updateState.value = UpdateCheckState.Downloading(release, 0.02f)
                    val apkFile = appUpdateManager.downloadApkInApp(
                        context = context,
                        release = release,
                    ) { progress ->
                        _updateState.value = UpdateCheckState.Downloading(release, progress)
                    }

                    if (apkFile != null) {
                        _updateState.value = UpdateCheckState.ReadyToInstall(release, apkFile)
                        delay(450L)
                        if (hasPromptedAutoInstallForTag != release.tagName || force) {
                            hasPromptedAutoInstallForTag = release.tagName
                            triggerApkInstall(apkFile)
                        }
                    } else {
                        _updateState.value = UpdateCheckState.Error("Failed to download update APK.")
                    }
                }
                is UpdateCheckState.UpToDate, is UpdateCheckState.Error -> {
                    _updateState.value = result
                }
                else -> _updateState.value = result
            }
        }
    }

    private suspend fun triggerApkInstall(apkFile: File) {
        when (val outcome = appUpdateManager.installApkInApp(context, apkFile)) {
            InAppInstallOutcome.SessionCommitted -> Unit
            InAppInstallOutcome.NeedsInstallPermission -> {
                pendingInstallApk = apkFile
                _requestInstallPermission.tryEmit(Unit)
            }
            is InAppInstallOutcome.Failed -> {
                _updateState.value = UpdateCheckState.Error(outcome.message)
            }
        }
    }

    private fun deriveBlackholeTelemetry(
        core: CoreUiInputs,
        tasks: List<DownloadTaskState>,
        fallbackTelemetry: DownloadTelemetry,
    ): DownloadTelemetry {
        if (core.isExtracting) {
            return DownloadTelemetry(
                phase = DownloadPhase.ABSORBING,
                sourceUrl = core.inputUrl,
                platform = PlatformBadge.fromUrl(core.inputUrl),
                title = "Extracting streams...",
            )
        }

        val activeTask = tasks.firstOrNull { it.isActive }
        if (activeTask != null) {
            val phase = when (activeTask.status) {
                DownloadStatus.Extracting -> DownloadPhase.ABSORBING
                DownloadStatus.Downloading -> DownloadPhase.DOWNLOADING
                DownloadStatus.Merging -> DownloadPhase.MERGING
                else -> DownloadPhase.IDLE
            }
            return DownloadTelemetry(
                phase = phase,
                sourceUrl = activeTask.sourceUrl,
                title = activeTask.title,
                platform = activeTask.platform,
                qualityLabel = activeTask.qualityLabel,
                bytesDownloaded = activeTask.downloadedBytes,
                totalBytes = activeTask.totalBytes,
                progressFraction = activeTask.progress,
                speedBytesPerSec = activeTask.speedBytesPerSec,
            )
        }

        if (!core.extractionError.isNullOrBlank()) {
            return DownloadTelemetry(
                phase = DownloadPhase.ERROR,
                sourceUrl = core.inputUrl,
                platform = PlatformBadge.fromUrl(core.inputUrl),
                errorMessage = core.extractionError,
            )
        }

        return fallbackTelemetry
    }

    private data class CoreUiInputs(
        val inputUrl: String,
        val isExtracting: Boolean,
        val extractionError: String?,
        val extractedMediaForSheet: ExtractedMediaInfo?,
    )

    companion object {
        private const val ENTRY_CHECK_COOLDOWN_MS = 15_000L
    }
}
