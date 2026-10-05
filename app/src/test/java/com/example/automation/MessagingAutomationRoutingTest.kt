package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ActionDispatcher
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.service.AutomationTask
import com.example.service.JarvisAccessibilityService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessagingAutomationRoutingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        MaxRuntimeManager.useComputerUseForMessaging = true
        JarvisAccessibilityService.pendingTask = null
    }

    @After
    fun tearDown() {
        MaxRuntimeManager.useComputerUseForMessaging = true
        JarvisAccessibilityService.pendingTask = null
    }

    @Test
    fun testFeatureFlag_useComputerUseForMessaging_DefaultEnabled() {
        assertTrue("Feature flag should be default-enabled for ComputerUseEngine", MaxRuntimeManager.useComputerUseForMessaging)
        
        // WhatsApp & Telegram should be classified as UI navigation tasks
        assertTrue(ActionDispatcher.isUiNavigationTask("WhatsApp", "send_whatsapp_message"))
        assertTrue(ActionDispatcher.isUiNavigationTask("Telegram", "send_telegram_message"))
        assertTrue(ActionDispatcher.isUiNavigationTask("WhatsApp", "create sticker"))
    }

    @Test
    fun testJarvisAccessibilityService_setPendingTaskSafely_PreventsConcurrentRace() {
        val task1 = AutomationTask(
            type = "SEND_MESSAGE",
            contact = "Yash",
            message = "Hello",
            appName = "WhatsApp",
            startTime = System.currentTimeMillis()
        )

        val task2 = AutomationTask(
            type = "SEND_MESSAGE",
            contact = "John",
            message = "Hi",
            appName = "WhatsApp",
            startTime = System.currentTimeMillis()
        )

        // Assign first task
        val firstAssigned = JarvisAccessibilityService.setPendingTaskSafely(task1)
        assertTrue("First task must be assigned successfully", firstAssigned)
        assertTrue("pendingTask must equal task1", JarvisAccessibilityService.pendingTask === task1)

        // Attempting to overwrite while active must be rejected
        val secondAssigned = JarvisAccessibilityService.setPendingTaskSafely(task2)
        assertFalse("Second concurrent task must be rejected while first is active", secondAssigned)
        assertTrue("pendingTask must remain task1", JarvisAccessibilityService.pendingTask === task1)

        // After clearing first task, second can be assigned
        JarvisAccessibilityService.clearPendingTask(task1)
        val retryAssigned = JarvisAccessibilityService.setPendingTaskSafely(task2)
        assertTrue("Second task can now be assigned after clearing", retryAssigned)
        assertTrue("pendingTask must now equal task2", JarvisAccessibilityService.pendingTask === task2)
    }
}
