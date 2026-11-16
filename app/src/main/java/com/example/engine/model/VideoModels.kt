package com.example.engine.model

enum class PlatformBadge(val label: String) {
    DIRECT("DIRECT"),
    YOUTUBE("YOUTUBE"),
    TIKTOK("TIKTOK"),
    INSTAGRAM("INSTAGRAM"),
    X_TWITTER("X / TWITTER"),
    VIMEO("VIMEO"),
    REDDIT("REDDIT"),
    WEB_MEDIA("WEB MEDIA");

    companion object {
        fun fromUrl(url: String): PlatformBadge {
            val lower = url.lowercase()
            return when {
                lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mov") ||
                    lower.contains(".mkv") || lower.contains(".m4v") || lower.contains(".m3u8") -> DIRECT
                lower.contains("youtube.com") || lower.contains("youtu.be") -> YOUTUBE
                lower.contains("tiktok.com") -> TIKTOK
                lower.contains("instagram.com") -> INSTAGRAM
                lower.contains("twitter.com") || lower.contains("x.com") -> X_TWITTER
                lower.contains("vimeo.com") -> VIMEO
                lower.contains("reddit.com") || lower.contains("v.redd.it") -> REDDIT
                else -> WEB_MEDIA
            }
        }
    }
}

data class ExtractedStream(
    val streamUrl: String,
    val title: String,
    val platform: PlatformBadge,
    val qualityLabel: String,
    val resolutionHeight: Int,
    val mimeType: String,
    val estimatedBytes: Long,
    val headers: Map<String, String> = emptyMap(),
    val audioStreamUrl: String? = null,
    val isHls: Boolean = false,
)

sealed class ExtractionResult {
    data class Success(
        val bestStream: ExtractedStream,
        val candidates: List<ExtractedStream>,
    ) : ExtractionResult()

    data class NeedsAuth(
        val url: String,
        val reason: String,
    ) : ExtractionResult()

    data class Error(val message: String) : ExtractionResult()
}

enum class DownloadPhase {
    IDLE,
    CLIPBOARD_ARMED,
    ABSORBING,
    DOWNLOADING,
    MERGING,
    COMPLETED,
    NEEDS_AUTH,
    ERROR,
}

data class DownloadTelemetry(
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val sourceUrl: String = "",
    val title: String = "",
    val platform: PlatformBadge = PlatformBadge.WEB_MEDIA,
    val qualityLabel: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val progressFraction: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val errorMessage: String? = null,
    val completedVideoId: Long? = null,
    val needsAuthUrl: String? = null,
)
