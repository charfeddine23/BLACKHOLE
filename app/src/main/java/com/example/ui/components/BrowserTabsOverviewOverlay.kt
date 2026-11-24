package com.example.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.DimSlate
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet

internal data class BrowserTabPreviewItem(
    val index: Int,
    val id: String,
    val title: String,
    val url: String,
    val isHome: Boolean,
    val isIncognito: Boolean,
    val isActive: Boolean,
    val thumbnail: Bitmap?,
    val favicon: Bitmap?,
)

/**
 * Samsung-Browser-inspired full-screen Tabs overview overlay shared by both
 * Downloaded Videos Browser and Vault Private Browser.
 */
@Composable
internal fun BrowserTabsOverviewOverlay(
    visible: Boolean,
    tabs: List<BrowserTabPreviewItem>,
    activeThumbnail: Bitmap?,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onAddTab: (incognito: Boolean) -> Unit,
    onCloseAllTabs: (incognitoOnly: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(
            initialScale = 0.96f,
            transformOrigin = TransformOrigin.Center,
        ),
        exit = fadeOut() + scaleOut(
            targetScale = 0.96f,
            transformOrigin = TransformOrigin.Center,
        ),
        modifier = modifier.fillMaxSize(),
    ) {
        val activeIsPrivate = tabs.firstOrNull { it.isActive }?.isIncognito == true
        var showPrivateFilter by rememberSaveable(visible) { mutableStateOf(activeIsPrivate) }
        var isCompactList by rememberSaveable { mutableStateOf(false) }
        var showSearchBar by rememberSaveable(visible) { mutableStateOf(false) }
        var searchQuery by rememberSaveable(visible) { mutableStateOf("") }
        var showMoreMenu by remember { mutableStateOf(false) }

        val standardCount = tabs.count { !it.isIncognito }
        val privateCount = tabs.count { it.isIncognito }

        val visibleTabs = remember(tabs, showPrivateFilter, searchQuery) {
            val base = tabs.filter { it.isIncognito == showPrivateFilter }
            val q = searchQuery.trim()
            if (q.isEmpty()) {
                base
            } else {
                base.filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.url.contains(q, ignoreCase = true)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .testTag("browser_tabs_overview_screen"),
        ) {
            // Frosted/Blurred Backdrop (from live active tab preview or deep nocturnal obsidian)
            if (activeThumbnail != null) {
                val bgMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier
                        .fillMaxSize()
                        .blur(34.dp)
                } else {
                    Modifier.fillMaxSize()
                }
                Image(
                    bitmap = activeThumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = bgMod,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ObsidianVoid.copy(alpha = 0.86f),
                                Color(0xFF0C121D).copy(alpha = 0.91f),
                                ObsidianVoid.copy(alpha = 0.94f),
                            ),
                        ),
                    ),
            )

            Column(modifier = Modifier.fillMaxSize()) {
                // Top Header Bar: Back arrow + "Tabs" + Search + More
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("browser_tabs_overview_back"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to browser",
                            tint = ChromeSilver,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    Text(
                        text = if (showPrivateFilter) "Private tabs" else "Tabs",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        ),
                        color = if (showPrivateFilter) PlasmaViolet else ChromeSilver,
                        modifier = Modifier.weight(1f),
                    )

                    IconButton(
                        onClick = {
                            showSearchBar = !showSearchBar
                            if (!showSearchBar) searchQuery = ""
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("browser_tabs_overview_search_toggle"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search tabs",
                            tint = if (showSearchBar) NeonCyan else ChromeSilver,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("browser_tabs_overview_more_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More tab actions",
                                tint = ChromeSilver,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            containerColor = Color(0xFF1A2230),
                        ) {
                            DropdownMenuItem(
                                text = { Text("New tab", color = ChromeSilver) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Add, contentDescription = null, tint = NeonCyan)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    onAddTab(false)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("New private tab", color = ChromeSilver) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.VisibilityOff, contentDescription = null, tint = PlasmaViolet)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    onAddTab(true)
                                },
                            )
                            if (privateCount > 0) {
                                DropdownMenuItem(
                                    text = { Text("Close private tabs ($privateCount)", color = ChromeSilver) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = PlasmaViolet)
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        onCloseAllTabs(true)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Close all tabs", color = CrimsonAlert) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = CrimsonAlert)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    onCloseAllTabs(false)
                                },
                            )
                        }
                    }
                }

                // Optional inline tab search pill
                AnimatedVisibility(visible = showSearchBar) {
                    Surface(
                        color = Color(0xFF151C28),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(0.8.dp, NeonCyan.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .height(42.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MutedSlate,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                cursorBrush = SolidColor(NeonCyan),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ChromeSilver),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("browser_tabs_search_input"),
                                decorationBox = { inner ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search open tabs...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = DimSlate,
                                        )
                                    }
                                    inner()
                                },
                            )
                            if (searchQuery.isNotEmpty()) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear tab search",
                                    tint = MutedSlate,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { searchQuery = "" },
                                )
                            }
                        }
                    }
                }

                // Grid of Open Tabs (or Empty State when filtering empty Private/Search)
                if (visibleTabs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            color = Color(0xFF141B27).copy(alpha = 0.9f),
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(0.8.dp, GlassStrokeBright.copy(alpha = 0.18f)),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    imageVector = if (showPrivateFilter) Icons.Rounded.VisibilityOff else Icons.Outlined.Public,
                                    contentDescription = null,
                                    tint = if (showPrivateFilter) PlasmaViolet else NeonCyan,
                                    modifier = Modifier.size(38.dp),
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = when {
                                        searchQuery.isNotBlank() -> "No matching tabs"
                                        showPrivateFilter -> "No private tabs open"
                                        else -> "No tabs open"
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = ChromeSilver,
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = { onAddTab(showPrivateFilter) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (showPrivateFilter) PlasmaViolet else NeonCyan,
                                        contentColor = ObsidianVoid,
                                    ),
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (showPrivateFilter) "New private tab" else "New tab",
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (isCompactList) 1 else 2),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 104.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        items(
                            items = visibleTabs,
                            key = { it.id },
                        ) { item ->
                            SamsungStyleTabGridCard(
                                item = item,
                                isCompact = isCompactList,
                                onSelect = { onSelectTab(item.index) },
                                onClose = { onCloseTab(item.index) },
                            )
                        }
                    }
                }
            }

            // Bottom Floating Pill (Layout | [N] Standard | Private Mask) + Circular '+' Button
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp, start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = Color(0xFF1A2230).copy(alpha = 0.96f),
                    shape = RoundedCornerShape(32.dp),
                    border = BorderStroke(0.85.dp, GlassStrokeBright.copy(alpha = 0.22f)),
                    shadowElevation = 14.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Left Segment: Grid / Compact list layout toggle
                        Box(
                            modifier = Modifier
                                .size(width = 52.dp, height = 40.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    if (isCompactList) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                                )
                                .clickable { isCompactList = !isCompactList }
                                .testTag("browser_tabs_layout_toggle"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (isCompactList) Icons.Rounded.GridView else Icons.Rounded.ViewAgenda,
                                contentDescription = if (isCompactList) "Grid view" else "List view",
                                tint = if (isCompactList) NeonCyan else ChromeSilver,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        // Middle Segment: Standard Tabs [N] (active pill highlight matching screenshot)
                        Box(
                            modifier = Modifier
                                .size(width = 58.dp, height = 40.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    if (!showPrivateFilter) Color.White.copy(alpha = 0.14f) else Color.Transparent,
                                )
                                .clickable { showPrivateFilter = false }
                                .testTag("browser_tabs_filter_standard"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .border(
                                        width = 1.7.dp,
                                        color = if (!showPrivateFilter) ChromeSilver else MutedSlate,
                                        shape = RoundedCornerShape(6.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = standardCount.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = if (!showPrivateFilter) ChromeSilver else MutedSlate,
                                )
                            }
                        }

                        // Right Segment: Private/Secret Tabs Filter (Mask Icon)
                        Box(
                            modifier = Modifier
                                .size(width = 58.dp, height = 40.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    if (showPrivateFilter) PlasmaViolet.copy(alpha = 0.24f) else Color.Transparent,
                                )
                                .clickable { showPrivateFilter = true }
                                .testTag("browser_tabs_filter_private"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VisibilityOff,
                                contentDescription = "Private tabs ($privateCount)",
                                tint = if (showPrivateFilter) PlasmaViolet else ChromeSilver,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }
                }

                // Separate Circular '+' New Tab FAB matching screenshot
                Surface(
                    color = Color(0xFF1A2230).copy(alpha = 0.96f),
                    shape = CircleShape,
                    border = BorderStroke(
                        width = 0.9.dp,
                        color = if (showPrivateFilter) PlasmaViolet.copy(alpha = 0.55f) else NeonCyan.copy(alpha = 0.48f),
                    ),
                    shadowElevation = 14.dp,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .clickable { onAddTab(showPrivateFilter) }
                        .testTag("browser_tabs_grid_add_button"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = if (showPrivateFilter) "New private tab" else "New tab",
                            tint = if (showPrivateFilter) PlasmaViolet else ChromeSilver,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SamsungStyleTabGridCard(
    item: BrowserTabPreviewItem,
    isCompact: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val activeAccent = if (item.isIncognito) PlasmaViolet else NeonCyan
    val borderStroke = when {
        item.isActive -> BorderStroke(2.dp, activeAccent.copy(alpha = 0.9f))
        item.isIncognito -> BorderStroke(0.9.dp, PlasmaViolet.copy(alpha = 0.45f))
        else -> BorderStroke(0.85.dp, GlassStrokeBright.copy(alpha = 0.22f))
    }

    Surface(
        color = Color(0xFF111824),
        shape = RoundedCornerShape(20.dp),
        border = borderStroke,
        shadowElevation = if (item.isActive) 12.dp else 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (isCompact) 1.72f else 0.74f)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onSelect)
            .testTag("browser_tabs_grid_card_${item.index}"),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Live Tab Page Preview Thumbnail or Start Page / Loading Miniature
            when {
                item.thumbnail != null -> {
                    Image(
                        bitmap = item.thumbnail.asImageBitmap(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                item.isHome -> {
                    StartPageMiniaturePreview(isIncognito = item.isIncognito)
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color(0xFF141D2B), Color(0xFF0A0F18)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(activeAccent.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = item.title.take(1).uppercase().ifBlank { "W" },
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = activeAccent,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = item.url.substringAfter("://").substringBefore("/"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSlate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // 2. Top-left Incognito Pill Badge (if private tab)
            if (item.isIncognito) {
                Surface(
                    color = Color(0xCC121622),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(0.6.dp, PlasmaViolet.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VisibilityOff,
                            contentDescription = "Private tab",
                            tint = PlasmaViolet,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }

            // 3. Top-right Circular Translucent Close 'X' Button (matching screenshot)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .testTag("browser_tabs_grid_close_${item.index}"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0x99121722))
                        .border(0.7.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close tab",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            // 4. Bottom Title Gradient Scrim + Circular Favicon + Single-Line Title
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xBB090D15),
                                Color(0xEB070A10),
                            ),
                        ),
                    )
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (item.favicon != null) {
                        Image(
                            bitmap = item.favicon.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(
                                    if (item.isIncognito) PlasmaViolet.copy(alpha = 0.26f) else activeAccent.copy(alpha = 0.22f),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (item.isIncognito) Icons.Rounded.VisibilityOff else Icons.Outlined.Public,
                                contentDescription = null,
                                tint = activeAccent,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }

                    Text(
                        text = item.title.ifBlank { if (item.isIncognito) "Private" else "New tab" },
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp,
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StartPageMiniaturePreview(isIncognito: Boolean) {
    val accent = if (isIncognito) PlasmaViolet else NeonCyan
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0E1420), Color(0xFF070A10)),
                ),
            )
            .padding(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 18.dp, bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(0.5.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(50)),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.07f)),
                            )
                        }
                    }
                }
            }
        }
    }
}
