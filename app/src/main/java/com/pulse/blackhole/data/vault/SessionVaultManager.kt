package com.pulse.blackhole.data.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.io.File

class SessionVaultManager(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences: SharedPreferences by lazy {
        createEncryptedPrefs()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            // Recover cleanly if Keystore key material became invalid after backup/reinstall
            runCatching {
                val prefsFile = File(appContext.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
                if (prefsFile.exists()) prefsFile.delete()
            }
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    // حفظ ملف الـ Cookies المستورد
    fun saveCookies(cookiesText: String) {
        val normalized = normalizeToNetscapeFormat(cookiesText)
        sharedPreferences.edit().putString(KEY_COOKIES_CONTENT, normalized).apply()
    }

    // استرجاع الـ Cookies لحقنها في محرك التحميل
    fun getCookies(): String? {
        return sharedPreferences.getString(KEY_COOKIES_CONTENT, null)
    }

    // التحقق هل خزنة الجلسات تحتوي على بيانات صالحة
    fun hasActiveSession(): Boolean {
        return !getCookies().isNullOrBlank()
    }

    // مسح الجلسة
    fun clearVault() {
        sharedPreferences.edit().remove(KEY_COOKIES_CONTENT).apply()
    }

    /**
     * Writes stored Netscape-format cookies into a private temporary file for CLI/router injection.
     */
    fun writeTempNetscapeCookiesFile(targetDir: File = appContext.cacheDir): File? {
        val raw = getCookies()?.trim().orEmpty()
        if (raw.isBlank()) return null
        val netscapeContent = normalizeToNetscapeFormat(raw)
        if (netscapeContent.isBlank()) return null
        return runCatching {
            if (!targetDir.exists()) targetDir.mkdirs()
            val tempFile = File(targetDir, "blackhole_session_cookies.txt")
            tempFile.writeText(netscapeContent)
            tempFile
        }.getOrNull()
    }

    private fun normalizeToNetscapeFormat(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""

        // Convert JSON-exported cookies (e.g., Cookie-Editor / EditThisCookie) to Netscape format
        if (trimmed.startsWith("[")) {
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            var count = 0
            runCatching {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val rawDomain = obj.optString("domain").trim()
                    val name = obj.optString("name").trim()
                    val value = obj.optString("value")
                    if (rawDomain.isBlank() || name.isBlank()) continue
                    val includeSub = rawDomain.startsWith(".")
                    val domainField = if (includeSub) rawDomain else ".$rawDomain"
                    val path = obj.optString("path", "/").ifBlank { "/" }
                    val secure = obj.optBoolean("secure", false)
                    val httpOnly = obj.optBoolean("httpOnly", false)
                    val exp = obj.optDouble("expirationDate", 0.0).toLong()
                    val prefix = if (httpOnly) "#HttpOnly_" else ""
                    sb.append(prefix)
                        .append(domainField).append('\t')
                        .append(if (includeSub) "TRUE" else "TRUE").append('\t')
                        .append(path).append('\t')
                        .append(if (secure) "TRUE" else "FALSE").append('\t')
                        .append(if (exp > 0L) exp else 0L).append('\t')
                        .append(name).append('\t')
                        .append(value).append('\n')
                    count++
                }
            }
            if (count > 0) return sb.toString()
        }

        return if (trimmed.startsWith("# Netscape HTTP Cookie File")) {
            trimmed
        } else {
            "# Netscape HTTP Cookie File\n$trimmed"
        }
    }

    companion object {
        private const val PREFS_NAME = "blackhole_secure_vault"
        private const val KEY_COOKIES_CONTENT = "KEY_COOKIES_CONTENT"
    }
}
