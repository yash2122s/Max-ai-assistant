package com.example.automation

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.ExecutionSource
import com.example.automation.tools.ClickUiTool
import com.example.service.AccessibilityTreePruner
import com.example.service.ClickEscalationResult
import com.example.service.IndexedUiElement
import com.example.service.TargetStateSnapshot
import com.example.voice.vision.ScreenContextProvider
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BlurrSession1UnitTest {

    private lateinit var context: Context
    private val clickTool = ClickUiTool()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ==========================================
    // PART A: TAP ESCALATION TESTS
    // ==========================================

    @Test
    fun testIndexedUiElementModelFormatting() {
        val element = IndexedUiElement(
            index = 1,
            text = "Search",
            resourceId = "com.android.settings:id/search",
            className = "Button",
            isClickable = true,
            role = "btn"
        )
        val display = element.toDisplayString()
        assertTrue(display.contains("[1]"))
        assertTrue(display.contains("\"Search\""))
        assertTrue(display.contains("<id=search>"))
        assertTrue(display.contains("<clickable>"))
        assertTrue(display.contains("<btn>"))
    }

    @Test
    fun testClickEscalationResultEnumValues() {
        val confirmed = ClickEscalationResult.ACCESSIBILITY_CONFIRMED
        val gesture = ClickEscalationResult.GESTURE_CONFIRMED
        val unconfirmed = ClickEscalationResult.UNCONFIRMED
        val failed = ClickEscalationResult.FAILED

        assertNotNull(confirmed)
        assertNotNull(gesture)
        assertNotNull(unconfirmed)
        assertNotNull(failed)
    }

    @Test
    fun testTargetStateSnapshot() {
        val snapshot = TargetStateSnapshot(
            isChecked = false,
            text = "Enable Wi-Fi",
            bounds = Rect(10, 20, 100, 200)
        )
        assertEquals(false, snapshot.isChecked)
        assertEquals("Enable Wi-Fi", snapshot.text)
        assertEquals(10, snapshot.bounds.left)
        assertEquals(200, snapshot.bounds.bottom)
    }

    // ==========================================
    // PART B: INDEXED UI & TREE PRUNING TESTS
    // ==========================================

    @Test
    fun testIndexedTreePruningWithInteractiveViews() {
        val rootNode = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.Button"
            text = "Send Message"
            isVisibleToUser = true
            isEnabled = true
            isClickable = true
            setBoundsInScreen(Rect(10, 10, 200, 100))
        }
        assertNotNull(rootNode)

        val (elements, liveMap) = AccessibilityTreePruner.pruneAndIndexTree(rootNode)

        // At least the interactive buttons or layout are extracted with 1-based indexing
        assertTrue(elements.isNotEmpty())
        assertEquals(1, elements[0].index)
        assertEquals("Send Message", elements[0].text)
        assertTrue(liveMap.containsKey(1))
    }

    // ==========================================
    // PART C: TOOL INTEGRATION TESTS
    // ==========================================

    @Test
    fun testClickUiToolValidation() {
        // 1. Text click validation
        val textReq = ExecutionRequest(
            action = "CLICK_TEXT",
            arguments = JsonObject().apply { addProperty("text", "Submit") },
            source = ExecutionSource.GEMINI_LIVE
        )
        assertTrue(clickTool.validate(textReq))

        // 2. Index click validation
        val indexReq = ExecutionRequest(
            action = "CLICK_INDEX",
            arguments = JsonObject().apply { addProperty("index", 3) },
            source = ExecutionSource.GEMINI_LIVE
        )
        assertTrue(clickTool.validate(indexReq))

        // 3. Invalid empty request
        val emptyReq = ExecutionRequest(
            action = "CLICK_ELEMENT",
            arguments = JsonObject(),
            source = ExecutionSource.GEMINI_LIVE
        )
        assertFalse(clickTool.validate(emptyReq))
    }

    @Test
    fun testClickUiToolExecutionWithoutService() = runBlocking {
        val req = ExecutionRequest(
            action = "CLICK_INDEX",
            arguments = JsonObject().apply { addProperty("index", 1) },
            source = ExecutionSource.GEMINI_LIVE
        )
        val result = clickTool.execute(context, req)
        assertFalse(result.success)
        assertEquals("SERVICE_UNAVAILABLE", result.errorCode)
    }

    @Test
    fun testScreenContextProviderIndexedFields() = runBlocking {
        val payload = ScreenContextProvider.captureScreenContext(context)
        assertNotNull(payload.indexedElements)
        assertNotNull(payload.indexedElementsFormatted)
        assertEquals("general", payload.focusMode)
    }
}
