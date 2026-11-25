package com.example.vault.data

import android.content.Context
import android.net.Uri
import com.example.vault.browser.SafeWebViewConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface VaultDownloadEvent {
    data class Starting(
        val streamUrl: String,
        val title: String,
    ) : VaultDownloadEvent

    data class Progress(
        val fraction: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : VaultDownloadEvent

    data class SavingToVault(
        val title: String,
        val sizeBytes: Long,
    ) : VaultDownloadEvent

    data class Completed(
        val title: String,
        val savedLocation: String,
        val bytesSaved: Long,
        val entry: VaultFileEntry? = null,
    ) : VaultDownloadEvent

    data class Failed(
        val message: String,
        val httpStatusCode: Int? = null,
        val isDrmOrRestricted: Boolean = false,
        val isManifestPlaylist: Boolean = false,
    ) : VaultDownloadEvent
}

/**
 * Cookie-free OkHttp streaming downloader that writes to a private staging file and
 * encrypts/stores completed videos directly into the BlackHole Vault via [SafVaultRepository].
 */
class VaultVideoDownloader(
    context: Context,
    private val vaultRepository: SafVaultRepository = SafVaultRepository(context.applicationContext),
    private val okHttpClient: OkHttpClient = createDefaultClient(),
) {
    private val appContext = context.applicationContext
    private val stagingDir: File
        get() = File(appContext.cacheDir, STAGING_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

    fun downloadVideoStream(
        streamUrl: String,
        refererOrigin: String?,
        suggestedTitle: String,
    ): Flow<VaultDownloadEvent> = flow {
        val cleanUrl = streamUrl.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            emit(
                VaultDownloadEvent.Failed(
                    message = "Stream URL must be a direct HTTP or HTTPS resource.",
                    isDrmOrRestricted = cleanUrl.startsWith("blob:", ignoreCase = true),
                ),
            )
            return@flow
        }

        if (isDrmUrlHint(cleanUrl)) {
            emit(
                VaultDownloadEvent.Failed(
                    message = "DRM-protected stream cannot be downloaded.",
                    isDrmOrRestricted = true,
                ),
            )
            return@flow
        }

        if (isManifestUrl(cleanUrl)) {
            emit(
                VaultDownloadEvent.Failed(
                    message = "Adaptive streaming manifest detected; resolving through page extractor.",
                    isManifestPlaylist = true,
                ),
            )
            return@flow
        }

        val safeTitle = sanitizeFileTitle(suggestedTitle)
        emit(VaultDownloadEvent.Starting(streamUrl = cleanUrl, title = safeTitle))

        val requestBuilder = Request.Builder()
            .url(cleanUrl)
            .get()
            .header("User-Agent", SafeWebViewConfig.STANDARD_ANDROID_CHROME_USER_AGENT)
            .header("Accept", "video/*,application/octet-stream;q=0.9,*/*;q=0.5")

        val safeReferer = refererOrigin?.trim().orEmpty()
        if (safeReferer.startsWith("http://", ignoreCase = true) ||
            safeReferer.startsWith("https://", ignoreCase = true)
        ) {
            requestBuilder.header("Referer", safeReferer)
        }

        val tempPartFile = File(stagingDir, "${safeTitle}_${UUID.randomUUID().toString().take(8)}.mp4.part")
        var finalizedTempFile: File? = null

        try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                val statusCode = response.code
                if (statusCode == 429 || statusCode == 403 || statusCode == 401) {
                    emit(
                        VaultDownloadEvent.Failed(
                            message = when (statusCode) {
                                429 -> "Rate limited by host (HTTP 429). Cooling down automatically."
                                403 -> "Access restricted by host (HTTP 403). Avoid rapid requests."
                                else -> "Authentication required (HTTP 401). Private account streams are not supported."
                            },
                            httpStatusCode = statusCode,
                            isDrmOrRestricted = statusCode == 401 || statusCode == 403,
                        ),
                    )
                    return@flow
                }

                if (!response.isSuccessful) {
                    emit(
                        VaultDownloadEvent.Failed(
                            message = "Server returned HTTP $statusCode while fetching stream.",
                            httpStatusCode = statusCode,
                        ),
                    )
                    return@flow
                }

                val body = response.body
                if (body == null) {
                    emit(VaultDownloadEvent.Failed("Server returned an empty response body."))
                    return@flow
                }

                val contentType = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase(Locale.US)
                    .orEmpty()

                if (contentType == "application/vnd.apple.mpegurl" ||
                    contentType == "application/x-mpegurl" ||
                    contentType == "application/dash+xml"
                ) {
                    emit(
                        VaultDownloadEvent.Failed(
                            message = "Adaptive playlist ($contentType) requires stream resolution.",
                            isManifestPlaylist = true,
                        ),
                    )
                    return@flow
                }

                if (contentType.startsWith("text/html") ||
                    contentType == "application/xhtml+xml" ||
                    contentType == "application/json"
                ) {
                    emit(
                        VaultDownloadEvent.Failed(
                            message = "Host returned web content ($contentType) instead of a direct video file.",
                        ),
                    )
                    return@flow
                }

                val extension = extensionForMimeOrUrl(contentType, cleanUrl)
                val readyTempFile = File(stagingDir, "${safeTitle}_${UUID.randomUUID().toString().take(8)}.$extension")
                finalizedTempFile = readyTempFile

                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                var lastEmitTimeMs = 0L
                val buffer = ByteArray(16 * 1024)

                body.byteStream().use { input ->
                    FileOutputStream(tempPartFile).use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read

                            val now = System.currentTimeMillis()
                            if (now - lastEmitTimeMs >= PROGRESS_EMIT_INTERVAL_MS ||
                                (totalBytes > 0L && downloadedBytes >= totalBytes)
                            ) {
                                lastEmitTimeMs = now
                                val fraction = if (totalBytes > 0L) {
                                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.96f)
                                } else {
                                    0.35f
                                }
                                emit(
                                    VaultDownloadEvent.Progress(
                                        fraction = fraction,
                                        bytesDownloaded = downloadedBytes,
                                        totalBytes = totalBytes,
                                    ),
                                )
                            }
                        }
                        output.flush()
                    }
                }

                if (downloadedBytes <= MIN_VALID_VIDEO_BYTES || !tempPartFile.exists()) {
                    emit(
                        VaultDownloadEvent.Failed(
                            message = "Downloaded payload was too small or incomplete to be a valid video.",
                        ),
                    )
                    return@flow
                }

                if (!tempPartFile.renameTo(readyTempFile)) {
                    tempPartFile.copyTo(readyTempFile, overwrite = true)
                    runCatching { tempPartFile.delete() }
                }

                emit(
                    VaultDownloadEvent.SavingToVault(
                        title = safeTitle,
                        sizeBytes = readyTempFile.length(),
                    ),
                )

                var storedInVault = false
                var savedTargetLocation = readyTempFile.name
                vaultRepository.moveMediaToVault(
                    sourceUri = Uri.fromFile(readyTempFile),
                    fallbackCategory = VaultCategory.VIDEOS,
                    deleteOriginal = true,
                ).collect { transfer ->
                    when (transfer.stage) {
                        VaultTransferStage.COMPLETED -> {
                            storedInVault = true
                            savedTargetLocation = transfer.metadata.originalName
                        }
                        else -> {
                            val combinedFraction = (0.96f + (transfer.fraction * 0.04f)).coerceIn(0.96f, 0.99f)
                            emit(
                                VaultDownloadEvent.Progress(
                                    fraction = combinedFraction,
                                    bytesDownloaded = readyTempFile.length(),
                                    totalBytes = readyTempFile.length(),
                                ),
                            )
                        }
                    }
                }

                if (!storedInVault) {
                    emit(
                        VaultDownloadEvent.Failed(
                            message = "Failed to encrypt and save downloaded video into Vault storage.",
                        ),
                    )
                    return@flow
                }

                val latestEntry = runCatching {
                    vaultRepository.listVaultEntries()
                        .filter { it.category == VaultCategory.VIDEOS }
                        .maxByOrNull { it.lastModifiedMs }
                }.getOrNull()

                emit(
                    VaultDownloadEvent.Completed(
                        title = safeTitle,
                        savedLocation = latestEntry?.uriString ?: savedTargetLocation,
                        bytesSaved = downloadedBytes,
                        entry = latestEntry,
                    ),
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            emit(
                VaultDownloadEvent.Failed(
                    message = t.message?.takeIf { it.isNotBlank() }
                        ?: "Network error occurred while downloading video.",
                ),
            )
        } finally {
            runCatching { if (tempPartFile.exists()) tempPartFile.delete() }
            runCatching {
                finalizedTempFile?.let { file ->
                    if (file.exists()) file.delete()
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun cleanupTempFiles() {
        runCatching {
            val dir = File(appContext.cacheDir, STAGING_DIR_NAME)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun isManifestUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains(".m3u8") || lower.contains(".mpd")
    }

    private fun isDrmUrlHint(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("widevine") ||
            lower.contains("playready") ||
            lower.contains("fairplay") ||
            lower.contains(".wvm") ||
            lower.contains("drm/")
    }

    private fun extensionForMimeOrUrl(contentType: String, url: String): String {
        val path = Uri.parse(url).path?.lowercase(Locale.US).orEmpty()
        return when {
            contentType.contains("webm") || path.endsWith(".webm") -> "webm"
            contentType.contains("quicktime") || path.endsWith(".mov") -> "mov"
            contentType.contains("matroska") || path.endsWith(".mkv") -> "mkv"
            else -> "mp4"
        }
    }

    private fun sanitizeFileTitle(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[^\p{L}\p{N}_\-\s]"""), " ")
            .trim()
            .replace(Regex("""\s+"""), "_")
            .take(48)
            .trim('_')
        return cleaned.ifBlank { "Vault_Video_${System.currentTimeMillis() / 1000L}" }
    }

    companion object {
        private const val STAGING_DIR_NAME = "vault_auto_dl_tmp"
        private const val PROGRESS_EMIT_INTERVAL_MS = 120L
        private const val MIN_VALID_VIDEO_BYTES = 1024L

        fun createDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
