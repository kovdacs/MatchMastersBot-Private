package com.match3vision.analyzer

import android.Manifest
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke. CI compiles this with assembleDebugAndroidTest.
 * It does not require the permission to be granted.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test
    fun postNotificationsPermissionName() {
        assertEquals(
            "android.permission.POST_NOTIFICATIONS",
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @Test
    fun overlaySettingIsReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Settings.canDrawOverlays(context)
        assertNotNull(context)
    }

    @Test
    fun accessibilityManagerIsPresent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        assertNotNull(manager)
    }
}
