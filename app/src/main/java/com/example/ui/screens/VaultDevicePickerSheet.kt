package com.example.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.util.formatByteSize
import com.example.util.formatDurationMs
import com.example.vault.data.DeviceMediaItem
import com.example.vault.data.VaultCategory
import com.example.vault.ui.VaultDevicePickerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun VaultDevicePickerSheet(
    pickerState: VaultDevicePickerUiState,
    hasMediaAccess: Boolean,
    hasAllFilesAccess: Boolean,
    onClose: () -> Unit,
    onNavigateUpDirectory: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onSwitchBrowseMode: (Boolean) -> Unit,
    onToggleSelect: (String) -> Unit,
    onClearSelection: () -> Unit,
    onConfirmMoveSelected: () -> Unit,
    onRequestMediaPermission: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onOpenSystemPickerFallback: () -> Unit,
) {
    val accent = when (pickerState.category) {
        VaultCategory.PHOTOS -> NeonCyan
        VaultCategory.VIDEOS -> PlasmaViolet
        VaultCategory.FILES -> EmeraldPulse
    }

    var selectedFolderFilter by remember(pickerState.category, pickerState.browseFoldersMode) {
        mutableStateOf<String?>(null)
    }

    val folderNames = remember(pickerState.items, pickerState.browseFoldersMode) {
        if (pickerState.browseFoldersMode) {
            emptyList()
        } else {
            pickerState.items.map { it.folderName }.distinct().take(12)
        }
    }

    val visibleItems = remember(pickerState.items, selectedFolderFilter, pickerState.browseFoldersMode) {
        if (pickerState.browseFoldersMode || selectedFolderFilter == null) {
            pickerState.items
        } else {
            pickerState.items.filter { it.folderName == selectedFolderFilter }
        }
    }

    Surface(
        color = ObsidianVoid,
        modifier = Modifier
            .fillMaxSize()
            .testTag("vault_device_picker_sheet"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(
                        onClick = {
                            if (pickerState.canNavigateUp) {
                                onNavigateUpDirectory()
                            } else {
                                onClose()
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("vault_picker_close_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = if (pickerState.canNavigateUp) "المجلد السابق" else "إغلاق",
                            tint = ChromeSilver,
                        )
                    }
                    Column {
                        Text(
                            text = when {
                                pickerState.browseFoldersMode -> "تصفح ملفات الهاتف"
                                pickerState.category == VaultCategory.PHOTOS -> "صور الهاتف"
                                pickerState.category == VaultCategory.VIDEOS -> "فيديوهات الهاتف"
                                else -> "ملفات الهاتف"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = ChromeSilver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "اختر لنقلها وحمايتها داخل الخزنة",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            maxLines = 1,
                        )
                    }
                }

                OutlinedButton(
                    onClick = onOpenSystemPickerFallback,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(0.8.dp, GlassStrokeBright.copy(alpha = 0.3f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("vault_picker_system_fallback_button"),
                ) {
                    Text(
                        text = "مختار النظام",
                        style = MaterialTheme.typography.labelSmall,
                        color = ChromeSilver,
                    )
                }
            }

            // Mode Tabs: Gallery vs Phone Folders
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PickerModeChip(
                    label = "المعرض والوسائط",
                    icon = Icons.Outlined.PhotoLibrary,
                    selected = !pickerState.browseFoldersMode,
                    accent = accent,
                    onClick = { onSwitchBrowseMode(false) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("vault_picker_mode_gallery"),
                )
                PickerModeChip(
                    label = "مجلدات الهاتف",
                    icon = Icons.Outlined.FolderOpen,
                    selected = pickerState.browseFoldersMode,
                    accent = accent,
                    onClick = { onSwitchBrowseMode(true) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("vault_picker_mode_folders"),
                )
            }

            // Permission Banner if missing
            if (!hasMediaAccess || !hasAllFilesAccess) {
                Surface(
                    color = Color(0xFF101928),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.85.dp, accent.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (!hasMediaAccess) {
                                    "السماح بالوصول إلى صور وفيديوهات وملفات الهاتف لعرضها هنا."
                                } else {
                                    "فعّل صلاحية كل الملفات ليُشفّر الملف ويختفي من الهاتف نهائياً فور نقله."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = ChromeSilver,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (!hasMediaAccess) onRequestMediaPermission() else onRequestAllFilesAccess()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accent,
                                contentColor = ObsidianVoid,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("vault_picker_grant_permission_button"),
                        ) {
                            Text(
                                text = if (!hasMediaAccess) {
                                    "السماح بالوصول لملفات الهاتف"
                                } else {
                                    "تفعيل صلاحية كل الملفات (حذف الأصل تلقائياً)"
                                },
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }

            // Subheader: Breadcrumb or Folder Filters
            if (pickerState.browseFoldersMode) {
                pickerState.currentDirectoryPath?.let { path ->
                    Surface(
                        color = Color(0xFF0E1522),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSlate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (pickerState.canNavigateUp) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "للأعلى ↑",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onNavigateUpDirectory() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            } else if (folderNames.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterPill(
                            label = "الكل",
                            selected = selectedFolderFilter == null,
                            accent = accent,
                            onClick = { selectedFolderFilter = null },
                        )
                    }
                    items(folderNames) { folder ->
                        FilterPill(
                            label = folder,
                            selected = selectedFolderFilter == folder,
                            accent = accent,
                            onClick = { selectedFolderFilter = folder },
                        )
                    }
                }
            }

            // Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    pickerState.isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = accent)
                        }
                    }
                    visibleItems.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(28.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                tint = MutedSlate,
                                modifier = Modifier.size(44.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (!hasMediaAccess) {
                                    "يرجى منح صلاحية الوصول لعرض ملفات الهاتف هنا"
                                } else {
                                    "لا توجد عناصر في هذا المجلد"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = ChromeSilver,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            if (!hasMediaAccess) {
                                Button(
                                    onClick = onRequestMediaPermission,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = accent,
                                        contentColor = ObsidianVoid,
                                    ),
                                    modifier = Modifier.height(48.dp),
                                ) {
                                    Text("منح الصلاحية الآن", fontWeight = FontWeight.Bold)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onOpenSystemPickerFallback,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.height(48.dp),
                                ) {
                                    Text("فتح مختار النظام", color = ChromeSilver)
                                }
                            }
                        }
                    }
                    pickerState.browseFoldersMode -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 100.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(visibleItems, key = { it.id }) { item ->
                                val selected = item.uriString in pickerState.selectedUris
                                DeviceFolderGridTile(
                                    item = item,
                                    selected = selected,
                                    accent = accent,
                                    onClick = {
                                        if (item.isDirectory && item.filePath != null) {
                                            onOpenDirectory(item.filePath)
                                        } else {
                                            onToggleSelect(item.uriString)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 100.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(visibleItems, key = { it.id }) { item ->
                                val selected = item.uriString in pickerState.selectedUris
                                DeviceGalleryGridItem(
                                    item = item,
                                    selected = selected,
                                    accent = accent,
                                    onClick = { onToggleSelect(item.uriString) },
                                )
                            }
                        }
                    }
                }
            }

            // Sticky Move Selected Footer Bar
            if (pickerState.selectedUris.isNotEmpty()) {
                Surface(
                    color = Color(0xFF0E1522),
                    border = BorderStroke(0.85.dp, accent.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onClearSelection,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(0.85.dp, GlassStrokeBright.copy(alpha = 0.35f)),
                            modifier = Modifier.height(48.dp),
                        ) {
                            Text("إلغاء (${pickerState.selectedUris.size})", color = ChromeSilver)
                        }
                        Button(
                            onClick = onConfirmMoveSelected,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accent,
                                contentColor = ObsidianVoid,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("vault_confirm_move_selected_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.DriveFileMove,
                                contentDescription = null,
                                modifier = Modifier.size(19.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "نقل وحماية (${pickerState.selectedUris.size})",
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
private fun PickerModeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (selected) accent.copy(alpha = 0.18f) else Color(0xFF0E1522),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.9.dp, if (selected) accent else GlassStrokeBright.copy(alpha = 0.24f)),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .minimumInteractiveComponentSize(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accent else ChromeSilver,
                modifier = Modifier.size(17.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (selected) accent else ChromeSilver,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) accent.copy(alpha = 0.2f) else Color(0xFF0E1522),
        shape = RoundedCornerShape(50),
        border = BorderStroke(0.8.dp, if (selected) accent else GlassStrokeBright.copy(alpha = 0.22f)),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick() },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (selected) accent else ChromeSilver,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun DeviceGalleryGridItem(
    item: DeviceMediaItem,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) {
            loadDeviceMediaThumbnail(context, item)
        }
    }

    Surface(
        color = Color(0xFF0E1420),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 2.dp else 0.75.dp, if (selected) accent else GlassStrokeBright.copy(alpha = 0.2f)),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag("picker_item_${item.id}"),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumb != null) {
                Image(
                    bitmap = thumb!!,
                    contentDescription = item.displayName,
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
                        imageVector = if (item.category == VaultCategory.VIDEOS) Icons.Rounded.PlayArrow else Icons.Rounded.Image,
                        contentDescription = item.displayName,
                        tint = accent.copy(alpha = 0.7f),
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            if (item.category == VaultCategory.VIDEOS) {
                Surface(
                    color = ObsidianVoid.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = if (item.durationMs > 0L) formatDurationMs(item.durationMs) else formatByteSize(item.sizeBytes),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = Color.White,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(ObsidianVoid.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (selected) "محدد" else "تحديد",
                    tint = if (selected) accent else ChromeSilver,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceFolderGridTile(
    item: DeviceMediaItem,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val tileAccent = if (item.isDirectory) NeonCyan else accent
    Surface(
        color = if (selected) tileAccent.copy(alpha = 0.16f) else Color(0xFF0E1420),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 0.75.dp,
            color = if (selected) tileAccent else GlassStrokeBright.copy(alpha = 0.2f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.95f)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .minimumInteractiveComponentSize()
            .testTag("picker_row_${item.id}"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
        ) {
            if (!item.isDirectory) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(ObsidianVoid.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (selected) "محدد" else "تحديد",
                        tint = if (selected) tileAccent else ChromeSilver,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    color = tileAccent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (item.isDirectory) Icons.Rounded.Folder else Icons.Outlined.Description,
                            contentDescription = item.displayName,
                            tint = tileAccent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = item.displayName,
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
                    text = if (item.isDirectory) "مجلد" else formatByteSize(item.sizeBytes),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MutedSlate,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun loadDeviceMediaThumbnail(context: Context, item: DeviceMediaItem): ImageBitmap? {
    val uri = Uri.parse(item.uriString)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme.equals("content", ignoreCase = true)) {
        val osThumb = runCatching {
            context.contentResolver.loadThumbnail(uri, Size(360, 360), null).asImageBitmap()
        }.getOrNull()
        if (osThumb != null) return osThumb
    }

    if (item.category == VaultCategory.VIDEOS) {
        val retriever = MediaMetadataRetriever()
        return try {
            val localPath = item.filePath?.takeIf { File(it).exists() }
            if (localPath != null) {
                retriever.setDataSource(localPath)
            } else {
                retriever.setDataSource(context, uri)
            }
            val frame = retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L)
            frame?.asImageBitmap()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    val localPath = item.filePath?.takeIf { File(it).exists() }
    if (localPath != null) {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(localPath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sampleSize = 1
            while (longest / sampleSize > 360 && sampleSize < 32) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(localPath, opts)?.asImageBitmap()
        }.getOrNull()
    }

    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sampleSize = 1
        while (longest / sampleSize > 360 && sampleSize < 32) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
        }
    }.getOrNull()
}
