package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import com.example.engine.updater.InAppInstallOutcome
import com.example.engine.updater.UpdateCheckState
import com.example.engine.updater.UpdateInstallStatusBus
import com.example.service.BlackholeDownloadService
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
import kotlinx.coroutines.withContext
import java.io.File

data class BlackholeUiState(
    val inputUrl: String = "",
    val detectedPlatform: PlatformBadge = PlatformBadge.WEB_MEDIA,
    val telemetry: DownloadTelemetry = DownloadTelemetry(),
    val videoCount: Int = 0,
    val clipboardFeedback: String? = null,
    val updateState: UpdateCheckState = UpdateCheckState.Idle,
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
    private val _showCookieVault = MutableStateFlow(false)
    private val _authWebViewUrl = MutableStateFlow<String?>(null)
    private val _cookieStatusMessage = MutableStateFlow<String?>(null)
    private val _requestInstallPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestInstallPermission: SharedFlow<Unit> = _requestInstallPermission.asSharedFlow()

    private var lastUpdateCheckElapsedMs: Long = 0L
    private var activeUpdateJob: Job? = null
    private var pendingInstallApk: File? = null
    private var hasPromptedAutoInstallForTag: String? = null

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
        cookieVault.syncedDomains,
        _showCookieVault,
        _authWebViewUrl,
    ) { update, domains, showCookies, authUrl ->
        Triple(update, domains, showCookies to authUrl)
    }

    val uiState: StateFlow<BlackholeUiState> = combine(
        baseStateFlow,
        metaStateFlow,
        _cookieStatusMessage,
    ) { base, meta, cookieMsg ->
        base.copy(
            updateState = meta.first,
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
        UpdateInstallStatusBus.onStatusCallback = { installed, error ->
            if (installed) {
                pendingInstallApk = null
                _updateState.value = UpdateCheckState.Idle
            } else if (!error.isNullOrBlank()) {
                _updateState.value = UpdateCheckState.Error(error)
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

    fun checkForUpdatesOnEntry() {
        // Resume pending in-app install immediately once user grants "Allow from this source"
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

    fun onUpdatePillTap() {
        val current = _updateState.value
        when (current) {
            is UpdateCheckState.ReadyToInstall -> {
                viewModelScope.launch { triggerApkInstall(current.apkFile) }
            }
            is UpdateCheckState.Checking, is UpdateCheckState.Downloading -> Unit
            else -> checkForUpdates(force = true)
        }
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

                    // No dialog/popup window: show Blackhole perimeter progress ring at 0% -> 100% (360°)
                    _updateState.value = UpdateCheckState.Downloading(release, 0.02f)
                    val apkFile = appUpdateManager.downloadApkInApp(
                        context = context,
                        release = release,
                    ) { progress ->
                        _updateState.value = UpdateCheckState.Downloading(release, progress)
                    }

                    if (apkFile != null) {
                        _updateState.value = UpdateCheckState.ReadyToInstall(release, apkFile)
                        // Pause briefly at 100% so the 360° perimeter ring visibly finishes a full turn
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

    companion object {
        private const val ENTRY_CHECK_COOLDOWN_MS = 15_000L
    }
}
