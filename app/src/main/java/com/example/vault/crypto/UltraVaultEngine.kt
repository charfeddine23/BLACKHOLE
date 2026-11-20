package com.example.vault.crypto

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class VaultMediaMetadata(
    val originalName: String,
    val mimeType: String,
    val originalSizeBytes: Long,
)

enum class VaultOperationStage {
    DERIVING_KEY,
    STREAMING,
    FINALIZING,
    COMPLETED,
}

data class VaultOperationProgress(
    val fraction: Float,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val stage: VaultOperationStage,
    val metadata: VaultMediaMetadata? = null,
    val outputFile: File? = null,
)

/**
 * Chunked AES-256-GCM streaming cryptographic engine for large video files.
 *
 * File layout (BHVAULT2):
 *  - [8 bytes]  Magic: "BHVAULT2"
 *  - [4 bytes]  Format version (int = 2)
 *  - [4 bytes]  Chunk size in bytes (int = 1,048,576)
 *  - [16 bytes] Random per-file PBKDF2 salt
 *  - [Meta chunk: chunkIndex = -1L, isFinal = false] encrypted JSON metadata
 *  - [Data chunks: chunkIndex = 0L..N-1, isFinal = false] encrypted 1 MB media blocks
 *  - [EOF sentinel chunk: chunkIndex = N, isFinal = true, 0-byte plaintext] 16-byte GCM tag
 *
 * Each chunk uses a fresh 12-byte SecureRandom IV and authenticates:
 *   Magic || Version || Salt || ChunkIndex (64-bit BE) || IsFinal (1 byte)
 * via Cipher.updateAAD(...), making chunk reordering, splicing, modification,
 * or end-of-stream truncation cryptographically detectable.
 */
object UltraVaultEngine {

    private const val MAGIC_STRING = "BHVAULT2"
    private val MAGIC_BYTES = MAGIC_STRING.toByteArray(StandardCharsets.US_ASCII)
    private const val FORMAT_VERSION = 2
    private const val CHUNK_SIZE_BYTES = 1024 * 1024 // 1 MB streaming chunks
    private const val SALT_SIZE_BYTES = 16
    private const val IV_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 210_000
    private const val AES_KEY_BITS = 256
    private const val METADATA_CHUNK_INDEX = -1L
    private const val TEMP_CACHE_SUBDIR = "vault_stream_tmp"

    private val secureRandom = SecureRandom()

