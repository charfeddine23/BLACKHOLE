package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BrowserBookmark(
    val url: String,
    val title: String,
    val createdAtMs: Long = System.currentTimeMillis(),
)

@Serializable
data class BrowserHistoryEntry(
    val url: String,
    val title: String,
    val visitedAtMs: Long = System.currentTimeMillis(),
)

@Serializable
data class BrowserPinnedSite(
    val id: String,
    val label: String,
    val url: String,
    val glyph: String,
    val colorStartHex: Long,
    val colorEndHex: Long,
    val isPlayIcon: Boolean = false,
)

enum class BrowserSearchEngine(
    val label: String,
    val searchTemplate: String,
) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BRAVE("Brave", "https://search.brave.com/search?q="),
    BING("Bing", "https://www.bing.com/search?q="),
}

class BrowserPreferencesStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    private val _bookmarks = MutableStateFlow(loadBookmarks())
    val bookmarks: StateFlow<List<BrowserBookmark>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<BrowserHistoryEntry>> = _history.asStateFlow()

    private val _adBlockEnabled = MutableStateFlow(prefs.getBoolean(KEY_AD_BLOCK_ENABLED, true))
    val adBlockEnabled: StateFlow<Boolean> = _adBlockEnabled.asStateFlow()

    private val _searchEngine = MutableStateFlow(loadSearchEngine())
    val searchEngine: StateFlow<BrowserSearchEngine> = _searchEngine.asStateFlow()

    private val _pinnedSites = MutableStateFlow(loadPinnedSites())
    val pinnedSites: StateFlow<List<BrowserPinnedSite>> = _pinnedSites.asStateFlow()

    fun isBookmarked(url: String): Boolean {
        val normalized = url.trimEnd('/')
        return _bookmarks.value.any { it.url.trimEnd('/').equals(normalized, ignoreCase = true) }
    }

    fun toggleBookmark(url: String, title: String): Boolean {
        if (!url.startsWith("http", ignoreCase = true)) return false
        val normalized = url.trimEnd('/')
        val current = _bookmarks.value
        val existingIdx = current.indexOfFirst { it.url.trimEnd('/').equals(normalized, ignoreCase = true) }
        val added: Boolean
        val updated = if (existingIdx >= 0) {
            added = false
            current.toMutableList().apply { removeAt(existingIdx) }
        } else {
            added = true
            listOf(BrowserBookmark(url = url, title = title.ifBlank { url })) + current
        }
        _bookmarks.value = updated
        prefs.edit().putString(KEY_BOOKMARKS_JSON, json.encodeToString(updated)).apply()
        return added
    }

    fun removeBookmark(url: String) {
        val updated = _bookmarks.value.filterNot { it.url.equals(url, ignoreCase = true) }
        _bookmarks.value = updated
        prefs.edit().putString(KEY_BOOKMARKS_JSON, json.encodeToString(updated)).apply()
    }

    fun recordHistory(url: String, title: String) {
        if (!url.startsWith("http", ignoreCase = true)) return
        val filtered = _history.value.filterNot { it.url.equals(url, ignoreCase = true) }
        val updated = (listOf(BrowserHistoryEntry(url = url, title = title.ifBlank { url })) + filtered)
            .take(MAX_HISTORY_ITEMS)
        _history.value = updated
        prefs.edit().putString(KEY_HISTORY_JSON, json.encodeToString(updated)).apply()
    }

    fun clearHistory() {
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY_JSON).apply()
    }

    fun setAdBlockEnabled(enabled: Boolean) {
        _adBlockEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AD_BLOCK_ENABLED, enabled).apply()
    }

    fun cycleSearchEngine(): BrowserSearchEngine {
        val entries = BrowserSearchEngine.entries
        val next = entries[(entries.indexOf(_searchEngine.value) + 1) % entries.size]
        _searchEngine.value = next
        prefs.edit().putString(KEY_SEARCH_ENGINE, next.name).apply()
        return next
    }

    fun addPinnedSite(label: String, rawUrl: String): BrowserPinnedSite? {
        val normalizedUrl = normalizePinnedUrl(rawUrl) ?: return null
        val cleanLabel = label.trim().ifBlank { deriveShortLabelFromUrl(normalizedUrl) }
        val palette = PINNED_ACCENT_PALETTES[
            kotlin.math.abs((cleanLabel + normalizedUrl).hashCode()) % PINNED_ACCENT_PALETTES.size
        ]
        val entry = BrowserPinnedSite(
            id = "site_${System.nanoTime()}",
            label = cleanLabel.take(24),
            url = normalizedUrl,
            glyph = deriveGlyph(cleanLabel),
            colorStartHex = palette.first,
            colorEndHex = palette.second,
            isPlayIcon = false,
        )
        val updated = _pinnedSites.value + entry
        savePinnedSites(updated)
        return entry
    }

    fun updatePinnedSite(id: String, label: String, rawUrl: String): Boolean {
        val normalizedUrl = normalizePinnedUrl(rawUrl) ?: return false
        val cleanLabel = label.trim().ifBlank { deriveShortLabelFromUrl(normalizedUrl) }
        val current = _pinnedSites.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return false
        val existing = current[index]
        val updated = current.toMutableList().apply {
            set(
                index,
                existing.copy(
                    label = cleanLabel.take(24),
                    url = normalizedUrl,
                    glyph = if (existing.isPlayIcon && cleanLabel.equals(existing.label, ignoreCase = true)) {
                        existing.glyph
                    } else {
                        deriveGlyph(cleanLabel)
                    },
                    isPlayIcon = existing.isPlayIcon && cleanLabel.equals(existing.label, ignoreCase = true),
                ),
            )
        }
        savePinnedSites(updated)
        return true
    }

    fun deletePinnedSite(id: String): Boolean {
        val current = _pinnedSites.value
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return false
        savePinnedSites(updated)
        return true
    }

    fun resetPinnedSitesToDefault() {
        savePinnedSites(DEFAULT_PINNED_SITES)
    }

    private fun savePinnedSites(list: List<BrowserPinnedSite>) {
        _pinnedSites.value = list
        prefs.edit().putString(KEY_PINNED_SITES_JSON, json.encodeToString(list)).apply()
    }

    private fun loadPinnedSites(): List<BrowserPinnedSite> {
        if (!prefs.contains(KEY_PINNED_SITES_JSON)) {
            return DEFAULT_PINNED_SITES
        }
        val raw = prefs.getString(KEY_PINNED_SITES_JSON, null) ?: return DEFAULT_PINNED_SITES
        return runCatching { json.decodeFromString<List<BrowserPinnedSite>>(raw) }
            .getOrDefault(DEFAULT_PINNED_SITES)
    }

    private fun loadBookmarks(): List<BrowserBookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS_JSON, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<BrowserBookmark>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadHistory(): List<BrowserHistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<BrowserHistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadSearchEngine(): BrowserSearchEngine {
        val raw = prefs.getString(KEY_SEARCH_ENGINE, null)
        return entriesOrDefault(raw)
    }

    private fun entriesOrDefault(raw: String?): BrowserSearchEngine {
        return BrowserSearchEngine.entries.firstOrNull { it.name == raw } ?: BrowserSearchEngine.GOOGLE
    }

    private fun normalizePinnedUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme.takeIf { it.contains(".") }
    }

    private fun deriveShortLabelFromUrl(url: String): String {
        return url.substringAfter("://")
            .substringBefore("/")
            .removePrefix("www.")
            .removePrefix("m.")
            .substringBefore(".")
            .replaceFirstChar { it.uppercaseChar() }
            .ifBlank { "Site" }
    }

    private fun deriveGlyph(label: String): String {
        val clean = label.trim()
        if (clean.isEmpty()) return "W"
        val words = clean.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return if (words.size >= 2) {
            "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
        } else {
            clean.take(2).replaceFirstChar { it.uppercaseChar() }
        }
    }

    companion object {
        private const val PREFS_NAME = "blackhole_browser_prefs"
        private const val KEY_BOOKMARKS_JSON = "KEY_BOOKMARKS_JSON"
        private const val KEY_HISTORY_JSON = "KEY_HISTORY_JSON"
        private const val KEY_AD_BLOCK_ENABLED = "KEY_AD_BLOCK_ENABLED"
        private const val KEY_SEARCH_ENGINE = "KEY_SEARCH_ENGINE"
        private const val KEY_PINNED_SITES_JSON = "KEY_PINNED_SITES_JSON"
        private const val MAX_HISTORY_ITEMS = 150

        private val PINNED_ACCENT_PALETTES = listOf(
            0xFF0EA5E9L to 0xFF0369A1L,
            0xFF8B5CF6L to 0xFF6D28D9L,
            0xFF10B981L to 0xFF047857L,
            0xFFF43F5EL to 0xFFBE123CL,
            0xFFF59E0BL to 0xFFD97706L,
            0xFF3B82F6L to 0xFF1D4ED8L,
        )

        val DEFAULT_PINNED_SITES: List<BrowserPinnedSite> = listOf(
            BrowserPinnedSite("default_fb", "Facebook", "https://m.facebook.com/", "f", 0xFF1877F2L, 0xFF0E5AD6L),
            BrowserPinnedSite("default_x", "X", "https://x.com/", "𝕏", 0xFF0C0D10L, 0xFF040507L),
            BrowserPinnedSite("default_yt", "YouTube", "https://m.youtube.com/", "▶", 0xFFFF0033L, 0xFFD50026L, isPlayIcon = true),
            BrowserPinnedSite("default_ig", "Instagram", "https://www.instagram.com/", "IG", 0xFFF58529L, 0xFF8134AFL),
            BrowserPinnedSite("default_tt", "TikTok", "https://www.tiktok.com/", "♪", 0xFF14161AL, 0xFF08090BL),
            BrowserPinnedSite("default_yt_login", "YT Sign-In", "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F", "G", 0xFF4285F4L, 0xFF2B6DE0L),
            BrowserPinnedSite("default_ig_login", "IG Login", "https://www.instagram.com/accounts/login/", "IG+", 0xFFC13584L, 0xFF833AB4L),
            BrowserPinnedSite("default_fb_login", "FB Login", "https://m.facebook.com/login/", "FB+", 0xFF2563EBL, 0xFF1D4ED8L),
            BrowserPinnedSite("default_vimeo", "Vimeo", "https://vimeo.com/", "v", 0xFF1AB7EAL, 0xFF0F94C2L),
            BrowserPinnedSite("default_reddit", "Reddit", "https://www.reddit.com/", "R", 0xFFFF4500L, 0xFFD93800L),
            BrowserPinnedSite("default_ig_reels", "IG Reels", "https://www.instagram.com/reels/", "Rls", 0xFFE4405FL, 0xFFC92A4CL),
            BrowserPinnedSite("default_fb_watch", "FB Watch", "https://m.facebook.com/watch/", "W", 0xFF3B5998L, 0xFF283E6EL),
            BrowserPinnedSite("default_yt_shorts", "YT Shorts", "https://m.youtube.com/shorts/", "S", 0xFFFF1744L, 0xFFD50000L),
            BrowserPinnedSite("default_google", "Google", "https://www.google.com/", "G", 0xFF1F2736L, 0xFF111722L),
            BrowserPinnedSite("default_twitch", "Twitch", "https://m.twitch.tv/", "Tw", 0xFF9146FFL, 0xFF6B28D9L),
        )

        @Volatile
        private var INSTANCE: BrowserPreferencesStore? = null

        fun getInstance(context: Context): BrowserPreferencesStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BrowserPreferencesStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
