package com.example.vault.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class VaultHeaderMetadata(
    val n: String,
    val m: String,
)

data class VaultEncryptedHeader(
    val category: VaultCategory,
    val plaintextSize: Long,
    val payloadIv: ByteArray,
    val payloadStartOffset: Long,
    val metadata: VaultHeaderMetadata,
)

interface VaultRandomAccessReader : AutoCloseable {
    val totalLength: Long
    fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int
}

class FileVaultRandomAccessReader(private val file: File) : VaultRandomAccessReader {
    private val raf = RandomAccessFile(file, "r")
    override val totalLength: Long = raf.length()

    @Synchronized
    override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        if (offset < 0L || offset >= totalLength || length <= 0) return -1
        raf.seek(offset)
        return raf.read(dest, destOffset, length)
    }

    override fun close() {
        runCatching { raf.close() }
    }
}

class ChannelVaultRandomAccessReader(
    private val pfd: android.os.ParcelFileDescriptor,
) : VaultRandomAccessReader {
    private val stream = java.io.FileInputStream(pfd.fileDescriptor)
    private val channel = stream.channel
    override val totalLength: Long = runCatching { channel.size() }.getOrDefault(0L)

    @Synchronized
    override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        if (offset < 0L || offset >= totalLength || length <= 0) return -1
        val bb = ByteBuffer.wrap(dest, destOffset, length)
        var totalRead = 0
        var currOffset = offset
        while (bb.hasRemaining()) {
            val read = channel.read(bb, currOffset)
            if (read <= 0) break
            totalRead += read
            currOffset += read
        }
        return if (totalRead == 0) -1 else totalRead
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { stream.close() }
        runCatching { pfd.close() }
    }
}

