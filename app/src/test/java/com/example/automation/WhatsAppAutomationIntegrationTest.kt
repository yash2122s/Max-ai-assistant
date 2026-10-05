package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ActionAuthorizer
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.OfflineCommandEngine
import com.example.automation.tools.WhatsAppTool
import com.example.network.FunctionCall
import com.example.network.ToolDispatcher
import com.google.gson.JsonObject
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
class WhatsAppAutomationIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testToolDispatcher_supportedTools_includesWhatsAppActions() {
        assertTrue(ToolDispatcher.supportedTools.contains("send_whatsapp_message"))
        assertTrue(ToolDispatcher.supportedTools.contains("open_whatsapp_chat"))
        assertTrue(ToolDispatcher.supportedTools.contains("navigate_whatsapp"))
    }

    @Test
    fun testToolDispatcher_dispatchesOpenWhatsAppChat() {
        val args = JsonObject().apply {
            addProperty("contact", "Ravi")
            addProperty("message", "")
        }
        val fc = FunctionCall(
            name = "open_whatsapp_chat",
            args = args
        )
        val jsonStr = ToolDispatcher.dispatch(fc, "open ravi chat on whatsapp")
        val obj = JSONObject(jsonStr)

        assertEquals("OPEN_WHATSAPP_CHAT", obj.getString("action"))
        assertEquals("Ravi", obj.getString("contact"))
    }

    @Test
    fun testToolDispatcher_dispatchesNavigateWhatsApp() {
        val args = JsonObject().apply {
            addProperty("screen", "status")
        }
        val fc = FunctionCall(
            name = "navigate_whatsapp",
            args = args
        )
        val jsonStr = ToolDispatcher.dispatch(fc, "whatsapp status ki vellu")
        val obj = JSONObject(jsonStr)

        assertEquals("NAVIGATE_WHATSAPP", obj.getString("action"))
        assertEquals("status", obj.getString("screen"))
    }

    @Test
    fun testToolDispatcher_normalizesOpenAppToWhatsAppNavigation() {
        val args = JsonObject().apply {
            addProperty("app_name", "whatsapp status")
        }
        val fc = FunctionCall(
            name = "open_app",
            args = args
        )
        val jsonStr = ToolDispatcher.dispatch(fc, "whatsapp status open chey")
        val obj = JSONObject(jsonStr)

        assertEquals("NAVIGATE_WHATSAPP", obj.getString("action"))
        assertEquals("status", obj.getString("screen"))
    }

    @Test
    fun testActionAuthorizer_authorizesNavigateWhatsApp() {
        val intent1 = "whatsapp status ki vellu"
        assertTrue(ActionAuthorizer.authorizeAction(intent1, "NAVIGATE_WHATSAPP", "status").authorized)

        val intent2 = "వాట్సాప్ స్టేటస్ ఓపెన్ చెయ్"
        assertTrue(ActionAuthorizer.authorizeAction(intent2, "NAVIGATE_WHATSAPP", "status").authorized)

        val intent3 = "whatsapp lo calls screen open chey"
        assertTrue(ActionAuthorizer.authorizeAction(intent3, "NAVIGATE_WHATSAPP", "calls").authorized)

        // General open whatsapp should not authorize specific screen navigation
        val intent4 = "Open WhatsApp."
        assertFalse(ActionAuthorizer.authorizeAction(intent4, "NAVIGATE_WHATSAPP", "status").authorized)
    }

    @Test
    fun testActionAuthorizer_authorizesOpenWhatsAppChat() {
        val intent1 = "whatsapp lo ravi chat open chey"
        assertTrue(ActionAuthorizer.authorizeAction(intent1, "OPEN_WHATSAPP_CHAT", "ravi").authorized)

        val intent2 = "open chat with Yash on whatsapp"
        assertTrue(ActionAuthorizer.authorizeAction(intent2, "OPEN_WHATSAPP_CHAT", "Yash").authorized)

        // Pure conversation should not authorize opening chat
        val intent3 = "how are you today?"
        assertFalse(ActionAuthorizer.authorizeAction(intent3, "OPEN_WHATSAPP_CHAT", "ravi").authorized)
    }

    @Test
    fun testActionAuthorizer_isPureConversation_DoesNotBlockTeluguCommandsWithLoveOrHello() {
        // "i love you" inside a message payload must not cause the command to be classified as pure conversation
        val intent1 = "whatsapp lo ravi ki i love you ani message pampu"
        assertFalse(ActionAuthorizer.isPureConversation(intent1))
        assertTrue(ActionAuthorizer.authorizeAction(intent1, "SEND_WHATSAPP_MESSAGE", "ravi").authorized)

        // "hello" inside a message payload
        val intent2 = "whatsapp lo ravi ki hello ani cheppu"
        assertFalse(ActionAuthorizer.isPureConversation(intent2))
        assertTrue(ActionAuthorizer.authorizeAction(intent2, "SEND_WHATSAPP_MESSAGE", "ravi").authorized)

        // "repu kaluddam" with pettu
        val intent3 = "whatsapp lo ravi ki repu kaluddam ani pettu"
        assertFalse(ActionAuthorizer.isPureConversation(intent3))
        assertTrue(ActionAuthorizer.authorizeAction(intent3, "SEND_WHATSAPP_MESSAGE", "ravi").authorized)
    }

    @Test
    fun testWhatsAppTool_validatesNavigateAndChatActions() {
        val tool = WhatsAppTool()

        // Valid screen navigation
        val validNavArgs = JsonObject().apply { addProperty("screen", "status") }
        assertTrue(tool.validate(ExecutionRequest(action = "NAVIGATE_WHATSAPP", arguments = validNavArgs)))

        // Missing screen
        val invalidNavArgs = JsonObject()
        assertFalse(tool.validate(ExecutionRequest(action = "NAVIGATE_WHATSAPP", arguments = invalidNavArgs)))

        // Valid open chat
        val validChatArgs = JsonObject().apply { addProperty("contact", "Ravi") }
        assertTrue(tool.validate(ExecutionRequest(action = "OPEN_WHATSAPP_CHAT", arguments = validChatArgs)))

        // Missing contact
        val invalidChatArgs = JsonObject()
        assertFalse(tool.validate(ExecutionRequest(action = "OPEN_WHATSAPP_CHAT", arguments = invalidChatArgs)))
    }

    @Test
    fun testOfflineCommandEngine_MatchesWhatsAppNavigation_Chat_And_Messaging() = runBlocking {
        // Navigation matching
        val navResult = OfflineCommandEngine.executeIfMatched(context, "whatsapp lo status ki vellu")
        assertNotNull("OfflineCommandEngine should match WhatsApp status navigation", navResult)

        val navResult2 = OfflineCommandEngine.executeIfMatched(context, "వాట్సాప్ స్టేటస్ ఓపెన్ చెయ్")
        assertNotNull("OfflineCommandEngine should match Telugu status navigation", navResult2)

        // Open chat matching
        val chatResult = OfflineCommandEngine.executeIfMatched(context, "whatsapp lo ravi chat open chey")
        assertNotNull("OfflineCommandEngine should match WhatsApp chat opening", chatResult)

        // Send message matching
        val msgResult1 = OfflineCommandEngine.executeIfMatched(context, "whatsapp lo ravi ki hello ani message pampu")
        assertNotNull("OfflineCommandEngine should match WhatsApp messaging with pampu", msgResult1)

        val msgResult2 = OfflineCommandEngine.executeIfMatched(context, "whatsapp lo ravi ki repu kaluddam ani rayi")
        assertNotNull("OfflineCommandEngine should match WhatsApp messaging with rayi", msgResult2)

        val msgResult3 = OfflineCommandEngine.executeIfMatched(context, "whatsapp lo ravi ki good morning pettu")
        assertNotNull("OfflineCommandEngine should match WhatsApp messaging with pettu", msgResult3)
    }
}
