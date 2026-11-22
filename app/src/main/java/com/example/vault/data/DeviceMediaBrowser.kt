package com.example.vault.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DeviceMediaItem(
    val id: String,
    val uriString: String,
    val filePath: String? = null,
    val displayName: String,
    val folderName: String,
    val sizeBytes: Long,
    val dateModifiedMs: Long,
    val durationMs: Long = 0L,
    val mimeType: String,
    val category: VaultCategory,
    val isDirectory: Boolean = false,
)

class DeviceMediaBrowser(context: Context) {

    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    fun getExternalStorageRoot(): File = Environment.getExternalStorageDirectory()

    suspend fun queryDeviceMedia(
        category: VaultCategory,
        limit: Int = 400,
    ): List<DeviceMediaItem> = withContext(Dispatchers.IO) {
        val items = when (category) {
            VaultCategory.PHOTOS -> queryMediaStoreImages(limit)
            VaultCategory.VIDEOS -> queryMediaStoreVideos(limit)
            VaultCategory.FILES -> queryMediaStoreDocuments(limit)
        }

        if (items.isNotEmpty()) {
            return@withContext items
        }

        // Fallback direct directory scan when storage access is enabled on devices/emulators
        // where MediaStore index has not populated yet.
        val root = getExternalStorageRoot()
        if (!root.exists() || !root.canRead()) {
            return@withContext emptyList()
        }
        val candidateDirs = when (category) {
            VaultCategory.PHOTOS -> listOf(
                File(root, Environment.DIRECTORY_DCIM),
                File(root, Environment.DIRECTORY_PICTURES),
                File(root, Environment.DIRECTORY_DOWNLOADS),
            )
            VaultCategory.VIDEOS -> listOf(
                File(root, Environment.DIRECTORY_DCIM),
                File(root, Environment.DIRECTORY_MOVIES),
                File(root, Environment.DIRECTORY_DOWNLOADS),
            )
            VaultCategory.FILES -> listOf(
                File(root, Environment.DIRECTORY_DOWNLOADS),
                File(root, Environment.DIRECTORY_DOCUMENTS),
                root,
            )
        }

        val collected = ArrayList<DeviceMediaItem>()
        val seenPaths = HashSet<String>()
        for (dir in candidateDirs) {
            if (!dir.exists() || !dir.isDirectory || !dir.canRead()) continue
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.add(dir to 0)
            while (stack.isNotEmpty() && collected.size < limit) {
                val (curr, depth) = stack.removeFirst()
                val children = curr.listFiles() ?: continue
                for (child in children) {
                    val name = child.name
                    if (name.startsWith(".") || name.equals("BlackholeVault", ignoreCase = true)) continue
                    if (child.isDirectory) {
                        if (depth < 3 && !name.equals("Android", ignoreCase = true)) {
                            stack.add(child to depth + 1)
                        }
                    } else if (child.isFile && child.length() > 0L && seenPaths.add(child.absolutePath)) {
                        val mime = SafVaultRepository.inferMimeFromFileName(name) ?: "application/octet-stream"
                        val resolvedCat = SafVaultRepository.classifyCategory(name, mime)
                        if (category == VaultCategory.FILES || resolvedCat == category) {
                            collected.add(
                                DeviceMediaItem(
                                    id = "fs_${child.absolutePath.hashCode()}",
                                    uriString = Uri.fromFile(child).toString(),
                                    filePath = child.absolutePath,
                                    displayName = name,
                                    folderName = curr.name,
                                    sizeBytes = child.length(),
                                    dateModifiedMs = child.lastModified(),
                                    mimeType = mime,
                                    category = resolvedCat,
                                    isDirectory = false,
                                ),
                            )
                        }
                    }
                }
            }
        }
        collected.sortedByDescending { it.dateModifiedMs }
    }

