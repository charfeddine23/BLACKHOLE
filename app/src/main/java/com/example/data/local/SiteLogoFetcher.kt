package com.example.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Fetches and caches real website logos/favicons locally on disk for pinned speed-dial sites
 * in the Vault Private Browser.
 */
object SiteLogoFetcher {

    private val fetchMutex = Mutex()
    private val inFlight = mutableMapOf<String, String?>()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val LINK_TAG_REGEX = Pattern.compile(
        "<link\\b([^>]+)>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
    )
    private val META_TAG_REGEX = Pattern.compile(
        "<meta\\b([^>]+)>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
    )
    private val ATTR_REGEX = Pattern.compile(
        """([a-zA-Z0-9_:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""",
        Pattern.CASE_INSENSITIVE,
    )

    fun getCachedLogoFile(context: Context, siteUrl: String): File? {
        val cacheKey = cacheKeyForUrl(siteUrl) ?: return null
        val dir = File(context.applicationContext.filesDir, CACHE_DIR_NAME)
        val file = File(dir, "$cacheKey.png")
        return file.takeIf { it.exists() && it.length() > 100L }
    }

    suspend fun fetchAndCacheLogo(
        context: Context,
        siteUrl: String,
        forceRefresh: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val parsedUrl = siteUrl.trim().toHttpUrlOrNull() ?: return@withContext null
        val cacheKey = cacheKeyForUrl(siteUrl) ?: return@withContext null
        val cacheDir = File(appContext.filesDir, CACHE_DIR_NAME).apply { mkdirs() }
        val targetFile = File(cacheDir, "$cacheKey.png")

        if (!forceRefresh && targetFile.exists() && targetFile.length() > 100L) {
            return@withContext targetFile.absolutePath
        }

        val candidates = collectCandidateIconUrls(parsedUrl.toString())
        for (iconUrl in candidates) {
            val bytes = downloadImageBytes(iconUrl) ?: continue
            val decoded = decodeValidBitmap(bytes) ?: continue
            val normalized = scaleToLogoSize(decoded, MAX_ICON_PX)
            val saved = savePngAtomically(normalized, targetFile)
            if (normalized !== decoded) decoded.recycle()
            normalized.recycle()
            if (saved) {
                return@withContext targetFile.absolutePath
            }
        }
        null
    }

    suspend fun fetchAndCacheLogoDeduplicated(
        context: Context,
        siteUrl: String,
    ): String? {
        val existing = getCachedLogoFile(context, siteUrl)
        if (existing != null) return existing.absolutePath
        val key = cacheKeyForUrl(siteUrl) ?: return null

        val alreadyResolved = fetchMutex.withLock {
            if (inFlight.containsKey(key)) inFlight[key] else null
        }
        if (alreadyResolved != null && File(alreadyResolved).exists()) {
            return alreadyResolved
        }

        val result = fetchAndCacheLogo(context, siteUrl)
        fetchMutex.withLock {
            if (result != null) {
                inFlight[key] = result
            }
        }
        return result
    }

    internal fun collectCandidateIconUrls(pageUrl: String): List<String> {
        val base = pageUrl.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${base.scheme}://${base.host}" +
            if ((base.scheme == "http" && base.port != 80) || (base.scheme == "https" && base.port != 443)) {
                ":${base.port}"
            } else {
                ""
            }
        val htmlIcons = extractHtmlIconCandidates(base.toString())
        val encodedOrigin = runCatching { URLEncoder.encode(origin, "UTF-8") }.getOrDefault(origin)

        return (
            htmlIcons + listOf(
                "https://www.google.com/s2/favicons?sz=128&domain_url=$encodedOrigin",
                "https://icons.duckduckgo.com/ip3/${base.host}.ico",
                "$origin/apple-touch-icon.png",
                "$origin/apple-touch-icon-precomposed.png",
                "$origin/favicon.png",
                "$origin/favicon.ico",
            )
            ).distinct()
    }

