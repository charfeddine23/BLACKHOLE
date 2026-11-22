package com.example.vault.data

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
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
import com.example.data.local.BlackholeDatabase
import com.example.data.local.DownloadedVideoEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    val recoverableDeleteSender: IntentSender? = null,
    val resolvedOriginalPath: String? = null,
    val resolvedMediaUris: List<String> = emptyList(),
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
    val isEncrypted: Boolean = true,
)

data class PendingOriginalDeletion(
    val uriString: String,
    val filePath: String?,
    val displayName: String,
    val category: VaultCategory,
    val resolvedMediaUris: List<String> = emptyList(),
)

private data class OriginalDeleteResult(
    val deleted: Boolean,
    val recoverableSender: IntentSender? = null,
    val resolvedPath: String? = null,
    val resolvedMediaUris: List<Uri> = emptyList(),
)

class SafVaultRepository(
    context: Context,
    val cipher: VaultCipher = VaultCipher(context.applicationContext),
) {

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

    fun hasMediaReadAccess(): Boolean {
        if (hasAllFilesAccess()) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return true
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_MEDIA_IMAGES,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.READ_MEDIA_VIDEO,
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun buildMediaStoreDeleteSender(uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val canonicalMediaUris = uris.flatMap { uri ->
            val resolvedFile = resolveLocalFileFromUri(uri)
            val meta = runCatching { resolveSourceMetadata(uri, VaultCategory.VIDEOS) }.getOrNull()
            resolveDeletableMediaStoreUris(uri, resolvedFile, meta)
        }.distinct()

        if (canonicalMediaUris.isEmpty()) return null
        return runCatching {
            MediaStore.createDeleteRequest(appContext.contentResolver, canonicalMediaUris).intentSender
        }.getOrNull()
    }

    suspend fun forceDeletePendingOriginal(item: PendingOriginalDeletion): Boolean =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(item.uriString)
            val resolvedFile = item.filePath?.let(::File)?.takeIf { it.exists() }
                ?: resolveLocalFileFromUri(uri)
            val extraMeta = VaultMediaMetadata(
                originalName = item.displayName,
                mimeType = inferMimeFromFileName(item.displayName) ?: "video/mp4",
                originalSizeBytes = resolvedFile?.length() ?: 0L,
            )
            deleteOriginalSource(uri, resolvedFile, extraMeta).deleted
        }

    suspend fun cleanupMediaStoreAfterSystemDelete(uris: List<Uri>) = withContext(Dispatchers.IO) {
        val pathsToClean = LinkedHashSet<String>()
        for (uri in uris) {
            val resolvedFile = resolveLocalFileFromUri(uri)
            if (resolvedFile != null) {
                expandPathAliases(resolvedFile.absolutePath).forEach { pathsToClean.add(it) }
            }
            cleanupMatchingBlackholeDownloads(uri, resolvedFile, null)
        }
        for (path in pathsToClean) {
            if (!File(path).exists()) {
                deleteMediaStoreRowsByPath(path)
            }
        }
        val missingPaths = pathsToClean.filter { !File(it).exists() }
        if (missingPaths.isNotEmpty()) {
            scanDeletedPathsAndWait(missingPaths)
        }
        notifyMediaStoreChanged()
    }

    suspend fun isMediaOriginalGone(uriString: String, filePath: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (!filePath.isNullOrBlank() && File(filePath).exists()) {
                return@withContext false
            }
            val uri = Uri.parse(uriString)
            val resolvedFile = resolveLocalFileFromUri(uri)
            if (resolvedFile != null && resolvedFile.exists()) {
                return@withContext false
            }
            val canonicalUris = resolveDeletableMediaStoreUris(uri, resolvedFile, null)
            !hasRemainingMediaStoreEntry(canonicalUris, filePath ?: resolvedFile?.absolutePath)
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
                    "مجلد SAF مشفر: $docName (.bhlock)"
                } else {
                    "مجلد SAF مشفر (.bhlock)"
                }
            }
            VaultStorageMode.PHONE_DOWNLOADS -> {
                "خزنة الهاتف المخفية المشفرة ($HIDDEN_EXTERNAL_VAULT_FOLDER)"
            }
            VaultStorageMode.INTERNAL -> {
                "خزنة التطبيق المشفرة المعزولة (AES-256)"
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
        if (!hasAllFilesAccess()) {
            return@withContext false
        }
        ensureProtectedDir(hiddenExternalVaultDir)
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
                            .mapNotNull { doc -> buildSafVaultEntry(doc) }
                            .sortedByDescending { it.lastModifiedMs }
                    }
                }
            }

            VaultStorageMode.PHONE_DOWNLOADS -> {
                if (hasAllFilesAccess()) {
                    val dir = hiddenExternalVaultDir.apply { ensureProtectedDir(this) }
                    return@withContext listDirectoryEntries(dir, isExternalFlag = true)
                }
            }

            VaultStorageMode.INTERNAL -> Unit
        }

        val dir = internalVaultDir
        listDirectoryEntries(dir, isExternalFlag = false)
    }

    private fun buildSafVaultEntry(doc: DocumentFile): VaultFileEntry? {
        val rawName = doc.name ?: return null
        val header = if (rawName.endsWith(VaultCipher.ENCRYPTED_VAULT_EXT, ignoreCase = true)) {
            cipher.readHeader(doc.uri)
        } else {
            null
        }

        val displayTitle = header?.metadata?.n ?: rawName
        val mime = header?.metadata?.m?.takeIf { it.isNotBlank() }
            ?: doc.type?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: inferMimeFromFileName(displayTitle)
            ?: "application/octet-stream"
        val category = header?.category ?: classifyCategory(displayTitle, mime)
        val sizeBytes = header?.plaintextSize?.takeIf { it > 0L } ?: doc.length()

        return VaultFileEntry(
            id = doc.uri.toString().hashCode().toString(),
            fileName = rawName,
            displayTitle = displayTitle,
            sizeBytes = sizeBytes,
            lastModifiedMs = doc.lastModified(),
            uriString = doc.uri.toString(),
            localFilePath = null,
            isSafExternal = true,
            mimeType = mime,
            category = category,
            isEncrypted = header != null,
        )
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
                val header = cipher.readHeader(file)
                val displayTitle = header?.metadata?.n ?: file.name
                val mime = header?.metadata?.m?.takeIf { it.isNotBlank() }
                    ?: inferMimeFromFileName(displayTitle)
                    ?: "application/octet-stream"
                val category = header?.category ?: classifyCategory(displayTitle, mime)
                val sizeBytes = header?.plaintextSize?.takeIf { it > 0L } ?: file.length()
                VaultFileEntry(
                    id = file.absolutePath.hashCode().toString(),
                    fileName = file.name,
                    displayTitle = displayTitle,
                    sizeBytes = sizeBytes,
                    lastModifiedMs = file.lastModified(),
                    uriString = Uri.fromFile(file).toString(),
                    localFilePath = file.absolutePath,
                    isSafExternal = isExternalFlag,
                    mimeType = mime,
                    category = category,
                    isEncrypted = header != null,
                )
            }
            .sortedByDescending { it.lastModifiedMs }
    }

    fun moveMediaToVault(
        sourceUri: Uri,
        fallbackCategory: VaultCategory = VaultCategory.VIDEOS,
        deleteOriginal: Boolean = true,
    ): Flow<VaultTransferProgress> = channelFlow {
        val meta = resolveSourceMetadata(sourceUri, fallbackCategory)
        val sanitizedOriginalName = sanitizeTargetFileName(meta.originalName, fallbackCategory)
        val resolvedCategory = classifyCategory(sanitizedOriginalName, meta.mimeType)
        val totalBytes = meta.originalSizeBytes.coerceAtLeast(1L)
        val sourceFile = resolveLocalFileFromUri(sourceUri)

        send(
            VaultTransferProgress(
                fraction = 0.04f,
                bytesProcessed = 0L,
                totalBytes = totalBytes,
                stage = VaultTransferStage.PREPARING,
                metadata = meta.copy(originalName = sanitizedOriginalName),
                resolvedOriginalPath = sourceFile?.absolutePath,
            ),
        )

        val encryptedFileName = generateEncryptedVaultFileName()
        val activeMode = getActiveStorageModeBlocking()

        val inputStream: InputStream = if (sourceFile != null && sourceFile.exists() && sourceFile.canRead()) {
            sourceFile.inputStream()
        } else {
            appContext.contentResolver.openInputStream(sourceUri)
                ?: throw IllegalStateException("تعذر قراءة الملف المختار من الجهاز.")
        }

        var commitAction: suspend (Long) -> Unit = {}
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
                val stagingName = "$encryptedFileName$STAGING_SUFFIX"
                val stagingDoc = tree.createFile("application/octet-stream", stagingName)
                    ?: run {
                        runCatching { inputStream.close() }
                        throw IllegalStateException("تعذر إنشاء ملف التشفير في المجلد المختار.")
                    }
                outStream = appContext.contentResolver.openOutputStream(stagingDoc.uri, "w")
                    ?: run {
                        runCatching { stagingDoc.delete() }
                        runCatching { inputStream.close() }
                        throw IllegalStateException("تعذر فتح المجلد الخارجي للكتابة.")
                    }
                commitAction = { _ ->
                    val renamed = runCatching { stagingDoc.renameTo(encryptedFileName) }.getOrDefault(false)
                    if (!renamed) {
                        val committedDoc = tree.createFile("application/octet-stream", encryptedFileName)
                            ?: throw IllegalStateException("تعذر حفظ الملف المشفر داخل المجلد الخارجي.")
                        appContext.contentResolver.openInputStream(stagingDoc.uri)?.use { src ->
                            appContext.contentResolver.openOutputStream(committedDoc.uri, "w")?.use { dst ->
                                src.copyTo(dst, 64 * 1024)
                                dst.flush()
                            }
                        }
                        runCatching { stagingDoc.delete() }
                    }
                }
                cleanupAction = {
                    runCatching { stagingDoc.delete() }
                }
            }

            else -> {
                val targetDir = if (activeMode == VaultStorageMode.PHONE_DOWNLOADS && hasAllFilesAccess()) {
                    hiddenExternalVaultDir.apply { ensureProtectedDir(this) }
                } else {
                    internalVaultDir
                }
                val destFile = resolveUniqueFile(targetDir, encryptedFileName)
                val stagingFile = File(targetDir, ".${destFile.name}$STAGING_SUFFIX")
                outStream = try {
                    stagingFile.outputStream()
                } catch (t: Throwable) {
                    runCatching { inputStream.close() }
                    throw t
                }
                commitAction = { actualPlainBytes ->
                    cipher.patchPlaintextSizeInFile(stagingFile, actualPlainBytes)
                    if (destFile.exists()) destFile.delete()
                    if (!stagingFile.renameTo(destFile)) {
                        stagingFile.copyTo(destFile, overwrite = true)
                        stagingFile.delete()
                    }
                    if (!destFile.exists() || destFile.length() <= 32L) {
                        throw IllegalStateException("فشل حفظ النسخة المشفرة داخل الخزنة.")
                    }
                }
                cleanupAction = {
                    runCatching { stagingFile.delete() }
                }
            }
        }

        var committed = false
        var bytesEncrypted = 0L

        try {
            BufferedInputStream(inputStream, 64 * 1024).use { bufferedIn ->
                BufferedOutputStream(outStream, 64 * 1024).use { bufferedOut ->
                    val headerMeta = VaultHeaderMetadata(
                        n = sanitizedOriginalName,
                        m = meta.mimeType,
                    )
                    var lastEmittedBytes = 0L
                    bytesEncrypted = cipher.encryptStreamToOutput(
                        inputStream = bufferedIn,
                        outputStream = bufferedOut,
                        metadata = headerMeta,
                        category = resolvedCategory,
                        expectedPlaintextSize = meta.originalSizeBytes,
                        onBytesRead = { processed ->
                            if (processed - lastEmittedBytes >= 256 * 1024 || processed >= totalBytes) {
                                lastEmittedBytes = processed
                                val fraction = (0.06f + 0.86f * (processed.toFloat() / totalBytes.toFloat()))
                                    .coerceIn(0.06f, 0.92f)
                                trySend(
                                    VaultTransferProgress(
                                        fraction = fraction,
                                        bytesProcessed = processed,
                                        totalBytes = totalBytes,
                                        stage = VaultTransferStage.MOVING,
                                        metadata = meta.copy(originalName = sanitizedOriginalName),
                                        resolvedOriginalPath = sourceFile?.absolutePath,
                                    ),
                                )
                            }
                        },
                    )
                }
            }

            // Emit smooth final progress step before committing
            send(
                VaultTransferProgress(
                    fraction = 0.95f,
                    bytesProcessed = bytesEncrypted,
                    totalBytes = bytesEncrypted.coerceAtLeast(totalBytes),
                    stage = VaultTransferStage.FINALIZING,
                    metadata = meta.copy(originalName = sanitizedOriginalName),
                    resolvedOriginalPath = sourceFile?.absolutePath,
                ),
            )

            commitAction(bytesEncrypted)
            committed = true

            val normalizedMeta = meta.copy(originalName = sanitizedOriginalName)
            val deleteResult = if (deleteOriginal) {
                deleteOriginalSource(sourceUri, sourceFile, normalizedMeta)
            } else {
                OriginalDeleteResult(deleted = false, resolvedPath = sourceFile?.absolutePath)
            }

            send(
                VaultTransferProgress(
                    fraction = 1f,
                    bytesProcessed = bytesEncrypted,
                    totalBytes = bytesEncrypted.coerceAtLeast(totalBytes),
                    stage = VaultTransferStage.COMPLETED,
                    metadata = normalizedMeta,
                    originalDeleted = deleteResult.deleted,
                    recoverableDeleteSender = deleteResult.recoverableSender,
                    resolvedOriginalPath = deleteResult.resolvedPath ?: sourceFile?.absolutePath,
                    resolvedMediaUris = deleteResult.resolvedMediaUris.map { it.toString() },
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
        val localFile = entry.localFilePath?.let(::File)?.takeIf { it.exists() }
        val decryptedStream = try {
            if (localFile != null) {
                cipher.openDecryptedInputStream(localFile)
            } else {
                cipher.openDecryptedInputStream(entryUri)
            }
        } catch (_: Throwable) {
            return@withContext false
        }

        val restoreFileName = entry.displayTitle.ifBlank { entry.fileName }
        val restoredPath: String? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasAllFilesAccess()) {
                val resolver = appContext.contentResolver
                val collection = when (entry.category) {
                    VaultCategory.PHOTOS -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    VaultCategory.VIDEOS -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    VaultCategory.FILES -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, restoreFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDir/$RESTORED_SUBFOLDER")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val outUri = resolver.insert(collection, values) ?: run {
                    runCatching { decryptedStream.close() }
                    return@withContext false
                }
                val copied = runCatching {
                    resolver.openOutputStream(outUri, "w")?.use { dst ->
                        decryptedStream.use { src -> src.copyTo(dst, 64 * 1024) }
                        dst.flush()
                    } ?: error("Output stream null")
                    resolver.update(
                        outUri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
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
                val destFile = resolveUniqueFile(targetDir, restoreFileName)
                decryptedStream.use { src ->
                    destFile.outputStream().use { dst ->
                        src.copyTo(dst, 64 * 1024)
                        dst.flush()
                    }
                }
                destFile.absolutePath
            }
        } catch (_: Throwable) {
            runCatching { decryptedStream.close() }
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

    private suspend fun deleteOriginalSource(
        sourceUri: Uri,
        resolvedFile: File?,
        metadata: VaultMediaMetadata?,
    ): OriginalDeleteResult {
        val resolvedPath = resolvedFile?.absolutePath ?: queryDataColumnPath(sourceUri)

        // Step 0: Always purge any linked Blackhole downloaded video record + exported MediaStore copy
        val linkedGalleryUris = cleanupMatchingBlackholeDownloads(sourceUri, resolvedFile, metadata)

        // Collect all canonical MediaStore URIs BEFORE deleting the physical file so we can verify & purge them
        val preDeleteCanonicalUris = (resolveDeletableMediaStoreUris(sourceUri, resolvedFile, metadata) + linkedGalleryUris)
            .distinct()

        // Step 1: Attempt direct physical deletion (succeeds whenever All Files Access or app-owned storage applies)
        var physicalFileDeleted = false
        if (resolvedFile != null) {
            if (!resolvedFile.exists()) {
                physicalFileDeleted = true
            } else {
                runCatching { resolvedFile.delete() }
                physicalFileDeleted = !resolvedFile.exists()
            }
        }

        // Step 2: Attempt direct ContentResolver deletion on every canonical MediaStore URI & path aliases
        var capturedRecoverableSender: IntentSender? = null
        for (targetUri in preDeleteCanonicalUris) {
            val (_, sender) = tryDeleteMediaStoreRowWithRecovery(targetUri)
            if (capturedRecoverableSender == null && sender != null) {
                capturedRecoverableSender = sender
            }
        }

        if (sourceUri.scheme.equals("content", ignoreCase = true) && sourceUri !in preDeleteCanonicalUris) {
            val (_, sender) = tryDeleteMediaStoreRowWithRecovery(sourceUri)
            if (capturedRecoverableSender == null && sender != null) {
                capturedRecoverableSender = sender
            }
        }

        if (resolvedPath != null && (physicalFileDeleted || resolvedFile == null)) {
            deleteMediaStoreRowsByPath(resolvedPath)
        }

        // Step 3: Attempt SAF DocumentsContract deletion for document provider URIs if file still present
        if (!physicalFileDeleted && DocumentsContract.isDocumentUri(appContext, sourceUri)) {
            runCatching {
                DocumentsContract.deleteDocument(appContext.contentResolver, sourceUri)
            }
            if (resolvedFile != null && !resolvedFile.exists()) {
                physicalFileDeleted = true
                if (resolvedPath != null) {
                    deleteMediaStoreRowsByPath(resolvedPath)
                }
            }
        }

        // Step 4: If physical file was deleted, synchronously ask MediaScanner to drop ONLY the deleted file paths
        if (resolvedPath != null && (resolvedFile == null || !resolvedFile.exists())) {
            val aliases = expandPathAliases(resolvedPath)
            scanDeletedPathsAndWait(aliases)
            notifyMediaStoreChanged()
        }

        // Step 5: Verify BOTH physical disk AND MediaStore Gallery index are truly clean
        val fileStillOnDisk = resolvedFile?.exists() == true
        val mediaRowStillExists = hasRemainingMediaStoreEntry(preDeleteCanonicalUris, resolvedPath)

        if (!fileStillOnDisk && !mediaRowStillExists) {
            // Confirm for content:// URIs without a resolvedFile that the stream is no longer readable
            if (resolvedFile != null || preDeleteCanonicalUris.isNotEmpty() || !isContentUriReadable(sourceUri)) {
                return OriginalDeleteResult(
                    deleted = true,
                    resolvedPath = resolvedPath,
                    resolvedMediaUris = preDeleteCanonicalUris,
                )
            }
        }

        return OriginalDeleteResult(
            deleted = false,
            recoverableSender = capturedRecoverableSender,
            resolvedPath = resolvedPath,
            resolvedMediaUris = preDeleteCanonicalUris,
        )
    }

    private suspend fun cleanupMatchingBlackholeDownloads(
        sourceUri: Uri,
        resolvedFile: File?,
        metadata: VaultMediaMetadata?,
    ): List<Uri> {
        val discoveredGalleryUris = mutableListOf<Uri>()
        runCatching {
            val dao = BlackholeDatabase.getInstance(appContext).downloadedVideoDao()
            val allVideos: List<DownloadedVideoEntity> = dao.getAllVideosSnapshot()
            val sourceUriStr = sourceUri.toString()
            val sourceAliases = resolvedFile?.absolutePath?.let { expandPathAliases(it) }.orEmpty().toSet()
            val sourceName = resolvedFile?.name ?: metadata?.originalName
            val sourceSize = resolvedFile?.takeIf { it.exists() }?.length() ?: metadata?.originalSizeBytes ?: 0L

            for (entity in allVideos) {
                val entityFileAliases = entity.filePath.takeIf { it.isNotBlank() }?.let { expandPathAliases(it) }.orEmpty()
                val pathMatches = sourceAliases.isNotEmpty() && entityFileAliases.any { it in sourceAliases }
                val uriMatches = entity.contentUri == sourceUriStr
                val downloadedPairMatches = !sourceName.isNullOrBlank() &&
                    (entity.filePath.endsWith("/$sourceName") ||
                        (sourceAliases.any { it.contains("/Movies/Blackhole/", ignoreCase = true) } &&
                            File(entity.filePath).name == sourceName &&
                            (sourceSize <= 0L || entity.fileSizeBytes == sourceSize)))

                if (pathMatches || uriMatches || downloadedPairMatches) {
                    val galleryUri = runCatching { Uri.parse(entity.contentUri) }.getOrNull()
                    if (galleryUri != null && galleryUri.scheme.equals("content", ignoreCase = true)) {
                        discoveredGalleryUris.add(galleryUri)
                        val deletedGallery = runCatching {
                            appContext.contentResolver.delete(galleryUri, null, null) > 0
                        }.getOrDefault(false)
                        if (!deletedGallery) {
                            queryDataColumnPath(galleryUri)?.let { exportedPath ->
                                runCatching { File(exportedPath).delete() }
                                deleteMediaStoreRowsByPath(exportedPath)
                                scanDeletedPathsAndWait(expandPathAliases(exportedPath))
                            }
                        }
                    }
                    if (entity.filePath.isNotBlank()) {
                        runCatching { File(entity.filePath).delete() }
                    }
                    if (!entity.thumbnailPath.isNullOrBlank()) {
                        runCatching { File(entity.thumbnailPath).delete() }
                    }
                    dao.deleteVideoById(entity.id)
                }
            }
        }
        return discoveredGalleryUris
    }

    private fun isContentUriReadable(uri: Uri): Boolean {
        if (!uri.scheme.equals("content", ignoreCase = true)) return false
        return runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun hasRemainingMediaStoreEntry(
        canonicalUris: List<Uri>,
        resolvedPath: String?,
    ): Boolean {
        val resolver = appContext.contentResolver
        for (uri in canonicalUris) {
            val exists = runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { c ->
                    c.moveToFirst()
                } ?: false
            }.getOrDefault(false)
            if (exists) return true
        }
        if (!resolvedPath.isNullOrBlank()) {
            val aliases = expandPathAliases(resolvedPath)
            val placeholders = aliases.joinToString(",") { "?" }
            @Suppress("DEPRECATION")
            val selection = "${MediaStore.MediaColumns.DATA} IN ($placeholders)"
            val args = aliases.toTypedArray()
            val mediaTables = listOf(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            )
            for (table in mediaTables) {
                val found = runCatching {
                    resolver.query(table, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { c ->
                        c.moveToFirst()
                    } ?: false
                }.getOrDefault(false)
                if (found) return true
            }
        }
        return false
    }

    private fun tryDeleteMediaStoreRowWithRecovery(uri: Uri): Pair<Boolean, IntentSender?> {
        if (!uri.scheme.equals("content", ignoreCase = true)) return false to null
        return try {
            val deletedRows = appContext.contentResolver.delete(uri, null, null)
            (deletedRows > 0) to null
        } catch (securityEx: SecurityException) {
            val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                securityEx is RecoverableSecurityException
            ) {
                securityEx.userAction.actionIntent.intentSender
            } else {
                null
            }
            false to sender
        } catch (_: Throwable) {
            false to null
        }
    }

    private fun deleteMediaStoreRowsByPath(path: String) {
        val resolver = appContext.contentResolver
        val aliases = expandPathAliases(path)
        val placeholders = aliases.joinToString(",") { "?" }
        @Suppress("DEPRECATION")
        val selection = "${MediaStore.MediaColumns.DATA} IN ($placeholders)"
        val args = aliases.toTypedArray()
        val collections = buildList {
            add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            add(MediaStore.Files.getContentUri("external"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
        }
        for (collection in collections) {
            runCatching { resolver.delete(collection, selection, args) }
        }
    }

    private fun resolveDeletableMediaStoreUris(
        uri: Uri,
        resolvedFile: File?,
        metadata: VaultMediaMetadata?,
    ): List<Uri> {
        val found = LinkedHashSet<Uri>()

        // 1. Direct canonical MediaStore URI
        if (uri.scheme.equals("content", ignoreCase = true) && uri.authority == MediaStore.AUTHORITY) {
            val pathSegs = uri.pathSegments
            val isStandardMediaCollection = pathSegs.any { it == "images" || it == "video" || it == "audio" }
            if (isStandardMediaCollection) {
                found.add(uri)
            }
        }

        // 2. Android 13+ Photo Picker & Document URI unwrapping via MediaStore.getMediaUri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            uri.scheme.equals("content", ignoreCase = true)
        ) {
            runCatching { MediaStore.getMediaUri(appContext, uri) }.getOrNull()?.let { unwrapped ->
                if (unwrapped.authority == MediaStore.AUTHORITY) {
                    found.add(unwrapped)
                }
            }
        }

        // 3. com.android.providers.media.documents ("video:123", "image:123", "audio:123")
        if (DocumentsContract.isDocumentUri(appContext, uri) &&
            uri.authority == "com.android.providers.media.documents"
        ) {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
            val mediaType = docId.substringBefore(":")
            val idNum = docId.substringAfter(":").toLongOrNull()
            if (idNum != null) {
                when (mediaType) {
                    "image" -> found.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, idNum))
                    "video" -> found.add(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, idNum))
                    "audio" -> found.add(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, idNum))
                }
            }
        }

        // 4. com.android.providers.downloads.documents ("msf:123")
        if (DocumentsContract.isDocumentUri(appContext, uri) &&
            uri.authority == "com.android.providers.downloads.documents"
        ) {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
            if (docId.startsWith("msf:")) {
                val idNum = docId.removePrefix("msf:").toLongOrNull()
                if (idNum != null) {
                    val candidateTables = listOf(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    )
                    for (table in candidateTables) {
                        val candidate = ContentUris.withAppendedId(table, idNum)
                        val exists = runCatching {
                            appContext.contentResolver.query(
                                candidate,
                                arrayOf(MediaStore.MediaColumns._ID),
                                null,
                                null,
                                null,
                            )?.use { it.moveToFirst() } ?: false
                        }.getOrDefault(false)
                        if (exists) found.add(candidate)
                    }
                }
            }
        }

        // 5. Path-based lookup using all path aliases (/storage/emulated/0 vs /sdcard vs canonical)
        val filePath = resolvedFile?.absolutePath ?: queryDataColumnPath(uri)
        val mediaTables = listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        )
        if (!filePath.isNullOrBlank()) {
            val resolver = appContext.contentResolver
            val aliases = expandPathAliases(filePath)
            val placeholders = aliases.joinToString(",") { "?" }
            @Suppress("DEPRECATION")
            val selection = "${MediaStore.MediaColumns.DATA} IN ($placeholders)"
            val args = aliases.toTypedArray()
            for (table in mediaTables) {
                runCatching {
                    resolver.query(table, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { c ->
                        while (c.moveToNext()) {
                            found.add(ContentUris.withAppendedId(table, c.getLong(0)))
                        }
                    }
                }
            }
        }

        // 6. Metadata fallback (DISPLAY_NAME + SIZE) for Photo Picker / redacted-path URIs on Android 11/12
        if (found.isEmpty() && metadata != null && metadata.originalName.isNotBlank() && metadata.originalSizeBytes > 0L) {
            val resolver = appContext.contentResolver
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
            val args = arrayOf(metadata.originalName, metadata.originalSizeBytes.toString())
            val preferredTables = when (classifyCategory(metadata.originalName, metadata.mimeType)) {
                VaultCategory.VIDEOS -> listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                VaultCategory.PHOTOS -> listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                VaultCategory.FILES -> mediaTables
            }
            for (table in preferredTables) {
                runCatching {
                    resolver.query(table, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { c ->
                        if (c.count == 1 && c.moveToFirst()) {
                            found.add(ContentUris.withAppendedId(table, c.getLong(0)))
                        }
                    }
                }
            }
        }

        return found.toList()
    }

    private fun scanDeletedPathsAndWait(paths: List<String>) {
        if (paths.isEmpty()) return
        val latch = CountDownLatch(paths.size)
        runCatching {
            MediaScannerConnection.scanFile(
                appContext,
                paths.toTypedArray(),
                null,
            ) { _, _ ->
                latch.countDown()
            }
            latch.await(600L, TimeUnit.MILLISECONDS)
        }
    }

    private fun notifyMediaStoreChanged() {
        val resolver = appContext.contentResolver
        runCatching { resolver.notifyChange(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null) }
        runCatching { resolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null) }
        runCatching { resolver.notifyChange(MediaStore.Files.getContentUri("external"), null) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { resolver.notifyChange(MediaStore.Downloads.EXTERNAL_CONTENT_URI, null) }
        }
    }

    private fun expandPathAliases(path: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(path)
        runCatching { File(path).canonicalPath }.getOrNull()?.let { out.add(it) }
        val sdcardPrefix = "/sdcard/"
        val emulatedPrefix = "/storage/emulated/0/"
        if (path.startsWith(sdcardPrefix)) {
            out.add(emulatedPrefix + path.removePrefix(sdcardPrefix))
        } else if (path.startsWith(emulatedPrefix)) {
            out.add(sdcardPrefix + path.removePrefix(emulatedPrefix))
        }
        return out.toList()
    }

    private fun resolveLocalFileFromUri(uri: Uri): File? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri.path?.let(::File)
        }
        if (!uri.scheme.equals("content", ignoreCase = true)) return null

        // 1. Document URI paths
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
            } else if (authority == "com.android.providers.downloads.documents") {
                if (docId.startsWith("raw:")) {
                    val candidate = File(docId.removePrefix("raw:"))
                    if (candidate.exists()) return candidate
                } else if (docId.startsWith("msf:")) {
                    val idNum = docId.removePrefix("msf:").toLongOrNull()
                    if (idNum != null) {
                        val tables = buildList {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
                            }
                            add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                            add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            add(MediaStore.Files.getContentUri("external"))
                        }
                        for (table in tables) {
                            queryDataColumnFile(ContentUris.withAppendedId(table, idNum))?.let { return it }
                        }
                    }
                }
            } else if (authority == "com.android.providers.media.documents" && docId.contains(":")) {
                val mediaType = docId.substringBefore(":")
                val idNum = docId.substringAfter(":").toLongOrNull()
                if (idNum != null) {
                    val contentUri = when (mediaType) {
                        "image" -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, idNum)
                        "video" -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, idNum)
                        "audio" -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, idNum)
                        else -> ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), idNum)
                    }
                    queryDataColumnFile(contentUri)?.let { return it }
                }
            }
        }

        // 2. Android 13+ PhotoPicker URI unwrapping via MediaStore.getMediaUri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { MediaStore.getMediaUri(appContext, uri) }.getOrNull()?.let { unwrapped ->
                queryDataColumnFile(unwrapped)?.let { return it }
            }
        }

        // 3. Direct MediaStore / PhotoPicker _data lookup
        return queryDataColumnFile(uri)
    }

    private fun queryDataColumnPath(uri: Uri): String? {
        return runCatching {
            @Suppress("DEPRECATION")
            val column = MediaStore.MediaColumns.DATA
            appContext.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(column)
                    if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun queryDataColumnFile(uri: Uri): File? {
        val path = queryDataColumnPath(uri) ?: return null
        return File(path).takeIf { it.exists() }
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

    private fun generateEncryptedVaultFileName(): String {
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        return "v_${token}${VaultCipher.ENCRYPTED_VAULT_EXT}"
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
