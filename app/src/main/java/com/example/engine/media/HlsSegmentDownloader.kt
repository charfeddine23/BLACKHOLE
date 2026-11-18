package com.example.engine.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class HlsSegmentDownloader(private val client: OkHttpClient) {

    suspend fun downloadHlsToFile(
        playlistUrl: String,
        headers: Map<String, String>,
        outputFile: File,
        isCancelled: () -> Boolean,
        onProgress: (downloadedBytes: Long, estimatedTotalBytes: Long, speedBytesPerSec: Long) -> Unit,
        audioPlaylistUrl: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (audioPlaylistUrl.isNullOrBlank()) {
            return@withContext downloadSinglePlaylistToFile(
                playlistUrl = playlistUrl,
                headers = headers,
                outputFile = outputFile,
                isCancelled = isCancelled,
                onProgress = onProgress,
            )
        }

        val stagingDir = File(outputFile.parentFile ?: outputFile.absoluteFile.parentFile, "hls_av_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        val videoTemp = File(stagingDir, "video_track.mp4")
        val audioTemp = File(stagingDir, "audio_track.mp4")

        val videoBytes = AtomicLong(0L)
        val audioBytes = AtomicLong(0L)
        val videoEstTotal = AtomicLong(0L)
        val audioEstTotal = AtomicLong(0L)
        val lastTick = AtomicLong(System.currentTimeMillis())
        val lastCombinedBytes = AtomicLong(0L)

        fun reportCombined() {
            val now = System.currentTimeMillis()
            val prev = lastTick.get()
            val elapsed = now - prev
            if (elapsed >= 200L && lastTick.compareAndSet(prev, now)) {
                val downloaded = videoBytes.get() + audioBytes.get()
                val estTotal = (videoEstTotal.get() + audioEstTotal.get()).takeIf { it > 0L } ?: -1L
                val prevBytes = lastCombinedBytes.getAndSet(downloaded)
                val speed = if (elapsed > 0L) ((downloaded - prevBytes) * 1000L) / elapsed else 0L
                onProgress(downloaded, estTotal, speed)
            }
        }

        try {
            val (videoOk, audioOk) = coroutineScope {
                val vJob = async(Dispatchers.IO) {
                    downloadSinglePlaylistToFile(
                        playlistUrl = playlistUrl,
                        headers = headers,
                        outputFile = videoTemp,
                        isCancelled = isCancelled,
                        onProgress = { dl, est, _ ->
                            videoBytes.set(dl)
                            if (est > 0L) videoEstTotal.set(est)
                            reportCombined()
                        },
                    )
                }
                val aJob = async(Dispatchers.IO) {
                    downloadSinglePlaylistToFile(
                        playlistUrl = audioPlaylistUrl,
                        headers = headers,
                        outputFile = audioTemp,
                        isCancelled = isCancelled,
                        onProgress = { dl, est, _ ->
                            audioBytes.set(dl)
                            if (est > 0L) audioEstTotal.set(est)
                            reportCombined()
                        },
                    )
                }
                vJob.await() to aJob.await()
            }

            if (!videoOk || isCancelled()) return@withContext false

            if (audioOk && audioTemp.exists() && audioTemp.length() > 0L) {
                val muxed = LocalMediaMuxer.mergeVideoAndAudio(videoTemp, audioTemp, outputFile)
                if (muxed && outputFile.exists() && outputFile.length() > 0L) {
                    return@withContext true
                }
            }

            // Fall back to copying videoTemp ONLY if it already contains an embedded audio track
            if (LocalMediaMuxer.hasAudioTrack(videoTemp)) {
                videoTemp.copyTo(outputFile, overwrite = true)
                return@withContext outputFile.exists() && outputFile.length() > 0L
            }
            false
        } finally {
            runCatching { stagingDir.deleteRecursively() }
        }
    }

    private suspend fun downloadSinglePlaylistToFile(
        playlistUrl: String,
        headers: Map<String, String>,
        outputFile: File,
        isCancelled: () -> Boolean,
        onProgress: (downloadedBytes: Long, estimatedTotalBytes: Long, speedBytesPerSec: Long) -> Unit,
    ): Boolean = coroutineScope {
        val playlistBody = fetchText(playlistUrl, headers) ?: return@coroutineScope false
        val segmentUrls = parseSegmentUrls(playlistUrl, playlistBody)
        if (segmentUrls.isEmpty()) return@coroutineScope false

        val segTempDir = File(
            outputFile.parentFile ?: outputFile.absoluteFile.parentFile,
            "hls_segs_${System.nanoTime()}",
        ).apply { mkdirs() }

        val totalBytes = AtomicLong(0L)
        val completedCount = AtomicInteger(0)
        val lastTick = AtomicLong(System.currentTimeMillis())
        val lastBytes = AtomicLong(0L)
        val semaphore = Semaphore(PARALLEL_SEGMENTS)

        fun maybeEmitProgress(force: Boolean = false) {
            val now = System.currentTimeMillis()
            val prev = lastTick.get()
            val elapsed = (now - prev).coerceAtLeast(1L)
            if ((force || elapsed >= 180L) && lastTick.compareAndSet(prev, now)) {
                val dl = totalBytes.get()
                val done = completedCount.get().coerceAtLeast(1)
                val prevB = lastBytes.getAndSet(dl)
                val speed = ((dl - prevB) * 1000L) / elapsed
                val avgPerSeg = dl / done
                val estTotal = avgPerSeg * segmentUrls.size
                onProgress(dl, estTotal, speed)
            }
        }

        try {
            val segmentFiles = segmentUrls.indices.map { idx ->
                File(segTempDir, "seg_${idx.toString().padStart(6, '0')}.part")
            }

            val jobs = segmentUrls.mapIndexed { index, segUrl ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (isCancelled()) throw CancellationException("Cancelled by user")
                        val targetSegFile = segmentFiles[index]
                        var ok = false
                        for (attempt in 0 until 2) {
                            if (isCancelled()) throw CancellationException("Cancelled by user")
                            val reqBuilder = Request.Builder().url(segUrl)
                            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

                            val downloadedThisTry = runCatching {
                                client.newCall(reqBuilder.build()).execute().use { response ->
                                    if (!response.isSuccessful) {
                                        return@use -1L
                                    }
                                    val stream = response.body?.byteStream() ?: return@use -1L
                                    var written = 0L
                                    BufferedOutputStream(FileOutputStream(targetSegFile), 128 * 1024).use { out ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            if (isCancelled()) throw CancellationException("Cancelled by user")
                                            val read = stream.read(buffer)
                                            if (read == -1) break
                                            out.write(buffer, 0, read)
                                            written += read
                                            totalBytes.addAndGet(read.toLong())
                                            maybeEmitProgress(force = false)
                                        }
                                        out.flush()
                                    }
                                    written
                                }
                            }.getOrElse { err ->
                                if (err is CancellationException) throw err
                                -1L
                            }

                            if (downloadedThisTry > 0L) {
                                ok = true
                                completedCount.incrementAndGet()
                                maybeEmitProgress(force = false)
                                break
                            }
                        }
                        if (!ok) {
                            throw IllegalStateException("HLS segment $index failed to download")
                        }
                    }
                }
            }

            jobs.awaitAll()
            maybeEmitProgress(force = true)

            if (isCancelled()) throw CancellationException("Cancelled by user")

            // Concatenate segments in exact playlist order using 256 KB buffer
            outputFile.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(outputFile), 256 * 1024).use { out ->
                val copyBuf = ByteArray(128 * 1024)
                for (segFile in segmentFiles) {
                    if (isCancelled()) throw CancellationException("Cancelled by user")
                    FileInputStream(segFile).use { input ->
                        while (true) {
                            val read = input.read(copyBuf)
                            if (read == -1) break
                            out.write(copyBuf, 0, read)
                        }
                    }
                    runCatching { segFile.delete() }
                }
                out.flush()
            }

            outputFile.exists() && outputFile.length() > 0L
        } finally {
            runCatching { segTempDir.deleteRecursively() }
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        return runCatching {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    private fun parseSegmentUrls(baseUrl: String, m3u8: String): List<String> {
        val urls = mutableListOf<String>()
        val baseUri = runCatching { URI(baseUrl) }.getOrNull() ?: return emptyList()

        m3u8.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach

            if (line.startsWith("#EXT-X-MAP:", ignoreCase = true)) {
                val uriAttr = Regex("""URI=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1)
                if (!uriAttr.isNullOrBlank()) {
                    urls.add(baseUri.resolve(uriAttr).toString())
                }
                return@forEach
            }

            if (line.startsWith("#")) return@forEach
            urls.add(baseUri.resolve(line).toString())
        }
        return urls
    }

    companion object {
        private const val PARALLEL_SEGMENTS = 6
    }
}
