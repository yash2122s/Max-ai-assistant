package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoReplySettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testDefaultWhatsAppAutoReplyScopeIsAll() {
        val settings = SettingsManager(context)
        assertEquals("ALL", settings.whatsappAutoReplyScope)
    }

    @Test
    fun testSetAndGetWhatsAppAutoReplyScope() {
        val settings = SettingsManager(context)
        settings.whatsappAutoReplyScope = "SELECTED"
        assertEquals("SELECTED", SettingsManager(context).whatsappAutoReplyScope)
    }

    @Test
    fun testSetAndGetSelectedContacts() {
        val settings = SettingsManager(context)
        val testSet = setOf("Priya", "+919876543210", "Boss")
        settings.whatsappSelectedContacts = testSet
        assertEquals(testSet, SettingsManager(context).whatsappSelectedContacts)
    }
}
