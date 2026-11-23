package com.example.vault.data

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import com.example.engine.UniversalDownloader
import com.example.engine.cookies.CookieVaultManager
import com.example.engine.extractor.MetaVideoPowerExtractor
import com.example.engine.extractor.VideoStreamExtractor
import com.example.engine.extractor.WebViewStreamSniffer
import com.example.engine.media.HlsSegmentDownloader
import com.example.engine.media.LocalMediaMuxer
import com.example.engine.media.TurboHttpDownloader
import com.example.engine.model.ExtractedStream
import com.example.engine.model.ExtractionResult
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
import com.example.util.formatQualityLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class VaultBrowserDownloadPhase {
    IDLE,
    EXTRACTING,
    DOWNLOADING,
    MERGING,
    ENCRYPTING,
    COMPLETED,
    ERROR,
}

data class VaultBrowserDownloadState(
    val phase: VaultBrowserDownloadPhase = VaultBrowserDownloadPhase.IDLE,
    val progress: Float = 0f,
    val title: String = "",
    val qualityLabel: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSec: Long = 0L,
    val message: String? = null,
) {
    val isActive: Boolean
        get() = phase == VaultBrowserDownloadPhase.EXTRACTING ||
            phase == VaultBrowserDownloadPhase.DOWNLOADING ||
            phase == VaultBrowserDownloadPhase.MERGING ||
            phase == VaultBrowserDownloadPhase.ENCRYPTING
}

/**
 * Universal video extraction and direct AES-256 encrypted Vault downloader for Vault's Private Browser.
 * Extracts highest-quality streams from any site (UniversalDownloader + live WebView DOM & network
 * sniffed candidates), muxes video+audio locally if split, and encrypts directly into VaultCategory.VIDEOS
 * without exposing plaintext files to MediaStore or the system Gallery.
 */