class VaultCipher(
    context: Context,
    private val testKeyOverride: SecretKey? = null,
) {
    private val appContext = context.applicationContext
    private val secureRandom = SecureRandom()
    private val keyLock = Any()

    @Volatile
    private var cachedDek: SecretKey? = testKeyOverride

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun getOrCreateDataKey(): SecretKey {
        cachedDek?.let { return it }
        synchronized(keyLock) {
            cachedDek?.let { return it }
            val prefs = appContext.getSharedPreferences(KEY_PREFS_NAME, Context.MODE_PRIVATE)
            val existing = loadStoredKey(prefs)
            if (existing != null) {
                cachedDek = existing
                return existing
            }
            val rawDek = ByteArray(AES_KEY_SIZE_BYTES).apply { secureRandom.nextBytes(this) }
            val newKey = SecretKeySpec(rawDek, "AES")
            storeDataKey(prefs, rawDek)
            cachedDek = newKey
            return newKey
        }
    }

    private fun loadStoredKey(prefs: SharedPreferences): SecretKey? {
        val wrappedCtB64 = prefs.getString(KEY_WRAPPED_DEK_CT, null)
        val wrappedIvB64 = prefs.getString(KEY_WRAPPED_DEK_IV, null)
        if (!wrappedCtB64.isNullOrBlank() && !wrappedIvB64.isNullOrBlank()) {
            val decrypted = runCatching {
                val masterKey = getOrCreateKeystoreMasterKey() ?: return@runCatching null
                val cipher = Cipher.getInstance(GCM_TRANSFORM)
                val iv = Base64.decode(wrappedIvB64, Base64.NO_WRAP)
                val ct = Base64.decode(wrappedCtB64, Base64.NO_WRAP)
                cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                val raw = cipher.doFinal(ct)
                SecretKeySpec(raw, "AES")
            }.getOrNull()
            if (decrypted != null) return decrypted
        }

        // Per-install random fallback only when AndroidKeyStore is unavailable (e.g., restricted environments)
        val rawFallbackB64 = prefs.getString(KEY_FALLBACK_DEK, null)
        if (!rawFallbackB64.isNullOrBlank()) {
            val raw = runCatching { Base64.decode(rawFallbackB64, Base64.NO_WRAP) }.getOrNull()
            if (raw != null && raw.size == AES_KEY_SIZE_BYTES) {
                return SecretKeySpec(raw, "AES")
            }
        }
        return null
    }

    private fun storeDataKey(prefs: SharedPreferences, rawDek: ByteArray) {
        val keystoreWrapped = runCatching {
            val masterKey = getOrCreateKeystoreMasterKey() ?: return@runCatching false
            val cipher = Cipher.getInstance(GCM_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val wrappedCt = cipher.doFinal(rawDek)
            val wrappedIv = cipher.iv
            prefs.edit()
                .putString(KEY_WRAPPED_DEK_CT, Base64.encodeToString(wrappedCt, Base64.NO_WRAP))
                .putString(KEY_WRAPPED_DEK_IV, Base64.encodeToString(wrappedIv, Base64.NO_WRAP))
                .remove(KEY_FALLBACK_DEK)
                .commit()
        }.getOrDefault(false)

        if (!keystoreWrapped) {
            prefs.edit()
                .putString(KEY_FALLBACK_DEK, Base64.encodeToString(rawDek, Base64.NO_WRAP))
                .commit()
        }
    }

    private fun getOrCreateKeystoreMasterKey(): SecretKey? {
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
            if (existing != null) return@runCatching existing

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        }.getOrNull()
    }

    fun encryptStreamToOutput(
        inputStream: InputStream,
        outputStream: OutputStream,
        metadata: VaultHeaderMetadata,
        category: VaultCategory,
        expectedPlaintextSize: Long,
        onBytesRead: (Long) -> Unit = {},
    ): Long {
        val dek = getOrCreateDataKey()
        val payloadIv = ByteArray(AES_BLOCK_SIZE).apply { secureRandom.nextBytes(this) }
        val metaJsonBytes = json.encodeToString(metadata).toByteArray(Charsets.UTF_8)

        val metaIv = ByteArray(GCM_IV_BYTES).apply { secureRandom.nextBytes(this) }
        val metaCipher = Cipher.getInstance(GCM_TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, metaIv))
        }
        val metaCt = metaCipher.doFinal(metaJsonBytes)
        val metaBlob = metaIv + metaCt
        require(metaBlob.size <= 65_535) { "Vault header metadata exceeds limit." }

        val prefix = ByteBuffer.allocate(FIXED_PREFIX_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC_BYTES)
            put(FORMAT_VERSION)
            put(category.ordinal.toByte())
            putLong(expectedPlaintextSize)
            put(payloadIv)
            putShort(metaBlob.size.toShort())
        }.array()

        outputStream.write(prefix)
        outputStream.write(metaBlob)

        val payloadCipher = Cipher.getInstance(CTR_TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, dek, IvParameterSpec(payloadIv))
        }

        val buffer = ByteArray(128 * 1024)
        var totalProcessed = 0L
        val cipherOut = CipherOutputStream(NoCloseOutputStream(outputStream), payloadCipher)
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            cipherOut.write(buffer, 0, read)
            totalProcessed += read
            onBytesRead(totalProcessed)
        }
        cipherOut.flush()
        cipherOut.close()
        outputStream.flush()
        return totalProcessed
    }

    fun patchPlaintextSizeInFile(file: File, actualSize: Long) {
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                if (raf.length() >= FIXED_PREFIX_BYTES) {
                    raf.seek(6L)
                    raf.writeLong(actualSize)
                }
            }
        }
    }

    fun isEncryptedFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < FIXED_PREFIX_BYTES) return false
        return runCatching {
            file.inputStream().use { stream ->
                val magic = ByteArray(4)
                val read = stream.read(magic)
                read == 4 && magic.contentEquals(MAGIC_BYTES)
            }
        }.getOrDefault(false)
    }

    fun readHeader(file: File): VaultEncryptedHeader? {
        if (!file.exists() || !file.isFile || file.length() < FIXED_PREFIX_BYTES) return null
        return runCatching {
            BufferedInputStream(file.inputStream(), 1024).use { stream ->
                readHeaderFromStream(stream, file.length())
            }
        }.getOrNull()
    }

    fun readHeader(uri: Uri): VaultEncryptedHeader? {
        return runCatching {
            val totalLen = appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length
            } ?: -1L
            appContext.contentResolver.openInputStream(uri)?.use { raw ->
                BufferedInputStream(raw, 1024).use { stream ->
                    readHeaderFromStream(stream, totalLen)
                }
            }
        }.getOrNull()
    }

    private fun readHeaderFromStream(
        stream: InputStream,
        totalContainerLength: Long = -1L,
    ): VaultEncryptedHeader? {
        val prefix = ByteArray(FIXED_PREFIX_BYTES)
        if (!readExact(stream, prefix)) return null
        if (!prefix.copyOfRange(0, 4).contentEquals(MAGIC_BYTES)) return null

        val bb = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
        bb.position(4)
        val version = bb.get()
        if (version != FORMAT_VERSION) return null
        val catOrdinal = bb.get().toInt() and 0xFF
        val category = VaultCategory.entries.getOrElse(catOrdinal) { VaultCategory.FILES }
        val storedSize = bb.long
        val payloadIv = ByteArray(AES_BLOCK_SIZE).also { bb.get(it) }
        val metaBlobLen = bb.short.toInt() and 0xFFFF
        if (metaBlobLen <= GCM_IV_BYTES || metaBlobLen > 65_535) return null

        val metaBlob = ByteArray(metaBlobLen)
        if (!readExact(stream, metaBlob)) return null

        val dek = getOrCreateDataKey()
        val metaIv = metaBlob.copyOfRange(0, GCM_IV_BYTES)
        val metaCt = metaBlob.copyOfRange(GCM_IV_BYTES, metaBlob.size)
        val metaPlain = Cipher.getInstance(GCM_TRANSFORM).run {
            init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, metaIv))
            doFinal(metaCt)
        }
        val metadata = json.decodeFromString<VaultHeaderMetadata>(metaPlain.toString(Charsets.UTF_8))
        val payloadStartOffset = FIXED_PREFIX_BYTES.toLong() + metaBlobLen.toLong()
        val resolvedSize = when {
            storedSize > 0L -> storedSize
            totalContainerLength >= payloadStartOffset -> totalContainerLength - payloadStartOffset
            else -> storedSize.coerceAtLeast(0L)
        }
        return VaultEncryptedHeader(
            category = category,
            plaintextSize = resolvedSize,
            payloadIv = payloadIv,
            payloadStartOffset = payloadStartOffset,
            metadata = metadata,
        )
    }

    fun openDecryptedInputStream(file: File): InputStream {
        val raw = BufferedInputStream(file.inputStream(), 64 * 1024)
        raw.mark(FIXED_PREFIX_BYTES + 4)
        val magic = ByteArray(4)
        val read = raw.read(magic)
        raw.reset()
        if (read < 4 || !magic.contentEquals(MAGIC_BYTES)) {
            return raw
        }
        val header = readHeaderFromStream(raw, file.length())
            ?: throw IllegalStateException("فشل قراءة ترويسة الملف المشفر.")
        val dek = getOrCreateDataKey()
        val cipher = Cipher.getInstance(CTR_TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, dek, IvParameterSpec(header.payloadIv))
        }
        return CipherInputStream(raw, cipher)
    }

    fun openDecryptedInputStream(uri: Uri): InputStream {
        val raw = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("تعذر فتح الملف من المجلد المختار.")
        val buffered = BufferedInputStream(raw, 64 * 1024)
        buffered.mark(FIXED_PREFIX_BYTES + 4)
        val magic = ByteArray(4)
        val read = buffered.read(magic)
        buffered.reset()
        if (read < 4 || !magic.contentEquals(MAGIC_BYTES)) {
            return buffered
        }
        val header = readHeaderFromStream(buffered, -1L)
            ?: throw IllegalStateException("فشل فك ترويسة الملف المشفر.")
        val dek = getOrCreateDataKey()
        val cipher = Cipher.getInstance(CTR_TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, dek, IvParameterSpec(header.payloadIv))
        }
        return CipherInputStream(buffered, cipher)
    }

    fun decryptRange(
        reader: VaultRandomAccessReader,
        header: VaultEncryptedHeader,
        plaintextOffset: Long,
        dest: ByteArray,
        destOffset: Int,
        length: Int,
    ): Int {
        if (length <= 0) return 0
        val payloadAvailable = (reader.totalLength - header.payloadStartOffset).coerceAtLeast(0L)
        val effectivePayloadLength = if (header.plaintextSize > 0L) {
            minOf(header.plaintextSize, payloadAvailable)
        } else {
            payloadAvailable
        }
        if (plaintextOffset < 0L || plaintextOffset >= effectivePayloadLength) return -1

        val bytesWanted = minOf(length.toLong(), effectivePayloadLength - plaintextOffset).toInt()
        if (bytesWanted <= 0) return -1

        val blockIndex = plaintextOffset / AES_BLOCK_SIZE.toLong()
        val intraBlockOffset = (plaintextOffset % AES_BLOCK_SIZE.toLong()).toInt()
        val ciphertextReadOffset = header.payloadStartOffset + (blockIndex * AES_BLOCK_SIZE.toLong())
        val rawToRead = bytesWanted + intraBlockOffset

        val ciphertextBuffer = ByteArray(rawToRead)
        var totalRead = 0
        while (totalRead < rawToRead) {
            val read = reader.readAt(
                ciphertextReadOffset + totalRead,
                ciphertextBuffer,
                totalRead,
                rawToRead - totalRead,
            )
            if (read <= 0) break
            totalRead += read
        }
        if (totalRead <= intraBlockOffset) return -1

        val dek = getOrCreateDataKey()
        val blockIv = incrementCounterIv(header.payloadIv, blockIndex)
        val cipher = Cipher.getInstance(CTR_TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, dek, IvParameterSpec(blockIv))
        }
        val decrypted = cipher.doFinal(ciphertextBuffer, 0, totalRead)
        val validPlainLen = (decrypted.size - intraBlockOffset).coerceAtMost(bytesWanted)
        if (validPlainLen <= 0) return -1
        System.arraycopy(decrypted, intraBlockOffset, dest, destOffset, validPlainLen)
        return validPlainLen
    }

    private fun readExact(stream: InputStream, dest: ByteArray): Boolean {
        var offset = 0
        while (offset < dest.size) {
            val read = stream.read(dest, offset, dest.size - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }

    private class NoCloseOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun flush() = delegate.flush()
        override fun close() {
            // Preserve caller control over underlying stream closing
        }
    }

    companion object {
        const val ENCRYPTED_VAULT_EXT = ".bhlock"
        private const val KEY_PREFS_NAME = "blackhole_vault_keystore_prefs"
        private const val KEY_WRAPPED_DEK_CT = "KEY_WRAPPED_DEK_CT"
        private const val KEY_WRAPPED_DEK_IV = "KEY_WRAPPED_DEK_IV"
        private const val KEY_FALLBACK_DEK = "KEY_FALLBACK_DEK"
        private const val KEYSTORE_ALIAS = "blackhole_vault_master_wrap_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_TRANSFORM = "AES/GCM/NoPadding"
        private const val CTR_TRANSFORM = "AES/CTR/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val AES_KEY_SIZE_BYTES = 32
        private const val AES_BLOCK_SIZE = 16
        private const val FIXED_PREFIX_BYTES = 32
        private const val FORMAT_VERSION: Byte = 1
        private val MAGIC_BYTES = byteArrayOf(0x42, 0x48, 0x4C, 0x4B) // "BHLK"

        fun incrementCounterIv(baseIv: ByteArray, blockOffset: Long): ByteArray {
            require(baseIv.size == AES_BLOCK_SIZE)
            val result = baseIv.copyOf()
            if (blockOffset == 0L) return result
            var carry = blockOffset
            for (i in AES_BLOCK_SIZE - 1 downTo 0) {
                if (carry == 0L) break
                val byteVal = result[i].toInt() and 0xFF
                val add = carry and 0xFFL
                val sum = byteVal.toLong() + add
                result[i] = (sum and 0xFFL).toByte()
                carry = (carry ushr 8) + (sum ushr 8)
            }
            return result
        }
    }
}
