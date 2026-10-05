package com.example.automation

import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.MainActivity
import com.example.automation.activity.AgentActivityTracker
import com.example.automation.engine.TaskCancellationCoordinator
import com.example.automation.engine.truth.ExecutionTruth
import com.example.automation.engine.truth.ExecutionTruthCoordinator
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.data.local.AppDatabase
import com.example.service.JarvisAccessibilityService
import com.example.ui.home.HomeScreen
import com.example.ui.home.HomeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/** Real Home input and real STOP control. No fake roots, tools, torch state or truth. */
class BasicExecutionDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var home: HomeViewModel

    @Before fun prepareHome() {
        assertTrue("Enable MAX before acceptance tests", MaxRuntimeManager.isMaxEnabled(rule.activity))
        assertNotNull("Enable MAX Accessibility to observe app foreground", JarvisAccessibilityService.instance)
        rule.runOnUiThread {
            home = HomeViewModel(rule.activity.application)
            rule.activity.setContent {
                MaterialTheme { HomeScreen(home, onOpenDrawer = {}, onNavigateTo = {}) }
            }
        }
        AgentActivityTracker.reset()
        ExecutionTruthCoordinator.clearAllExecutions()
    }

    private fun submit(command: String) {
        rule.onNode(hasSetTextAction()).performTextInput(command)
        rule.onNodeWithContentDescription("Send").performClick()
    }

    private fun verified(command: String, action: String): ExecutionTruth {
        val previous = ExecutionTruthCoordinator.getLastExecution()?.executionId
        submit(command)
        rule.waitUntil(45_000) {
            ExecutionTruthCoordinator.getLastExecution()?.let { it.executionId != previous && it.actionName == action && it.finalSuccess } == true
        }
        val truth = ExecutionTruthCoordinator.getLastExecution()!!
        assertEquals("COMPLETED", truth.status)
        val task = runBlocking { AppDatabase.getDatabase(rule.activity).agentTaskDao().getTask(truth.taskId!!) }!!
        assertEquals("COMPLETED", task.status)
        rule.waitUntil(10_000) { !home.uiState.value.isThinking && !AgentActivityTracker.state.value.active && !TaskCancellationCoordinator.isTaskActive() }
        assertTrue("No final assistant response", home.uiState.value.messages.any { it.sender == "jarvis" && it.timestamp >= truth.timestamp })
        Log.i("MAXRegressionDevice", "command=$command taskId=${truth.taskId} stepId=${truth.stepId} executionId=${truth.executionId} executor=${truth.executorSuccess} verified=${truth.verifiedSuccess} status=${task.status} processing=${home.uiState.value.isThinking}")
        return truth
    }

    private fun returnHome() {
        rule.runOnUiThread {
            rule.activity.startActivity(Intent(rule.activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        rule.waitForIdle()
    }

    @Test fun flashlightOnAndOffAreActuallyObservedAndLeaveProcessing() {
        val camera = rule.activity.getSystemService(CameraManager::class.java)
        val states = ConcurrentHashMap<String, Boolean>()
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { states[cameraId] = enabled }
        }
        camera.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        try {
            val on = verified("Turn on flashlight", "FLASHLIGHT_ON")
            assertTrue("No device torch ON evidence", on.message?.contains("verified ON") == true)
            rule.waitUntil(5000) { states.values.any { it } }
            val off = verified("Turn off flashlight", "FLASHLIGHT_OFF")
            assertTrue("No device torch OFF evidence", off.message?.contains("verified OFF") == true)
            rule.waitUntil(5000) { states.isNotEmpty() && states.values.none { it } }
        } finally { camera.unregisterTorchCallback(callback) }
    }

    @Test fun youtubeAndWhatsAppActuallyOpenSequentiallyAndLeaveProcessing() {
        val youtube = verified("Open YouTube", "OPEN_APP")
        assertEquals("com.google.android.youtube", JarvisAccessibilityService.getActiveRoot()?.packageName?.toString())
        assertTrue(youtube.finalSuccess)
        returnHome()
        val whatsapp = verified("Open WhatsApp", "OPEN_APP")
        assertEquals("com.whatsapp", JarvisAccessibilityService.getActiveRoot()?.packageName?.toString())
        assertTrue(whatsapp.finalSuccess)
    }

    @Test fun requestedLongTaskVerifiesAllFourSteps() {
        submit("Open WhatsApp, wait 10 seconds, open YouTube, wait 10 seconds.")
        rule.waitUntil(10_000) { TaskCancellationCoordinator.getActiveTaskId() != null }
        val taskId = TaskCancellationCoordinator.getActiveTaskId()!!
        rule.waitUntil(60_000) {
            runBlocking { AppDatabase.getDatabase(rule.activity).agentTaskDao().getTask(taskId)?.status } == "COMPLETED"
        }
        val steps = runBlocking { AppDatabase.getDatabase(rule.activity).agentTaskDao().getSteps(taskId) }
        assertEquals(listOf("OPEN_APP", "WAIT", "OPEN_APP", "WAIT"), steps.map { it.actionType })
        assertTrue(steps.all { it.status == "SUCCEEDED" && it.verificationResult == "VERIFIED" })
        assertEquals("com.google.android.youtube", JarvisAccessibilityService.getActiveRoot()?.packageName?.toString())
        rule.waitUntil(10_000) { !home.uiState.value.isThinking && !TaskCancellationCoordinator.isTaskActive() }
        Log.i("MAXRegressionDevice", "long_task taskId=$taskId status=COMPLETED steps=${steps.size} processing=${home.uiState.value.isThinking}")
    }

    @Test fun realStopButtonDuringWaitCancelsAndNeverLaunchesYouTube() {
        submit("Open WhatsApp, wait 20 seconds, open YouTube.")
        rule.waitUntil(15_000) { AgentActivityTracker.state.value.currentAction == "WAIT" }
        val taskId = TaskCancellationCoordinator.getActiveTaskId()!!
        returnHome()
        // The production HomeScreen callback invokes HomeViewModel.cancelActiveTask.
        rule.onNodeWithContentDescription("Stop Task").performClick()
        rule.waitUntil(10_000) {
            runBlocking { AppDatabase.getDatabase(rule.activity).agentTaskDao().getTask(taskId)?.status } == "CANCELLED"
        }
        val before = runBlocking { AppDatabase.getDatabase(rule.activity).agentTaskDao().getSteps(taskId) }
        assertEquals("SUCCEEDED", before[0].status)
        assertEquals("CANCELLED", before[1].status)
        assertEquals("CANCELLED", before[2].status)
        assertNull("Future YouTube step received execution ID", before[2].executionId)
        rule.waitUntil(10_000) { !home.uiState.value.isThinking && !TaskCancellationCoordinator.isTaskActive() }
        // Observe beyond the originally requested WAIT to detect orphan continuation.
        val until = android.os.SystemClock.elapsedRealtime() + 22_000L
        while (android.os.SystemClock.elapsedRealtime() < until) {
            assertNotEquals("com.google.android.youtube", JarvisAccessibilityService.getActiveRoot()?.packageName?.toString())
            Thread.sleep(100L)
        }
        val after = runBlocking { AppDatabase.getDatabase(rule.activity).agentTaskDao().getSteps(taskId) }
        assertNull(after[2].executionId)
        assertEquals("CANCELLED", after[2].status)
        assertTrue(home.uiState.value.messages.any { it.sender == "jarvis" && it.text.contains("cancel", ignoreCase = true) })
        Log.i("MAXRegressionDevice", "stop_task taskId=$taskId status=CANCELLED futureExecutionId=${after[2].executionId} processing=${home.uiState.value.isThinking}")
    }
}
