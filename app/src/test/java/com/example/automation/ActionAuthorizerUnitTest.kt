package com.example.automation

import com.example.automation.engine.ActionAuthorizer
import com.example.automation.engine.GoalPlanner
import com.example.automation.state.ActionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActionAuthorizerUnitTest {

    @Test
    fun testTest1_HelloMax_BlocksAllActions() {
        val userIntent = "Hello MAX"
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "OPEN_APP", "YouTube").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "YOUTUBE_SEARCH", "video").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "SYSTEM_ACTION", "notifications").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "GET_SCREEN_CONTEXT", null).authorized)
    }

    @Test
    fun testTest2_TellMeAboutVegetables_BlocksAllActions() {
        val userIntent = "Tell me about vegetables."
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "YOUTUBE_SEARCH", "vegetables").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "OPEN_APP", "YouTube").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "SYSTEM_ACTION", "notifications").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "GET_SCREEN_CONTEXT", null).authorized)
    }

    @Test
    fun testTest3_Vegetable_AmbiguousBlocked() {
        val userIntent = "vegetable"
        assertTrue(GoalPlanner.isGoalAmbiguous(userIntent))
    }

    @Test
    fun testTest4_WhatIsOnMyScreen_AuthorizesScreenContextOnly() {
        val userIntent = "What is currently on my screen?"
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "GET_SCREEN_CONTEXT", null).authorized)
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "TAKE_SCREENSHOT", null).authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "OPEN_APP", "Settings").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "YOUTUBE_SEARCH", "video").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "SYSTEM_ACTION", "notifications").authorized)
    }

    @Test
    fun testTest5_OpenWhatsApp_AuthorizesOpenAppOnly() {
        val userIntent = "Open WhatsApp."
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "OPEN_APP", "WhatsApp").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "CLICK_ELEMENT", "Bhaskar").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "TYPE_TEXT", "Hello").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "SEND_WHATSAPP_MESSAGE", "Bhaskar").authorized)

        // Validate that GoalPlanner rejects unrequested subsequent steps in plan
        val steps = listOf(
            ActionStep(stepId = "s1", actionType = "OPEN_APP", targetText = "WhatsApp"),
            ActionStep(stepId = "s2", actionType = "CLICK_ELEMENT", targetText = "Bhaskar")
        )
        val err = GoalPlanner.validatePlan(steps, userIntent)
        assertNotNull(err)
        assertTrue(err!!.contains("unauthorized"))
    }

    @Test
    fun testTest6_SearchYouTubeForVegetable_AuthorizesYouTube() {
        val userIntent = "Search YouTube for vegetable."
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "YOUTUBE_SEARCH", "vegetable").authorized)
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "OPEN_APP", "YouTube").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "SYSTEM_ACTION", "notifications").authorized)
    }

    @Test
    fun testTest7_OpenNotificationPanel_AuthorizesNotifications() {
        val userIntent = "Open Notification Panel."
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "SYSTEM_ACTION", "notifications").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "SYSTEM_ACTION", "quick_settings").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "OPEN_APP", "WhatsApp").authorized)
    }

    @Test
    fun testTest8_SendHelloToBhaskarOnWhatsApp_AuthorizesAllSteps() {
        val userIntent = "Send Hello to Bhaskar on WhatsApp."
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "OPEN_APP", "WhatsApp").authorized)
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "CLICK_ELEMENT", "Bhaskar").authorized)
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "TYPE_TEXT", "Hello").authorized)
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "SEND_WHATSAPP_MESSAGE", "Bhaskar").authorized)

        val steps = listOf(
            ActionStep(stepId = "s1", actionType = "OPEN_APP", targetText = "WhatsApp"),
            ActionStep(stepId = "s2", actionType = "CLICK_ELEMENT", targetText = "Bhaskar"),
            ActionStep(stepId = "s3", actionType = "TYPE_TEXT", targetText = "Hello")
        )
        val err = GoalPlanner.validatePlan(steps, userIntent)
        assertNull(err)
    }

    @Test
    fun testTest9_WeatherToday_BlocksAllActions() {
        val userIntent = "What's the weather today?"
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "GET_SCREEN_CONTEXT", null).authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "SYSTEM_ACTION", "notifications").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "YOUTUBE_SEARCH", "weather").authorized)
        assertFalse(ActionAuthorizer.authorizeAction(userIntent, "OPEN_APP", "Weather").authorized)
    }

    @Test
    fun testTest10_OpenWhatsAppFindBhaskarSendHello_AuthorizesPackageAndAliases() {
        val userIntent = "Open WhatsApp, find Bhaskar, and send Hello."
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "OPEN_APP", "com.whatsapp").authorized)
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "OPEN_APP", "WhatsApp").authorized)
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "CLICK_ELEMENT", "Bhaskar").authorized)
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "FIND_CONTACT", "Bhaskar").authorized)
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "TYPE_TEXT", "Hello").authorized)
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "SEND_WHATSAPP", "Hello").authorized)
        assertTrue(ActionAuthorizer.authorizeAction(userIntent, "SEND_WHATSAPP_MESSAGE", "Hello").authorized)
    }

    @Test
    fun testTest11_NegativeCrossAppAuthorization() {
        assertFalse(ActionAuthorizer.authorizeAction("Open WhatsApp", "OPEN_APP", "com.android.settings").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("Open WhatsApp", "OPEN_APP", "Settings").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("Open Settings", "OPEN_APP", "com.whatsapp").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("Open Settings", "OPEN_APP", "WhatsApp").authorized)
    }

    @Test
    fun testSaveMemory_ExplicitEnglishPhrases() {
        assertTrue(ActionAuthorizer.authorizeAction("remember my name is Yashwanth", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("remember this", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("save this", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("save that", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("save it", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("save memory", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("store in memory", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("note down my address is Bangalore", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("note this for later", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("keep in mind my birthday is July 5", "SAVE_MEMORY").authorized)
    }

    @Test
    fun testSaveMemory_TanglishPhrases() {
        assertTrue(ActionAuthorizer.authorizeAction("save chey Yashwanth", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("save cheyi", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("save chesko", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("idi memory lo save chey", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("memory lo save chey", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("gurthupettuko", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("naa peru Yashwanth ani gurthupettuko", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("gurthunchuko", "SAVE_MEMORY").authorized)
    }

    @Test
    fun testSaveMemory_TeluguScriptPhrases() {
        assertTrue(ActionAuthorizer.authorizeAction("గుర్తుపెట్టుకో", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("దీన్ని గుర్తుపెట్టుకో", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("గుర్తుంచుకో", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("నోట్ చేసుకో", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("సేవ్ చేయి", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("సేవ్ చేసుకో", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("మెమరీలో సేవ్ చేయి", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("దీన్ని సేవ్ చేయి", "SAVE_MEMORY").authorized)
    }

    @Test
    fun testSaveMemory_OrdinaryStatements_Blocked() {
        assertFalse(ActionAuthorizer.authorizeAction("My name is Yashwanth", "SAVE_MEMORY").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("I am Yashwanth", "SAVE_MEMORY").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("Yashwanth", "SAVE_MEMORY").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("What is my name?", "SAVE_MEMORY").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("I like mangoes", "SAVE_MEMORY").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("I prefer Android", "SAVE_MEMORY").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("My favorite color is blue", "SAVE_MEMORY").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("My phone is Samsung", "SAVE_MEMORY").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("I live in Bhimavaram", "SAVE_MEMORY").authorized)
    }

    @Test
    fun testSaveMemory_UnrelatedSave_Blocked() {
        assertFalse(ActionAuthorizer.authorizeAction("save the file", "SAVE_MEMORY").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("save the screenshot", "SAVE_MEMORY").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("save this document", "SAVE_MEMORY").authorized)
        assertFalse(ActionAuthorizer.authorizeAction("save the document", "SAVE_MEMORY").authorized)
    }

    @Test
    fun testSaveMemory_PunctuationAndCaseNormalization() {
        assertTrue(ActionAuthorizer.authorizeAction("SAVE CHEY, Yashwanth!", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("  Remember  This...  ", "SAVE_MEMORY").authorized)
        assertTrue(ActionAuthorizer.authorizeAction("idi memory lo save chey.", "SAVE_MEMORY").authorized)
    }
}
