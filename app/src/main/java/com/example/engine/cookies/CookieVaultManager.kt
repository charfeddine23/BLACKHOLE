package com.example.engine.cookies

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class StoredCookieEntry(
    val domain: String,
    val includeSubdomains: Boolean,
    val path: String,
    val secure: Boolean,
    val expiresAtMs: Long,
    val name: String,
    val value: String,
    val httpOnly: Boolean = false,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        return expiresAtMs in 1L until nowMs
    }

    fun matches(url: HttpUrl): Boolean {
        if (isExpired()) return false
        if (secure && !url.isHttps) return false
        val host = url.host.lowercase()
        val targetDomain = domain.lowercase().trimStart('.')
        val domainMatches = if (includeSubdomains) {
            host == targetDomain || host.endsWith(".$targetDomain")
        } else {
            host == targetDomain
        }
        if (!domainMatches) return false
        val encodedPath = url.encodedPath
        return encodedPath == path ||
            encodedPath.startsWith(if (path.endsWith("/")) path else "$path/") ||
            path == "/"
    }

    fun toOkHttpCookie(): Cookie? {
        return runCatching {
            val builder = Cookie.Builder()
                .name(name)
                .value(value)
                .path(path.ifBlank { "/" })
                .expiresAt(if (expiresAtMs > 0L) expiresAtMs else Long.MAX_VALUE)
            if (includeSubdomains) {
                builder.domain(domain.trimStart('.'))
            } else {
                builder.hostOnlyDomain(domain.trimStart('.'))
            }
            if (secure) builder.secure()
            if (httpOnly) builder.httpOnly()
            builder.build()
        }.getOrNull()
    }
}

class CookieVaultManager private constructor(private val appContext: Context) {

    private val mutex = Mutex()
    private val storeFile = File(appContext.filesDir, "blackhole_cookies_vault.json")
    private val cookieCache = ConcurrentHashMap<String, MutableList<StoredCookieEntry>>()

    private val _syncedDomains = MutableStateFlow<List<String>>(emptyList())
    val syncedDomains: StateFlow<List<String>> = _syncedDomains.asStateFlow()

    init {
        loadFromDiskSync()
    }

    val okHttpCookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val entries = cookies.map { c ->
                StoredCookieEntry(
                    domain = c.domain,
                    includeSubdomains = !c.hostOnly,
                    path = c.path,
                    secure = c.secure,
                    expiresAtMs = if (c.persistent) c.expiresAt else 0L,
                    name = c.name,
                    value = c.value,
                    httpOnly = c.httpOnly,
                )
            }
            mergeEntriesSync(entries)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val matched = mutableListOf<Cookie>()
            val seenNames = mutableSetOf<String>()

            // 1. Vault cookies (imported cookies.txt + synced WebView cookies)
            cookieCache.values.flatten()
                .filter { it.matches(url) }
                .forEach { entry ->
                    entry.toOkHttpCookie()?.let { okCookie ->
                        if (seenNames.add("${okCookie.name}@${okCookie.domain}")) {
                            matched.add(okCookie)
                        }
                    }
                }

