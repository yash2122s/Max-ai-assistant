"""
MAX AI — Windows UI Automation (UIA) Perception Provider
Extracts structural UI elements via Microsoft UIAutomation COM interface.
"""

import sys
import time
import logging
import ctypes
from ctypes import wintypes
from typing import Dict, Any, List, Optional, Tuple

from core.computer_use.models import UIElement, Rect, Point, ElementSource
from core.computer_use.coordinate_transform import coordinate_transformer

logger = logging.getLogger("MAX.ComputerUse.UIAProvider")

# Control Type ID Mappings
UIA_CONTROL_TYPE_NAMES = {
    50000: "Button",
    50001: "Calendar",
    50002: "CheckBox",
    50003: "ComboBox",
    50004: "Edit",
    50005: "Hyperlink",
    50006: "Image",
    50007: "ListItem",
    50008: "List",
    50009: "Menu",
    50010: "MenuBar",
    50011: "MenuItem",
    50012: "ProgressBar",
    50013: "RadioButton",
    50014: "ScrollBar",
    50015: "Slider",
    50016: "Spinner",
    50017: "StatusBar",
    50018: "Tab",
    50019: "TabItem",
    50020: "Text",
    50021: "ToolBar",
    50022: "ToolTip",
    50023: "Tree",
    50024: "TreeItem",
    50025: "Custom",
    50026: "Group",
    50027: "Thumb",
    50028: "DataGrid",
    50029: "DataItem",
    50030: "Document",
    50031: "SplitButton",
    50032: "Window",
    50033: "Pane",
    50034: "Header",
    50035: "HeaderItem",
    50036: "Table",
    50037: "TitleBar",
    50038: "Separator",
}

INTERACTIVE_CONTROL_TYPES = frozenset({
    "Button", "CheckBox", "ComboBox", "Edit", "Hyperlink", "ListItem",
    "MenuItem", "RadioButton", "Slider", "TabItem", "SplitButton",
    "TreeItem", "Custom", "Document"
})


