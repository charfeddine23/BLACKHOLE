package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.engine.model.ExtractedMediaInfo
import com.example.engine.model.VideoQualityOption
import com.example.ui.theme.AbyssalSurface
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CyanGlowSoft
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.LiquidGlassFill
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelectionBottomSheet(
    media: ExtractedMediaInfo,
    onDismiss: () -> Unit,
    onDownloadSelected: (ExtractedMediaInfo, VideoQualityOption) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedOption by remember(media) {
        mutableStateOf(media.qualityOptions.firstOrNull())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AbyssalSurface,
        contentColor = ChromeSilver,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .testTag("quality_selection_sheet"),
        ) {
            // Video Preview Header: Thumbnail, Title, Duration, Platform Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(124.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF0C1624), Color(0xFF1A1230)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!media.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = media.thumbnailUrl,
                            contentDescription = "Thumbnail for ${media.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = NeonCyan.copy(alpha = 0.7f),
                            modifier = Modifier.size(30.dp),
                        )
                    }

                    if (media.durationSeconds > 0L) {
                        Surface(
                            color = ObsidianVoid.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp),
                        ) {
                            Text(
                                text = formatDurationSeconds(media.durationSeconds),
                                style = MaterialTheme.typography.labelSmall,
                                color = ChromeSilver,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        color = CyanGlowSoft.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(0.75.dp, NeonCyan.copy(alpha = 0.65f)),
                    ) {
                        Text(
                            text = media.platform.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = media.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ChromeSilver,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (media.author.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = media.author,
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "SELECT RESOLUTION",
                style = MaterialTheme.typography.labelLarge,
                color = MutedSlate,
            )

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((media.qualityOptions.size.coerceAtMost(4) * 68).dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(media.qualityOptions, key = { it.id }) { option ->
                    val isSelected = selectedOption?.id == option.id
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { selectedOption = option }
                            .testTag("quality_option_${option.resolutionHeight}"),
                        color = if (isSelected) Color(0xFF0C2230) else LiquidGlassFill,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            width = if (isSelected) 1.25.dp else 0.75.dp,
                            color = if (isSelected) NeonCyan else GlassStrokeBright.copy(alpha = 0.25f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.HighQuality,
                                    contentDescription = null,
                                    tint = if (isSelected) NeonCyan else MutedSlate,
                                    modifier = Modifier.size(22.dp),
                                )
                                Column {
                                    Text(
                                        text = option.qualityLabel,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = ChromeSilver,
                                    )
                                    val metaParts = buildList {
                                        add(if (option.requiresMuxing) "Auto-Mux MP4" else "Direct MP4")
                                        if (option.fileSizeBytes > 0L) add(formatSizeBytes(option.fileSizeBytes))
                                        else add("Dynamic Size")
                                    }.joinToString(" · ")
                                    Text(
                                        text = metaParts,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (option.requiresMuxing) PlasmaViolet.copy(alpha = 0.9f) else MutedSlate,
                                    )
                                }
                            }
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedOption = option },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = NeonCyan,
                                    unselectedColor = MutedSlate,
                                ),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    selectedOption?.let { opt -> onDownloadSelected(media, opt) }
                },
                enabled = selectedOption != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("confirm_download_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = ObsidianVoid,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "DOWNLOAD TO MOVIES/BLACKHOLE",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun formatDurationSeconds(seconds: Long): String {
    val hrs = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hrs > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}

private fun formatSizeBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}
