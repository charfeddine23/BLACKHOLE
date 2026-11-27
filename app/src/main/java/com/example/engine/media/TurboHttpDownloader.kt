package com.example.engine.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * High-speed HTTP/HTTPS downloader supporting:
 * - Zero-waste Range capability probing (consumes HTTP 200 full responses immediately without re-requesting)
 * - Multi-connection parallel HTTP Range chunking (4-6 concurrent streams via lock-free FileChannel.write)
 * - 128 KB read buffers + 256 KB buffered file sinks for maximum throughput
 */
object TurboHttpDownloader {

    private const val READ_BUFFER_BYTES = 256 * 1024
    private const val FILE_BUFFER_BYTES = 512 * 1024
    private const val PARALLEL_THRESHOLD_BYTES = 1_000_000L // 1 MB
    private const val PROGRESS_EMIT_STEP_BYTES = 64 * 1024L

    suspend fun downloadToFile(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        destination: File,
        isCancelled: () -> Boolean,
        onBytes: (downloaded: Long, contentLength: Long) -> Unit,
    ): Boolean {
        destination.parentFile?.mkdirs()

        // Probe with 1-byte Range GET:
        // - HTTP 206 -> server supports ranges; launch 4-6 parallel workers if >= 1.5 MB
        // - HTTP 200 -> server ignored Range and is already streaming full body; consume directly!
        val probeReq = buildRequest(url, headers) {
            header("Range", "bytes=0-0")
        }

        val probeOutcome = runCatching {
            client.newCall(probeReq).execute().use { response ->
                if (isNonMediaErrorResponse(response)) {
                    return@use ProbeResult.AbortedHtmlOrError
                }
                when (response.code) {
                    206 -> {
                        val contentRange = response.header("Content-Range").orEmpty()
                        val totalBytes = contentRange
                            .substringAfterLast("/", "")
                            .trim()
                            .toLongOrNull()
                            ?: -1L
                        ProbeResult.SupportsRange(totalBytes)
                    }
                    in 200..299 -> {
                        // Server ignored Range and returned the full body — stream immediately!
                        val ok = streamResponseBody(
                            response = response,
                            destination = destination,
                            isCancelled = isCancelled,
                            onBytes = onBytes,
                        )
                        ProbeResult.AlreadyStreamed(ok)
                    }
                    else -> ProbeResult.FallbackSingleGet
                }
            }
        }.getOrElse { err ->
            if (err is CancellationException) throw err
            ProbeResult.FallbackSingleGet
        }

        return when (probeOutcome) {
            is ProbeResult.AbortedHtmlOrError -> false
            is ProbeResult.AlreadyStreamed -> probeOutcome.success
            is ProbeResult.SupportsRange -> {
                val total = probeOutcome.totalBytes
                if (total >= PARALLEL_THRESHOLD_BYTES) {
                    val parallelOk = runCatching {
                        downloadParallelRanges(
                            client = client,
                            url = url,
                            headers = headers,
                            totalBytes = total,
                            destination = destination,
                            isCancelled = isCancelled,
                            onBytes = onBytes,
                        )
                    }.getOrElse { err ->
                        if (err is CancellationException) throw err
                        false
                    }
                    if (parallelOk) return true
                    runCatching { destination.delete() }
                }
                downloadSingleStream(client, url, headers, destination, isCancelled, onBytes)
            }
            ProbeResult.FallbackSingleGet -> {
                downloadSingleStream(client, url, headers, destination, isCancelled, onBytes)
            }
        }
    }

    private sealed interface ProbeResult {
        data class SupportsRange(val totalBytes: Long) : ProbeResult
        data class AlreadyStreamed(val success: Boolean) : ProbeResult
        data object FallbackSingleGet : ProbeResult
        data object AbortedHtmlOrError : ProbeResult
    }