    fun encryptStream(
        inputStream: InputStream,
        outputStream: OutputStream,
        password: CharArray,
        metadata: VaultMediaMetadata,
        onCommitAtomic: suspend () -> Unit,
        onCleanupFailed: suspend () -> Unit,
    ): Flow<VaultOperationProgress> = flow {
        val totalBytes = metadata.originalSizeBytes.coerceAtLeast(1L)
        val salt = ByteArray(SALT_SIZE_BYTES).apply { secureRandom.nextBytes(this) }
        val plainBuffer = ByteArray(CHUNK_SIZE_BYTES)
        var secretKey: SecretKey? = null
        var derivedKeyBytes: ByteArray? = null

        try {
            emit(
                VaultOperationProgress(
                    fraction = 0.02f,
                    bytesProcessed = 0L,
                    totalBytes = totalBytes,
                    stage = VaultOperationStage.DERIVING_KEY,
                    metadata = metadata,
                ),
            )

            val derived = deriveAesKey(password, salt)
            secretKey = derived.first
            derivedKeyBytes = derived.second

            val dataOut = DataOutputStream(BufferedOutputStream(outputStream, 64 * 1024))
            // Header
            dataOut.write(MAGIC_BYTES)
            dataOut.writeInt(FORMAT_VERSION)
            dataOut.writeInt(CHUNK_SIZE_BYTES)
            dataOut.write(salt)

            // Authenticated metadata block at chunkIndex = -1L
            val metaJsonBytes = JSONObject().apply {
                put("name", metadata.originalName)
                put("mime", metadata.mimeType)
                put("size", metadata.originalSizeBytes)
            }.toString().toByteArray(StandardCharsets.UTF_8)

            try {
                writeEncryptedChunk(
                    dataOut = dataOut,
                    secretKey = secretKey,
                    salt = salt,
                    chunkIndex = METADATA_CHUNK_INDEX,
                    isFinal = false,
                    plaintext = metaJsonBytes,
                    plainLength = metaJsonBytes.size,
                )
            } finally {
                metaJsonBytes.fill(0)
            }

            // Stream data chunks
            val bufferedIn = BufferedInputStream(inputStream, 64 * 1024)
            var chunkIndex = 0L
            var totalProcessed = 0L

            while (true) {
                val read = readFullChunk(bufferedIn, plainBuffer)
                if (read <= 0) break

                writeEncryptedChunk(
                    dataOut = dataOut,
                    secretKey = secretKey,
                    salt = salt,
                    chunkIndex = chunkIndex,
                    isFinal = false,
                    plaintext = plainBuffer,
                    plainLength = read,
                )
                chunkIndex++
                totalProcessed += read

                val progress = (0.05f + 0.90f * (totalProcessed.toFloat() / totalBytes.toFloat()))
                    .coerceIn(0.05f, 0.95f)
                emit(
                    VaultOperationProgress(
                        fraction = progress,
                        bytesProcessed = totalProcessed,
                        totalBytes = totalBytes,
                        stage = VaultOperationStage.STREAMING,
                        metadata = metadata,
                    ),
                )
            }

            // Explicit authenticated End-Of-Stream sentinel to block truncation attacks
            writeEncryptedChunk(
                dataOut = dataOut,
                secretKey = secretKey,
                salt = salt,
                chunkIndex = chunkIndex,
                isFinal = true,
                plaintext = ByteArray(0),
                plainLength = 0,
            )

            dataOut.flush()
            emit(
                VaultOperationProgress(
                    fraction = 0.97f,
                    bytesProcessed = totalProcessed,
                    totalBytes = totalBytes,
                    stage = VaultOperationStage.FINALIZING,
                    metadata = metadata,
                ),
            )

            onCommitAtomic()

            emit(
                VaultOperationProgress(
                    fraction = 1f,
                    bytesProcessed = totalProcessed,
                    totalBytes = totalProcessed.coerceAtLeast(totalBytes),
                    stage = VaultOperationStage.COMPLETED,
                    metadata = metadata,
                ),
            )
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                runCatching { onCleanupFailed() }
            }
            if (t is CancellationException) throw t
            throw t
        } finally {
            plainBuffer.fill(0)
            salt.fill(0)
            derivedKeyBytes?.fill(0)
            runCatching { secretKey = null }
        }
    }.flowOn(Dispatchers.IO)

    fun decryptToTempFile(
        context: Context,
        inputStream: InputStream,
        encryptedFileLengthHint: Long,
        password: CharArray,
    ): Flow<VaultOperationProgress> = flow {
        val tempDir = File(context.cacheDir, TEMP_CACHE_SUBDIR).apply {
            if (!exists()) mkdirs()
        }
        val stagingTempFile = File(tempDir, "dec_${UUID.randomUUID()}.tmp")
        var finalTempFile: File? = null
        val salt = ByteArray(SALT_SIZE_BYTES)
        var secretKey: SecretKey? = null
        var derivedKeyBytes: ByteArray? = null
        var success = false

        try {
            emit(
                VaultOperationProgress(
                    fraction = 0.02f,
                    bytesProcessed = 0L,
                    totalBytes = encryptedFileLengthHint.coerceAtLeast(1L),
                    stage = VaultOperationStage.DERIVING_KEY,
                ),
            )

            val dataIn = DataInputStream(BufferedInputStream(inputStream, 64 * 1024))

            val magic = ByteArray(MAGIC_BYTES.size)
            dataIn.readFully(magic)
            if (!magic.contentEquals(MAGIC_BYTES)) {
                throw SecurityException("Invalid BlackHole Vault file format.")
            }

            val version = dataIn.readInt()
            if (version != FORMAT_VERSION) {
                throw SecurityException("Unsupported Vault format version: $version")
            }

            val declaredChunkSize = dataIn.readInt()
            if (declaredChunkSize <= 0 || declaredChunkSize > 8 * 1024 * 1024) {
                throw SecurityException("Corrupted Vault chunk configuration.")
            }

            dataIn.readFully(salt)

            val derived = deriveAesKey(password, salt)
            secretKey = derived.first
            derivedKeyBytes = derived.second

            // Verify and decrypt authenticated metadata block (chunkIndex = -1L)
            val metaChunk = readAndDecryptChunk(
                dataIn = dataIn,
                secretKey = secretKey,
                salt = salt,
                expectedChunkIndex = METADATA_CHUNK_INDEX,
            ) ?: throw SecurityException("Vault metadata block missing.")

            if (metaChunk.isFinal) {
                throw SecurityException("Unexpected EOF sentinel in metadata chunk.")
            }

            val metadata = try {
                val json = JSONObject(String(metaChunk.plaintext, StandardCharsets.UTF_8))
                VaultMediaMetadata(
                    originalName = json.optString("name", "vault_video.mp4").ifBlank { "vault_video.mp4" },
                    mimeType = json.optString("mime", "video/mp4").ifBlank { "video/mp4" },
                    originalSizeBytes = json.optLong("size", 0L),
                )
            } finally {
                metaChunk.plaintext.fill(0)
            }

            val expectedTotalBytes = metadata.originalSizeBytes.coerceAtLeast(
                encryptedFileLengthHint.coerceAtLeast(1L),
            )
            val ext = sanitizeExtension(metadata.originalName, metadata.mimeType)
            val committedTempFile = File(tempDir, "play_${UUID.randomUUID()}.$ext")

            DataOutputStream(BufferedOutputStream(stagingTempFile.outputStream(), 64 * 1024)).use { out ->
                var expectedIndex = 0L
                var bytesWritten = 0L
                var sawFinalSentinel = false

                while (true) {
                    val chunk = readAndDecryptChunk(
                        dataIn = dataIn,
                        secretKey = secretKey,
                        salt = salt,
                        expectedChunkIndex = expectedIndex,
                    ) ?: break

                    try {
                        if (chunk.isFinal) {
                            sawFinalSentinel = true
                            if (chunk.plaintext.isNotEmpty()) {
                                out.write(chunk.plaintext)
                                bytesWritten += chunk.plaintext.size
                            }
                            break
                        } else {
                            out.write(chunk.plaintext)
                            bytesWritten += chunk.plaintext.size
                            expectedIndex++

                            val fraction = (0.08f + 0.88f * (bytesWritten.toFloat() / expectedTotalBytes.toFloat()))
                                .coerceIn(0.08f, 0.96f)
                            emit(
                                VaultOperationProgress(
                                    fraction = fraction,
                                    bytesProcessed = bytesWritten,
                                    totalBytes = expectedTotalBytes,
                                    stage = VaultOperationStage.STREAMING,
                                    metadata = metadata,
                                ),
                            )
                        }
                    } finally {
                        chunk.plaintext.fill(0)
                    }
                }

                if (!sawFinalSentinel) {
                    throw SecurityException("Vault file truncated or tampered (missing authenticated EOF sentinel).")
                }

                out.flush()
            }

            if (committedTempFile.exists()) committedTempFile.delete()
            if (!stagingTempFile.renameTo(committedTempFile)) {
                stagingTempFile.copyTo(committedTempFile, overwrite = true)
                secureDeleteFile(stagingTempFile)
            }
            finalTempFile = committedTempFile
            success = true

            emit(
                VaultOperationProgress(
                    fraction = 1f,
                    bytesProcessed = committedTempFile.length(),
                    totalBytes = committedTempFile.length().coerceAtLeast(1L),
                    stage = VaultOperationStage.COMPLETED,
                    metadata = metadata,
                    outputFile = committedTempFile,
                ),
            )
        } catch (badTag: AEADBadTagException) {
            throw SecurityException("Incorrect master password or tampered encrypted video.", badTag)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw t
        } finally {
            salt.fill(0)
            derivedKeyBytes?.fill(0)
            runCatching { secretKey = null }
            if (!success) {
                withContext(NonCancellable) {
                    secureDeleteFile(stagingTempFile)
                    finalTempFile?.let { secureDeleteFile(it) }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun purgeAllDecryptedTempFiles(context: Context) {
        val tempDir = File(context.cacheDir, TEMP_CACHE_SUBDIR)
        if (!tempDir.exists()) return
        runCatching {
            tempDir.listFiles()?.forEach { file ->
                secureDeleteFile(file)
            }
        }
    }

    fun secureDeleteFile(file: File?) {
        if (file == null || !file.exists()) return
        runCatching {
            if (file.isFile && file.length() in 1L..2_097_152L) {
                val len = file.length().toInt()
                val zeros = ByteArray(len)
                file.outputStream().use { it.write(zeros) }
            }
            file.delete()
        }
    }

    private fun deriveAesKey(password: CharArray, salt: ByteArray): Pair<SecretKey, ByteArray> {
        val passwordCopy = password.copyOf()
        val spec = PBEKeySpec(passwordCopy, salt, PBKDF2_ITERATIONS, AES_KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val generated = factory.generateSecret(spec)
            val keyBytes = generated.encoded
            val aesKey = SecretKeySpec(keyBytes, "AES")
            aesKey to keyBytes
        } finally {
            spec.clearPassword()
            passwordCopy.fill('\u0000')
        }
    }

    private fun writeEncryptedChunk(
        dataOut: DataOutputStream,
        secretKey: SecretKey,
        salt: ByteArray,
        chunkIndex: Long,
        isFinal: Boolean,
        plaintext: ByteArray,
        plainLength: Int,
    ) {
        val iv = ByteArray(IV_SIZE_BYTES).apply { secureRandom.nextBytes(this) }
        val aad = buildChunkAad(salt = salt, chunkIndex = chunkIndex, isFinal = isFinal)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        var encrypted: ByteArray? = null
        try {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            encrypted = cipher.doFinal(plaintext, 0, plainLength)

            dataOut.writeByte(if (isFinal) 1 else 0)
            dataOut.writeInt(encrypted.size)
            dataOut.write(iv)
            dataOut.write(encrypted)
        } finally {
            iv.fill(0)
            aad.fill(0)
            encrypted?.fill(0)
        }
    }

    private data class DecryptedChunkResult(
        val isFinal: Boolean,
        val plaintext: ByteArray,
    )

    private fun readAndDecryptChunk(
        dataIn: DataInputStream,
        secretKey: SecretKey,
        salt: ByteArray,
        expectedChunkIndex: Long,
    ): DecryptedChunkResult? {
        val flagByte = try {
            dataIn.readByte()
        } catch (_: EOFException) {
            return null
        }
        val isFinal = flagByte.toInt() == 1
        val cipherLen = dataIn.readInt()
        if (cipherLen < 16 || cipherLen > 16 * 1024 * 1024) {
            throw SecurityException("Invalid encrypted chunk size ($cipherLen) at index $expectedChunkIndex")
        }

        val iv = ByteArray(IV_SIZE_BYTES)
        val cipherBytes = ByteArray(cipherLen)
        dataIn.readFully(iv)
        dataIn.readFully(cipherBytes)

        val aad = buildChunkAad(salt = salt, chunkIndex = expectedChunkIndex, isFinal = isFinal)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        return try {
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(cipherBytes)
            DecryptedChunkResult(isFinal = isFinal, plaintext = plain)
        } finally {
            iv.fill(0)
            cipherBytes.fill(0)
            aad.fill(0)
        }
    }

    private fun buildChunkAad(
        salt: ByteArray,
        chunkIndex: Long,
        isFinal: Boolean,
    ): ByteArray {
        return ByteBuffer.allocate(MAGIC_BYTES.size + 4 + SALT_SIZE_BYTES + 8 + 1)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC_BYTES)
            .putInt(FORMAT_VERSION)
            .put(salt)
            .putLong(chunkIndex)
            .put(if (isFinal) 1.toByte() else 0.toByte())
            .array()
    }

    private fun readFullChunk(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            if (count == -1) break
            total += count
        }
        return total
    }

    private fun sanitizeExtension(name: String, mime: String): String {
        val fromName = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
        if (fromName.length in 1..10 && fromName != "bhv" && fromName != "tmp") {
            return fromName
        }
        val lowerMime = mime.lowercase()
        return when {
            lowerMime.contains("jpeg") || lowerMime.contains("jpg") -> "jpg"
            lowerMime.contains("png") -> "png"
            lowerMime.contains("webp") -> "webp"
            lowerMime.contains("gif") -> "gif"
            lowerMime.contains("heic") -> "heic"
            lowerMime.contains("heif") -> "heif"
            lowerMime.contains("bmp") -> "bmp"
            lowerMime.startsWith("image/") -> "jpg"
            lowerMime.contains("webm") -> "webm"
            lowerMime.contains("matroska") -> "mkv"
            lowerMime.startsWith("video/") -> "mp4"
            lowerMime.contains("pdf") -> "pdf"
            lowerMime.contains("zip") -> "zip"
            lowerMime.contains("json") -> "json"
            lowerMime.contains("text/plain") -> "txt"
            lowerMime.contains("mpeg") || lowerMime.contains("mp3") -> "mp3"
            lowerMime.startsWith("audio/") -> "m4a"
            else -> "bin"
        }
    }
}
