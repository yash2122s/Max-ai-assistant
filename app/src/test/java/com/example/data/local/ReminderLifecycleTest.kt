package com.example.data.local

import android.app.AlarmManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.JarvisRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReminderLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dao = AppDatabase.getDatabase(context).reminderDao()
    private val repo = JarvisRepository(context)
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Test fun editingNoteKeepsTimeAndActionAndDeliveryCanOnlyBeClaimedOnce() = runBlocking {
        val due = System.currentTimeMillis() + 60_000
        val original = Reminder(message = "Original", triggerAt = due, automationType = "test",
            automationTarget = "fixture", automationMessage = "Example")
        val id = dao.insertReminder(original).toInt()
        repo.saveReminder(id, "Edited", null)
        val expected = original.copy(id = id, message = "Edited")
        assertEquals(expected, dao.getReminderById(id))
        assertNull(dao.claimDueReminder(id, due - 1))
        val claims = List(2) { async { dao.claimDueReminder(id, due) } }.awaitAll()
        assertEquals(listOf(expected), claims.filterNotNull())
        repo.deleteReminder(id)
    }

    @Test fun completionReopeningAndDeletionUpdateTheAlarmAndRejectStaleDelivery() = runBlocking {
        val due = System.currentTimeMillis() + 60_000
        repo.saveReminder(null, "Example", due)
        val id = dao.getPendingReminders().single().id
        assertEquals(1, alarms.scheduledAlarms.size)
        repo.markReminderCompleted(id)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(dao.claimDueReminder(id, due))
        repo.saveReminder(id, "New note", null)
        assertEquals("completed", dao.getReminderById(id)?.status)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertTrue(repo.reopenReminder(id))
        assertEquals(1, alarms.scheduledAlarms.size)
        repo.deleteReminder(id)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(dao.claimDueReminder(id, due))
    }

    @Test fun unsupportedOrInvalidTimesAskForClarificationBeforeSaving() = runBlocking {
        val tool = com.example.automation.tools.ReminderTool()
        val before = dao.getAllReminders()
        for (expression in listOf("next Monday at 3", "25:71", "in 0 minutes", "in 999999999999 days")) {
            val args = com.google.gson.JsonObject().apply { addProperty("time_expression", expression) }
            val request = com.example.automation.engine.ExecutionRequest(action = "SET_REMINDER", arguments = args)
            val result = tool.execute(context, request)
            assertEquals("$expression returned ${result.message}", com.example.automation.tools.ToolResultStatus.NEEDS_INPUT, result.status)
        }
        assertEquals(before, dao.getAllReminders())
        assertEquals(601_000L, tool.parseTimeExpression("in 10 minutes", 1000L))
    }

    @Test fun earlyDeliveryWithinToleranceIsSuccessfullyClaimed() = runBlocking {
        val due = System.currentTimeMillis() + 60_000
        val reminder = Reminder(message = "Tolerance Test", triggerAt = due)
        val id = dao.insertReminder(reminder).toInt()

        // Early delivery without tolerance returns null
        assertNull(dao.claimDueReminder(id, due - 5_000L, toleranceMs = 0L))

        // Early delivery with tolerance claims the reminder
        val claimed = dao.claimDueReminder(id, due - 5_000L, toleranceMs = 60_000L)
        assertNotNull(claimed)
        assertEquals("Tolerance Test", claimed?.message)
        assertEquals("completed", dao.getReminderById(id)?.status)
    }

    @Test fun reminderReceiverPostsNotificationWithAlarmAttributes() = runBlocking {
        val due = System.currentTimeMillis()
        val reminder = Reminder(message = "Meeting with Team", triggerAt = due)
        val id = dao.insertReminder(reminder).toInt()

        val receiver = com.example.receiver.ReminderReceiver()
        val intent = android.content.Intent(context, com.example.receiver.ReminderReceiver::class.java).apply {
            putExtra(com.example.receiver.ReminderReceiver.EXTRA_REMINDER_ID, id)
        }
        receiver.onReceive(context, intent)

        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        val shadowNotifManager = shadowOf(notificationManager)
        var notifications = shadowNotifManager.allNotifications
        var waited = 0
        while (notifications.isEmpty() && waited < 2000) {
            kotlinx.coroutines.delay(100)
            waited += 100
            notifications = shadowNotifManager.allNotifications
        }

        assertTrue("Notification should be posted", notifications.isNotEmpty())
        val posted = notifications.last()
        assertEquals("MAX Reminder", shadowOf(posted).contentTitle)
        assertEquals("Meeting with Team", shadowOf(posted).contentText)
    }
}
