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

    private val _uiState = MutableStateFlow(
        VaultUiState(
            isExternalSafActive = repository.isExternalSafActive(),
            storageLabel = repository.getStorageDisplayLabel(),
        ),
    )
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private var sessionPasswordChars: CharArray? = null
    private var activeTransferJob: Job? = null

    init {
        UltraVaultEngine.purgeAllDecryptedTempFiles(context)
    }

    fun markBiometricVerified() {
        _uiState.update {
            it.copy(
                biometricVerified = true,
                statusMessage = null,
                isErrorStatus = false,
            )
        }
    }

    fun unlockWithMasterPassword(passwordInput: String) {
        if (passwordInput.length < 4) {
            _uiState.update {
                it.copy(
                    statusMessage = "Enter a master password (minimum 4 characters) to derive AES-256 keys.",
                    isErrorStatus = true,
                )
            }
            return
        }
        wipeSessionPassword()
        sessionPasswordChars = passwordInput.toCharArray()
        _uiState.update {
            it.copy(
                isLocked = false,
                statusMessage = null,
                isErrorStatus = false,
                isExternalSafActive = repository.isExternalSafActive(),
                storageLabel = repository.getStorageDisplayLabel(),
            )
        }
        refreshEntries()
    }

    fun lockVaultAndWipe() {
        activeTransferJob?.cancel()
        activeTransferJob = null
        wipeSessionPassword()
        val currentPlayback = _uiState.value.activePlayback
        UltraVaultEngine.secureDeleteFile(currentPlayback?.tempFile)
        UltraVaultEngine.purgeAllDecryptedTempFiles(context)
        _uiState.update {
            it.copy(
                isLocked = true,
                biometricVerified = false,
                entries = emptyList(),
                activePlayback = null,
                transferState = VaultTransferUiState.Idle,
            )
        }
    }

    fun refreshEntries() {
        viewModelScope.launch {
            val items = runCatching { repository.listVaultEntries() }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    entries = items,
                    isExternalSafActive = repository.isExternalSafActive(),
                    storageLabel = repository.getStorageDisplayLabel(),
                )
            }
        }
    }

    fun onSafDirectorySelected(treeUri: Uri) {
        val persisted = repository.persistSafTreeUri(treeUri)
        _uiState.update {
            it.copy(
                isExternalSafActive = repository.isExternalSafActive(),
                storageLabel = repository.getStorageDisplayLabel(),
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

    fun revertToInternalVaultStorage() {
        repository.clearSafTreeUri()
        _uiState.update {
            it.copy(
                isExternalSafActive = repository.isExternalSafActive(),
                storageLabel = repository.getStorageDisplayLabel(),
                statusMessage = "Switched active vault index back to Private Internal Storage.",
                isErrorStatus = false,
            )
        }
        refreshEntries()
    }

    fun encryptVideoFromPicker(sourceUri: Uri) {
        val pwd = sessionPasswordChars
        if (pwd == null) {
            _uiState.update {
                it.copy(
                    isLocked = true,
                    statusMessage = "Vault locked — re-enter master password first.",
                    isErrorStatus = true,
                )
            }
            return
        }
        if (activeTransferJob?.isActive == true) return

        val pwdCopy = pwd.copyOf()
        activeTransferJob = viewModelScope.launch {
            try {
                repository.encryptMediaFromUri(sourceUri, pwdCopy).collect { progress ->
                    val label = progress.metadata?.originalName ?: "Encrypting video"
                    when (progress.stage) {
                        VaultOperationStage.COMPLETED -> {
                            _uiState.update {
                                it.copy(
                                    transferState = VaultTransferUiState.Idle,
                                    statusMessage = "Encrypted '$label' with AES-256-GCM (PBKDF2 210k).",
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
        if (pwd == null) {
            _uiState.update {
                it.copy(
                    isLocked = true,
                    statusMessage = "Vault locked — unlock with your master password first.",
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
                UltraVaultEngine.purgeAllDecryptedTempFiles(context)
                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        statusMessage = "Decryption cancelled — temporary cache cleared.",
                        isErrorStatus = false,
                    )
                }
            } catch (t: Throwable) {
                UltraVaultEngine.purgeAllDecryptedTempFiles(context)
                _uiState.update {
                    it.copy(
                        transferState = VaultTransferUiState.Idle,
                        statusMessage = t.message ?: "Authentication failed — wrong password or tampered file.",
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
        UltraVaultEngine.secureDeleteFile(current?.tempFile)
        UltraVaultEngine.purgeAllDecryptedTempFiles(context)
        _uiState.update { it.copy(activePlayback = null) }
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
        UltraVaultEngine.purgeAllDecryptedTempFiles(context)
    }
}
