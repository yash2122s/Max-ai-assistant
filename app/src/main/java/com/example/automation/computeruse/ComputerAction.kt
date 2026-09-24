package com.example.automation.computeruse

/**
 * Expected outcome for an action to verify against the post-action observation.
 */
sealed class ActionExpectation {
    object ScreenChange : ActionExpectation()
    data class TextPresent(val value: String) : ActionExpectation()
    data class FocusField(val target: String) : ActionExpectation()
    data class AppForeground(val packageName: String) : ActionExpectation()
    data class ElementSelected(val target: String) : ActionExpectation()
}

/**
 * Primitive actions executable by the ComputerActionExecutor.
 */
sealed class ComputerAction {
    abstract val actionName: String
    open val expectation: ActionExpectation? = null

    data class LaunchApp(
        val appName: String,
        val packageName: String? = null,
        override val expectation: ActionExpectation? = null
    ) : ComputerAction() {
        override val actionName: String = "LAUNCH_APP"
    }

    data class TapCoordinate(
        val x: Int,
        val y: Int,
        val description: String? = null,
        val grounding: TargetGrounding? = null,
        override val expectation: ActionExpectation? = ActionExpectation.ScreenChange
    ) : ComputerAction() {
        override val actionName: String = "TAP_COORDINATE"
    }

    data class LongPress(
        val x: Int,
        val y: Int,
        val durationMs: Long = 1000L,
        val description: String? = null,
        val grounding: TargetGrounding? = null,
        override val expectation: ActionExpectation? = ActionExpectation.ScreenChange
    ) : ComputerAction() {
        override val actionName: String = "LONG_PRESS"
    }

    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long = 300L,
        override val expectation: ActionExpectation? = ActionExpectation.ScreenChange
    ) : ComputerAction() {
        override val actionName: String = "SWIPE"
    }

    data class Drag(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        override val expectation: ActionExpectation? = ActionExpectation.ScreenChange
    ) : ComputerAction() {
        override val actionName: String = "DRAG"
    }

    data class TapElement(
        val targetText: String,
        val resourceId: String? = null,
        val contentDescription: String? = null,
        val targetRole: String? = null,
        val grounding: TargetGrounding? = null,
        override val expectation: ActionExpectation? = ActionExpectation.ScreenChange
    ) : ComputerAction() {
        override val actionName: String = "TAP_ELEMENT"
    }

    data class TypeText(
        val text: String,
        val targetField: String? = null,
        val pressEnterAfter: Boolean = false,
        val clearExisting: Boolean = false,
        val grounding: TargetGrounding? = null,
        override val expectation: ActionExpectation? = ActionExpectation.TextPresent(text)
    ) : ComputerAction() {
        override val actionName: String = "TYPE_TEXT"
    }

    data class PressKey(
        val keyNameOrCode: String,
        override val expectation: ActionExpectation? = null
    ) : ComputerAction() {
        override val actionName: String = "PRESS_KEY"
    }

    object PressEnter : ComputerAction() {
        override val actionName: String = "PRESS_ENTER"
        override val expectation: ActionExpectation? = null
    }

    object PressBack : ComputerAction() {
        override val actionName: String = "PRESS_BACK"
        override val expectation: ActionExpectation = ActionExpectation.ScreenChange
    }

    data class Scroll(
        val direction: String = "down", // "down" | "up"
        override val expectation: ActionExpectation? = ActionExpectation.ScreenChange
    ) : ComputerAction() {
        override val actionName: String = "SCROLL"
    }

    data class Wait(
        val durationMs: Long = 1000L,
        val reason: String? = null
    ) : ComputerAction() {
        override val actionName: String = "WAIT"
    }

    data class ObserveMore(
        val reason: String = "Awaiting UI settlement and fresh observation"
    ) : ComputerAction() {
        override val actionName: String = "OBSERVE_MORE"
    }

    data class FinishTask(
        val success: Boolean,
        val message: String
    ) : ComputerAction() {
        override val actionName: String = "FINISH_TASK"
    }
}

/**
 * Result of executing a [ComputerAction].
 */
data class ActionResult(
    val success: Boolean,
    val actionType: String,
    val message: String? = null,
    val errorCode: String? = null,
    val targetApp: String? = null,
    val methodUsed: String = "ACCESSIBILITY",
    val durationMs: Long = 0L
)

