package com.example.engine.model

import kotlinx.serialization.Serializable

enum class DownloadStatus(val displayLabel: String) {
    Idle("Idle"),
    Extracting("Extracting"),
    Downloading("Downloading"),
    Merging("Merging"),
    Completed("Completed"),
    Failed("Failed"),
    Cancelled("Cancelled"),
}

@Serializable
data class VideoQualityOption(
    val id: String,
    val qualityLabel: String,
    val resolutionHeight: Int,
    val videoUrl: String,
    val audioUrl: String? = null,
    val mimeType: String = "video/mp4",
    val fileSizeBytes: Long = -1L,
    val headers: Map<String, String> = emptyMap(),
    val isHls: Boolean = false,
    val bitrateBps: Long = 0L,
    val fps: Int = 30,
) {
    val requiresMuxing: Boolean
        get() = !audioUrl.isNullOrBlank()
}

data class ExtractedMediaInfo(
    val sourceUrl: String,
    val canonicalUrl: String,
    val platform: PlatformBadge,
    val title: String,
    val author: String = "",
    val thumbnailUrl: String? = null,
    val durationSeconds: Long = 0L,
    val qualityOptions: List<VideoQualityOption>,
)

sealed interface MediaExtractionResult {
    data class Success(val media: ExtractedMediaInfo) : MediaExtractionResult
    data class NeedsAuth(val url: String, val reason: String) : MediaExtractionResult
    data class Error(val message: String) : MediaExtractionResult
}

data class DownloadTaskState(
    val taskId: String,
    val sourceUrl: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val platform: PlatformBadge,
    val qualityLabel: String,
    val videoUrl: String,
    val audioUrl: String? = null,
    val mimeType: String = "video/mp4",
    val headersJson: String = "{}",
    val isHls: Boolean = false,
    val status: DownloadStatus = DownloadStatus.Idle,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSec: Long = 0L,
    val mediaStoreUri: String? = null,
    val localFilePath: String? = null,
    val localThumbnailPath: String? = null,
    val errorMessage: String? = null,
    val savedVideoId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isActive: Boolean
        get() = status == DownloadStatus.Extracting ||
            status == DownloadStatus.Downloading ||
            status == DownloadStatus.Merging
}
