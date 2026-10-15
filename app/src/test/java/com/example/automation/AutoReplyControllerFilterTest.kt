package com.example.automation

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.SettingsManager
import com.example.features.whatsapp.AutoReplyController
import com.example.features.whatsapp.RemoteInputSender
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoReplyControllerFilterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var settings: SettingsManager

    @Before
    fun setup() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = SettingsManager(context)
        settings.isAutoReplyEnabled = true
        settings.isWhatsAppAutoReplyEnabled = true
    }

    private fun createWhatsAppNotification(sender: String, hasRemoteInput: Boolean = true): StatusBarNotification {
        val intent = Intent("com.example.REPLY")
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val actionBuilder = Notification.Action.Builder(
            0, "Reply", pendingIntent
        )

        if (hasRemoteInput) {
            val remoteInput = RemoteInput.Builder("key_text_reply")
                .setLabel("Reply")
                .build()
            actionBuilder.addRemoteInput(remoteInput)
        }

        val notification = Notification.Builder(context, "test_channel")
            .setContentTitle(sender)
            .setContentText("Hello")
            .addAction(actionBuilder.build())
            .build()

        return StatusBarNotification(
            "com.whatsapp",
            null,
            101,
            "test_tag",
            android.os.Process.myUid(),
            android.os.Process.myPid(),
            0,
            notification,
            android.os.Process.myUserHandle(),
            System.currentTimeMillis()
        )
    }

    @Test
    fun testHasRemoteInputHelper() {
        val sbnWithInput = createWhatsAppNotification("Priya", hasRemoteInput = true)
        val sbnWithoutInput = createWhatsAppNotification("Priya", hasRemoteInput = false)

        assertTrue(RemoteInputSender.hasRemoteInput(sbnWithInput))
        assertFalse(RemoteInputSender.hasRemoteInput(sbnWithoutInput))
    }

    @Test
    fun testSelectedContactsFilterSuppressesNonWhitelistedSender() = kotlinx.coroutines.runBlocking {
        settings.whatsappAutoReplyScope = "SELECTED"
        settings.whatsappSelectedContacts = setOf("Priya", "Mom")

        val sbn = createWhatsAppNotification("Boss", hasRemoteInput = true)
        AutoReplyController.handleIncoming(context, "Boss", "Are you available?", sbn)

        // Boss is not in whitelist, so NO pending confirmation should be created
        val pending = AutoReplyController.getLatestPendingConfirmation()
        assertNull(pending)
    }

    @Test
    fun testSelectedContactsFilterAllowsWhitelistedSender() = kotlinx.coroutines.runBlocking {
        settings.whatsappAutoReplyScope = "SELECTED"
        settings.whatsappSelectedContacts = setOf("Priya", "Mom")

        val sbn = createWhatsAppNotification("Priya", hasRemoteInput = true)
        AutoReplyController.handleIncoming(context, "Priya", "Are you available?", sbn)

        // Priya is in whitelist, pending confirmation should be created
        val pending = AutoReplyController.getLatestPendingConfirmation()
        assertNotNull(pending)
        assertEquals("Priya", pending?.sender)

        // Clean up
        AutoReplyController.cancelPendingReply(context, pending!!.confirmationId)
    }

    @Test
    fun testNotificationWithoutRemoteInputIsSuppressed() = kotlinx.coroutines.runBlocking {
        settings.whatsappAutoReplyScope = "ALL"

        val sbn = createWhatsAppNotification("Priya", hasRemoteInput = false)
        AutoReplyController.handleIncoming(context, "Priya", "Hello", sbn)

        // No RemoteInput -> must suppress
        val pending = AutoReplyController.getLatestPendingConfirmation()
        assertNull(pending)
    }

    @Test
    fun testBlankSenderFailsClosed() = kotlinx.coroutines.runBlocking {
        settings.whatsappAutoReplyScope = "ALL"

        val sbn = createWhatsAppNotification("", hasRemoteInput = true)
        AutoReplyController.handleIncoming(context, "   ", "Hello", sbn)

        val pending = AutoReplyController.getLatestPendingConfirmation()
        assertNull(pending)
    }

    @Test
    fun testNotificationAppAccessManagerAllowsWhatsAppWhenAutoReplyEnabled() {
        settings.isWhatsAppAutoReplyEnabled = true
        assertTrue(com.example.service.NotificationAppAccessManager.isAppEnabled(context, "com.whatsapp"))
        assertTrue(com.example.service.NotificationAppAccessManager.isAppEnabled(context, "com.whatsapp.w4b"))

        settings.isWhatsAppAutoReplyEnabled = false
        assertFalse(com.example.service.NotificationAppAccessManager.isAppEnabled(context, "com.whatsapp"))
    }
}

