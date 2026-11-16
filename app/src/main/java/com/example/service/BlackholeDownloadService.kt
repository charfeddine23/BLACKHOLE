package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.MainActivity
import com.example.R
import com.example.data.local.BlackholeDatabase
import com.example.data.local.DownloadedVideoEntity
import com.example.data.repository.VideoVaultRepository
import com.example.engine.extractor.VideoStreamExtractor
import com.example.engine.model.ExtractedStream
import com.example.engine.model.ExtractionResult
import com.example.engine.model.PlatformBadge
import com.example.engine.service.DownloadStateCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class BlackholeDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient: OkHttpClient by lazy { VideoStreamExtractor.defaultOkHttpClient() }
    private val extractor: VideoStreamExtractor by lazy { VideoStreamExtractor(okHttpClient) }
    private val repository: VideoVaultRepository by lazy {
        val db = BlackholeDatabase.getInstance(applicationContext)
        VideoVaultRepository(applicationContext, db.downloadedVideoDao())
    }

    private var activeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_PULL -> {
                DownloadStateCoordinator.cancelRequested = true
                activeJob?.cancel()
                val current = DownloadStateCoordinator.telemetry.value
                DownloadStateCoordinator.resetToArmedOrIdle(current.sourceUrl, current.platform)
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_PULL -> {
                val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
                if (targetUrl.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val platform = PlatformBadge.fromUrl(targetUrl)
                startForegroundWithNotification(
                    buildNotification(
                        title = "Blackhole Core Active",
                        text = "Absorbing stream metadata...",
                        progress = 0,
                        indeterminate = true,
                    ),
                )
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    executeGravityPull(targetUrl, platform)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun executeGravityPull(sourceUrl: String, platform: PlatformBadge) {
        DownloadStateCoordinator.setAbsorbing(sourceUrl, platform)
        try {
            when (val extraction = extractor.extractHighestQuality(sourceUrl)) {
                is ExtractionResult.Error -> {
                    DownloadStateCoordinator.setError(sourceUrl, platform, extraction.message)
                    postTerminalNotification("Blackhole Extraction Failed", extraction.message)
                }

                is ExtractionResult.Success -> {
                    val stream = extraction.bestStream
                    streamAndSaveVideo(sourceUrl, stream)
                }
            }
        } catch (_: CancellationException) {
            DownloadStateCoordinator.resetToArmedOrIdle(sourceUrl, platform)
        } catch (e: Exception) {
            DownloadStateCoordinator.setError(
                sourceUrl,
                platform,
                e.message ?: "Unexpected gravity pull failure",
            )
            postTerminalNotification("Blackhole Pull Interrupted", e.message ?: "Download failed")
        } finally {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private suspend fun streamAndSaveVideo(sourceUrl: String, stream: ExtractedStream) {
        val requestBuilder = Request.Builder()
            .url(stream.streamUrl)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            )
        stream.headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        val call = okHttpClient.newCall(requestBuilder.build())
        call.execute().use { response ->
            if (!response.isSuccessful) {
                DownloadStateCoordinator.setError(
                    sourceUrl,
                    stream.platform,
                    "Upstream media server returned HTTP ${response.code}",
                )
                return
            }
            val body = response.body ?: run {
                DownloadStateCoordinator.setError(
                    sourceUrl,
                    stream.platform,
                    "Empty response stream from server",
                )
                return
            }

            val totalBytes = body.contentLength().takeIf { it > 0L } ?: stream.estimatedBytes
            val extension = extensionForMime(stream.mimeType)
            val safeFileBase = stream.title
                .replace(Regex("""[^A-Za-z0-9_\-]"""), "_")
                .trim('_')
                .take(40)
                .ifBlank { "Blackhole" }
            val fileName = "${safeFileBase}_${System.currentTimeMillis()}.$extension"

            // Always stage into app-private vault first for guaranteed MediaMetadataRetriever + FileProvider reliability,
            // and mirror into public MediaStore Movies/Blackhole on Android 10+ (zero-permission).
            val vaultDir = File(filesDir, "blackhole_videos").apply { mkdirs() }
            val localFile = File(vaultDir, fileName)

            var bytesDownloaded = 0L
            var lastUiTick = System.currentTimeMillis()
            var lastTickBytes = 0L
            var lastNotificationTick = 0L

            body.byteStream().use { input ->
                FileOutputStream(localFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (DownloadStateCoordinator.cancelRequested) {
                            localFile.delete()
                            throw CancellationException("User cancelled pull")
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesDownloaded += read

                        val now = System.currentTimeMillis()
                        val elapsedMs = now - lastUiTick
                        if (elapsedMs >= 200L) {
                            val deltaBytes = bytesDownloaded - lastTickBytes
                            val speed = if (elapsedMs > 0) (deltaBytes * 1000L) / elapsedMs else 0L
                            val eta = if (totalBytes > 0 && speed > 0) {
                                (totalBytes - bytesDownloaded).coerceAtLeast(0L) / speed
                            } else {
                                -1L
                            }
                            DownloadStateCoordinator.updateDownloading(
                                sourceUrl = sourceUrl,
                                title = stream.title,
                                platform = stream.platform,
                                qualityLabel = stream.qualityLabel,
                                bytesDownloaded = bytesDownloaded,
                                totalBytes = totalBytes,
                                speedBytesPerSec = speed,
                                etaSeconds = eta,
                            )
                            lastUiTick = now
                            lastTickBytes = bytesDownloaded

                            if (now - lastNotificationTick >= 700L) {
                                val pct = if (totalBytes > 0) {
                                    ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                } else {
                                    0
                                }
                                updateProgressNotification(
                                    title = stream.title,
                                    text = if (totalBytes > 0) {
                                        "${stream.qualityLabel} · $pct% · ${formatSpeed(speed)}"
                                    } else {
                                        "${stream.qualityLabel} · ${formatSize(bytesDownloaded)}"
                                    },
                                    progress = pct,
                                    indeterminate = totalBytes <= 0L,
                                )
                                lastNotificationTick = now
                            }
                        }
                    }
                    output.flush()
                }
            }

            if (bytesDownloaded <= 0L) {
                localFile.delete()
                DownloadStateCoordinator.setError(
                    sourceUrl,
                    stream.platform,
                    "Downloaded stream contained 0 bytes.",
                )
                return
            }

            val exportedUri = exportToMediaStoreIfSupported(localFile, fileName, stream.mimeType)
            val contentUri = exportedUri ?: FileProvider.getUriForFile(
                applicationContext,
                "${packageName}.fileprovider",
                localFile,
            )

            val metadata = extractLocalVideoMetadata(localFile, stream.qualityLabel)
            val entity = DownloadedVideoEntity(
                title = stream.title,
                sourceUrl = sourceUrl,
                streamUrl = stream.streamUrl,
                platform = stream.platform.label,
                qualityLabel = stream.qualityLabel,
                resolution = metadata.resolution,
                mimeType = stream.mimeType,
                fileSizeBytes = localFile.length(),
                durationMs = metadata.durationMs,
                contentUri = contentUri.toString(),
                filePath = localFile.absolutePath,
                thumbnailPath = metadata.thumbnailPath,
            )
            val insertedId = repository.insertVideo(entity)

            DownloadStateCoordinator.setCompleted(
                sourceUrl = sourceUrl,
                title = stream.title,
                platform = stream.platform,
                qualityLabel = stream.qualityLabel,
                totalBytes = localFile.length(),
                videoId = insertedId,
            )
            postTerminalNotification(
                title = "Captured in Vault",
                text = "${stream.title} (${stream.qualityLabel})",
            )
        }
    }

    private fun exportToMediaStoreIfSupported(
        sourceFile: File,
        displayName: String,
        mimeType: String,
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/Blackhole",
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = applicationContext.contentResolver
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(itemUri)?.use { out: OutputStream ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
        }.getOrNull()
    }

    private data class LocalVideoMeta(
        val durationMs: Long,
        val resolution: String,
        val thumbnailPath: String?,
    )

    private fun extractLocalVideoMetadata(videoFile: File, fallbackQuality: String): LocalVideoMeta {
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

            val frameTimeUs = if (duration > 1500L) 1_000_000L else 0L
            val bitmap = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L)

            val thumbPath = if (bitmap != null) {
                val thumbDir = File(filesDir, "blackhole_thumbs").apply { mkdirs() }
                val thumbFile = File(thumbDir, "${videoFile.nameWithoutExtension}.jpg")
                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out)
                }
                bitmap.recycle()
                thumbFile.absolutePath
            } else {
                null
            }
            LocalVideoMeta(durationMs = duration, resolution = resolution, thumbnailPath = thumbPath)
        } catch (_: Exception) {
            LocalVideoMeta(durationMs = 0L, resolution = fallbackQuality, thumbnailPath = null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            manager.createNotificationChannel(progressChannel)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BlackholeDownloadService::class.java).apply { action = ACTION_CANCEL_PULL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_cancel), cancelIntent)
            .build()
    }

    private fun updateProgressNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(title, text, progress, indeterminate))
    }

    private fun postTerminalNotification(title: String, text: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(TERMINAL_NOTIFICATION_ID, notification)
    }

    private fun extensionForMime(mimeType: String): String = when {
        mimeType.contains("webm") -> "webm"
        mimeType.contains("quicktime") -> "mov"
        mimeType.contains("matroska") -> "mkv"
        else -> "mp4"
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val mb = bytesPerSec / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format("%.1f MB/s", mb)
        } else {
            "${(bytesPerSec / 1024L).coerceAtLeast(1L)} KB/s"
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format("%.1f MB", mb)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "blackhole_downloads"
        private const val NOTIFICATION_ID = 4091
        private const val TERMINAL_NOTIFICATION_ID = 4092

        const val ACTION_START_PULL = "com.example.action.START_PULL"
        const val ACTION_CANCEL_PULL = "com.example.action.CANCEL_PULL"
        const val EXTRA_URL = "extra_url"

        fun startGravityPull(context: Context, url: String) {
            val intent = Intent(context, BlackholeDownloadService::class.java).apply {
                action = ACTION_START_PULL
                putExtra(EXTRA_URL, url)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelGravityPull(context: Context) {
            val intent = Intent(context, BlackholeDownloadService::class.java).apply {
                action = ACTION_CANCEL_PULL
            }
            context.startService(intent)
        }
    }
}
