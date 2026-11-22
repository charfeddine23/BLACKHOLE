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

    companion object {
        private const val PREFS_NAME = "blackhole_browser_prefs"
        private const val KEY_BOOKMARKS_JSON = "KEY_BOOKMARKS_JSON"
        private const val KEY_HISTORY_JSON = "KEY_HISTORY_JSON"
        private const val KEY_AD_BLOCK_ENABLED = "KEY_AD_BLOCK_ENABLED"
        private const val KEY_SEARCH_ENGINE = "KEY_SEARCH_ENGINE"
        private const val MAX_HISTORY_ITEMS = 150

        @Volatile
        private var INSTANCE: BrowserPreferencesStore? = null

        fun getInstance(context: Context): BrowserPreferencesStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BrowserPreferencesStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
