package com.example.automation.skills.catalog

/**
 * Shared Canonical Capability Catalog defining platform-agnostic automation verbs.
 * Decouples skill files from platform-specific action symbols.
 */
object CanonicalCapabilityCatalog {

    const val APP_OPEN = "app.open"
    const val APP_CLOSE = "app.close"
    const val UI_CLICK = "ui.click"
    const val UI_TYPE_TEXT = "ui.type_text"
    const val UI_CLEAR_TEXT = "ui.clear_text"
    const val UI_SCROLL = "ui.scroll"
    const val UI_WAIT = "ui.wait"
    const val SYSTEM_PRESS_KEY = "system.press_key"
    const val SYSTEM_ACTION = "system.action"
    const val SYSTEM_SETTING = "system.setting"

    val ALL_CAPABILITIES: Set<String> = setOf(
        APP_OPEN,
        APP_CLOSE,
        UI_CLICK,
        UI_TYPE_TEXT,
        UI_CLEAR_TEXT,
        UI_SCROLL,
        UI_WAIT,
        SYSTEM_PRESS_KEY,
        SYSTEM_ACTION,
        SYSTEM_SETTING
    )

    private val CAPABILITY_TO_ANDROID_ACTION = mapOf(
        APP_OPEN to "OPEN_APP",
        APP_CLOSE to "CLOSE_APP",
        UI_CLICK to "CLICK_ELEMENT",
        UI_TYPE_TEXT to "TYPE_TEXT",
        UI_CLEAR_TEXT to "CLEAR_TEXT",
        UI_SCROLL to "SCROLL_SCREEN",
        UI_WAIT to "WAIT_FOR_UI",
        SYSTEM_PRESS_KEY to "PRESS_KEY",
        SYSTEM_ACTION to "SYSTEM_ACTION",
        SYSTEM_SETTING to "SET_SYSTEM_SETTING"
    )

    private val CAPABILITY_TO_WINDOWS_ACTION = mapOf(
        APP_OPEN to "open_application",
        APP_CLOSE to "close_application",
        UI_CLICK to "click_element_by_selector",
        UI_TYPE_TEXT to "type_in_element",
        UI_CLEAR_TEXT to "clear_text",
        UI_SCROLL to "scroll_mouse",
        UI_WAIT to "wait_for_element",
        SYSTEM_PRESS_KEY to "send_key",
        SYSTEM_ACTION to "system_control",
        SYSTEM_SETTING to "set_volume"
    )

    /**
     * Checks if a verb is part of the standard Canonical Capability Catalog.
     */
    fun isSupported(capability: String): Boolean {
        return ALL_CAPABILITIES.contains(capability.trim().lowercase())
    }

    /**
     * Maps a canonical verb to its corresponding Android action identifier.
     */
    fun toAndroidAction(capability: String): String? {
        return CAPABILITY_TO_ANDROID_ACTION[capability.trim().lowercase()]
    }

    /**
     * Maps a canonical verb to its corresponding Windows tool/action identifier.
     */
    fun toWindowsAction(capability: String): String? {
        return CAPABILITY_TO_WINDOWS_ACTION[capability.trim().lowercase()]
    }
}
