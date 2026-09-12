package com.example.automation.agent

/**
 * Represents an atomic action to be performed on the Android device by the UI Agent.
 */
data class UiAction(
    val type: Type,
    val x: Int? = null,
    val y: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val durationMs: Long = 300L,
    val text: String? = null,
    val packageName: String? = null,
    val direction: String? = null, // "UP", "DOWN", "LEFT", "RIGHT"
    val reason: String? = null,
    val isSuccess: Boolean = false
) {
    enum class Type {
        TAP,
        LONG_PRESS,
        SWIPE,
        SCROLL,
        TYPE,
        KEY_BACK,
        KEY_HOME,
        KEY_ENTER,
        LAUNCH_APP,
        SCREENSHOT,
        FINISH
    }

    /**
     * Validates that the action parameters meet constraints.
     */
    fun validate(): Boolean {
        return when (type) {
            Type.TAP, Type.LONG_PRESS -> (x != null && x >= 0) && (y != null && y >= 0)
            Type.SWIPE -> (x != null && x >= 0) && (y != null && y >= 0) && (endX != null && endX >= 0) && (endY != null && endY >= 0)
            Type.SCROLL -> direction != null && direction.uppercase() in listOf("UP", "DOWN", "LEFT", "RIGHT")
            Type.TYPE -> !text.isNullOrEmpty()
            Type.LAUNCH_APP -> !packageName.isNullOrBlank()
            Type.KEY_BACK, Type.KEY_HOME, Type.KEY_ENTER, Type.SCREENSHOT, Type.FINISH -> true
        }
    }
}
