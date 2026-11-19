package com.example.vault.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.ContextCompat
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

enum class VaultStorageMode {
    INTERNAL,
    PHONE_DOWNLOADS,
    SAF_EXTERNAL,
}

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

    @Suppress("DEPRECATION")
    private val legacyPublicDownloadsDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            PUBLIC_VAULT_FOLDER,
        )

    private fun hasLegacyExternalWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
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
        if (!writable && !hasPersistedPerm) {
            return null
        }
        return if (writable) uri else null
    }

    private fun getActiveStorageModeBlocking(): VaultStorageMode {
        val savedMode = getSecurePrefs().getString(KEY_STORAGE_MODE, null)
        if (savedMode == VaultStorageMode.PHONE_DOWNLOADS.name && hasLegacyExternalWritePermission()) {
            return VaultStorageMode.PHONE_DOWNLOADS
        }
        if (savedMode == VaultStorageMode.INTERNAL.name) {
            return VaultStorageMode.INTERNAL
        }
        if (getSafTreeUriBlocking() != null) {
            return VaultStorageMode.SAF_EXTERNAL
        }
        if (savedMode == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasInternalFiles = internalVaultDir.listFiles()?.any {
                it.isFile && it.name.endsWith(VAULT_EXT, ignoreCase = true)
            } == true
            if (!hasInternalFiles) {
                return VaultStorageMode.PHONE_DOWNLOADS
            }
        }
        return VaultStorageMode.INTERNAL
    }

    suspend fun getActiveStorageMode(): VaultStorageMode = withContext(Dispatchers.IO) {
        getActiveStorageModeBlocking()
    }

    suspend fun isExternalSafActive(): Boolean = withContext(Dispatchers.IO) {
        getActiveStorageModeBlocking() != VaultStorageMode.INTERNAL
    }

    suspend fun getStorageDisplayLabel(): String = withContext(Dispatchers.IO) {
        when (getActiveStorageModeBlocking()) {
            VaultStorageMode.SAF_EXTERNAL -> {
                val uri = getSafTreeUriBlocking()
                val docName = uri?.let {
                    runCatching { DocumentFile.fromTreeUri(appContext, it)?.name }.getOrNull()
                }.orEmpty()
                if (docName.isNotBlank()) {
                    "SAF External: $docName (Survives Uninstall)"
                } else {
                    "SAF External Folder (Survives Uninstall)"
                }
            }
            VaultStorageMode.PHONE_DOWNLOADS -> {
                "Phone Storage: Download/$PUBLIC_VAULT_FOLDER"
            }
            VaultStorageMode.INTERNAL -> {
                "Private Internal Vault (App Storage)"
            }
        }
    }

    suspend fun persistSafTreeUri(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bothFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val tookBoth = runCatching {
            appContext.contentResolver.takePersistableUriPermission(treeUri, bothFlags)
        }.isSuccess
        if (!tookBoth) {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        val doc = runCatching { DocumentFile.fromTreeUri(appContext, treeUri) }.getOrNull()
        val writable = runCatching { doc != null && doc.exists() && doc.canWrite() }.getOrDefault(false)
        if (!writable) {
            return@withContext false
        }
        getSecurePrefs().edit()
            .putString(KEY_SAF_TREE_URI, treeUri.toString())
            .putString(KEY_STORAGE_MODE, VaultStorageMode.SAF_EXTERNAL.name)
            .commit()
        true
    }

    suspend fun activatePhoneDownloadsVault(): Boolean = withContext(Dispatchers.IO) {
        if (!hasLegacyExternalWritePermission()) {
            return@withContext false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = legacyPublicDownloadsDir
            if (!dir.exists() && !dir.mkdirs()) {
                return@withContext false
            }
        }
        getSecurePrefs().edit()
            .putString(KEY_STORAGE_MODE, VaultStorageMode.PHONE_DOWNLOADS.name)
            .commit()
        true
    }

    suspend fun clearSafTreeUri() = withContext(Dispatchers.IO) {
        getSecurePrefs().edit()
            .remove(KEY_SAF_TREE_URI)
            .putString(KEY_STORAGE_MODE, VaultStorageMode.INTERNAL.name)
            .commit()
    }

    suspend fun listVaultEntries(): List<VaultFileEntry> = withContext(Dispatchers.IO) {
        val prefs = getSecurePrefs()
        when (getActiveStorageModeBlocking()) {
            VaultStorageMode.SAF_EXTERNAL -> {
                val safUri = getSafTreeUriBlocking()
                if (safUri != null) {
                    val tree = runCatching { DocumentFile.fromTreeUri(appContext, safUri) }.getOrNull()
                    if (tree != null && runCatching { tree.exists() }.getOrDefault(false)) {
                        val files = runCatching { tree.listFiles() }.getOrDefault(emptyArray())
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
            }

            VaultStorageMode.PHONE_DOWNLOADS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val mediaEntries = listMediaStoreDownloadsEntries(prefs)
                    return@withContext mediaEntries
                } else {
                    val dir = legacyPublicDownloadsDir.apply { if (!exists()) mkdirs() }
                    return@withContext listDirectoryEntries(dir, prefs, isExternalFlag = false)
                }
            }

            VaultStorageMode.INTERNAL -> Unit
        }

        // Default / fallback to private app vault directory
        val dir = internalVaultDir
        listDirectoryEntries(dir, prefs, isExternalFlag = false)
    }

    private fun listDirectoryEntries(
        dir: File,
        prefs: SharedPreferences,
        isExternalFlag: Boolean,
    ): List<VaultFileEntry> {
        dir.listFiles()?.filter { it.name.endsWith(STAGING_SUFFIX, ignoreCase = true) }
            ?.forEach { UltraVaultEngine.secureDeleteFile(it) }

        return (dir.listFiles()?.toList() ?: emptyList())
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
                    isSafExternal = isExternalFlag,
                )
            }
            .sortedByDescending { it.lastModifiedMs }
    }

    private fun listMediaStoreDownloadsEntries(prefs: SharedPreferences): List<VaultFileEntry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val results = mutableListOf<VaultFileEntry>()
        val resolver = appContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.IS_PENDING,
        )
        runCatching {
            resolver.query(
                collection,
                projection,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("%$PUBLIC_VAULT_FOLDER%"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                val pendingIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.IS_PENDING)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val rowUri = ContentUris.withAppendedId(collection, id)
                    val isPending = cursor.getInt(pendingIdx) == 1
                    val name = cursor.getString(nameIdx).orEmpty()
                    if (isPending) {
                        runCatching { resolver.delete(rowUri, null, null) }
                        continue
                    }
                    if (!name.contains(VAULT_EXT, ignoreCase = true)) continue
                    val size = cursor.getLong(sizeIdx)
                    val modifiedMs = cursor.getLong(dateIdx) * 1000L
                    val cachedTitle = prefs.getString("$KEY_TITLE_PREFIX$name", null)
                        ?: prefs.getString("$KEY_TITLE_PREFIX${name.substringBefore(".bin")}", null)
                    results.add(
                        VaultFileEntry(
                            id = name.substringBefore(VAULT_EXT),
                            fileName = name,
                            displayTitle = cachedTitle ?: "Encrypted Media (${name.take(12)})",
                            sizeBytes = size,
                            lastModifiedMs = modifiedMs,
                            uriString = rowUri.toString(),
                            isSafExternal = true,
                        ),
                    )
                }
            }
        }
        return results
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

        when (getActiveStorageModeBlocking()) {
            VaultStorageMode.SAF_EXTERNAL -> {
                val safUri = getSafTreeUriBlocking()
                    ?: run {
                        runCatching { inputStream.close() }
                        throw IllegalStateException("External SAF Vault directory unavailable.")
                    }
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
            }

            VaultStorageMode.PHONE_DOWNLOADS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = appContext.contentResolver
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_VAULT_FOLDER")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val pendingUri = resolver.insert(collection, values)
                        ?: run {
                            runCatching { inputStream.close() }
                            throw IllegalStateException("Unable to create file in Download/$PUBLIC_VAULT_FOLDER.")
                        }
                    val outStream = resolver.openOutputStream(pendingUri, "w")
                        ?: run {
                            runCatching { resolver.delete(pendingUri, null, null) }
                            runCatching { inputStream.close() }
                            throw IllegalStateException("Unable to open Download/$PUBLIC_VAULT_FOLDER output stream.")
                        }

                    val streamFlow = UltraVaultEngine.encryptStream(
                        inputStream = inputStream,
                        outputStream = outStream,
                        password = password,
                        metadata = meta,
                        onCommitAtomic = {
                            runCatching { outStream.close() }
                            runCatching { inputStream.close() }
                            val commitValues = ContentValues().apply {
                                put(MediaStore.Downloads.IS_PENDING, 0)
                            }
                            resolver.update(pendingUri, commitValues, null, null)
                            val committedName = queryDisplayNameForUri(pendingUri) ?: finalName
                            cacheEncryptedDisplayTitle(committedName, meta.originalName)
                            cacheEncryptedDisplayTitle(finalName, meta.originalName)
                        },
                        onCleanupFailed = {
                            runCatching { outStream.close() }
                            runCatching { resolver.delete(pendingUri, null, null) }
                            runCatching { inputStream.close() }
                        },
                    )
                    emitAll(streamFlow)
                } else {
                    val dir = legacyPublicDownloadsDir.apply { if (!exists()) mkdirs() }
                    emitAll(encryptIntoLocalDirectory(dir, finalName, stagingName, inputStream, password, meta))
                }
            }

            VaultStorageMode.INTERNAL -> {
                emitAll(encryptIntoLocalDirectory(internalVaultDir, finalName, stagingName, inputStream, password, meta))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun encryptIntoLocalDirectory(
        dir: File,
        finalName: String,
        stagingName: String,
        inputStream: InputStream,
        password: CharArray,
        meta: VaultMediaMetadata,
    ): Flow<VaultOperationProgress> {
        val stagingFile = File(dir, stagingName)
        val finalFile = File(dir, finalName)
        val outStream: OutputStream = try {
            stagingFile.outputStream()
        } catch (t: Throwable) {
            runCatching { inputStream.close() }
            throw t
        }

        return UltraVaultEngine.encryptStream(
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
    }

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
        val uri = Uri.parse(entry.uriString)
        val deleted = if (uri.scheme.equals("content", ignoreCase = true)) {
            val directDelete = runCatching {
                appContext.contentResolver.delete(uri, null, null) > 0
            }.getOrDefault(false)
            if (directDelete) {
                true
            } else {
                runCatching {
                    DocumentFile.fromSingleUri(appContext, uri)?.delete() == true
                }.getOrDefault(false)
            }
        } else {
            runCatching {
                val file = File(uri.path ?: return@runCatching false)
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

    suspend fun listBlackholeDownloadedVideos(): List<File> = withContext(Dispatchers.IO) {
        val downloadsDir = File(appContext.filesDir, "blackhole_videos")
        if (!downloadsDir.exists() || !downloadsDir.isDirectory) return@withContext emptyList()
        (downloadsDir.listFiles()?.toList() ?: emptyList())
            .filter { file ->
                file.isFile && file.length() > 0L && !file.name.endsWith(".tmp", ignoreCase = true)
            }
            .sortedByDescending { it.lastModified() }
    }

    private fun openEntryInputStream(entry: VaultFileEntry): InputStream? {
        val uri = Uri.parse(entry.uriString)
        return if (uri.scheme.equals("content", ignoreCase = true)) {
            appContext.contentResolver.openInputStream(uri)
        } else {
            val path = uri.path ?: return null
            val file = File(path)
            if (file.exists()) file.inputStream() else null
        }
    }

    private fun queryDisplayNameForUri(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() } else null
                } else {
                    null
                }
            }
        }.getOrNull()
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
        private const val KEY_STORAGE_MODE = "KEY_STORAGE_MODE"
        private const val KEY_BIOMETRIC_MASTER_SECRET = "KEY_BIOMETRIC_MASTER_SECRET"
        private const val KEY_TITLE_PREFIX = "TITLE_"
        private const val INTERNAL_VAULT_FOLDER = "blackhole_vault"
        private const val PUBLIC_VAULT_FOLDER = "BlackholeVault"
        private const val VAULT_EXT = ".bhv"
        private const val STAGING_SUFFIX = ".tmp"
    }
}
