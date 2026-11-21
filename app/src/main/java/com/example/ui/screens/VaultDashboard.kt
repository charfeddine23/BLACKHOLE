package com.example.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.example.util.formatShortDate
import com.example.vault.data.VaultCategory
import com.example.vault.data.VaultCipher
import com.example.vault.data.VaultEncryptedMediaDataSource
import com.example.vault.data.VaultFileEntry
import com.example.vault.data.VaultTransferStage
import com.example.vault.ui.VaultTransferUiState
import com.example.vault.ui.VaultUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun VaultHomeCategoriesDashboard(
    uiState: VaultUiState,
    onBack: () -> Unit,
    onLockNow: () -> Unit,
    onOpenCategory: (VaultCategory) -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onRetryDeletePendingOriginals: () -> Unit,
    onDismissPendingOriginals: () -> Unit,
    onCancelTransfer: () -> Unit,
    onDismissStatus: () -> Unit,
) {
    val videoEntries = remember(uiState.entries) {
        uiState.entries.filter { it.category == VaultCategory.VIDEOS }
    }
    val photoEntries = remember(uiState.entries) {
        uiState.entries.filter { it.category == VaultCategory.PHOTOS }
    }
    val fileEntries = remember(uiState.entries) {
        uiState.entries.filter { it.category == VaultCategory.FILES }
    }

    val videosTotalBytes = remember(videoEntries) { videoEntries.sumOf { it.sizeBytes } }
    val photosTotalBytes = remember(photoEntries) { photoEntries.sumOf { it.sizeBytes } }
    val filesTotalBytes = remember(fileEntries) { fileEntries.sumOf { it.sizeBytes } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        VaultTopHeaderBar(
            title = "الخزنة",
            subtitle = "${uiState.entries.size} عنصر مشفر ومخفي",
            subtitleColor = NeonCyan,
            onBack = onBack,
            onLockNow = onLockNow,
        )

        VaultTransferAndStatusSection(
            uiState = uiState,
            onCancelTransfer = onCancelTransfer,
            onDismissStatus = onDismissStatus,
            onRequestAllFilesAccess = onRequestAllFilesAccess,
            onRetryDeletePendingOriginals = onRetryDeletePendingOriginals,
            onDismissPendingOriginals = onDismissPendingOriginals,
        )

        Spacer(modifier = Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VaultFolderCard(
                title = "الفيديوهات",
                count = videoEntries.size,
                totalSizeBytes = videosTotalBytes,
                icon = Icons.Outlined.VideoLibrary,
                accent = PlasmaViolet,
                onClick = { onOpenCategory(VaultCategory.VIDEOS) },
                modifier = Modifier.testTag("vault_category_videos"),
            )

            VaultFolderCard(
                title = "الصور",
                count = photoEntries.size,
                totalSizeBytes = photosTotalBytes,
                icon = Icons.Outlined.PhotoLibrary,
                accent = NeonCyan,
                onClick = { onOpenCategory(VaultCategory.PHOTOS) },
                modifier = Modifier.testTag("vault_category_photos"),
            )

            VaultFolderCard(
                title = "الملفات",
                count = fileEntries.size,
                totalSizeBytes = filesTotalBytes,
                icon = Icons.Rounded.FolderCopy,
                accent = EmeraldPulse,
                onClick = { onOpenCategory(VaultCategory.FILES) },
                modifier = Modifier.testTag("vault_category_files"),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultCategoryDetailScreen(
    category: VaultCategory,
    uiState: VaultUiState,
    cipher: VaultCipher,
    onBackToCategories: () -> Unit,
    onLockNow: () -> Unit,
    onPickFromGallery: () -> Unit,
    onPickFromPhoneFiles: () -> Unit,
    onMoveDownloadedVideo: (File) -> Unit,
    onCancelTransfer: () -> Unit,
    onOpenEntry: (VaultFileEntry) -> Unit,
    onRestoreEntry: (VaultFileEntry) -> Unit,
    onDeleteEntry: (VaultFileEntry) -> Unit,
    onRestoreEntries: (List<VaultFileEntry>) -> Unit,
    onDeleteEntries: (List<VaultFileEntry>) -> Unit,
    onDismissStatus: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onRetryDeletePendingOriginals: () -> Unit,
    onDismissPendingOriginals: () -> Unit,
) {
    val categoryEntries = remember(uiState.entries, category) {
        uiState.entries.filter { it.category == category }
    }

    var selectedUris by remember(category) { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBatchDelete by remember(category, selectedUris.size) { mutableStateOf(false) }

    val isGalleryMode = category == VaultCategory.PHOTOS || category == VaultCategory.VIDEOS
    val isSelectionActive = isGalleryMode && selectedUris.isNotEmpty()

    val categoryTitle = when (category) {
        VaultCategory.PHOTOS -> "الصور"
        VaultCategory.VIDEOS -> "الفيديوهات"
        VaultCategory.FILES -> "الملفات"
    }
    val categoryAccent = when (category) {
        VaultCategory.PHOTOS -> NeonCyan
        VaultCategory.VIDEOS -> PlasmaViolet
        VaultCategory.FILES -> EmeraldPulse
    }
    val addFromGalleryLabel = when (category) {
        VaultCategory.PHOTOS -> "إضافة صور من المعرض"
        VaultCategory.VIDEOS -> "إضافة فيديوهات من المعرض"
        VaultCategory.FILES -> "إضافة ملف من الهاتف"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        VaultTopHeaderBar(
            title = categoryTitle,
            subtitle = if (isSelectionActive) {
                "تم تحديد ${selectedUris.size} عنصر"
            } else {
                "${categoryEntries.size} عنصر مشفر"
            },
            subtitleColor = categoryAccent,
            onBack = {
                if (isSelectionActive) {
                    selectedUris = emptySet()
                } else {
                    onBackToCategories()
                }
            },
            onLockNow = onLockNow,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onPickFromGallery,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = categoryAccent,
                    contentColor = ObsidianVoid,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("vault_add_from_gallery_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AddPhotoAlternate,
                    contentDescription = addFromGalleryLabel,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "إضافة من المعرض",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            OutlinedButton(
                onClick = onPickFromPhoneFiles,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.9.dp, categoryAccent.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("vault_add_from_phone_files_button"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = "تصفح ملفات الهاتف",
                    tint = categoryAccent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "ملفات الهاتف",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ChromeSilver,
                    maxLines = 1,
                )
            }
        }

        VaultTransferAndStatusSection(
            uiState = uiState,
            onCancelTransfer = onCancelTransfer,
            onDismissStatus = onDismissStatus,
            onRequestAllFilesAccess = onRequestAllFilesAccess,
            onRetryDeletePendingOriginals = onRetryDeletePendingOriginals,
            onDismissPendingOriginals = onDismissPendingOriginals,
        )

        if (category == VaultCategory.VIDEOS &&
            uiState.availableDownloadedVideos.isNotEmpty() &&
            uiState.transferState is VaultTransferUiState.Idle
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.availableDownloadedVideos.take(10), key = { it.absolutePath }) { file ->
                    Surface(
                        color = Color(0xFF101928),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(0.8.dp, NeonCyan.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onMoveDownloadedVideo(file) }
                            .minimumInteractiveComponentSize()
                            .testTag("vault_import_download_${file.name}"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.DriveFileMove,
                                contentDescription = "نقل ${file.name} إلى الخزنة",
                                tint = NeonCyan,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = file.nameWithoutExtension,
                                style = MaterialTheme.typography.labelSmall,
                                color = ChromeSilver,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 160.dp),
                            )
                        }
                    }
                }
            }
        }

        if (categoryEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        color = categoryAccent.copy(alpha = 0.14f),
                        shape = CircleShape,
                        border = BorderStroke(0.9.dp, categoryAccent.copy(alpha = 0.45f)),
                        modifier = Modifier.size(72.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = when (category) {
                                    VaultCategory.PHOTOS -> Icons.Outlined.PhotoLibrary
                                    VaultCategory.VIDEOS -> Icons.Outlined.VideoLibrary
                                    VaultCategory.FILES -> Icons.Rounded.FolderCopy
                                },
                                contentDescription = null,
                                tint = categoryAccent,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "لا توجد $categoryTitle محفوظة بعد",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = ChromeSilver,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "اضغط على زر \"إضافة من المعرض\" لاختيار ونقل الملفات لتشفيرها وإخفائها من الهاتف كلياً.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = onPickFromGallery,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = categoryAccent,
                            contentColor = ObsidianVoid,
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("vault_empty_add_from_gallery_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddPhotoAlternate,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = addFromGalleryLabel,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }
            }
        } else if (isGalleryMode) {
            // Pure edge-to-edge 3-column Gallery Grid for both Encrypted Photos & Encrypted Videos
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("vault_gallery_grid"),
            ) {
                items(categoryEntries, key = { it.uriString }) { item ->
                    val isSelected = item.uriString in selectedUris
                    VaultGalleryMediaTile(
                        entry = item,
                        cipher = cipher,
                        accent = categoryAccent,
                        isSelectionMode = isSelectionActive,
                        isSelected = isSelected,
                        onClick = {
                            if (isSelectionActive) {
                                selectedUris = if (isSelected) {
                                    selectedUris - item.uriString
                                } else {
                                    selectedUris + item.uriString
                                }
                            } else {
                                onOpenEntry(item)
                            }
                        },
                        onLongClick = {
                            selectedUris = if (isSelected) {
                                selectedUris - item.uriString
                            } else {
                                selectedUris + item.uriString
                            }
                        },
                    )
                }
            }

            if (isSelectionActive) {
                val chosenItems = categoryEntries.filter { it.uriString in selectedUris }
                Surface(
                    color = Color(0xFF0E1522),
                    border = BorderStroke(0.85.dp, categoryAccent.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { selectedUris = emptySet() },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(0.85.dp, GlassStrokeBright.copy(alpha = 0.35f)),
                            modifier = Modifier.height(48.dp),
                        ) {
                            Text("إلغاء", color = ChromeSilver)
                        }

                        Button(
                            onClick = {
                                selectedUris = emptySet()
                                onRestoreEntries(chosenItems)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = ObsidianVoid,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("vault_gallery_batch_restore_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Restore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "استعادة (${chosenItems.size})",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        }

                        Button(
                            onClick = {
                                if (confirmBatchDelete) {
                                    selectedUris = emptySet()
                                    confirmBatchDelete = false
                                    onDeleteEntries(chosenItems)
                                } else {
                                    confirmBatchDelete = true
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CrimsonAlert,
                                contentColor = Color.White,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("vault_gallery_batch_delete_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (confirmBatchDelete) "تأكيد (${chosenItems.size})" else "حذف (${chosenItems.size})",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(categoryEntries, key = { it.uriString }) { item ->
                    VaultProtectedFileCard(
                        entry = item,
                        accent = categoryAccent,
                        onOpen = { onOpenEntry(item) },
                        onRestore = { onRestoreEntry(item) },
                        onDelete = { onDeleteEntry(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultTopHeaderBar(
    title: String,
    subtitle: String,
    subtitleColor: Color,
    onBack: () -> Unit,
    onLockNow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("vault_back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = ChromeSilver,
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = ChromeSilver,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        OutlinedButton(
            onClick = onLockNow,
            shape = RoundedCornerShape(50),
            border = BorderStroke(0.85.dp, PlasmaViolet.copy(alpha = 0.7f)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("vault_lock_now_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = "قفل الخزنة",
                tint = PlasmaViolet,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "قفل",
                style = MaterialTheme.typography.labelSmall,
                color = ChromeSilver,
            )
        }
    }
}

@Composable
private fun VaultFolderCard(
    title: String,
    count: Int,
    totalSizeBytes: Long,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xFF0E1522),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(0.95.dp, accent.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .height(116.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .minimumInteractiveComponentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.22f),
                                accent.copy(alpha = 0.04f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.14f, size.height * 0.5f),
                            radius = size.height * 0.95f,
                        ),
                        radius = size.height * 0.95f,
                        center = Offset(size.width * 0.14f, size.height * 0.5f),
                    )
                }
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    color = accent.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(0.85.dp, accent.copy(alpha = 0.55f)),
                    modifier = Modifier.size(58.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accent,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 21.sp,
                        ),
                        color = ChromeSilver,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (count > 0) {
                            "$count عنصر · ${formatByteSize(totalSizeBytes)}"
                        } else {
                            "فارغ"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = accent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = accent,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MutedSlate,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultGalleryMediaTile(
    entry: VaultFileEntry,
    cipher: VaultCipher,
    accent: Color,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val isVideo = entry.category == VaultCategory.VIDEOS

    val thumbnailBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        entry.uriString,
        entry.localFilePath,
        entry.category,
    ) {
        value = withContext(Dispatchers.IO) {
            if (isVideo) {
                decodeVideoThumbnail(context, cipher, entry)
            } else {
                decodePhotoThumbnail(cipher, entry, maxDim = 420)
            }
        }
    }

    Surface(
        color = Color(0xFF0E1420),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isSelected) 2.2.dp else 0.75.dp,
            color = if (isSelected) accent else GlassStrokeBright.copy(alpha = 0.18f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .testTag("vault_entry_${entry.id}"),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnailBitmap != null) {
                Image(
                    bitmap = thumbnailBitmap!!,
                    contentDescription = entry.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF0A1926), Color(0xFF101729))),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isVideo) Icons.Rounded.PlayArrow else Icons.Rounded.Image,
                        contentDescription = entry.displayTitle,
                        tint = accent.copy(alpha = 0.75f),
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            if (isVideo && thumbnailBitmap != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(ObsidianVoid.copy(alpha = 0.62f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "تشغيل ${entry.displayTitle}",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (isVideo) {
                Surface(
                    color = ObsidianVoid.copy(alpha = 0.76f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp),
                ) {
                    Text(
                        text = formatByteSize(entry.sizeBytes),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(ObsidianVoid.copy(alpha = 0.68f)),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelectionMode) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "محدد" else "تحديد",
                        tint = if (isSelected) accent else ChromeSilver,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "مشفر",
                        tint = EmeraldPulse,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultProtectedFileCard(
    entry: VaultFileEntry,
    accent: Color,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(entry.uriString) { mutableStateOf(false) }

    Surface(
        color = Color(0xFF0E1420),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(0.85.dp, GlassStrokeBright.copy(alpha = 0.24f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onOpen() }
            .testTag("vault_entry_${entry.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    color = accent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.55f)),
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val extLabel = entry.displayTitle
                            .substringAfterLast('.', "")
                            .uppercase(Locale.US)
                            .take(4)
                        if (extLabel.isNotBlank()) {
                            Text(
                                text = extLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = accent,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Description,
                                contentDescription = "فتح ${entry.displayTitle}",
                                tint = accent,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${formatByteSize(entry.sizeBytes)} · ${formatShortDate(entry.lastModifiedMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onRestore,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("vault_restore_${entry.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Restore,
                        contentDescription = "استعادة إلى الجهاز",
                        tint = NeonCyan,
                    )
                }

                if (confirmDelete) {
                    Surface(
                        color = CrimsonAlert,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                confirmDelete = false
                                onDelete()
                            }
                            .minimumInteractiveComponentSize(),
                    ) {
                        Text(
                            text = "حذف",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    IconButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("vault_delete_${entry.id}"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "حذف من الخزنة",
                            tint = CrimsonAlert,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultTransferAndStatusSection(
    uiState: VaultUiState,
    onCancelTransfer: () -> Unit,
    onDismissStatus: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onRetryDeletePendingOriginals: () -> Unit,
    onDismissPendingOriginals: () -> Unit,
) {
    val transfer = uiState.transferState
    if (transfer is VaultTransferUiState.Running) {
        Spacer(modifier = Modifier.height(10.dp))
        Surface(
            color = Color(0xFF101928),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(0.9.dp, NeonCyan.copy(alpha = 0.65f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("vault_transfer_progress_card"),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val actionTitle = when (transfer.stage) {
                        VaultTransferStage.PREPARING -> "تجهيز الملف للتشفير..."
                        VaultTransferStage.MOVING -> "جاري تشفير ونقل: ${transfer.label}"
                        VaultTransferStage.FINALIZING, VaultTransferStage.COMPLETED -> "تأمين التشفير وحذف الأصل من الهاتف..."
                    }
                    Text(
                        text = actionTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(transfer.progress * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = NeonCyan,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { transfer.progress.coerceIn(0f, 1f) },
                    color = NeonCyan,
                    trackColor = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${formatByteSize(transfer.bytesProcessed)} / ${formatByteSize(transfer.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate,
                    )
                    Text(
                        text = "إلغاء",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = CrimsonAlert,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onCancelTransfer() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    AnimatedVisibility(visible = !uiState.statusMessage.isNullOrBlank()) {
        uiState.statusMessage?.let { msg ->
            Surface(
                color = (if (uiState.isErrorStatus) CrimsonAlert else EmeraldPulse).copy(alpha = 0.14f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    0.8.dp,
                    (if (uiState.isErrorStatus) CrimsonAlert else EmeraldPulse).copy(alpha = 0.55f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.isErrorStatus) CrimsonAlert else EmeraldPulse,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismissStatus,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "إغلاق التنبيه",
                            tint = MutedSlate,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (uiState.pendingUnremovedOriginals.isNotEmpty()) {
        Surface(
            color = CrimsonAlert.copy(alpha = 0.14f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(0.95.dp, CrimsonAlert.copy(alpha = 0.65f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("vault_pending_originals_card"),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "نسخ أصلية ما زالت على الهاتف (${uiState.pendingUnremovedOriginals.size})",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = CrimsonAlert,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismissPendingOriginals,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "تجاهل",
                            tint = MutedSlate,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (!uiState.hasAllFilesAccess) {
                        "لتشفير وحذف الأصل من الهاتف كلياً، فعّل صلاحية \"كل الملفات\" وسيتم الحذف تلقائياً فور عودتك."
                    } else {
                        "الصلاحية مفعّلة الآن — اضغط لحذف النسخ الأصلية المتبقية من الهاتف نهائياً."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ChromeSilver,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (!uiState.hasAllFilesAccess) {
                            onRequestAllFilesAccess()
                        } else {
                            onRetryDeletePendingOriginals()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CrimsonAlert,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("vault_fix_pending_originals_button"),
                ) {
                    Text(
                        text = if (!uiState.hasAllFilesAccess) {
                            "تفعيل صلاحية كل الملفات وحذف الأصل الآن"
                        } else {
                            "حذف النسخ الأصلية من الهاتف الآن"
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

private fun decodePhotoThumbnail(cipher: VaultCipher, entry: VaultFileEntry, maxDim: Int): ImageBitmap? {
    val localFile = entry.localFilePath?.let(::File)?.takeIf { it.exists() }
    val uri = Uri.parse(entry.uriString)
    fun openStream() = if (localFile != null) {
        cipher.openDecryptedInputStream(localFile)
    } else {
        cipher.openDecryptedInputStream(uri)
    }

    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream().use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sampleSize = 1
        while (longest / sampleSize > maxDim && sampleSize < 32) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        openStream().use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
        }
    }.getOrNull()
}

private fun decodeVideoThumbnail(
    context: Context,
    cipher: VaultCipher,
    entry: VaultFileEntry,
): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        val localFile = entry.localFilePath?.let(::File)?.takeIf { it.exists() }
        val dataSource = VaultEncryptedMediaDataSource(
            context = context.applicationContext,
            cipher = cipher,
            localFile = localFile,
            uri = Uri.parse(entry.uriString),
        )
        retriever.setDataSource(dataSource)
        val frame = retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(0L)
        frame?.asImageBitmap()
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}
