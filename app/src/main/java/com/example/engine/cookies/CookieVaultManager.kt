package com.example.engine.cookies

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

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

    private val googleResetMutex = Mutex()

    private fun isGoogleOrYoutubeDomain(domain: String): Boolean {
        val d = domain.lowercase().trimStart('.')
        return d == "google.com" || d.endsWith(".google.com") ||
            d == "google.fr" || d.endsWith(".google.fr") ||
            d.contains(".google.") || d.startsWith("google.") ||
            d == "youtube.com" || d.endsWith(".youtube.com") ||
            d == "youtu.be" || d.endsWith(".youtu.be") ||
            d == "googleusercontent.com" || d.endsWith(".googleusercontent.com")
    }

    /**
     * Pushes missing permanently saved AES-256 cookies back into Android WebView's CookieManager
     * without overwriting live Chromium cookies or touching Google/YouTube native Chromium cookies.
     */
    suspend fun restoreVaultCookiesToWebView(): Int {
        googleResetMutex.withLock {
            val needsResetV4 = !sessionVault.hasCompletedGoogleCookieResetV4()
            var cacheCleaned = false

            if (needsResetV4) {
                val keysToPurge = cookieCache.keys.filter { isGoogleOrYoutubeDomain(it) }
                keysToPurge.forEach { key ->
                    if (cookieCache.remove(key) != null) cacheCleaned = true
                }
                if (cacheCleaned) {
                    refreshFlowCounters()
                    withContext(Dispatchers.IO) {
                        mutex.withLock { persistToDisk() }
                    }
                }
                resetGoogleWebViewCookies()
                sessionVault.markGoogleCookieResetV4Done()
            } else {
                listOf("google.com", "google.fr", "youtube.com", "accounts.google.com", "accounts.youtube.com").forEach { key ->
                    cookieCache[key]?.let { list ->
                        synchronized(list) {
                            val removed = list.removeAll { entry ->
                                isTransientGoogleHandshakeCookie(entry.name) ||
                                    entry.name.startsWith("__Host-", ignoreCase = true) ||
                                    isGoogleAccountsHostOnlyCookie(entry.name)
                            }
                            if (removed) cacheCleaned = true
                        }
                    }
                }
                if (cacheCleaned) {
                    refreshFlowCounters()
                    vaultScope.launch {
                        mutex.withLock { persistToDisk() }
                    }
                }
            }
        }

        val snapshot = cookieCache.values.flatten()
        if (snapshot.isEmpty()) return 0

        val permanentMode = _keepForeverEnabled.value
        val respectExpiry = !permanentMode
        // Never inject Google or YouTube cookies into WebView; Chromium manages Google/YouTube auth natively.
        val validEntries = snapshot.filter { entry ->
            !entry.isExpired(respectExpiry = respectExpiry) &&
                entry.value.isNotBlank() &&
                !isTransientGoogleHandshakeCookie(entry.name) &&
                !isGoogleOrYoutubeDomain(entry.domain)
        }
        if (validEntries.isEmpty()) return 0

        return withContext(Dispatchers.Main) {
            var restored = 0
            runCatching {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                val nowSec = System.currentTimeMillis() / 1000L
                val liveNamesByUrl = mutableMapOf<String, Set<String>>()

                validEntries.forEach { entry ->
                    val bareDomain = entry.domain.trimStart('.')
                    if (bareDomain.isBlank() || entry.name.isBlank()) return@forEach
                    val targetUrl = "https://$bareDomain/"

                    val existingNames = liveNamesByUrl.getOrPut(targetUrl) {
                        val raw = runCatching { cookieManager.getCookie(targetUrl) }.getOrNull().orEmpty()
                        if (raw.isBlank()) {
                            emptySet()
                        } else {
                            raw.split(";")
                                .mapNotNull { seg ->
                                    val idx = seg.indexOf('=')
                                    if (idx > 0) seg.substring(0, idx).trim() else null
                                }
                                .toSet()
                        }
                    }
                    if (existingNames.contains(entry.name)) return@forEach

                    val isStrictHostOnly = entry.name.startsWith("__Host-", ignoreCase = true) ||
                        !entry.includeSubdomains

                    val path = if (entry.name.startsWith("__Host-", ignoreCase = true)) {
                        "/"
                    } else {
                        entry.path.ifBlank { "/" }
                    }
                    val maxAgeSec = when {
                        permanentMode || entry.expiresAtMs <= 0L -> TEN_YEARS_SECONDS
                        else -> ((entry.expiresAtMs / 1000L) - nowSec).coerceAtLeast(3600L)
                    }
                    val cookieLine = buildString {
                        append("${entry.name}=${entry.value}")
                        if (!isStrictHostOnly && entry.includeSubdomains) {
                            append("; Domain=.$bareDomain")
                        }
                        append("; Path=$path")
                        append("; Max-Age=$maxAgeSec")
                        if (entry.secure || entry.name.startsWith("__Host-", true) || entry.name.startsWith("__Secure-", true)) {
                            append("; Secure; SameSite=None")
                        }
                    }
                    cookieManager.setCookie(targetUrl, cookieLine)
                    restored++
                }
                if (restored > 0) {
                    cookieManager.flush()
                }
            }
            restored
        }
    }

    /**
     * Performs a complete Chromium cookie & WebStorage wipe so stale/subpath/HttpOnly Google cookies
     * causing CookieMismatch (e.g., on www.google.fr) are completely eliminated.
     */
    suspend fun resetGoogleWebViewCookies() = withContext(Dispatchers.Main) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            suspendCancellableCoroutine<Unit> { cont ->
                cm.removeAllCookies {
                    cm.flush()
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            runCatching { WebStorage.getInstance().deleteAllData() }
            cm.flush()
        }
    }

    suspend fun syncFromWebView(url: String): Int = withContext(Dispatchers.Main) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val lowerUrl = url.lowercase()
        val isAuthHandshakeUrl = lowerUrl.contains("accounts.google.") ||
            lowerUrl.contains("accounts.youtube.") ||
            lowerUrl.contains("cookiemismatch") ||
            lowerUrl.contains("consent.google.") ||
            lowerUrl.contains("consent.youtube.")

        val candidateUrls = linkedSetOf<String>()
        if (!isAuthHandshakeUrl &&
            (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))
        ) {
            val urlHost = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
            if (!urlHost.contains("google.")) {
                candidateUrls.add(url)
            }
        }
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
            val host = runCatching { java.net.URI(target).host.orEmpty().lowercase() }.getOrDefault("")
            if (host.isBlank() || host.contains("google.")) return@forEach

            rawHeader.split(";").forEach { segment ->
                val idx = segment.indexOf('=')
                if (idx <= 0) return@forEach
                val name = segment.substring(0, idx).trim()
                val value = segment.substring(idx + 1).trim()
                if (name.isBlank() || isInvalidClearedValue(value)) return@forEach
                if (isTransientGoogleHandshakeCookie(name) || isGoogleAccountsHostOnlyCookie(name)) {
                    return@forEach
                }

                val isHostOnly = name.startsWith("__Host-", ignoreCase = true) ||
                    host.startsWith("accounts.", ignoreCase = true)

                val cookieDomain = if (isHostOnly) host else normalizeRootDomain(host)
                val dedupKey = "$cookieDomain:$name"
                if (seenKeys.add(dedupKey)) {
                    collected.add(
                        StoredCookieEntry(
                            domain = cookieDomain,
                            includeSubdomains = !isHostOnly,
                            path = "/",
                            secure = target.startsWith("https://", ignoreCase = true) ||
                                name.startsWith("__Host-", true) ||
                                name.startsWith("__Secure-", true),
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

    private fun isGoogleAccountsHostOnlyCookie(name: String): Boolean {
        return name.equals("GAPS", ignoreCase = true) ||
            name.equals("LSID", ignoreCase = true) ||
            name.equals("ACCOUNT_CHOOSER", ignoreCase = true) ||
            name.equals("SMSV", ignoreCase = true) ||
            name.equals("OSID", ignoreCase = true) ||
            name.equals("__Host-GAPS", ignoreCase = true) ||
            name.equals("__Host-1PLSID", ignoreCase = true) ||
            name.equals("__Host-3PLSID", ignoreCase = true)
    }

    private fun isTransientGoogleHandshakeCookie(name: String): Boolean {
        return TRANSIENT_GOOGLE_HANDSHAKE_COOKIES.contains(name.lowercase())
    }

    private fun normalizeRootDomain(host: String): String {
        val clean = host.lowercase().trimStart('.')
        return when {
            clean == "accounts.google.com" -> "accounts.google.com"
            clean == "accounts.youtube.com" -> "accounts.youtube.com"
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

        private val TRANSIENT_GOOGLE_HANDSHAKE_COOKIES = setOf(
            "gaps",
            "__host-gaps",
            "lsid",
            "__host-1plsid",
            "__host-3plsid",
            "account_chooser",
            "smsv",
            "osid",
            "otz",
            "check_cookie",
            "nid",
            "aec",
            "socs",
            "consent",
            "1p_jar",
            "__secure-enid",
            "uule",
            "dv",
        )

        private val KNOWN_PLATFORM_COOKIE_URLS = listOf(
            "https://www.instagram.com/",
            "https://i.instagram.com/",
            "https://instagram.com/",
            "https://www.facebook.com/",
            "https://m.facebook.com/",
            "https://facebook.com/",
            "https://www.youtube.com/",
            "https://m.youtube.com/",
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
