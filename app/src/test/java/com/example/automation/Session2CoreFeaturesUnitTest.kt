package com.example.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.engine.GoalPlanner
import com.example.automation.tools.TypeTextTool
import com.example.automation.ui.GroundingStatus
import com.example.automation.ui.ScreenGroundingEngine
import com.example.service.AccessibilityTreePruner
import com.example.service.IndexedUiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Session2CoreFeaturesUnitTest {

    // =========================================================================
    // FEATURE 1: INDEXED INTERACTIVE UI MAP TESTS
    // =========================================================================

    @Test
    fun testIndexedUiElement_metadataAndCompactFormatting() {
        val element = IndexedUiElement(
            index = 1,
            text = "Search",
            contentDescription = "Search here",
            resourceId = "com.google.android.apps.maps:id/search_box",
            className = "EditText",
            isClickable = true,
            isFocusable = true,
            isEditable = true,
            isEnabled = true,
            isVisibleToUser = true,
            bounds = Rect(64, 120, 1016, 240),
            role = "input"
        )

        assertEquals(1, element.index)
        assertEquals("Search", element.text)
        assertEquals("Search here", element.contentDescription)
        assertEquals("com.google.android.apps.maps:id/search_box", element.resourceId)
        assertEquals("EditText", element.className)
        assertTrue(element.isClickable)
        assertTrue(element.isEditable)
        assertTrue(element.isEnabled)
        assertTrue(element.isVisibleToUser)

        val compact = element.toCompactBlock()
        assertTrue(compact.contains("[1] Search"))
        assertTrue(compact.contains("text=\"Search\""))
        assertTrue(compact.contains("contentDescription=\"Search here\""))
        assertTrue(compact.contains("resourceId=\"com.google.android.apps.maps:id/search_box\""))
        assertTrue(compact.contains("class=\"EditText\""))
        assertTrue(compact.contains("clickable=true"))
        assertTrue(compact.contains("editable=true"))
        assertTrue(compact.contains("enabled=true"))
        assertTrue(compact.contains("visible=true"))
        assertTrue(compact.contains("bounds=[64,120,1016,240]"))

        val display = element.toDisplayString()
        assertTrue(display.startsWith("[1] \"Search\""))
        assertTrue(display.contains("id=search_box"))
        assertTrue(display.contains("clickable"))
        assertTrue(display.contains("input"))
    }

    @Test
    fun testIndexedUiElement_fallbackLabels() {
        val descOnly = IndexedUiElement(
            index = 2,
            contentDescription = "Profile Settings",
            isClickable = true,
            bounds = Rect(900, 120, 1000, 220)
        )
        assertTrue(descOnly.toCompactBlock().contains("[2] Profile Settings"))

        val idOnly = IndexedUiElement(
            index = 3,
            resourceId = "com.android.settings:id/wifi_toggle",
            isClickable = true,
            bounds = Rect(100, 200, 900, 300)
        )
        assertTrue(idOnly.toCompactBlock().contains("[3] wifi_toggle"))
    }

    // =========================================================================
    // FEATURE 2: SCREEN GROUNDING & CLICK DUAL-LAYER TESTS
    // =========================================================================

    @Test
    fun testTargetGrounding_emptyTargetReturnsNotFound() {
        val dummyNode = AccessibilityNodeInfo.obtain()
        dummyNode.className = "android.widget.FrameLayout"
        dummyNode.isVisibleToUser = true
        dummyNode.isEnabled = true
        dummyNode.setBoundsInScreen(Rect(0, 0, 1080, 1920))

        val result = ScreenGroundingEngine.groundTarget("", dummyNode)
        assertEquals(GroundingStatus.NOT_FOUND, result.status)
        dummyNode.recycle()
    }

    @Test
    fun testTargetAliases_mathematicalOperators() {
        val addGrounding = ScreenGroundingEngine.groundTarget("+", AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.Button"
            text = "+"
            isVisibleToUser = true
            isEnabled = true
            isClickable = true
            setBoundsInScreen(Rect(100, 100, 200, 200))
        })
        assertEquals(GroundingStatus.FOUND, addGrounding.status)
        assertEquals(1.0f, addGrounding.confidence, 0.01f)
    }

    // =========================================================================
    // FEATURE 3: FOCUS-FIRST REAL-TIME TYPING & VERIFICATION TESTS
    // =========================================================================

    @Test
    fun testTypeText_isTextMatch_replaceTrue() {
        val typeTool = TypeTextTool()
        // Exact match
        assertTrue(typeTool.isTextMatch("Palakollu", "Palakollu", replace = true))
        // Case-insensitive match
        assertTrue(typeTool.isTextMatch("palakollu", "Palakollu", replace = true))
        // Positive containment (e.g. prefix in search box like "Search Palakollu")
        assertTrue(typeTool.isTextMatch("Search Palakollu", "Palakollu", replace = true))
        // Negative mismatch
        assertFalse(typeTool.isTextMatch("Bhimavaram", "Palakollu", replace = true))
        // Blank actual vs non-blank expected
        assertFalse(typeTool.isTextMatch("", "Palakollu", replace = true))
    }

    @Test
    fun testTypeText_isTextMatch_replaceFalse_append() {
        val typeTool = TypeTextTool()
        // Combined original + expected
        assertTrue(typeTool.isTextMatch("Hello World", "World", replace = false, originalText = "Hello "))
        // Positive containment of appended text
        assertTrue(typeTool.isTextMatch("Hello World", "World", replace = false))
        // Mismatch
        assertFalse(typeTool.isTextMatch("Hello", "World", replace = false, originalText = "Hello"))
    }

    @Test
    fun testTypeText_securityDetectionKeyterms() {
        val typeTool = TypeTextTool()
        // Ensure standard validation succeeds on non-empty string
        val validRequest = com.example.automation.engine.ExecutionRequest(
            action = "TYPE_TEXT",
            arguments = com.google.gson.JsonObject().apply { addProperty("text", "Palakollu") }
        )
        assertTrue(typeTool.validate(validRequest))

        val invalidRequest = com.example.automation.engine.ExecutionRequest(
            action = "TYPE_TEXT",
            arguments = com.google.gson.JsonObject().apply { addProperty("text", "") }
        )
        assertFalse(typeTool.validate(invalidRequest))
    }

    // =========================================================================
    // REGRESSION: MULTI-STEP WORKFLOW PARITY & FAST-PATH
    // =========================================================================

    @Test
    fun testAppSearchWorkflow_mapsSearchPlanGeneration() {
        val parsed = com.example.automation.engine.SemanticUiCommandParser.parse("Google maps open chesi Palakollu ane search chey")
        assertNotNull(parsed)
        assertEquals(4, parsed?.actions?.size)
        assertEquals("OPEN_APP", parsed?.actions?.get(0)?.action)
        assertEquals("CLICK", parsed?.actions?.get(1)?.action)
        assertEquals("Search", parsed?.actions?.get(1)?.target)
        assertEquals("TYPE", parsed?.actions?.get(2)?.action)
        assertEquals("Palakollu", parsed?.actions?.get(2)?.text)
        assertEquals("PRESS_ENTER", parsed?.actions?.get(3)?.action)
    }

    @Test
    fun testStandaloneSearchWorkflow_planGeneration() {
        val parsed = com.example.automation.engine.SemanticUiCommandParser.parse("Palakollu ane search chey")
        assertNotNull(parsed)
        assertEquals(2, parsed?.actions?.size)
        assertEquals("TYPE", parsed?.actions?.get(0)?.action)
        assertEquals("Palakollu", parsed?.actions?.get(0)?.text)
        assertEquals("PRESS_ENTER", parsed?.actions?.get(1)?.action)
    }

    @Test
    fun testCalculatorWorkflow_planGeneration() {
        val parsed = com.example.automation.engine.SemanticUiCommandParser.parse("Open Calculator, enter 25, press +, enter 10")
        assertNotNull(parsed)
        assertEquals("Calculator", parsed?.app)
        assertEquals(4, parsed?.actions?.size)
        assertEquals("OPEN_APP", parsed?.actions?.get(0)?.action)
        assertEquals("TYPE", parsed?.actions?.get(1)?.action)
        assertEquals("25", parsed?.actions?.get(1)?.text)
        assertEquals("CLICK", parsed?.actions?.get(2)?.action)
        assertEquals("+", parsed?.actions?.get(2)?.target)
        assertEquals("TYPE", parsed?.actions?.get(3)?.action)
        assertEquals("10", parsed?.actions?.get(3)?.text)
    }
}
