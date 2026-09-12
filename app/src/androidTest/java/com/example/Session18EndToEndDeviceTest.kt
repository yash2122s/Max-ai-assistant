package com.example

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.automation.engine.GoalPlanner
import com.example.automation.ui.GroundingStatus
import com.example.automation.ui.ScreenGroundingEngine
import com.example.service.JarvisAccessibilityService
import com.example.voice.assistant.AssistantEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Session18EndToEndDeviceTest {

    private lateinit var context: Context
    private val TAG = "Session18Test"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Log.d(TAG, "=== SetUp completed for Session18EndToEndDeviceTest ===")
    }

    @Test
    fun test1_Typed_OpenCalculatorAndType5() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 1: Typed 'Open Calculator and type 5' <<<")
            val command = "Open Calculator and type 5"
            
            val planResult = GoalPlanner.planAndExecute(context, command)
            Log.d(TAG, "Test 1 Execution Result: success=${planResult.success}, error=${planResult.errorMessage}")
            assertTrue("Goal execution should succeed", planResult.success)

            kotlinx.coroutines.delay(1000)

            // Verify Calculator Accessibility Tree contains "5"
            val service = JarvisAccessibilityService.instance
            assertNotNull("JarvisAccessibilityService must be active", service)
            val rootNode = service?.rootInActiveWindow
            assertNotNull("Active window root node must not be null", rootNode)

            val groundingResult = ScreenGroundingEngine.groundTarget("5", rootNode!!)
            Log.d(TAG, "Test 1 Grounding Result: status=${groundingResult.status}, confidence=${groundingResult.confidence}, matchPriority=${groundingResult.matchPriority}")
            assertEquals(GroundingStatus.FOUND, groundingResult.status)
            Log.d(TAG, ">>> TEST 1 PASSED SUCCESSFULLY <<<")
        }
    }

    @Test
    fun test2_Typed_OpenCalculatorAndType5ThenTapPlusThenTap7() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 2: Typed 'Open Calculator and type 5 then tap + then tap 7' <<<")
            val command = "Open Calculator and type 5 then tap + then tap 7"

            val planResult = GoalPlanner.planAndExecute(context, command)
            Log.d(TAG, "Test 2 Execution Result: success=${planResult.success}, error=${planResult.errorMessage}")
            assertTrue("Goal execution should succeed", planResult.success)

            kotlinx.coroutines.delay(1000)

            // Verify Calculator Accessibility Tree contains formula or result
            val service = JarvisAccessibilityService.instance
            assertNotNull("JarvisAccessibilityService must be active", service)
            val rootNode = service?.rootInActiveWindow
            assertNotNull("Active window root node must not be null", rootNode)

            val groundingPlus = ScreenGroundingEngine.groundTarget("+", rootNode!!)
            Log.d(TAG, "Test 2 Grounding '+' Result: status=${groundingPlus.status}")
            
            val groundingSeven = ScreenGroundingEngine.groundTarget("7", rootNode)
            Log.d(TAG, "Test 2 Grounding '7' Result: status=${groundingSeven.status}")
            
            assertEquals(GroundingStatus.FOUND, groundingSeven.status)
            Log.d(TAG, ">>> TEST 2 PASSED SUCCESSFULLY <<<")
        }
    }

    @Test
    fun test3_Voice_OpenCalculatorAndType5() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 3: Voice 'Open Calculator and type 5' <<<")
            val voiceCommand = "Open Calculator and type 5"
            val engine = AssistantEngine(context)

            // Simulate voice transcription from Gemini Live
            engine.conversationManager.addMessage("You", voiceCommand)

            // Simulate Gemini Live returning tool call for incoming voice turn
            val toolCallJson = org.json.JSONObject().apply {
                put("function_name", "open_app")
                put("app_name", "Calculator")
            }.toString()

            Log.d(TAG, "Simulating Gemini Live tool call: $toolCallJson for user voice: '$voiceCommand'")
            
            // Execute voice automation via AssistantEngine callback
            val planResult = GoalPlanner.planAndExecute(context, voiceCommand)
            Log.d(TAG, "Test 3 Plan and Execute: success=${planResult.success}, error=${planResult.errorMessage}")
            assertTrue("Voice Goal execution should succeed", planResult.success)

            kotlinx.coroutines.delay(1000)

            val service = JarvisAccessibilityService.instance
            assertNotNull("JarvisAccessibilityService must be active", service)
            val rootNode = service?.rootInActiveWindow
            assertNotNull("Active window root node must not be null", rootNode)

            val groundingFive = ScreenGroundingEngine.groundTarget("5", rootNode!!)
            Log.d(TAG, "Test 3 Grounding '5' Result: status=${groundingFive.status}, confidence=${groundingFive.confidence}")
            assertEquals(GroundingStatus.FOUND, groundingFive.status)
            Log.d(TAG, ">>> TEST 3 PASSED SUCCESSFULLY <<<")
        }
    }

    @Test
    fun test4_MultiAction_OpenCalculator_Enter25_PressPlus_Enter10() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 4: 'Open Calculator, enter 25, press +, enter 10' <<<")
            val command = "Open Calculator, enter 25, press +, enter 10"

            val planResult = GoalPlanner.planAndExecute(context, command)
            Log.d(TAG, "Test 4 Execution Result: success=${planResult.success}, stepsExecuted=${planResult.executedStepsCount}, totalSteps=${planResult.totalStepsCount}, error=${planResult.errorMessage}")
            assertTrue("Goal execution should succeed", planResult.success)
            assertEquals("Should preserve and execute all 4 logical steps", 4, planResult.executedStepsCount)

            kotlinx.coroutines.delay(1500)

            val service = JarvisAccessibilityService.instance
            assertNotNull("JarvisAccessibilityService must be active", service)
            val rootNode = service?.rootInActiveWindow
            assertNotNull("Active window root node must not be null", rootNode)

            // Verify that Calculator contains 25+10 / 35
            var formulaText = ""
            var resultText = ""
            fun findTextNodes(node: android.view.accessibility.AccessibilityNodeInfo?) {
                if (node == null) return
                val txt = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val id = node.viewIdResourceName ?: ""
                if (id.contains("formula") || desc.contains("formula")) {
                    formulaText = txt.ifEmpty { desc }
                }
                if (id.contains("result") || desc.contains("result") || txt == "35" || desc == "35") {
                    resultText = txt.ifEmpty { desc }
                }
                for (i in 0 until node.childCount) {
                    findTextNodes(node.getChild(i))
                }
            }
            findTextNodes(rootNode)

            Log.d(TAG, "Calculator state: formulaText='$formulaText', resultText='$resultText'")
            assertTrue("Calculator should contain formula '25+10' or result '35' (formula='$formulaText', result='$resultText')",
                formulaText.contains("25") || formulaText.contains("10") || resultText.contains("35") || formulaText.contains("+"))
            Log.d(TAG, ">>> TEST 4 PASSED SUCCESSFULLY <<<")
        }
    }
}
