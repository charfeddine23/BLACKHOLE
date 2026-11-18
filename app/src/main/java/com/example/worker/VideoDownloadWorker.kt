package com.example.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.data.local.BlackholeDatabase
import com.example.data.local.DownloadedVideoEntity
import com.example.data.repository.VideoVaultRepository
import com.example.engine.cookies.CookieVaultManager
import com.example.engine.extractor.VideoStreamExtractor
import com.example.engine.media.HlsSegmentDownloader
import com.example.engine.media.LocalMediaMuxer
import com.example.engine.model.DownloadStatus
import com.example.engine.model.DownloadTaskState
import com.example.engine.model.ExtractedMediaInfo
import com.example.engine.model.PlatformBadge
import com.example.engine.model.VideoQualityOption
import com.example.engine.network.ClientSpoofer
import com.example.engine.service.DownloadProgressManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Android 14/15 compliant WorkManager CoroutineWorker executing high-speed OkHttp
 * streaming, optional on-device MediaMuxer A/V merging, and Scoped Storage MediaStore
 * export directly to Movies/BlackHole.
 */
class VideoDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val cookieVault by lazy { CookieVaultManager.getInstance(applicationContext) }
    private val okHttpClient: OkHttpClient by lazy { VideoStreamExtractor.createWithVault(cookieVault) }
    private val hlsDownloader by lazy { HlsSegmentDownloader(okHttpClient) }
    private val repository by lazy {
        VideoVaultRepository(
            context = applicationContext,
            videoDao = BlackholeDatabase.getInstance(applicationContext).downloadedVideoDao(),
        )
    }

    private val taskId: String
        get() = inputData.getString(KEY_TASK_ID).orEmpty().ifBlank { id.toString() }

    private val notificationId: Int
        get() = (NOTIFICATION_BASE_ID + (taskId.hashCode() and 0x0FFF))

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val title = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "BlackHole Download" }
        return createForegroundInfo(title = title, statusText = "Preparing download...", progress = 0, indeterminate = true)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceUrl = inputData.getString(KEY_SOURCE_URL).orEmpty()
        val videoUrl = inputData.getString(KEY_VIDEO_URL).orEmpty()
        val audioUrl = inputData.getString(KEY_AUDIO_URL)?.takeIf { it.isNotBlank() }
        val title = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "BlackHole_Video" }
        val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL)?.takeIf { it.isNotBlank() }
        val platformName = inputData.getString(KEY_PLATFORM).orEmpty()
        val platform = PlatformBadge.entries.firstOrNull { it.name == platformName } ?: PlatformBadge.fromUrl(sourceUrl)
        val qualityLabel = inputData.getString(KEY_QUALITY_LABEL).orEmpty().ifBlank { "HD" }
        val mimeType = inputData.getString(KEY_MIME_TYPE).orEmpty().ifBlank { "video/mp4" }
        val headersJson = inputData.getString(KEY_HEADERS_JSON).orEmpty().ifBlank { "{}" }
        val isHls = inputData.getBoolean(KEY_IS_HLS, false)
        val expectedBytes = inputData.getLong(KEY_EXPECTED_BYTES, -1L)

        if (videoUrl.isBlank()) {
            DownloadProgressManager.updateTask(taskId) {
                it.copy(status = DownloadStatus.Failed, errorMessage = "Missing video stream URL.")
            }
            return@withContext Result.failure()
        }

        ensureNotificationChannel()
        runCatching {
            setForeground(
                createForegroundInfo(
                    title = title,
                    statusText = "Starting $qualityLabel download...",
                    progress = 0,
                    indeterminate = true,
                ),
            )
        }

        DownloadProgressManager.updateTask(taskId) {
            it.copy(
                status = DownloadStatus.Downloading,
                totalBytes = expectedBytes,
                errorMessage = null,
            )
        }

        val headers = parseHeadersMap(headersJson) + ClientSpoofer.buildHeadersForUrl(
            targetUrl = videoUrl,
            profile = ClientSpoofer.profiles.first(),
        )
        val safeBase = title.replace(Regex("""[^A-Za-z0-9_\-]"""), "_").trim('_').take(40).ifBlank { "BlackHole" }
        val fileName = "${safeBase}_${System.currentTimeMillis()}.mp4"

        val stagingDir = File(applicationContext.filesDir, "blackhole_videos").apply { mkdirs() }
        val localOutputFile = File(stagingDir, fileName)

        try {
            val ok = when {
                isHls -> downloadHls(videoUrl, title, qualityLabel, headers, localOutputFile)
                !audioUrl.isNullOrBlank() -> downloadAndMuxSplit(
                    videoUrl = videoUrl,
                    audioUrl = audioUrl,
                    title = title,
                    qualityLabel = qualityLabel,
                    headers = headers,
                    outputFile = localOutputFile,
                )
                else -> downloadProgressive(
                    videoUrl = videoUrl,
                    title = title,
                    qualityLabel = qualityLabel,
                    expectedBytes = expectedBytes,
                    headers = headers,
                    outputFile = localOutputFile,
                )
            }

            if (isStopped) {
                localOutputFile.delete()
                DownloadProgressManager.updateTask(taskId) {
                    it.copy(status = DownloadStatus.Cancelled, errorMessage = "Download cancelled")
                }
                return@withContext Result.failure()
            }

            if (!ok || !localOutputFile.exists() || localOutputFile.length() <= 0L) {
                localOutputFile.delete()
                DownloadProgressManager.updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.Failed,
                        errorMessage = "Stream returned empty payload or connection dropped.",
                    )
                }
                return@withContext Result.failure()
            }

            val finalUri = saveToMoviesBlackhole(localOutputFile, fileName, "video/mp4")
                ?: FileProvider.getUriForFile(
                    applicationContext,
                    "${applicationContext.packageName}.fileprovider",
                    localOutputFile,
                )

            val meta = extractLocalVideoMeta(localOutputFile, qualityLabel)
            val entity = DownloadedVideoEntity(
                title = title,
                sourceUrl = sourceUrl,
                streamUrl = videoUrl,
                platform = platform.label,
                qualityLabel = qualityLabel,
                resolution = meta.resolution,
                mimeType = mimeType,
                fileSizeBytes = localOutputFile.length(),
                durationMs = meta.durationMs,
                contentUri = finalUri.toString(),
                filePath = localOutputFile.absolutePath,
                thumbnailPath = meta.thumbnailPath,
            )
            val insertedId = repository.insertVideo(entity)

            DownloadProgressManager.updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.Completed,
                    progress = 1f,
                    downloadedBytes = localOutputFile.length(),
                    totalBytes = localOutputFile.length(),
                    mediaStoreUri = finalUri.toString(),
                    localFilePath = localOutputFile.absolutePath,
                    localThumbnailPath = meta.thumbnailPath ?: thumbnailUrl,
                    savedVideoId = insertedId,
                    errorMessage = null,
                )
            }

            postCompletionNotification(title, qualityLabel)
            Result.success()
        } catch (_: CancellationException) {
            localOutputFile.delete()
            DownloadProgressManager.updateTask(taskId) {
                it.copy(status = DownloadStatus.Cancelled, errorMessage = "Download cancelled")
            }
            Result.failure()
        } catch (e: Exception) {
            localOutputFile.delete()
            DownloadProgressManager.updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.Failed,
                    errorMessage = e.message ?: "Download failed unexpectedly.",
                )
            }
            Result.failure()
        }
    }

    private suspend fun downloadHls(
        playlistUrl: String,
        title: String,
        qualityLabel: String,
        headers: Map<String, String>,
        outputFile: File,
    ): Boolean {
        var lastNotifTick = 0L
        return hlsDownloader.downloadHlsToFile(
            playlistUrl = playlistUrl,
            headers = headers,
            outputFile = outputFile,
            isCancelled = { isStopped },
            onProgress = { downloaded, total, speed ->
                val pct = if (total > 0L) ((downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)) else 0f
                DownloadProgressManager.updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.Downloading,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        progress = pct,
                        speedBytesPerSec = speed,
                    )
                }
                val now = System.currentTimeMillis()
                if (now - lastNotifTick >= 800L) {
                    lastNotifTick = now
                    val pctInt = (pct * 100).toInt().coerceIn(0, 100)
                    updateNotification(title, "$qualityLabel · $pctInt% · ${formatSpeed(speed)}", pctInt, total <= 0L)
                }
            },
        )
    }

    private suspend fun downloadAndMuxSplit(
        videoUrl: String,
        audioUrl: String,
        title: String,
        qualityLabel: String,
        headers: Map<String, String>,
        outputFile: File,
    ): Boolean = coroutineScope {
        val tempDir = File(applicationContext.cacheDir, "mux_worker").apply { mkdirs() }
        val videoTemp = File(tempDir, "v_${System.currentTimeMillis()}.mp4")
        val audioTemp = File(tempDir, "a_${System.currentTimeMillis()}.m4a")

        val vRead = AtomicLong(0L)
        val aRead = AtomicLong(0L)
        val vTotal = AtomicLong(0L)
        val aTotal = AtomicLong(0L)
        val lastUiTick = AtomicLong(System.currentTimeMillis())
        val lastBytes = AtomicLong(0L)
        val lastNotifTick = AtomicLong(0L)

        fun emitCombined() {
            val now = System.currentTimeMillis()
            val prev = lastUiTick.get()
            val elapsed = now - prev
            if (elapsed >= 200L && lastUiTick.compareAndSet(prev, now)) {
                val downloaded = vRead.get() + aRead.get()
                val total = (vTotal.get() + aTotal.get()).takeIf { it > 0L } ?: -1L
                val prevB = lastBytes.getAndSet(downloaded)
                val speed = if (elapsed > 0L) ((downloaded - prevB) * 1000L) / elapsed else 0L
                val pct = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 0.94f) else 0f

                DownloadProgressManager.updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.Downloading,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        progress = pct,
                        speedBytesPerSec = speed,
                    )
                }

                val prevN = lastNotifTick.get()
                if (now - prevN >= 800L && lastNotifTick.compareAndSet(prevN, now)) {
                    val pctInt = (pct * 100).toInt().coerceIn(0, 99)
                    updateNotification(title, "$qualityLabel · $pctInt% · ${formatSpeed(speed)}", pctInt, total <= 0L)
                }
            }
        }

        try {
            val vJob = async(Dispatchers.IO) {
                streamUrlToFile(videoUrl, headers, videoTemp) { read, total ->
                    vRead.set(read)
                    if (total > 0L) vTotal.set(total)
                    emitCombined()
                }
            }
            val aJob = async(Dispatchers.IO) {
                streamUrlToFile(audioUrl, headers, audioTemp) { read, total ->
                    aRead.set(read)
                    if (total > 0L) aTotal.set(total)
                    emitCombined()
                }
            }

            val vOk = vJob.await()
            val aOk = aJob.await()
            if (!vOk || isStopped) return@coroutineScope false

            if (!aOk || !audioTemp.exists() || audioTemp.length() <= 0L) {
                videoTemp.copyTo(outputFile, overwrite = true)
                return@coroutineScope true
            }

            DownloadProgressManager.updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.Merging,
                    progress = 0.96f,
                    speedBytesPerSec = 0L,
                )
            }
            updateNotification(title, "Merging $qualityLabel video & audio tracks...", 96, true)

            val muxed = LocalMediaMuxer.mergeVideoAndAudio(videoTemp, audioTemp, outputFile)
            if (!muxed) {
                videoTemp.copyTo(outputFile, overwrite = true)
            }
            outputFile.exists() && outputFile.length() > 0L
        } finally {
            runCatching { videoTemp.delete() }
            runCatching { audioTemp.delete() }
        }
    }

    private fun downloadProgressive(
        videoUrl: String,
        title: String,
        qualityLabel: String,
        expectedBytes: Long,
        headers: Map<String, String>,
        outputFile: File,
    ): Boolean {
        var lastUiTick = System.currentTimeMillis()
        var lastBytes = 0L
        var lastNotifTick = 0L

        return streamUrlToFile(videoUrl, headers, outputFile) { read, reportedTotal ->
            val total = reportedTotal.takeIf { it > 0L } ?: expectedBytes
            val now = System.currentTimeMillis()
            val elapsed = now - lastUiTick
            if (elapsed >= 180L) {
                val speed = if (elapsed > 0L) ((read - lastBytes) * 1000L) / elapsed else 0L
                val pct = if (total > 0L) (read.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                DownloadProgressManager.updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.Downloading,
                        downloadedBytes = read,
                        totalBytes = total,
                        progress = pct,
                        speedBytesPerSec = speed,
                    )
                }
                lastUiTick = now
                lastBytes = read

                if (now - lastNotifTick >= 800L) {
                    lastNotifTick = now
                    val pctInt = (pct * 100).toInt().coerceIn(0, 100)
                    updateNotification(title, "$qualityLabel · $pctInt% · ${formatSpeed(speed)}", pctInt, total <= 0L)
                }
            }
        }
    }

    private fun streamUrlToFile(
        url: String,
        headers: Map<String, String>,
        destination: File,
        onBytes: (downloaded: Long, totalBytes: Long) -> Unit,
    ): Boolean {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        return okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val body = response.body ?: return@use false
            val total = body.contentLength()
            var downloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (isStopped) {
                            destination.delete()
                            throw CancellationException("Worker cancelled")
                        }
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onBytes(downloaded, total)
                    }
                    output.flush()
                }
            }
            downloaded > 0L
        }
    }

    /**
     * Scoped Storage MediaStore export directly into Movies/BlackHole on Android 10+ (API 29+),
     * with public Movies/BlackHole + MediaScannerConnection fallback on API 24-28.
     */
    private fun saveToMoviesBlackhole(
        sourceFile: File,
        displayName: String,
        mimeType: String,
    ): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BlackHole")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = applicationContext.contentResolver
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, values) ?: return null
                resolver.openOutputStream(itemUri)?.use { out: OutputStream ->
                    sourceFile.inputStream().use { input -> input.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                itemUri
            }.getOrNull()
        } else {
            return runCatching {
                @Suppress("DEPRECATION")
                val moviesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "BlackHole",
                ).apply { mkdirs() }
                val destFile = File(moviesDir, displayName)
                sourceFile.copyTo(destFile, overwrite = true)
                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(destFile.absolutePath),
                    arrayOf(mimeType),
                    null,
                )
                FileProvider.getUriForFile(
                    applicationContext,
                    "${applicationContext.packageName}.fileprovider",
                    destFile,
                )
            }.getOrNull()
        }
    }

    private data class LocalVideoMeta(
        val durationMs: Long,
        val resolution: String,
        val thumbnailPath: String?,
    )

    private fun extractLocalVideoMeta(videoFile: File, fallbackQuality: String): LocalVideoMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val resolution = if (width > 0 && height > 0) "${width}x${height}" else fallbackQuality

            val frameTime = if (duration > 1500L) 1_000_000L else 0L
            val bitmap = retriever.getFrameAtTime(frameTime, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L)
            val thumbPath = bitmap?.let { bmp ->
                val thumbDir = File(applicationContext.filesDir, "blackhole_thumbs").apply { mkdirs() }
                val thumbFile = File(thumbDir, "${videoFile.nameWithoutExtension}.jpg")
                FileOutputStream(thumbFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 86, out)
                }
                bmp.recycle()
                thumbFile.absolutePath
            }
            LocalVideoMeta(duration, resolution, thumbPath)
        } catch (_: Exception) {
            LocalVideoMeta(0L, fallbackQuality, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun createForegroundInfo(
        title: String,
        statusText: String,
        progress: Int,
        indeterminate: Boolean,
    ): ForegroundInfo {
        val notification = buildNotification(title, statusText, progress, indeterminate)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(R.string.action_cancel),
                cancelIntent,
            )
            .build()
    }

    private fun updateNotification(title: String, text: String, progress: Int, indeterminate: Boolean) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, buildNotification(title, text, progress, indeterminate))
    }

    private fun postCompletionNotification(title: String, qualityLabel: String) {
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Saved to Movies/BlackHole")
            .setContentText("$title ($qualityLabel)")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId + 10_000, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.notification_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun parseHeadersMap(json: String): Map<String, String> {
        return runCatching {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { key ->
                    put(key, obj.optString(key))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val mb = bytesPerSec / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB/s", mb)
        } else {
            "${(bytesPerSec / 1024L).coerceAtLeast(1L)} KB/s"
        }
    }

    companion object {
        private const val CHANNEL_ID = "blackhole_downloads"
        private const val NOTIFICATION_BASE_ID = 5000

        const val KEY_TASK_ID = "task_id"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_TITLE = "title"
        const val KEY_THUMBNAIL_URL = "thumbnail_url"
        const val KEY_PLATFORM = "platform"
        const val KEY_QUALITY_LABEL = "quality_label"
        const val KEY_VIDEO_URL = "video_url"
        const val KEY_AUDIO_URL = "audio_url"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_HEADERS_JSON = "headers_json"
        const val KEY_IS_HLS = "is_hls"
        const val KEY_EXPECTED_BYTES = "expected_bytes"

        fun enqueueDownload(
            context: Context,
            media: ExtractedMediaInfo,
            option: VideoQualityOption,
        ): String {
            val headersJson = JSONObject(option.headers).toString()
            val placeholderId = UUID.randomUUID()
            val taskId = placeholderId.toString()

            DownloadProgressManager.upsertTask(
                DownloadTaskState(
                    taskId = taskId,
                    sourceUrl = media.canonicalUrl,
                    title = media.title,
                    thumbnailUrl = media.thumbnailUrl,
                    platform = media.platform,
                    qualityLabel = option.qualityLabel,
                    videoUrl = option.videoUrl,
                    audioUrl = option.audioUrl,
                    mimeType = option.mimeType,
                    headersJson = headersJson,
                    isHls = option.isHls,
                    status = DownloadStatus.Extracting,
                    totalBytes = option.fileSizeBytes,
                ),
            )

            val input = Data.Builder()
                .putString(KEY_TASK_ID, taskId)
                .putString(KEY_SOURCE_URL, media.canonicalUrl)
                .putString(KEY_TITLE, media.title)
                .putString(KEY_THUMBNAIL_URL, media.thumbnailUrl)
                .putString(KEY_PLATFORM, media.platform.name)
                .putString(KEY_QUALITY_LABEL, option.qualityLabel)
                .putString(KEY_VIDEO_URL, option.videoUrl)
                .putString(KEY_AUDIO_URL, option.audioUrl)
                .putString(KEY_MIME_TYPE, option.mimeType)
                .putString(KEY_HEADERS_JSON, headersJson)
                .putBoolean(KEY_IS_HLS, option.isHls)
                .putLong(KEY_EXPECTED_BYTES, option.fileSizeBytes)
                .build()

            val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                .setId(placeholderId)
                .setInputData(input)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueue(request)
            return taskId
        }

        fun retryDownload(context: Context, existingTask: DownloadTaskState): String {
            val newUuid = UUID.randomUUID()
            val newTaskId = newUuid.toString()

            DownloadProgressManager.removeTask(existingTask.taskId)
            DownloadProgressManager.upsertTask(
                existingTask.copy(
                    taskId = newTaskId,
                    status = DownloadStatus.Extracting,
                    progress = 0f,
                    downloadedBytes = 0L,
                    errorMessage = null,
                    createdAt = System.currentTimeMillis(),
                ),
            )

            val input = Data.Builder()
                .putString(KEY_TASK_ID, newTaskId)
                .putString(KEY_SOURCE_URL, existingTask.sourceUrl)
                .putString(KEY_TITLE, existingTask.title)
                .putString(KEY_THUMBNAIL_URL, existingTask.thumbnailUrl)
                .putString(KEY_PLATFORM, existingTask.platform.name)
                .putString(KEY_QUALITY_LABEL, existingTask.qualityLabel)
                .putString(KEY_VIDEO_URL, existingTask.videoUrl)
                .putString(KEY_AUDIO_URL, existingTask.audioUrl)
                .putString(KEY_MIME_TYPE, existingTask.mimeType)
                .putString(KEY_HEADERS_JSON, existingTask.headersJson)
                .putBoolean(KEY_IS_HLS, existingTask.isHls)
                .putLong(KEY_EXPECTED_BYTES, existingTask.totalBytes)
                .build()

            val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                .setId(newUuid)
                .setInputData(input)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueue(request)
            return newTaskId
        }

        fun cancelDownload(context: Context, taskId: String) {
            runCatching {
                val uuid = UUID.fromString(taskId)
                WorkManager.getInstance(context.applicationContext).cancelWorkById(uuid)
            }
            DownloadProgressManager.updateTask(taskId) {
                it.copy(status = DownloadStatus.Cancelled, errorMessage = "Cancelled by user")
            }
        }
    }
}
