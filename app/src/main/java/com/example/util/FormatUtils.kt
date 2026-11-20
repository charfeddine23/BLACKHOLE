package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatMb(bytes: Long): String {
    if (bytes <= 0L) return "0.0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}

fun formatByteSize(bytes: Long, zeroLabel: String = "0.0 MB"): String {
    if (bytes <= 0L) return zeroLabel
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}

fun formatTransferSpeed(bytesPerSec: Long): String {
    val mb = bytesPerSec / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB/s", mb)
    } else {
        "${(bytesPerSec / 1024L).coerceAtLeast(1L)} KB/s"
    }
}

fun formatSecondsMmSs(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val mins = safe / 60L
    val secs = safe % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

fun formatDurationMs(durationMs: Long): String {
    return formatSecondsMmSs(durationMs / 1000L)
}

fun formatShortDate(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    return SimpleDateFormat("MMM d", Locale.US).format(Date(timestampMs))
}

fun formatQualityLabel(height: Int): String = when {
    height >= 2160 -> "4K 2160P"
    height >= 1440 -> "2K 1440P"
    height >= 1080 -> "1080P FHD"
    height >= 720 -> "720P HD"
    height > 0 -> "${height}P"
    else -> "BEST HD"
}
