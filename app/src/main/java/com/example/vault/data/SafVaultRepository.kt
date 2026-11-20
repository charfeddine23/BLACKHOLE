package com.example.vault.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

enum class VaultStorageMode {
    INTERNAL,
    PHONE_DOWNLOADS,
    SAF_EXTERNAL,
}

enum class VaultCategory {
    PHOTOS,
    VIDEOS,
    FILES,
}

enum class VaultTransferStage {
    PREPARING,
    MOVING,
    FINALIZING,
    COMPLETED,
}

data class VaultMediaMetadata(
    val originalName: String,
    val mimeType: String,
    val originalSizeBytes: Long,
)

data class VaultTransferProgress(
    val fraction: Float,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val stage: VaultTransferStage,
    val metadata: VaultMediaMetadata,
    val originalDeleted: Boolean = false,
)

data class VaultFileEntry(
    val id: String,
    val fileName: String,
    val displayTitle: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val uriString: String,
    val localFilePath: String? = null,
    val isSafExternal: Boolean,
    val mimeType: String = "video/mp4",
    val category: VaultCategory = VaultCategory.VIDEOS,
)

class SafVaultRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefsLock = Any()

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun getPrefs(): SharedPreferences {
        cachedPrefs?.let { return it }
        return synchronized(prefsLock) {
            cachedPrefs?.let { return it }
            appContext.getSharedPreferences(VAULT_PREFS_NAME, Context.MODE_PRIVATE).also {
                cachedPrefs = it
            }
        }
    }

    private val internalVaultDir: File
        get() = File(appContext.filesDir, INTERNAL_VAULT_FOLDER).apply {
            ensureProtectedDir(this)
        }

    @Suppress("DEPRECATION")
    private val hiddenExternalVaultDir: File
        get() = File(Environment.getExternalStorageDirectory(), HIDDEN_EXTERNAL_VAULT_FOLDER)

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureProtectedDir(dir: File) {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val nomedia = File(dir, NOMEDIA_FILE_NAME)
        if (!nomedia.exists()) {
            runCatching { nomedia.createNewFile() }
        }
    }

    private fun getSafTreeUriBlocking(): Uri? {
        val raw = getPrefs().getString(KEY_SAF_TREE_URI, null) ?: return null
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
        val savedMode = getPrefs().getString(KEY_STORAGE_MODE, null)
        if (savedMode == VaultStorageMode.PHONE_DOWNLOADS.name) {
            if (hasAllFilesAccess() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return VaultStorageMode.PHONE_DOWNLOADS
            }
        }
        if (savedMode == VaultStorageMode.INTERNAL.name) {
            return VaultStorageMode.INTERNAL
        }
        if (getSafTreeUriBlocking() != null) {
            return VaultStorageMode.SAF_EXTERNAL
        }
        if (savedMode == null && hasAllFilesAccess()) {
            return VaultStorageMode.PHONE_DOWNLOADS
        }
        return VaultStorageMode.INTERNAL
    }

    suspend fun getActiveStorageMode(): VaultStorageMode = withContext(Dispatchers.IO) {
        getActiveStorageModeBlocking()
    }

    suspend fun getStorageDisplayLabel(): String = withContext(Dispatchers.IO) {
        when (getActiveStorageModeBlocking()) {
            VaultStorageMode.SAF_EXTERNAL -> {
                val uri = getSafTreeUriBlocking()
                val docName = uri?.let {
                    runCatching { DocumentFile.fromTreeUri(appContext, it)?.name }.getOrNull()
                }.orEmpty()
                if (docName.isNotBlank()) {
                    "External SAF: $docName (.nomedia)"
                } else {
                    "External SAF Folder (.nomedia)"
                }
            }
            VaultStorageMode.PHONE_DOWNLOADS -> {
                if (hasAllFilesAccess()) {
                    "Phone Hidden Vault: $HIDDEN_EXTERNAL_VAULT_FOLDER"
                } else {
                    "Phone Downloads: Download/$PUBLIC_DOWNLOADS_SUBFOLDER"
                }
            }
            VaultStorageMode.INTERNAL -> {
                "Private App Vault (Isolated Sandbox)"
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
        if (doc != null && doc.findFile(NOMEDIA_FILE_NAME) == null) {
            runCatching { doc.createFile("application/octet-stream", NOMEDIA_FILE_NAME) }
        }
        getPrefs().edit()
            .putString(KEY_SAF_TREE_URI, treeUri.toString())
            .putString(KEY_STORAGE_MODE, VaultStorageMode.SAF_EXTERNAL.name)
            .commit()
        true
    }

    suspend fun activatePhoneDownloadsVault(): Boolean = withContext(Dispatchers.IO) {
        if (!hasAllFilesAccess() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext false
        }
        if (hasAllFilesAccess()) {
            ensureProtectedDir(hiddenExternalVaultDir)
        }
        getPrefs().edit()
            .putString(KEY_STORAGE_MODE, VaultStorageMode.PHONE_DOWNLOADS.name)
            .commit()
        true
    }

    suspend fun revertToInternalVaultStorage() = withContext(Dispatchers.IO) {
        getPrefs().edit()
            .remove(KEY_SAF_TREE_URI)
            .putString(KEY_STORAGE_MODE, VaultStorageMode.INTERNAL.name)
            .commit()
    }

    suspend fun listVaultEntries(): List<VaultFileEntry> = withContext(Dispatchers.IO) {
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
                            .filter { doc ->
                                val name = doc.name.orEmpty()
                                doc.isFile &&
                                    name.isNotBlank() &&
                                    !name.equals(NOMEDIA_FILE_NAME, ignoreCase = true) &&
                                    !name.startsWith(".") &&
                                    !name.endsWith(STAGING_SUFFIX, ignoreCase = true) &&
                                    !name.endsWith(LEGACY_ENCRYPTED_EXT, ignoreCase = true)
                            }
                            .map { doc ->
                                val name = doc.name ?: "vault_item"
                                val mime = doc.type?.takeIf { it.isNotBlank() }
                                    ?: inferMimeFromFileName(name)
                                    ?: "application/octet-stream"
                                VaultFileEntry(
                                    id = doc.uri.toString().hashCode().toString(),
                                    fileName = name,
                                    displayTitle = name,
                                    sizeBytes = doc.length(),
                                    lastModifiedMs = doc.lastModified(),
                                    uriString = doc.uri.toString(),
                                    localFilePath = null,
                                    isSafExternal = true,
                                    mimeType = mime,
                                    category = classifyCategory(name, mime),
                                )
                            }
                            .sortedByDescending { it.lastModifiedMs }
                    }
                }
            }

            VaultStorageMode.PHONE_DOWNLOADS -> {
                if (hasAllFilesAccess()) {
                    val dir = hiddenExternalVaultDir.apply { ensureProtectedDir(this) }
                    return@withContext listDirectoryEntries(dir, isExternalFlag = true)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return@withContext listMediaStoreDownloadsEntries()
                }
            }

            VaultStorageMode.INTERNAL -> Unit
        }

        val dir = internalVaultDir
        listDirectoryEntries(dir, isExternalFlag = false)
    }

    private fun listDirectoryEntries(
        dir: File,
        isExternalFlag: Boolean,
    ): List<VaultFileEntry> {
        ensureProtectedDir(dir)
        dir.listFiles()?.filter { it.name.endsWith(STAGING_SUFFIX, ignoreCase = true) }
            ?.forEach { runCatching { it.delete() } }

        return (dir.listFiles()?.toList() ?: emptyList())
            .filter { file ->
                file.isFile &&
                    !file.name.equals(NOMEDIA_FILE_NAME, ignoreCase = true) &&
                    !file.name.startsWith(".") &&
                    !file.name.endsWith(STAGING_SUFFIX, ignoreCase = true) &&
                    !file.name.endsWith(LEGACY_ENCRYPTED_EXT, ignoreCase = true)
            }
            .map { file ->
                val mime = inferMimeFromFileName(file.name) ?: "application/octet-stream"
                VaultFileEntry(
                    id = file.absolutePath.hashCode().toString(),
                    fileName = file.name,
                    displayTitle = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                    uriString = Uri.fromFile(file).toString(),
                    localFilePath = file.absolutePath,
                    isSafExternal = isExternalFlag,
                    mimeType = mime,
                    category = classifyCategory(file.name, mime),
                )
            }
            .sortedByDescending { it.lastModifiedMs }
    }

    private fun listMediaStoreDownloadsEntries(): List<VaultFileEntry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val results = mutableListOf<VaultFileEntry>()
        val resolver = appContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.IS_PENDING,
        )
        runCatching {
            resolver.query(
                collection,
                projection,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("%$PUBLIC_DOWNLOADS_SUBFOLDER%"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                val pendingIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.IS_PENDING)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val rowUri = ContentUris.withAppendedId(collection, id)
                    if (cursor.getInt(pendingIdx) == 1) {
                        runCatching { resolver.delete(rowUri, null, null) }
                        continue
                    }
                    val name = cursor.getString(nameIdx).orEmpty()
                    if (name.isBlank() ||
                        name.equals(NOMEDIA_FILE_NAME, ignoreCase = true) ||
                        name.endsWith(LEGACY_ENCRYPTED_EXT, ignoreCase = true)
                    ) {
                        continue
                    }
                    val mime = cursor.getString(mimeIdx)
                        ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                        ?: inferMimeFromFileName(name)
                        ?: "application/octet-stream"
                    val size = cursor.getLong(sizeIdx)
                    val modifiedMs = cursor.getLong(dateIdx) * 1000L
                    results.add(
                        VaultFileEntry(
                            id = "ms_$id",
                            fileName = name,
                            displayTitle = name,
                            sizeBytes = size,
                            lastModifiedMs = modifiedMs,
                            uriString = rowUri.toString(),
                            localFilePath = null,
                            isSafExternal = true,
                            mimeType = mime,
                            category = classifyCategory(name, mime),
                        ),
                    )
                }
            }
        }
        return results
    }

    fun moveMediaToVault(
        sourceUri: Uri,
        fallbackCategory: VaultCategory = VaultCategory.VIDEOS,
        deleteOriginal: Boolean = true,
    ): Flow<VaultTransferProgress> = flow {
        val meta = resolveSourceMetadata(sourceUri, fallbackCategory)
        val totalBytes = meta.originalSizeBytes.coerceAtLeast(1L)
        val sanitizedName = sanitizeTargetFileName(meta.originalName, fallbackCategory)

        emit(
            VaultTransferProgress(
                fraction = 0.04f,
                bytesProcessed = 0L,
                totalBytes = totalBytes,
                stage = VaultTransferStage.PREPARING,
                metadata = meta,
            ),
        )

        val sourceFile = resolveLocalFileFromUri(sourceUri)
        val activeMode = getActiveStorageModeBlocking()

        // Fast atomic local rename when source and target are plain Files on the same volume
        if (deleteOriginal && sourceFile != null && sourceFile.exists() && sourceFile.canWrite()) {
            val targetDir = when (activeMode) {
                VaultStorageMode.PHONE_DOWNLOADS -> {
                    if (hasAllFilesAccess()) {
                        hiddenExternalVaultDir.apply { ensureProtectedDir(this) }
                    } else {
                        null
                    }
                }
                VaultStorageMode.INTERNAL -> internalVaultDir
                VaultStorageMode.SAF_EXTERNAL -> null
            }
            if (targetDir != null) {
                val destFile = resolveUniqueFile(targetDir, sanitizedName)
                if (sourceFile.renameTo(destFile)) {
                    scanDeletedPath(sourceFile.absolutePath)
                    tryDeleteMediaStoreRow(sourceUri)
                    emit(
                        VaultTransferProgress(
                            fraction = 1f,
                            bytesProcessed = destFile.length(),
                            totalBytes = destFile.length().coerceAtLeast(totalBytes),
                            stage = VaultTransferStage.COMPLETED,
                            metadata = meta.copy(originalName = destFile.name),
                            originalDeleted = true,
                        ),
                    )
                    return@flow
                }
            }
        }

        val inputStream: InputStream = if (sourceFile != null && sourceFile.exists()) {
            sourceFile.inputStream()
        } else {
            appContext.contentResolver.openInputStream(sourceUri)
                ?: throw IllegalStateException("تعذر قراءة الملف المختار من الجهاز.")
        }

        var commitAction: suspend () -> String = { sanitizedName }
        var cleanupAction: suspend () -> Unit = {}
        val outStream: OutputStream

        when {
            activeMode == VaultStorageMode.SAF_EXTERNAL -> {
                val safUri = getSafTreeUriBlocking()
                    ?: run {
                        runCatching { inputStream.close() }
                        throw IllegalStateException("المجلد الخارجي (SAF) غير متاح.")
                    }
                val tree = DocumentFile.fromTreeUri(appContext, safUri)
                    ?: run {
                        runCatching { inputStream.close() }
                        throw IllegalStateException("المجلد الخارجي (SAF) غير متاح.")
                    }
                val uniqueName = resolveUniqueSafName(tree, sanitizedName)
                val stagingName = "$uniqueName$STAGING_SUFFIX"
                val stagingDoc = tree.createFile("application/octet-stream", stagingName)
                    ?: run {
                        runCatching { inputStream.close() }
                        throw IllegalStateException("تعذر إنشاء ملف النقل في المجلد المختار.")
                    }
                outStream = appContext.contentResolver.openOutputStream(stagingDoc.uri, "w")
                    ?: run {
                        runCatching { stagingDoc.delete() }
                        runCatching { inputStream.close() }
                        throw IllegalStateException("تعذر فتح المجلد الخارجي للكتابة.")
                    }
                commitAction = {
                    val renamed = runCatching { stagingDoc.renameTo(uniqueName) }.getOrDefault(false)
                    if (!renamed) {
                        val committedDoc = tree.createFile(meta.mimeType, uniqueName)
                            ?: throw IllegalStateException("تعذر حفظ الملف داخل المجلد الخارجي.")
                        appContext.contentResolver.openInputStream(stagingDoc.uri)?.use { src ->
                            appContext.contentResolver.openOutputStream(committedDoc.uri, "w")?.use { dst ->
                                src.copyTo(dst, 64 * 1024)
                                dst.flush()
                            }
                        }
                        runCatching { stagingDoc.delete() }
                    }
                    uniqueName
                }
                cleanupAction = {
                    runCatching { stagingDoc.delete() }
                }
            }

            activeMode == VaultStorageMode.PHONE_DOWNLOADS &&
                !hasAllFilesAccess() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                val resolver = appContext.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sanitizedName)
                    put(MediaStore.Downloads.MIME_TYPE, meta.mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DOWNLOADS_SUBFOLDER")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val pendingUri = resolver.insert(collection, values)
                    ?: run {
                        runCatching { inputStream.close() }
                        throw IllegalStateException("تعذر حفظ الملف في Download/$PUBLIC_DOWNLOADS_SUBFOLDER.")
                    }
                outStream = resolver.openOutputStream(pendingUri, "w")
                    ?: run {
                        runCatching { resolver.delete(pendingUri, null, null) }
                        runCatching { inputStream.close() }
                        throw IllegalStateException("تعذر فتح مجلد التنزيلات للكتابة.")
                    }
                commitAction = {
                    val commitValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(pendingUri, commitValues, null, null)
                    queryDisplayNameForUri(pendingUri) ?: sanitizedName
                }
                cleanupAction = {
                    runCatching { resolver.delete(pendingUri, null, null) }
                }
            }

            else -> {
                val targetDir = if (activeMode == VaultStorageMode.PHONE_DOWNLOADS && hasAllFilesAccess()) {
                    hiddenExternalVaultDir.apply { ensureProtectedDir(this) }
                } else {
                    internalVaultDir
                }
                val destFile = resolveUniqueFile(targetDir, sanitizedName)
                val stagingFile = File(targetDir, ".${destFile.name}$STAGING_SUFFIX")
                outStream = try {
                    stagingFile.outputStream()
                } catch (t: Throwable) {
                    runCatching { inputStream.close() }
                    throw t
                }
                commitAction = {
                    if (destFile.exists()) destFile.delete()
                    if (!stagingFile.renameTo(destFile)) {
                        stagingFile.copyTo(destFile, overwrite = true)
                        stagingFile.delete()
                    }
                    destFile.name
                }
                cleanupAction = {
                    runCatching { stagingFile.delete() }
                }
            }
        }

        val buffer = ByteArray(128 * 1024)
        var bytesCopied = 0L
        var committed = false

        try {
            BufferedInputStream(inputStream, 64 * 1024).use { bufferedIn ->
                BufferedOutputStream(outStream, 64 * 1024).use { bufferedOut ->
                    while (true) {
                        val read = bufferedIn.read(buffer)
                        if (read == -1) break
                        bufferedOut.write(buffer, 0, read)
                        bytesCopied += read
                        val fraction = (0.06f + 0.88f * (bytesCopied.toFloat() / totalBytes.toFloat()))
                            .coerceIn(0.06f, 0.94f)
                        emit(
                            VaultTransferProgress(
                                fraction = fraction,
                                bytesProcessed = bytesCopied,
                                totalBytes = totalBytes,
                                stage = VaultTransferStage.MOVING,
                                metadata = meta,
                            ),
                        )
                    }
                    bufferedOut.flush()
                }
            }

            emit(
                VaultTransferProgress(
                    fraction = 0.97f,
                    bytesProcessed = bytesCopied,
                    totalBytes = totalBytes,
                    stage = VaultTransferStage.FINALIZING,
                    metadata = meta,
                ),
            )

            val committedName = commitAction()
            committed = true

            val originalRemoved = if (deleteOriginal) {
                deleteOriginalSource(sourceUri, sourceFile)
            } else {
                false
            }

            emit(
                VaultTransferProgress(
                    fraction = 1f,
                    bytesProcessed = bytesCopied,
                    totalBytes = bytesCopied.coerceAtLeast(totalBytes),
                    stage = VaultTransferStage.COMPLETED,
                    metadata = meta.copy(originalName = committedName),
                    originalDeleted = originalRemoved,
                ),
            )
        } catch (t: Throwable) {
            if (!committed) {
                withContext(NonCancellable) {
                    runCatching { outStream.close() }
                    cleanupAction()
                }
            }
            if (t is CancellationException) throw t
            throw t
        } finally {
            runCatching { inputStream.close() }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun restoreEntryToDevice(entry: VaultFileEntry): Boolean = withContext(Dispatchers.IO) {
        val publicDir = when (entry.category) {
            VaultCategory.PHOTOS -> Environment.DIRECTORY_PICTURES
            VaultCategory.VIDEOS -> Environment.DIRECTORY_MOVIES
            VaultCategory.FILES -> Environment.DIRECTORY_DOWNLOADS
        }
        val entryUri = Uri.parse(entry.uriString)
        val inputStream = entry.localFilePath?.let { File(it) }?.takeIf { it.exists() }?.inputStream()
            ?: runCatching { appContext.contentResolver.openInputStream(entryUri) }.getOrNull()
            ?: return@withContext false

        val restoredPath: String? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasAllFilesAccess()) {
                val resolver = appContext.contentResolver
                val collection = when (entry.category) {
                    VaultCategory.PHOTOS -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    VaultCategory.VIDEOS -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    VaultCategory.FILES -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, entry.fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDir/$RESTORED_SUBFOLDER")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val outUri = resolver.insert(collection, values) ?: return@withContext false
                val copied = runCatching {
                    resolver.openOutputStream(outUri, "w")?.use { dst ->
                        inputStream.use { src -> src.copyTo(dst, 64 * 1024) }
                        dst.flush()
                    } ?: error("Output stream null")
                    resolver.update(outUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }.isSuccess
                if (!copied) {
                    runCatching { resolver.delete(outUri, null, null) }
                    return@withContext false
                }
                null
            } else {
                @Suppress("DEPRECATION")
                val targetDir = File(
                    Environment.getExternalStoragePublicDirectory(publicDir),
                    RESTORED_SUBFOLDER,
                ).apply { if (!exists()) mkdirs() }
                val destFile = resolveUniqueFile(targetDir, entry.fileName)
                inputStream.use { src ->
                    destFile.outputStream().use { dst ->
                        src.copyTo(dst, 64 * 1024)
                        dst.flush()
                    }
                }
                destFile.absolutePath
            }
        } catch (_: Throwable) {
            runCatching { inputStream.close() }
            return@withContext false
        }

        if (restoredPath != null) {
            MediaScannerConnection.scanFile(appContext, arrayOf(restoredPath), arrayOf(entry.mimeType), null)
        }
        deleteEntry(entry)
        true
    }

    suspend fun deleteEntry(entry: VaultFileEntry): Boolean = withContext(Dispatchers.IO) {
        val localFile = entry.localFilePath?.let(::File)
        if (localFile != null && localFile.exists()) {
            return@withContext runCatching { localFile.delete() }.getOrDefault(false)
        }
        val uri = Uri.parse(entry.uriString)
        if (uri.scheme.equals("content", ignoreCase = true)) {
            val directDelete = runCatching {
                appContext.contentResolver.delete(uri, null, null) > 0
            }.getOrDefault(false)
            if (directDelete) true else {
                runCatching {
                    DocumentFile.fromSingleUri(appContext, uri)?.delete() == true
                }.getOrDefault(false)
            }
        } else {
            runCatching {
                val file = File(uri.path ?: return@runCatching false)
                file.delete()
            }.getOrDefault(false)
        }
    }

    suspend fun listBlackholeDownloadedVideos(): List<File> = withContext(Dispatchers.IO) {
        val downloadsDir = File(appContext.filesDir, "blackhole_videos")
        if (!downloadsDir.exists() || !downloadsDir.isDirectory) return@withContext emptyList()
        (downloadsDir.listFiles()?.toList() ?: emptyList())
            .filter { file ->
                file.isFile && file.length() > 0L && !file.name.endsWith(STAGING_SUFFIX, ignoreCase = true)
            }
            .sortedByDescending { it.lastModified() }
    }

    private fun deleteOriginalSource(sourceUri: Uri, resolvedFile: File?): Boolean {
        var deleted = false
        if (resolvedFile != null && resolvedFile.exists()) {
            val path = resolvedFile.absolutePath
            if (runCatching { resolvedFile.delete() }.getOrDefault(false) || !resolvedFile.exists()) {
                deleted = true
                scanDeletedPath(path)
            }
        }
        if (tryDeleteMediaStoreRow(sourceUri)) {
            deleted = true
        }
        if (!deleted && DocumentsContract.isDocumentUri(appContext, sourceUri)) {
            val docDeleted = runCatching {
                DocumentsContract.deleteDocument(appContext.contentResolver, sourceUri)
            }.getOrDefault(false)
            if (docDeleted) deleted = true
        }
        return deleted
    }

    private fun tryDeleteMediaStoreRow(uri: Uri): Boolean {
        if (!uri.scheme.equals("content", ignoreCase = true)) return false
        return runCatching {
            appContext.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    private fun scanDeletedPath(path: String) {
        runCatching {
            MediaScannerConnection.scanFile(appContext, arrayOf(path), null, null)
        }
    }

    private fun resolveLocalFileFromUri(uri: Uri): File? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri.path?.let(::File)?.takeIf { it.exists() }
        }
        if (!uri.scheme.equals("content", ignoreCase = true)) return null

        // 1. ExternalStorageDocuments document URI (e.g., primary:DCIM/Camera/vid.mp4)
        if (DocumentsContract.isDocumentUri(appContext, uri)) {
            val authority = uri.authority.orEmpty()
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
            if (authority == "com.android.externalstorage.documents" && docId.contains(":")) {
                val type = docId.substringBefore(":")
                val relPath = docId.substringAfter(":")
                if (type.equals("primary", ignoreCase = true)) {
                    @Suppress("DEPRECATION")
                    val candidate = File(Environment.getExternalStorageDirectory(), relPath)
                    if (candidate.exists()) return candidate
                }
            } else if (authority == "com.android.providers.downloads.documents" && docId.startsWith("raw:")) {
                val candidate = File(docId.removePrefix("raw:"))
                if (candidate.exists()) return candidate
            } else if (authority == "com.android.providers.media.documents" && docId.contains(":")) {
                val mediaType = docId.substringBefore(":")
                val idNum = docId.substringAfter(":").toLongOrNull()
                if (idNum != null) {
                    val contentUri = when (mediaType) {
                        "image" -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, idNum)
                        "video" -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, idNum)
                        "audio" -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, idNum)
                        else -> null
                    }
                    contentUri?.let { queryDataColumnFile(it) }?.let { return it }
                }
            }
        }

        // 2. Direct MediaStore or File-backed content URI _data lookup
        return queryDataColumnFile(uri)
    }

    private fun queryDataColumnFile(uri: Uri): File? {
        return runCatching {
            @Suppress("DEPRECATION")
            val column = MediaStore.MediaColumns.DATA
            appContext.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(column)
                    if (idx >= 0) {
                        val path = cursor.getString(idx)
                        if (!path.isNullOrBlank()) {
                            return@runCatching File(path).takeIf { it.exists() }
                        }
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun resolveUniqueFile(dir: File, desiredName: String): File {
        var candidate = File(dir, desiredName)
        if (!candidate.exists()) return candidate
        val base = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "")
        var idx = 1
        while (candidate.exists()) {
            val nextName = if (ext.isNotEmpty()) "${base}_$idx.$ext" else "${base}_$idx"
            candidate = File(dir, nextName)
            idx++
        }
        return candidate
    }

    private fun resolveUniqueSafName(tree: DocumentFile, desiredName: String): String {
        val existingNames = runCatching {
            tree.listFiles().mapNotNull { it.name }.toHashSet()
        }.getOrDefault(emptySet())
        if (desiredName !in existingNames) return desiredName
        val base = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "")
        var idx = 1
        while (true) {
            val nextName = if (ext.isNotEmpty()) "${base}_$idx.$ext" else "${base}_$idx"
            if (nextName !in existingNames) return nextName
            idx++
        }
    }

    private fun sanitizeTargetFileName(rawName: String, fallbackCategory: VaultCategory): String {
        val cleaned = rawName.replace(Regex("""[/\\:*?"<>|]"""), "_").trim()
        if (cleaned.isNotBlank() && !cleaned.startsWith(".")) return cleaned
        val ext = when (fallbackCategory) {
            VaultCategory.PHOTOS -> "jpg"
            VaultCategory.VIDEOS -> "mp4"
            VaultCategory.FILES -> "bin"
        }
        return "vault_${System.currentTimeMillis()}.$ext"
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

    private fun resolveSourceMetadata(
        uri: Uri,
        fallbackCategory: VaultCategory,
    ): VaultMediaMetadata {
        val defaultExt = when (fallbackCategory) {
            VaultCategory.PHOTOS -> "jpg"
            VaultCategory.VIDEOS -> "mp4"
            VaultCategory.FILES -> "bin"
        }
        val defaultMime = when (fallbackCategory) {
            VaultCategory.PHOTOS -> "image/jpeg"
            VaultCategory.VIDEOS -> "video/mp4"
            VaultCategory.FILES -> "application/octet-stream"
        }
        val defaultPrefix = when (fallbackCategory) {
            VaultCategory.PHOTOS -> "vault_photo"
            VaultCategory.VIDEOS -> "vault_video"
            VaultCategory.FILES -> "vault_file"
        }

        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.let(::File)
            val name = file?.name?.takeIf { it.isNotBlank() }
                ?: "${defaultPrefix}_${System.currentTimeMillis()}.$defaultExt"
            val size = file?.takeIf { it.exists() }?.length() ?: 0L
            val mime = inferMimeFromFileName(name) ?: defaultMime
            return VaultMediaMetadata(
                originalName = name,
                mimeType = mime,
                originalSizeBytes = size,
            )
        }
        var displayName = "${defaultPrefix}_${System.currentTimeMillis()}.$defaultExt"
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
        val resolvedMime = runCatching { appContext.contentResolver.getType(uri) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: inferMimeFromFileName(displayName)
            ?: defaultMime
        return VaultMediaMetadata(
            originalName = displayName,
            mimeType = resolvedMime,
            originalSizeBytes = sizeBytes,
        )
    }

    companion object {
        private const val VAULT_PREFS_NAME = "blackhole_vault_saf_prefs"
        private const val KEY_SAF_TREE_URI = "KEY_SAF_TREE_URI"
        private const val KEY_STORAGE_MODE = "KEY_STORAGE_MODE"
        private const val INTERNAL_VAULT_FOLDER = "blackhole_vault"
        private const val HIDDEN_EXTERNAL_VAULT_FOLDER = ".BlackholeVault"
        private const val PUBLIC_DOWNLOADS_SUBFOLDER = "BlackholeVault"
        private const val RESTORED_SUBFOLDER = "BlackholeRestored"
        private const val NOMEDIA_FILE_NAME = ".nomedia"
        private const val LEGACY_ENCRYPTED_EXT = ".bhv"
        private const val STAGING_SUFFIX = ".tmp"

        private val PHOTO_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif",
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi", "ts", "flv",
        )

        fun inferMimeFromFileName(fileName: String): String? {
            return when (fileName.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "heic" -> "image/heic"
                "heif" -> "image/heif"
                "bmp" -> "image/bmp"
                "avif" -> "image/avif"
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                "3gp" -> "video/3gpp"
                "avi" -> "video/x-msvideo"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "txt", "log", "md" -> "text/plain"
                "json" -> "application/json"
                "csv" -> "text/csv"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                else -> null
            }
        }

        fun classifyCategory(title: String, mimeType: String): VaultCategory {
            val lowerMime = mimeType.lowercase()
            val ext = title.substringAfterLast('.', "").lowercase()
            return when {
                lowerMime.startsWith("image/") || ext in PHOTO_EXTENSIONS -> VaultCategory.PHOTOS
                lowerMime.startsWith("video/") || ext in VIDEO_EXTENSIONS -> VaultCategory.VIDEOS
                else -> VaultCategory.FILES
            }
        }
    }
}
