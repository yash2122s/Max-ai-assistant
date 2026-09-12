package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ActionAuthorizer
import com.example.automation.engine.OfflineCommandEngine
import com.example.automation.engine.planning.TaskIntent
import com.example.automation.engine.planning.TaskPlanner
import com.example.automation.models.UiStep
import com.example.network.FunctionCall
import com.example.network.ToolDispatcher
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IntentRoutingGuardUnitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testWhatsAppMessageWithCallPhrase_DoesNotAuthorizeCallPhone() {
        val utterance = "Send WhatsApp message to Yash that I am in a meeting and will call back later"
        
        // 1. Authorizer must REJECT CALL_PHONE
        val callAuth = ActionAuthorizer.authorizeAction(utterance, "CALL_PHONE")
        assertFalse("CALL_PHONE must be rejected when phrase is inside a message body", callAuth.authorized)
        assertTrue(callAuth.reason.contains("messaging command"))

        // 2. Authorizer must APPROVE WhatsApp messaging
        val waAuth = ActionAuthorizer.authorizeAction(utterance, "SEND_WHATSAPP")
        assertTrue("SEND_WHATSAPP must be authorized", waAuth.authorized)
    }

    @Test
    fun testExplicitCallCommand_AuthorizesCallPhone() {
        val callUtterance = "Call Yash"
        val callAuth = ActionAuthorizer.authorizeAction(callUtterance, "CALL_PHONE")
        assertTrue("Direct 'Call Yash' must authorize CALL_PHONE", callAuth.authorized)

        val waCallUtterance = "Call Yash on WhatsApp"
        val waCallAuth = ActionAuthorizer.authorizeAction(waCallUtterance, "CALL_PHONE")
        assertTrue("Direct 'Call Yash on WhatsApp' must authorize CALL_PHONE", waCallAuth.authorized)
    }

    @Test
    fun testToolDispatcher_ReroutesSpuriousCallContactToWhatsAppTaskIntent() {
        val utterance = "Send WhatsApp message to Yash that I am in a meeting and will call back later"
        val dummyCall = FunctionCall(
            name = "call_contact",
            args = null,
            id = "call_123"
        )

        val dispatchedJsonStr = ToolDispatcher.dispatch(dummyCall, utterance)
        val json = JSONObject(dispatchedJsonStr)

        assertEquals("TASK_INTENT", json.optString("action"))
        assertEquals("send_whatsapp_message", json.optString("goal"))
        assertEquals("WhatsApp", json.optString("target_entity"))
    }

    @Test
    fun testToolDispatcher_PassesLegitimateCallContactThrough() {
        val utterance = "Call John"
        val callArgs = com.google.gson.JsonObject().apply {
            addProperty("contactId", 123L)
            addProperty("phoneId", 456L)
        }
        val dummyCall = FunctionCall(
            name = "call_contact",
            args = callArgs,
            id = "call_999"
        )

        val dispatchedJsonStr = ToolDispatcher.dispatch(dummyCall, utterance)
        val json = JSONObject(dispatchedJsonStr)

        assertEquals("CALL_PHONE", json.optString("action"))
        assertEquals(123L, json.optLong("contactId"))
        assertEquals(456L, json.optLong("phoneId"))
    }

    @Test
    fun testTaskPlanner_CreatesExact6StepWhatsAppDag_ZeroCallSteps() {
        val planner = TaskPlanner()
        val utterance = "Send WhatsApp message to Yash that I am in a meeting and will call back later"
        val intent = TaskIntent(
            goal = "send_whatsapp_message",
            targetEntity = "com.whatsapp",
            requiredOutcome = "MessageSent",
            parameters = emptyMap()
        )

        val plan = planner.createPlan(utterance, intent)
        assertEquals(6, plan.steps.size)

        // Step 1: OpenApp
        val s1 = plan.steps[0].action as UiStep.OpenApp
        assertEquals("com.whatsapp", s1.appName)

        // Step 2: Tap Search
        val s2 = plan.steps[1].action as UiStep.Tap
        assertEquals("Search", s2.target)

        // Step 3: TypeText Contact Name
        val s3 = plan.steps[2].action as UiStep.TypeText
        assertEquals("Yash", s3.text)

        // Step 4: Tap Contact
        val s4 = plan.steps[3].action as UiStep.Tap
        assertEquals("Yash", s4.target)

        // Step 5: TypeText Message Body
        val s5 = plan.steps[4].action as UiStep.TypeText
        assertEquals("I am in a meeting and will call back later", s5.text)

        // Step 6: Tap Send
        val s6 = plan.steps[5].action as UiStep.Tap
        assertEquals("Send", s6.target)
    }

    @Test
    fun testOfflineCommandEngine_ParsesWhatsAppMessageBeforePhoneCall() = runBlocking {
        val utterance = "Send WhatsApp message to Yash that I am in a meeting and will call back later"
        val result = OfflineCommandEngine.executeIfMatched(context, utterance)
        
        assertNotNull("OfflineCommandEngine must match WhatsApp command", result)
        assertEquals("send_whatsapp_message", result?.toolName)
    }

    @Test
    fun testYaswanthWhatsAppMessageDictation_CreatesCorrectPlan() {
        val planner = TaskPlanner()
        val utterance = "Send WhatsApp message to yaswanth that I am in a meeting and will call back later"
        val intent = TaskIntent(
            goal = "send_whatsapp_message",
            targetEntity = "com.whatsapp",
            requiredOutcome = "MessageSent",
            parameters = emptyMap()
        )

        val plan = planner.createPlan(utterance, intent)
        assertEquals(6, plan.steps.size)

        val s3 = plan.steps[2].action as UiStep.TypeText
        assertEquals("yaswanth", s3.text)

        val s4 = plan.steps[3].action as UiStep.Tap
        assertEquals("yaswanth", s4.target)

        val s5 = plan.steps[4].action as UiStep.TypeText
        assertEquals("I am in a meeting and will call back later", s5.text)
    }
}
