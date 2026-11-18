package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.engine.model.DownloadStatus
import com.example.engine.model.DownloadTaskState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.LiquidGlassFill
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DownloadTaskCard(
    task: DownloadTaskState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onPlayInline: () -> Unit,
    onOpenExternal: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (task.status) {
        DownloadStatus.Completed -> EmeraldPulse
        DownloadStatus.Failed, DownloadStatus.Cancelled -> CrimsonAlert
        DownloadStatus.Merging -> PlasmaViolet
        DownloadStatus.Downloading, DownloadStatus.Extracting -> NeonCyan
        DownloadStatus.Idle -> MutedSlate
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("download_task_${task.taskId}"),
        color = LiquidGlassFill,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            width = 0.85.dp,
            color = when (task.status) {
                DownloadStatus.Completed -> EmeraldPulse.copy(alpha = 0.35f)
                DownloadStatus.Failed -> CrimsonAlert.copy(alpha = 0.4f)
                DownloadStatus.Downloading, DownloadStatus.Merging -> NeonCyan.copy(alpha = 0.45f)
                else -> GlassStrokeBright.copy(alpha = 0.22f)
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Thumbnail Preview
                val thumbModel: Any? = task.localThumbnailPath?.takeIf { File(it).exists() }?.let { File(it) }
                    ?: task.thumbnailUrl?.takeIf { it.startsWith("http") }

                Box(
                    modifier = Modifier
                        .size(width = 72.dp, height = 50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF0A1420), Color(0xFF17112B)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumbModel != null) {
                        AsyncImage(
                            model = thumbModel,
                            contentDescription = "Thumbnail for ${task.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = statusColor.copy(alpha = 0.75f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // Title & Status Telemetry
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            color = statusColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = task.status.displayLabel.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            text = "${task.platform.label} · ${task.qualityLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Contextual Action Buttons (Cancel / Retry / Play / Open / Delete)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        task.isActive -> {
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("cancel_task_${task.taskId}"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Cancel download",
                                    tint = AmberWarning,
                                )
                            }
                        }
                        task.status == DownloadStatus.Failed || task.status == DownloadStatus.Cancelled -> {
                            IconButton(
                                onClick = onRetry,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("retry_task_${task.taskId}"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Retry download",
                                    tint = NeonCyan,
                                )
                            }
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Remove task",
                                    tint = MutedSlate,
                                )
                            }
                        }
                        task.status == DownloadStatus.Completed -> {
                            IconButton(
                                onClick = onPlayInline,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("play_task_${task.taskId}"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Play video",
                                    tint = EmeraldPulse,
                                )
                            }
                            IconButton(
                                onClick = onOpenExternal,
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.OpenInNew,
                                    contentDescription = "Open in gallery player",
                                    tint = ChromeSilver,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete downloaded video",
                                    tint = CrimsonAlert.copy(alpha = 0.85f),
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }

            // Progress Bar + Byte Counters or Error Message
            if (task.isActive) {
                Spacer(modifier = Modifier.height(10.dp))
                if (task.status == DownloadStatus.Extracting || (task.status == DownloadStatus.Downloading && task.totalBytes <= 0L)) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(50)),
                        color = statusColor,
                        trackColor = ObsidianVoid.copy(alpha = 0.8f),
                        strokeCap = StrokeCap.Round,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { task.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(50)),
                        color = statusColor,
                        trackColor = ObsidianVoid.copy(alpha = 0.8f),
                        strokeCap = StrokeCap.Round,
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val sizeLine = if (task.totalBytes > 0L) {
                        "${formatMb(task.downloadedBytes)} / ${formatMb(task.totalBytes)}"
                    } else {
                        formatMb(task.downloadedBytes)
                    }
                    Text(
                        text = sizeLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = ChromeSilver.copy(alpha = 0.85f),
                    )
                    val pctText = "${(task.progress * 100f).roundToInt().coerceIn(0, 100)}%"
                    val speedText = if (task.speedBytesPerSec > 0L) " · ${formatSpeed(task.speedBytesPerSec)}" else ""
                    Text(
                        text = "$pctText$speedText",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                }
            } else if (task.status == DownloadStatus.Completed && task.totalBytes > 0L) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${formatMb(task.totalBytes)} · Saved to Movies/BlackHole",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate,
                )
            } else if (!task.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = task.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = CrimsonAlert,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}

private fun formatSpeed(bytesPerSec: Long): String {
    val mb = bytesPerSec / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB/s", mb)
    } else {
        "${(bytesPerSec / 1024L).coerceAtLeast(1L)} KB/s"
    }
}
