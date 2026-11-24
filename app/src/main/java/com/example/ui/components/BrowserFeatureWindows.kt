package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.BrowserBookmark
import com.example.data.local.BrowserHistoryEntry
import com.example.data.local.BrowserSearchEngine
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.DimSlate
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaViolet
import com.example.vault.browser.VideoElementTelemetry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal enum class BrowserFeatureWindow {
    HISTORY,
    DOWNLOADS,
    BOOKMARKS,
    ADD_PAGE_TO,
    SETTINGS,
    AD_BLOCKERS,
    PRIVACY,
    SEARCH_ENGINE,
    TEXT_SIZE,
    VPN,
}

@Composable
internal fun BrowserFeatureWindowHost(
    currentWindow: BrowserFeatureWindow?,
    canNavigateBackInWindow: Boolean,
    onNavigateToWindow: (BrowserFeatureWindow) -> Unit,
    onBackWindow: () -> Unit,
    onCloseAllWindows: () -> Unit,
    // Page context
    isHomeTab: Boolean,
    activePageUrl: String,
    activePageTitle: String,
    isCurrentBookmarked: Boolean,
    isCurrentPinnedToHome: Boolean,
    enableEditableSpeedDials: Boolean,
    activeDetectedVideo: VideoElementTelemetry?,
    statusMessage: String?,
    // Stored data & state
    historyEntries: List<BrowserHistoryEntry>,
    bookmarks: List<BrowserBookmark>,
    adBlockEnabled: Boolean,
    blockedAdsCount: Int,
    isVpnActive: Boolean,
    isDesktopMode: Boolean,
    isPermanentCookieLock: Boolean,
    syncedDomains: List<String>,
    storedCookieCount: Int,
    searchEngine: BrowserSearchEngine,
    zoomPercent: Int,
    // Actions
    onOpenUrl: (String) -> Unit,
    onRemoveHistoryEntry: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onToggleBookmark: () -> Unit,
    onTogglePinToHome: () -> Unit,
    onResetPinnedSites: () -> Unit,
    onPullCurrentPage: () -> Unit,
    onPullCustomUrl: (String) -> Unit,
    onCopyUrl: () -> Unit,
    onShareUrl: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onOpenPrivateTab: () -> Unit,
    onSetAdBlockEnabled: (Boolean) -> Unit,
    onReloadCurrentPage: () -> Unit,
    onToggleVpn: () -> Unit,
    onTogglePermanentCookieLock: () -> Unit,
    onManualSyncCookies: () -> Unit,
    onClearCookies: () -> Unit,
    onClearBrowserCache: () -> Unit,
    onSelectSearchEngine: (BrowserSearchEngine) -> Unit,
    onSetZoomPercent: (Int) -> Unit,
    onResetZoom: () -> Unit,
) {
    AnimatedVisibility(
        visible = currentWindow != null,
        enter = fadeIn() + scaleIn(
            initialScale = 0.94f,
            transformOrigin = TransformOrigin.Center,
        ),
        exit = fadeOut() + scaleOut(
            targetScale = 0.94f,
            transformOrigin = TransformOrigin.Center,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianVoid.copy(alpha = 0.80f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onCloseAllWindows() }
                .padding(horizontal = 14.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = Color(0xFF0E1420),
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(0.95.dp, GlassStrokeBright.copy(alpha = 0.24f)),
                shadowElevation = 22.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .heightIn(min = 260.dp, max = 640.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
                    .testTag("browser_feature_window_container"),
            ) {
                currentWindow?.let { win ->
                    AnimatedContent(
                        targetState = win,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "browser_feature_window_content",
                    ) { activeWin ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            BrowserFeatureWindowHeader(
                                window = activeWin,
                                canNavigateBack = canNavigateBackInWindow,
                                onBack = onBackWindow,
                                onClose = onCloseAllWindows,
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.75.dp)

                            when (activeWin) {
                                BrowserFeatureWindow.HISTORY -> HistoryWindowContent(
                                    entries = historyEntries,
                                    onOpenUrl = { url ->
                                        onOpenUrl(url)
                                        onCloseAllWindows()
                                    },
                                    onRemoveEntry = onRemoveHistoryEntry,
                                    onClearAll = onClearHistory,
                                )

                                BrowserFeatureWindow.DOWNLOADS -> DownloadsWindowContent(
                                    isHomeTab = isHomeTab,
                                    activePageUrl = activePageUrl,
                                    activePageTitle = activePageTitle,
                                    activeDetectedVideo = activeDetectedVideo,
                                    statusMessage = statusMessage,
                                    onPullCurrentPage = {
                                        onPullCurrentPage()
                                        onCloseAllWindows()
                                    },
                                    onPullCustomUrl = { custom ->
                                        onPullCustomUrl(custom)
                                        onCloseAllWindows()
                                    },
                                )

                                BrowserFeatureWindow.BOOKMARKS -> BookmarksWindowContent(
                                    bookmarks = bookmarks,
                                    isHomeTab = isHomeTab,
                                    isCurrentBookmarked = isCurrentBookmarked,
                                    onBookmarkCurrent = onToggleBookmark,
                                    onOpenUrl = { url ->
                                        onOpenUrl(url)
                                        onCloseAllWindows()
                                    },
                                    onRemoveBookmark = onRemoveBookmark,
                                )

                                BrowserFeatureWindow.ADD_PAGE_TO -> AddPageToWindowContent(
                                    isHomeTab = isHomeTab,
                                    activePageUrl = activePageUrl,
                                    activePageTitle = activePageTitle,
                                    isCurrentBookmarked = isCurrentBookmarked,
                                    isCurrentPinnedToHome = isCurrentPinnedToHome,
                                    enableEditableSpeedDials = enableEditableSpeedDials,
                                    onToggleBookmark = {
                                        onToggleBookmark()
                                        onCloseAllWindows()
                                    },
                                    onTogglePinToHome = {
                                        onTogglePinToHome()
                                        onCloseAllWindows()
                                    },
                                    onCopyUrl = {
                                        onCopyUrl()
                                        onCloseAllWindows()
                                    },
                                    onShareUrl = {
                                        onShareUrl()
                                        onCloseAllWindows()
                                    },
                                )

                                BrowserFeatureWindow.SETTINGS -> SettingsWindowContent(
                                    searchEngine = searchEngine,
                                    isDesktopMode = isDesktopMode,
                                    zoomPercent = zoomPercent,
                                    adBlockEnabled = adBlockEnabled,
                                    blockedAdsCount = blockedAdsCount,
                                    isVpnActive = isVpnActive,
                                    isPermanentCookieLock = isPermanentCookieLock,
                                    storedCookieCount = storedCookieCount,
                                    bookmarksCount = bookmarks.size,
                                    historyCount = historyEntries.size,
                                    enableEditableSpeedDials = enableEditableSpeedDials,
                                    onOpenSubWindow = onNavigateToWindow,
                                    onToggleDesktopMode = onToggleDesktopMode,
                                    onSetAdBlockEnabled = onSetAdBlockEnabled,
                                    onToggleVpn = onToggleVpn,
                                    onResetPinnedSites = onResetPinnedSites,
                                )

                                BrowserFeatureWindow.AD_BLOCKERS -> AdBlockersWindowContent(
                                    adBlockEnabled = adBlockEnabled,
                                    blockedAdsCount = blockedAdsCount,
                                    isHomeTab = isHomeTab,
                                    onSetAdBlockEnabled = onSetAdBlockEnabled,
                                    onReloadCurrentPage = {
                                        onReloadCurrentPage()
                                        onCloseAllWindows()
                                    },
                                )

                                BrowserFeatureWindow.PRIVACY -> PrivacyWindowContent(
                                    isHomeTab = isHomeTab,
                                    isPermanentCookieLock = isPermanentCookieLock,
                                    syncedDomains = syncedDomains,
                                    storedCookieCount = storedCookieCount,
                                    onTogglePermanentCookieLock = onTogglePermanentCookieLock,
                                    onManualSyncCookies = onManualSyncCookies,
                                    onOpenPrivateTab = {
                                        onOpenPrivateTab()
                                        onCloseAllWindows()
                                    },
                                    onClearBrowserCache = onClearBrowserCache,
                                    onClearCookies = onClearCookies,
                                )

                                BrowserFeatureWindow.SEARCH_ENGINE -> SearchEngineWindowContent(
                                    selected = searchEngine,
                                    onSelect = { engine ->
                                        onSelectSearchEngine(engine)
                                    },
                                )

                                BrowserFeatureWindow.TEXT_SIZE -> TextSizeWindowContent(
                                    isHomeTab = isHomeTab,
                                    zoomPercent = zoomPercent,
                                    onSetZoomPercent = onSetZoomPercent,
                                    onResetZoom = onResetZoom,
                                )

                                BrowserFeatureWindow.VPN -> VpnWindowContent(
                                    isVpnActive = isVpnActive,
                                    onToggleVpn = onToggleVpn,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserFeatureWindowHeader(
    window: BrowserFeatureWindow,
    canNavigateBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val (title, icon, accent) = when (window) {
        BrowserFeatureWindow.HISTORY -> Triple("History", Icons.Rounded.History, NeonCyan)
        BrowserFeatureWindow.DOWNLOADS -> Triple("Downloads & Capture", Icons.Rounded.ArrowDownward, NeonCyan)
        BrowserFeatureWindow.BOOKMARKS -> Triple("Saved Pages", Icons.Rounded.Bookmarks, NeonCyan)
        BrowserFeatureWindow.ADD_PAGE_TO -> Triple("Add Page To", Icons.Rounded.Add, NeonCyan)
        BrowserFeatureWindow.SETTINGS -> Triple("Browser Settings", Icons.Outlined.Settings, NeonCyan)
        BrowserFeatureWindow.AD_BLOCKERS -> Triple("Ad Blockers", Icons.Rounded.Block, NeonCyan)
        BrowserFeatureWindow.PRIVACY -> Triple("Privacy & Vault", Icons.Rounded.VerifiedUser, EmeraldPulse)
        BrowserFeatureWindow.SEARCH_ENGINE -> Triple("Search Engine", Icons.Outlined.Public, NeonCyan)
        BrowserFeatureWindow.TEXT_SIZE -> Triple("Text Size & Zoom", Icons.Rounded.ZoomIn, NeonCyan)
        BrowserFeatureWindow.VPN -> Triple("1.1.1.1 DNS VPN", Icons.Rounded.VpnLock, EmeraldPulse)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0xFF131B2B), Color(0xFF0E1420)),
                ),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canNavigateBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("browser_window_back"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = ChromeSilver,
                )
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = ChromeSilver,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("browser_window_close"),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close window",
                tint = MutedSlate,
            )
        }
    }
}

@Composable
private fun HistoryWindowContent(
    entries: List<BrowserHistoryEntry>,
    onOpenUrl: (String) -> Unit,
    onRemoveEntry: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val filtered = remember(entries, query) {
        val q = query.trim()
        if (q.isEmpty()) entries
        else entries.filter {
            it.title.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true)
        }
    }
    val formatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WindowSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search history...",
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty()) {
                Surface(
                    color = if (confirmClear) CrimsonAlert else CrimsonAlert.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(0.8.dp, CrimsonAlert.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            if (confirmClear) {
                                confirmClear = false
                                onClearAll()
                            } else {
                                confirmClear = true
                            }
                        }
                        .testTag("browser_history_clear_all"),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (confirmClear) "Confirm" else "Clear",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (confirmClear) Color.White else CrimsonAlert,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            WindowEmptyState(
                icon = Icons.Rounded.History,
                message = if (entries.isEmpty()) "No browsing history recorded yet." else "No matching history entries.",
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.url + it.visitedAtMs }) { item ->
                    Surface(
                        color = Color(0xFF151D2C),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onOpenUrl(item.url) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(NeonCyan.copy(alpha = 0.13f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = item.title.take(1).uppercase().ifBlank { "H" },
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = NeonCyan,
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = ChromeSilver,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${formatter.format(Date(item.visitedAtMs))} · ${item.url}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedSlate,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { onRemoveEntry(item.url) },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Remove history entry",
                                    tint = MutedSlate,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsWindowContent(
    isHomeTab: Boolean,
    activePageUrl: String,
    activePageTitle: String,
    activeDetectedVideo: VideoElementTelemetry?,
    statusMessage: String?,
    onPullCurrentPage: () -> Unit,
    onPullCustomUrl: (String) -> Unit,
) {
    var customUrl by remember { mutableStateOf(if (!isHomeTab) activePageUrl else "") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Active Page Video Detection & Quick Pull Card
        WindowSectionCard(title = "Active Page Video Capture") {
            if (!isHomeTab && activePageUrl.startsWith("http", ignoreCase = true)) {
                Text(
                    text = activePageTitle.ifBlank { activePageUrl },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ChromeSilver,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = activePageUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (activeDetectedVideo != null && !activeDetectedVideo.isLikelyAd) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val res = activeDetectedVideo.resolutionLabel.ifBlank { "Video" }
                    val stateTag = if (activeDetectedVideo.isAdvancing || !activeDetectedVideo.paused) {
                        "Playing now"
                    } else {
                        "Detected"
                    }
                    Surface(
                        color = EmeraldPulse.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(0.7.dp, EmeraldPulse.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = "Active stream · $res · $stateTag",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = EmeraldPulse,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onPullCurrentPage,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = ObsidianVoid,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("browser_window_download_current"),
                ) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download Highest Quality", fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    text = "Open a video page or paste a direct video URL below to extract and save in highest quality.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate,
                )
            }
        }

        // Direct Link Extractor Input Card
        WindowSectionCard(title = "Download by Direct Link") {
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                placeholder = { Text("https://...", color = DimSlate) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = GlassStrokeBright.copy(alpha = 0.25f),
                    focusedTextColor = ChromeSilver,
                    unfocusedTextColor = ChromeSilver,
                    cursorColor = NeonCyan,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("browser_window_download_url_input"),
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    val trimmed = customUrl.trim()
                    if (trimmed.isNotBlank()) onPullCustomUrl(trimmed)
                },
                enabled = customUrl.trim().isNotBlank(),
                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.65f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("browser_window_download_custom_button"),
            ) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Extract & Download Link", color = NeonCyan, fontWeight = FontWeight.SemiBold)
            }
        }

        if (!statusMessage.isNullOrBlank()) {
            Surface(
                color = NeonCyan.copy(alpha = 0.1f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.7.dp, NeonCyan.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonCyan,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun BookmarksWindowContent(
    bookmarks: List<BrowserBookmark>,
    isHomeTab: Boolean,
    isCurrentBookmarked: Boolean,
    onBookmarkCurrent: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(bookmarks, query) {
        val q = query.trim()
        if (q.isEmpty()) bookmarks
        else bookmarks.filter {
            it.title.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WindowSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search saved pages...",
                modifier = Modifier.weight(1f),
            )
            if (!isHomeTab) {
                Surface(
                    color = if (isCurrentBookmarked) PlasmaViolet.copy(alpha = 0.18f) else NeonCyan.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        0.8.dp,
                        if (isCurrentBookmarked) PlasmaViolet.copy(alpha = 0.6f) else NeonCyan.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onBookmarkCurrent() },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isCurrentBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.Add,
                            contentDescription = null,
                            tint = if (isCurrentBookmarked) PlasmaViolet else NeonCyan,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isCurrentBookmarked) "Saved" else "Save",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = ChromeSilver,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            WindowEmptyState(
                icon = Icons.Rounded.Bookmarks,
                message = if (bookmarks.isEmpty()) "No saved pages yet." else "No matching saved pages.",
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.url }) { bm ->
                    Surface(
                        color = Color(0xFF151D2C),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onOpenUrl(bm.url) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(NeonCyan.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Bookmark,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bm.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = ChromeSilver,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = bm.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedSlate,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { onRemoveBookmark(bm.url) },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Remove saved page",
                                    tint = MutedSlate,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPageToWindowContent(
    isHomeTab: Boolean,
    activePageUrl: String,
    activePageTitle: String,
    isCurrentBookmarked: Boolean,
    isCurrentPinnedToHome: Boolean,
    enableEditableSpeedDials: Boolean,
    onToggleBookmark: () -> Unit,
    onTogglePinToHome: () -> Unit,
    onCopyUrl: () -> Unit,
    onShareUrl: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isHomeTab) {
            WindowEmptyState(
                icon = Icons.Rounded.Add,
                message = "Open a website first to add it to bookmarks or pin it to your start page.",
            )
        } else {
            WindowSectionCard(title = "Current Page") {
                Text(
                    text = activePageTitle.ifBlank { activePageUrl },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ChromeSilver,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = activePageUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            WindowActionTile(
                icon = Icons.Rounded.Bookmark,
                title = if (isCurrentBookmarked) "Remove from Saved Pages" else "Save to Bookmarks",
                subtitle = if (isCurrentBookmarked) "Currently saved in your browser bookmarks" else "Keep this page in Saved Pages",
                accent = if (isCurrentBookmarked) PlasmaViolet else NeonCyan,
                onClick = onToggleBookmark,
            )

            if (enableEditableSpeedDials) {
                WindowActionTile(
                    icon = Icons.Rounded.PushPin,
                    title = if (isCurrentPinnedToHome) "Unpin from Start Page" else "Pin to Start Page",
                    subtitle = if (isCurrentPinnedToHome) "Remove shortcut from your browser home grid" else "Add shortcut with automatic site logo",
                    accent = EmeraldPulse,
                    onClick = onTogglePinToHome,
                )
            }

            WindowActionTile(
                icon = Icons.Rounded.ContentCopy,
                title = "Copy Page Link",
                subtitle = "Copy URL to system clipboard",
                accent = ChromeSilver,
                onClick = onCopyUrl,
            )

            WindowActionTile(
                icon = Icons.Rounded.Share,
                title = "Share Link",
                subtitle = "Send link via Android share sheet",
                accent = ChromeSilver,
                onClick = onShareUrl,
            )
        }
    }
}

@Composable
private fun SettingsWindowContent(
    searchEngine: BrowserSearchEngine,
    isDesktopMode: Boolean,
    zoomPercent: Int,
    adBlockEnabled: Boolean,
    blockedAdsCount: Int,
    isVpnActive: Boolean,
    isPermanentCookieLock: Boolean,
    storedCookieCount: Int,
    bookmarksCount: Int,
    historyCount: Int,
    enableEditableSpeedDials: Boolean,
    onOpenSubWindow: (BrowserFeatureWindow) -> Unit,
    onToggleDesktopMode: () -> Unit,
    onSetAdBlockEnabled: (Boolean) -> Unit,
    onToggleVpn: () -> Unit,
    onResetPinnedSites: () -> Unit,
) {
    var confirmResetHome by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WindowSectionCard(title = "Browsing & Display") {
            WindowSettingNavRow(
                icon = Icons.Outlined.Public,
                title = "Search engine",
                subtitle = searchEngine.label,
                onClick = { onOpenSubWindow(BrowserFeatureWindow.SEARCH_ENGINE) },
            )
            WindowDivider()
            WindowSettingSwitchRow(
                icon = Icons.Rounded.DesktopWindows,
                title = "Desktop site view",
                subtitle = if (isDesktopMode) "Requesting desktop pages" else "Mobile layout active",
                checked = isDesktopMode,
                onCheckedChange = { onToggleDesktopMode() },
            )
            WindowDivider()
            WindowSettingNavRow(
                icon = Icons.Rounded.ZoomIn,
                title = "Text size & page zoom",
                subtitle = "$zoomPercent% scale",
                onClick = { onOpenSubWindow(BrowserFeatureWindow.TEXT_SIZE) },
            )
        }

        WindowSectionCard(title = "Privacy & Protection") {
            WindowSettingSwitchRow(
                icon = Icons.Rounded.Block,
                title = "Ad blockers",
                subtitle = if (adBlockEnabled) "$blockedAdsCount ads/trackers blocked" else "Paused",
                checked = adBlockEnabled,
                onCheckedChange = onSetAdBlockEnabled,
                onRowClick = { onOpenSubWindow(BrowserFeatureWindow.AD_BLOCKERS) },
            )
            WindowDivider()
            WindowSettingSwitchRow(
                icon = Icons.Rounded.VpnLock,
                title = "1.1.1.1 Cloudflare DNS VPN",
                subtitle = if (isVpnActive) "Tunnel active" else "Disconnected",
                checked = isVpnActive,
                accent = EmeraldPulse,
                onCheckedChange = { onToggleVpn() },
                onRowClick = { onOpenSubWindow(BrowserFeatureWindow.VPN) },
            )
            WindowDivider()
            WindowSettingNavRow(
                icon = Icons.Rounded.VerifiedUser,
                title = "Privacy & Cookie Vault",
                subtitle = "${if (isPermanentCookieLock) "AES-256 Lock" else "Temp session"} · $storedCookieCount cookies",
                accent = EmeraldPulse,
                onClick = { onOpenSubWindow(BrowserFeatureWindow.PRIVACY) },
            )
        }

        WindowSectionCard(title = "Saved Data & Start Page") {
            WindowSettingNavRow(
                icon = Icons.Rounded.Bookmarks,
                title = "Saved pages",
                subtitle = "$bookmarksCount bookmarks",
                onClick = { onOpenSubWindow(BrowserFeatureWindow.BOOKMARKS) },
            )
            WindowDivider()
            WindowSettingNavRow(
                icon = Icons.Rounded.History,
                title = "History",
                subtitle = "$historyCount visited pages",
                onClick = { onOpenSubWindow(BrowserFeatureWindow.HISTORY) },
            )
            if (enableEditableSpeedDials) {
                WindowDivider()
                WindowSettingNavRow(
                    icon = Icons.Rounded.PushPin,
                    title = if (confirmResetHome) "Tap again to restore defaults" else "Restore default pinned sites",
                    subtitle = "Reset home shortcuts to built-in sites",
                    accent = if (confirmResetHome) AmberWarning else MutedSlate,
                    onClick = {
                        if (confirmResetHome) {
                            confirmResetHome = false
                            onResetPinnedSites()
                        } else {
                            confirmResetHome = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AdBlockersWindowContent(
    adBlockEnabled: Boolean,
    blockedAdsCount: Int,
    isHomeTab: Boolean,
    onSetAdBlockEnabled: (Boolean) -> Unit,
    onReloadCurrentPage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = if (adBlockEnabled) NeonCyan.copy(alpha = 0.1f) else Color(0xFF151D2C),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(
                0.9.dp,
                if (adBlockEnabled) NeonCyan.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.1f),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (adBlockEnabled) NeonCyan.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = if (adBlockEnabled) NeonCyan else MutedSlate,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (adBlockEnabled) "Protection Active" else "Protection Paused",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = ChromeSilver,
                    )
                    Text(
                        text = "$blockedAdsCount ads & tracker requests blocked",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (adBlockEnabled) NeonCyan else MutedSlate,
                    )
                }
                Switch(
                    checked = adBlockEnabled,
                    onCheckedChange = onSetAdBlockEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ObsidianVoid,
                        checkedTrackColor = NeonCyan,
                    ),
                )
            }
        }

        WindowSectionCard(title = "Filtered Networks & Video Ad Rules") {
            Text(
                text = "Automatically intercepts ad servers, tracking pixels, pre-roll VAST/VMAP calls, DoubleClick, GoogleAds, Taboola, Outbrain, and IMA SDK overlays before they load.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedSlate,
                lineHeight = 19.sp,
            )
        }

        if (!isHomeTab) {
            OutlinedButton(
                onClick = onReloadCurrentPage,
                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reload Page to Apply", color = NeonCyan, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PrivacyWindowContent(
    isHomeTab: Boolean,
    isPermanentCookieLock: Boolean,
    syncedDomains: List<String>,
    storedCookieCount: Int,
    onTogglePermanentCookieLock: () -> Unit,
    onManualSyncCookies: () -> Unit,
    onOpenPrivateTab: () -> Unit,
    onClearBrowserCache: () -> Unit,
    onClearCookies: () -> Unit,
) {
    var confirmClearVault by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WindowSectionCard(title = "Cookie Vault & Session Lock") {
            WindowSettingSwitchRow(
                icon = Icons.Outlined.Lock,
                title = "AES-256 Privacy Lock",
                subtitle = if (isPermanentCookieLock) "Sessions persist encrypted across restarts" else "Temporary session cookies only",
                checked = isPermanentCookieLock,
                accent = EmeraldPulse,
                onCheckedChange = { onTogglePermanentCookieLock() },
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onManualSyncCookies,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPulse,
                        contentColor = ObsidianVoid,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isHomeTab) "Sync Cookies" else "Sync This Site",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (syncedDomains.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Synced domains ($storedCookieCount cookies):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    syncedDomains.forEach { domain ->
                        Surface(
                            color = EmeraldPulse.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(0.6.dp, EmeraldPulse.copy(alpha = 0.4f)),
                        ) {
                            Text(
                                text = domain,
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldPulse,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        WindowActionTile(
            icon = Icons.Rounded.VisibilityOff,
            title = "Open Private Incognito Tab",
            subtitle = "Zero history & no-cache WebView session",
            accent = PlasmaViolet,
            onClick = onOpenPrivateTab,
        )

        WindowActionTile(
            icon = Icons.Rounded.DeleteSweep,
            title = "Clear WebView Cache & History",
            subtitle = "Remove cached page files and browsing history",
            accent = AmberWarning,
            onClick = onClearBrowserCache,
        )

        if (storedCookieCount > 0) {
            WindowActionTile(
                icon = Icons.Outlined.DeleteOutline,
                title = if (confirmClearVault) "Tap Again to Confirm Clear" else "Clear Encrypted Cookie Vault",
                subtitle = "Erase all $storedCookieCount stored session cookies",
                accent = CrimsonAlert,
                onClick = {
                    if (confirmClearVault) {
                        confirmClearVault = false
                        onClearCookies()
                    } else {
                        confirmClearVault = true
                    }
                },
            )
        }
    }
}

@Composable
private fun SearchEngineWindowContent(
    selected: BrowserSearchEngine,
    onSelect: (BrowserSearchEngine) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Choose the default search provider used when typing queries in the address bar.",
            style = MaterialTheme.typography.bodySmall,
            color = MutedSlate,
        )
        BrowserSearchEngine.entries.forEach { engine ->
            val isSelected = engine == selected
            Surface(
                color = if (isSelected) NeonCyan.copy(alpha = 0.12f) else Color(0xFF151D2C),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    width = if (isSelected) 1.2.dp else 0.75.dp,
                    color = if (isSelected) NeonCyan.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(engine) }
                    .testTag("browser_search_engine_option_${engine.name.lowercase()}"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) NeonCyan else MutedSlate,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = engine.label,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isSelected) NeonCyan else ChromeSilver,
                        )
                        Text(
                            text = engine.searchTemplate.substringAfter("://").substringBefore("/"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlate,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextSizeWindowContent(
    isHomeTab: Boolean,
    zoomPercent: Int,
    onSetZoomPercent: (Int) -> Unit,
    onResetZoom: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Live scaled text preview
        Surface(
            color = Color(0xFF141C2B),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(0.8.dp, GlassStrokeBright.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val scaleFactor = (zoomPercent / 100f).coerceIn(0.65f, 1.85f)
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Live Text Preview ($zoomPercent%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonCyan,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Webpage typography scales smoothly to match your reading preference.",
                    fontSize = (14f * scaleFactor).sp,
                    lineHeight = (20f * scaleFactor).sp,
                    color = ChromeSilver,
                )
            }
        }

        if (isHomeTab) {
            Text(
                text = "Open any webpage to apply live zoom scaling.",
                style = MaterialTheme.typography.labelSmall,
                color = AmberWarning,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(
                onClick = { onSetZoomPercent((zoomPercent - 20).coerceIn(50, 250)) },
                enabled = !isHomeTab,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
            ) {
                Icon(Icons.Rounded.ZoomOut, contentDescription = "Decrease text size", tint = ChromeSilver)
            }

            Slider(
                value = zoomPercent.toFloat(),
                onValueChange = { onSetZoomPercent(it.roundToInt()) },
                valueRange = 50f..250f,
                steps = 9,
                enabled = !isHomeTab,
                colors = SliderDefaults.colors(
                    thumbColor = NeonCyan,
                    activeTrackColor = NeonCyan,
                    inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                ),
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = { onSetZoomPercent((zoomPercent + 20).coerceIn(50, 250)) },
                enabled = !isHomeTab,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
            ) {
                Icon(Icons.Rounded.ZoomIn, contentDescription = "Increase text size", tint = NeonCyan)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(75, 100, 125, 150, 200).forEach { preset ->
                val active = zoomPercent == preset
                Surface(
                    color = if (active) NeonCyan.copy(alpha = 0.18f) else Color(0xFF161F30),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(
                        0.8.dp,
                        if (active) NeonCyan else GlassStrokeBright.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = !isHomeTab) { onSetZoomPercent(preset) },
                ) {
                    Text(
                        text = "$preset%",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (active) NeonCyan else ChromeSilver,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onResetZoom,
            enabled = !isHomeTab && zoomPercent != 100,
            border = BorderStroke(0.9.dp, GlassStrokeBright.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset to 100%", color = ChromeSilver)
        }
    }
}

@Composable
private fun VpnWindowContent(
    isVpnActive: Boolean,
    onToggleVpn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            color = if (isVpnActive) EmeraldPulse.copy(alpha = 0.12f) else Color(0xFF151D2C),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(
                0.9.dp,
                if (isVpnActive) EmeraldPulse.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.1f),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(
                            if (isVpnActive) EmeraldPulse.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isVpnActive) Icons.Rounded.CheckCircle else Icons.Rounded.VpnLock,
                        contentDescription = null,
                        tint = if (isVpnActive) EmeraldPulse else ChromeSilver,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isVpnActive) "1.1.1.1 DNS Connected" else "1.1.1.1 DNS Disconnected",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = ChromeSilver,
                    )
                    Text(
                        text = if (isVpnActive) {
                            "Encrypted Cloudflare 1.1.1.1 DNS tunnel protecting browser lookups"
                        } else {
                            "Enable encrypted 1.1.1.1 DNS resolution inside this device"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isVpnActive) EmeraldPulse else MutedSlate,
                    )
                }
            }
        }

        Button(
            onClick = onToggleVpn,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isVpnActive) CrimsonAlert else EmeraldPulse,
                contentColor = if (isVpnActive) Color.White else ObsidianVoid,
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("browser_window_vpn_toggle"),
        ) {
            Icon(Icons.Rounded.VpnLock, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isVpnActive) "Disconnect 1.1.1.1 VPN" else "Connect 1.1.1.1 VPN",
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun WindowSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xFF151D2C),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.8.dp, GlassStrokeBright.copy(alpha = 0.2f)),
        modifier = modifier.height(40.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MutedSlate,
                modifier = Modifier.size(17.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(NeonCyan),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ChromeSilver),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodySmall, color = DimSlate)
                    }
                    inner()
                },
            )
            if (value.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Clear search",
                    tint = MutedSlate,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onValueChange("") },
                )
            }
        }
    }
}

@Composable
private fun WindowSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = Color(0xFF141B29),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = NeonCyan,
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun WindowActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Surface(
        color = Color(0xFF151D2C),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ChromeSilver,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MutedSlate,
            )
        }
    }
}

@Composable
private fun WindowSettingNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color = NeonCyan,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = ChromeSilver,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MutedSlate,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MutedSlate,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun WindowSettingSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color = NeonCyan,
    onCheckedChange: (Boolean) -> Unit,
    onRowClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                if (onRowClick != null) onRowClick() else onCheckedChange(!checked)
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (checked) accent else ChromeSilver, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = ChromeSilver,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MutedSlate,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ObsidianVoid,
                checkedTrackColor = accent,
            ),
        )
    }
}

@Composable
private fun WindowDivider() {
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.06f),
        thickness = 0.7.dp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun WindowEmptyState(
    icon: ImageVector,
    message: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MutedSlate,
            modifier = Modifier.size(36.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MutedSlate,
        )
    }
}
