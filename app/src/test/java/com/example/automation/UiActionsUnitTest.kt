package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ActionDispatcher
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ClickUiTool
import com.example.automation.tools.PressEnterTool
import com.example.automation.tools.TypeTextTool
import com.google.gson.JsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UiActionsUnitTest {

    private lateinit var context: Context
    private lateinit var clickTool: ClickUiTool
    private lateinit var typeTool: TypeTextTool
    private lateinit var enterTool: PressEnterTool

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clickTool = ClickUiTool()
        typeTool = TypeTextTool()
        enterTool = PressEnterTool()
    }

    @Test
    fun testActionDispatcherNormalization() {
        val tapJson = ActionDispatcher.normalizeJson(JSONObject().apply { put("action", "TAP"); put("target", "Search") })
        assertEquals("CLICK_ELEMENT", tapJson.getString("action"))

        val typeJson = ActionDispatcher.normalizeJson(JSONObject().apply { put("action", "TYPE"); put("text", "Palakollu") })
        assertEquals("TYPE_TEXT", typeJson.getString("action"))

        val enterJson = ActionDispatcher.normalizeJson(JSONObject().apply { put("action", "ENTER") })
        assertEquals("PRESS_ENTER", enterJson.getString("action"))

        val scrollJson = ActionDispatcher.normalizeJson(JSONObject().apply { put("action", "SCROLL"); put("direction", "down") })
        assertEquals("SCROLL_SCREEN", scrollJson.getString("action"))
    }

    @Test
    fun testClickToolValidation() {
        // Valid by target text
        val validRequest = ExecutionRequest(
            action = "TAP",
            arguments = JsonObject().apply { addProperty("target", "Directions") }
        )
        assertTrue(clickTool.validate(validRequest))

        // Valid by resourceId
        val validResRequest = ExecutionRequest(
            action = "CLICK_ELEMENT",
            arguments = JsonObject().apply { addProperty("resourceId", "search_btn") }
        )
        assertTrue(clickTool.validate(validResRequest))

        // Invalid empty
        val invalidRequest = ExecutionRequest(
            action = "TAP",
            arguments = JsonObject()
        )
        assertFalse(clickTool.validate(invalidRequest))
    }

    @Test
    fun testTypeTextToolValidation() {
        // Valid text
        val validRequest = ExecutionRequest(
            action = "TYPE_TEXT",
            arguments = JsonObject().apply { addProperty("text", "Palakollu to Bhimavaram") }
        )
        assertTrue(typeTool.validate(validRequest))

        // Invalid empty
        val invalidRequest = ExecutionRequest(
            action = "TYPE_TEXT",
            arguments = JsonObject()
        )
        assertFalse(typeTool.validate(invalidRequest))
    }

    @Test
    fun testPressEnterToolValidation() {
        val request = ExecutionRequest(action = "PRESS_ENTER")
        assertTrue(enterTool.validate(request))
    }

    @Test
    fun testToolActionSets() {
        assertTrue(clickTool.supportedActions.contains("TAP"))
        assertTrue(clickTool.supportedActions.contains("CLICK_ELEMENT"))
        assertTrue(typeTool.supportedActions.contains("TYPE_TEXT"))
        assertTrue(enterTool.supportedActions.contains("PRESS_ENTER"))
    }
}
