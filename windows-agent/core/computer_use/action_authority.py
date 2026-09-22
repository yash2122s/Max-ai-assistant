"""
MAX AI — Authoritative Action Dispatcher (Action Authority)
The single authoritative physical-action routing layer for Windows Computer-Use.
Routes model intent to the optimal backend with exactly-once execution semantics:
1. Native application/API semantic backend
2. UIA semantic pattern (Invoke, Toggle, SelectionItem, Value, ExpandCollapse)
3. UIA element bounding-box physical interaction
4. Win32 / SendInput (via tools.input_tool.input_helper)
5. PyAutoGUI
6. Visual/OCR coordinate fallback when appropriate
"""

import sys
import time
import logging
import ctypes
from ctypes import wintypes
from typing import Dict, Any, Optional, Tuple, List

from core.computer_use.models import (
    UIElement, UIMap, ActionType, ComputerUseAction, Point, Rect, RiskLevel, ElementSource
)
from core.computer_use.coordinate_transform import coordinate_transformer
from core.computer_use.errors import ActionExecutionError, SafetyViolationError
from core.confirmation_gate import confirmation_gate

logger = logging.getLogger("MAX.ComputerUse.ActionAuthority")


class ActionAuthority:
    """Authoritative action dispatcher selecting optimal backends with exactly-once execution."""

    def __init__(self):
        self.transformer = coordinate_transformer

    @property
    def input_helper(self):
        from tools.input_tool import input_helper
        return input_helper

    def execute(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement] = None,
        pre_map: Optional[Any] = None
    ) -> Dict[str, Any]:
        """
        Authoritatively dispatches ComputerUseAction.
        Selects exactly one primary backend, collects execution evidence,
        and falls back only upon explicit failure evidence.
        """
        t0 = time.time()
        a_type = action.action_type

        # Safety gate check for HIGH risk actions
        if action.risk_level == RiskLevel.HIGH:
            logger.warning(f"🔒 HIGH-risk action intercepted by ActionAuthority: {action.description}")
            gate_res = confirmation_gate.request_confirmation(
                action_name=f"computer_use_{a_type.value}",
                description=action.description or f"{a_type.value} on {target_element.name if target_element else 'system'}",
                target=target_element.name if target_element else (action.target_query or "system"),
                action_fn=lambda: self._execute_internal(action, target_element, pre_map, t0)
            )
            return gate_res

        return self._execute_internal(action, target_element, pre_map, t0)

    def _execute_internal(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement],
        pre_map: Optional[Any],
        t0: float
    ) -> Dict[str, Any]:
        a_type = action.action_type
        logger.info(f"⚡ ActionAuthority: Dispatching {a_type.value} [target={target_element.ref if target_element else action.coordinates}]")

        target_id = target_element.ref if target_element else (action.target_query or "coordinates")
        evidence = {
            "backend_used": "unknown",
            "target_identity": target_id,
            "target_coordinates": None,
            "target_hwnd": target_element.native_handle if target_element else None,
            "foreground_hwnd": self._get_foreground_hwnd(),
            "dispatch_evidence": {},
            "timing_ms": 0,
            "status": "pending",
            "error": None
        }

        try:
            if a_type == ActionType.CLICK:
                res = self._dispatch_click(action, target_element, click_count=1, button=action.button or "left", evidence=evidence)
            elif a_type == ActionType.DOUBLE_CLICK:
                res = self._dispatch_click(action, target_element, click_count=2, button="left", evidence=evidence)
            elif a_type == ActionType.RIGHT_CLICK:
                res = self._dispatch_click(action, target_element, click_count=1, button="right", evidence=evidence)
            elif a_type == ActionType.MIDDLE_CLICK:
                res = self._dispatch_click(action, target_element, click_count=1, button="middle", evidence=evidence)
            elif a_type == ActionType.MOVE_MOUSE:
                res = self._dispatch_move_mouse(action, target_element, evidence=evidence)
            elif a_type == ActionType.MOUSE_DOWN:
                res = self._dispatch_mouse_down(action, target_element, evidence=evidence)
            elif a_type == ActionType.MOUSE_UP:
                res = self._dispatch_mouse_up(action, target_element, evidence=evidence)
            elif a_type == ActionType.DRAG_AND_DROP:
                res = self._dispatch_drag_and_drop(action, target_element, pre_map=pre_map, evidence=evidence)
            elif a_type == ActionType.TYPE_TEXT:
                res = self._dispatch_type_text(action, target_element, evidence=evidence)
            elif a_type == ActionType.PRESS_KEY:
                res = self._dispatch_press_key(action, evidence=evidence)
            elif a_type == ActionType.HOTKEY:
                res = self._dispatch_hotkey(action, evidence=evidence)
            elif a_type == ActionType.SCROLL:
                res = self._dispatch_scroll(action, target_element, evidence=evidence)
            elif a_type == ActionType.ACTIVATE_WINDOW:
                res = self._dispatch_activate_window(action, target_element, evidence=evidence)
            elif a_type == ActionType.WAIT:
                wait_sec = min(action.timeout_sec or 1.0, 5.0)
                time.sleep(wait_sec)
                evidence["backend_used"] = "timer"
                evidence["status"] = "success"
                res = {"status": "success", "message": f"Waited {wait_sec}s"}
            else:
                raise ActionExecutionError(f"Unsupported action type: {a_type}")

            dur_ms = int((time.time() - t0) * 1000)
            evidence["timing_ms"] = dur_ms
            evidence["status"] = res.get("status", "success")

            res["execution_evidence"] = evidence
            res["action_execution_verified"] = (res.get("status") == "success")
            res["duration_ms"] = dur_ms
            return res

        except Exception as e:
            dur_ms = int((time.time() - t0) * 1000)
            evidence["timing_ms"] = dur_ms
            evidence["status"] = "error"
            evidence["error"] = str(e)
            logger.error(f"ActionAuthority execution failure on {a_type.value}: {e}", exc_info=True)
            return {
                "status": "error",
                "error": str(e),
                "action": a_type.value,
                "action_execution_verified": False,
                "execution_evidence": evidence,
                "duration_ms": dur_ms
            }

    def _get_foreground_hwnd(self) -> int:
        try:
            return int(ctypes.windll.user32.GetForegroundWindow())
        except Exception:
            return 0

    def _dispatch_click(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement],
        click_count: int = 1,
        button: str = "left",
        evidence: Dict[str, Any] = None
    ) -> Dict[str, Any]:
        """
        Deterministic click dispatch:
        Backend Priority:
        1. UIA Semantic Pattern (if target is standard Button/Toggle/Select and single left-click)
        2. Win32 physical click via input_helper
        3. PyAutoGUI fallback
        """
        # Step 1: Attempt UIA Semantic Pattern if element is native UIA and left-click single
        if target_element and click_count == 1 and button == "left":
            ctrl_type = str(target_element.control_type or target_element.role or "").lower()
            native_h = target_element.native_handle
            if native_h and self._invoke_uia_pattern_by_handle(native_h, ctrl_type):
                if evidence is not None:
                    evidence["backend_used"] = "uia_semantic_pattern"
                    evidence["dispatch_evidence"] = {"pattern_invoked": True, "control_type": ctrl_type}
                return {
                    "status": "success",
                    "action": "click",
                    "backend": "uia_semantic_pattern",
                    "message": f"Clicked element '{target_element.name}' via UIA semantic pattern."
                }

        # Step 2: Determine coordinates
        point = None
        if target_element and target_element.center:
            point = target_element.center
        elif action.coordinates:
            point = action.coordinates
        elif action.target_point:
            point = action.target_point
        elif action.x is not None and action.y is not None:
            point = Point(float(action.x), float(action.y))
        else:
            raise ActionExecutionError("No target coordinates or element available for click.")

        phys_point = self.transformer.logical_to_physical(point)
        clamped = self.transformer.clamp_to_screen(phys_point)
        target_x, target_y = clamped.to_tuple()

        if evidence is not None:
            evidence["target_coordinates"] = (target_x, target_y)

        # Step 3: Primary Physical Dispatch via Win32 (tools.input_tool.input_helper)
        try:
            self.input_helper.move_mouse(target_x, target_y, duration_sec=0.08)
            time.sleep(0.02)
            if button == "left":
                input_res = self.input_helper.left_click(target_x, target_y, clicks=click_count)
            else:
                input_res = self.input_helper.click_mouse(button=button, double=(click_count > 1), x=target_x, y=target_y)

            if input_res.get("status") == "success":
                if evidence is not None:
                    evidence["backend_used"] = "win32_input_helper"
                    evidence["dispatch_evidence"] = input_res
                return {
                    "status": "success",
                    "action": "click",
                    "backend": "win32_input_helper",
                    "coordinates": [target_x, target_y],
                    "button": button,
                    "click_count": click_count,
                    "message": f"Clicked at ({target_x}, {target_y}) [{button}, count={click_count}]"
                }
        except Exception as ex:
            logger.warning(f"Win32 input_helper click failed ({ex}); trying PyAutoGUI fallback...")

        # Step 4: Fallback to PyAutoGUI
        try:
            import pyautogui
            pyautogui.FAILSAFE = False
            pyautogui.moveTo(target_x, target_y, duration=0.1)
            if click_count == 2:
                pyautogui.doubleClick(target_x, target_y, button=button)
            else:
                pyautogui.click(target_x, target_y, button=button)

            if evidence is not None:
                evidence["backend_used"] = "pyautogui_fallback"
            return {
                "status": "success",
                "action": "click",
                "backend": "pyautogui_fallback",
                "coordinates": [target_x, target_y],
                "message": f"Clicked at ({target_x}, {target_y}) via PyAutoGUI"
            }
        except Exception as pe:
            raise ActionExecutionError(f"All click backends failed: {pe}")

    def _invoke_uia_pattern_by_handle(self, hwnd: int, ctrl_type: str) -> bool:
        """Invokes appropriate UIA pattern for given window handle without duplicating clicks."""
        try:
            import comtypes.client
            from comtypes.gen.UIAutomationClient import (
                CUIAutomation, IUIAutomation,
                IUIAutomationInvokePattern,
                IUIAutomationTogglePattern,
                IUIAutomationSelectionItemPattern,
                IUIAutomationExpandCollapsePattern
            )
            uia = comtypes.client.CreateObject(CUIAutomation, interface=IUIAutomation)
            elem = uia.ElementFromHandle(hwnd)
            if not elem:
                return False

            if ctrl_type in ("button", "menuitem", "hyperlink"):
                p = elem.GetCurrentPattern(10000)  # UIA_InvokePatternId
                if p:
                    p.QueryInterface(IUIAutomationInvokePattern).Invoke()
                    return True
            elif ctrl_type in ("checkbox", "radiobutton"):
                p = elem.GetCurrentPattern(10015)  # UIA_TogglePatternId
                if p:
                    p.QueryInterface(IUIAutomationTogglePattern).Toggle()
                    return True
            elif ctrl_type in ("tabitem", "listitem"):
                p = elem.GetCurrentPattern(10010)  # UIA_SelectionItemPatternId
                if p:
                    p.QueryInterface(IUIAutomationSelectionItemPattern).Select()
                    return True
            return False
        except Exception:
            return False

    def _dispatch_move_mouse(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement],
        evidence: Dict[str, Any] = None
    ) -> Dict[str, Any]:
        point = None
        if target_element and target_element.center:
            point = target_element.center
        elif action.coordinates:
            point = action.coordinates
        elif action.x is not None and action.y is not None:
            point = Point(float(action.x), float(action.y))
        else:
            raise ActionExecutionError("No destination coordinates provided for move_mouse.")

        phys_point = self.transformer.logical_to_physical(point)
        clamped = self.transformer.clamp_to_screen(phys_point)
        tx, ty = clamped.to_tuple()

        res = self.input_helper.move_mouse(tx, ty, duration_sec=action.duration or 0.1)
        if evidence is not None:
            evidence["backend_used"] = "win32_input_helper"
            evidence["target_coordinates"] = (tx, ty)
            evidence["dispatch_evidence"] = res
        return {"status": "success", "action": "move_mouse", "coordinates": [tx, ty]}

    def _dispatch_mouse_down(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement],
        evidence: Dict[str, Any] = None
    ) -> Dict[str, Any]:
        button = action.button or "left"
        x, y = None, None
        if target_element and target_element.center:
            clamped = self.transformer.clamp_to_screen(self.transformer.logical_to_physical(target_element.center))
            x, y = clamped.to_tuple()
        elif action.coordinates:
            clamped = self.transformer.clamp_to_screen(self.transformer.logical_to_physical(action.coordinates))
            x, y = clamped.to_tuple()
        elif action.x is not None and action.y is not None:
            x, y = int(action.x), int(action.y)

        res = self.input_helper.mouse_down(x=x, y=y, button=button)
        if evidence is not None:
            evidence["backend_used"] = "win32_input_helper"
            evidence["target_coordinates"] = (x, y) if x is not None else None
            evidence["dispatch_evidence"] = res
        return {"status": "success", "action": "mouse_down", "button": button, "coordinates": [x, y]}

    def _dispatch_mouse_up(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement],
        evidence: Dict[str, Any] = None
    ) -> Dict[str, Any]:
        button = action.button or "left"
        x, y = None, None
        if target_element and target_element.center:
            clamped = self.transformer.clamp_to_screen(self.transformer.logical_to_physical(target_element.center))
            x, y = clamped.to_tuple()
        elif action.coordinates:
            clamped = self.transformer.clamp_to_screen(self.transformer.logical_to_physical(action.coordinates))
            x, y = clamped.to_tuple()
        elif action.x is not None and action.y is not None:
            x, y = int(action.x), int(action.y)

        res = self.input_helper.mouse_up(x=x, y=y, button=button)
        if evidence is not None:
            evidence["backend_used"] = "win32_input_helper"
            evidence["target_coordinates"] = (x, y) if x is not None else None
            evidence["dispatch_evidence"] = res
        return {"status": "success", "action": "mouse_up", "button": button, "coordinates": [x, y]}

    def _dispatch_drag_and_drop(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement],
        pre_map: Optional[UIMap] = None,
        evidence: Dict[str, Any] = None
    ) -> Dict[str, Any]:
        """
        Flow:
        resolve source -> resolve destination -> move to source -> mouse_down
        -> controlled move path -> mouse_up -> return evidence.
        Guaranteed mouse_up in error conditions to prevent cursor lock.
        """
        button = action.button or "left"
        duration = max(0.2, action.duration or 0.6)

        # Source coordinates
        src_pt = None
        if action.source_point:
            src_pt = action.source_point
        elif target_element and target_element.center:
            src_pt = target_element.center
        elif action.coordinates:
            src_pt = action.coordinates
        elif action.x is not None and action.y is not None:
            src_pt = Point(float(action.x), float(action.y))
        else:
            raise ActionExecutionError("Drag and drop requires valid source coordinates.")

        # Destination coordinates
        dst_pt = None
        if action.destination_point:
            dst_pt = action.destination_point
        elif action.target_point:
            dst_pt = action.target_point
        elif action.destination_ref and pre_map:
            dst_el = pre_map.get_element_by_ref(action.destination_ref)
            if dst_el and dst_el.center:
                dst_pt = dst_el.center
        elif action.destination_ref:
            try:
                from core.computer_use.ui_ref_cache import ui_ref_cache
                dst_el, _ = ui_ref_cache.validate_and_get(action.destination_ref)
                if dst_el and dst_el.center:
                    dst_pt = dst_el.center
            except Exception:
                pass

        if not dst_pt:
            raise ActionExecutionError("Drag and drop requires valid destination coordinates (destination_point, target_point, or destination_ref).")

        clamped_src = self.transformer.clamp_to_screen(self.transformer.logical_to_physical(src_pt)).to_tuple()
        clamped_dst = self.transformer.clamp_to_screen(self.transformer.logical_to_physical(dst_pt)).to_tuple()

        if evidence is not None:
            evidence["target_coordinates"] = {"source": clamped_src, "destination": clamped_dst}

        logger.info(f"🖱️ DragAndDrop: Moving from {clamped_src} -> {clamped_dst} over {duration}s")
        res = self.input_helper.drag_and_drop(
            start_x=clamped_src[0],
            start_y=clamped_src[1],
            end_x=clamped_dst[0],
            end_y=clamped_dst[1],
            duration_sec=duration,
            button=button,
            steps=20
        )

        if res.get("status") != "success":
            raise ActionExecutionError(f"Drag and drop execution failed: {res.get('error')}")

        if evidence is not None:
            evidence["backend_used"] = "win32_drag_and_drop"
            evidence["dispatch_evidence"] = res

        return {
            "status": "success",
            "action": "drag_and_drop",
            "backend": "win32_drag_and_drop",
            "source": list(clamped_src),
            "destination": list(clamped_dst),
            "duration": duration,
            "message": f"Successfully dragged from {clamped_src} to {clamped_dst}."
        }

    def _dispatch_type_text(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement],
        evidence: Dict[str, Any] = None
    ) -> Dict[str, Any]:
        text = action.text
        if text is None:
            raise ActionExecutionError("No text provided for type_text action.")

        # If target element provided, focus it first with a single click
        if target_element:
            self._dispatch_click(action, target_element, click_count=1, button="left")
            time.sleep(0.08)

        try:
            # Fast Unicode clipboard paste for multi-character text
            if len(text) > 4 or any(ord(c) > 127 for c in text):
                import pyperclip
                import pyautogui
                pyautogui.FAILSAFE = False
                pyperclip.copy(text)
                time.sleep(0.03)
                pyautogui.hotkey("ctrl", "v")
                if evidence is not None:
                    evidence["backend_used"] = "clipboard_paste"
            else:
                input_res = self.input_helper.type_text(text, interval=0.02)
                if input_res.get("status") != "success":
                    import pyautogui
                    pyautogui.write(text, interval=0.02)
                if evidence is not None:
                    evidence["backend_used"] = "win32_keybd_event"

            return {
                "status": "success",
                "action": "type_text",
                "length": len(text),
                "message": f"Typed text of length {len(text)} into target."
            }
        except Exception as e:
            raise ActionExecutionError(f"Text input failed: {e}")

    def _dispatch_press_key(self, action: ComputerUseAction, evidence: Dict[str, Any] = None) -> Dict[str, Any]:
        key = action.key
        if not key:
            raise ActionExecutionError("No key provided for press_key action.")

        res = self.input_helper.press_key(key)
        if res.get("status") != "success":
            import pyautogui
            pyautogui.FAILSAFE = False
            pyautogui.press(key.lower())
            if evidence is not None:
                evidence["backend_used"] = "pyautogui_press"
        else:
            if evidence is not None:
                evidence["backend_used"] = "win32_input_helper"

        return {"status": "success", "action": "press_key", "key": key}

    def _dispatch_hotkey(self, action: ComputerUseAction, evidence: Dict[str, Any] = None) -> Dict[str, Any]:
        keys = list(action.modifiers)
        if action.key and action.key not in keys:
            keys.append(action.key)
        if not keys:
            raise ActionExecutionError("No keys provided for hotkey action.")

        backend = "win32_key_combo"
        res = self.input_helper.key_combo(keys)
        if res.get("status") != "success":
            import pyautogui
            pyautogui.FAILSAFE = False
            pyautogui.hotkey(*[k.lower() for k in keys])
            backend = "pyautogui_hotkey"
            if evidence is not None:
                evidence["backend_used"] = "pyautogui_hotkey"
        else:
            if evidence is not None:
                evidence["backend_used"] = "win32_key_combo"

        return {"status": "success", "action": "hotkey", "backend": backend, "keys": keys}

    def _dispatch_scroll(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement],
        evidence: Dict[str, Any] = None
    ) -> Dict[str, Any]:
        delta = action.scroll_delta or action.amount or -300
        if action.direction and action.direction.lower() in ("up", "u"):
            delta = abs(delta)
        elif action.direction and action.direction.lower() in ("down", "d"):
            delta = -abs(delta)

        if target_element:
            self._dispatch_click(action, target_element, click_count=1, button="left")
            time.sleep(0.04)

        try:
            import pyautogui
            pyautogui.FAILSAFE = False
            pyautogui.scroll(delta)
            if evidence is not None:
                evidence["backend_used"] = "pyautogui_scroll"
            return {"status": "success", "action": "scroll", "delta": delta}
        except Exception:
            clicks = delta // 120 or (1 if delta > 0 else -1)
            self.input_helper.scroll_mouse(amount=clicks)
            if evidence is not None:
                evidence["backend_used"] = "win32_scroll"
            return {"status": "success", "action": "scroll", "delta": delta}

    def _dispatch_activate_window(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement],
        evidence: Dict[str, Any] = None
    ) -> Dict[str, Any]:
        """
        Routes foreground activation through tools.app_control.activate_app_window()
        Authoritative foreground activation with restore, foreground lock bypass, and verification.
        """
        from tools.app_control import activate_app_window

        target = None
        if target_element:
            target = target_element.native_handle or target_element.name or target_element.window
        elif action.window_title:
            target = action.window_title
        elif action.target_query:
            target = action.target_query

        if not target:
            raise ActionExecutionError("No window target specified for activation.")

        logger.info(f"🪟 ActionAuthority: Activating window for target '{target}' via activate_app_window()")
        act_res = activate_app_window(target, timeout_seconds=action.timeout_sec or 3.0)

        if evidence is not None:
            evidence["backend_used"] = "tools.app_control.activate_app_window"
            evidence["dispatch_evidence"] = act_res
            evidence["foreground_hwnd"] = act_res.get("hwnd", 0)

        if act_res.get("verified") or act_res.get("status") == "success":
            return {
                "status": "success",
                "action": "activate_window",
                "backend": "tools.app_control.activate_app_window",
                "verified": True,
                "hwnd": act_res.get("hwnd"),
                "pid": act_res.get("pid"),
                "window_title": act_res.get("window_title"),
                "process_name": act_res.get("process_name"),
                "message": f"Successfully activated window '{act_res.get('window_title')}' (HWND: {act_res.get('hwnd')})"
            }
        else:
            return {
                "status": "error",
                "action": "activate_window",
                "verified": False,
                "failure_reason": act_res.get("failure_reason"),
                "message": act_res.get("message") or f"Could not verify window activation for '{target}'"
            }


action_authority = ActionAuthority()
