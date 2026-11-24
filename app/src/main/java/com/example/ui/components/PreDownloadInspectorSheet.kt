package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.util.formatByteSize
import com.example.util.formatTransferSpeed
import com.example.vault.browser.InspectedStreamMetadata
import com.example.vault.browser.VaultInspectorUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Material 3 Pre-Download Inspector & Session-Aware Confirmation ModalBottomSheet.
 * Prevents wrong-video downloads by displaying fresh video metadata, thumbnail preview, duration,
 * resolved URL, Logcat verification details, candidate chooser, and manual URL override BEFORE
 * initiating the Vault download + AES-256 encryption pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreDownloadInspectorSheet(
    state: VaultInspectorUiState,
    ytDlpBackendUrl: String,
    onSelectCandidate: (Int) -> Unit,
    onReInspectUrl: (String) -> Unit,
    onConfirmDownload: (overrideStreamUrl: String?) -> Unit,
    onSaveYtDlpBackendUrl: (String) -> Unit,
    onCancelOrDismiss: () -> Unit,
) {
    if (state is VaultInspectorUiState.Idle) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onCancelOrDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0A111D),
        contentColor = ChromeSilver,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(GlassStrokeBright.copy(alpha = 0.35f)),
            )
        },
        modifier = Modifier.testTag("pre_download_inspector_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(NeonCyan.copy(alpha = 0.22f), PlasmaViolet.copy(alpha = 0.22f)),
                                ),
                            )
                            .border(0.8.dp, NeonCyan.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column {
                        Text(
                            text = "فحص الفيديو قبل التحميل",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = ChromeSilver,
                        )
                        Text(
                            text = "معاينة حية وتأكيد الرابط لمنع تحميل أي فيديو خاطئ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlate,
                        )
                    }
                }
                IconButton(
                    onClick = onCancelOrDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("inspector_close_icon_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "إغلاق الفحص",
                        tint = MutedSlate,
                    )
                }
            }

            when (state) {
                is VaultInspectorUiState.Inspecting -> {
                    InspectingSection(
                        pageUrl = state.pageUrl,
                        hasActiveVideo = state.snapshot?.activeVideo != null,
                        onCancel = onCancelOrDismiss,
                    )
                }

                is VaultInspectorUiState.PreviewReady -> {
                    PreviewReadySection(
                        metadata = state.metadata,
                        ytDlpBackendUrl = ytDlpBackendUrl,
                        onSelectCandidate = onSelectCandidate,
                        onReInspectUrl = onReInspectUrl,
                        onConfirmDownload = onConfirmDownload,
                        onSaveYtDlpBackendUrl = onSaveYtDlpBackendUrl,
                        onCancel = onCancelOrDismiss,
                    )
                }

                is VaultInspectorUiState.Downloading -> {
                    DownloadingSection(
                        state = state,
                        onCancel = onCancelOrDismiss,
                    )
                }

                is VaultInspectorUiState.Completed -> {
                    CompletedSection(
                        title = state.title,
                        onDone = onCancelOrDismiss,
                    )
                }

                is VaultInspectorUiState.Failed -> {
                    ErrorOrDrmSection(
                        isDrm = false,
                        title = "تعذر فحص رابط الفيديو",
                        reason = state.reason,
                        initialUrl = state.lastUrlInput,
                        debugDetails = state.debugDetails,
                        onRetryUrl = onReInspectUrl,
                        onDismiss = onCancelOrDismiss,
                    )
                }

                is VaultInspectorUiState.UnsupportedOrDrm -> {
                    ErrorOrDrmSection(
                        isDrm = true,
                        title = "بث محمي (DRM / غير مدعوم)",
                        reason = state.reason,
                        initialUrl = state.pageUrl,
                        debugDetails = state.debugDetails,
                        onRetryUrl = onReInspectUrl,
                        onDismiss = onCancelOrDismiss,
                    )
                }

                VaultInspectorUiState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun InspectingSection(
    pageUrl: String,
    hasActiveVideo: Boolean,
    onCancel: () -> Unit,
) {
    Surface(
        color = Color(0xFF101929),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.8.dp, NeonCyan.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                color = NeonCyan,
                strokeWidth = 3.dp,
                modifier = Modifier.size(38.dp),
            )
            Text(
                text = if (hasActiveVideo) {
                    "جاري تحليل الفيديو النشط والتحقق من الروابط والجلسة..."
                } else {
                    "جاري فحص الصفحة واستخراج روابط الفيديو المتاحة..."
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = ChromeSilver,
            )
            if (pageUrl.isNotBlank()) {
                Text(
                    text = pageUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = onCancel,
                border = BorderStroke(1.dp, GlassStrokeBright.copy(alpha = 0.35f)),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("inspector_cancel_button"),
            ) {
                Text("إلغاء", color = ChromeSilver)
            }
        }
    }
}

@Composable
private fun PreviewReadySection(
    metadata: InspectedStreamMetadata,
    ytDlpBackendUrl: String,
    onSelectCandidate: (Int) -> Unit,
    onReInspectUrl: (String) -> Unit,
    onConfirmDownload: (overrideStreamUrl: String?) -> Unit,
    onSaveYtDlpBackendUrl: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var editableUrl by remember(metadata.resolvedStreamUrl, metadata.selectedCandidateIndex) {
        mutableStateOf(metadata.resolvedStreamUrl)
    }
    var showDiagnostics by remember { mutableStateOf(false) }
    var backendInput by remember(ytDlpBackendUrl) { mutableStateOf(ytDlpBackendUrl) }

    LaunchedEffect(metadata.resolvedStreamUrl) {
        editableUrl = metadata.resolvedStreamUrl
    }

    val selectedCandidate = metadata.selectedCandidate
    val previewHeaders = selectedCandidate?.stream?.headers ?: buildMap {
        put("Referer", metadata.referer)
        if (metadata.cookies.isNotBlank()) put("Cookie", metadata.cookies)
    }
    val heroCacheKey = remember(metadata.thumbnailUrl, metadata.resolvedStreamUrl) {
        "${metadata.thumbnailUrl.orEmpty()}|${metadata.resolvedStreamUrl}"
    }
    val heroBitmap by produceState<ImageBitmap?>(
        initialValue = candidateThumbCache.get(heroCacheKey),
        key1 = heroCacheKey,
    ) {
        if (value != null) return@produceState
        val loaded = withContext(Dispatchers.IO) {
            loadCandidatePreviewBitmap(
                thumbnailUrl = metadata.thumbnailUrl,
                streamUrl = metadata.resolvedStreamUrl,
                headers = previewHeaders,
            )
        }
        if (loaded != null) {
            candidateThumbCache.put(heroCacheKey, loaded)
            value = loaded
        }
    }

    // 1. Hero Video Thumbnail & Metadata Card
    Surface(
        color = Color(0xFF101929),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.9.dp, EmeraldPulse.copy(alpha = 0.42f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF070D17), Color(0xFF14213A)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (heroBitmap != null) {
                    Image(
                        bitmap = heroBitmap!!,
                        contentDescription = metadata.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color(0x33050912), Color.Transparent, Color(0xBB050912)),
                                ),
                            ),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(NeonCyan.copy(alpha = 0.16f))
                                .border(1.dp, NeonCyan.copy(alpha = 0.45f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Text(
                            text = "معاينة البث المحدد",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlate,
                        )
                    }
                }

                // Top-start Quality & Format pill
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (metadata.qualityLabel.isNotBlank()) {
                        Surface(
                            color = ObsidianVoid.copy(alpha = 0.86f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.7.dp, NeonCyan.copy(alpha = 0.45f)),
                        ) {
                            Text(
                                text = metadata.qualityLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = NeonCyan,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Surface(
                        color = ObsidianVoid.copy(alpha = 0.86f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = if (selectedCandidate?.stream?.isHls == true) "HLS" else "MP4",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = ChromeSilver,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                // Bottom-end Duration badge
                if (metadata.durationSeconds > 0.5) {
                    Surface(
                        color = ObsidianVoid.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                    ) {
                        Text(
                            text = formatDurationSeconds(metadata.durationSeconds),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = ChromeSilver,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = metadata.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = ChromeSilver,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        color = EmeraldPulse.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(50),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = EmeraldPulse,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = if (metadata.cookies.isNotBlank()) "كوكيز الجلسة + Referer مرفقة" else "Referer مرفق",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = EmeraldPulse,
                            )
                        }
                    }
                    if (selectedCandidate?.stream?.estimatedBytes ?: 0L > 0L) {
                        Text(
                            text = formatByteSize(selectedCandidate!!.stream.estimatedBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan,
                        )
                    }
                }
                Text(
                    text = "الصفحة: ${metadata.pageUrl}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    color = MutedSlate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // 2. Detected Candidate Stream Picker (when >1 links exist, or clear active selector)
    if (metadata.candidates.size > 1) {
        Text(
            text = "الروابط المكتشفة (${metadata.candidates.size}) — اختر الفيديو المطلوب:",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = NeonCyan,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0D1524))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            metadata.candidates.forEachIndexed { idx, cand ->
                val isSelected = idx == metadata.selectedCandidateIndex
                Surface(
                    color = if (isSelected) Color(0xFF102833) else Color(0xFF121C2E),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = if (isSelected) 1.3.dp else 0.75.dp,
                        color = if (isSelected) EmeraldPulse else GlassStrokeBright.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectCandidate(idx) }
                        .testTag("inspector_candidate_item_$idx"),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CandidateVideoThumbnail(candidate = cand, index = idx + 1)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = cand.stream.title.ifBlank { "رابط فيديو #${idx + 1}" },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = ChromeSilver,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (cand.isActiveVideoMatch) {
                                    Surface(
                                        color = EmeraldPulse.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(50),
                                    ) {
                                        Text(
                                            text = "نشط",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                            color = EmeraldPulse,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = buildString {
                                    append(cand.stream.qualityLabel)
                                    append(" · ")
                                    append(if (cand.stream.isHls) "HLS" else "MP4")
                                    if (cand.durationSeconds > 0.5) {
                                        append(" · ")
                                        append(formatDurationSeconds(cand.durationSeconds))
                                    }
                                    if (cand.stream.estimatedBytes > 0L) {
                                        append(" · ")
                                        append(formatByteSize(cand.stream.estimatedBytes))
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = NeonCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = cand.matchReason,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MutedSlate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "محدد",
                                tint = EmeraldPulse,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // 3. Editable Stream / Page URL Override
    OutlinedTextField(
        value = editableUrl,
        onValueChange = { editableUrl = it },
        label = { Text("رابط البث / الصفحة (يمكنك تعديله أو لصق رابط مباشر)") },
        singleLine = true,
        trailingIcon = {
            IconButton(
                onClick = { onReInspectUrl(editableUrl) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("inspector_reinspect_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "إعادة فحص الرابط",
                    tint = NeonCyan,
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NeonCyan,
            unfocusedBorderColor = GlassStrokeBright.copy(alpha = 0.25f),
            focusedTextColor = ChromeSilver,
            unfocusedTextColor = ChromeSilver,
            focusedLabelColor = NeonCyan,
            unfocusedLabelColor = MutedSlate,
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("inspector_url_override_input"),
    )

    // 4. Collapsible Logcat Verification Trace & yt-dlp Retrofit Backend Config
    Surface(
        color = Color(0xFF0D1422),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.6.dp, GlassStrokeBright.copy(alpha = 0.16f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable { showDiagnostics = !showDiagnostics }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "معلومات التحقق (Logcat) وخادم yt-dlp",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MutedSlate,
                )
                Icon(
                    imageVector = if (showDiagnostics) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MutedSlate,
                )
            }
            AnimatedVisibility(visible = showDiagnostics) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (metadata.debugTrace.isNotBlank()) {
                        Text(
                            text = metadata.debugTrace,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            ),
                            color = NeonCyan.copy(alpha = 0.9f),
                        )
                    }
                    OutlinedTextField(
                        value = backendInput,
                        onValueChange = { backendInput = it },
                        label = { Text("رابط خادم yt-dlp الاختياري (Retrofit Base URL)") },
                        placeholder = { Text("https://extractor.example.com/") },
                        singleLine = true,
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    onSaveYtDlpBackendUrl(backendInput)
                                    onReInspectUrl(metadata.pageUrl)
                                },
                            ) {
                                Text("حفظ", color = EmeraldPulse)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PlasmaViolet,
                            unfocusedBorderColor = GlassStrokeBright.copy(alpha = 0.22f),
                            focusedTextColor = ChromeSilver,
                            unfocusedTextColor = ChromeSilver,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("inspector_ytdlp_backend_input"),
                    )
                }
            }
        }
    }

    // 5. Explicit Confirm & Cancel Actions
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = onCancel,
            border = BorderStroke(1.dp, GlassStrokeBright.copy(alpha = 0.35f)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .weight(0.38f)
                .height(50.dp)
                .testTag("inspector_cancel_button"),
        ) {
            Text(
                text = "إلغاء",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = ChromeSilver,
            )
        }
        Button(
            onClick = { onConfirmDownload(editableUrl) },
            colors = ButtonDefaults.buttonColors(
                containerColor = EmeraldPulse,
                contentColor = ObsidianVoid,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .weight(0.62f)
                .height(50.dp)
                .testTag("inspector_confirm_download_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "تأكيد والتحميل للخزنة",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun DownloadingSection(
    state: VaultInspectorUiState.Downloading,
    onCancel: () -> Unit,
) {
    val pct = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    Surface(
        color = Color(0xFF101929),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.9.dp, NeonCyan.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.metadata.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = ChromeSilver,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$pct%",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = NeonCyan,
                )
            }
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0.03f, 1f) },
                color = NeonCyan,
                trackColor = GlassStrokeBright.copy(alpha = 0.16f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.stageLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate,
                )
                val stats = buildString {
                    if (state.bytesDownloaded > 0L) append(formatByteSize(state.bytesDownloaded))
                    if (state.totalBytes > 0L) append(" / ${formatByteSize(state.totalBytes)}")
                    if (state.speedBytesPerSec > 0L) append(" · ${formatTransferSpeed(state.speedBytesPerSec)}")
                }
                if (stats.isNotBlank()) {
                    Text(
                        text = stats,
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldPulse,
                    )
                }
            }
            OutlinedButton(
                onClick = onCancel,
                border = BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("inspector_cancel_download_button"),
            ) {
                Text("إلغاء التحميل", color = CrimsonAlert, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CompletedSection(
    title: String,
    onDone: () -> Unit,
) {
    Surface(
        color = Color(0xFF0F2420),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, EmeraldPulse.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = EmeraldPulse,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = "تم التحميل والتشفير داخل الخزنة بنجاح",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = ChromeSilver,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = EmeraldPulse,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPulse,
                    contentColor = ObsidianVoid,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize(),
            ) {
                Text("تم", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ErrorOrDrmSection(
    isDrm: Boolean,
    title: String,
    reason: String,
    initialUrl: String,
    debugDetails: String,
    onRetryUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var retryUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    val accent = if (isDrm) PlasmaViolet else CrimsonAlert

    Surface(
        color = Color(0xFF1A111A),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.9.dp, accent.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = accent,
                )
            }
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = ChromeSilver,
            )
            if (debugDetails.isNotBlank()) {
                Text(
                    text = debugDetails,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    ),
                    color = MutedSlate,
                )
            }
            OutlinedTextField(
                value = retryUrl,
                onValueChange = { retryUrl = it },
                label = { Text("جرب تعديل أو لصق رابط آخر للفحص") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = GlassStrokeBright.copy(alpha = 0.25f),
                    focusedTextColor = ChromeSilver,
                    unfocusedTextColor = ChromeSilver,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("inspector_retry_url_input"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.dp, GlassStrokeBright.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .weight(0.4f)
                        .minimumInteractiveComponentSize()
                        .testTag("inspector_cancel_button"),
                ) {
                    Text("إغلاق", color = ChromeSilver)
                }
                Button(
                    onClick = { onRetryUrl(retryUrl) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = ObsidianVoid,
                    ),
                    modifier = Modifier
                        .weight(0.6f)
                        .minimumInteractiveComponentSize()
                        .testTag("inspector_retry_inspect_button"),
                ) {
                    Text("إعادة الفحص", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun formatDurationSeconds(seconds: Double): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    val hrs = total / 3600
    val mins = (total % 3600) / 60
    val secs = total % 60
    return if (hrs > 0) {
        String.format("%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}
