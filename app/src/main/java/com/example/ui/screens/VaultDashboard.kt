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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.material.icons.rounded.VisibilityOff
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
    onOpenSettings: () -> Unit,
    onOpenCategory: (VaultCategory) -> Unit,
    onOpenPrivateBrowser: () -> Unit,
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

    val homeTiles = remember(videoEntries, photoEntries, fileEntries) {
        listOf(
            VaultHomeTileItem(
                key = VaultCategory.VIDEOS.name,
                title = "الفيديوهات",
                badgeText = videoEntries.size.toString(),
                subtitleText = if (videoEntries.isNotEmpty()) formatByteSize(videosTotalBytes) else "فارغ",
                icon = Icons.Outlined.VideoLibrary,
                accent = PlasmaViolet,
                testTag = "vault_category_videos",
                onClick = { onOpenCategory(VaultCategory.VIDEOS) },
            ),
            VaultHomeTileItem(
                key = VaultCategory.PHOTOS.name,
                title = "الصور",
                badgeText = photoEntries.size.toString(),
                subtitleText = if (photoEntries.isNotEmpty()) formatByteSize(photosTotalBytes) else "فارغ",
                icon = Icons.Outlined.PhotoLibrary,
                accent = NeonCyan,
                testTag = "vault_category_photos",
                onClick = { onOpenCategory(VaultCategory.PHOTOS) },
            ),
            VaultHomeTileItem(
                key = VaultCategory.FILES.name,
                title = "الملفات",
                badgeText = fileEntries.size.toString(),
                subtitleText = if (fileEntries.isNotEmpty()) formatByteSize(filesTotalBytes) else "فارغ",
                icon = Icons.Rounded.FolderCopy,
                accent = EmeraldPulse,
                testTag = "vault_category_files",
                onClick = { onOpenCategory(VaultCategory.FILES) },
            ),
            VaultHomeTileItem(
                key = "PRIVATE_BROWSER",
                title = "private Brower",
                badgeText = "AES",
                subtitleText = "تصفح وتحميل مشفر",
                icon = Icons.Rounded.VisibilityOff,
                accent = NeonCyan,
                testTag = "vault_category_private_browser",
                onClick = onOpenPrivateBrowser,
            ),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        VaultTopHeaderBar(
            title = "الخزنة",
            subtitle = "${uiState.entries.size} عنصر مشفر ومخفي",
            subtitleColor = NeonCyan,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
        )

        VaultTransferAndStatusSection(
            uiState = uiState,
            onCancelTransfer = onCancelTransfer,
            onDismissStatus = onDismissStatus,
            onRequestAllFilesAccess = onRequestAllFilesAccess,
            onRetryDeletePendingOriginals = onRetryDeletePendingOriginals,
            onDismissPendingOriginals = onDismissPendingOriginals,
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("vault_home_folders_grid"),
        ) {
            items(homeTiles, key = { it.key }) { tile ->
                VaultFolderGridTile(
                    title = tile.title,
                    badgeText = tile.badgeText,
                    subtitleText = tile.subtitleText,
                    icon = tile.icon,
                    accent = tile.accent,
                    onClick = tile.onClick,
                    modifier = Modifier.testTag(tile.testTag),
                )
            }
        }
    }
}

private data class VaultHomeTileItem(
    val key: String,
    val title: String,
    val badgeText: String,
    val subtitleText: String,
    val icon: ImageVector,
    val accent: Color,
    val testTag: String,
    val onClick: () -> Unit,
)

