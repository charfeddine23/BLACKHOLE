package com.example.engine.cookies

import android.content.Context
import android.webkit.CookieManager
import com.example.engine.model.PlatformBadge
import com.example.engine.network.ClientSpoofer
import com.pulse.blackhole.data.vault.SessionVaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    fun isExpired(
        nowMs: Long = System.currentTimeMillis(),
        respectExpiry: Boolean = true,
    ): Boolean {
        if (!respectExpiry) return false
        return expiresAtMs in 1L until nowMs
    }

    fun matches(url: HttpUrl, respectExpiry: Boolean = true): Boolean {
        if (isExpired(respectExpiry = respectExpiry)) return false
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

    fun toOkHttpCookie(keepForever: Boolean = true): Cookie? {
        return runCatching {
            val nowMs = System.currentTimeMillis()
            val effectiveExpiry = when {
                keepForever && (expiresAtMs <= nowMs) -> nowMs + TEN_YEARS_MS
                expiresAtMs > 0L -> expiresAtMs
                else -> nowMs + TEN_YEARS_MS
            }
            val builder = Cookie.Builder()
                .name(name)
                .value(value)
                .path(path.ifBlank { "/" })
                .expiresAt(effectiveExpiry)
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

    companion object {
        const val TEN_YEARS_MS = 315_360_000_000L
    }
}

class CookieVaultManager private constructor(private val appContext: Context) {

    private val mutex = Mutex()
    private val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debouncedPersistJob: Job? = null

    val sessionVault = SessionVaultManager(appContext)
    private val legacyPlaintextFile = File(appContext.filesDir, "blackhole_cookies_vault.json")
    private val cookieCache = ConcurrentHashMap<String, MutableList<StoredCookieEntry>>()

    private val _syncedDomains = MutableStateFlow<List<String>>(emptyList())
    val syncedDomains: StateFlow<List<String>> = _syncedDomains.asStateFlow()

    private val _storedCookieCount = MutableStateFlow(0)
    val storedCookieCount: StateFlow<Int> = _storedCookieCount.asStateFlow()

    private val _keepForeverEnabled = MutableStateFlow(sessionVault.isPermanentLockEnabled())
    val keepForeverEnabled: StateFlow<Boolean> = _keepForeverEnabled.asStateFlow()

    init {
        loadFromDiskSync()
    }

    fun hasActiveSession(): Boolean {
        return sessionVault.hasActiveSession() || cookieCache.values.any { it.isNotEmpty() }
    }

    fun setKeepForeverEnabled(enabled: Boolean) {
        _keepForeverEnabled.value = enabled
        sessionVault.setPermanentLockEnabled(enabled)
        vaultScope.launch {
            mutex.withLock {
                persistToDisk()
            }
        }
    }

    val okHttpCookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val permanentMode = _keepForeverEnabled.value
            val nowMs = System.currentTimeMillis()
            val entries = cookies.map { c ->
                val rawExp = if (c.persistent) c.expiresAt else 0L
                val safeExp = if (permanentMode && rawExp <= nowMs) {
                    nowMs + StoredCookieEntry.TEN_YEARS_MS
                } else {
                    rawExp
                }
                StoredCookieEntry(
                    domain = c.domain,
                    includeSubdomains = !c.hostOnly,
                    path = c.path,
                    secure = c.secure,
                    expiresAtMs = safeExp,
                    name = c.name,
                    value = c.value,
                    httpOnly = c.httpOnly,
                )
            }
            val changed = mergeEntriesSync(entries)
            if (changed) {
                scheduleDebouncedPersist(immediate = entries.any { isProtectedSessionCookie(it.name) })
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val permanentMode = _keepForeverEnabled.value
            val respectExpiry = !permanentMode
            val matched = mutableListOf<Cookie>()
            val seenNames = mutableSetOf<String>()

            // 1. Vault cookies (imported cookies.txt + synced WebView cookies + persisted OkHttp cookies)
            cookieCache.values.flatten()
                .filter { it.matches(url, respectExpiry = respectExpiry) }
                .forEach { entry ->
                    entry.toOkHttpCookie(keepForever = permanentMode)?.let { okCookie ->
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

    private fun scheduleDebouncedPersist(immediate: Boolean) {
        debouncedPersistJob?.cancel()
        debouncedPersistJob = vaultScope.launch {
            if (!immediate) delay(350L)
            mutex.withLock {
                persistToDisk()
            }
        }
    }

    suspend fun importNetscapeCookiesTxt(rawContent: String): Int = withContext(Dispatchers.IO) {
        val parsed = parseCookiesText(rawContent)
        if (parsed.isEmpty()) return@withContext 0
        mutex.withLock {
            mergeEntriesSync(parsed)
            persistToDisk()
        }
        restoreVaultCookiesToWebView()
        parsed.size
    }

    /**
     * Pushes all permanently saved AES-256 cookies back into Android WebView's CookieManager
     * so users only need to log in ONCE even if WebView cache clears or after restarts/updates.
     */
    suspend fun restoreVaultCookiesToWebView(): Int {
        val snapshot = cookieCache.values.flatten()
        if (snapshot.isEmpty()) return 0

        val permanentMode = _keepForeverEnabled.value
        val respectExpiry = !permanentMode
        val validEntries = snapshot.filter {
            !it.isExpired(respectExpiry = respectExpiry) && it.value.isNotBlank()
        }
        if (validEntries.isEmpty()) return 0

        return withContext(Dispatchers.Main) {
            var restored = 0
            runCatching {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                val nowSec = System.currentTimeMillis() / 1000L

                validEntries.forEach { entry ->
                    val bareDomain = entry.domain.trimStart('.')
                    if (bareDomain.isBlank() || entry.name.isBlank()) return@forEach
                    val scheme = if (entry.secure) "https" else "https"
                    val targetUrl = "$scheme://$bareDomain/"
                    val path = entry.path.ifBlank { "/" }
                    val maxAgeSec = when {
                        permanentMode || entry.expiresAtMs <= 0L -> TEN_YEARS_SECONDS
                        else -> ((entry.expiresAtMs / 1000L) - nowSec).coerceAtLeast(3600L)
                    }
                    val cookieLine = buildString {
                        append("${entry.name}=${entry.value}")
                        if (entry.includeSubdomains) {
                            append("; Domain=.$bareDomain")
                        }
                        append("; Path=$path")
                        append("; Max-Age=$maxAgeSec")
                        if (entry.secure) append("; Secure")
                    }
                    cookieManager.setCookie(targetUrl, cookieLine)
                    restored++
                }
                cookieManager.flush()
            }
            restored
        }
    }

    suspend fun syncFromWebView(url: String): Int = withContext(Dispatchers.Main) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.flush()

        val candidateUrls = linkedSetOf<String>()
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            candidateUrls.add(url)
        }
        // Always sweep primary social/video domains + SSO origins so a single sign-in
        // locks all related session cookies safely into the AES-256 vault forever.
        candidateUrls.addAll(KNOWN_PLATFORM_COOKIE_URLS)

        val collected = mutableListOf<StoredCookieEntry>()
        val seenKeys = mutableSetOf<String>()
        val permanentExpiryMs = if (_keepForeverEnabled.value) {
            System.currentTimeMillis() + StoredCookieEntry.TEN_YEARS_MS
        } else {
            0L
        }

        candidateUrls.forEach { target ->
            val rawHeader = runCatching { cookieManager.getCookie(target) }.getOrNull().orEmpty()
            if (rawHeader.isBlank()) return@forEach
            val host = runCatching { java.net.URI(target).host.orEmpty() }.getOrDefault("")
            if (host.isBlank()) return@forEach
            val normalizedDomain = normalizeRootDomain(host)

            rawHeader.split(";").forEach { segment ->
                val idx = segment.indexOf('=')
                if (idx <= 0) return@forEach
                val name = segment.substring(0, idx).trim()
                val value = segment.substring(idx + 1).trim()
                if (name.isBlank() || isInvalidClearedValue(value)) return@forEach
                val dedupKey = "$normalizedDomain:$name"
                if (seenKeys.add(dedupKey)) {
                    collected.add(
                        StoredCookieEntry(
                            domain = normalizedDomain,
                            includeSubdomains = true,
                            path = "/",
                            secure = target.startsWith("https://", ignoreCase = true),
                            expiresAtMs = permanentExpiryMs,
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
            clean == "cdninstagram.com" || clean.endsWith(".cdninstagram.com") -> "cdninstagram.com"
            clean == "facebook.com" || clean.endsWith(".facebook.com") -> "facebook.com"
            clean == "fbcdn.net" || clean.endsWith(".fbcdn.net") -> "fbcdn.net"
            clean == "youtube.com" || clean.endsWith(".youtube.com") -> "youtube.com"
            clean == "google.com" || clean.endsWith(".google.com") -> "google.com"
            clean == "tiktok.com" || clean.endsWith(".tiktok.com") -> "tiktok.com"
            clean == "x.com" || clean.endsWith(".x.com") -> "x.com"
            clean == "twitter.com" || clean.endsWith(".twitter.com") -> "twitter.com"
            clean == "reddit.com" || clean.endsWith(".reddit.com") -> "reddit.com"
            clean == "vimeo.com" || clean.endsWith(".vimeo.com") -> "vimeo.com"
            clean == "twitch.tv" || clean.endsWith(".twitch.tv") -> "twitch.tv"
            else -> clean.removePrefix("www.").removePrefix("m.")
        }
    }

    suspend fun clearAllCookies() = withContext(Dispatchers.IO) {
        debouncedPersistJob?.cancel()
        mutex.withLock {
            cookieCache.clear()
            sessionVault.clearVault()
            if (legacyPlaintextFile.exists()) legacyPlaintextFile.delete()
            refreshFlowCounters()
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

    private fun mergeEntriesSync(entries: List<StoredCookieEntry>): Boolean {
        val permanentMode = _keepForeverEnabled.value
        val respectExpiry = !permanentMode
        val nowMs = System.currentTimeMillis()
        var mutated = false

        entries.forEach { rawEntry ->
            val entry = if (permanentMode && rawEntry.expiresAtMs <= nowMs) {
                rawEntry.copy(expiresAtMs = nowMs + StoredCookieEntry.TEN_YEARS_MS)
            } else {
                rawEntry
            }
            val key = entry.domain.lowercase().trimStart('.')
            val list = cookieCache.getOrPut(key) { mutableListOf() }
            synchronized(list) {
                val existing = list.firstOrNull {
                    it.name == entry.name && it.path == entry.path
                }

                // Never let an empty/"deleted" anonymous response wipe out a protected login cookie
                if (isInvalidClearedValue(entry.value) &&
                    existing != null &&
                    !isInvalidClearedValue(existing.value) &&
                    (permanentMode || isProtectedSessionCookie(entry.name))
                ) {
                    return@synchronized
                }

                if (existing != null &&
                    existing.value == entry.value &&
                    existing.secure == entry.secure &&
                    existing.httpOnly == entry.httpOnly &&
                    existing.expiresAtMs >= entry.expiresAtMs
                ) {
                    return@synchronized
                }

                list.removeAll { it.name == entry.name && it.path == entry.path }
                if (!entry.isExpired(nowMs = nowMs, respectExpiry = respectExpiry) &&
                    !isInvalidClearedValue(entry.value)
                ) {
                    list.add(entry)
                    mutated = true
                }
            }
        }
        refreshFlowCounters()
        return mutated
    }

    private fun refreshFlowCounters() {
        val nonEmptyKeys = cookieCache.entries
            .filter { it.value.isNotEmpty() }
            .map { it.key }
            .sorted()
        _syncedDomains.value = nonEmptyKeys
        _storedCookieCount.value = cookieCache.values.sumOf { it.size }
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
            val permanentMode = _keepForeverEnabled.value
            val respectExpiry = !permanentMode
            val nowMs = System.currentTimeMillis()
            val eternalExpSeconds = (nowMs / 1000L) + TEN_YEARS_SECONDS

            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            var written = 0
            cookieCache.values.flatten().forEach { entry ->
                if (!entry.isExpired(nowMs = nowMs, respectExpiry = respectExpiry) &&
                    !isInvalidClearedValue(entry.value)
                ) {
                    val domainField = if (entry.includeSubdomains && !entry.domain.startsWith(".")) {
                        ".${entry.domain}"
                    } else {
                        entry.domain
                    }
                    val prefix = if (entry.httpOnly) "#HttpOnly_" else ""
                    val includeSubField = if (entry.includeSubdomains) "TRUE" else "FALSE"
                    val secureField = if (entry.secure) "TRUE" else "FALSE"
                    val expSeconds = when {
                        permanentMode && entry.expiresAtMs <= nowMs -> eternalExpSeconds
                        entry.expiresAtMs > 0L -> entry.expiresAtMs / 1000L
                        else -> eternalExpSeconds
                    }
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

    private fun isInvalidClearedValue(value: String): Boolean {
        val v = value.trim()
        return v.isEmpty() ||
            v.equals("deleted", ignoreCase = true) ||
            v.equals("\"\"", ignoreCase = true) ||
            v.equals("null", ignoreCase = true)
    }

    private fun isProtectedSessionCookie(name: String): Boolean {
        return PROTECTED_SESSION_COOKIE_NAMES.contains(name.lowercase())
    }

    companion object {
        private const val TEN_YEARS_SECONDS = 315_360_000L

        private val KNOWN_PLATFORM_COOKIE_URLS = listOf(
            "https://www.instagram.com/",
            "https://i.instagram.com/",
            "https://instagram.com/",
            "https://www.facebook.com/",
            "https://m.facebook.com/",
            "https://facebook.com/",
            "https://www.youtube.com/",
            "https://m.youtube.com/",
            "https://accounts.google.com/",
            "https://www.google.com/",
            "https://www.tiktok.com/",
            "https://m.tiktok.com/",
            "https://x.com/",
            "https://twitter.com/",
            "https://www.reddit.com/",
            "https://vimeo.com/",
            "https://m.twitch.tv/",
        )

        private val PROTECTED_SESSION_COOKIE_NAMES = setOf(
            "sessionid",
            "ds_user_id",
            "csrftoken",
            "mid",
            "ig_did",
            "rur",
            "c_user",
            "xs",
            "datr",
            "sb",
            "fr",
            "sid",
            "ssid",
            "hsid",
            "apisid",
            "sapisid",
            "__secure-1psid",
            "__secure-3psid",
            "__secure-1papisid",
            "__secure-3papisid",
            "login_info",
            "ps_l",
            "ps_n",
            "sessionid_ss",
            "sid_tt",
            "uid_tt",
            "ttwid",
            "passport_csrf_token",
            "auth_token",
            "ct0",
            "kdt",
            "twid",
            "reddit_session",
            "token_v2",
            "vuid",
        )

        @Volatile
        private var INSTANCE: CookieVaultManager? = null

        fun getInstance(context: Context): CookieVaultManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CookieVaultManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