class UIAProvider:
    """Extracts UIAutomation elements from Windows desktop and active windows."""

    def __init__(self):
        self._uia = None
        self._available = False
        self._initialized = False

    def _ensure_init(self):
        if not self._initialized:
            self._initialized = True
            self._init_uia()

    def _init_uia(self):
        if sys.platform != "win32":
            return
        try:
            from core.readiness import readiness
            readiness.set("uia", "INITIALIZING")
        except Exception:
            readiness = None

        try:
            try:
                from core.startup_profiler import profiler
                profiler.mark("UIA_INIT_START")
            except Exception:
                pass
            import comtypes
            import comtypes.client
            comtypes.CoInitialize()
            comtypes.client.GetModule("UIAutomationCore.dll")
            from comtypes.gen.UIAutomationClient import CUIAutomation, IUIAutomation
            self._uia = comtypes.client.CreateObject(CUIAutomation, interface=IUIAutomation)
            self._available = True
            logger.info("✅ UIAProvider: IUIAutomation COM client initialized successfully.")
            if readiness:
                readiness.set("uia", "READY")
            try:
                from core.startup_profiler import profiler
                profiler.mark("UIA_READY")
            except Exception:
                pass
        except Exception as e:
            logger.warning(f"UIAProvider COM initialization notice: {e}")
            self._available = False
            if readiness:
                readiness.set("uia", "ERROR", error=str(e))

    @property
    def is_available(self) -> bool:
        self._ensure_init()
        return self._available and self._uia is not None

    def get_active_window_info(self) -> Dict[str, Any]:
        """Returns details about the currently focused top-level window."""
        if sys.platform != "win32":
            return {"title": "Desktop", "process": "explorer.exe", "hwnd": 0}

        try:
            user32 = ctypes.WinDLL("user32", use_last_error=True)
            hwnd = user32.GetForegroundWindow()
            if not hwnd:
                return {"title": "Desktop", "process": "explorer.exe", "hwnd": 0}

            length = user32.GetWindowTextLengthW(hwnd)
            buff = ctypes.create_unicode_buffer(length + 1)
            user32.GetWindowTextW(hwnd, buff, length + 1)
            title = buff.value or "Untitled Window"

            # Get Process Name
            pid = wintypes.DWORD()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            process_name = "unknown"
            try:
                import psutil
                if pid.value > 0:
                    proc = psutil.Process(pid.value)
                    process_name = proc.name()
            except Exception:
                pass

            return {
                "title": title,
                "process": process_name,
                "hwnd": int(hwnd)
            }
        except Exception as e:
            logger.debug(f"Error getting active window: {e}")
            return {"title": "Desktop", "process": "explorer.exe", "hwnd": 0}

    def inspect_active_window_elements(
        self,
        max_depth: int = 6,
        max_elements: int = 150
    ) -> List[UIElement]:
        """
        Traverses UI elements in the current active window or desktop.
        Returns a structured list of normalized UIElement objects.
        """
        win_info = self.get_active_window_info()
        elements: List[UIElement] = []

        if not self.is_available:
            return elements

        try:
            import comtypes
            comtypes.CoInitialize()

            # Target active window element or fallback to desktop root
            target_el = None
            if win_info.get("hwnd"):
                try:
                    target_el = self._uia.ElementFromHandle(win_info["hwnd"])
                except Exception:
                    target_el = None

            if target_el is None:
                target_el = self._uia.GetRootElement()

            if not target_el:
                return elements

            # Create Raw/Control View Walker
            walker = self._uia.ControlViewWalker

            # Breadth-first / Depth-limited traversal
            queue = [(target_el, 0)]
            visited_count = 0

            while queue and len(elements) < max_elements:
                curr_node, depth = queue.pop(0)
                visited_count += 1

                try:
                    name = curr_node.CurrentName or ""
                    c_type_id = curr_node.CurrentControlType
                    role = UIA_CONTROL_TYPE_NAMES.get(c_type_id, "Custom")
                    auto_id = curr_node.CurrentAutomationId or ""
                    is_enabled = bool(curr_node.CurrentIsEnabled)
                    is_offscreen = bool(curr_node.CurrentIsOffscreen)
                    rect_val = curr_node.CurrentBoundingRectangle

                    # Extract coordinates (left, top, right, bottom)
                    # Comtypes rect is a tagRECT struct or tuple
                    r_left = float(getattr(rect_val, "left", 0))
                    r_top = float(getattr(rect_val, "top", 0))
                    r_right = float(getattr(rect_val, "right", 0))
                    r_bottom = float(getattr(rect_val, "bottom", 0))

                    width = r_right - r_left
                    height = r_bottom - r_top

                    # Filter out invisible or 0-sized elements
                    if width > 1 and height > 1 and not is_offscreen:
                        bounds = Rect(r_left, r_top, r_right, r_bottom)
                        is_interactive = role in INTERACTIVE_CONTROL_TYPES or is_enabled

                        # Only include elements that have names, automation ids, or are interactive controls
                        if name.strip() or auto_id.strip() or role in ("Button", "Edit", "MenuItem", "TabItem", "CheckBox"):
                            ui_elem = UIElement(
                                ref="",  # Will be assigned during UIMap generation
                                role=role.lower(),
                                name=name.strip(),
                                text=name.strip(),
                                bounds=bounds,
                                center=bounds.center,
                                source=ElementSource.UIA,
                                control_type=role,
                                automation_id=auto_id.strip(),
                                enabled=is_enabled,
                                visible=not is_offscreen,
                                interactive=is_interactive,
                                confidence=1.0,
                                window=win_info["title"],
                                process=win_info["process"],
                                timestamp=time.time()
                            )
                            elements.append(ui_elem)

                except Exception:
                    pass

                # Enqueue children if depth within limits
                if depth < max_depth:
                    try:
                        child = walker.GetFirstChildElement(curr_node)
                        while child:
                            queue.append((child, depth + 1))
                            child = walker.GetNextSiblingElement(child)
                    except Exception:
                        pass

        except Exception as e:
            logger.error(f"Error inspecting UIA elements: {e}")

        return elements


uia_provider = UIAProvider()
_uia_provider_instance = None


def get_uia_provider() -> UIAProvider:
    """Returns the lazy singleton UIAProvider instance."""
    global _uia_provider_instance
    if _uia_provider_instance is None:
        _uia_provider_instance = uia_provider
    return _uia_provider_instance
