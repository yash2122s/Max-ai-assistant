package com.example.automation

import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.engine.ActionDispatcher
import com.example.automation.keyboard.KeyboardController
import com.example.automation.models.UiStep
import com.example.automation.resolver.InputTargetResolver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardControllerUnitTest {

    private fun createNode(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        className: String = "android.widget.EditText",
        isClickable: Boolean = true,
        isEditable: Boolean = true,
        isFocused: Boolean = false,
        isPassword: Boolean = false,
        isEnabled: Boolean = true,
        isVisible: Boolean = true,
        bounds: Rect = Rect(10, 10, 200, 100)
    ): AccessibilityNodeInfo {
        return AccessibilityNodeInfo.obtain().apply {
            this.text = text
            this.contentDescription = desc
            this.viewIdResourceName = resId
            this.className = className
            this.isClickable = isClickable
            this.isEditable = isEditable
            this.isFocused = isFocused
            this.isPassword = isPassword
            this.isEnabled = isEnabled
            this.isVisibleToUser = isVisible
            this.setBoundsInScreen(bounds)
        }
    }

    @Test
    fun testKeyMapResolution() {
        assertEquals(KeyEvent.KEYCODE_ENTER, KeyboardController.KEY_MAP["ENTER"])
        assertEquals(KeyEvent.KEYCODE_ENTER, KeyboardController.KEY_MAP["KEY_ENTER"])
        assertEquals(KeyEvent.KEYCODE_BACK, KeyboardController.KEY_MAP["BACK"])
        assertEquals(KeyEvent.KEYCODE_BACK, KeyboardController.KEY_MAP["KEY_BACK"])
        assertEquals(KeyEvent.KEYCODE_TAB, KeyboardController.KEY_MAP["TAB"])
        assertEquals(KeyEvent.KEYCODE_SPACE, KeyboardController.KEY_MAP["SPACE"])
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, KeyboardController.KEY_MAP["DELETE"])
        assertEquals(KeyEvent.KEYCODE_DEL, KeyboardController.KEY_MAP["BACKSPACE"])
        assertEquals(KeyEvent.KEYCODE_ESCAPE, KeyboardController.KEY_MAP["ESC"])
        assertEquals(KeyEvent.KEYCODE_MOVE_HOME, KeyboardController.KEY_MAP["HOME"])
        assertEquals(KeyEvent.KEYCODE_MOVE_END, KeyboardController.KEY_MAP["END"])
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, KeyboardController.KEY_MAP["ARROW_UP"])
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, KeyboardController.KEY_MAP["ARROW_DOWN"])
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, KeyboardController.KEY_MAP["ARROW_LEFT"])
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, KeyboardController.KEY_MAP["ARROW_RIGHT"])
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, KeyboardController.KEY_MAP["SHIFT"])
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, KeyboardController.KEY_MAP["CTRL"])
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, KeyboardController.KEY_MAP["ALT"])
    }

    @Test
    fun testInputTargetResolverFindsFocusedNode() {
        val focusedNode = createNode(
            text = "Palakollu",
            resId = "com.example.app:id/search_query",
            isFocused = true,
            isEditable = true
        )

        val resolved = InputTargetResolver.resolveInputTarget(focusedNode)
        assertNotNull(resolved)
        assertEquals("com.example.app:id/search_query", resolved?.target?.resourceId)
        assertTrue(resolved?.isAlreadyFocused == true)
    }

    @Test
    fun testInputTargetResolverFindsSingleEditableNode() {
        val editableNode = createNode(
            resId = "com.google.android.apps.maps:id/search_omnibox_text_box",
            isFocused = false,
            isEditable = true
        )

        val resolved = InputTargetResolver.resolveInputTarget(editableNode)
        assertNotNull(resolved)
        assertEquals("com.google.android.apps.maps:id/search_omnibox_text_box", resolved?.target?.resourceId)
    }

    @Test
    fun testActionDispatcherNormalizesKeyboardActions() {
        val typeJson = JSONObject().apply {
            put("action", "TYPE")
            put("text", "Palakollu to Bhimavaram")
        }
        val normType = ActionDispatcher.normalizeJson(typeJson)
        assertEquals("TYPE_TEXT", normType.optString("action"))

        val enterJson = JSONObject().apply {
            put("action", "KEY_ENTER")
        }
        val normEnter = ActionDispatcher.normalizeJson(enterJson)
        assertEquals("PRESS_ENTER", normEnter.optString("action"))

        val clearJson = JSONObject().apply {
            put("action", "CLEAR_INPUT")
        }
        val normClear = ActionDispatcher.normalizeJson(clearJson)
        assertEquals("CLEAR_TEXT", normClear.optString("action"))

        val keyJson = JSONObject().apply {
            put("action", "KEY_TAB")
        }
        val normKey = ActionDispatcher.normalizeJson(keyJson)
        assertEquals("PRESS_KEY", normKey.optString("action"))
        assertEquals("TAB", normKey.optString("key"))

        val backJson = JSONObject().apply {
            put("action", "KEY_BACK")
        }
        val normBack = ActionDispatcher.normalizeJson(backJson)
        assertEquals("SYSTEM_ACTION", normBack.optString("action"))
        assertEquals("back", normBack.optString("system_action_str"))
    }

    @Test
    fun testUiStepModels() {
        val clearStep = UiStep.ClearText(target = "Search bar")
        assertEquals("Search bar", clearStep.target)

        val keyStep = UiStep.PressKey(key = "ENTER")
        assertEquals("ENTER", keyStep.key)
    }
}
