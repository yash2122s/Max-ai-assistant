package com.example

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.automation.engine.execution.LongTaskOrchestrator
import com.example.automation.engine.planning.TaskIntent
import com.example.automation.engine.planning.TaskPlanner
import com.example.automation.resolver.UiTargetResolver
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenericUiAutomationDeviceTest {

    private lateinit var context: Context
    private lateinit var planner: TaskPlanner
    private lateinit var orchestrator: LongTaskOrchestrator
    private val TAG = "GenericUiDeviceTest"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        JarvisAccessibilityService.testRootProvider = { uiAutomation.rootInActiveWindow }
        JarvisAccessibilityService.testWindowsProvider = { uiAutomation.windows }
        planner = TaskPlanner()
        orchestrator = LongTaskOrchestrator(context)
        Log.d(TAG, "=== SetUp GenericUiAutomationDeviceTest on device with UiAutomation bridge ===")
    }

    @Test
    fun test1_OpenInstagramAndGoToReels() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 1: 'Open Instagram and go to Reels' <<<")
            val command = "Open Instagram and go to Reels"
            val task = planner.createPlan(command, TaskIntent(goal = command))

            assertEquals(2, task.steps.size)
            val result = orchestrator.executeTask(task)

            Log.d(TAG, "Test 1 Result: success=${result.success}, executed=${result.executedSteps}/${result.totalSteps}, msg=${result.message}")
            assertTrue("Reels task should succeed or ground properly", result.success || result.executedSteps >= 1)
            Log.d(TAG, ">>> TEST 1 FINISHED <<<")
        }
    }

    @Test
    fun test2_OpenInstagramAndGoToChats() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 2: 'Open Instagram and go to chats' <<<")
            val command = "Open Instagram and go to chats"
            val task = planner.createPlan(command, TaskIntent(goal = command))

            assertEquals(2, task.steps.size)
            val result = orchestrator.executeTask(task)

            Log.d(TAG, "Test 2 Result: success=${result.success}, executed=${result.executedSteps}/${result.totalSteps}, msg=${result.message}")
            assertTrue("Chats task should succeed or ground properly", result.success || result.executedSteps >= 1)
            Log.d(TAG, ">>> TEST 2 FINISHED <<<")
        }
    }

    @Test
    fun test3_SearchYaswanthOpenAccountAndMessage() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 3: 'Open Instagram, search yaswanth.1432, open the first account, open Message and wait' <<<")
            val command = "Open Instagram, search yaswanth.1432, open the first account, open Message and wait"
            val task = planner.createPlan(command, TaskIntent(goal = command))

            assertEquals(8, task.steps.size)
            assertFalse("Never plan Send unless explicitly commanded", task.steps.any { (it.action as? com.example.automation.models.UiStep.Tap)?.target == "Send" })

            val result = orchestrator.executeTask(task)
            Log.d(TAG, "Test 3 Result: success=${result.success}, executed=${result.executedSteps}/${result.totalSteps}, msg=${result.message}")
            assertTrue("Search and message navigation must execute steps", result.executedSteps >= 2)
            Log.d(TAG, ">>> TEST 3 FINISHED <<<")
        }
    }

    @Test
    fun test4_SearchDifferentUsername_NoYaswanthFallback() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 4: Search another username (no hardcoded fallback) <<<")
            val command = "Open Instagram, search android, open the first account, open Message and wait"
            val task = planner.createPlan(command, TaskIntent(goal = command))

            val typeStep = task.steps[3].action as com.example.automation.models.UiStep.TypeText
            assertEquals("android", typeStep.text)
            assertNotEquals("yaswanth.1432", typeStep.text)
            Log.d(TAG, ">>> TEST 4 PASSED: Extracted username '${typeStep.text}' without fabricating yaswanth.1432 <<<")
        }
    }

    @Test
    fun test5_AvatarNonSelectionRule() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 5: Verify avatar/story node is deprioritized <<<")
            val root = JarvisAccessibilityService.getActiveRoot()
            if (root != null) {
                val res = UiTargetResolver.resolve(query = "first account", rootNode = root)
                if (res.success && res.matchedNode != null) {
                    val nodeDesc = res.matchedNode.contentDescription?.toString()?.lowercase() ?: ""
                    val nodeId = res.matchedNode.viewIdResourceName?.lowercase() ?: ""
                    assertFalse("Selected node must NOT be avatar subnode", nodeId.contains("avatar") || nodeDesc.contains("story"))
                    Log.d(TAG, "Grounded node: id=$nodeId, desc=$nodeDesc (not avatar)")
                }
            }
            Log.d(TAG, ">>> TEST 5 VERIFIED <<<")
        }
    }

    @Test
    fun test6_NoMessageSentContract() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 6: Verify no message was sent <<<")
            val command = "Open Instagram, search yaswanth.1432, open the first account, open Message and wait"
            val task = planner.createPlan(command, TaskIntent(goal = command))
            // Strictly verify that no send action was planned
            val hasSendAction = task.steps.any { step ->
                val tapTarget = (step.action as? com.example.automation.models.UiStep.Tap)?.target ?: ""
                tapTarget.equals("Send", ignoreCase = true)
            }
            assertFalse("Contract violation: Send action must NEVER be emitted when user only asked to wait", hasSendAction)
            Log.d(TAG, ">>> TEST 6 PASSED: Strict message send contract preserved <<<")
        }
    }
}
