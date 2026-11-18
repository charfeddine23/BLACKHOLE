package com.example.engine.cookies

import android.content.Context
import android.webkit.CookieManager
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
import com.pulse.blackhole.data.vault.SessionVaultManager
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
    val sessionVault = SessionVaultManager(appContext)
    private val legacyPlaintextFile = File(appContext.filesDir, "blackhole_cookies_vault.json")
    private val cookieCache = ConcurrentHashMap<String, MutableList<StoredCookieEntry>>()

    private val _syncedDomains = MutableStateFlow<List<String>>(emptyList())
    val syncedDomains: StateFlow<List<String>> = _syncedDomains.asStateFlow()

    init {
        loadFromDiskSync()
    }

    fun hasActiveSession(): Boolean {
        return sessionVault.hasActiveSession() || cookieCache.values.any { it.isNotEmpty() }
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

        val candidateUrls = linkedSetOf(url)
        val lower = url.lowercase()
        when {
            lower.contains("instagram") || lower.contains("instagr.am") -> {
                candidateUrls.add("https://www.instagram.com/")
                candidateUrls.add("https://i.instagram.com/")
                candidateUrls.add("https://instagram.com/")
            }
            lower.contains("facebook") || lower.contains("fb.") -> {
                candidateUrls.add("https://www.facebook.com/")
                candidateUrls.add("https://m.facebook.com/")
                candidateUrls.add("https://facebook.com/")
            }
            lower.contains("youtube") || lower.contains("google") || lower.contains("youtu.be") -> {
                candidateUrls.add("https://www.youtube.com/")
                candidateUrls.add("https://youtube.com/")
                candidateUrls.add("https://accounts.google.com/")
            }
            lower.contains("tiktok") -> {
                candidateUrls.add("https://www.tiktok.com/")
                candidateUrls.add("https://m.tiktok.com/")
            }
            lower.contains("twitter") || lower.contains("x.com") -> {
                candidateUrls.add("https://x.com/")
                candidateUrls.add("https://twitter.com/")
            }
        }

        val collected = mutableListOf<StoredCookieEntry>()
        val seenKeys = mutableSetOf<String>()

        candidateUrls.forEach { target ->
            val rawHeader = cookieManager.getCookie(target).orEmpty()
            if (rawHeader.isBlank()) return@forEach
            val host = runCatching { java.net.URI(target).host.orEmpty() }.getOrDefault("")
            if (host.isBlank()) return@forEach
            val normalizedDomain = normalizeRootDomain(host)

            rawHeader.split(";").forEach { segment ->
                val idx = segment.indexOf('=')
                if (idx <= 0) return@forEach
                val name = segment.substring(0, idx).trim()
                val value = segment.substring(idx + 1).trim()
                if (name.isBlank()) return@forEach
                val dedupKey = "$normalizedDomain:$name"
                if (seenKeys.add(dedupKey)) {
                    collected.add(
                        StoredCookieEntry(
                            domain = normalizedDomain,
                            includeSubdomains = true,
                            path = "/",
                            secure = target.startsWith("https://", ignoreCase = true),
                            expiresAtMs = 0L,
                            name = name,
                            value = value,
                        ),
                    )
                }
            }
        }

        if (collected.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    mergeEntriesSync(collected)
                    persistToDisk()
                }
            }
        }
        collected.size
    }

    private fun normalizeRootDomain(host: String): String {
        val clean = host.lowercase().trimStart('.')
        return when {
            clean == "instagram.com" || clean.endsWith(".instagram.com") -> "instagram.com"
            clean == "facebook.com" || clean.endsWith(".facebook.com") -> "facebook.com"
            clean == "youtube.com" || clean.endsWith(".youtube.com") -> "youtube.com"
            clean == "tiktok.com" || clean.endsWith(".tiktok.com") -> "tiktok.com"
            clean == "x.com" || clean.endsWith(".x.com") -> "x.com"
            clean == "twitter.com" || clean.endsWith(".twitter.com") -> "twitter.com"
            else -> clean.removePrefix("www.").removePrefix("m.")
        }
    }

    suspend fun clearAllCookies() = withContext(Dispatchers.IO) {
        mutex.withLock {
            cookieCache.clear()
            sessionVault.clearVault()
            if (legacyPlaintextFile.exists()) legacyPlaintextFile.delete()
            _syncedDomains.value = emptyList()
        }
        withContext(Dispatchers.Main) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
    }

    fun buildExtractionCommand(
        targetUrl: String,
        vault: SessionVaultManager = sessionVault,
    ): List<String> {
        val platform = PlatformBadge.fromUrl(targetUrl)
        val command = mutableListOf(
            "yt-dlp",
            "--user-agent",
            ClientSpoofer.nextRotatedUserAgent(targetUrl),
            "--no-check-certificate",
            "--geo-bypass",
        )

        if (vault.hasActiveSession()) {
            val cookieFile = vault.writeTempNetscapeCookiesFile(appContext.cacheDir)
            if (cookieFile != null && cookieFile.exists()) {
                command.add("--cookies")
                command.add(cookieFile.absolutePath)
            }
        }

        when (platform) {
            PlatformBadge.YOUTUBE -> {
                command.add("--extractor-args")
                command.add("youtube:player_client=ios,web")
                command.add("-f")
                command.add("bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4] / bv*+ba/b")
            }
            PlatformBadge.TIKTOK -> {
                command.add("--referer")
                command.add("https://www.tiktok.com/")
                command.add("-f")
                command.add("best")
            }
            else -> {
                command.add("--referer")
                command.add(ClientSpoofer.refererForUrl(targetUrl))
                command.add("-f")
                command.add("best")
            }
        }

        command.add(targetUrl)
        return command
    }

    fun getTikTokExtractionCommand(targetUrl: String): List<String> {
        return buildExtractionCommand(targetUrl, sessionVault)
    }

    private fun writeCookiesToTempFile(cookiesContent: String): String {
        val tempFile = File.createTempFile("session_cookies", ".txt", appContext.cacheDir)
        tempFile.writeText(cookiesContent)
        return tempFile.absolutePath
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

    fun getCookieValue(url: String, cookieName: String): String? {
        val httpUrl = runCatching {
            HttpUrl.Builder()
                .scheme(if (url.startsWith("https://", ignoreCase = true)) "https" else "http")
                .host(java.net.URI(url).host ?: return null)
                .build()
        }.getOrNull() ?: return null

        return okHttpCookieJar.loadForRequest(httpUrl)
            .firstOrNull { it.name.equals(cookieName, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
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
        runCatching {
            val encryptedRaw = sessionVault.getCookies()
            if (!encryptedRaw.isNullOrBlank()) {
                val parsed = parseCookiesText(encryptedRaw)
                if (parsed.isNotEmpty()) {
                    mergeEntriesSync(parsed)
                    return
                }
            }

            // Migrate legacy unencrypted JSON file into EncryptedSharedPreferences if present
            if (legacyPlaintextFile.exists()) {
                val raw = legacyPlaintextFile.readText()
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
                if (loaded.isNotEmpty()) {
                    mergeEntriesSync(loaded)
                    persistToDisk()
                }
                legacyPlaintextFile.delete()
            }
        }
    }

    private fun persistToDisk() {
        runCatching {
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            var written = 0
            cookieCache.values.flatten().forEach { entry ->
                if (!entry.isExpired()) {
                    val domainField = if (entry.includeSubdomains && !entry.domain.startsWith(".")) {
                        ".${entry.domain}"
                    } else {
                        entry.domain
                    }
                    val prefix = if (entry.httpOnly) "#HttpOnly_" else ""
                    val includeSubField = if (entry.includeSubdomains) "TRUE" else "FALSE"
                    val secureField = if (entry.secure) "TRUE" else "FALSE"
                    val expSeconds = if (entry.expiresAtMs > 0L) entry.expiresAtMs / 1000L else 0L
                    sb.append(prefix)
                        .append(domainField).append('\t')
                        .append(includeSubField).append('\t')
                        .append(entry.path.ifBlank { "/" }).append('\t')
                        .append(secureField).append('\t')
                        .append(expSeconds).append('\t')
                        .append(entry.name).append('\t')
                        .append(entry.value).append('\n')
                    written++
                }
            }
            if (written > 0) {
                sessionVault.saveCookies(sb.toString())
            } else {
                sessionVault.clearVault()
            }
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