@Composable
fun VaultCategoryDetailScreen(
    category: VaultCategory,
    uiState: VaultUiState,
    cipher: VaultCipher,
    onBackToCategories: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickFromGallery: () -> Unit,
    onPickFromPhoneFiles: () -> Unit,
    onMoveDownloadedVideo: (File) -> Unit,
    onCancelTransfer: () -> Unit,
    onOpenEntry: (VaultFileEntry) -> Unit,
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

    val isSelectionActive = selectedUris.isNotEmpty()

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

    val isMediaGalleryOnly = category == VaultCategory.VIDEOS || category == VaultCategory.PHOTOS

    Column(modifier = Modifier.fillMaxSize()) {
        if (isMediaGalleryOnly) {
            // Ultra-clean Gallery Top Bar: ONLY Back button, title, and "Add from Gallery" button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    IconButton(
                        onClick = {
                            if (isSelectionActive) {
                                selectedUris = emptySet()
                            } else {
                                onBackToCategories()
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("vault_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "العودة",
                            tint = ChromeSilver,
                        )
                    }
                    Text(
                        text = if (isSelectionActive) "تم تحديد ${selectedUris.size}" else categoryTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Button(
                    onClick = onPickFromGallery,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = categoryAccent,
                        contentColor = ObsidianVoid,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("vault_add_from_gallery_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddPhotoAlternate,
                        contentDescription = addFromGalleryLabel,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "إضافة من المعرض",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }
            }

            val runningTransfer = uiState.transferState as? VaultTransferUiState.Running
            if (runningTransfer != null) {
                LinearProgressIndicator(
                    progress = { runningTransfer.progress.coerceIn(0f, 1f) },
                    color = categoryAccent,
                    trackColor = GlassStrokeBright.copy(alpha = 0.2f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        } else {
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
                onOpenSettings = onOpenSettings,
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
        }

        if (categoryEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isMediaGalleryOnly) {
                    Text(
                        text = if (category == VaultCategory.VIDEOS) "لا توجد فيديوهات" else "لا توجد صور",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedSlate,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            color = categoryAccent.copy(alpha = 0.14f),
                            shape = CircleShape,
                            border = BorderStroke(0.9.dp, categoryAccent.copy(alpha = 0.45f)),
                            modifier = Modifier.size(72.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.FolderCopy,
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
            }
        } else {
            // Pure 3-column Gallery Grid for Videos & Photos; adaptive tile grid for Files
            LazyVerticalGrid(
                columns = if (isMediaGalleryOnly) GridCells.Fixed(3) else GridCells.Adaptive(minSize = 100.dp),
                contentPadding = if (isMediaGalleryOnly) {
                    PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)
                } else {
                    PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp)
                },
                verticalArrangement = Arrangement.spacedBy(if (isMediaGalleryOnly) 3.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(if (isMediaGalleryOnly) 3.dp else 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("vault_gallery_grid"),
            ) {
                items(categoryEntries, key = { it.uriString }) { item ->
                    val isSelected = item.uriString in selectedUris
                    val toggleSelection = {
                        selectedUris = if (isSelected) {
                            selectedUris - item.uriString
                        } else {
                            selectedUris + item.uriString
                        }
                    }
                    val handleTap = {
                        if (isSelectionActive) {
                            toggleSelection()
                        } else {
                            onOpenEntry(item)
                        }
                    }

                    if (category == VaultCategory.FILES) {
                        VaultGalleryFileTile(
                            entry = item,
                            accent = categoryAccent,
                            isSelectionMode = isSelectionActive,
                            isSelected = isSelected,
                            onClick = handleTap,
                            onLongClick = toggleSelection,
                        )
                    } else {
                        VaultGalleryMediaTile(
                            entry = item,
                            cipher = cipher,
                            accent = categoryAccent,
                            isSelectionMode = isSelectionActive,
                            isSelected = isSelected,
                            onClick = handleTap,
                            onLongClick = toggleSelection,
                        )
                    }
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
        }
    }
}

@Composable
private fun VaultTopHeaderBar(
    title: String,
    subtitle: String,
    subtitleColor: Color,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
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
            onClick = onOpenSettings,
            shape = RoundedCornerShape(50),
            border = BorderStroke(0.85.dp, NeonCyan.copy(alpha = 0.65f)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("vault_settings_button"),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "إعدادات الخزنة",
                tint = NeonCyan,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "الإعدادات",
                style = MaterialTheme.typography.labelSmall,
                color = ChromeSilver,
            )
        }
    }
}

@Composable
private fun VaultFolderGridTile(
    title: String,
    badgeText: String,
    subtitleText: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xFF0E1522),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(0.95.dp, accent.copy(alpha = 0.48f)),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.96f)
            .clip(RoundedCornerShape(22.dp))
            .clickable { onClick() }
            .minimumInteractiveComponentSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.24f),
                                accent.copy(alpha = 0.05f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.34f),
                            radius = size.width * 0.72f,
                        ),
                        radius = size.width * 0.72f,
                        center = Offset(size.width * 0.5f, size.height * 0.34f),
                    )
                }
                .padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
            Surface(
                color = accent.copy(alpha = 0.16f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = accent,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    color = accent.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.85.dp, accent.copy(alpha = 0.55f)),
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    ),
                    color = ChromeSilver,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MutedSlate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
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
        shape = RoundedCornerShape(6.dp),
        border = if (isSelected) BorderStroke(2.2.dp, accent) else null,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
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

            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(ObsidianVoid.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "محدد" else "تحديد",
                        tint = if (isSelected) accent else ChromeSilver,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultGalleryFileTile(
    entry: VaultFileEntry,
    accent: Color,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val extLabel = remember(entry.displayTitle) {
        entry.displayTitle.substringAfterLast('.', "").uppercase(Locale.US).take(5)
    }

    Surface(
        color = Color(0xFF0E1420),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (isSelected) 2.2.dp else 0.8.dp,
            color = if (isSelected) accent else GlassStrokeBright.copy(alpha = 0.22f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.95f)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .testTag("vault_entry_${entry.id}"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.18f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.32f),
                            radius = size.width * 0.65f,
                        ),
                        radius = size.width * 0.65f,
                        center = Offset(size.width * 0.5f, size.height * 0.32f),
                    )
                }
                .padding(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(ObsidianVoid.copy(alpha = 0.7f)),
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

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    color = accent.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.5f)),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (extLabel.isNotBlank()) {
                            Text(
                                text = extLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = accent,
                                maxLines = 1,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Description,
                                contentDescription = entry.displayTitle,
                                tint = accent,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = entry.displayTitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = ChromeSilver,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatByteSize(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MutedSlate,
                    maxLines = 1,
                )
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
