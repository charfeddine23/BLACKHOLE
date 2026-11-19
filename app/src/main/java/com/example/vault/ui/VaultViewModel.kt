package com.example.vault.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vault.crypto.UltraVaultEngine
import com.example.vault.crypto.VaultOperationStage
import com.example.vault.data.SafVaultRepository
import com.example.vault.data.VaultFileEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface VaultTransferUiState {
    data object Idle : VaultTransferUiState
    data class Running(
        val isEncrypting: Boolean,
        val progress: Float,
        val bytesProcessed: Long,
        val totalBytes: Long,
        val stage: VaultOperationStage,
        val label: String,
    ) : VaultTransferUiState
}

data class VaultPlaybackSession(
    val tempFile: File,
    val displayTitle: String,
    val mimeType: String,
)

data class VaultUiState(
    val isLocked: Boolean = true,
    val biometricVerified: Boolean = false,
    val isExternalSafActive: Boolean = false,
    val storageLabel: String = "Private Internal Vault (App Storage)",
    val entries: List<VaultFileEntry> = emptyList(),
    val transferState: VaultTransferUiState = VaultTransferUiState.Idle,
    val statusMessage: String? = null,
    val isErrorStatus: Boolean = false,
    val activePlayback: VaultPlaybackSession? = null,
)

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val repository = SafVaultRepository(context)

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private var sessionPasswordChars: CharArray? = null
    private var activeTransferJob: Job? = null

    @Volatile
    private var awaitingExternalSystemFlow: Boolean = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            UltraVaultEngine.purgeAllDecryptedTempFiles(context)
            val isSaf = runCatching { repository.isExternalSafActive() }.getOrDefault(false)
            val label = runCatching { repository.getStorageDisplayLabel() }
                .getOrDefault("Private Internal Vault (App Storage)")
            _uiState.update {
                it.copy(
                    isExternalSafActive = isSaf,
                    storageLabel = label,
                )
            }
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
            val secretChars = runCatching { repository.getOrCreateBiometricVaultSecret() }.getOrNull()
            if (secretChars == null) {
                _uiState.update {
                    it.copy(
                        isLocked = true,
                        biometricVerified = false,
                        statusMessage = "Unable to initialize Vault key store.",
                        isErrorStatus = true,
                    )
                }
                return@launch
            }
            wipeSessionPassword()
            sessionPasswordChars = secretChars
            val isSaf = runCatching { repository.isExternalSafActive() }.getOrDefault(false)
            val label = runCatching { repository.getStorageDisplayLabel() }
                .getOrDefault("Private Internal Vault (App Storage)")
            _uiState.update {
                it.copy(
                    isLocked = false,
                    biometricVerified = true,
                    statusMessage = noticeMessage,
                    isErrorStatus = false,
                    isExternalSafActive = isSaf,
                    storageLabel = label,
                )
            }
            refreshEntries()
        }
    }

    fun lockVaultAndWipe() {
        awaitingExternalSystemFlow = false
        activeTransferJob?.cancel()
        activeTransferJob = null
        wipeSessionPassword()
        val currentPlayback = _uiState.value.activePlayback
        _uiState.update {
            it.copy(
                isLocked = true,
                biometricVerified = false,
                entries = emptyList(),
                activePlayback = null,
                transferState = VaultTransferUiState.Idle,
            )
        }
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            UltraVaultEngine.secureDeleteFile(currentPlayback?.tempFile)
            UltraVaultEngine.purgeAllDecryptedTempFiles(context)
        }
    }

    fun refreshEntries() {
        viewModelScope.launch {
            val items = runCatching { repository.listVaultEntries() }.getOrDefault(emptyList())
            val isSaf = runCatching { repository.isExternalSafActive() }.getOrDefault(false)
            val label = runCatching { repository.getStorageDisplayLabel() }
                .getOrDefault("Private Internal Vault (App Storage)")
            _uiState.update {
                it.copy(
                    entries = items,
                    isExternalSafActive = isSaf,
                    storageLabel = label,
                )
            }
        }
    }

    fun onSafDirectorySelected(treeUri: Uri) {
        viewModelScope.launch {
            val persisted = runCatching { repository.persistSafTreeUri(treeUri) }.getOrDefault(false)
            val isSaf = runCatching { repository.isExternalSafActive() }.getOrDefault(false)
            val label = runCatching { repository.getStorageDisplayLabel() }
                .getOrDefault("Private Internal Vault (App Storage)")
            _uiState.update {
                it.copy(
                    isExternalSafActive = isSaf,
                    storageLabel = label,
                    statusMessage = if (persisted) {
                        "External SAF Vault bound. Encrypted .bhv files persist even across app uninstall."
                    } else {
                        "Selected folder is not writable. Keeping previous storage."
                    },
                    isErrorStatus = !persisted,
                )
            }
            refreshEntries()
        }
    }

    fun revertToInternalVaultStorage() {
        viewModelScope.launch {
            runCatching { repository.clearSafTreeUri() }
            val isSaf = runCatching { repository.isExternalSafActive() }.getOrDefault(false)
            val label = runCatching { repository.getStorageDisplayLabel() }
                .getOrDefault("Private Internal Vault (App Storage)")
            _uiState.update {
                it.copy(
                    isExternalSafActive = isSaf,
                    storageLabel = label,
                    statusMessage = "Switched active vault index back to Private Internal Storage.",
                    isErrorStatus = false,
                )
            }
            refreshEntries()
        }
    }

    fun encryptVideoFromPicker(sourceUri: Uri) {
        if (activeTransferJob?.isActive == true) return
        activeTransferJob = viewModelScope.launch {
            val pwd = sessionPasswordChars ?: runCatching {
                repository.getOrCreateBiometricVaultSecret()
            }.getOrNull()?.also {
                sessionPasswordChars = it
            }

            if (pwd == null || _uiState.value.isLocked) {
                _uiState.update {
                    it.copy(
                        isLocked = true,
                        statusMessage = "Verify biometric to unlock Vault first.",
                        isErrorStatus = true,
                    )
                }
                return@launch
            }

            val pwdCopy = pwd.copyOf()
            try {
                repository.encryptMediaFromUri(sourceUri, pwdCopy).collect { progress ->
                    val label = progress.metadata?.originalName ?: "Encrypting video"
                    when (progress.stage) {
                        VaultOperationStage.COMPLETED -> {
                            _uiState.update {
                                it.copy(
                                    transferState = VaultTransferUiState.Idle,
                                    statusMessage = "Encrypted '$label' with AES-256-GCM.",
                                    isErrorStatus = false,
                                )
                            }
                            refreshEntries()
                        }
                        else -> {
                            _uiState.update {
                                it.copy(
                                    transferState = VaultTransferUiState.Running(
                                        isEncrypting = true,
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
                        statusMessage = "Encryption cancelled — temporary staging files wiped.",
                        isErrorStatus = false,
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        statusMessage = t.message ?: "Encryption failed cleanly without corrupting vault.",
                        isErrorStatus = true,
                    )
                }
            } finally {
                pwdCopy.fill('\u0000')
            }
        }
    }

    fun decryptAndPlayEntry(entry: VaultFileEntry) {
        val pwd = sessionPasswordChars
        if (pwd == null || _uiState.value.isLocked) {
            _uiState.update {
                it.copy(
                    isLocked = true,
                    statusMessage = "Vault locked — verify biometric first.",
                    isErrorStatus = true,
                )
            }
            return
        }
        if (activeTransferJob?.isActive == true) return

        closePlayerAndWipeTemp()
        val pwdCopy = pwd.copyOf()
        activeTransferJob = viewModelScope.launch {
            try {
                repository.decryptEntryToTempCache(entry, pwdCopy).collect { progress ->
                    when (progress.stage) {
                        VaultOperationStage.COMPLETED -> {
                            val decryptedFile = progress.outputFile
                            val meta = progress.metadata
                            if (decryptedFile != null && decryptedFile.exists() && meta != null) {
                                repository.cacheEncryptedDisplayTitle(entry.fileName, meta.originalName)
                                _uiState.update {
                                    it.copy(
                                        transferState = VaultTransferUiState.Idle,
                                        activePlayback = VaultPlaybackSession(
                                            tempFile = decryptedFile,
                                            displayTitle = meta.originalName,
                                            mimeType = meta.mimeType,
                                        ),
                                        statusMessage = null,
                                    )
                                }
                                refreshEntries()
                            } else {
                                _uiState.update {
                                    it.copy(
                                        transferState = VaultTransferUiState.Idle,
                                        statusMessage = "Decrypted stream unavailable.",
                                        isErrorStatus = true,
                                    )
                                }
                            }
                        }
                        else -> {
                            _uiState.update {
                                it.copy(
                                    transferState = VaultTransferUiState.Running(
                                        isEncrypting = false,
                                        progress = progress.fraction,
                                        bytesProcessed = progress.bytesProcessed,
                                        totalBytes = progress.totalBytes,
                                        stage = progress.stage,
                                        label = progress.metadata?.originalName ?: entry.displayTitle,
                                    ),
                                    statusMessage = null,
                                )
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.IO + NonCancellable) {
                    UltraVaultEngine.purgeAllDecryptedTempFiles(context)
                }
                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        statusMessage = "Decryption cancelled — temporary cache cleared.",
                        isErrorStatus = false,
                    )
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.IO + NonCancellable) {
                    UltraVaultEngine.purgeAllDecryptedTempFiles(context)
                }
                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        statusMessage = t.message ?: "Authentication failed — unable to decrypt file.",
                        isErrorStatus = true,
                    )
                }
            } finally {
                pwdCopy.fill('\u0000')
            }
        }
    }

    fun cancelActiveTransfer() {
        activeTransferJob?.cancel()
    }

    fun closePlayerAndWipeTemp() {
        val current = _uiState.value.activePlayback
        _uiState.update { it.copy(activePlayback = null) }
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            UltraVaultEngine.secureDeleteFile(current?.tempFile)
            UltraVaultEngine.purgeAllDecryptedTempFiles(context)
        }
    }

    fun deleteEntry(entry: VaultFileEntry) {
        viewModelScope.launch {
            val deleted = repository.deleteEntry(entry)
            _uiState.update {
                it.copy(
                    statusMessage = if (deleted) {
                        "Shredded encrypted file '${entry.displayTitle}'."
                    } else {
                        "Unable to delete '${entry.fileName}'."
                    },
                    isErrorStatus = !deleted,
                )
            }
            refreshEntries()
        }
    }

    fun reportStatusError(message: String) {
        _uiState.update { it.copy(statusMessage = message, isErrorStatus = true) }
    }

    fun dismissStatus() {
        _uiState.update { it.copy(statusMessage = null, isErrorStatus = false) }
    }

    private fun wipeSessionPassword() {
        sessionPasswordChars?.fill('\u0000')
        sessionPasswordChars = null
    }

    override fun onCleared() {
        super.onCleared()
        activeTransferJob?.cancel()
        wipeSessionPassword()
        // Run cleanup in a daemon background thread without touching Main Thread during teardown
        Thread {
            UltraVaultEngine.purgeAllDecryptedTempFiles(context)
        }.start()
    }
}
