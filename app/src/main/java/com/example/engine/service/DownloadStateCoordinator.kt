package com.example.engine.service

import com.example.engine.model.DownloadPhase
import com.example.engine.model.DownloadTelemetry
import com.example.engine.model.PlatformBadge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object DownloadStateCoordinator {

    private val _telemetry = MutableStateFlow(DownloadTelemetry())
    val telemetry: StateFlow<DownloadTelemetry> = _telemetry.asStateFlow()

    @Volatile
    var cancelRequested: Boolean = false

    fun armClipboardUrl(url: String, platform: PlatformBadge) {
        val current = _telemetry.value
        if (current.phase == DownloadPhase.ABSORBING ||
            current.phase == DownloadPhase.DOWNLOADING ||
            current.phase == DownloadPhase.MERGING
        ) {
            return
        }
        _telemetry.value = DownloadTelemetry(
            phase = if (url.isBlank()) DownloadPhase.IDLE else DownloadPhase.CLIPBOARD_ARMED,
            sourceUrl = url,
            platform = platform,
        )
    }

    fun setAbsorbing(url: String, platform: PlatformBadge) {
        cancelRequested = false
        _telemetry.value = DownloadTelemetry(
            phase = DownloadPhase.ABSORBING,
            sourceUrl = url,
            platform = platform,
            title = "Running multi-strategy stream extraction...",
        )
    }

    fun updateDownloading(
        sourceUrl: String,
        title: String,
        platform: PlatformBadge,
        qualityLabel: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        etaSeconds: Long,
    ) {
        val fraction = if (totalBytes > 0L) {
            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        _telemetry.value = DownloadTelemetry(
            phase = DownloadPhase.DOWNLOADING,
            sourceUrl = sourceUrl,
            title = title,
            platform = platform,
            qualityLabel = qualityLabel,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            progressFraction = fraction,
            speedBytesPerSec = speedBytesPerSec,
            etaSeconds = etaSeconds,
        )
    }

    fun setMerging(
        sourceUrl: String,
        title: String,
        platform: PlatformBadge,
        qualityLabel: String,
        totalBytes: Long,
    ) {
        _telemetry.value = DownloadTelemetry(
            phase = DownloadPhase.MERGING,
            sourceUrl = sourceUrl,
            title = title,
            platform = platform,
            qualityLabel = qualityLabel,
            bytesDownloaded = totalBytes,
            totalBytes = totalBytes,
            progressFraction = 0.96f,
        )
    }

    fun setCompleted(
        sourceUrl: String,
        title: String,
        platform: PlatformBadge,
        qualityLabel: String,
        totalBytes: Long,
        videoId: Long,
    ) {
        _telemetry.value = DownloadTelemetry(
            phase = DownloadPhase.COMPLETED,
            sourceUrl = sourceUrl,
            title = title,
            platform = platform,
            qualityLabel = qualityLabel,
            bytesDownloaded = totalBytes,
            totalBytes = totalBytes,
            progressFraction = 1f,
            completedVideoId = videoId,
        )
    }

    fun setNeedsAuth(sourceUrl: String, platform: PlatformBadge, reason: String) {
        _telemetry.value = DownloadTelemetry(
            phase = DownloadPhase.NEEDS_AUTH,
            sourceUrl = sourceUrl,
            platform = platform,
            errorMessage = reason,
            needsAuthUrl = sourceUrl,
        )
    }

    fun setError(sourceUrl: String, platform: PlatformBadge, message: String) {
        _telemetry.value = DownloadTelemetry(
            phase = DownloadPhase.ERROR,
            sourceUrl = sourceUrl,
            platform = platform,
            errorMessage = message,
        )
    }

    fun resetToArmedOrIdle(url: String, platform: PlatformBadge) {
        cancelRequested = false
        _telemetry.update {
            DownloadTelemetry(
                phase = if (url.isBlank()) DownloadPhase.IDLE else DownloadPhase.CLIPBOARD_ARMED,
                sourceUrl = url,
                platform = platform,
            )
        }
    }
}
