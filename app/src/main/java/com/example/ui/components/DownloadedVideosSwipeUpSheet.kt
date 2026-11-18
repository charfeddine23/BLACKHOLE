package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.data.local.DownloadedVideoEntity
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanGlowSoft
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadedVideosSwipeUpSheet(
    videos: List<DownloadedVideoEntity>,
    drawerProgress: Float,
    maxHeightPx: Float,
    onDragByPx: (Float) -> Unit,
    onDragFinish: (Float) -> Unit,
    onCollapse: () -> Unit,
    onDeleteVideo: (DownloadedVideoEntity) -> Unit,
    modifier: Modifier = Modifier,
    syncedDomains: List<String> = emptyList(),
    vaultStatusMessage: String? = null,
    onAutoSyncCookies: (String) -> Unit = {},
    onManualSyncCookies: (String) -> Unit = {},
    onPullFromBrowser: (String) -> Unit = {},
    onDismissVaultStatus: () -> Unit = {},
) {
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    var previewingVideo by remember { mutableStateOf<DownloadedVideoEntity?>(null) }
    var showBrowserTab by remember { mutableStateOf(false) }
    var embeddedWebView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = previewingVideo != null || showBrowserTab || drawerProgress > 0.05f) {
        when {
            previewingVideo != null -> previewingVideo = null
            showBrowserTab && embeddedWebView?.canGoBack() == true -> embeddedWebView?.goBack()
            showBrowserTab -> showBrowserTab = false
            else -> onCollapse()
        }
    }

    val sheetNestedScroll = remember(maxHeightPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val atTop = gridState.firstVisibleItemIndex == 0 &&
                    gridState.firstVisibleItemScrollOffset == 0
                val isPullingDownFromTop = atTop && available.y > 0f
                val isMidTransition = drawerProgress < 0.999f

                if (isMidTransition || isPullingDownFromTop) {
                    onDragByPx(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drawerProgress < 0.999f) {
                    onDragFinish(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = ((1f - drawerProgress) * maxHeightPx).coerceAtLeast(0f)
            }
            .testTag("downloaded_videos_swipe_sheet"),
        shape = RoundedCornerShape(
            topStart = if (drawerProgress > 0.98f) 0.dp else 28.dp,
            topEnd = if (drawerProgress > 0.98f) 0.dp else 28.dp,
        ),
        color = ObsidianVoid.copy(alpha = 0.97f),
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    NeonCyan.copy(alpha = 0.35f),
                    GlassStrokeBright.copy(alpha = 0.16f),
                    Color.Transparent,
                ),
            ),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0A1019),
                            ObsidianVoid,
                        ),
                    ),
                )
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Draggable Sheet Header & Pull-Down Handle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        val velocityTracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { velocityTracker.resetTracking() },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                onDragByPx(dragAmount)
                            },
                            onDragEnd = {
                                val velocityY = velocityTracker.calculateVelocity().y
                                onDragFinish(velocityY)
                            },
                            onDragCancel = {
                                onDragFinish(0f)
                            },
                        )
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(GlassStrokeBright.copy(alpha = 0.45f)),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DOWNLOADED VIDEOS",
                            style = MaterialTheme.typography.titleLarge,
                            color = ChromeSilver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (showBrowserTab) {
                                "IN-APP BROWSER · AUTO COOKIE CAPTURE"
                            } else {
                                "${videos.size} LOCAL VAULT ${if (videos.size == 1) "CAPTURE" else "CAPTURES"}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (showBrowserTab) NeonCyan else MutedSlate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = if (showBrowserTab) NeonCyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                width = 0.85.dp,
                                color = if (showBrowserTab) NeonCyan.copy(alpha = 0.75f) else GlassStrokeBright.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { showBrowserTab = !showBrowserTab }
                                .minimumInteractiveComponentSize()
                                .testTag("downloaded_sheet_browser_toggle"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (showBrowserTab) Icons.Outlined.Movie else Icons.Outlined.Public,
                                    contentDescription = if (showBrowserTab) "Show downloaded videos" else "Open in-app browser",
                                    tint = if (showBrowserTab) NeonCyan else ChromeSilver,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (showBrowserTab) "VIDEOS" else "BROWSER",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (showBrowserTab) NeonCyan else ChromeSilver,
                                )
                            }
                        }

                        IconButton(
                            onClick = onCollapse,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("collapse_videos_sheet_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Swipe down to close videos",
                                tint = ChromeSilver,
                            )
                        }
                    }
                }
            }

            if (showBrowserTab) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    DownloadedVideosBrowserPanel(
                        syncedDomains = syncedDomains,
                        statusMessage = vaultStatusMessage,
                        onAutoSyncCookies = onAutoSyncCookies,
                        onManualSyncCookies = onManualSyncCookies,
                        onPullCurrentPage = { pageUrl ->
                            onPullFromBrowser(pageUrl)
                            onCollapse()
                        },
                        onDismissStatus = onDismissVaultStatus,
                        onBindWebViewRef = { embeddedWebView = it },
                    )
                }
            } else if (videos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            val velocityTracker = VelocityTracker()
                            detectVerticalDragGestures(
                                onDragStart = { velocityTracker.resetTracking() },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    onDragByPx(dragAmount)
                                },
                                onDragEnd = {
                                    onDragFinish(velocityTracker.calculateVelocity().y)
                                },
                                onDragCancel = { onDragFinish(0f) },
                            )
                        }
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LiquidGlassCard(
                        modifier = Modifier.widthIn(max = 420.dp),
                        shape = RoundedCornerShape(28.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                NeonCyan.copy(alpha = 0.22f),
                                                PlasmaViolet.copy(alpha = 0.1f),
                                                Color.Transparent,
                                            ),
                                        ),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Movie,
                                    contentDescription = "Empty video vault",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Singularity Vault Empty",
                                style = MaterialTheme.typography.titleMedium,
                                color = ChromeSilver,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Swipe down to return to the Blackhole core, copy a video link, and tap to pull.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedSlate,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(sheetNestedScroll),
                ) {
                    val columns = if (maxWidth >= 600.dp) 2 else 1
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(videos, key = { it.id }) { video ->
                            SwipeSheetVideoCard(
                                video = video,
                                onPlay = { previewingVideo = video },
                                onShare = {
                                    val shareUri = resolvePlayableVideoUri(context, video)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = video.mimeType.ifBlank { "video/mp4" }
                                        putExtra(Intent.EXTRA_STREAM, shareUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "Share ${video.title}"),
                                    )
                                },
                                onDelete = { onDeleteVideo(video) },
                            )
                        }
                    }
                }
            }
        }

        previewingVideo?.let { activeVideo ->
            InlineVideoPlayerView(
                video = activeVideo,
                onDismiss = { previewingVideo = null },
            )
        }
    }
}

