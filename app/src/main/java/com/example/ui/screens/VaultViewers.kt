package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.util.formatByteSize
import com.example.vault.ui.VaultPlaybackSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun VaultProtectedVideoPlayer(
    session: VaultPlaybackSession,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val mediaUri = remember(session.uriString, session.localFile) {
        session.localFile?.takeIf { it.exists() }?.let { Uri.fromFile(it) }
            ?: Uri.parse(session.uriString)
    }

    val player = remember(mediaUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(mediaUri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            VaultViewerHeaderBar(
                title = session.displayTitle,
                subtitle = "محمي داخل الخزنة · FLAG_SECURE Active",
                onClose = onClose,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
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

@Composable
fun VaultProtectedPhotoViewer(
    session: VaultPlaybackSession,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val imageBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        session.uriString,
        session.localFile?.absolutePath,
    ) {
        value = withContext(Dispatchers.IO) {
            decodeDownsampledSessionBitmap(context, session, maxDim = 2560)
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .testTag("vault_photo_viewer"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            VaultViewerHeaderBar(
                title = session.displayTitle,
                subtitle = "صورة محمية داخل الخزنة · FLAG_SECURE Active",
                onClose = onClose,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF070A10))
                    .pointerInput(session.uriString) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = nextScale
                            if (nextScale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap!!,
                        contentDescription = session.displayTitle,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                    )
                } else {
                    Text(
                        text = "تعذر عرض الصورة — استخدم قسم الملفات لفتحها.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedSlate,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun VaultProtectedFileViewer(
    session: VaultPlaybackSession,
    onBeforeExternalOpen: () -> Unit,
    onReportOpenError: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val isTextPreviewable = remember(session.displayTitle, session.mimeType) {
        val ext = session.displayTitle.substringAfterLast('.', "").lowercase()
        session.mimeType.startsWith("text/", ignoreCase = true) ||
            session.mimeType.contains("json", ignoreCase = true) ||
            session.mimeType.contains("xml", ignoreCase = true) ||
            ext in setOf("txt", "json", "csv", "log", "md", "xml", "yml", "yaml")
    }
    val textSnippet by produceState<String?>(
        initialValue = null,
        session.uriString,
        session.localFile?.absolutePath,
        isTextPreviewable,
    ) {
        value = if (!isTextPreviewable) {
            null
        } else {
            withContext(Dispatchers.IO) {
                readSessionTextSnippet(context, session)
            }
        }
    }

    var launchedMessage by remember { mutableStateOf<String?>(null) }
    val displayBytes = remember(session.localFile, session.sizeBytes) {
        session.localFile?.takeIf { it.exists() }?.length() ?: session.sizeBytes
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .testTag("vault_file_viewer"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            VaultViewerHeaderBar(
                title = session.displayTitle,
                subtitle = "ملف محمي داخل الخزنة · FLAG_SECURE Active",
                onClose = onClose,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    color = Color(0xFF0E1522),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(0.85.dp, EmeraldPulse.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = EmeraldPulse.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(0.8.dp, EmeraldPulse.copy(alpha = 0.55f)),
                                modifier = Modifier.size(48.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Description,
                                        contentDescription = null,
                                        tint = EmeraldPulse,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.displayTitle,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = ChromeSilver,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${session.mimeType} · ${formatByteSize(displayBytes, "0 KB")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedSlate,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.VerifiedUser,
                                contentDescription = null,
                                tint = EmeraldPulse,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "محمي ومخفي داخل الخزنة (.nomedia) · FLAG_SECURE",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldPulse,
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val openResult = runCatching {
                                    val shareUri = resolveViewableFileUri(context, session)
                                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(shareUri, session.mimeType)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    onBeforeExternalOpen()
                                    context.startActivity(
                                        Intent.createChooser(viewIntent, session.displayTitle),
                                    )
                                }
                                if (openResult.isFailure) {
                                    onReportOpenError(
                                        openResult.exceptionOrNull()?.message
                                            ?: "لا يوجد تطبيق على الهاتف لفتح هذا النوع من الملفات.",
                                    )
                                } else {
                                    launchedMessage = "تم فتح الملف عبر التطبيق المختار مع بقاء نسخته محمية داخل الخزنة."
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = ObsidianVoid,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("vault_open_external_file_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "Open file externally",
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "فتح الملف بتطبيقات الهاتف",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        }

                        if (!launchedMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = launchedMessage.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldPulse,
                            )
                        }
                    }
                }

                if (textSnippet != null) {
                    Surface(
                        color = Color(0xFF0A0F18),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.22f)),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(14.dp),
                        ) {
                            Text(
                                text = textSnippet.orEmpty().ifBlank { "(ملف نصي فارغ)" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = ChromeSilver,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultViewerHeaderBar(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = ChromeSilver,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldPulse,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            color = CrimsonAlert.copy(alpha = 0.2f),
            shape = RoundedCornerShape(50),
            border = BorderStroke(0.9.dp, CrimsonAlert),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onClose() }
                .minimumInteractiveComponentSize()
                .testTag("vault_close_player_button"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "إغلاق العارض",
                    tint = CrimsonAlert,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "إغلاق",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        }
    }
}

private fun resolveViewableFileUri(context: Context, session: VaultPlaybackSession): Uri {
    val localFile: File? = session.localFile?.takeIf { it.exists() }
    if (localFile != null) {
        val provided = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)
        }.getOrNull()
        if (provided != null) return provided
    }
    return Uri.parse(session.uriString)
}

private fun decodeDownsampledSessionBitmap(
    context: Context,
    session: VaultPlaybackSession,
    maxDim: Int,
): ImageBitmap? {
    val localFile = session.localFile?.takeIf { it.exists() }
    if (localFile != null) {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(localFile.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while (longest / sampleSize > maxDim && sampleSize < 32) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(localFile.absolutePath, opts)?.asImageBitmap()
        }.getOrNull()
    }

    val uri = Uri.parse(session.uriString)
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (longest / sampleSize > maxDim && sampleSize < 32) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
        }
    }.getOrNull()
}

private fun readSessionTextSnippet(context: Context, session: VaultPlaybackSession): String? {
    val localFile = session.localFile?.takeIf { it.exists() }
    if (localFile != null) {
        return runCatching {
            localFile.bufferedReader().use { reader ->
                val chars = CharArray(16_384)
                val read = reader.read(chars)
                if (read > 0) String(chars, 0, read) else ""
            }
        }.getOrNull()
    }
    val uri = Uri.parse(session.uriString)
    return runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val chars = CharArray(16_384)
            val read = reader.read(chars)
            if (read > 0) String(chars, 0, read) else ""
        }
    }.getOrNull()
}