class VaultBrowserDownloadEngine(
    context: Context,
    private val vaultRepository: SafVaultRepository,
    private val cookieVault: CookieVaultManager = CookieVaultManager.getInstance(context.applicationContext),
    private val okHttpClient: OkHttpClient = VideoStreamExtractor.createWithVault(cookieVault),
    private val webViewSniffer: WebViewStreamSniffer = WebViewStreamSniffer(context.applicationContext, cookieVault),
    private val extractor: VideoStreamExtractor = VideoStreamExtractor(okHttpClient, webViewSniffer, cookieVault),
    private val universalDownloader: UniversalDownloader = UniversalDownloader(
        context = context.applicationContext,
        cookieVault = cookieVault,
        httpClient = okHttpClient,
        webViewSniffer = webViewSniffer,
        streamExtractor = extractor,
    ),
    private val hlsDownloader: HlsSegmentDownloader = HlsSegmentDownloader(okHttpClient),
) {
    private val appContext = context.applicationContext

    suspend fun downloadPageVideoToVault(
        pageUrl: String,
        pageTitle: String?,
        domVideoUrls: List<String>,
        sniffedVideoUrls: List<String>,
        sniffedAudioUrls: List<String>,
        isCancelled: () -> Boolean,
        onState: (VaultBrowserDownloadState) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanPageUrl = pageUrl.trim()
        if (!cleanPageUrl.startsWith("http://", ignoreCase = true) &&
            !cleanPageUrl.startsWith("https://", ignoreCase = true)
        ) {
            onState(
                VaultBrowserDownloadState(
                    phase = VaultBrowserDownloadPhase.ERROR,
                    message = "أدخل رابط الفيديو في الشريط بالأعلى أو افتح صفحة فيديو أولاً ثم اضغط زر التحميل.",
                ),
            )
            return@withContext false
        }

        onState(
            VaultBrowserDownloadState(
                phase = VaultBrowserDownloadPhase.EXTRACTING,
                title = pageTitle?.takeIf { it.isNotBlank() } ?: "",
                message = "جاري البحث عن الفيديو بأعلى جودة...",
            ),
        )

        // Capture any fresh login/session cookies from the live WebView before making HTTP requests
        runCatching { cookieVault.syncFromWebView(cleanPageUrl) }

        val platform = PlatformBadge.fromUrl(cleanPageUrl)
        val routedOutcome = runCatching { universalDownloader.routeAndExtract(cleanPageUrl) }.getOrNull()
        val routedStreams = when (val res = routedOutcome?.result) {
            is ExtractionResult.Success -> res.candidates.ifEmpty { listOf(res.bestStream) }
            else -> emptyList()
        }

        // Probe live WebView DOM + intercepted network URLs as universal fallback/upgrade
        val liveSniffedStreams = probeLiveCapturedMedia(
            pageUrl = routedOutcome?.canonicalUrl ?: cleanPageUrl,
            pageTitle = pageTitle,
            platform = routedOutcome?.platform ?: platform,
            candidateUrls = (domVideoUrls + sniffedVideoUrls).distinct().take(16),
            audioUrls = sniffedAudioUrls.distinct(),
        )

        val combinedCandidates = extractor.rankBestStreams(routedStreams + liveSniffedStreams)
            .take(6)

        if (combinedCandidates.isEmpty()) {
            val errorMsg = when (val res = routedOutcome?.result) {
                is ExtractionResult.NeedsAuth ->
                    "يتطلب هذا الفيديو تسجيل الدخول في المتصفح ثم الضغط مجدداً على دائرة الثقب الأسود."
                is ExtractionResult.Error ->
                    "تعذر العثور على فيديو قابل للتحميل في هذه الصفحة — شغّل الفيديو أولاً ثم اضغط الدائرة."
                else ->
                    "لم يتم رصد أي رابط فيديو في الصفحة الحالية — شغّل الفيديو لثانية ثم حاول."
            }
            onState(
                VaultBrowserDownloadState(
                    phase = VaultBrowserDownloadPhase.ERROR,
                    message = errorMsg,
                ),
            )
            return@withContext false
        }

        val fallbackAudioUrl = combinedCandidates.firstNotNullOfOrNull { it.audioStreamUrl }
            ?: sniffedAudioUrls.firstOrNull()

        val stagingRoot = File(appContext.cacheDir, "vault_browser_dl").apply {
            mkdirs()
            listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        }

        try {
            var completed = false
            for ((index, candidate) in combinedCandidates.withIndex()) {
                if (isCancelled()) throw CancellationException("Cancelled")
                val hasLaterCandidate = index < combinedCandidates.lastIndex
                val resolvedTitle = buildSafeVideoTitle(
                    streamTitle = candidate.title,
                    pageTitle = pageTitle,
                    fallbackPlatform = candidate.platform,
                )
                val extension = if (candidate.isHls) "mp4" else extensionForMime(candidate.mimeType)
                val stagingFile = File(stagingRoot, "${resolvedTitle}_${System.currentTimeMillis()}.$extension")

                val mergedHeaders = ClientSpoofer.buildDownloadHeaders(
                    streamUrl = candidate.streamUrl,
                    streamHeaders = candidate.headers + mapOf("Referer" to cleanPageUrl),
                )

                onState(
                    VaultBrowserDownloadState(
                        phase = VaultBrowserDownloadPhase.DOWNLOADING,
                        progress = 0.02f,
                        title = resolvedTitle,
                        qualityLabel = candidate.qualityLabel,
                        message = "جاري تحميل الفيديو (${candidate.qualityLabel})...",
                    ),
                )

                val downloadedOk = when {
                    candidate.isHls -> {
                        downloadHlsToStaging(
                            stream = candidate.copy(title = resolvedTitle),
                            headers = mergedHeaders,
                            outputFile = stagingFile,
                            isCancelled = isCancelled,
                            onState = onState,
                        )
                    }
                    !candidate.audioStreamUrl.isNullOrBlank() -> {
                        downloadSplitToStaging(
                            stream = candidate.copy(title = resolvedTitle),
                            audioUrl = candidate.audioStreamUrl,
                            headers = mergedHeaders,
                            outputFile = stagingFile,
                            stagingRoot = stagingRoot,
                            isCancelled = isCancelled,
                            onState = onState,
                        )
                    }
                    else -> {
                        downloadProgressiveToStaging(
                            stream = candidate.copy(title = resolvedTitle),
                            headers = mergedHeaders,
                            outputFile = stagingFile,
                            isCancelled = isCancelled,
                            onState = onState,
                        )
                    }
                }

                if (!downloadedOk || !stagingFile.exists() || stagingFile.length() <= 16_384L) {
                    runCatching { stagingFile.delete() }
                    continue
                }

                // Ensure split Meta/YouTube progressive streams have audio
                if (candidate.audioStreamUrl.isNullOrBlank() &&
                    (candidate.platform == PlatformBadge.INSTAGRAM ||
                        candidate.platform == PlatformBadge.FACEBOOK ||
                        candidate.platform == PlatformBadge.YOUTUBE) &&
                    !LocalMediaMuxer.hasAudioTrack(stagingFile)
                ) {
                    var recovered = false
                    if (!fallbackAudioUrl.isNullOrBlank()) {
                        val audioTemp = File(stagingRoot, "audio_fix_${System.currentTimeMillis()}.mp4")
                        val muxedTemp = File(stagingRoot, "muxed_fix_${System.currentTimeMillis()}.mp4")
                        try {
                            val audioOk = TurboHttpDownloader.downloadToFile(
                                client = okHttpClient,
                                url = fallbackAudioUrl,
                                headers = mergedHeaders,
                                destination = audioTemp,
                                isCancelled = isCancelled,
                                onBytes = { _, _ -> },
                            )
                            if (audioOk &&
                                LocalMediaMuxer.mergeVideoAndAudio(stagingFile, audioTemp, muxedTemp) &&
                                LocalMediaMuxer.hasAudioTrack(muxedTemp)
                            ) {
                                muxedTemp.copyTo(stagingFile, overwrite = true)
                                recovered = true
                            }
                        } finally {
                            runCatching { audioTemp.delete() }
                            runCatching { muxedTemp.delete() }
                        }
                    }
                    if (!recovered && hasLaterCandidate) {
                        runCatching { stagingFile.delete() }
                        continue
                    }
                }

                // Encrypt directly into Vault Videos folder (AES-256)
                onState(
                    VaultBrowserDownloadState(
                        phase = VaultBrowserDownloadPhase.ENCRYPTING,
                        progress = 0.9f,
                        title = resolvedTitle,
                        qualityLabel = candidate.qualityLabel,
                        bytesDownloaded = stagingFile.length(),
                        totalBytes = stagingFile.length(),
                        message = "جاري التشفير بـ AES-256 والحفظ في مجلد الفيديوهات بالخزنة...",
                    ),
                )

                var storedInVault = false
                vaultRepository.moveMediaToVault(
                    sourceUri = Uri.fromFile(stagingFile),
                    fallbackCategory = VaultCategory.VIDEOS,
                    deleteOriginal = true,
                ).collect { transfer ->
                    when (transfer.stage) {
                        VaultTransferStage.COMPLETED -> {
                            storedInVault = true
                        }
                        else -> {
                            onState(
                                VaultBrowserDownloadState(
                                    phase = VaultBrowserDownloadPhase.ENCRYPTING,
                                    progress = (0.88f + transfer.fraction * 0.12f).coerceIn(0.88f, 0.99f),
                                    title = resolvedTitle,
                                    qualityLabel = candidate.qualityLabel,
                                    bytesDownloaded = transfer.bytesProcessed,
                                    totalBytes = transfer.totalBytes,
                                    message = "جاري التشفير بـ AES-256 والحفظ في مجلد الفيديوهات بالخزنة...",
                                ),
                            )
                        }
                    }
                }

                if (storedInVault) {
                    onState(
                        VaultBrowserDownloadState(
                            phase = VaultBrowserDownloadPhase.COMPLETED,
                            progress = 1f,
                            title = resolvedTitle,
                            qualityLabel = candidate.qualityLabel,
                            message = "تم التحميل (${candidate.qualityLabel}) والتشفير في مجلد الفيديوهات بالخزنة.",
                        ),
                    )
                    completed = true
                    break
                }
            }

            if (!completed) {
                onState(
                    VaultBrowserDownloadState(
                        phase = VaultBrowserDownloadPhase.ERROR,
                        message = "تعذر تحميل الفيديو من الخادم — جرب تشغيل الفيديو أولاً أو التأكد من الرابط.",
                    ),
                )
            }
            completed
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            onState(
                VaultBrowserDownloadState(
                    phase = VaultBrowserDownloadPhase.ERROR,
                    message = t.message ?: "فشل تحميل الفيديو إلى الخزنة.",
                ),
            )
            false
        } finally {
            runCatching { stagingRoot.deleteRecursively() }
        }
    }

    private fun probeLiveCapturedMedia(
        pageUrl: String,
        pageTitle: String?,
        platform: PlatformBadge,
        candidateUrls: List<String>,
        audioUrls: List<String>,
    ): List<ExtractedStream> {
        if (candidateUrls.isEmpty()) return emptyList()
        val out = mutableListOf<ExtractedStream>()
        val fallbackAudio = audioUrls.firstOrNull()
        val webViewCookie = runCatching { CookieManager.getInstance().getCookie(pageUrl) }.getOrNull()
        val headers = ClientSpoofer.buildHeadersForUrl(
            targetUrl = pageUrl,
            profile = ClientSpoofer.profiles.first(),
            refererOverride = pageUrl,
        ).toMutableMap().apply {
            if (!webViewCookie.isNullOrBlank()) {
                put("Cookie", webViewCookie)
            }
        }

        for (rawUrl in candidateUrls) {
            if (!rawUrl.startsWith("http://", ignoreCase = true) &&
                !rawUrl.startsWith("https://", ignoreCase = true)
            ) {
                continue
            }
            if (MetaVideoPowerExtractor.isMetaAudioOnlyUrl(rawUrl)) continue

            val probed = extractor.probeDirectMediaOrHls(rawUrl, platform, headers)
            if (probed != null) {
                if (probed.estimatedBytes in 1L..16_383L) continue
                val needsAudio = fallbackAudio != null && !probed.isHls && (
                    rawUrl.contains("mime=video", ignoreCase = true) ||
                        platform == PlatformBadge.YOUTUBE ||
                        MetaVideoPowerExtractor.isMetaVideoOnlyDashUrl(rawUrl) ||
                        ((platform == PlatformBadge.INSTAGRAM || platform == PlatformBadge.FACEBOOK) &&
                            !MetaVideoPowerExtractor.isMetaProgressiveUrl(rawUrl))
                    )
                out.add(
                    probed.copy(
                        title = pageTitle?.takeIf { it.isNotBlank() } ?: probed.title,
                        headers = headers,
                        audioStreamUrl = if (needsAudio) fallbackAudio else probed.audioStreamUrl,
                    ),
                )
            } else if (extractor.looksLikeHlsUrl(rawUrl) || extractor.looksLikeDownloadableVideoUrl(rawUrl)) {
                val isHls = extractor.looksLikeHlsUrl(rawUrl)
                val height = extractor.inferResolutionHeight(rawUrl)
                val needsAudio = !isHls && fallbackAudio != null && (
                    rawUrl.contains("mime=video", ignoreCase = true) ||
                        platform == PlatformBadge.YOUTUBE ||
                        MetaVideoPowerExtractor.isMetaVideoOnlyDashUrl(rawUrl)
                    )
                out.add(
                    ExtractedStream(
                        streamUrl = rawUrl,
                        title = pageTitle?.takeIf { it.isNotBlank() } ?: extractor.deriveTitleFromUrl(pageUrl),
                        platform = platform,
                        qualityLabel = formatQualityLabel(height),
                        resolutionHeight = height,
                        mimeType = if (isHls) "application/vnd.apple.mpegurl" else extractor.resolveMimeType("", rawUrl),
                        estimatedBytes = -1L,
                        headers = headers,
                        audioStreamUrl = if (needsAudio) fallbackAudio else null,
                        isHls = isHls,
                    ),
                )
            }
        }
        return extractor.expandHlsCandidates(out)
    }

    private suspend fun downloadHlsToStaging(
        stream: ExtractedStream,
        headers: Map<String, String>,
        outputFile: File,
        isCancelled: () -> Boolean,
        onState: (VaultBrowserDownloadState) -> Unit,
    ): Boolean {
        return hlsDownloader.downloadHlsToFile(
            playlistUrl = stream.streamUrl,
            headers = headers,
            outputFile = outputFile,
            isCancelled = isCancelled,
            onProgress = { downloaded, estimatedTotal, speed ->
                val rawProgress = if (estimatedTotal > 0L) {
                    (downloaded.toFloat() / estimatedTotal.toFloat()).coerceIn(0f, 1f)
                } else {
                    0.35f
                }
                onState(
                    VaultBrowserDownloadState(
                        phase = VaultBrowserDownloadPhase.DOWNLOADING,
                        progress = (0.04f + rawProgress * 0.82f).coerceIn(0.04f, 0.86f),
                        title = stream.title,
                        qualityLabel = stream.qualityLabel,
                        bytesDownloaded = downloaded,
                        totalBytes = estimatedTotal,
                        speedBytesPerSec = speed,
                        message = "جاري تحميل الفيديو (${stream.qualityLabel})...",
                    ),
                )
            },
            audioPlaylistUrl = stream.audioStreamUrl,
        )
    }

    private suspend fun downloadSplitToStaging(
        stream: ExtractedStream,
        audioUrl: String,
        headers: Map<String, String>,
        outputFile: File,
        stagingRoot: File,
        isCancelled: () -> Boolean,
        onState: (VaultBrowserDownloadState) -> Unit,
    ): Boolean = coroutineScope {
        val videoTemp = File(stagingRoot, "split_v_${System.currentTimeMillis()}.mp4")
        val audioTemp = File(stagingRoot, "split_a_${System.currentTimeMillis()}.mp4")
        val videoBytes = AtomicLong(0L)
        val audioBytes = AtomicLong(0L)
        val videoTotal = AtomicLong(0L)
        val audioTotal = AtomicLong(0L)
        val lastTick = AtomicLong(System.currentTimeMillis())
        val lastBytes = AtomicLong(0L)

        fun reportCombined() {
            val now = System.currentTimeMillis()
            val prev = lastTick.get()
            val elapsed = now - prev
            if (elapsed >= 200L && lastTick.compareAndSet(prev, now)) {
                val downloaded = videoBytes.get() + audioBytes.get()
                val total = (videoTotal.get() + audioTotal.get()).takeIf { it > 0L } ?: -1L
                val prevBytes = lastBytes.getAndSet(downloaded)
                val speed = if (elapsed > 0L) ((downloaded - prevBytes) * 1000L) / elapsed else 0L
                val rawProgress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.3f
                onState(
                    VaultBrowserDownloadState(
                        phase = VaultBrowserDownloadPhase.DOWNLOADING,
                        progress = (0.04f + rawProgress * 0.78f).coerceIn(0.04f, 0.82f),
                        title = stream.title,
                        qualityLabel = stream.qualityLabel,
                        bytesDownloaded = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speed,
                        message = "جاري تحميل الفيديو والصوت (${stream.qualityLabel})...",
                    ),
                )
            }
        }

        try {
            val vJob = async(Dispatchers.IO) {
                TurboHttpDownloader.downloadToFile(
                    client = okHttpClient,
                    url = stream.streamUrl,
                    headers = headers,
                    destination = videoTemp,
                    isCancelled = isCancelled,
                    onBytes = { read, total ->
                        videoBytes.set(read)
                        if (total > 0L) videoTotal.set(total)
                        reportCombined()
                    },
                )
            }
            val aJob = async(Dispatchers.IO) {
                TurboHttpDownloader.downloadToFile(
                    client = okHttpClient,
                    url = audioUrl,
                    headers = headers,
                    destination = audioTemp,
                    isCancelled = isCancelled,
                    onBytes = { read, total ->
                        audioBytes.set(read)
                        if (total > 0L) audioTotal.set(total)
                        reportCombined()
                    },
                )
            }

            val vOk = vJob.await()
            val aOk = aJob.await()
            if (!vOk) return@coroutineScope false

            if (!aOk || !audioTemp.exists() || audioTemp.length() <= 0L) {
                if (LocalMediaMuxer.hasAudioTrack(videoTemp)) {
                    videoTemp.copyTo(outputFile, overwrite = true)
                    return@coroutineScope true
                }
                return@coroutineScope false
            }

            onState(
                VaultBrowserDownloadState(
                    phase = VaultBrowserDownloadPhase.MERGING,
                    progress = 0.85f,
                    title = stream.title,
                    qualityLabel = stream.qualityLabel,
                    bytesDownloaded = videoTemp.length() + audioTemp.length(),
                    totalBytes = videoTemp.length() + audioTemp.length(),
                    message = "جاري دمج الفيديو والصوت بأعلى جودة...",
                ),
            )

            val muxed = LocalMediaMuxer.mergeVideoAndAudio(videoTemp, audioTemp, outputFile)
            if (!muxed) {
                if (LocalMediaMuxer.hasAudioTrack(videoTemp)) {
                    videoTemp.copyTo(outputFile, overwrite = true)
                    return@coroutineScope true
                }
                return@coroutineScope false
            }
            outputFile.exists() && outputFile.length() > 0L
        } finally {
            runCatching { videoTemp.delete() }
            runCatching { audioTemp.delete() }
        }
    }

    private suspend fun downloadProgressiveToStaging(
        stream: ExtractedStream,
        headers: Map<String, String>,
        outputFile: File,
        isCancelled: () -> Boolean,
        onState: (VaultBrowserDownloadState) -> Unit,
    ): Boolean {
        var lastTick = System.currentTimeMillis()
        var lastBytes = 0L
        return TurboHttpDownloader.downloadToFile(
            client = okHttpClient,
            url = stream.streamUrl,
            headers = headers,
            destination = outputFile,
            isCancelled = isCancelled,
            onBytes = { downloaded, reportedTotal ->
                val total = reportedTotal.takeIf { it > 0L } ?: stream.estimatedBytes
                val now = System.currentTimeMillis()
                val elapsed = now - lastTick
                if (elapsed >= 200L) {
                    val speed = if (elapsed > 0L) ((downloaded - lastBytes) * 1000L) / elapsed else 0L
                    lastTick = now
                    lastBytes = downloaded
                    val rawProgress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.35f
                    onState(
                        VaultBrowserDownloadState(
                            phase = VaultBrowserDownloadPhase.DOWNLOADING,
                            progress = (0.04f + rawProgress * 0.82f).coerceIn(0.04f, 0.86f),
                            title = stream.title,
                            qualityLabel = stream.qualityLabel,
                            bytesDownloaded = downloaded,
                            totalBytes = total,
                            speedBytesPerSec = speed,
                            message = "جاري تحميل الفيديو (${stream.qualityLabel})...",
                        ),
                    )
                }
            },
        )
    }

    private fun buildSafeVideoTitle(
        streamTitle: String,
        pageTitle: String?,
        fallbackPlatform: PlatformBadge,
    ): String {
        val chosen = pageTitle
            ?.takeIf {
                it.isNotBlank() &&
                    !it.equals("New tab", ignoreCase = true) &&
                    !it.equals("Private", ignoreCase = true) &&
                    !it.startsWith("http", ignoreCase = true)
            }
            ?: streamTitle
        val clean = chosen
            .replace(Regex("""[^\p{L}\p{N}_\-\s]"""), " ")
            .trim()
            .replace(Regex("""\s+"""), "_")
            .take(44)
            .trim('_')
        return clean.ifBlank { "${fallbackPlatform.label.replace(" ", "_")}_Video" }
    }

    private fun extensionForMime(mimeType: String): String {
        return when {
            mimeType.contains("webm", ignoreCase = true) -> "webm"
            mimeType.contains("quicktime", ignoreCase = true) -> "mov"
            mimeType.contains("matroska", ignoreCase = true) -> "mkv"
            else -> "mp4"
        }
    }
}
