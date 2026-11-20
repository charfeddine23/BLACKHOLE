package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.vault.crypto.VaultOperationStage
import com.example.vault.data.VaultCategory
import com.example.vault.data.VaultFileEntry
import com.example.vault.data.VaultStorageMode
import com.example.vault.ui.VaultTransferUiState
import com.example.vault.ui.VaultUiState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultHomeCategoriesDashboard(
    uiState: VaultUiState,
    onBack: () -> Unit,
    onLockNow: () -> Unit,
    onOpenCategory: (VaultCategory) -> Unit,
    onSelectPhoneDownloads: () -> Unit,
    onSelectSafFolder: () -> Unit,
    onRevertInternalStorage: () -> Unit,
    onCancelTransfer: () -> Unit,
    onDismissStatus: () -> Unit,
) {
    val photoEntries = remember(uiState.entries) {
        uiState.entries.filter { it.category == VaultCategory.PHOTOS }
    }
    val videoEntries = remember(uiState.entries) {
        uiState.entries.filter { it.category == VaultCategory.VIDEOS }
    }
    val fileEntries = remember(uiState.entries) {
        uiState.entries.filter { it.category == VaultCategory.FILES }
    }

    val photosTotalBytes = remember(photoEntries) { photoEntries.sumOf { it.sizeBytes } }
    val videosTotalBytes = remember(videoEntries) { videoEntries.sumOf { it.sizeBytes } }
    val filesTotalBytes = remember(fileEntries) { fileEntries.sumOf { it.sizeBytes } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Header Row
        VaultTopHeaderBar(
            title = "BLACKHOLE VAULT",
            subtitle = "BHVAULT2 · AES-256-GCM · ${uiState.entries.size} ENCRYPTED",
            subtitleColor = NeonCyan,
            onBack = onBack,
            onLockNow = onLockNow,
        )

        // Storage Location Switcher Card
        VaultStorageModeCard(
            uiState = uiState,
            onSelectPhoneDownloads = onSelectPhoneDownloads,
            onSelectSafFolder = onSelectSafFolder,
            onRevertInternalStorage = onRevertInternalStorage,
        )

        // Transfer Progress & Inline Status Banners
        VaultTransferAndStatusSection(
            uiState = uiState,
            onCancelTransfer = onCancelTransfer,
            onDismissStatus = onDismissStatus,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "الأقسام المشفرة",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = ChromeSilver,
            modifier = Modifier.padding(horizontal = 18.dp),
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Top Row: 2 Bento Tiles (صور & فيديوهات)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VaultCategoryBentoTile(
                title = "صور",
                subtitle = "Photos Vault",
                count = photoEntries.size,
                totalSizeBytes = photosTotalBytes,
                icon = Icons.Outlined.PhotoLibrary,
                accent = NeonCyan,
                onClick = { onOpenCategory(VaultCategory.PHOTOS) },
                modifier = Modifier
                    .weight(1f)
                    .height(172.dp)
                    .testTag("vault_category_photos"),
            )

            VaultCategoryBentoTile(
                title = "فيديوهات",
                subtitle = "Videos Vault",
                count = videoEntries.size,
                totalSizeBytes = videosTotalBytes,
                icon = Icons.Outlined.VideoLibrary,
                accent = PlasmaViolet,
                onClick = { onOpenCategory(VaultCategory.VIDEOS) },
                modifier = Modifier
                    .weight(1f)
                    .height(172.dp)
                    .testTag("vault_category_videos"),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Bottom Wide Bento Tile (ملفات)
        VaultWideCategoryTile(
            title = "ملفات",
            subtitle = "PDF · مستندات · صوتيات · أرشيف",
            count = fileEntries.size,
            totalSizeBytes = filesTotalBytes,
            icon = Icons.Rounded.FolderCopy,
            accent = EmeraldPulse,
            onClick = { onOpenCategory(VaultCategory.FILES) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("vault_category_files"),
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun VaultCategoryDetailScreen(
    category: VaultCategory,
    uiState: VaultUiState,
    onBackToCategories: () -> Unit,
    onLockNow: () -> Unit,
    onPickFromGallery: () -> Unit,
    onPickFromPhoneFiles: () -> Unit,
    onEncryptDownloadedVideo: (File) -> Unit,
    onCancelTransfer: () -> Unit,
    onPlayEntry: (VaultFileEntry) -> Unit,
    onDeleteEntry: (VaultFileEntry) -> Unit,
    onDismissStatus: () -> Unit,
) {
    val categoryEntries = remember(uiState.entries, category) {
        uiState.entries.filter { it.category == category }
    }

    val categoryTitle = when (category) {
        VaultCategory.PHOTOS -> "صور"
        VaultCategory.VIDEOS -> "فيديوهات"
        VaultCategory.FILES -> "ملفات"
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
            subtitle = "${categoryEntries.size} عنصر مشفر · ${uiState.storageLabel}",
            subtitleColor = categoryAccent,
            onBack = onBackToCategories,
            onLockNow = onLockNow,
        )

        // Direct Action Bar inside Category: Add from Gallery / Add from Phone Files
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
                    contentDescription = "Browse phone files",
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
        )

        // Optional quick import strip inside VIDEOS for in-app downloaded videos
        if (category == VaultCategory.VIDEOS &&
            uiState.availableDownloadedVideos.isNotEmpty() &&
            uiState.transferState is VaultTransferUiState.Idle
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "استيراد من فيديوهات التطبيق (${uiState.availableDownloadedVideos.size})",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NeonCyan,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                )
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
                                .clickable { onEncryptDownloadedVideo(file) }
                                .minimumInteractiveComponentSize()
                                .testTag("vault_import_download_${file.name}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = "Encrypt ${file.name}",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(14.dp),
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
        }

        // Stored Items inside the selected Category
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
                        text = "اضغط على زر \"إضافة من المعرض\" لاختيار ونقل أي عنصر من هاتفك وتشفيره بأمان.",
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
        } else if (category == VaultCategory.PHOTOS) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(categoryEntries, key = { it.uriString }) { item ->
                    VaultPhotoGridCard(
                        entry = item,
                        onOpen = { onPlayEntry(item) },
                        onDelete = { onDeleteEntry(item) },
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(categoryEntries, key = { it.uriString }) { item ->
                    EncryptedVaultFileCard(
                        entry = item,
                        accent = categoryAccent,
                        onPlay = { onPlayEntry(item) },
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
                contentDescription = "Lock vault",
                tint = PlasmaViolet,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "LOCK",
                style = MaterialTheme.typography.labelSmall,
                color = ChromeSilver,
            )
        }
    }
}

@Composable
private fun VaultStorageModeCard(
    uiState: VaultUiState,
    onSelectPhoneDownloads: () -> Unit,
    onSelectSafFolder: () -> Unit,
    onRevertInternalStorage: () -> Unit,
) {
    Surface(
        color = Color(0xFF0E1420),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            0.85.dp,
            if (uiState.isExternalSafActive) EmeraldPulse.copy(alpha = 0.55f) else GlassStrokeBright.copy(alpha = 0.25f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = "Vault storage location",
                    tint = if (uiState.isExternalSafActive) EmeraldPulse else NeonCyan,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.storageLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = ChromeSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (uiState.storageMode) {
                            VaultStorageMode.PHONE_DOWNLOADS ->
                                "محفوظة في مجلد Download/BlackholeVault على الهاتف."
                            VaultStorageMode.SAF_EXTERNAL ->
                                "مجلد خارجي مختار — يبقى حتى بعد حذف التطبيق."
                            VaultStorageMode.INTERNAL ->
                                "التخزين الداخلي الخاص — يمكنك التبديل لمجلد الهاتف."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate,
                        maxLines = 2,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VaultStorageChip(
                    label = "PHONE FOLDER",
                    selected = uiState.storageMode == VaultStorageMode.PHONE_DOWNLOADS,
                    accent = EmeraldPulse,
                    onClick = onSelectPhoneDownloads,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("vault_phone_folder_button"),
                )
                VaultStorageChip(
                    label = "PICK SAF",
                    selected = uiState.storageMode == VaultStorageMode.SAF_EXTERNAL,
                    accent = NeonCyan,
                    onClick = onSelectSafFolder,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("vault_choose_saf_folder_button"),
                )
                VaultStorageChip(
                    label = "INTERNAL",
                    selected = uiState.storageMode == VaultStorageMode.INTERNAL,
                    accent = PlasmaViolet,
                    onClick = onRevertInternalStorage,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("vault_internal_folder_button"),
                )
            }
        }
    }
}

@Composable
private fun VaultCategoryBentoTile(
    title: String,
    subtitle: String,
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
        border = BorderStroke(0.95.dp, accent.copy(alpha = 0.42f)),
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.22f),
                                accent.copy(alpha = 0.05f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.78f, size.height * 0.22f),
                            radius = size.minDimension * 0.75f,
                        ),
                        radius = size.minDimension * 0.75f,
                        center = Offset(size.width * 0.78f, size.height * 0.22f),
                    )
                }
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Surface(
                        color = accent.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(0.8.dp, accent.copy(alpha = 0.55f)),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = title,
                                tint = accent,
                                modifier = Modifier.size(25.dp),
                            )
                        }
                    }

                    Surface(
                        color = accent.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = accent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 21.sp,
                        ),
                        color = ChromeSilver,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (count > 0) "$subtitle · ${formatVaultMb(totalSizeBytes)}" else subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultWideCategoryTile(
    title: String,
    subtitle: String,
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
        border = BorderStroke(0.95.dp, accent.copy(alpha = 0.42f)),
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .minimumInteractiveComponentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.18f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.12f, size.height * 0.5f),
                            radius = size.height * 0.9f,
                        ),
                        radius = size.height * 0.9f,
                        center = Offset(size.width * 0.12f, size.height * 0.5f),
                    )
                }
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    color = accent.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.55f)),
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accent,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        ),
                        color = ChromeSilver,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (count > 0) "$count محفوظ · ${formatVaultMb(totalSizeBytes)}" else subtitle,
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
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
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

