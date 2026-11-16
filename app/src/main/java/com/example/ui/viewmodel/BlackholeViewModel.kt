package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.BlackholeDatabase
import com.example.data.local.DownloadedVideoEntity
import com.example.data.repository.VideoVaultRepository
import com.example.engine.clipboard.ClipboardDetector
import com.example.engine.model.DownloadPhase
import com.example.engine.model.DownloadTelemetry
import com.example.engine.model.PlatformBadge
import com.example.engine.service.DownloadStateCoordinator
import com.example.engine.updater.AppUpdateManager
import com.example.engine.updater.UpdateCheckState
import com.example.service.BlackholeDownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BlackholeUiState(
    val inputUrl: String = "",
    val detectedPlatform: PlatformBadge = PlatformBadge.WEB_MEDIA,
    val telemetry: DownloadTelemetry = DownloadTelemetry(),
    val videoCount: Int = 0,
    val clipboardFeedback: String? = null,
    val updateState: UpdateCheckState = UpdateCheckState.Idle,
    val showUpdateDialog: Boolean = false,
)

class BlackholeViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val clipboardDetector = ClipboardDetector(context)
    private val appUpdateManager = AppUpdateManager()
    private val repository = VideoVaultRepository(
        context = context,
        videoDao = BlackholeDatabase.getInstance(context).downloadedVideoDao(),
    )

    private val _inputUrl = MutableStateFlow("")
    private val _clipboardFeedback = MutableStateFlow<String?>(null)
    private val _updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    private val _showUpdateDialog = MutableStateFlow(false)

    val videos: StateFlow<List<DownloadedVideoEntity>> = repository.allVideos
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val baseStateFlow = combine(
        _inputUrl,
        DownloadStateCoordinator.telemetry,
        videos,
        _clipboardFeedback,
    ) { url, telemetry, videoList, feedback ->
        BlackholeUiState(
            inputUrl = url,
            detectedPlatform = PlatformBadge.fromUrl(url),
            telemetry = telemetry,
            videoCount = videoList.size,
            clipboardFeedback = feedback,
        )
    }

    val uiState: StateFlow<BlackholeUiState> = combine(
        baseStateFlow,
        _updateState,
        _showUpdateDialog,
    ) { base, update, showDialog ->
        base.copy(
            updateState = update,
            showUpdateDialog = showDialog,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BlackholeUiState(),
    )

    init {
        refreshClipboardOnLaunch()
        checkForUpdates(silent = true)
    }

    fun checkForUpdates(silent: Boolean = false) {
        if (_updateState.value is UpdateCheckState.Checking) return
        viewModelScope.launch {
            _updateState.value = UpdateCheckState.Checking
            val result = appUpdateManager.checkLatestRelease()
            _updateState.value = result
            when (result) {
                is UpdateCheckState.UpdateAvailable -> {
                    _showUpdateDialog.value = true
                }
                is UpdateCheckState.UpToDate -> {
                    if (!silent) {
                        _showUpdateDialog.value = true
                    }
                }
                is UpdateCheckState.Error -> {
                    if (!silent) {
                        _showUpdateDialog.value = true
                    }
                }
                else -> Unit
            }
        }
    }

    fun openUpdateDialog() {
        val current = _updateState.value
        if (current is UpdateCheckState.UpdateAvailable) {
            _showUpdateDialog.value = true
        } else {
            checkForUpdates(silent = false)
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    fun refreshClipboardOnLaunch() {
        val currentPhase = DownloadStateCoordinator.telemetry.value.phase
        if (currentPhase == DownloadPhase.ABSORBING || currentPhase == DownloadPhase.DOWNLOADING) {
            return
        }
        val detected = clipboardDetector.readClipboardUrl()
        if (detected != null) {
            _inputUrl.value = detected.url
            DownloadStateCoordinator.armClipboardUrl(detected.url, detected.platform)
            _clipboardFeedback.value = null
        }
    }

    fun pasteFromClipboard() {
        val detected = clipboardDetector.readClipboardUrl()
        if (detected != null) {
            _inputUrl.value = detected.url
            DownloadStateCoordinator.armClipboardUrl(detected.url, detected.platform)
            _clipboardFeedback.value = null
        } else {
            _clipboardFeedback.value = "No valid video URL found on clipboard"
        }
    }

    fun onUrlEdited(newUrl: String) {
        _inputUrl.value = newUrl
        _clipboardFeedback.value = null
        val trimmed = newUrl.trim()
        if (clipboardDetector.isSupportedVideoCandidate(trimmed)) {
            DownloadStateCoordinator.armClipboardUrl(trimmed, PlatformBadge.fromUrl(trimmed))
        } else {
            DownloadStateCoordinator.armClipboardUrl("", PlatformBadge.WEB_MEDIA)
        }
    }

    fun clearUrl() {
        _inputUrl.value = ""
        _clipboardFeedback.value = null
        DownloadStateCoordinator.resetToArmedOrIdle("", PlatformBadge.WEB_MEDIA)
    }

    fun dismissFeedback() {
        _clipboardFeedback.value = null
    }

    fun triggerGravityPull() {
        val activePhase = DownloadStateCoordinator.telemetry.value.phase
        if (activePhase == DownloadPhase.ABSORBING || activePhase == DownloadPhase.DOWNLOADING) {
            return
        }

        // Read fresh clipboard link directly on tap (falling back to any armed URL)
        val candidateRaw = clipboardDetector.readClipboardUrl()?.url
            ?: _inputUrl.value.trim()
        val validUrl = clipboardDetector.extractFirstValidUrl(candidateRaw)
        if (validUrl == null) {
            _clipboardFeedback.value = "Copy a video URL to clipboard first, then tap the Blackhole"
            DownloadStateCoordinator.setError(
                sourceUrl = candidateRaw,
                platform = PlatformBadge.WEB_MEDIA,
                message = "No valid HTTP/HTTPS video link detected on clipboard.",
            )
            return
        }

        _inputUrl.value = validUrl
        _clipboardFeedback.value = null
        val platform = PlatformBadge.fromUrl(validUrl)
        DownloadStateCoordinator.setAbsorbing(validUrl, platform)
        BlackholeDownloadService.startGravityPull(context, validUrl)
    }

    fun abortGravityPull() {
        BlackholeDownloadService.cancelGravityPull(context)
    }

    fun deleteVideo(video: DownloadedVideoEntity) {
        viewModelScope.launch {
            repository.deleteVideo(video)
        }
    }
}
