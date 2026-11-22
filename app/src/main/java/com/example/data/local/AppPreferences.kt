package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent lightweight app settings store backed by SharedPreferences.
 */
class AppPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val _noScreenshotEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_NO_SCREENSHOT_ENABLED, true),
    )
    val noScreenshotEnabled: StateFlow<Boolean> = _noScreenshotEnabled.asStateFlow()

    fun setNoScreenshotEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NO_SCREENSHOT_ENABLED, enabled).apply()
        _noScreenshotEnabled.value = enabled
    }

    fun hasRequestedStartupPermissions(): Boolean {
        return prefs.getBoolean(KEY_STARTUP_PERMISSIONS_REQUESTED, false)
    }

    fun markStartupPermissionsRequested() {
        prefs.edit().putBoolean(KEY_STARTUP_PERMISSIONS_REQUESTED, true).apply()
    }

    companion object {
        private const val PREFS_NAME = "blackhole_app_settings"
        private const val KEY_NO_SCREENSHOT_ENABLED = "no_screenshot_enabled"
        private const val KEY_STARTUP_PERMISSIONS_REQUESTED = "startup_permissions_requested"

        @Volatile
        private var instance: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context).also { instance = it }
            }
        }
    }
}
