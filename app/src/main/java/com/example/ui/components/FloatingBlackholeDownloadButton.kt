package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.util.formatByteSize
import com.example.util.formatTransferSpeed
import com.example.vault.browser.ResolvedMediaCandidate
import com.example.vault.browser.VideoElementTelemetry
import com.example.vault.data.VaultBrowserDownloadPhase
import com.example.vault.data.VaultBrowserDownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Floating, draggable Blackhole download sphere overlaid on top of the Vault Private Browser.
 * Tapping it captures the active playing video from the web page in highest quality and stores it
 * directly encrypted (AES-256) inside the Vault Videos folder.
 */
@Composable
fun FloatingBlackholeDownloadOverlay(
    downloadState: VaultBrowserDownloadState,
    sniffedCandidatesCount: Int,
    isPageActive: Boolean,
    onTapBlackhole: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
    activeDetectedVideo: VideoElementTelemetry? = null,
    onSelectCandidate: (ResolvedMediaCandidate) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val orbContainerDp = 82.dp
    val orbContainerPx = with(density) { orbContainerDp.toPx() }
    val edgeMarginPx = with(density) { 12.dp.toPx() }
    val bottomClearancePx = with(density) { 24.dp.toPx() }

    val infiniteTransition = rememberInfiniteTransition(label = "floating_bh_orbit")
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (downloadState.isActive) 1100 else 3600,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "floating_bh_angle",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floating_bh_pulse",
    )
    val smoothProgress by animateFloatAsState(
        targetValue = downloadState.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220, easing = LinearEasing),
        label = "floating_bh_progress",
    )

    val primaryGlow = when (downloadState.phase) {
        VaultBrowserDownloadPhase.COMPLETED -> EmeraldPulse
        VaultBrowserDownloadPhase.ERROR -> CrimsonAlert
        VaultBrowserDownloadPhase.CANDIDATE_PICKER -> PlasmaViolet
        VaultBrowserDownloadPhase.INSPECTING,
        VaultBrowserDownloadPhase.EXTRACTING,
        VaultBrowserDownloadPhase.DOWNLOADING,
        VaultBrowserDownloadPhase.MERGING,
        VaultBrowserDownloadPhase.ENCRYPTING,
        -> NeonCyan
        VaultBrowserDownloadPhase.IDLE -> when {
            activeDetectedVideo != null && !activeDetectedVideo.isLikelyAd -> EmeraldPulse
            sniffedCandidatesCount > 0 || isPageActive -> NeonCyan
            else -> ElectricBlue
        }
    }
    val secondaryGlow = when (downloadState.phase) {
        VaultBrowserDownloadPhase.COMPLETED -> NeonCyan
        VaultBrowserDownloadPhase.ERROR -> PlasmaViolet
        else -> PlasmaViolet
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }

        var offsetX by remember { mutableFloatStateOf(-1f) }
        var offsetY by remember { mutableFloatStateOf(-1f) }

        val minX = edgeMarginPx
        val maxX = (maxWidthPx - orbContainerPx - edgeMarginPx).coerceAtLeast(minX)
        val minY = edgeMarginPx
        val maxY = (maxHeightPx - orbContainerPx - bottomClearancePx).coerceAtLeast(minY)

        LaunchedEffect(maxWidthPx, maxHeightPx) {
            if (offsetX < 0f || offsetY < 0f) {
                offsetX = maxX
                offsetY = maxY
            } else {
                offsetX = offsetX.coerceIn(minX, maxX)
                offsetY = offsetY.coerceIn(minY, maxY)
            }
        }

        val resolvedX = (if (offsetX < 0f) maxX else offsetX).coerceIn(minX, maxX)
        val resolvedY = (if (offsetY < 0f) maxY else offsetY).coerceIn(minY, maxY)

        // Active detected <video> telemetry indicator when idle on a live page
        val showActiveVideoBadge = downloadState.phase == VaultBrowserDownloadPhase.IDLE &&
            isPageActive &&
            activeDetectedVideo != null &&
            !activeDetectedVideo.isLikelyAd
        AnimatedVisibility(
            visible = showActiveVideoBadge,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            val video = activeDetectedVideo
            if (video != null) {
                Surface(
                    color = Color(0xFF0A111D).copy(alpha = 0.88f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(0.75.dp, EmeraldPulse.copy(alpha = 0.55f)),
                    modifier = Modifier.testTag("vault_browser_active_video_indicator"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (!video.paused && video.isAdvancing) EmeraldPulse else NeonCyan),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val resPart = video.resolutionLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                        val modePart = when {
                            video.isBlobOrMediaSource -> " · MSE/HLS"
                            !video.paused && video.isAdvancing -> " · قيد التشغيل"
                            else -> ""
                        }
                        Text(
                            text = "فيديو نشط$resPart$modePart",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = ChromeSilver,
                        )
                    }
                }
            }
        }

        // Top compact telemetry pill OR CandidatePicker whenever resolving/downloading/reporting status
        AnimatedVisibility(
            visible = downloadState.phase != VaultBrowserDownloadPhase.IDLE,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Surface(
                color = Color(0xFF0A111D).copy(alpha = 0.94f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(0.9.dp, primaryGlow.copy(alpha = 0.55f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_browser_download_status_pill"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = if (downloadState.phase == VaultBrowserDownloadPhase.COMPLETED) {
                                    Icons.Rounded.Lock
                                } else {
                                    Icons.Rounded.ArrowDownward
                                },
                                contentDescription = null,
                                tint = primaryGlow,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = downloadState.message
                                    ?: downloadState.title.ifBlank { "خزنة الفيديو" },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = ChromeSilver,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (downloadState.isActive) {
                            val pctText = "${(smoothProgress * 100f).roundToInt()}%"
                            val speedText = if (downloadState.speedBytesPerSec > 0L) {
                                " · ${formatTransferSpeed(downloadState.speedBytesPerSec)}"
                            } else {
                                ""
                            }
                            Text(
                                text = "$pctText$speedText",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = NeonCyan,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(CrimsonAlert.copy(alpha = 0.16f))
                                    .clickable { onCancelDownload() }
                                    .testTag("vault_browser_cancel_download_button"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "إلغاء التحميل",
                                    tint = CrimsonAlert,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable { onDismissStatus() }
                                    .testTag("vault_browser_dismiss_download_status"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "إغلاق التنبيه",
                                    tint = MutedSlate,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }

                    if (downloadState.phase == VaultBrowserDownloadPhase.CANDIDATE_PICKER &&
                        downloadState.pickerCandidates.isNotEmpty()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 390.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            downloadState.pickerCandidates.forEachIndexed { idx, cand ->
                                val cleanUrlPreview = cand.stream.streamUrl
                                    .substringAfter("://")
                                    .take(52)
                                val sizePart = if (cand.stream.estimatedBytes > 0L) {
                                    " · ${formatByteSize(cand.stream.estimatedBytes)}"
                                } else {
                                    ""
                                }
                                Surface(
                                    color = if (cand.isActiveVideoMatch) Color(0xFF10242B) else Color(0xFF121C2E),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(
                                        width = if (cand.isActiveVideoMatch) 1.dp else 0.75.dp,
                                        color = if (cand.isActiveVideoMatch) {
                                            EmeraldPulse.copy(alpha = 0.68f)
                                        } else {
                                            NeonCyan.copy(alpha = 0.34f)
                                        },
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onSelectCandidate(cand) }
                                        .testTag("vault_browser_candidate_pick_$idx"),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 9.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        CandidateVideoThumbnail(
                                            candidate = cand,
                                            index = idx + 1,
                                        )
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
                                                text = "${cand.stream.qualityLabel} · ${if (cand.stream.isHls) "HLS" else "MP4"}$sizePart · ${cand.matchReason}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = NeonCyan,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = cleanUrlPreview,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                                color = MutedSlate,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(EmeraldPulse.copy(alpha = 0.14f))
                                                .border(0.75.dp, EmeraldPulse.copy(alpha = 0.45f), CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.ArrowDownward,
                                                contentDescription = "تحميل هذا الرابط",
                                                tint = EmeraldPulse,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else if (downloadState.isActive) {
                        LinearProgressIndicator(
                            progress = { smoothProgress.coerceAtLeast(0.05f) },
                            color = primaryGlow,
                            trackColor = GlassStrokeBright.copy(alpha = 0.16f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        // Draggable Floating Blackhole Circle Button
        Box(
            modifier = Modifier
                .offset { IntOffset(resolvedX.roundToInt(), resolvedY.roundToInt()) }
                .size(orbContainerDp)
                .pointerInput(maxWidthPx, maxHeightPx) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = ((if (offsetX < 0f) maxX else offsetX) + dragAmount.x)
                            .coerceIn(minX, maxX)
                        offsetY = ((if (offsetY < 0f) maxY else offsetY) + dragAmount.y)
                            .coerceIn(minY, maxY)
                    }
                }
                .clip(CircleShape)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (downloadState.isActive) {
                        onCancelDownload()
                    } else {
                        onTapBlackhole()
                    }
                }
                .semantics {
                    contentDescription = "زر الثقب الأسود العائم لتحميل الفيديو إلى الخزنة"
                }
                .testTag("floating_blackhole_download_button"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val coreRadius = (size.minDimension * 0.34f) * (if (downloadState.isActive) pulseScale else 1f)
                val haloRadius = size.minDimension * 0.47f

                // Outer gravity ambient glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = if (downloadState.isActive) 0.45f else 0.28f),
                            secondaryGlow.copy(alpha = 0.14f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = haloRadius,
                    ),
                    radius = haloRadius,
                    center = center,
                )

                // Tilted rotating accretion ring
                rotate(degrees = orbitAngle, pivot = center) {
                    drawOval(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                primaryGlow.copy(alpha = 0.95f),
                                secondaryGlow.copy(alpha = 0.85f),
                                primaryGlow.copy(alpha = 0.35f),
                            ),
                        ),
                        topLeft = Offset(center.x - coreRadius * 1.38f, center.y - coreRadius * 0.56f),
                        size = Size(coreRadius * 2.76f, coreRadius * 1.12f),
                        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
                    )
                }

                // Event horizon blackhole sphere
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF020408),
                            ObsidianVoid,
                            Color(0xFF0E182A),
                        ),
                        center = center,
                        radius = coreRadius,
                    ),
                    radius = coreRadius,
                    center = center,
                )

                // Crisp Neon event-horizon rim
                drawCircle(
                    color = primaryGlow.copy(alpha = 0.78f),
                    radius = coreRadius,
                    center = center,
                    style = Stroke(width = 1.6.dp.toPx()),
                )

                // Determinate progress arc while downloading/encrypting
                if (downloadState.isActive) {
                    val arcInset = center.x - coreRadius * 1.16f
                    val arcTop = center.y - coreRadius * 1.16f
                    val arcDim = coreRadius * 2.32f
                    drawArc(
                        color = primaryGlow,
                        startAngle = -90f,
                        sweepAngle = (smoothProgress.coerceAtLeast(0.06f)) * 360f,
                        useCenter = false,
                        topLeft = Offset(arcInset, arcTop),
                        size = Size(arcDim, arcDim),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            // Center Glyph inside Blackhole core
            AnimatedContent(
                targetState = downloadState.phase,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "floating_bh_center_icon",
            ) { phase ->
                when (phase) {
                    VaultBrowserDownloadPhase.COMPLETED -> {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "تم التحميل",
                            tint = EmeraldPulse,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    VaultBrowserDownloadPhase.INSPECTING,
                    VaultBrowserDownloadPhase.EXTRACTING,
                    VaultBrowserDownloadPhase.DOWNLOADING,
                    VaultBrowserDownloadPhase.MERGING,
                    VaultBrowserDownloadPhase.ENCRYPTING,
                    -> {
                        val pctLabel = if (phase == VaultBrowserDownloadPhase.INSPECTING) {
                            "•••"
                        } else {
                            "${(smoothProgress * 100f).roundToInt()}%"
                        }
                        Text(
                            text = pctLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = ChromeSilver,
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.ArrowDownward,
                            contentDescription = "تحميل الفيديو إلى الخزنة",
                            tint = primaryGlow,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // Small detected media counter badge if streams detected on page
            if (sniffedCandidatesCount > 0 && downloadState.phase == VaultBrowserDownloadPhase.IDLE) {
                Surface(
                    color = EmeraldPulse,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = sniffedCandidatesCount.coerceAtMost(9).toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = ObsidianVoid,
                        )
                    }
                }
            }
        }
    }
}

internal val candidateThumbCache = object : LruCache<String, ImageBitmap>(36) {}

@Composable
internal fun CandidateVideoThumbnail(
    candidate: ResolvedMediaCandidate,
    index: Int,
) {
    val cacheKey = remember(candidate.stream.streamUrl, candidate.thumbnailUrl) {
        "${candidate.thumbnailUrl.orEmpty()}|${candidate.stream.streamUrl}"
    }
    val thumbnail by produceState<ImageBitmap?>(
        initialValue = candidateThumbCache.get(cacheKey),
        key1 = cacheKey,
    ) {
        if (value != null) return@produceState
        val loaded = withContext(Dispatchers.IO) {
            loadCandidatePreviewBitmap(
                thumbnailUrl = candidate.thumbnailUrl,
                streamUrl = candidate.stream.streamUrl,
                headers = candidate.stream.headers,
            )
        }
        if (loaded != null) {
            candidateThumbCache.put(cacheKey, loaded)
            value = loaded
        }
    }

    val accent = if (candidate.isActiveVideoMatch) EmeraldPulse else NeonCyan

    Box(
        modifier = Modifier
            .width(96.dp)
            .height(60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0A1322),
                        Color(0xFF142138),
                    ),
                ),
            )
            .border(
                width = 0.85.dp,
                color = if (candidate.isActiveVideoMatch) accent.copy(alpha = 0.65f) else GlassStrokeBright.copy(alpha = 0.25f),
                shape = RoundedCornerShape(10.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!,
                contentDescription = candidate.stream.title.ifBlank { "معاينة الفيديو #$index" },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Subtle bottom gradient vignette so quality badge remains crisp over bright frames
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0x88050A14),
                            ),
                        ),
                    ),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.9f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Top-start index badge
        Surface(
            color = ObsidianVoid.copy(alpha = 0.82f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp),
        ) {
            Text(
                text = "$index",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = accent,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }

        // Bottom-end resolution badge
        if (candidate.stream.qualityLabel.isNotBlank()) {
            Surface(
                color = ObsidianVoid.copy(alpha = 0.82f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            ) {
                Text(
                    text = candidate.stream.qualityLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = ChromeSilver,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

internal suspend fun loadCandidatePreviewBitmap(
    thumbnailUrl: String?,
    streamUrl: String,
    headers: Map<String, String>,
): ImageBitmap? {
    // 1. Decode inline canvas-captured data URI if present
    if (!thumbnailUrl.isNullOrBlank() && thumbnailUrl.startsWith("data:image/", ignoreCase = true)) {
        val base64Part = thumbnailUrl.substringAfter("base64,", "")
        if (base64Part.isNotBlank()) {
            runCatching {
                val decoded = Base64.decode(base64Part, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                if (bmp != null) return bmp.asImageBitmap()
            }
        }
    }

    // 2. Fetch remote poster / YouTube / og:image thumbnail using session headers
    if (!thumbnailUrl.isNullOrBlank() &&
        (thumbnailUrl.startsWith("http://", ignoreCase = true) || thumbnailUrl.startsWith("https://", ignoreCase = true))
    ) {
        val fetched = withTimeoutOrNull(3800L) {
            runCatching {
                val conn = (URL(thumbnailUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3500
                    instanceFollowRedirects = true
                    headers.forEach { (k, v) ->
                        if (k.isNotBlank() && v.isNotBlank()) setRequestProperty(k, v)
                    }
                    if (!headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36")
                    }
                }
                try {
                    val cType = conn.contentType.orEmpty().lowercase()
                    if (conn.responseCode in 200..299 && !cType.startsWith("video/") && !cType.startsWith("text/html")) {
                        val bytes = conn.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(8192)
                            var total = 0
                            while (total < 2 * 1024 * 1024) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                                total += read
                            }
                            out.toByteArray()
                        }
                        if (bytes.isNotEmpty()) {
                            decodeSampledBitmap(bytes, targetWidth = 280)?.asImageBitmap()
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
        if (fetched != null) return fetched
    }

    // 3. Extract a real preview frame directly from the video stream (.mp4 / .m3u8) with cookies & referer
    if (streamUrl.startsWith("http://", ignoreCase = true) || streamUrl.startsWith("https://", ignoreCase = true)) {
        return withTimeoutOrNull(4500L) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(streamUrl, headers)
                val frame = retriever.getFrameAtTime(600_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                frame?.let { scaleDownBitmap(it, maxDim = 280).asImageBitmap() }
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
    return null
}

private fun decodeSampledBitmap(bytes: ByteArray, targetWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    if (bounds.outWidth > targetWidth * 2) {
        val halfW = bounds.outWidth / 2
        while (halfW / sample >= targetWidth) {
            sample *= 2
        }
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let {
        scaleDownBitmap(it, targetWidth)
    }
}

private fun scaleDownBitmap(source: Bitmap, maxDim: Int): Bitmap {
    val w = source.width
    val h = source.height
    if (w <= maxDim && h <= maxDim) return source
    val scale = maxDim.toFloat() / maxOf(w, h).toFloat()
    val nw = (w * scale).roundToInt().coerceAtLeast(1)
    val nh = (h * scale).roundToInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(source, nw, nh, true)
    if (scaled !== source) {
        source.recycle()
    }
    return scaled
}