@Composable
private fun VaultPhotoGridCard(
    entry: VaultFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(entry.uriString) { mutableStateOf(false) }

    Surface(
        color = Color(0xFF0E1420),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(0.85.dp, NeonCyan.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onOpen() }
            .testTag("vault_entry_${entry.id}"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(94.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF0A1926), Color(0xFF101729)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = "Encrypted photo ${entry.displayTitle}",
                    tint = NeonCyan,
                    modifier = Modifier.size(38.dp),
                )
                Surface(
                    color = ObsidianVoid.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = EmeraldPulse,
                            modifier = Modifier.size(10.dp),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "AES",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = EmeraldPulse,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = entry.displayTitle,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = ChromeSilver,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatVaultMb(entry.sizeBytes)} · ${formatVaultDate(entry.lastModifiedMs)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MutedSlate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (confirmDelete) {
                    Surface(
                        color = CrimsonAlert,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                confirmDelete = false
                                onDelete()
                            },
                    ) {
                        Text(
                            text = "حذف",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    IconButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "Delete encrypted photo",
                            tint = CrimsonAlert,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EncryptedVaultFileCard(
    entry: VaultFileEntry,
    accent: Color,
    onPlay: () -> Unit,
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
            .clickable { onPlay() }
            .testTag("vault_entry_${entry.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    color = accent.copy(alpha = 0.14f),
                    shape = CircleShape,
                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.55f)),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (entry.category == VaultCategory.VIDEOS) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Decrypt and play ${entry.displayTitle}",
                                tint = accent,
                                modifier = Modifier.size(24.dp),
                            )
                        } else {
                            val extLabel = entry.displayTitle
                                .substringAfterLast('.', "")
                                .uppercase(Locale.US)
                                .take(4)
                            if (extLabel.isNotBlank() && extLabel != "BHV") {
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
                                    contentDescription = "Decrypt and open ${entry.displayTitle}",
                                    tint = accent,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
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
                        text = "${if (entry.isSafExternal) "External .bhv" else "Internal .bhv"} · ${formatVaultMb(entry.sizeBytes)} · ${formatVaultDate(entry.lastModifiedMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                        text = "CONFIRM",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete encrypted file",
                        tint = CrimsonAlert,
                    )
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
                    val actionTitle = when {
                        transfer.stage == VaultOperationStage.DERIVING_KEY -> "تجهيز مفتاح AES-256-GCM..."
                        transfer.isEncrypting -> "جاري التشفير: ${transfer.label}"
                        else -> "فك التشفير والتحقق: ${transfer.label}"
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
                        text = "${formatVaultMb(transfer.bytesProcessed)} / ${formatVaultMb(transfer.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate,
                    )
                    Text(
                        text = "CANCEL",
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
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss message",
                            tint = MutedSlate,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultStorageChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (selected) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            0.85.dp,
            if (selected) accent else GlassStrokeBright.copy(alpha = 0.22f),
        ),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .minimumInteractiveComponentSize(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (selected) accent else ChromeSilver,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatVaultMb(bytes: Long): String {
    if (bytes <= 0L) return "0.0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}

private fun formatVaultDate(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    return SimpleDateFormat("MMM d", Locale.US).format(Date(timestampMs))
}