    suspend fun listDeviceDirectory(directory: File): List<DeviceMediaItem> = withContext(Dispatchers.IO) {
        if (!directory.exists() || !directory.isDirectory || !directory.canRead()) {
            return@withContext emptyList()
        }
        val children = directory.listFiles()?.toList() ?: return@withContext emptyList()

        val folders = children
            .filter { file ->
                file.isDirectory &&
                    !file.name.startsWith(".") &&
                    !file.name.equals("BlackholeVault", ignoreCase = true)
            }
            .sortedBy { it.name.lowercase() }
            .map { dir ->
                val childCount = dir.listFiles()?.count { !it.name.startsWith(".") } ?: 0
                DeviceMediaItem(
                    id = "dir_${dir.absolutePath.hashCode()}",
                    uriString = Uri.fromFile(dir).toString(),
                    filePath = dir.absolutePath,
                    displayName = dir.name,
                    folderName = "$childCount عنصر",
                    sizeBytes = 0L,
                    dateModifiedMs = dir.lastModified(),
                    mimeType = "vnd.android.document/directory",
                    category = VaultCategory.FILES,
                    isDirectory = true,
                )
            }

        val files = children
            .filter { file ->
                file.isFile &&
                    file.length() > 0L &&
                    !file.name.startsWith(".") &&
                    !file.name.equals(".nomedia", ignoreCase = true) &&
                    !file.name.endsWith(VaultCipher.ENCRYPTED_VAULT_EXT, ignoreCase = true) &&
                    !file.name.endsWith(".tmp", ignoreCase = true)
            }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val mime = SafVaultRepository.inferMimeFromFileName(file.name) ?: "application/octet-stream"
                val category = SafVaultRepository.classifyCategory(file.name, mime)
                DeviceMediaItem(
                    id = "file_${file.absolutePath.hashCode()}",
                    uriString = Uri.fromFile(file).toString(),
                    filePath = file.absolutePath,
                    displayName = file.name,
                    folderName = directory.name,
                    sizeBytes = file.length(),
                    dateModifiedMs = file.lastModified(),
                    mimeType = mime,
                    category = category,
                    isDirectory = false,
                )
            }

