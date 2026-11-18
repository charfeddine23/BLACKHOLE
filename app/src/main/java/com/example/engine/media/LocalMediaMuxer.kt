package com.example.engine.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

object LocalMediaMuxer {

    suspend fun hasAudioTrack(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) return@withContext false
        val extractor = MediaExtractor()
        return@withContext try {
            extractor.setDataSource(file.absolutePath)
            findFirstTrack(extractor, "audio/") >= 0
        } catch (_: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Merges a separate high-resolution video file and audio file into a single MP4/WebM container
     * using Android's native MediaExtractor + MediaMuxer (zero external binaries).
     */
    suspend fun mergeVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File,
    ): Boolean = withContext(Dispatchers.IO) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        return@withContext try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrackIndex = findFirstTrack(videoExtractor, "video/")
            val audioTrackIndex = findFirstTrack(audioExtractor, "audio/")

            if (videoTrackIndex < 0) return@withContext false
            if (audioTrackIndex < 0) return@withContext false

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)

            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
            val isWebmVideo = videoMime.contains("vp9") || videoMime.contains("vp8")
            val isWebmAudio = audioMime.contains("opus") || audioMime.contains("vorbis")

            val outputFormat = if (isWebmVideo && isWebmAudio) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            muxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            val outVideoTrack = muxer.addTrack(videoFormat)
            val outAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferBytes = maxOf(
                getMaxInputSize(videoFormat, 1024 * 1024),
                getMaxInputSize(audioFormat, 256 * 1024),
            )
            val buffer = ByteBuffer.allocate(maxBufferBytes)
            val bufferInfo = MediaCodec.BufferInfo()

            copySamples(videoExtractor, muxer, outVideoTrack, buffer, bufferInfo)
            copySamples(audioExtractor, muxer, outAudioTrack, buffer, bufferInfo)

            muxer.stop()
            outputFile.exists() && outputFile.length() > 0L
        } catch (_: Exception) {
            runCatching { outputFile.delete() }
            false
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun findFirstTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun getMaxInputSize(format: MediaFormat, fallback: Int): Int {
        return if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(fallback)
        } else {
            fallback
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTrackIndex: Int,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    ) {
        var lastPts = -1L
        while (true) {
            bufferInfo.offset = 0
            bufferInfo.size = extractor.readSampleData(buffer, 0)
            if (bufferInfo.size < 0) {
                bufferInfo.size = 0
                break
            }
            val pts = extractor.sampleTime
            if (pts < 0L) break
            bufferInfo.presentationTimeUs = if (pts <= lastPts) lastPts + 1L else pts
            lastPts = bufferInfo.presentationTimeUs

            @Suppress("DEPRECATION")
            bufferInfo.flags = extractor.sampleFlags
            muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }
}