            // 2. Live WebView CookieManager fallback for any domain already logged in
            runCatching {
                val rawHeader = CookieManager.getInstance().getCookie(url.toString())
                if (!rawHeader.isNullOrBlank()) {
                    rawHeader.split(";").forEach { pair ->
                        val idx = pair.indexOf('=')
                        if (idx > 0) {
                            val name = pair.substring(0, idx).trim()
                            val value = pair.substring(idx + 1).trim()
                            if (name.isNotBlank() && seenNames.add("$name@${url.host}")) {
                                Cookie.Builder()
                                    .hostOnlyDomain(url.host)
                                    .path("/")
                                    .name(name)
                                    .value(value)
                                    .build()
                                    .let(matched::add)
                            }
                        }
                    }
                }
            }
            return matched
        }
    }

    suspend fun importNetscapeCookiesTxt(rawContent: String): Int = withContext(Dispatchers.IO) {
        val parsed = parseCookiesText(rawContent)
        if (parsed.isEmpty()) return@withContext 0
        mutex.withLock {
            mergeEntriesSync(parsed)
            persistToDisk()
        }
        parsed.size
    }

    suspend fun syncFromWebView(url: String): Int = withContext(Dispatchers.Main) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val rawHeader = cookieManager.getCookie(url).orEmpty()
        if (rawHeader.isBlank()) return@withContext 0

        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        if (host.isBlank()) return@withContext 0

        val entries = rawHeader.split(";").mapNotNull { segment ->
            val idx = segment.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val name = segment.substring(0, idx).trim()
            val value = segment.substring(idx + 1).trim()
            if (name.isBlank()) return@mapNotNull null
            StoredCookieEntry(
                domain = host.removePrefix("www."),
                includeSubdomains = true,
                path = "/",
                secure = url.startsWith("https://", ignoreCase = true),
                expiresAtMs = 0L,
                name = name,
                value = value,
            )
        }

        if (entries.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    mergeEntriesSync(entries)
                    persistToDisk()
                }
            }
        }
        entries.size
    }

    suspend fun clearAllCookies() = withContext(Dispatchers.IO) {
        mutex.withLock {
            cookieCache.clear()
            if (storeFile.exists()) storeFile.delete()
            _syncedDomains.value = emptyList()
        }
        withContext(Dispatchers.Main) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
    }

    fun getCookieHeaderForUrl(url: String): String {
        val httpUrl = runCatching {
            HttpUrl.Builder()
                .scheme(if (url.startsWith("https://", ignoreCase = true)) "https" else "http")
                .host(java.net.URI(url).host ?: return "")
                .build()
        }.getOrNull() ?: return ""

        val cookies = okHttpCookieJar.loadForRequest(httpUrl)
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun parseCookiesText(content: String): List<StoredCookieEntry> {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return emptyList()

        // JSON array export fallback (e.g., EditThisCookie / Cookie-Editor)
        if (trimmed.startsWith("[")) {
            return runCatching {
                val arr = JSONArray(trimmed)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val domain = obj.optString("domain").trim()
                        val name = obj.optString("name").trim()
                        val value = obj.optString("value")
                        if (domain.isBlank() || name.isBlank()) continue
                        val exp = obj.optDouble("expirationDate", 0.0).toLong() * 1000L
                        add(
                            StoredCookieEntry(
                                domain = domain.trimStart('.'),
                                includeSubdomains = domain.startsWith("."),
                                path = obj.optString("path", "/").ifBlank { "/" },
                                secure = obj.optBoolean("secure", false),
                                expiresAtMs = exp,
                                name = name,
                                value = value,
                                httpOnly = obj.optBoolean("httpOnly", false),
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

        // Standard Netscape cookies.txt
        val entries = mutableListOf<StoredCookieEntry>()
        trimmed.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach
            val isHttpOnly = line.startsWith("#HttpOnly_")
            if (line.startsWith("#") && !isHttpOnly) return@forEach

            val cleanLine = if (isHttpOnly) line.removePrefix("#HttpOnly_") else line
            val parts = cleanLine.split('\t')
            if (parts.size >= 7) {
                val domain = parts[0].trim()
                val includeSubdomains = parts[1].equals("TRUE", ignoreCase = true)
                val path = parts[2].trim().ifBlank { "/" }
                val secure = parts[3].equals("TRUE", ignoreCase = true)
                val expSeconds = parts[4].trim().toLongOrNull() ?: 0L
                val name = parts[5].trim()
                val value = parts.subList(6, parts.size).joinToString("\t")
                if (domain.isNotBlank() && name.isNotBlank()) {
                    entries.add(
                        StoredCookieEntry(
                            domain = domain.trimStart('.'),
                            includeSubdomains = includeSubdomains || domain.startsWith("."),
                            path = path,
                            secure = secure,
                            expiresAtMs = if (expSeconds > 0L) expSeconds * 1000L else 0L,
                            name = name,
                            value = value,
                            httpOnly = isHttpOnly,
                        ),
                    )
                }
            }
        }
        return entries
    }

    private fun mergeEntriesSync(entries: List<StoredCookieEntry>) {
        entries.forEach { entry ->
            val key = entry.domain.lowercase().trimStart('.')
            val list = cookieCache.getOrPut(key) { mutableListOf() }
            synchronized(list) {
                list.removeAll { it.name == entry.name && it.path == entry.path }
                if (!entry.isExpired()) {
                    list.add(entry)
                }
            }
        }
        _syncedDomains.value = cookieCache.keys.sorted()
    }

    private fun loadFromDiskSync() {
        if (!storeFile.exists()) return
        runCatching {
            val raw = storeFile.readText()
            val arr = JSONArray(raw)
            val loaded = mutableListOf<StoredCookieEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                loaded.add(
                    StoredCookieEntry(
                        domain = obj.optString("domain"),
                        includeSubdomains = obj.optBoolean("includeSubdomains", true),
                        path = obj.optString("path", "/"),
                        secure = obj.optBoolean("secure", false),
                        expiresAtMs = obj.optLong("expiresAtMs", 0L),
                        name = obj.optString("name"),
                        value = obj.optString("value"),
                        httpOnly = obj.optBoolean("httpOnly", false),
                    ),
                )
            }
            mergeEntriesSync(loaded)
        }
    }

    private fun persistToDisk() {
        runCatching {
            val arr = JSONArray()
            cookieCache.values.flatten().forEach { entry ->
                if (!entry.isExpired()) {
                    arr.put(
                        JSONObject().apply {
                            put("domain", entry.domain)
                            put("includeSubdomains", entry.includeSubdomains)
                            put("path", entry.path)
                            put("secure", entry.secure)
                            put("expiresAtMs", entry.expiresAtMs)
                            put("name", entry.name)
                            put("value", entry.value)
                            put("httpOnly", entry.httpOnly)
                        },
                    )
                }
            }
            storeFile.writeText(arr.toString())
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: CookieVaultManager? = null

        fun getInstance(context: Context): CookieVaultManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CookieVaultManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
