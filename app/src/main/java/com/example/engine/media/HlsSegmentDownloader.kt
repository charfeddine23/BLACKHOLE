package com.example.engine.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI

class HlsSegmentDownloader(private val client: OkHttpClient) {

    suspend fun downloadHlsToFile(
        playlistUrl: String,
        headers: Map<String, String>,
        outputFile: File,
        isCancelled: () -> Boolean,
        onProgress: (downloadedBytes: Long, estimatedTotalBytes: Long, speedBytesPerSec: Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val playlistBody = fetchText(playlistUrl, headers) ?: return@withContext false
        val segmentUrls = parseSegmentUrls(playlistUrl, playlistBody)
        if (segmentUrls.isEmpty()) return@withContext false

        var totalBytes = 0L
        var lastTick = System.currentTimeMillis()
        var lastBytes = 0L

        FileOutputStream(outputFile).use { out ->
            segmentUrls.forEachIndexed { index, segUrl ->
                if (isCancelled()) throw CancellationException("Cancelled by user")
                val reqBuilder = Request.Builder().url(segUrl)
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HLS segment failed with HTTP ${response.code}")
                    }
                    val stream = response.body?.byteStream() ?: return@use
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (isCancelled()) throw CancellationException("Cancelled by user")
                        val read = stream.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        totalBytes += read
                    }
                }

                val now = System.currentTimeMillis()
                val elapsed = (now - lastTick).coerceAtLeast(1L)
                if (elapsed >= 200L || index == segmentUrls.lastIndex) {
                    val speed = ((totalBytes - lastBytes) * 1000L) / elapsed
                    val avgPerSeg = totalBytes / (index + 1).coerceAtLeast(1)
                    val estimatedTotal = avgPerSeg * segmentUrls.size
                    onProgress(totalBytes, estimatedTotal, speed)
                    lastTick = now
                    lastBytes = totalBytes
                }
            }
            out.flush()
        }
        outputFile.exists() && outputFile.length() > 0L
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
}