        folders + files
    }

    private fun queryMediaStoreImages(limit: Int): List<DeviceMediaItem> {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val bucketColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        } else {
            MediaStore.Images.Media.DISPLAY_NAME
        }
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATA,
            bucketColumn,
        )
        val results = ArrayList<DeviceMediaItem>()
        runCatching {
            resolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val dateIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val bucketIdx = cursor.getColumnIndex(bucketColumn)

                while (cursor.moveToNext() && results.size < limit) {
                    val path = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                    if (!path.isNullOrBlank() && isInsideVaultPath(path)) continue
                    if (!path.isNullOrBlank() && isConfirmedMissingOnDisk(path)) continue
                    val id = cursor.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null)
                        ?.takeIf { it.isNotBlank() } ?: "photo_$id.jpg"
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    if (size <= 0L && path != null && File(path).length() <= 0L) continue
                    val modified = if (dateIdx >= 0) cursor.getLong(dateIdx) * 1000L else 0L
                    val mime = (if (mimeIdx >= 0) cursor.getString(mimeIdx) else null)
                        ?.takeIf { it.isNotBlank() }
                        ?: SafVaultRepository.inferMimeFromFileName(name)
                        ?: "image/jpeg"
                    val folder = (if (bucketIdx >= 0) cursor.getString(bucketIdx) else null)
                        ?.takeIf { it.isNotBlank() && bucketIdx != nameIdx } ?: "الصور"

                    results.add(
                        DeviceMediaItem(
                            id = "img_$id",
                            uriString = uri.toString(),
                            filePath = path,
                            displayName = name,
                            folderName = folder,
                            sizeBytes = if (size > 0L) size else (path?.let { File(it).length() } ?: 0L),
                            dateModifiedMs = modified,
                            mimeType = mime,
                            category = VaultCategory.PHOTOS,
                        ),
                    )
                }
            }
        }
        return results
    }

    private fun queryMediaStoreVideos(limit: Int): List<DeviceMediaItem> {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val bucketColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        } else {
            MediaStore.Video.Media.DISPLAY_NAME
        }
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA,
            bucketColumn,
        )
        val results = ArrayList<DeviceMediaItem>()
        runCatching {
            resolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val durIdx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val bucketIdx = cursor.getColumnIndex(bucketColumn)

                while (cursor.moveToNext() && results.size < limit) {
                    val path = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                    if (!path.isNullOrBlank() && isInsideVaultPath(path)) continue
                    if (!path.isNullOrBlank() && isConfirmedMissingOnDisk(path)) continue
                    val id = cursor.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null)
                        ?.takeIf { it.isNotBlank() } ?: "video_$id.mp4"
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    if (size <= 0L && path != null && File(path).length() <= 0L) continue
                    val modified = if (dateIdx >= 0) cursor.getLong(dateIdx) * 1000L else 0L
                    val duration = if (durIdx >= 0) cursor.getLong(durIdx) else 0L
                    val mime = (if (mimeIdx >= 0) cursor.getString(mimeIdx) else null)
                        ?.takeIf { it.isNotBlank() }
                        ?: SafVaultRepository.inferMimeFromFileName(name)
                        ?: "video/mp4"
                    val folder = (if (bucketIdx >= 0) cursor.getString(bucketIdx) else null)
                        ?.takeIf { it.isNotBlank() && bucketIdx != nameIdx } ?: "الفيديوهات"

                    results.add(
                        DeviceMediaItem(
                            id = "vid_$id",
                            uriString = uri.toString(),
                            filePath = path,
                            displayName = name,
                            folderName = folder,
                            sizeBytes = if (size > 0L) size else (path?.let { File(it).length() } ?: 0L),
                            dateModifiedMs = modified,
                            durationMs = duration,
                            mimeType = mime,
                            category = VaultCategory.VIDEOS,
                        ),
                    )
                }
            }
        }
        return results
    }

    private fun queryMediaStoreDocuments(limit: Int): List<DeviceMediaItem> {
        val resolver = appContext.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA,
        )
        val results = ArrayList<DeviceMediaItem>()
        runCatching {
            resolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (cursor.moveToNext() && results.size < limit) {
                    val path = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                    if (!path.isNullOrBlank() && isInsideVaultPath(path)) continue
                    val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null).orEmpty()
                    if (name.isBlank() || name.startsWith(".")) continue
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    if (size <= 0L) continue
                    val id = cursor.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val modified = if (dateIdx >= 0) cursor.getLong(dateIdx) * 1000L else 0L
                    val mime = (if (mimeIdx >= 0) cursor.getString(mimeIdx) else null)
                        ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                        ?: SafVaultRepository.inferMimeFromFileName(name)
                        ?: "application/octet-stream"

                    results.add(
                        DeviceMediaItem(
                            id = "doc_$id",
                            uriString = uri.toString(),
                            filePath = path,
                            displayName = name,
                            folderName = "Download",
                            sizeBytes = size,
                            dateModifiedMs = modified,
                            mimeType = mime,
                            category = SafVaultRepository.classifyCategory(name, mime),
                        ),
                    )
                }
            }
        }
        return results
    }

    private fun isInsideVaultPath(path: String): Boolean {
        return path.contains("/.BlackholeVault/", ignoreCase = true) ||
            path.contains("/BlackholeVault/", ignoreCase = true) ||
            path.contains("/blackhole_vault/", ignoreCase = true) ||
            path.endsWith(VaultCipher.ENCRYPTED_VAULT_EXT, ignoreCase = true)
    }

    private fun isConfirmedMissingOnDisk(path: String): Boolean {
        val file = File(path)
        val parent = file.parentFile ?: return false
        return parent.canRead() && !file.exists()
    }
}
