package com.example.automation.activity

object ActionReasonFormatter {
    fun formatActionTitle(actionType: String, targetText: String?): String {
        val target = targetText ?: ""
        return when (actionType.uppercase()) {
            "GET_SCREEN_CONTEXT", "OBSERVING" -> "Capturing current screen"
            "OPEN_APP" -> if (target.isNotBlank()) "Open $target" else "Open App"
            "CLICK_ELEMENT", "CLICK_TEXT", "TAP" -> if (target.isNotBlank()) "Tap \"$target\"" else "Tap Element"
            "TYPE_TEXT" -> if (target.isNotBlank()) "Type \"$target\"" else "Type Text"
            "SCROLL_SCREEN" -> "Scroll $target"
            "SYSTEM_ACTION" -> "System $target"
            "SET_VOLUME" -> "Set volume to $target"
            "SET_BRIGHTNESS" -> "Set brightness to $target"
            "SET_DND" -> "Set Do Not Disturb ($target)"
            "SET_RINGER_MODE" -> "Set ringer mode to $target"
            "YOUTUBE_SEARCH" -> if (target.isNotBlank()) "YouTube Search: $target" else "YouTube Search"
            "SEND_WHATSAPP_MESSAGE" -> if (target.isNotBlank()) "Message $target" else "Send Message"
            "GET_BATTERY_STATUS" -> "Check Battery Status"
            "GET_DEVICE_STATUS" -> "Check Device Status"
            "FLASHLIGHT_ON" -> "Turn On Flashlight"
            "FLASHLIGHT_OFF" -> "Turn Off Flashlight"
            else -> "$actionType ${if (target.isNotBlank()) "· $target" else ""}"
        }
    }

    fun formatReason(actionType: String, targetText: String?, goal: String? = null): String {
        val target = targetText ?: ""
        return when (actionType.uppercase()) {
            "GET_SCREEN_CONTEXT", "OBSERVING" -> "Checking the current screen so MAX can determine what is currently visible."
            "OPEN_APP" -> if (target.isNotBlank()) "Opening $target because the next task step requires interacting with $target." else "Opening the requested application to proceed with the task."
            "CLICK_ELEMENT", "CLICK_TEXT", "TAP" -> if (target.isNotBlank()) "Selecting \"$target\" to proceed with the requested action." else "Tapping element to proceed with the requested action."
            "TYPE_TEXT" -> if (target.isNotBlank()) "Entering \"$target\" into the active input field." else "Entering the requested message."
            "SCROLL_SCREEN" -> "Scrolling screen $target to find visible items."
            "SYSTEM_ACTION" -> "Navigating $target as required by the task flow."
            "SET_VOLUME" -> "Adjusting device volume to $target as requested."
            "SET_BRIGHTNESS" -> "Adjusting display brightness to $target as requested."
            "SET_DND" -> "Updating Do Not Disturb setting as requested."
            "SET_RINGER_MODE" -> "Setting ringer mode to $target as requested."
            "YOUTUBE_SEARCH" -> if (target.isNotBlank()) "Searching YouTube for \"$target\" because the current goal requires finding YouTube content." else "Searching YouTube content."
            "SEND_WHATSAPP_MESSAGE" -> if (target.isNotBlank()) "Preparing to message $target as requested." else "Sending requested message."
            else -> "Required to determine and execute the next action."
        }
    }
}
