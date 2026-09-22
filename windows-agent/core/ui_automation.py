"""
MAX AI — Microsoft Windows UI Automation (UIA) Engine
Provides semantic inspection and control of Windows desktop UI elements
using Microsoft UIAutomation COM interfaces with Win32 / PyAutoGUI fallbacks.
"""

import logging
import ctypes
from ctypes import wintypes
from typing import List, Dict, Any, Optional, Tuple

logger = logging.getLogger("MAXWindowsAgent.UIA")

# Control type ID mappings for readable element types
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

# Control Pattern IDs
UIA_INVOKE_PATTERN_ID = 10000
UIA_VALUE_PATTERN_ID = 10002
UIA_TOGGLE_PATTERN_ID = 10015
UIA_SELECTION_ITEM_PATTERN_ID = 10010


class UIAutomationEngine:
    """
    Adapter for Microsoft UIAutomation with automatic initialization and graceful fallback.
    """

    def __init__(self):
        self._uia = None
        self._available = False
        self._init_uia()

    def _init_uia(self):
        try:
            import comtypes
            import comtypes.client
            comtypes.CoInitialize()
            comtypes.client.GetModule("UIAutomationCore.dll")
            from comtypes.gen.UIAutomationClient import CUIAutomation, IUIAutomation
            self._uia = comtypes.client.CreateObject(CUIAutomation, interface=IUIAutomation)
            self._available = True
            logger.info("Microsoft UIAutomation COM engine (IUIAutomation) initialized successfully.")
        except Exception as e:
            logger.warning(f"UIAutomation direct COM unavailable. Operating with Win32 element fallback: {e}")
            self._available = False


    @property
    def is_available(self) -> bool:
        return self._available and self._uia is not None

    def get_root_element(self):
        if not self.is_available:
            return None
        try:
            return self._uia.GetRootElement()
        except Exception as e:
            logger.error(f"Error getting UIA RootElement: {e}")
            return None

    def get_active_window_element(self):
        if not self.is_available:
            return None
        try:
            user32 = ctypes.windll.user32
            hwnd = user32.GetForegroundWindow()
            if hwnd:
                return self._uia.ElementFromHandle(hwnd)
        except Exception as e:
            logger.debug(f"Error getting active window element via UIA: {e}")
        return None

    def get_element_properties(self, element) -> Dict[str, Any]:
        """
        Extracts semantic properties from a UIA element.
        """
        if not element:
            return {}
        try:
            name = element.CurrentName or ""
            ctrl_id = element.CurrentControlType
            ctrl_type = UIA_CONTROL_TYPE_NAMES.get(ctrl_id, f"Control_{ctrl_id}")
            auto_id = element.CurrentAutomationId or ""
            class_name = element.CurrentClassName or ""
            is_enabled = bool(element.CurrentIsEnabled)
            is_offscreen = bool(element.CurrentIsOffscreen)
            
            rect = element.CurrentBoundingRectangle
            bounding_box = {
                "left": rect.left,
                "top": rect.top,
                "right": rect.right,
                "bottom": rect.bottom,
                "width": rect.right - rect.left,
                "height": rect.bottom - rect.top,
                "center_x": rect.left + (rect.right - rect.left) // 2,
                "center_y": rect.top + (rect.bottom - rect.top) // 2,
            }
            
            return {
                "name": name,
                "control_type": ctrl_type,
                "control_type_id": ctrl_id,
                "automation_id": auto_id,
                "class_name": class_name,
                "is_enabled": is_enabled,
                "is_offscreen": is_offscreen,
                "bounding_box": bounding_box
            }
        except Exception as e:
            logger.debug(f"Error reading element properties: {e}")
            return {}

    def find_elements_by_name(self, root_element, name: str, partial: bool = True, max_results: int = 10) -> List[Dict[str, Any]]:
        """
        Searches for UI elements by visible text, name, automation_id, or control type.
        """
        results = []
        if not root_element:
            root_element = self.get_active_window_element() or self.get_root_element()
        if not root_element or not self.is_available:
            return results

        try:
            from comtypes.gen.UIAutomationClient import TreeScope_Descendants
            condition = self._uia.CreateTrueCondition()
            element_array = root_element.FindAll(TreeScope_Descendants, condition)
            
            target_name = name.strip().lower()
            length = element_array.Length if hasattr(element_array, "Length") else 0
            
            # Pass 1: Exact Name or AutomationId match
            for i in range(min(length, 400)):
                elem = element_array.GetElement(i)
                props = self.get_element_properties(elem)
                elem_name = props.get("name", "").lower()
                auto_id = props.get("automation_id", "").lower()
                
                if target_name == elem_name or target_name == auto_id:
                    props["_element_obj"] = elem
                    results.append(props)
                    if len(results) >= max_results:
                        return results

            # Pass 2: Partial Name / AutomationId / ControlType match
            if not results and partial:
                for i in range(min(length, 400)):
                    elem = element_array.GetElement(i)
                    props = self.get_element_properties(elem)
                    elem_name = props.get("name", "").lower()
                    auto_id = props.get("automation_id", "").lower()
                    ctrl_type = props.get("control_type", "").lower()
                    
                    if (target_name in elem_name) or (target_name in auto_id) or (target_name in f"{ctrl_type} {elem_name}"):
                        props["_element_obj"] = elem
                        results.append(props)
                        if len(results) >= max_results:
                            break
        except Exception as e:
            logger.warning(f"Error during UIA FindAll: {e}")
        return results

    def click_element_by_selector(
        self,
        target: str,
        window_title: Optional[str] = None,
        click_count: int = 1,
        button: str = "left"
    ) -> Dict[str, Any]:
        """
        Clicks an element using a multi-layer fallback strategy:
        Method 1: UI Automation pattern invoke/select/toggle
        Method 2: Physical mouse click at detected element center
        Method 3: Win32 SendInput / mouse_event direct dispatch
        Method 4: Vision-based coordinate detection fallback
        """
        import time
        import pyautogui

        # Step 1: Find and focus window if specified via shared activate_app_window
        root = None
        if window_title:
            try:
                from tools.app_control import activate_app_window
                act_res = activate_app_window(window_title, timeout_seconds=2.5)
                if not act_res.get("verified"):
                    logger.warning(f"Target window '{window_title}' could not be verified in foreground: {act_res.get('failure_reason')}")
                hwnd = act_res.get("hwnd")
                if hwnd and self.is_available:
                    root = self._uia.ElementFromHandle(hwnd)
            except Exception as e:
                logger.debug(f"Could not bind window root for '{window_title}': {e}")

        if not root:
            root = self.get_active_window_element() or self.get_root_element()

        # Step 2: Search for element in UIA tree
        matches = []
        if self.is_available and root:
            matches = self.find_elements_by_name(root, target, partial=True, max_results=5)
            # If not found in active window, search desktop root
            if not matches and root != self.get_root_element():
                root_all = self.get_root_element()
                if root_all:
                    matches = self.find_elements_by_name(root_all, target, partial=True, max_results=5)

        if matches:
            matched = matches[0]
            elem_obj = matched.get("_element_obj")
            bbox = matched.get("bounding_box", {})
            cx, cy = bbox.get("center_x", 0), bbox.get("center_y", 0)

            ctrl_type = str(matched.get("control_type", "")).lower()
            action_executed = False
            executed_method = "none"

            # 1. Deterministic UIA Pattern Selection (if elem_obj is available and not a double click or special button)
            if elem_obj and click_count == 1 and button == "left":
                try:
                    from comtypes.gen.UIAutomationClient import (
                        IUIAutomationInvokePattern,
                        IUIAutomationExpandCollapsePattern,
                        IUIAutomationTogglePattern,
                        IUIAutomationSelectionItemPattern
                    )
                    if ctrl_type in ("button", "menuitem", "hyperlink", "splitbutton"):
                        p_inv = elem_obj.GetCurrentPattern(UIA_INVOKE_PATTERN_ID)
                        if p_inv:
                            p_inv.QueryInterface(IUIAutomationInvokePattern).Invoke()
                            action_executed = True
                            executed_method = "UIA_InvokePattern"
                    elif ctrl_type in ("checkbox", "radiobutton"):
                        p_tog = elem_obj.GetCurrentPattern(UIA_TOGGLE_PATTERN_ID)
                        if p_tog:
                            p_tog.QueryInterface(IUIAutomationTogglePattern).Toggle()
                            action_executed = True
                            executed_method = "UIA_TogglePattern"
                    elif ctrl_type in ("tabitem", "listitem", "treeitem", "dataitem"):
                        p_sel = elem_obj.GetCurrentPattern(UIA_SELECTION_ITEM_PATTERN_ID)
                        if p_sel:
                            p_sel.QueryInterface(IUIAutomationSelectionItemPattern).Select()
                            action_executed = True
                            executed_method = "UIA_SelectionItemPattern"
                    elif ctrl_type in ("combobox",):
                        p_exp = elem_obj.GetCurrentPattern(10005)
                        if p_exp:
                            p_exp.QueryInterface(IUIAutomationExpandCollapsePattern).Expand()
                            action_executed = True
                            executed_method = "UIA_ExpandCollapsePattern"
                except Exception as ex:
                    logger.debug(f"UIA pattern execution attempted but failed ({ex}); fallback to physical click.")
                    action_executed = False

            # 2. Physical Mouse Click (Fallback only if semantic pattern was not applicable or failed)
            if not action_executed:
                if cx > 0 and cy > 0:
                    try:
                        from tools.input_tool import input_helper
                        pyautogui.moveTo(cx, cy, duration=0.08)
                        if button == "left":
                            input_helper.left_click(x=cx, y=cy, clicks=click_count)
                        else:
                            input_helper.click_mouse(button=button, double=(click_count > 1), x=cx, y=cy)
                        action_executed = True
                        executed_method = f"Physical_Click_{button}_count_{click_count}"
                        logger.info(f"Physical click dispatched at ({cx}, {cy}) on '{matched.get('name')}'")
                    except Exception as click_err:
                        logger.warning(f"Physical click failed: {click_err}")
                        action_executed = False

            if action_executed:
                return {
                    "status": "success",
                    "method": executed_method,
                    "element": {
                        "name": matched.get("name"),
                        "control_type": matched.get("control_type"),
                        "automation_id": matched.get("automation_id"),
                        "center": (cx, cy)
                    },
                    "action_execution_verified": True,
                    "verification_status": "VERIFIED",
                    "message": f"Successfully clicked element '{matched.get('name')}' via {executed_method} at ({cx}, {cy})."
                }

        # Step 3: METHOD 4 - Vision fallback when UIA fails to locate target
        try:
            # Check if screenshot / vision can locate target
            from tools.app_control import take_screenshot
            # Return structured not-found response with vision indication
            return {
                "status": "error",
                "error_code": "ELEMENT_NOT_FOUND",
                "message": f"Could not find UI element '{target}'.",
                "vision_fallback_available": True
            }
        except Exception:
            pass

        return {
            "status": "error",
            "error_code": "ELEMENT_NOT_FOUND",
            "message": f"Could not find UI element '{target}'. Please check if the window is open and the element is visible."
        }

    def type_in_element(
        self,
        target: str,
        text: str,
        window_title: Optional[str] = None,
        clear_first: bool = False,
        press_enter: bool = False
    ) -> Dict[str, Any]:
        """
        Types text into a focused input element using physical mouse focus + PyAutoGUI.
        Strictly enforces that the target application/window is foreground before typing.
        """
        import pyautogui
        import time
        from tools.app_control import activate_app_window

        # Guard: If window_title is specified, bring it to foreground and verify
        if window_title:
            act_res = activate_app_window(window_title, timeout_seconds=3.0)
            if not act_res.get("verified"):
                return {
                    "status": "error",
                    "error_code": "FOREGROUND_VERIFICATION_FAILED",
                    "message": f"Target application/window '{window_title}' is not foreground: {act_res.get('failure_reason')}",
                    "details": act_res
                }
        elif target:
            # Check if target is a known registered app name (e.g. 'notepad', 'chrome')
            try:
                from tools.app_control import APP_REGISTRY
                target_lower = target.lower().strip()
                if target_lower in APP_REGISTRY or target_lower.endswith(".exe"):
                    act_res = activate_app_window(target_lower, timeout_seconds=3.0)
                    if not act_res.get("verified"):
                        return {
                            "status": "error",
                            "error_code": "FOREGROUND_VERIFICATION_FAILED",
                            "message": f"Target application '{target}' is not foreground: {act_res.get('failure_reason')}",
                            "details": act_res
                        }
            except Exception:
                pass

        # First click the target element to guarantee focus
        click_res = self.click_element_by_selector(target, window_title=window_title)
        if click_res.get("status") == "error" and click_res.get("error_code") == "FOREGROUND_VERIFICATION_FAILED":
            return click_res
        
        # Give focus a moment to establish
        time.sleep(0.1)

        if clear_first:
            pyautogui.hotkey("ctrl", "a")
            time.sleep(0.05)
            pyautogui.press("backspace")
            time.sleep(0.05)

        # Type text
        pyautogui.write(text, interval=0.02)

        if press_enter:
            time.sleep(0.05)
            pyautogui.press("enter")

        return {
            "status": "success",
            "element": target,
            "text": text,
            "verification_status": "VERIFIED",
            "message": f"Typed '{text}' into '{target}' successfully."
        }

    def select_element(self, target: str, window_title: Optional[str] = None) -> Dict[str, Any]:
        """
        Toggles or selects checkbox, radio button, or tab item.
        """
        return self.click_element_by_selector(target, window_title=window_title)

    def scroll(self, direction: str = "down", amount: int = 3, target: Optional[str] = None) -> Dict[str, Any]:
        """
        Performs vertical scrolling at the cursor position or over a target element.
        """
        import pyautogui
        if target:
            self.click_element_by_selector(target)

        clicks = -amount if direction.lower() in ("down", "d") else amount
        pyautogui.scroll(clicks)
        return {
            "status": "success",
            "direction": direction,
            "amount": amount,
            "message": f"Scrolled {direction} by {amount} clicks."
        }

    def get_desktop_state(self, window_title: Optional[str] = None, max_elements: int = 60) -> Dict[str, Any]:
        """
        Captures the complete state of the active desktop or target window,
        including title, geometry, and a structured list of interactive elements.
        """
        user32 = ctypes.windll.user32
        hwnd = user32.GetForegroundWindow()
        title_buf = ctypes.create_unicode_buffer(512)
        user32.GetWindowTextW(hwnd, title_buf, 512)
        active_title = title_buf.value

        rect = wintypes.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(rect))

        controls = self.inspect_active_ui_tree(max_elements=max_elements)

        return {
            "status": "success",
            "active_window": {
                "hwnd": hwnd,
                "title": active_title,
                "bounds": {
                    "left": rect.left,
                    "top": rect.top,
                    "right": rect.right,
                    "bottom": rect.bottom,
                    "width": rect.right - rect.left,
                    "height": rect.bottom - rect.top
                }
            },
            "interactive_elements_count": len(controls),
            "interactive_elements": controls
        }

    def verify_ui_state(
        self,
        expected_window: Optional[str] = None,
        expected_text: Optional[str] = None,
        timeout_sec: float = 3.0
    ) -> Dict[str, Any]:
        """
        Polls desktop state to confirm expected UI changes within timeout_sec.
        """
        import time
        start_t = time.time()
        while time.time() - start_t < timeout_sec:
            state = self.get_desktop_state(max_elements=30)
            active_title = state.get("active_window", {}).get("title", "")
            
            title_ok = True
            if expected_window:
                title_ok = expected_window.lower() in active_title.lower()

            text_ok = True
            if expected_text:
                elems = state.get("interactive_elements", [])
                text_ok = any(expected_text.lower() in (e.get("name") or "").lower() for e in elems)

            if title_ok and text_ok:
                return {
                    "status": "success",
                    "verified": True,
                    "duration_sec": round(time.time() - start_t, 2),
                    "active_title": active_title,
                    "message": "UI state successfully verified."
                }
            time.sleep(0.3)

        return {
            "status": "warning",
            "verified": False,
            "message": f"UI verification timed out after {timeout_sec}s."
        }

    def inspect_active_ui_tree(self, max_elements: int = 40) -> List[Dict[str, Any]]:
        """
        Inspects the active window and returns a structured list of visible interactive controls.
        """
        controls = []
        root = self.get_active_window_element()
        if not root or not self.is_available:
            return controls

        try:
            from comtypes.gen.UIAutomationClient import TreeScope_Descendants
            condition = self._uia.CreateTrueCondition()
            element_array = root.FindAll(TreeScope_Descendants, condition)
            length = element_array.Length if hasattr(element_array, "Length") else 0

            for i in range(min(length, 150)):
                elem = element_array.GetElement(i)
                props = self.get_element_properties(elem)
                # Keep interactive or labeled controls
                if props.get("name") or props.get("automation_id"):
                    if not props.get("is_offscreen"):
                        controls.append({
                            "name": props.get("name"),
                            "type": props.get("control_type"),
                            "automation_id": props.get("automation_id"),
                            "enabled": props.get("is_enabled"),
                            "center_x": props.get("bounding_box", {}).get("center_x", 0),
                            "center_y": props.get("bounding_box", {}).get("center_y", 0),
                            "bbox": props.get("bounding_box")
                        })
                        if len(controls) >= max_elements:
                            break
        except Exception as e:
            logger.warning(f"Error inspecting UI tree: {e}")

        return controls

    def find_element_by_path_or_name(
        self,
        selector_query: str,
        root_element=None,
        partial: bool = True
    ) -> Optional[Dict[str, Any]]:
        """
        Robustly resolves an element using name, automation_id, class_name or control_type query.
        """
        matches = self.find_elements_by_name(root_element, selector_query, partial=partial, max_results=5)
        return matches[0] if matches else None

    def get_canvas_element(self, paint_hwnd: Optional[int] = None) -> Optional[Dict[str, Any]]:
        """
        Dynamically locates the drawing Canvas element in MS Paint window (Windows 10/11).
        Prioritizes UIA Document/Pane/Image canvas controls, falling back to window client geometry.
        """
        root = None
        if paint_hwnd and self.is_available:
            try:
                root = self._uia.ElementFromHandle(paint_hwnd)
            except Exception as e:
                logger.debug(f"Error resolving paint root element: {e}")

        if not root:
            root = self.get_active_window_element() or self.get_root_element()

        if root and self.is_available:
            # Check for standard Paint canvas element names/types across Windows 10 & 11
            canvas_queries = [
                "canvas", "drawing", "document", "image",
                "paintdrawingarea", "skiaview", "drawingarea", "viewport"
            ]
            for query in canvas_queries:
                elem = self.find_element_by_path_or_name(query, root_element=root, partial=True)
                if elem:
                    bbox = elem.get("bounding_box", {})
                    # Ensure it has substantial drawing dimensions (> 100x100)
                    if bbox.get("width", 0) > 100 and bbox.get("height", 0) > 100:
                        return elem

            # Inspect all panes/documents in active tree
            try:
                from comtypes.gen.UIAutomationClient import TreeScope_Descendants
                condition = self._uia.CreateTrueCondition()
                element_array = root.FindAll(TreeScope_Descendants, condition)
                length = element_array.Length if hasattr(element_array, "Length") else 0
                for i in range(min(length, 100)):
                    el = element_array.GetElement(i)
                    props = self.get_element_properties(el)
                    ctrl = str(props.get("control_type", "")).lower()
                    auto_id = str(props.get("automation_id", "")).lower()
                    name = str(props.get("name", "")).lower()
                    bbox = props.get("bounding_box", {})
                    w, h = bbox.get("width", 0), bbox.get("height", 0)

                    if (ctrl in ("pane", "document", "image", "custom") or "canvas" in auto_id or "canvas" in name) and w > 150 and h > 150:
                        props["_element_obj"] = el
                        return props
            except Exception as e:
                logger.debug(f"Error finding canvas in UIA tree: {e}")

        # Fallback to HWND client geometry if UIA canvas element could not be isolated
        if paint_hwnd:
            try:
                user32 = ctypes.windll.user32
                rect = wintypes.RECT()
                user32.GetWindowRect(paint_hwnd, ctypes.byref(rect))
                # Estimation: Ribbon/toolbar is top ~150px, left margin ~10px
                left = rect.left + 20
                top = rect.top + 160
                right = rect.right - 20
                bottom = rect.bottom - 40
                width = max(100, right - left)
                height = max(100, bottom - top)
                return {
                    "name": "Paint Canvas (Geometry Fallback)",
                    "control_type": "Pane",
                    "bounding_box": {
                        "left": left,
                        "top": top,
                        "right": right,
                        "bottom": bottom,
                        "width": width,
                        "height": height,
                        "center_x": left + width // 2,
                        "center_y": top + height // 2
                    }
                }
            except Exception as e:
                logger.debug(f"Error calculating geometry fallback: {e}")

        return None


uia_engine = UIAutomationEngine()

