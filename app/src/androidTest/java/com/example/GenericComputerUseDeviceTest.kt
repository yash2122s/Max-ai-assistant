package com.example

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.automation.computeruse.*
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class GenericComputerUseDeviceTest {

    private lateinit var context: Context
    private val TAG = "GenericComputerUseTest"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        JarvisAccessibilityService.testRootProvider = { uiAutomation.rootInActiveWindow }
        JarvisAccessibilityService.testWindowsProvider = { uiAutomation.windows }
        JarvisAccessibilityService.testShellExecutor = { cmd ->
            try {
                val pfd = uiAutomation.executeShellCommand(cmd)
                pfd?.let {
                    java.io.FileInputStream(it.fileDescriptor).use { stream ->
                        stream.readBytes()
                    }
                    it.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Test shell execution failed for '$cmd': ${e.message}")
            }
        }
        MaxRuntimeManager.enableMax(context)
        Log.d(TAG, "=== SetUp GenericComputerUseDeviceTest with UiAutomation bridge ===")
    }

    private fun resetToHomeScreen() {
        try {
            JarvisAccessibilityService.runShell("am force-stop com.google.android.youtube")
            JarvisAccessibilityService.runShell("am force-stop com.instagram.android")
            JarvisAccessibilityService.runShell("input keyevent 3")
            Thread.sleep(1200)
        } catch (e: Exception) {
            Log.w(TAG, "Clean slate reset failed: ${e.message}")
        }
    }

    /**
     * TEST 1: Baseline Positive Clean-Slate Test.
     * Starts from Home screen -> Launches YouTube -> Opens Search -> Types Query -> Submits Search -> Selects Result -> Verifies Destination.
     */
    @Test
    fun test_01_CleanSlate_YouTubeSearchAndVerify() {
        resetToHomeScreen()
        runBlocking {
            Log.d(TAG, ">>> [TEST 1] STARTING POSITIVE CLEAN-SLATE ACCEPTANCE TEST <<<")

            val goal = "Open YouTube. Search for Python automation tutorial. Inspect the current screen yourself, identify a relevant result, open it, and verify that the video page is open."
            val result = ComputerUseEngine.execute(
                context = context,
                goal = goal,
                targetApp = "YouTube",
                maxSteps = 8
            )

            Log.d(TAG, "[TEST 1] Result: success=${result.success}, steps=${result.stepsExecuted}, phases=${result.executedPhases}")

            // Log verified phases
            if (result.executedPhases.contains(GoalState.SEARCH_UI_OPEN)) Log.i(TAG, "[TEST] SEARCH_OPENED")
            if (result.executedPhases.contains(GoalState.QUERY_ENTERED)) Log.i(TAG, "[TEST] QUERY_TYPED")
            if (result.executedPhases.contains(GoalState.SEARCH_SUBMITTED)) Log.i(TAG, "[TEST] SEARCH_SUBMITTED")
            if (result.executedPhases.contains(GoalState.SEARCH_RESULTS_OBSERVED)) Log.i(TAG, "[TEST] SEARCH_RESULTS_OBSERVED")
            if (result.executedPhases.contains(GoalState.RESULT_SELECTED)) Log.i(TAG, "[TEST] RESULT_SELECTED")
            if (result.executedPhases.contains(GoalState.DESTINATION_VERIFIED)) Log.i(TAG, "[TEST] DESTINATION_VERIFIED")

            assertTrue("Clean-slate execution must succeed: ${result.message}", result.success)
            assertNotNull("Verified outcome must be present", result.verifiedOutcome)
            assertTrue("Must execute at least 3 steps", result.stepsExecuted >= 3)

            // Strict monotonic phase assertions
            assertTrue("Must contain APP_READY", result.executedPhases.contains(GoalState.APP_READY))
            assertTrue("Must contain SEARCH_UI_OPEN", result.executedPhases.contains(GoalState.SEARCH_UI_OPEN))
            assertTrue("Must contain QUERY_ENTERED", result.executedPhases.contains(GoalState.QUERY_ENTERED))
            assertTrue("Must contain SEARCH_SUBMITTED", result.executedPhases.contains(GoalState.SEARCH_SUBMITTED))
            assertTrue("Must contain SEARCH_RESULTS_OBSERVED", result.executedPhases.contains(GoalState.SEARCH_RESULTS_OBSERVED))
            assertTrue("Must contain RESULT_SELECTED", result.executedPhases.contains(GoalState.RESULT_SELECTED))
            assertTrue("Must contain DESTINATION_VERIFIED", result.executedPhases.contains(GoalState.DESTINATION_VERIFIED))

            Log.d(TAG, ">>> [TEST 1] PASSED SUCCESSFULLY <<<")
        }
    }

    /**
     * TEST 2: Smart Current-State Attachment (App Already in Foreground).
     * YouTube is already running in foreground -> Engine must attach directly to APP_READY without relaunching.
     */
    @Test
    fun test_02_SmartAttachment_AppAlreadyForeground() {
        runBlocking {
            Log.d(TAG, ">>> [TEST 2] STARTING SMART ATTACHMENT TEST (APP ALREADY FOREGROUND) <<<")

            // Ensure YouTube is in foreground at Home screen (navigate back from any playing video)
            JarvisAccessibilityService.runShell("input keyevent 4")
            delay(400)
            JarvisAccessibilityService.runShell("input keyevent 4")
            delay(400)
            JarvisAccessibilityService.runShell("am start -n com.google.android.youtube/com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity")
            delay(1500)

            val goal = "Open YouTube. Search for Python automation tutorial. Inspect the current screen yourself, identify a relevant result, open it, and verify that the video page is open."
            val result = ComputerUseEngine.execute(
                context = context,
                goal = goal,
                targetApp = "YouTube",
                maxSteps = 8
            )

            Log.d(TAG, "[TEST 2] Result: success=${result.success}, steps=${result.stepsExecuted}, phases=${result.executedPhases}")
            assertTrue("Must succeed with smart state attachment: ${result.message}", result.success)
            assertTrue("Must verify destination", result.executedPhases.contains(GoalState.DESTINATION_VERIFIED))

            Log.d(TAG, ">>> [TEST 2] PASSED SUCCESSFULLY <<<")
        }
    }

    /**
     * TEST 3: Smart State Attachment (Destination Already Open).
     * The video page is already active on screen -> Engine detects and verifies destination in 1 step!
     */
    @Test
    fun test_03_SmartAttachment_DestinationAlreadyOpen() {
        runBlocking {
            Log.d(TAG, ">>> [TEST 3] STARTING SMART ATTACHMENT TEST (DESTINATION ALREADY OPEN) <<<")

            // Current screen is already on the video page from previous test
            val goal = "Open YouTube. Search for Python automation tutorial. Inspect the current screen yourself, identify a relevant result, open it, and verify that the video page is open."
            val result = ComputerUseEngine.execute(
                context = context,
                goal = goal,
                targetApp = "YouTube",
                maxSteps = 5
            )

            Log.d(TAG, "[TEST 3] Result: success=${result.success}, steps=${result.stepsExecuted}, phases=${result.executedPhases}")
            assertTrue("Must succeed instantly on open destination: ${result.message}", result.success)
            assertEquals("Must complete in 1 step via state attachment", 1, result.stepsExecuted)
            assertTrue("Must contain DESTINATION_VERIFIED", result.executedPhases.contains(GoalState.DESTINATION_VERIFIED))

            Log.d(TAG, ">>> [TEST 3] PASSED SUCCESSFULLY <<<")
        }
    }

    /**
     * TEST 4: Security Policy - Shell Command Allowlist Enforcement.
     * Dangerous shell operations or injection must be strictly rejected.
     */
    @Test
    fun test_04_Security_ShellCommandPolicy_Rejection() {
        Log.d(TAG, ">>> [TEST 4] STARTING SHELL COMMAND POLICY SECURITY TEST <<<")

        assertFalse("rm -rf must be blocked", ShellCommandPolicy.isCommandAllowed("rm -rf /sdcard/test"))
        assertFalse("su must be blocked", ShellCommandPolicy.isCommandAllowed("su -c id"))
        assertFalse("reboot must be blocked", ShellCommandPolicy.isCommandAllowed("reboot"))
        assertFalse("shell injection must be blocked", ShellCommandPolicy.isCommandAllowed("input tap 100 100; rm -rf /"))
        assertFalse("pipe injection must be blocked", ShellCommandPolicy.isCommandAllowed("input tap 100 100 | sh"))
        assertTrue("input tap must be allowed", ShellCommandPolicy.isCommandAllowed("input tap 500 500"))
        assertTrue("input swipe must be allowed", ShellCommandPolicy.isCommandAllowed("input swipe 100 200 100 800 300"))
        assertTrue("input keyevent must be allowed", ShellCommandPolicy.isCommandAllowed("input keyevent 66"))
        assertTrue("screencap must be allowed", ShellCommandPolicy.isCommandAllowed("screencap -p /sdcard/obs.png"))

        Log.d(TAG, ">>> [TEST 4] PASSED SUCCESSFULLY <<<")
    }

    /**
     * TEST 5: Security Policy - Secure Field Protection.
     * Automated typing into password/PIN fields must be blocked.
     */
    @Test
    fun test_05_Security_SecureField_Blocked() {
        runBlocking {
            Log.d(TAG, ">>> [TEST 5] STARTING SECURE FIELD PROTECTION TEST <<<")

            val secureAction = ComputerAction.TypeText(
                text = "MySecretPassword123",
                targetField = "com.example.app:id/password_input_field"
            )
            val execResult = ComputerActionExecutor.execute(context, secureAction)

            assertFalse("Typing into secure field must fail", execResult.success)
            assertEquals("Must return SECURITY_BLOCKED error code", ComputerUseErrorCode.SECURITY_BLOCKED.name, execResult.errorCode)

            Log.d(TAG, ">>> [TEST 5] PASSED SUCCESSFULLY <<<")
        }
    }

    /**
     * TEST 6: Concurrency Control - Single Task Mutex.
     * A second concurrent task must be rejected while the lock is held.
     */
    @Test
    fun test_06_Concurrency_SingleTaskMutex() {
        runBlocking {
            Log.d(TAG, ">>> [TEST 6] STARTING CONCURRENCY MUTEX TEST <<<")

            val task1 = TaskContext(taskId = "task_primary", goal = "Test Task 1")
            val task2 = TaskContext(taskId = "task_secondary", goal = "Test Task 2")

            val acquired1 = ComputerUseConcurrency.tryAcquireLock(task1)
            assertTrue("Task 1 must acquire the lock", acquired1)

            val acquired2 = ComputerUseConcurrency.tryAcquireLock(task2)
            assertFalse("Task 2 must be blocked while Task 1 is running", acquired2)

            ComputerUseConcurrency.releaseLock(task1)
            val acquiredAfter = ComputerUseConcurrency.tryAcquireLock(task2)
            assertTrue("Task 2 can acquire lock after Task 1 releases it", acquiredAfter)
            ComputerUseConcurrency.releaseLock(task2)

            Log.d(TAG, ">>> [TEST 6] PASSED SUCCESSFULLY <<<")
        }
    }

    /**
     * TEST 7: Cancellation - Cooperative Task Interruption.
     * Calling cancelActiveTask() cancels execution immediately.
     */
    @Test
    fun test_07_Cancellation_CooperativeStop() {
        runBlocking {
            Log.d(TAG, ">>> [TEST 7] STARTING CANCELLATION TEST <<<")

            val taskContext = TaskContext(taskId = "task_cancel_test", goal = "Open YouTube and wait", maxSteps = 10)
            ComputerUseConcurrency.tryAcquireLock(taskContext)

            // Trigger cancellation
            ComputerUseConcurrency.cancelActiveTask("User pressed STOP")
            assertTrue("Task must be marked cancelled", ComputerUseConcurrency.isTaskCancelled(taskContext))
            ComputerUseConcurrency.releaseLock(taskContext)

            Log.d(TAG, ">>> [TEST 7] PASSED SUCCESSFULLY <<<")
        }
    }

    /**
     * TEST 8: Anti-Hallucination & Negative Test - Destination Mismatch Fails Safely.
     * An arbitrary screen without video/destination indicators must never claim success.
     */
    @Test
    fun test_08_Negative_DestinationMismatch_FailsSafely() {
        resetToHomeScreen()
        runBlocking {
            Log.d(TAG, ">>> [TEST 8] STARTING NEGATIVE DESTINATION MISMATCH TEST <<<")

            // Empty home screen observation
            val emptyObs = ScreenObservation(
                observationId = "obs_launcher",
                foregroundPackage = "com.android.launcher3"
            )
            val destResult = DestinationVerifier.verify(emptyObs, "Python automation tutorial", "YouTube")

            assertFalse("DestinationVerifier must fail on launcher screen", destResult.verified)
            assertEquals("Status must be FAILED", EvidenceStatus.FAILED, destResult.status)

            Log.d(TAG, ">>> [TEST 8] PASSED SUCCESSFULLY <<<")
        }
    }

    /**
     * TEST 9: Telugu Command 1 - "MAX, Instagram open cheyyi."
     * Must execute LaunchApp, observe live Instagram UI, and produce VERIFIED_SUCCESS.
     */
    @Test
    fun test_09_Telugu_OpenInstagram_Plain() {
        resetToHomeScreen()
        runBlocking {
            Log.d(TAG, ">>> [TEST 9] STARTING TELUGU OPEN INSTAGRAM PLAIN TEST <<<")

            val goal = "MAX, Instagram open cheyyi."
            val result = ComputerUseEngine.execute(
                context = context,
                goal = goal,
                maxSteps = 5
            )

            Log.d(TAG, "[TEST 9] Result: success=${result.success}, status=${result.taskStatus}, message='${result.message}', phases=${result.executedPhases}")
            assertTrue("Plain Instagram launch must succeed: ${result.message}", result.success)
            assertEquals("TaskStatus must be VERIFIED_SUCCESS", TaskStatus.VERIFIED_SUCCESS, result.taskStatus)
            assertEquals("Message must be exact verified Telugu message", "Instagram open ayyindi. Verify chesanu.", result.message)
            assertTrue("Must contain APP_READY", result.executedPhases.contains(GoalState.APP_READY))
            assertTrue("Must contain DESTINATION_VERIFIED", result.executedPhases.contains(GoalState.DESTINATION_VERIFIED))

            Log.d(TAG, ">>> [TEST 9] PASSED SUCCESSFULLY <<<")
        }
    }

    /**
     * TEST 10: Telugu Command 2 - "MAX, Instagram open cheyyi. Open ayindho verify chesi naaku cheppu."
     * Must execute LaunchApp, capture screen observation, verify Instagram package + accessibility, and produce VERIFIED_SUCCESS.
     */
    @Test
    fun test_10_Telugu_OpenInstagram_WithVerifyRequest() {
        resetToHomeScreen()
        runBlocking {
            Log.d(TAG, ">>> [TEST 10] STARTING TELUGU OPEN INSTAGRAM WITH EXPLICIT VERIFY REQUEST <<<")

            val goal = "MAX, Instagram open cheyyi. Open ayindho verify chesi naaku cheppu."
            val result = ComputerUseEngine.execute(
                context = context,
                goal = goal,
                maxSteps = 5
            )

            Log.d(TAG, "[TEST 10] Result: success=${result.success}, status=${result.taskStatus}, message='${result.message}', phases=${result.executedPhases}")
            assertTrue("Instagram launch and verify must succeed: ${result.message}", result.success)
            assertEquals("TaskStatus must be VERIFIED_SUCCESS", TaskStatus.VERIFIED_SUCCESS, result.taskStatus)
            assertEquals("Message must be exact verified Telugu message", "Instagram open ayyindi. Verify chesanu.", result.message)
            assertTrue("Must contain APP_READY", result.executedPhases.contains(GoalState.APP_READY))
            assertTrue("Must contain DESTINATION_VERIFIED", result.executedPhases.contains(GoalState.DESTINATION_VERIFIED))

            Log.d(TAG, ">>> [TEST 10] PASSED SUCCESSFULLY <<<")
        }
    }
}