    private fun extractHtmlIconCandidates(pageUrl: String): List<String> {
        val base = pageUrl.toHttpUrlOrNull() ?: return emptyList()
        val html = fetchHtmlHead(pageUrl) ?: return emptyList()
        val prioritized = mutableListOf<Pair<Int, String>>()

        val linkMatcher = LINK_TAG_REGEX.matcher(html)
        while (linkMatcher.find()) {
            val attrs = parseAttributes(linkMatcher.group(1).orEmpty())
            val rel = attrs["rel"]?.lowercase().orEmpty()
            val href = attrs["href"]?.trim().orEmpty()
            if (href.isBlank() || href.startsWith("data:image/svg", ignoreCase = true)) continue
            if (href.substringBefore("?").endsWith(".svg", ignoreCase = true)) continue

            val relScore = when {
                rel.contains("apple-touch-icon") -> 100
                rel.contains("fluid-icon") -> 90
                rel.contains("icon") -> 75
                else -> 0
            }
            if (relScore == 0) continue

            val sizeHint = parseSizeHint(attrs["sizes"])
            val resolved = base.resolve(href)?.toString() ?: continue
            prioritized += (relScore + sizeHint) to resolved
        }

        val metaMatcher = META_TAG_REGEX.matcher(html)
        while (metaMatcher.find()) {
            val attrs = parseAttributes(metaMatcher.group(1).orEmpty())
            val prop = (attrs["property"] ?: attrs["name"]).orEmpty().lowercase()
            val content = attrs["content"]?.trim().orEmpty()
            if (content.isBlank()) continue
            if (prop == "og:image" || prop == "twitter:image" || prop == "msapplication-tileimage") {
                val resolved = base.resolve(content)?.toString() ?: continue
                val score = if (prop == "msapplication-tileimage") 80 else 30
                prioritized += score to resolved
            }
        }

        return prioritized
            .sortedByDescending { it.first }
            .map { it.second }
            .distinct()
            .take(8)
    }

    private fun fetchHtmlHead(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body ?: return@use null
            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            if (contentType.isNotBlank() && !contentType.contains("html") && !contentType.contains("xml")) {
                return@use null
            }
            val source = body.source()
            source.request(MAX_HTML_BYTES)
            val buffer = source.buffer.clone()
            buffer.readUtf8(minOf(buffer.size, MAX_HTML_BYTES))
        }
    }.getOrNull()

    private fun downloadImageBytes(url: String): ByteArray? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/x-icon,image/*;q=0.8")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body ?: return@use null
            val bytes = body.bytes()
            if (bytes.size in 64..MAX_IMAGE_BYTES) bytes else null
        }
    }.getOrNull()

    private fun decodeValidBitmap(bytes: ByteArray): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null
        if (bmp.width < 16 || bmp.height < 16) {
            bmp.recycle()
            null
        } else {
            bmp
        }
    }.getOrNull()

    private fun scaleToLogoSize(src: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= maxDim) return src
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        val targetW = (src.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    private fun savePngAtomically(bitmap: Bitmap, dest: File): Boolean = runCatching {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)) return@runCatching false
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        tmp.writeBytes(out.toByteArray())
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
    }.getOrDefault(false)

    private fun parseAttributes(rawAttrs: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val matcher = ATTR_REGEX.matcher(rawAttrs)
        while (matcher.find()) {
            val key = matcher.group(1)?.lowercase() ?: continue
            val value = matcher.group(2) ?: matcher.group(3) ?: matcher.group(4) ?: ""
            map[key] = value
        }
        return map
    }

    private fun parseSizeHint(sizesAttr: String?): Int {
        if (sizesAttr.isNullOrBlank()) return 0
        val firstNum = sizesAttr.substringBefore("x", "").trim().toIntOrNull() ?: return 0
        return (firstNum / 8).coerceIn(0, 40)
    }

    private fun cacheKeyForUrl(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        val hostKey = parsed.host.lowercase().removePrefix("www.")
        val digest = MessageDigest.getInstance("SHA-256").digest(hostKey.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private const val CACHE_DIR_NAME = "browser_site_logos"
    private const val MAX_HTML_BYTES = 65_536L
    private const val MAX_IMAGE_BYTES = 1_500_000
    private const val MAX_ICON_PX = 192
}
