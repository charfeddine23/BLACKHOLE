package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.vault.data.FileVaultRandomAccessReader
import com.example.vault.data.SafVaultRepository
import com.example.vault.data.VaultCategory
import com.example.vault.data.VaultCipher
import com.example.vault.data.VaultHeaderMetadata
import com.example.vault.data.VaultStorageMode
import com.example.vault.data.VaultTransferStage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VaultEncryptionAndMoveTest {

    private fun testCipher(context: Context): VaultCipher {
        val raw = ByteArray(32) { idx -> (idx * 7 + 11).toByte() }
        return VaultCipher(context, SecretKeySpec(raw, "AES"))
    }

    @Test
    fun `cipher encrypts stream, hides header, and seeks accurately across block boundaries`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cipher = testCipher(context)
        val plainBytes = ByteArray(100_000).apply { SecureRandom().nextBytes(this) }

        val encFile = File(context.cacheDir, "test_seek.bhlock")
        encFile.outputStream().use { out ->
            cipher.encryptStreamToOutput(
                inputStream = ByteArrayInputStream(plainBytes),
                outputStream = out,
                metadata = VaultHeaderMetadata(n = "secret_clip.mp4", m = "video/mp4"),
                category = VaultCategory.VIDEOS,
                expectedPlaintextSize = plainBytes.size.toLong(),
            )
        }

        val header = cipher.readHeader(encFile)
        assertNotNull(header)
        assertEquals("secret_clip.mp4", header!!.metadata.n)
        assertEquals("video/mp4", header.metadata.m)
        assertEquals(plainBytes.size.toLong(), header.plaintextSize)

        val fullDecrypted = cipher.openDecryptedInputStream(encFile).use { it.readBytes() }
        assertArrayEquals(plainBytes, fullDecrypted)

        val testOffsets = listOf(0, 1, 15, 16, 17, 1023, 4096, 65_537, 99_990)
        FileVaultRandomAccessReader(encFile).use { reader ->
            for (offset in testOffsets) {
                val len = minOf(513, plainBytes.size - offset)
                val outBuf = ByteArray(len)
                val read = cipher.decryptRange(reader, header, offset.toLong(), outBuf, 0, len)
                assertEquals("Read mismatch at offset $offset", len, read)
                assertArrayEquals(
                    "Plaintext mismatch at offset $offset",
                    plainBytes.copyOfRange(offset, offset + len),
                    outBuf,
                )
            }
        }
        encFile.delete()
    }

    @Test
    fun `moveMediaToVault encrypts file as bhlock and completely deletes original source file`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val cipher = testCipher(context)
            val repo = SafVaultRepository(context, cipher)

            assertEquals(VaultStorageMode.INTERNAL, repo.getActiveStorageMode())

            val sourceDir = File(context.cacheDir, "mock_phone_dcim").apply { mkdirs() }
            val originalFile = File(sourceDir, "private_photo_2026.jpg")
            val payload = "EXIF_HEADER_SECRET_IMAGE_PAYLOAD_1234567890".repeat(200).toByteArray()
            originalFile.writeBytes(payload)
            assertTrue(originalFile.exists())

            val events = repo.moveMediaToVault(
                sourceUri = Uri.fromFile(originalFile),
                fallbackCategory = VaultCategory.PHOTOS,
                deleteOriginal = true,
            ).toList()

            val completed = events.lastOrNull { it.stage == VaultTransferStage.COMPLETED }
            assertNotNull(completed)
            assertTrue("Expected original file to be deleted", completed!!.originalDeleted)
            assertFalse("Original file must disappear from phone", originalFile.exists())

            val entries = repo.listVaultEntries()
            assertEquals(1, entries.size)
            val stored = entries.first()
            assertEquals("private_photo_2026.jpg", stored.displayTitle)
            assertEquals(VaultCategory.PHOTOS, stored.category)
            assertTrue(stored.fileName.endsWith(VaultCipher.ENCRYPTED_VAULT_EXT))
            assertTrue(stored.isEncrypted)

            val storedFile = File(stored.localFilePath!!)
            assertTrue(storedFile.exists())
            val rawDiskBytes = storedFile.readBytes()
            assertFalse(
                "Stored vault bytes must be encrypted",
                rawDiskBytes.decodeToString().contains("EXIF_HEADER_SECRET_IMAGE_PAYLOAD"),
            )

            val decryptedBack = cipher.openDecryptedInputStream(storedFile).use { it.readBytes() }
            assertArrayEquals(payload, decryptedBack)

            repo.deleteEntry(stored)
            sourceDir.deleteRecursively()
        }
    }
}
