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
    val updateState: UpdateCheckState = UpdateCheckState.Idle,
    val syncedDomains: List<String> = emptyList(),
    val vaultStatusMessage: String? = null,
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
    private val _updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    private val _vaultStatusMessage = MutableStateFlow<String?>(null)
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

    private val coreSnapshotFlow = combine(
        _inputUrl,
        DownloadStateCoordinator.telemetry,
        videos,
        _updateState,
    ) { url, telemetry, videoList, update ->
        BlackholeUiState(
            inputUrl = url,
            detectedPlatform = PlatformBadge.fromUrl(url),
            telemetry = telemetry,
            videoCount = videoList.size,
            updateState = update,
        )
    }

    val uiState: StateFlow<BlackholeUiState> = combine(
        coreSnapshotFlow,
        cookieVault.syncedDomains,
        _vaultStatusMessage,
    ) { base, domains, vaultMsg ->
        base.copy(
            syncedDomains = domains,
            vaultStatusMessage = vaultMsg,
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

                    // Silent perimeter-ring download (0% -> 100%, full 360° turn) with zero popup windows
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
            DownloadStateCoordinator.setError(
                sourceUrl = candidateRaw,
                platform = PlatformBadge.WEB_MEDIA,
                message = "No valid HTTP/HTTPS video link detected on clipboard.",
            )
            return
        }

        _inputUrl.value = validUrl
        val platform = PlatformBadge.fromUrl(validUrl)
        DownloadStateCoordinator.setAbsorbing(validUrl, platform)
        BlackholeDownloadService.startGravityPull(context, validUrl)
    }

    fun onSharedUrlReceived(rawText: String) {
        val validUrl = clipboardDetector.extractFirstValidUrl(rawText.trim()) ?: return
        _inputUrl.value = validUrl
        val platform = PlatformBadge.fromUrl(validUrl)
        DownloadStateCoordinator.armClipboardUrl(validUrl, platform)
        val activePhase = DownloadStateCoordinator.telemetry.value.phase
        if (activePhase != DownloadPhase.ABSORBING &&
            activePhase != DownloadPhase.DOWNLOADING &&
            activePhase != DownloadPhase.MERGING
        ) {
            DownloadStateCoordinator.setAbsorbing(validUrl, platform)
            BlackholeDownloadService.startGravityPull(context, validUrl)
        }
    }

    fun triggerGravityPullForUrl(rawUrl: String) {
        val activePhase = DownloadStateCoordinator.telemetry.value.phase
        if (activePhase == DownloadPhase.ABSORBING ||
            activePhase == DownloadPhase.DOWNLOADING ||
            activePhase == DownloadPhase.MERGING
        ) {
            return
        }
        val validUrl = clipboardDetector.extractFirstValidUrl(rawUrl.trim())
            ?: return
        _inputUrl.value = validUrl
        val platform = PlatformBadge.fromUrl(validUrl)
        DownloadStateCoordinator.setAbsorbing(validUrl, platform)
        BlackholeDownloadService.startGravityPull(context, validUrl)
    }

    fun deleteVideo(video: DownloadedVideoEntity) {
        viewModelScope.launch {
            repository.deleteVideo(video)
        }
    }

    fun importCookiesFromUri(uri: Uri) {
        viewModelScope.launch {
            val importedCount = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().use { reader ->
                            cookieVault.importNetscapeCookiesTxt(reader.readText())
                        }
                    } ?: 0
                }.getOrDefault(0)
            }
            _vaultStatusMessage.value = if (importedCount > 0) {
                "Encrypted & stored $importedCount session cookies in AES-256 Vault."
            } else {
                "No valid Netscape/JSON cookies found in selected file."
            }
        }
    }

    fun syncWebViewSession(url: String, silent: Boolean = false) {
        viewModelScope.launch {
            val syncedCount = cookieVault.syncFromWebView(url)
            if (!silent || syncedCount > 0) {
                _vaultStatusMessage.value = if (syncedCount > 0) {
                    "Synced $syncedCount live session cookies into AES-256 Vault."
                } else {
                    "No session cookies captured yet — complete login first, then tap Sync."
                }
            }
        }
    }

    fun clearAllCookies() {
        viewModelScope.launch {
            cookieVault.clearAllCookies()
            _vaultStatusMessage.value = "Session Vault cleared."
        }
    }

    fun clearVaultStatusMessage() {
        _vaultStatusMessage.value = null
    }

    companion object {
        private const val ENTRY_CHECK_COOLDOWN_MS = 15_000L
    }
}
