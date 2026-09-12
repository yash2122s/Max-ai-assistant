package com.example.automation.models

import android.graphics.Rect

/**
 * Universal sealed model representing a declarative UI interaction step
 * emitted by a planner or requested at runtime.
 */
sealed class UiStep {
    /** Launch or bring an application to the foreground */
    data class OpenApp(
        val appName: String,
        val packageName: String? = null
    ) : UiStep()

    /** Toggle or configure a direct system or hardware setting */
    data class ToggleSystemSetting(
        val settingKey: String,
        val enabled: Boolean = true
    ) : UiStep()

    /** Tap on a resolved target or text/description */
    data class Tap(
        val target: String? = null,
        val resourceId: String? = null,
        val contentDescription: String? = null,
        val bounds: Rect? = null,
        val index: Int? = null
    ) : UiStep()

    /** Input arbitrary text into an editable target or active focused field */
    data class TypeText(
        val text: String,
        val target: String? = null,
        val resourceId: String? = null,
        val clearExisting: Boolean = false
    ) : UiStep()

    /** Clear text in an editable target or active focused field */
    data class ClearText(
        val target: String? = null,
        val resourceId: String? = null
    ) : UiStep()

    /** Press a specific physical or virtual keyboard key (e.g. ENTER, TAB, SPACE, BACK, ESC, ARROW_UP, etc.) */
    data class PressKey(
        val key: String,
        val target: String? = null
    ) : UiStep()

    /** Press IME enter/search/done action or hardware enter key */
    object PressEnter : UiStep()

    /** Perform global or navigation back */
    object PressBack : UiStep()

    /** Scroll vertically or horizontally */
    data class Scroll(
        val direction: String = "DOWN"
    ) : UiStep()

    /** Perform directional swipe gesture */
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 300L
    ) : UiStep()

    /** Long-press on a target or coordinates */
    data class LongPress(
        val target: String? = null,
        val resourceId: String? = null,
        val bounds: Rect? = null,
        val durationMs: Long = 1000L
    ) : UiStep()

    /** Wait for a specific UI element, text, or package to appear */
    data class WaitForUi(
        val targetText: String? = null,
        val resourceId: String? = null,
        val packageName: String? = null,
        val timeoutMs: Long = 3000L
    ) : UiStep()

    /** Mark completion of UI interaction sequence */
    data class Finish(
        val success: Boolean = true,
        val message: String? = null
    ) : UiStep()
}
