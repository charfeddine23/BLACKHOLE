package com.example.vault.data

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException

/**
 * Seekable in-memory MediaDataSource for MediaMetadataRetriever thumbnails on encrypted .bhlock videos.
 */
class VaultEncryptedMediaDataSource(
    private val context: Context,
    private val cipher: VaultCipher,
    private val localFile: File?,
    private val uri: Uri,
) : MediaDataSource() {

    private val lock = Any()
    private var reader: VaultRandomAccessReader? = null
    private var header: VaultEncryptedHeader? = null
    private var initialized = false

    private fun ensureInitialized() {
        if (initialized) return
        val activeReader: VaultRandomAccessReader = when {
            localFile != null && localFile.exists() -> FileVaultRandomAccessReader(localFile)
            else -> {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Cannot open Vault URI for metadata.")
                ChannelVaultRandomAccessReader(pfd)
            }
        }
        reader = activeReader
        header = if (localFile != null && localFile.exists()) {
            cipher.readHeader(localFile)
        } else {
            cipher.readHeader(uri)
        }
        initialized = true
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        synchronized(lock) {
            ensureInitialized()
            val activeReader = reader ?: return -1
            val activeHeader = header
            val res = if (activeHeader != null) {
                cipher.decryptRange(activeReader, activeHeader, position, buffer, offset, size)
            } else {
                activeReader.readAt(position, buffer, offset, size)
            }
            return if (res < 0) -1 else res
        }
    }

    override fun getSize(): Long {
        synchronized(lock) {
            ensureInitialized()
            val activeReader = reader ?: return -1L
            val activeHeader = header
            return if (activeHeader != null) {
                val available = (activeReader.totalLength - activeHeader.payloadStartOffset).coerceAtLeast(0L)
                if (activeHeader.plaintextSize > 0L) minOf(activeHeader.plaintextSize, available) else available
            } else {
                activeReader.totalLength
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { reader?.close() }
            reader = null
            header = null
            initialized = false
        }
    }
}

/**
 * Custom seekable Media3 DataSource that streams & decrypts AES-CTR .bhlock files on the fly
 * with zero unencrypted temp files on disk. Also supports unencrypted legacy files transparently.
 */
@UnstableApi
class VaultEncryptedExoDataSource(
    private val context: Context,
    private val cipher: VaultCipher,
) : BaseDataSource(/* isNetwork = */ false) {

    private var dataSpec: DataSpec? = null
    private var reader: VaultRandomAccessReader? = null
    private var header: VaultEncryptedHeader? = null
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)

        val uri = dataSpec.uri
        val localFile = if (uri.scheme.equals("file", ignoreCase = true)) {
            uri.path?.let(::File)?.takeIf { it.exists() }
        } else {
            null
        }

        val activeReader: VaultRandomAccessReader = try {
            if (localFile != null) {
                FileVaultRandomAccessReader(localFile)
            } else {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("تعذر فتح ملف الخزنة للتشغيل.")
                ChannelVaultRandomAccessReader(pfd)
            }
        } catch (t: Throwable) {
            throw IOException(t.message ?: "تعذر قراءة ملف الخزنة.", t)
        }

        reader = activeReader
        header = if (localFile != null) {
            cipher.readHeader(localFile)
        } else {
            cipher.readHeader(uri)
        }

        val totalPlainSize: Long = header?.let { hdr ->
            val available = (activeReader.totalLength - hdr.payloadStartOffset).coerceAtLeast(0L)
            if (hdr.plaintextSize > 0L) minOf(hdr.plaintextSize, available) else available
        } ?: activeReader.totalLength

        if (dataSpec.position > totalPlainSize) {
            close()
            throw IOException("Seek position beyond end of encrypted vault file.")
        }

        readPosition = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, totalPlainSize - readPosition)
        } else {
            totalPlainSize - readPosition
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val activeReader = reader ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        if (toRead <= 0) return C.RESULT_END_OF_INPUT

        val activeHeader = header
        val readCount = if (activeHeader != null) {
            cipher.decryptRange(activeReader, activeHeader, readPosition, buffer, offset, toRead)
        } else {
            activeReader.readAt(readPosition, buffer, offset, toRead)
        }

        if (readCount <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        readPosition += readCount
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= readCount
        }
        bytesTransferred(readCount)
        return readCount
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        runCatching { reader?.close() }
        reader = null
        header = null
        if (opened) {
            opened = false
            transferEnded()
        }
        dataSpec = null
    }
}

@UnstableApi
class VaultEncryptedDataSourceFactory(
    private val context: Context,
    private val cipher: VaultCipher = VaultCipher(context),
) : DataSource.Factory {
    override fun createDataSource(): DataSource = VaultEncryptedExoDataSource(context.applicationContext, cipher)
}

object VaultTempShareHelper {
    private const val SHARE_CACHE_SUBDIR = "vault_export_temp"

    fun prepareDecryptedTempFile(
        context: Context,
        cipher: VaultCipher,
        localFile: File?,
        uri: Uri,
        desiredName: String,
    ): File {
        val cacheFolder = File(context.cacheDir, SHARE_CACHE_SUBDIR).apply {
            if (!exists()) mkdirs()
        }
        val safeName = desiredName.replace(Regex("""[/\\:*?"<>|]"""), "_").ifBlank { "vault_file.bin" }
        val targetFile = File(cacheFolder, safeName)
        if (targetFile.exists()) targetFile.delete()

        val decryptedStream = if (localFile != null && localFile.exists()) {
            cipher.openDecryptedInputStream(localFile)
        } else {
            cipher.openDecryptedInputStream(uri)
        }

        decryptedStream.use { input ->
            targetFile.outputStream().use { out ->
                input.copyTo(out, 64 * 1024)
                out.flush()
            }
        }
        return targetFile
    }

    fun clearTempShareCache(context: Context) {
        val cacheFolder = File(context.cacheDir, SHARE_CACHE_SUBDIR)
        if (cacheFolder.exists()) {
            runCatching { cacheFolder.deleteRecursively() }
        }
    }
}
