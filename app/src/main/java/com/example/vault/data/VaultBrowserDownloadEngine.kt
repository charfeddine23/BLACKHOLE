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
import com.example.vault.browser.CandidateResolutionOutcome
import com.example.vault.browser.InterceptedMediaKind
import com.example.vault.browser.InterceptedMediaRequest
import com.example.vault.browser.MediaCandidateResolver
import com.example.vault.browser.ResolvedMediaCandidate
import com.example.vault.browser.VaultWebSnapshot
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
    INSPECTING,
    CANDIDATE_PICKER,
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
    val activePageUrl: String = "",
    val pickerCandidates: List<ResolvedMediaCandidate> = emptyList(),
) {
    val isActive: Boolean
        get() = phase == VaultBrowserDownloadPhase.INSPECTING ||
            phase == VaultBrowserDownloadPhase.EXTRACTING ||
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
    private val candidateResolver: MediaCandidateResolver = MediaCandidateResolver(
        okHttpClient = okHttpClient,
        extractor = extractor,
        universalDownloader = universalDownloader,
        cookieVault = cookieVault,
    ),
) {
    private val appContext = context.applicationContext

    suspend fun downloadPageVideoToVault(
        pageUrl: String,
        pageTitle: String? = null,
        domVideoUrls: List<String> = emptyList(),
        sniffedVideoUrls: List<String> = emptyList(),
        sniffedAudioUrls: List<String> = emptyList(),
        snapshot: VaultWebSnapshot? = null,
        interceptedMedia: List<InterceptedMediaRequest> = emptyList(),
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

        val resolvedPageTitle = snapshot?.pageTitle?.takeIf { it.isNotBlank() } ?: pageTitle

        onState(
            VaultBrowserDownloadState(
                phase = VaultBrowserDownloadPhase.INSPECTING,
                title = resolvedPageTitle?.takeIf { it.isNotBlank() } ?: "",
                activePageUrl = cleanPageUrl,
                message = "جاري فحص الفيديو النشط ومطابقة روابط البث بأعلى جودة...",
            ),
        )

        // Capture any fresh login/session cookies from the live WebView before making HTTP requests
        runCatching { cookieVault.syncFromWebView(cleanPageUrl) }

        val resolution = candidateResolver.resolve(
            targetPageUrl = cleanPageUrl,
            snapshot = snapshot ?: VaultWebSnapshot(
                pageUrl = cleanPageUrl,
                pageTitle = resolvedPageTitle,
                domCandidateUrls = domVideoUrls,
            ),
            interceptedMedia = interceptedMedia,
            extraSniffedVideos = (domVideoUrls + sniffedVideoUrls).distinct(),
            extraSniffedAudios = sniffedAudioUrls.distinct(),
        )

        val candidatesToDownload: List<ExtractedStream> = when (resolution) {
            is CandidateResolutionOutcome.NeedsPicker -> {
                onState(
                    VaultBrowserDownloadState(
                        phase = VaultBrowserDownloadPhase.CANDIDATE_PICKER,
                        title = resolvedPageTitle?.takeIf { it.isNotBlank() } ?: "",
                        activePageUrl = cleanPageUrl,
                        pickerCandidates = resolution.candidates,
                        message = "تم العثور على ${resolution.candidates.size} روابط فيديو — اختر الرابط الذي تريد تحميله:",
                    ),
                )
                return@withContext false
            }
            is CandidateResolutionOutcome.NeedsAuth -> {
                onState(
                    VaultBrowserDownloadState(
                        phase = VaultBrowserDownloadPhase.ERROR,
                        activePageUrl = cleanPageUrl,
                        message = resolution.reason,
                    ),
                )
                return@withContext false
            }
            is CandidateResolutionOutcome.Failed -> {
                onState(
                    VaultBrowserDownloadState(
                        phase = VaultBrowserDownloadPhase.ERROR,
                        activePageUrl = cleanPageUrl,
                        message = resolution.reason,
                    ),
                )
                return@withContext false
            }
            is CandidateResolutionOutcome.Resolved -> {
                listOf(resolution.primary.stream) + resolution.fallbacks.map { it.stream }
            }
        }

        val fallbackAudioUrl = candidatesToDownload.firstNotNullOfOrNull { it.audioStreamUrl }
            ?: interceptedMedia.firstOrNull { !it.isLikelyAd && it.mediaKind == InterceptedMediaKind.AUDIO_STREAM }?.url
            ?: sniffedAudioUrls.firstOrNull()

        executeDownloadPipeline(
            cleanPageUrl = cleanPageUrl,
            pageTitle = resolvedPageTitle,
            combinedCandidates = candidatesToDownload,
            fallbackAudioUrl = fallbackAudioUrl,
            isCancelled = isCancelled,
            onState = onState,
        )
    }

    suspend fun downloadResolvedCandidateToVault(
        candidate: ResolvedMediaCandidate,
        pageUrl: String,
        pageTitle: String? = null,
        fallbackAudioUrl: String? = null,
        isCancelled: () -> Boolean,
        onState: (VaultBrowserDownloadState) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanPageUrl = pageUrl.trim().ifBlank { candidate.stream.streamUrl }
        runCatching { cookieVault.syncFromWebView(cleanPageUrl) }
        executeDownloadPipeline(
            cleanPageUrl = cleanPageUrl,
            pageTitle = pageTitle,
            combinedCandidates = listOf(candidate.stream),
            fallbackAudioUrl = fallbackAudioUrl ?: candidate.stream.audioStreamUrl,
            isCancelled = isCancelled,
            onState = onState,
        )
    }

    private suspend fun executeDownloadPipeline(
        cleanPageUrl: String,
        pageTitle: String?,
        combinedCandidates: List<ExtractedStream>,
        fallbackAudioUrl: String?,
        isCancelled: () -> Boolean,
        onState: (VaultBrowserDownloadState) -> Unit,
    ): Boolean {
        val stagingRoot = File(appContext.cacheDir, "vault_browser_dl").apply {
            mkdirs()
            listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        }

        return try {
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

                val liveCookie = runCatching { CookieManager.getInstance().getCookie(candidate.streamUrl) }.getOrNull()
                    ?: runCatching { CookieManager.getInstance().getCookie(cleanPageUrl) }.getOrNull()
                val cookieOverride = if (!liveCookie.isNullOrBlank()) mapOf("Cookie" to liveCookie) else emptyMap()
                val mergedHeaders = ClientSpoofer.buildDownloadHeaders(
                    streamUrl = candidate.streamUrl,
                    streamHeaders = candidate.headers + mapOf("Referer" to cleanPageUrl) + cookieOverride,
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
