package com.example.voice.tools

enum class ExecutionMethod {
    ANDROID_API,
    ACCESSIBILITY,
    SHIZUKU,
    SHELL,
    ROOT,
    UNSUPPORTED
}

class ExecutionPolicy(private val capabilityProvider: CapabilityProvider) {
    fun getExecutionMethod(action: String): ExecutionMethod {
        return when (action) {
            "PERFORM_BACK", "PERFORM_HOME", "PERFORM_RECENT_APPS", "TAKE_SCREENSHOT", "SYSTEM_ACTION" -> {
                if (capabilityProvider.hasAccessibility()) {
                    ExecutionMethod.ACCESSIBILITY
                } else if (capabilityProvider.hasShizuku()) {
                    ExecutionMethod.SHIZUKU
                } else if (capabilityProvider.hasRoot()) {
                    ExecutionMethod.ROOT
                } else {
                    ExecutionMethod.UNSUPPORTED
                }
            }
            "OPEN_APP" -> {
                ExecutionMethod.ANDROID_API
            }
            "SEND_WHATSAPP" -> {
                if (capabilityProvider.hasAccessibility()) {
                    ExecutionMethod.ACCESSIBILITY
                } else {
                    ExecutionMethod.ANDROID_API
                }
            }
            "SET_VOLUME", "SET_BRIGHTNESS", "SET_RINGER_MODE", "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "YOUTUBE_SEARCH", "CALL_PHONE", "CREATE_CONTACT", "MAX_DIAGNOSTICS" -> {
                ExecutionMethod.ANDROID_API
            }
            else -> ExecutionMethod.ANDROID_API
        }
    }
}
