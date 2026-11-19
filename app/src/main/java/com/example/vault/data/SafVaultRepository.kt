package com.example.vault.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.vault.crypto.UltraVaultEngine
import com.example.vault.crypto.VaultMediaMetadata
import com.example.vault.crypto.VaultOperationProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID

data class VaultFileEntry(
    val id: String,
    val fileName: String,
    val displayTitle: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val uriString: String,
    val isSafExternal: Boolean,
)

class SafVaultRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefsLock = Any()

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun getSecurePrefs(): SharedPreferences {
        cachedPrefs?.let { return it }
        return synchronized(prefsLock) {
            cachedPrefs?.let { return it }
            val created = runCatching {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    VAULT_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.recoverCatching {
                runCatching {
                    File(appContext.filesDir.parentFile, "shared_prefs/$VAULT_PREFS_NAME.xml").delete()
                }
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    VAULT_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrElse {
                // Safe private fallback if OEM KeyStore/Tink is corrupted or unavailable
                appContext.getSharedPreferences(VAULT_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            }
            cachedPrefs = created
            created
        }
    }

    private val internalVaultDir: File
        get() = File(appContext.filesDir, INTERNAL_VAULT_FOLDER).apply {
            if (!exists()) mkdirs()
        }

    suspend fun getOrCreateBiometricVaultSecret(): CharArray = withContext(Dispatchers.IO) {
        val prefs = getSecurePrefs()
        val existing = prefs.getString(KEY_BIOMETRIC_MASTER_SECRET, null)
        if (!existing.isNullOrBlank()) {
            return@withContext existing.toCharArray()
        }
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val encoded = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
        randomBytes.fill(0)
        prefs.edit().putString(KEY_BIOMETRIC_MASTER_SECRET, encoded).commit()
        encoded.toCharArray()
    }

    suspend fun getSafTreeUri(): Uri? = withContext(Dispatchers.IO) {
        getSafTreeUriBlocking()
    }

    private fun getSafTreeUriBlocking(): Uri? {
        val raw = getSecurePrefs().getString(KEY_SAF_TREE_URI, null) ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val hasPersistedPerm = runCatching {
            appContext.contentResolver.persistedUriPermissions.any { perm ->
                perm.uri == uri && perm.isReadPermission && perm.isWritePermission
            }
        }.getOrDefault(false)
        val doc = runCatching { DocumentFile.fromTreeUri(appContext, uri) }.getOrNull()
        val writable = runCatching { doc != null && doc.exists() && doc.canWrite() }.getOrDefault(false)
        if (!hasPersistedPerm || !writable) {
            return null
        }
        return uri
    }

    suspend fun isExternalSafActive(): Boolean = getSafTreeUri() != null

    suspend fun getStorageDisplayLabel(): String = withContext(Dispatchers.IO) {
        val uri = getSafTreeUriBlocking() ?: return@withContext "Private Internal Vault (App Storage)"
        val docName = runCatching {
            DocumentFile.fromTreeUri(appContext, uri)?.name
        }.getOrNull().orEmpty()
        if (docName.isNotBlank()) {
            "SAF External: $docName (Survives Uninstall)"
        } else {
            "SAF External Folder (Survives Uninstall)"
        }
    }

    suspend fun persistSafTreeUri(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        val doc = runCatching { DocumentFile.fromTreeUri(appContext, treeUri) }.getOrNull()
        val writable = runCatching { doc != null && doc.exists() && doc.canWrite() }.getOrDefault(false)
        if (!writable) {
            return@withContext false
        }
        getSecurePrefs().edit().putString(KEY_SAF_TREE_URI, treeUri.toString()).commit()
        true
    }

    suspend fun clearSafTreeUri() = withContext(Dispatchers.IO) {
        getSecurePrefs().edit().remove(KEY_SAF_TREE_URI).commit()
    }

    suspend fun listVaultEntries(): List<VaultFileEntry> = withContext(Dispatchers.IO) {
        val prefs = getSecurePrefs()
        val safUri = getSafTreeUriBlocking()
        if (safUri != null) {
            val tree = runCatching { DocumentFile.fromTreeUri(appContext, safUri) }.getOrNull()
            if (tree != null && runCatching { tree.exists() }.getOrDefault(false)) {
                val files = runCatching { tree.listFiles() }.getOrDefault(emptyArray())
                // Scrub orphaned staging files from prior crashes
                files.filter { it.name?.endsWith(STAGING_SUFFIX, ignoreCase = true) == true }
                    .forEach { runCatching { it.delete() } }

                return@withContext files
                    .filter { it.isFile && it.name?.endsWith(VAULT_EXT, ignoreCase = true) == true }
                    .map { doc ->
                        val name = doc.name ?: "vault_item$VAULT_EXT"
                        val cachedTitle = prefs.getString("$KEY_TITLE_PREFIX$name", null)
                        VaultFileEntry(
                            id = name.removeSuffix(VAULT_EXT),
                            fileName = name,
                            displayTitle = cachedTitle ?: "Encrypted Media (${name.take(12)})",
                            sizeBytes = doc.length(),
                            lastModifiedMs = doc.lastModified(),
                            uriString = doc.uri.toString(),
                            isSafExternal = true,
                        )
                    }
                    .sortedByDescending { it.lastModifiedMs }
            }
        }

        // Fallback to private app vault directory
        val dir = internalVaultDir
        dir.listFiles()?.filter { it.name.endsWith(STAGING_SUFFIX, ignoreCase = true) }
            ?.forEach { UltraVaultEngine.secureDeleteFile(it) }

        (dir.listFiles()?.toList() ?: emptyList())
            .filter { it.isFile && it.name.endsWith(VAULT_EXT, ignoreCase = true) }
            .map { file ->
                val cachedTitle = prefs.getString("$KEY_TITLE_PREFIX${file.name}", null)
                VaultFileEntry(
                    id = file.name.removeSuffix(VAULT_EXT),
                    fileName = file.name,
                    displayTitle = cachedTitle ?: "Encrypted Media (${file.name.take(12)})",
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                    uriString = Uri.fromFile(file).toString(),
                    isSafExternal = false,
                )
            }
            .sortedByDescending { it.lastModifiedMs }
    }

    fun encryptMediaFromUri(
        sourceUri: Uri,
        password: CharArray,
    ): Flow<VaultOperationProgress> = flow {
        val meta = resolveSourceMetadata(sourceUri)
        val inputStream = if (sourceUri.scheme.equals("file", ignoreCase = true)) {
            val filePath = sourceUri.path ?: throw IllegalStateException("Invalid selected file path.")
            File(filePath).inputStream()
        } else {
            appContext.contentResolver.openInputStream(sourceUri)
                ?: throw IllegalStateException("Unable to open selected video stream.")
        }

        val itemId = "bhv_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val finalName = "$itemId$VAULT_EXT"
        val stagingName = "$finalName$STAGING_SUFFIX"

        val safUri = getSafTreeUriBlocking()
        if (safUri != null) {
            val tree = DocumentFile.fromTreeUri(appContext, safUri)
                ?: run {
                    runCatching { inputStream.close() }
                    throw IllegalStateException("External SAF Vault directory unavailable.")
                }
            val stagingDoc = tree.createFile("application/octet-stream", stagingName)
                ?: run {
                    runCatching { inputStream.close() }
                    throw IllegalStateException("Failed to create encrypted SAF staging file.")
                }
            val outStream = appContext.contentResolver.openOutputStream(stagingDoc.uri, "w")
                ?: run {
                    runCatching { stagingDoc.delete() }
                    runCatching { inputStream.close() }
                    throw IllegalStateException("Failed to open SAF output stream.")
                }

            val streamFlow = UltraVaultEngine.encryptStream(
                inputStream = inputStream,
                outputStream = outStream,
                password = password,
                metadata = meta,
                onCommitAtomic = {
                    runCatching { outStream.close() }
                    runCatching { inputStream.close() }
                    val renamed = runCatching { stagingDoc.renameTo(finalName) }.getOrDefault(false)
                    if (!renamed) {
                        // Fallback for strict OEM DocumentProviders that block renameTo across extensions
                        val committedDoc = tree.createFile("application/octet-stream", finalName)
                            ?: throw IllegalStateException("Failed to finalize SAF vault file.")
                        appContext.contentResolver.openInputStream(stagingDoc.uri)?.use { src ->
                            appContext.contentResolver.openOutputStream(committedDoc.uri, "w")?.use { dst ->
                                src.copyTo(dst, 64 * 1024)
                                dst.flush()
                            }
                        }
                        runCatching { stagingDoc.delete() }
                    }
                    cacheEncryptedDisplayTitle(finalName, meta.originalName)
                },
                onCleanupFailed = {
                    runCatching { outStream.close() }
                    runCatching { stagingDoc.delete() }
                    runCatching { inputStream.close() }
                },
            )
            emitAll(streamFlow)
        } else {
            val dir = internalVaultDir
            val stagingFile = File(dir, stagingName)
            val finalFile = File(dir, finalName)
            val outStream: OutputStream = try {
                stagingFile.outputStream()
            } catch (t: Throwable) {
                runCatching { inputStream.close() }
                throw t
            }

            val streamFlow = UltraVaultEngine.encryptStream(
                inputStream = inputStream,
                outputStream = outStream,
                password = password,
                metadata = meta,
                onCommitAtomic = {
                    runCatching { outStream.close() }
                    runCatching { inputStream.close() }
                    if (finalFile.exists()) finalFile.delete()
                    val renamed = stagingFile.renameTo(finalFile)
                    if (!renamed) {
                        stagingFile.copyTo(finalFile, overwrite = true)
                        UltraVaultEngine.secureDeleteFile(stagingFile)
                    }
                    cacheEncryptedDisplayTitle(finalName, meta.originalName)
                },
                onCleanupFailed = {
                    runCatching { outStream.close() }
                    UltraVaultEngine.secureDeleteFile(stagingFile)
                    runCatching { inputStream.close() }
                },
            )
            emitAll(streamFlow)
        }
    }.flowOn(Dispatchers.IO)

    fun decryptEntryToTempCache(
        entry: VaultFileEntry,
        password: CharArray,
    ): Flow<VaultOperationProgress> = flow {
        val input: InputStream = openEntryInputStream(entry)
            ?: throw IllegalStateException("Encrypted file could not be opened.")
        try {
            emitAll(
                UltraVaultEngine.decryptToTempFile(
                    context = appContext,
                    inputStream = input,
                    encryptedFileLengthHint = entry.sizeBytes,
                    password = password,
                ),
            )
        } finally {
            runCatching { input.close() }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun deleteEntry(entry: VaultFileEntry): Boolean = withContext(Dispatchers.IO) {
        val deleted = if (entry.isSafExternal) {
            runCatching {
                val doc = DocumentFile.fromSingleUri(appContext, Uri.parse(entry.uriString))
                doc?.delete() == true
            }.getOrDefault(false)
        } else {
            runCatching {
                val file = File(Uri.parse(entry.uriString).path ?: return@runCatching false)
                UltraVaultEngine.secureDeleteFile(file)
                !file.exists()
            }.getOrDefault(false)
        }
        if (deleted) {
            getSecurePrefs().edit().remove("$KEY_TITLE_PREFIX${entry.fileName}").commit()
        }
        deleted
    }

    suspend fun cacheEncryptedDisplayTitle(fileName: String, originalTitle: String) =
        withContext(Dispatchers.IO) {
            getSecurePrefs().edit().putString("$KEY_TITLE_PREFIX$fileName", originalTitle).commit()
        }

    private fun openEntryInputStream(entry: VaultFileEntry): InputStream? {
        return if (entry.isSafExternal) {
            appContext.contentResolver.openInputStream(Uri.parse(entry.uriString))
        } else {
            val path = Uri.parse(entry.uriString).path ?: return null
            val file = File(path)
            if (file.exists()) file.inputStream() else null
        }
    }

    private fun resolveSourceMetadata(uri: Uri): VaultMediaMetadata {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.let(::File)
            val name = file?.name?.takeIf { it.isNotBlank() }
                ?: "vault_video_${System.currentTimeMillis()}.mp4"
            val size = file?.takeIf { it.exists() }?.length() ?: 0L
            return VaultMediaMetadata(
                originalName = name,
                mimeType = "video/mp4",
                originalSizeBytes = size,
            )
        }
        var displayName = "vault_video_${System.currentTimeMillis()}.mp4"
        var sizeBytes = 0L
        runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        sizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        if (sizeBytes <= 0L) {
            runCatching {
                appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0L) sizeBytes = afd.length
                }
            }
        }
        val mime = runCatching { appContext.contentResolver.getType(uri) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "video/mp4"
        return VaultMediaMetadata(
            originalName = displayName,
            mimeType = mime,
            originalSizeBytes = sizeBytes,
        )
    }

    companion object {
        private const val VAULT_PREFS_NAME = "blackhole_vault_saf_prefs"
        private const val VAULT_FALLBACK_PREFS_NAME = "blackhole_vault_saf_prefs_fb"
        private const val KEY_SAF_TREE_URI = "KEY_SAF_TREE_URI"
        private const val KEY_BIOMETRIC_MASTER_SECRET = "KEY_BIOMETRIC_MASTER_SECRET"
        private const val KEY_TITLE_PREFIX = "TITLE_"
        private const val INTERNAL_VAULT_FOLDER = "blackhole_vault"
        private const val VAULT_EXT = ".bhv"
        private const val STAGING_SUFFIX = ".tmp"
    }
}
