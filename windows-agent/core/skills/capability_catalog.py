"""
MAX AI — Canonical Capability Catalog (Python)
Maps platform-independent capability verbs to Windows UnifiedToolRegistry actions.
"""

from typing import Set, Optional, Dict


class CanonicalCapabilityCatalog:
    APP_OPEN = "app.open"
    APP_CLOSE = "app.close"
    UI_CLICK = "ui.click"
    UI_TYPE_TEXT = "ui.type_text"
    UI_CLEAR_TEXT = "ui.clear_text"
    UI_SCROLL = "ui.scroll"
    UI_WAIT = "ui.wait"
    SYSTEM_PRESS_KEY = "system.press_key"
    SYSTEM_ACTION = "system.action"
    SYSTEM_SETTING = "system.setting"

    ALL_CAPABILITIES: Set[str] = {
        APP_OPEN,
        APP_CLOSE,
        UI_CLICK,
        UI_TYPE_TEXT,
        UI_CLEAR_TEXT,
        UI_SCROLL,
        UI_WAIT,
        SYSTEM_PRESS_KEY,
        SYSTEM_ACTION,
        SYSTEM_SETTING,
    }

    CAPABILITY_TO_WINDOWS_ACTION: Dict[str, str] = {
        APP_OPEN: "open_application",
        APP_CLOSE: "close_application",
        UI_CLICK: "click_element_by_selector",
        UI_TYPE_TEXT: "type_in_element",
        UI_CLEAR_TEXT: "clear_text",
        UI_SCROLL: "scroll_mouse",
        UI_WAIT: "wait_for_element",
        SYSTEM_PRESS_KEY: "send_key",
        SYSTEM_ACTION: "system_control",
        SYSTEM_SETTING: "set_volume",
    }

    CAPABILITY_TO_ANDROID_ACTION: Dict[str, str] = {
        APP_OPEN: "OPEN_APP",
        APP_CLOSE: "CLOSE_APP",
        UI_CLICK: "CLICK_ELEMENT",
        UI_TYPE_TEXT: "TYPE_TEXT",
        UI_CLEAR_TEXT: "CLEAR_TEXT",
        UI_SCROLL: "SCROLL_SCREEN",
        UI_WAIT: "WAIT_FOR_UI",
        SYSTEM_PRESS_KEY: "PRESS_KEY",
        SYSTEM_ACTION: "SYSTEM_ACTION",
        SYSTEM_SETTING: "SET_SYSTEM_SETTING",
    }

    @classmethod
    def is_supported(cls, capability: str) -> bool:
        """Checks if a verb is part of the standard Canonical Capability Catalog."""
        return capability.strip().lower() in cls.ALL_CAPABILITIES

    @classmethod
    def to_windows_action(cls, capability: str) -> Optional[str]:
        """Maps a canonical verb to its Windows tool/action identifier."""
        return cls.CAPABILITY_TO_WINDOWS_ACTION.get(capability.strip().lower())

    @classmethod
    def to_android_action(cls, capability: str) -> Optional[str]:
        """Maps a canonical verb to its Android action identifier."""
        return cls.CAPABILITY_TO_ANDROID_ACTION.get(capability.strip().lower())


# Backward-compatible alias
CapabilityCatalog = CanonicalCapabilityCatalog
