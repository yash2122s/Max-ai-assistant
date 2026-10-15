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
     *
     * Coordinate actions deliberately do not have fallback coordinates. When display
     * dimensions are available, every coordinate must be inside the display. When a
     * current accessibility observation is available, coordinates must also land inside
     * one of its non-empty bounds; an empty bounds list is treated as unavailable data.
     */
    fun validate(
        displayWidth: Int? = null,
        displayHeight: Int? = null,
        observation: UiObservation? = null
    ): Boolean {
        return when (type) {
            Type.TAP, Type.LONG_PRESS ->
                validatePoint(x, y, displayWidth, displayHeight, observation)
            Type.SWIPE ->
                validatePoint(x, y, displayWidth, displayHeight, observation) &&
                    validatePoint(endX, endY, displayWidth, displayHeight, observation)
            Type.SCROLL -> direction != null && direction.uppercase() in listOf("UP", "DOWN", "LEFT", "RIGHT")
            Type.TYPE -> !text.isNullOrEmpty()
            Type.LAUNCH_APP -> !packageName.isNullOrBlank()
            Type.KEY_BACK, Type.KEY_HOME, Type.KEY_ENTER, Type.SCREENSHOT, Type.FINISH -> true
        }
    }

    private fun validatePoint(
        pointX: Int?,
        pointY: Int?,
        displayWidth: Int?,
        displayHeight: Int?,
        observation: UiObservation?
    ): Boolean {
        if (pointX == null || pointY == null || pointX < 0 || pointY < 0) return false

        if (displayWidth != null && displayWidth > 0 && pointX >= displayWidth) return false
        if (displayHeight != null && displayHeight > 0 && pointY >= displayHeight) return false

        val observedBounds = observation?.prunedElements
            ?.asSequence()
            ?.map { it.boundsInScreen }
            ?.filter { it.right > it.left && it.bottom > it.top }
            ?.toList()
            .orEmpty()
        if (observedBounds.isNotEmpty() && observedBounds.none {
                pointX >= it.left && pointX < it.right && pointY >= it.top && pointY < it.bottom
            }) {
            return false
        }

        return true
    }
}
