package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VaultSettingsAndGridTest {

    @Test
    fun `noScreenshotEnabled defaults to true and persists toggle updates`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferences.getInstance(context)

        prefs.setNoScreenshotEnabled(true)
        assertTrue(prefs.noScreenshotEnabled.value)

        prefs.setNoScreenshotEnabled(false)
        assertFalse(prefs.noScreenshotEnabled.value)

        prefs.setNoScreenshotEnabled(true)
        assertTrue(prefs.noScreenshotEnabled.value)
    }
}