@Composable
private fun SwipeSheetVideoCard(
    video: DownloadedVideoEntity,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumbnailBitmap by produceState<ImageBitmap?>(initialValue = null, video.thumbnailPath) {
        value = withContext(Dispatchers.IO) {
            val path = video.thumbnailPath ?: return@withContext null
            val file = File(path)
            if (!file.exists()) return@withContext null
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
    }

    LiquidGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .testTag("video_card_${video.id}"),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF0A0F17)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap!!,
                        contentDescription = "Thumbnail for ${video.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF09121E),
                                        Color(0xFF130E24),
                                    ),
                                ),
                            ),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    ObsidianVoid.copy(alpha = 0.72f),
                                ),
                            ),
                        ),
                )

                Surface(
                    color = ObsidianVoid.copy(alpha = 0.75f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.75f)),
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play ${video.title}",
                            tint = NeonCyan,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        color = CyanGlowSoft.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(0.75.dp, NeonCyan.copy(alpha = 0.7f)),
                    ) {
                        Text(
                            text = video.qualityLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                }

                if (video.durationMs > 0L) {
                    Surface(
                        color = ObsidianVoid.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                    ) {
                        Text(
                            text = formatSheetDuration(video.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = ChromeSilver,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${video.platform} · ${formatSheetFileSize(video.fileSizeBytes)} · ${video.resolution} · ${formatSheetDate(video.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("share_video_${video.id}"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "Share video",
                            tint = ChromeSilver,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("delete_video_${video.id}"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "Delete video",
                            tint = CrimsonAlert,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun InlineVideoPlayerView(
    video: DownloadedVideoEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playableUri = remember(video.id) { resolvePlayableVideoUri(context, video) }

    val exoPlayer = remember(playableUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(playableUri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${video.qualityLabel} · ${video.resolution} · ${formatSheetFileSize(video.fileSizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("close_player_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close media player",
                        tint = ChromeSilver,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun resolvePlayableVideoUri(context: Context, video: DownloadedVideoEntity): Uri {
    val file = File(video.filePath)
    if (file.exists()) {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }
    return Uri.parse(video.contentUri)
}

private fun formatSheetDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatSheetFileSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}

private fun formatSheetDate(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM d", Locale.US)
    return formatter.format(Date(timestamp))
}
