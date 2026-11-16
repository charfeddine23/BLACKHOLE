package com.pulse.blackhole.data.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
        sharedPreferences.edit().putString(KEY_COOKIES_CONTENT, cookiesText).apply()
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

    companion object {
        private const val PREFS_NAME = "blackhole_secure_vault"
        private const val KEY_COOKIES_CONTENT = "KEY_COOKIES_CONTENT"
    }
}
