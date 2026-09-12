package com.example.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApprovalSettingsUnitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun freshSettings(): SettingsManager {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
        return SettingsManager(context)
    }

    @Test
    fun `approval mode defaults to SAFE_AUTO`() {
        assertEquals("SAFE_AUTO", freshSettings().approvalMode)
    }

    @Test
    fun `approval mode persists across instances`() {
        val settings = freshSettings()
        settings.approvalMode = "FULL_AUTO"
        assertEquals("FULL_AUTO", SettingsManager(context).approvalMode)
    }

    @Test
    fun `caution auto flag persists and defaults false`() {
        val settings = freshSettings()
        assertFalse(settings.isCautionAutoAllowedInFullAuto)
        settings.isCautionAutoAllowedInFullAuto = true
        assertTrue(SettingsManager(context).isCautionAutoAllowedInFullAuto)
    }

    @Test
    fun `approval allowlist persists and defaults empty`() {
        val settings = freshSettings()
        assertEquals("", settings.approvalAllowlist)
        settings.approvalAllowlist = "SEND_SMS,OPEN_APP"
        assertEquals("SEND_SMS,OPEN_APP", SettingsManager(context).approvalAllowlist)
    }
}