    private suspend fun downloadParallelRanges(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        totalBytes: Long,
        destination: File,
        isCancelled: () -> Boolean,
        onBytes: (downloaded: Long, contentLength: Long) -> Unit,
    ): Boolean = coroutineScope {
        val workerCount = when {
            totalBytes >= 10_000_000L -> 8
            totalBytes >= 4_000_000L -> 6
            else -> 4
        }

        // Pre-allocate exact target size once so concurrent FileChannel positional writes stay lock-free
        RandomAccessFile(destination, "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        val chunkSize = (totalBytes + workerCount - 1L) / workerCount
        val totalDownloaded = AtomicLong(0L)
        val lastReportedBytes = AtomicLong(0L)

        val jobs = (0 until workerCount).mapNotNull { index ->
            val start = index * chunkSize
            if (start >= totalBytes) return@mapNotNull null
            val end = ((index + 1) * chunkSize - 1L).coerceAtMost(totalBytes - 1L)

            async(Dispatchers.IO) {
                downloadSingleRangeWithRetry(
                    client = client,
                    url = url,
                    headers = headers,
                    startByte = start,
                    endByte = end,
                    totalBytes = totalBytes,
                    destination = destination,
                    totalDownloaded = totalDownloaded,
                    lastReportedBytes = lastReportedBytes,
                    isCancelled = isCancelled,
                    onBytes = onBytes,
                )
            }
        }

        val results = jobs.awaitAll()
        if (isCancelled()) {
            destination.delete()
            throw CancellationException("Download cancelled")
        }
        val allSucceeded = results.all { it }
        if (allSucceeded) {
            onBytes(totalBytes, totalBytes)
        }
        allSucceeded && destination.exists() && destination.length() == totalBytes
    }

    private fun downloadSingleRangeWithRetry(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        startByte: Long,
        endByte: Long,
        totalBytes: Long,
        destination: File,
        totalDownloaded: AtomicLong,
        lastReportedBytes: AtomicLong,
        isCancelled: () -> Boolean,
        onBytes: (downloaded: Long, contentLength: Long) -> Unit,
    ): Boolean {
        val expectedLength = (endByte - startByte) + 1L
        var attemptOffset = 0L

        for (attempt in 0 until 2) {
            val reqStart = startByte + attemptOffset
            if (reqStart > endByte) return true

            val req = buildRequest(url, headers) {
                header("Range", "bytes=$reqStart-$endByte")
            }

            val chunkDone = runCatching {
                client.newCall(req).execute().use { response ->
                    if (response.code != 206 && !response.isSuccessful) return@use false
                    if (isNonMediaErrorResponse(response)) return@use false
                    // If server returned 200 instead of 206 for a non-zero offset, fail fast to single stream
                    if (response.code == 200 && reqStart > 0L) return@use false

                    val body = response.body ?: return@use false
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    var writePos = reqStart

                    RandomAccessFile(destination, "rw").use { raf ->
                        raf.channel.use { channel ->
                            body.byteStream().use { input ->
                                while (true) {
                                    if (isCancelled()) {
                                        throw CancellationException("Download cancelled")
                                    }
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    val remainingNeeded = (endByte - writePos) + 1L
                                    val bytesToWrite = minOf(read.toLong(), remainingNeeded).toInt()
                                    if (bytesToWrite <= 0) break

                                    val byteBuf = ByteBuffer.wrap(buffer, 0, bytesToWrite)
                                    while (byteBuf.hasRemaining()) {
                                        writePos += channel.write(byteBuf, writePos)
                                    }
                                    attemptOffset = writePos - startByte

                                    val globalNow = totalDownloaded.addAndGet(bytesToWrite.toLong())
                                    val prev = lastReportedBytes.get()
                                    if (globalNow - prev >= PROGRESS_EMIT_STEP_BYTES &&
                                        lastReportedBytes.compareAndSet(prev, globalNow)
                                    ) {
                                        onBytes(globalNow.coerceAtMost(totalBytes), totalBytes)
                                    }
                                    if (attemptOffset >= expectedLength) break
                                }
                            }
                        }
                    }
                    attemptOffset >= expectedLength
                }
            }.getOrElse { err ->
                if (err is CancellationException) throw err
                false
            }

            if (chunkDone) return true
        }
        return false
    }

    private fun downloadSingleStream(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        destination: File,
        isCancelled: () -> Boolean,
        onBytes: (downloaded: Long, contentLength: Long) -> Unit,
    ): Boolean {
        val req = buildRequest(url, headers)
        return client.newCall(req).execute().use { response ->
            if (!response.isSuccessful || isNonMediaErrorResponse(response)) return@use false
            streamResponseBody(response, destination, isCancelled, onBytes)
        }
    }

    private fun isNonMediaErrorResponse(response: Response): Boolean {
        val contentType = response.header("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return contentType.startsWith("text/html") ||
            contentType == "application/xhtml+xml" ||
            contentType == "application/json" ||
            contentType.startsWith("image/")
    }

    private fun streamResponseBody(
        response: Response,
        destination: File,
        isCancelled: () -> Boolean,
        onBytes: (downloaded: Long, contentLength: Long) -> Unit,
    ): Boolean {
        if (isNonMediaErrorResponse(response)) return false
        val body = response.body ?: return false
        val totalBytes = body.contentLength()
        var bytesDownloaded = 0L
        var lastEmitted = 0L

        body.byteStream().use { input ->
            BufferedOutputStream(FileOutputStream(destination), FILE_BUFFER_BYTES).use { output ->
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (true) {
                    if (isCancelled()) {
                        destination.delete()
                        throw CancellationException("Download cancelled")
                    }
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    bytesDownloaded += read
                    if (bytesDownloaded - lastEmitted >= PROGRESS_EMIT_STEP_BYTES) {
                        lastEmitted = bytesDownloaded
                        onBytes(bytesDownloaded, totalBytes)
                    }
                }
                output.flush()
            }
        }
        if (bytesDownloaded > 0L) {
            onBytes(bytesDownloaded, if (totalBytes > 0L) totalBytes else bytesDownloaded)
        }
        return bytesDownloaded > 0L
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        extra: Request.Builder.() -> Unit = {},
    ): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.extra()
        return builder.build()
    }
}
