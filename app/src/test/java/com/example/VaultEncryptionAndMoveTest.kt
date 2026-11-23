package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppPreferences
import com.example.data.local.BrowserPreferencesStore
import com.example.data.local.BrowserSearchEngine
import com.example.engine.cookies.CookieVaultManager
import com.example.engine.extractor.VideoStreamExtractor
import com.example.engine.network.DnsPacketCodec
import com.example.ui.navigation.buildMissingStartupRuntimePermissions
import com.example.ui.screens.buildAllFilesAccessSettingIntents
import com.example.ui.screens.launchSystemSettingsCandidates
import com.example.vault.data.FileVaultRandomAccessReader
import com.example.vault.data.SafVaultRepository
import com.example.vault.data.VaultBrowserDownloadEngine
import com.example.vault.data.VaultBrowserDownloadPhase
import com.example.vault.data.VaultCategory
import com.example.vault.data.VaultCipher
import com.example.vault.data.VaultHeaderMetadata
import com.example.vault.data.VaultStorageMode
import com.example.vault.data.VaultTransferStage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

            val sourceUri = Uri.fromFile(originalFile)
            val events = repo.moveMediaToVault(
                sourceUri = sourceUri,
                fallbackCategory = VaultCategory.PHOTOS,
                deleteOriginal = true,
            ).toList()

            val completed = events.lastOrNull { it.stage == VaultTransferStage.COMPLETED }
            assertNotNull(completed)
            assertTrue("Expected original file to be deleted", completed!!.originalDeleted)
            assertFalse("Original file must disappear from phone", originalFile.exists())
            assertTrue("Expected isMediaOriginalGone to confirm removal", repo.isMediaOriginalGone(sourceUri.toString(), originalFile.absolutePath))

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

    @Test
    fun `dns packet codec parses ipv4 udp dns query and builds valid swapped response`() {
        val dnsQuery = byteArrayOf(
            0x12, 0x34, // Transaction ID
            0x01, 0x00, // Standard query
            0x00, 0x01, // QDCOUNT = 1
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00,
            0x00, 0x01, 0x00, 0x01,
        )
        val totalLen = 20 + 8 + dnsQuery.size
        val queryPacket = ByteArray(totalLen)
        queryPacket[0] = 0x45.toByte()
        queryPacket[2] = ((totalLen ushr 8) and 0xFF).toByte()
        queryPacket[3] = (totalLen and 0xFF).toByte()
        queryPacket[8] = 64.toByte()
        queryPacket[9] = 17.toByte() // UDP
        // Src IP: 10.111.111.1 -> Dst IP: 1.1.1.1
        queryPacket[12] = 10; queryPacket[13] = 111; queryPacket[14] = 111; queryPacket[15] = 1
        queryPacket[16] = 1; queryPacket[17] = 1; queryPacket[18] = 1; queryPacket[19] = 1
        // UDP SrcPort: 54321 -> DstPort: 53
        queryPacket[20] = (54321 ushr 8).toByte(); queryPacket[21] = (54321 and 0xFF).toByte()
        queryPacket[22] = 0; queryPacket[23] = 53
        val udpLen = 8 + dnsQuery.size
        queryPacket[24] = (udpLen ushr 8).toByte(); queryPacket[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(dnsQuery, 0, queryPacket, 28, dnsQuery.size)

        val parsed = DnsPacketCodec.parseIpv4UdpDnsPacket(queryPacket, queryPacket.size)
        assertNotNull(parsed)
        assertEquals(54321, parsed!!.srcPort)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(byteArrayOf(10, 111, 111, 1), parsed.srcIp)
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), parsed.dstIp)
        assertArrayEquals(dnsQuery, parsed.dnsPayload)

        val dnsResp = dnsQuery.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }
        val responsePacket = DnsPacketCodec.buildIpv4UdpDnsResponse(parsed, dnsResp)
        assertEquals(20 + 8 + dnsResp.size, responsePacket.size)
        // Src IP should now be 1.1.1.1, Dst IP 10.111.111.1
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), responsePacket.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 111, 111, 1), responsePacket.copyOfRange(16, 20))
        // Src Port = 53, Dst Port = 54321
        assertEquals(53, ((responsePacket[20].toInt() and 0xFF) shl 8) or (responsePacket[21].toInt() and 0xFF))
        assertEquals(54321, ((responsePacket[22].toInt() and 0xFF) shl 8) or (responsePacket[23].toInt() and 0xFF))
        // IPv4 Header checksum verification (sum of header including checksum == 0)
        assertEquals(0, DnsPacketCodec.computeChecksum(responsePacket, 0, 20))
        // Non-DNS traffic must be ignored
        val nonDnsPacket = queryPacket.copyOf().also { it[23] = 80 }
        assertNull(DnsPacketCodec.parseIpv4UdpDnsPacket(nonDnsPacket, nonDnsPacket.size))
    }

    @Test
    fun `browser preferences store persists bookmarks, history, adblock, and search engine`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = BrowserPreferencesStore.getInstance(context)
        store.clearHistory()

        assertTrue(store.adBlockEnabled.value)
        store.setAdBlockEnabled(false)
        assertFalse(store.adBlockEnabled.value)
        store.setAdBlockEnabled(true)

        val initialEngine = store.searchEngine.value
        val nextEngine = store.cycleSearchEngine()
        assertEquals(
            BrowserSearchEngine.entries[(BrowserSearchEngine.entries.indexOf(initialEngine) + 1) % BrowserSearchEngine.entries.size],
            nextEngine,
        )

        val url = "https://example.org/watch?v=42"
        assertTrue(store.toggleBookmark(url, "Example Watch"))
        assertTrue(store.isBookmarked(url))
        assertFalse(store.toggleBookmark(url, "Example Watch"))
        assertFalse(store.isBookmarked(url))

        store.recordHistory("https://kotlinlang.org/", "Kotlin")
        store.recordHistory("https://developer.android.com/", "Android Developers")
        store.recordHistory("https://kotlinlang.org/", "Kotlin Updated")
        assertEquals(2, store.history.value.size)
        assertEquals("https://kotlinlang.org/", store.history.value.first().url)

        store.clearHistory()
        assertTrue(store.history.value.isEmpty())
    }

    @Test
    fun `all files settings intent chain includes per-app, global, and app-details fallbacks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intents = buildAllFilesAccessSettingIntents(context.packageName)
        val actions = intents.mapNotNull { it.action }
        assertTrue(actions.contains(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION))
        assertTrue(actions.contains(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        assertTrue(actions.contains(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
        assertTrue(actions.contains(Settings.ACTION_SETTINGS))

        val perAppIntent = intents.first { it.action == Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION }
        assertEquals("package:${context.packageName}", perAppIntent.data.toString())

        // Verify Robolectric Application context can launch the candidate list with FLAG_ACTIVITY_NEW_TASK fallback
        assertTrue(launchSystemSettingsCandidates(context, intents))

        // Verify empty candidate list cleanly returns false
        assertFalse(launchSystemSettingsCandidates(context, emptyList<Intent>()))
    }

    @Test
    fun `first launch startup permissions collect notifications and media permissions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appPrefs = AppPreferences.getInstance(context)
        assertFalse(appPrefs.hasRequestedStartupPermissions())

        val missing = buildMissingStartupRuntimePermissions(context).toSet()
        assertTrue(missing.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(missing.contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(missing.contains(Manifest.permission.READ_MEDIA_VIDEO))
        assertTrue(missing.contains(Manifest.permission.READ_MEDIA_AUDIO))
        assertTrue(missing.contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

        appPrefs.markStartupPermissionsRequested()
        assertTrue(appPrefs.hasRequestedStartupPermissions())

        assertFalse(appPrefs.hasPromptedMediaStorageSetup())
        appPrefs.markMediaStorageSetupPrompted()
        assertTrue(appPrefs.hasPromptedMediaStorageSetup())
    }

    @Test
    fun `media and storage permission helpers reflect granted permissions accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val perms = com.example.util.buildMediaPermissionsForSdk().toSet()
        assertTrue(perms.contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(perms.contains(Manifest.permission.READ_MEDIA_VIDEO))
        assertTrue(perms.contains(Manifest.permission.READ_MEDIA_AUDIO))
        assertTrue(perms.contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

        assertFalse(com.example.util.hasStrictMediaPermission(context))
        assertFalse(com.example.util.hasAllMediaAndStoragePermissions(context))

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        assertTrue(com.example.util.hasStrictMediaPermission(context))
    }

    @Test
    fun `browser preferences store supports adding, editing, and deleting pinned sites`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = BrowserPreferencesStore.getInstance(context)
        store.resetPinnedSitesToDefault()

        val initialCount = store.pinnedSites.value.size
        assertEquals(BrowserPreferencesStore.DEFAULT_PINNED_SITES.size, initialCount)

        val added = store.addPinnedSite("My Cinema", "example.com/videos")
        assertNotNull(added)
        assertEquals("https://example.com/videos", added!!.url)
        assertEquals(initialCount + 1, store.pinnedSites.value.size)

        val updated = store.updatePinnedSite(added.id, "Cinema HD", "https://videos.example.org/hd")
        assertTrue(updated)
        val foundAfterUpdate = store.pinnedSites.value.firstOrNull { it.id == added.id }
        assertNotNull(foundAfterUpdate)
        assertEquals("Cinema HD", foundAfterUpdate!!.label)
        assertEquals("https://videos.example.org/hd", foundAfterUpdate.url)

        assertTrue(store.deletePinnedSite(added.id))
        assertEquals(initialCount, store.pinnedSites.value.size)
        assertNull(store.pinnedSites.value.firstOrNull { it.id == added.id })
    }

    @Test
    fun `vault browser download engine downloads video from arbitrary page and encrypts directly into vault videos`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val cipher = testCipher(context)
            val repo = SafVaultRepository(context, cipher)
            val fakeMp4Payload = ("FTYPMP4_VAULT_STREAM_DATA_" + "0123456789ABCDEF".repeat(64)).repeat(32).toByteArray()

            val mockHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    val url = req.url.toString()
                    when {
                        url == "https://custom-media-site.example/watch/clip1" -> {
                            val html = """
                                <html>
                                  <head><title>Custom Clip 1080p</title></head>
                                  <body>
                                    <video src="https://cdn.custom-media-site.example/streams/clip_1080p.mp4"></video>
                                  </body>
                                </html>
                            """.trimIndent()
                            Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .header("Content-Type", "text/html; charset=utf-8")
                                .body(html.toResponseBody("text/html".toMediaType()))
                                .build()
                        }
                        url == "https://cdn.custom-media-site.example/streams/clip_1080p.mp4" -> {
                            val isHead = req.method.equals("HEAD", ignoreCase = true)
                            val rangeHeader = req.header("Range")
                            if (isHead) {
                                Response.Builder()
                                    .request(req)
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .header("Content-Type", "video/mp4")
                                    .header("Content-Length", fakeMp4Payload.size.toString())
                                    .body(ByteArray(0).toResponseBody("video/mp4".toMediaType()))
                                    .build()
                            } else if (rangeHeader == "bytes=0-0") {
                                Response.Builder()
                                    .request(req)
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(206)
                                    .message("Partial Content")
                                    .header("Content-Type", "video/mp4")
                                    .header("Content-Range", "bytes 0-0/${fakeMp4Payload.size}")
                                    .header("Content-Length", "1")
                                    .body(fakeMp4Payload.copyOfRange(0, 1).toResponseBody("video/mp4".toMediaType()))
                                    .build()
                            } else {
                                Response.Builder()
                                    .request(req)
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .header("Content-Type", "video/mp4")
                                    .header("Content-Length", fakeMp4Payload.size.toString())
                                    .body(fakeMp4Payload.toResponseBody("video/mp4".toMediaType()))
                                    .build()
                            }
                        }
                        else -> {
                            Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(404)
                                .message("Not Found")
                                .body("".toResponseBody("text/plain".toMediaType()))
                                .build()
                        }
                    }
                }
                .build()

            val cookieVault = CookieVaultManager.getInstance(context)
            val extractor = VideoStreamExtractor(client = mockHttpClient, webViewSniffer = null, cookieVault = cookieVault)
            val engine = VaultBrowserDownloadEngine(
                context = context,
                vaultRepository = repo,
                cookieVault = cookieVault,
                okHttpClient = mockHttpClient,
                extractor = extractor,
            )

            val phases = mutableListOf<VaultBrowserDownloadPhase>()
            val ok = engine.downloadPageVideoToVault(
                pageUrl = "https://custom-media-site.example/watch/clip1",
                pageTitle = "Custom Clip 1080p",
                domVideoUrls = listOf("https://cdn.custom-media-site.example/streams/clip_1080p.mp4"),
                sniffedVideoUrls = emptyList(),
                sniffedAudioUrls = emptyList(),
                isCancelled = { false },
                onState = { state -> phases += state.phase },
            )

            assertTrue("Expected downloadPageVideoToVault to succeed", ok)
            assertTrue(phases.contains(VaultBrowserDownloadPhase.EXTRACTING))
            assertTrue(phases.contains(VaultBrowserDownloadPhase.ENCRYPTING))
            assertEquals(VaultBrowserDownloadPhase.COMPLETED, phases.last())

            val storedEntries = repo.listVaultEntries().filter { it.category == VaultCategory.VIDEOS }
            assertEquals(1, storedEntries.size)
            val stored = storedEntries.first()
            assertTrue(stored.isEncrypted)
            assertTrue(stored.fileName.endsWith(VaultCipher.ENCRYPTED_VAULT_EXT))
            val storedFile = File(stored.localFilePath!!)
            assertTrue(storedFile.exists())
            val decryptedBytes = cipher.openDecryptedInputStream(storedFile).use { it.readBytes() }
            assertArrayEquals(fakeMp4Payload, decryptedBytes)

            repo.deleteEntry(stored)
        }
    }
}
