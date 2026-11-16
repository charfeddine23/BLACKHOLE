package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.BlackholeDatabase
import com.example.data.local.DownloadedVideoEntity
import com.example.data.repository.VideoVaultRepository
import com.example.engine.clipboard.ClipboardDetector
import com.example.engine.cookies.CookieVaultManager
import com.example.engine.model.DownloadPhase
import com.example.engine.model.DownloadTelemetry
import com.example.engine.model.PlatformBadge
import com.example.engine.service.DownloadStateCoordinator
import com.example.engine.updater.AppUpdateManager
import com.example.engine.updater.UpdateCheckState
import com.example.service.BlackholeDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BlackholeUiState(
    val inputUrl: String = "",
    val detectedPlatform: PlatformBadge = PlatformBadge.WEB_MEDIA,
    val telemetry: DownloadTelemetry = DownloadTelemetry(),
    val videoCount: Int = 0,
    val clipboardFeedback: String? = null,
    val updateState: UpdateCheckState = UpdateCheckState.Idle,
    val showUpdateDialog: Boolean = false,
    val syncedDomains: List<String> = emptyList(),
    val showCookieVault: Boolean = false,
    val authWebViewUrl: String? = null,
    val cookieStatusMessage: String? = null,
)

class BlackholeViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val clipboardDetector = ClipboardDetector(context)
    private val cookieVault = CookieVaultManager.getInstance(context)
    private val appUpdateManager = AppUpdateManager()
    private val repository = VideoVaultRepository(
        context = context,
        videoDao = BlackholeDatabase.getInstance(context).downloadedVideoDao(),
    )

    private val _inputUrl = MutableStateFlow("")
    private val _clipboardFeedback = MutableStateFlow<String?>(null)
    private val _updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    private val _showUpdateDialog = MutableStateFlow(false)
    private val _showCookieVault = MutableStateFlow(false)
    private val _authWebViewUrl = MutableStateFlow<String?>(null)
    private val _cookieStatusMessage = MutableStateFlow<String?>(null)

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

    private val metaStateFlow = combine(
        _updateState,
        _showUpdateDialog,
        cookieVault.syncedDomains,
        _showCookieVault,
        _authWebViewUrl,
    ) { update, showUpdate, domains, showCookies, authUrl ->
        Triple(update to showUpdate, domains, showCookies to authUrl)
    }

    val uiState: StateFlow<BlackholeUiState> = combine(
        baseStateFlow,
        metaStateFlow,
        _cookieStatusMessage,
    ) { base, meta, cookieMsg ->
        base.copy(
            updateState = meta.first.first,
            showUpdateDialog = meta.first.second,
            syncedDomains = meta.second,
            showCookieVault = meta.third.first,
            authWebViewUrl = meta.third.second,
            cookieStatusMessage = cookieMsg,
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
                is UpdateCheckState.UpdateAvailable -> _showUpdateDialog.value = true
                is UpdateCheckState.UpToDate, is UpdateCheckState.Error -> {
                    if (!silent) _showUpdateDialog.value = true
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

    fun openCookieVault(authUrl: String? = null) {
        _cookieStatusMessage.value = null
        _authWebViewUrl.value = authUrl
        _showCookieVault.value = true
    }

    fun dismissCookieVault() {
        _showCookieVault.value = false
        _authWebViewUrl.value = null
    }

    fun importCookiesFromUri(uri: Uri) {
        viewModelScope.launch {
            val importedCount = withContext(Dispatchers.IO) {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    it.readText()
                }.orEmpty()
                cookieVault.importNetscapeCookiesTxt(content)
            }
            _cookieStatusMessage.value = if (importedCount > 0) {
                "Imported $importedCount session cookies cleanly."
            } else {
                "No valid Netscape/JSON cookies found in selected file."
            }
        }
    }

    fun syncWebViewSession(url: String, retriggerPull: Boolean) {
        viewModelScope.launch {
            val count = cookieVault.syncFromWebView(url)
            _cookieStatusMessage.value = if (count > 0) {
                "Synced $count cookies from active WebView session."
            } else {
                "Session synced."
            }
            _showCookieVault.value = false
            val targetToPull = _authWebViewUrl.value ?: _inputUrl.value
            _authWebViewUrl.value = null
            if (retriggerPull && targetToPull.isNotBlank()) {
                _inputUrl.value = targetToPull
                val platform = PlatformBadge.fromUrl(targetToPull)
                DownloadStateCoordinator.setAbsorbing(targetToPull, platform)
                BlackholeDownloadService.startGravityPull(context, targetToPull)
            }
        }
    }

    fun clearAllCookies() {
        viewModelScope.launch {
            cookieVault.clearAllCookies()
            _cookieStatusMessage.value = "All stored session cookies cleared."
        }
    }

    fun refreshClipboardOnLaunch() {
        val currentPhase = DownloadStateCoordinator.telemetry.value.phase
        if (currentPhase == DownloadPhase.ABSORBING ||
            currentPhase == DownloadPhase.DOWNLOADING ||
            currentPhase == DownloadPhase.MERGING
        ) {
            return
        }
        val detected = clipboardDetector.readClipboardUrl()
        if (detected != null) {
            _inputUrl.value = detected.url
            DownloadStateCoordinator.armClipboardUrl(detected.url, detected.platform)
            _clipboardFeedback.value = null
        }
    }

    fun triggerGravityPull() {
        val activePhase = DownloadStateCoordinator.telemetry.value.phase
        if (activePhase == DownloadPhase.ABSORBING ||
            activePhase == DownloadPhase.DOWNLOADING ||
            activePhase == DownloadPhase.MERGING
        ) {
            return
        }

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
