package com.pulse.blackhole.data.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Hardware-backed AES-256-GCM persistent cookie vault with atomic commits and redundant
 * encrypted backup so user login sessions remain locked safely forever ("login once only").
 */
class SessionVaultManager(context: Context) {

    private val appContext = context.applicationContext
    private val vaultLock = Any()

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences: SharedPreferences by lazy {
        createEncryptedPrefs()
    }

    private val backupVaultFile: File
        get() = File(appContext.filesDir, BACKUP_ENCRYPTED_FILE_NAME)

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
            val recoveredFromBackup = readFromEncryptedBackupFile()
            runCatching {
                val prefsFile = File(appContext.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
                if (prefsFile.exists()) prefsFile.delete()
            }
            val recreated = EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            if (!recoveredFromBackup.isNullOrBlank()) {
                recreated.edit()
                    .putString(KEY_COOKIES_CONTENT, recoveredFromBackup)
                    .putLong(KEY_LAST_SAVED_MS, System.currentTimeMillis())
                    .commit()
            }
            recreated
        }
    }

    /**
     * Encrypts and commits cookies synchronously to both AES-256 SharedPreferences and the
     * redundant AES-256 EncryptedFile backup so sessions survive app updates, reboots, and kills.
     */
    fun saveCookies(cookiesText: String) {
        val normalized = normalizeToNetscapeFormat(cookiesText)
        if (normalized.isBlank()) return
        synchronized(vaultLock) {
            sharedPreferences.edit()
                .putString(KEY_COOKIES_CONTENT, normalized)
                .putLong(KEY_LAST_SAVED_MS, System.currentTimeMillis())
                .commit()
            writeToEncryptedBackupFile(normalized)
        }
    }

    /**
     * Retrieves encrypted cookies from primary vault, transparently recovering from the
     * redundant AES-256 backup file if primary preferences were unavailable.
     */
    fun getCookies(): String? {
        synchronized(vaultLock) {
            val primary = runCatching {
                sharedPreferences.getString(KEY_COOKIES_CONTENT, null)
            }.getOrNull()
            if (!primary.isNullOrBlank()) return primary

            val backup = readFromEncryptedBackupFile()
            if (!backup.isNullOrBlank()) {
                runCatching {
                    sharedPreferences.edit()
                        .putString(KEY_COOKIES_CONTENT, backup)
                        .commit()
                }
                return backup
            }
            return null
        }
    }

    fun isPermanentLockEnabled(): Boolean {
        return synchronized(vaultLock) {
            runCatching {
                sharedPreferences.getBoolean(KEY_PERMANENT_LOCK, true)
            }.getOrDefault(true)
        }
    }

    fun setPermanentLockEnabled(enabled: Boolean) {
        synchronized(vaultLock) {
            sharedPreferences.edit()
                .putBoolean(KEY_PERMANENT_LOCK, enabled)
                .commit()
        }
    }

    fun hasCompletedGoogleCookieResetV4(): Boolean {
        return synchronized(vaultLock) {
            runCatching {
                sharedPreferences.getBoolean(KEY_GOOGLE_COOKIE_RESET_V4, false)
            }.getOrDefault(false)
        }
    }

    fun markGoogleCookieResetV4Done() {
        synchronized(vaultLock) {
            sharedPreferences.edit()
                .putBoolean(KEY_GOOGLE_COOKIE_RESET_V4, true)
                .commit()
        }
    }

    fun hasActiveSession(): Boolean {
        return !getCookies().isNullOrBlank()
    }

    fun clearVault() {
        synchronized(vaultLock) {
            runCatching {
                sharedPreferences.edit()
                    .remove(KEY_COOKIES_CONTENT)
                    .remove(KEY_LAST_SAVED_MS)
                    .commit()
            }
            runCatching {
                if (backupVaultFile.exists()) backupVaultFile.delete()
            }
        }
    }

    private fun writeToEncryptedBackupFile(content: String) {
        runCatching {
            val staging = File(appContext.filesDir, "$BACKUP_ENCRYPTED_FILE_NAME.tmp")
            if (staging.exists()) staging.delete()
            val encryptedFile = EncryptedFile.Builder(
                appContext,
                staging,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
            encryptedFile.openFileOutput().use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
            if (staging.exists() && staging.length() > 0L) {
                if (backupVaultFile.exists()) backupVaultFile.delete()
                staging.renameTo(backupVaultFile)
            }
        }
    }

    private fun readFromEncryptedBackupFile(): String? {
        return runCatching {
            val target = backupVaultFile
            if (!target.exists() || target.length() == 0L) return@runCatching null
            val encryptedFile = EncryptedFile.Builder(
                appContext,
                target,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
            encryptedFile.openFileInput().use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            }.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun normalizeToNetscapeFormat(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""

        // Convert JSON-exported cookies (e.g., Cookie-Editor / EditThisCookie) to Netscape format
        if (trimmed.startsWith("[")) {
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            var count = 0
            val permanentExpSec = (System.currentTimeMillis() / 1000L) + TEN_YEARS_SECONDS
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
                    val effectiveExp = if (exp > 0L) exp else permanentExpSec
                    val prefix = if (httpOnly) "#HttpOnly_" else ""
                    sb.append(prefix)
                        .append(domainField).append('\t')
                        .append("TRUE").append('\t')
                        .append(path).append('\t')
                        .append(if (secure) "TRUE" else "FALSE").append('\t')
                        .append(effectiveExp).append('\t')
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
        private const val BACKUP_ENCRYPTED_FILE_NAME = "blackhole_eternal_vault.enc"
        private const val KEY_COOKIES_CONTENT = "KEY_COOKIES_CONTENT"
        private const val KEY_PERMANENT_LOCK = "KEY_PERMANENT_LOCK"
        private const val KEY_LAST_SAVED_MS = "KEY_LAST_SAVED_MS"
        private const val KEY_GOOGLE_COOKIE_RESET_V4 = "KEY_GOOGLE_COOKIE_RESET_V4"
        private const val TEN_YEARS_SECONDS = 315_360_000L
    }
}
