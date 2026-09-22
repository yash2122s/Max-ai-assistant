"""
MAX AI — Computer Use Master Engine
Orchestrates the authoritative closed-loop Observe -> Resolve -> Safety Check -> Act -> Verify cycle.
"""

import time
import logging
from typing import Dict, Any, Optional, List, Callable, Tuple

from core.computer_use.models import (
    UIElement, UIMap, ComputerUseAction, ActionType, RiskLevel,
    ResolutionResult, VerificationResult, Point
)
from core.computer_use.screen_perception import screen_perception
from core.computer_use.target_resolver import target_resolver
from core.computer_use.action_executor import action_executor
from core.computer_use.action_authority import action_authority
from core.computer_use.verification import computer_use_verifier
from core.computer_use.ui_ref_cache import ui_ref_cache
from core.computer_use.errors import (
    ComputerUseError, StaleRefError, TargetNotFoundError,
    AmbiguousTargetError, ActionExecutionError, SafetyViolationError
)
from core.confirmation_gate import confirmation_gate

logger = logging.getLogger("MAX.ComputerUse.Engine")


class ComputerUseEngine:
    """Master closed-loop Computer Use subsystem for MAX Windows AI Agent."""

    def __init__(self):
        self.perception = screen_perception
        self.resolver = target_resolver
        self.executor = action_executor
        self.authority = action_authority
        self.verifier = computer_use_verifier
        self.ref_cache = ui_ref_cache
        self._ui_event_callback: Optional[Callable[[Dict[str, Any]], None]] = None

    def register_ui_event_callback(self, callback: Callable[[Dict[str, Any]], None]):
        """Registers callback to notify MAX WPF Dynamic Island overlay."""
        self._ui_event_callback = callback

    def _notify_ui(self, event_type: str, data: Optional[Dict[str, Any]] = None):
        """Dispatches real-time Computer Use state updates to the UI Bridge and EventBus."""
        ev_upper = event_type.upper()
        if "OBSERV" in ev_upper or "STARTED" in ev_upper:
            default_phase = "OBSERVE"
        elif "TARGET" in ev_upper or "RESOLV" in ev_upper or "RECOVER" in ev_upper:
            default_phase = "RESOLVE"
        elif "SAFETY" in ev_upper or "GATE" in ev_upper:
            default_phase = "SAFETY"
        elif "ACT" in ev_upper:
            default_phase = "ACT"
        elif "VERIF" in ev_upper or "SUCCESS" in ev_upper:
            default_phase = "VERIFY"
        elif "FAIL" in ev_upper or "ERROR" in ev_upper:
            default_phase = "FAILED"
        else:
            default_phase = "IDLE"

        payload = {
            "type": event_type.lower(),
            "event_name": event_type,
            "phase": default_phase,
            "timestamp": time.time()
        }
        if data:
            payload.update(data)
        if self._ui_event_callback:
            try:
                self._ui_event_callback(payload)
            except Exception as e:
                logger.debug(f"UI notification error: {e}")
        try:
            from server.event_bus import event_bus
            from protocol.event_types import EventType
            event_bus.publish(EventType.COMPUTER_USE, payload)
        except Exception:
            pass

    def observe_screen(self, include_ocr: bool = True) -> Dict[str, Any]:
        """Performs a full screen observation cycle and returns UI map data."""
        self._notify_ui("COMPUTER_USE_OBSERVING", {
            "phase": "OBSERVE",
            "status": "Capturing desktop screen and analyzing UI elements..."
        })
        ui_map = self.perception.observe(include_ocr=include_ocr)

        # Prepare serializable elements list for UI
        element_dicts = []
        for el in ui_map.elements[:80]:
            try:
                element_dicts.append({
                    "ref_id": el.ref,
                    "label": el.name or el.role,
                    "x": int(el.bounds.left),
                    "y": int(el.bounds.top),
                    "width": int(el.bounds.width),
                    "height": int(el.bounds.height),
                    "source": el.source.value if hasattr(el.source, 'value') else str(el.source),
                    "confidence": float(el.confidence)
                })
            except Exception:
                pass

        self._notify_ui("COMPUTER_USE_OBSERVED", {
            "phase": "OBSERVE",
            "active_window": ui_map.active_window,
            "active_process": ui_map.active_process,
            "screenshot_path": ui_map.screenshot_path,
            "element_count": len(ui_map.elements),
            "elements": element_dicts,
            "status": f"Observed {len(ui_map.elements)} elements in '{ui_map.active_window}'"
        })

        return {
            "status": "success",
            "generation": ui_map.generation,
            "active_window": ui_map.active_window,
            "active_process": ui_map.active_process,
            "element_count": len(ui_map.elements),
            "summary": ui_map.to_summary(max_elements=30),
            "elements": [el.to_dict() for el in ui_map.elements[:50]],
            "screenshot_path": ui_map.screenshot_path
        }

    def resolve_target(self, query: str) -> Dict[str, Any]:
        """Resolves target description to an ephemeral @ref and element."""
        res = self.resolver.resolve(query)
        if res.status == "FOUND" and res.matched:
            el = res.matched.element
            self._notify_ui("COMPUTER_USE_TARGET_FOUND", {
                "phase": "RESOLVE",
                "target": query,
                "ref": res.matched.ref,
                "confidence": res.matched.confidence,
                "coordinates": [int(el.center.x), int(el.center.y)],
                "target_box": {
                    "ref_id": el.ref,
                    "label": el.name or el.role,
                    "x": int(el.bounds.left),
                    "y": int(el.bounds.top),
                    "width": int(el.bounds.width),
                    "height": int(el.bounds.height),
                },
                "status": f"Resolved '{query}' -> [{el.role}] '{el.name}' ({res.matched.ref}) at ({int(el.center.x)}, {int(el.center.y)})"
            })
            return {
                "status": "success",
                "ref": res.matched.ref,
                "role": res.matched.element.role,
                "name": res.matched.element.name,
                "confidence": res.matched.confidence,
                "center": [res.matched.element.center.x, res.matched.element.center.y],
                "message": res.message
            }
        elif res.status == "AMBIGUOUS":
            self._notify_ui("COMPUTER_USE_FAILED", {
                "phase": "RESOLVE",
                "status": f"Ambiguous target: {res.message}"
            })
            return {
                "status": "ambiguous",
                "message": res.message,
                "candidates": res.matched.competing_candidates if res.matched else []
            }
        else:
            self._notify_ui("COMPUTER_USE_FAILED", {
                "phase": "RESOLVE",
                "status": f"Target not found: {res.message}"
            })
            return {
                "status": "not_found",
                "message": res.message
            }

    def step(
        self,
        action_type: str,
        target_ref: Optional[str] = None,
        target_query: Optional[str] = None,
        coordinates: Optional[Point] = None,
        target_point: Optional[Point] = None,
        x: Optional[int] = None,
        y: Optional[int] = None,
        source_ref: Optional[str] = None,
        source_query: Optional[str] = None,
        source_point: Optional[Point] = None,
        destination_ref: Optional[str] = None,
        destination_query: Optional[str] = None,
        destination_point: Optional[Point] = None,
        button: str = "left",
        duration: float = 0.5,
        amount: Optional[int] = None,
        direction: Optional[str] = None,
        text: Optional[str] = None,
        key: Optional[str] = None,
        modifiers: Optional[List[str]] = None,
        scroll_delta: int = 0,
        risk_level: str = "LOW",
        description: str = "",
        window_title: Optional[str] = None,
        generation: Optional[int] = None,
        settle_delay_sec: float = 0.3
    ) -> Dict[str, Any]:
        """
        Executes one authoritative closed-loop step:
        OBSERVE -> RESOLVE -> SAFETY GATE -> ACT -> WAIT -> RE-OBSERVE -> VERIFY.
        Supports full action envelope including drag_and_drop and automatic stale-target re-resolution.
        """
        t0 = time.time()
        self._notify_ui("COMPUTER_USE_STARTED", {"action": action_type, "target": target_ref or target_query})

        # 0. If window_title specified, ensure window or active child dialog is foreground before observing
        effective_window = window_title
        if window_title:
            win_info = self.perception.uia_provider.get_active_window_info()
            fg_title = win_info.get("title", "")
            fg_low = fg_title.lower()
            wt_low = window_title.lower()

            is_already_active = (wt_low in fg_low or fg_low in wt_low)
            is_modal_dialog = any(d in fg_low for d in ("save as", "save", "open", "confirm", "replace", "dialog"))

            if is_already_active or is_modal_dialog:
                logger.info(f"🪟 Current active window '{fg_title}' is already foreground/dialog for target '{window_title}'")
                effective_window = fg_title
            else:
                from tools.app_control import activate_app_window
                act_res = activate_app_window(window_title, timeout_seconds=2.5)
                if not act_res.get("verified"):
                    # Check if the reason is a modal child dialog
                    re_info = self.perception.uia_provider.get_active_window_info()
                    re_title = re_info.get("title", "")
                    if re_title and any(d in re_title.lower() for d in ("save as", "save", "open", "confirm", "replace")):
                        logger.info(f"🪟 Child dialog '{re_title}' is active in foreground instead of parent '{window_title}'")
                        effective_window = re_title
                    else:
                        logger.warning(f"Window '{window_title}' could not be verified in foreground: {act_res.get('failure_reason')}")

        # 1. OBSERVE (or use fresh active map if available)
        pre_map = self.ref_cache.current_map
        if not pre_map:
            self._notify_ui("COMPUTER_USE_OBSERVING")
            pre_map = self.perception.observe()

        # 2. RESOLVE Target with automatic stale ref re-resolution
        target_element: Optional[UIElement] = None
        if target_ref:
            try:
                target_element, pre_map = self.ref_cache.validate_and_get(target_ref, expected_generation=generation)
                logger.info(f"🎯 Validated fresh ref '{target_ref}': [{target_element.role}] '{target_element.name}'")
            except StaleRefError as se:
                logger.warning(f"⚠️ Stale ref encountered: {se}. Auto re-observing & re-resolving...")
                self._notify_ui("COMPUTER_USE_RECOVERING", {"reason": "stale_ref"})
                pre_map = self.perception.observe()
                if target_query:
                    res = self.resolver.resolve(target_query, pre_map)
                    if res.status == "FOUND" and res.matched:
                        target_element = res.matched.element
                    else:
                        return {"status": "error", "error_code": "STALE_REF", "message": str(se)}
                else:
                    return {"status": "error", "error_code": "STALE_REF", "message": str(se)}
        elif target_query:
            res = self.resolver.resolve(target_query, pre_map, expected_generation=generation, expected_window=effective_window)
            if res.status == "FOUND" and res.matched:
                target_element = res.matched.element
                self._notify_ui("COMPUTER_USE_TARGET_FOUND", {"ref": target_element.ref, "name": target_element.name})
            elif res.status == "AMBIGUOUS":
                self._notify_ui("COMPUTER_USE_FAILED", {"reason": "ambiguous_target"})
                return {
                    "status": "error",
                    "error_code": "AMBIGUOUS_TARGET",
                    "message": res.message,
                    "candidates": res.matched.competing_candidates if res.matched else []
                }
            else:
                # TARGET_NOT_FOUND: Trigger Adaptive Computer-Use Recovery Pipeline
                logger.info(f"🔄 TARGET_NOT_FOUND for '{target_query}'. Entering Adaptive Recovery Pipeline...")
                self._notify_ui("COMPUTER_USE_RECOVERING", {"reason": "target_not_found", "query": target_query})
                
                r_enum = RiskLevel(risk_level.upper()) if risk_level.upper() in RiskLevel.__members__ else RiskLevel.LOW
                rec_element, rec_map, rec_strategy = self._adaptive_recovery_resolve(
                    target_query=target_query,
                    risk_level=r_enum
                )
                
                if rec_element and rec_map:
                    target_element = rec_element
                    pre_map = rec_map
                    logger.info(f"✅ Adaptive Recovery succeeded: Resolved '{target_query}' via {rec_strategy} -> [{target_element.role}] '{target_element.name}'")
                    self._notify_ui("COMPUTER_USE_TARGET_FOUND", {"ref": target_element.ref, "name": target_element.name, "strategy": rec_strategy})
                else:
                    self._notify_ui("COMPUTER_USE_FAILED", {"reason": "target_not_found_after_recovery"})
                    return {
                        "status": "error",
                        "error_code": "TARGET_NOT_FOUND",
                        "message": f"Target '{target_query}' could not be located on screen after multi-tier adaptive recovery ({rec_strategy})."
                    }

        # Resolve drag_and_drop source and destination if applicable
        resolved_src_pt = source_point
        resolved_dst_pt = destination_point or target_point
        if action_type.lower() == ActionType.DRAG_AND_DROP.value:
            if source_ref and not resolved_src_pt:
                try:
                    s_el, _ = self.ref_cache.validate_and_get(source_ref)
                    resolved_src_pt = s_el.center
                except Exception:
                    pass
            elif source_query and not resolved_src_pt:
                s_res = self.resolver.resolve(source_query, pre_map)
                if s_res.status == "FOUND" and s_res.matched:
                    resolved_src_pt = s_res.matched.element.center

            if destination_ref and not resolved_dst_pt:
                try:
                    d_el, _ = self.ref_cache.validate_and_get(destination_ref)
                    resolved_dst_pt = d_el.center
                except Exception:
                    pass
            elif destination_query and not resolved_dst_pt:
                d_res = self.resolver.resolve(destination_query, pre_map)
                if d_res.status == "FOUND" and d_res.matched:
                    resolved_dst_pt = d_res.matched.element.center

        # 3. CONSTRUCT ACTION & SAFETY GATE CHECK
        r_level = RiskLevel(risk_level.upper()) if risk_level.upper() in RiskLevel.__members__ else RiskLevel.LOW
        parsed_action_type = ActionType(action_type.lower()) if action_type.lower() in [a.value for a in ActionType] else ActionType.CLICK
        
        # Coordinates mapping
        coords = coordinates
        if not coords and x is not None and y is not None:
            coords = Point(float(x), float(y))

        action_obj = ComputerUseAction(
            action_type=parsed_action_type,
            target_ref=target_element.ref if target_element else target_ref,
            target_query=target_query,
            coordinates=coords,
            target_point=resolved_dst_pt,
            x=x,
            y=y,
            source_ref=source_ref,
            source_query=source_query,
            source_point=resolved_src_pt,
            destination_ref=destination_ref,
            destination_query=destination_query,
            destination_point=resolved_dst_pt,
            button=button,
            duration=duration,
            amount=amount,
            direction=direction,
            text=text,
            key=key,
            modifiers=modifiers or [],
            scroll_delta=scroll_delta or (amount or 0),
            risk_level=r_level,
            description=description or f"{action_type} on {target_element.name if target_element else target_query}",
            window_title=window_title,
            generation=generation
        )

        if r_level == RiskLevel.HIGH:
            logger.warning(f"🔒 HIGH-risk Computer Use action parked behind Safety Gate: {action_obj.description}")
            gate_res = confirmation_gate.request_confirmation(
                action_name=f"computer_use_{action_type}",
                description=action_obj.description,
                target=target_element.name if target_element else (target_query or "system"),
                action_fn=lambda: self._execute_and_verify(action_obj, target_element, pre_map, settle_delay_sec)
            )
            return gate_res

        # 4. EXECUTE & VERIFY
        return self._execute_and_verify(action_obj, target_element, pre_map, settle_delay_sec)

    def _execute_and_verify(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement],
        pre_map: UIMap,
        settle_delay_sec: float
    ) -> Dict[str, Any]:
        """Executes the action via ActionAuthority, re-observes, and verifies outcome."""
        # ACT via authoritative dispatcher
        self._notify_ui("COMPUTER_USE_ACTING", {"action": action.action_type.value})
        act_res = self.authority.execute(action, target_element, pre_map)

        if act_res.get("status") != "success":
            self._notify_ui("COMPUTER_USE_FAILED", {"error": act_res.get("error")})
            return act_res

        # WAIT
        if settle_delay_sec > 0:
            time.sleep(settle_delay_sec)

        # RE-OBSERVE
        self._notify_ui("COMPUTER_USE_VERIFYING")
        post_map = self.perception.observe()

        # VERIFY
        ver_res = self.verifier.verify_action(action, pre_map, post_map, act_res)

        if ver_res.verified:
            self._notify_ui("COMPUTER_USE_SUCCESS", {"score": ver_res.score})
            return {
                "status": "success",
                "action": action.action_type.value,
                "verified": True,
                "action_execution_verified": ver_res.action_execution_verified,
                "goal_state_verified": ver_res.goal_state_verified,
                "verification_status": ver_res.status,
                "verification_score": ver_res.score,
                "execution_evidence": ver_res.execution_evidence,
                "changes": ver_res.observed_changes,
                "active_window": post_map.active_window,
                "generation": post_map.generation,
                "message": f"Successfully performed {action.action_type.value} and verified UI transition."
            }
        else:
            self._notify_ui("COMPUTER_USE_FAILED", {"error": ver_res.error_message})
            return {
                "status": "verification_failed",
                "action": action.action_type.value,
                "verified": False,
                "action_execution_verified": ver_res.action_execution_verified,
                "goal_state_verified": False,
                "verification_status": ver_res.status,
                "error": ver_res.error_message,
                "recommendation": ver_res.recovery_recommendation,
                "execution_evidence": ver_res.execution_evidence,
                "active_window": post_map.active_window,
                "generation": post_map.generation,
                "message": f"Action executed, but post-action verification was incomplete: {ver_res.error_message}"
            }

    def _adaptive_recovery_resolve(
        self,
        target_query: str,
        risk_level: RiskLevel
    ) -> Tuple[Optional[UIElement], Optional[UIMap], str]:
        """
        Adaptive recovery pipeline when initial resolution fails:
        1. Fresh screen observation (Screenshot + UIA + OCR)
        2. Semantic / Ordinal matching on fresh observation
        3. UIA text/name/auto_id matching
        4. OCR text element matching
        5. Structural parent/child matching
        6. Coordinate-based visual fallback (ONLY for LOW-risk actions; rejected for HIGH-risk actions)
        """
        import re
        from core.computer_use.models import ElementSource

        # Step 1: Fresh screen observation
        fresh_map = self.perception.observe(include_ocr=True)
        if not fresh_map or not fresh_map.elements:
            return None, None, "EMPTY_OBSERVATION"

        # Step 2: Semantic / Ordinal matching on fresh observation
        res_semantic = self.resolver.resolve(target_query, fresh_map)
        if res_semantic.status == "FOUND" and res_semantic.matched:
            return res_semantic.matched.element, fresh_map, f"SEMANTIC_{res_semantic.matched.strategy}"

        # Step 3: UIA text/name matching with normalized token overlap
        query_words = [w.lower() for w in re.findall(r"\w+", target_query) if len(w) > 2 and w.lower() not in ("the", "and", "for", "click", "open", "play", "button", "link")]
        if query_words:
            for el in fresh_map.elements:
                el_label = f"{el.name} {el.text} {el.automation_id}".lower()
                if any(w in el_label for w in query_words) and el.role in ("button", "hyperlink", "edit", "menuitem", "tabitem", "listitem", "custom", "pane"):
                    return el, fresh_map, "UIA_TOKEN_MATCH"

        # Step 4: OCR matching
        for el in fresh_map.elements:
            if getattr(el, "source", None) == ElementSource.OCR or "ocr" in str(getattr(el, "source", "")).lower():
                ocr_text = (el.text or el.name or "").lower()
                if query_words and any(w in ocr_text for w in query_words):
                    # Step 5: Structural parent/child matching: check if an interactive container encloses this OCR text
                    if el.bounds:
                        for parent in fresh_map.elements:
                            if parent.bounds and parent.interactive and parent != el:
                                if (parent.bounds.left <= el.bounds.left and parent.bounds.top <= el.bounds.top and
                                    parent.bounds.right >= el.bounds.right and parent.bounds.bottom >= el.bounds.bottom):
                                    return parent, fresh_map, "STRUCTURAL_CONTAINER_OCR"
                    return el, fresh_map, "OCR_EXACT_MATCH"

        # Step 6: Coordinate-based visual fallback (Risk-aware)
        if risk_level == RiskLevel.LOW:
            # If any element has partial text overlap, use its center coordinates as visual fallback
            for el in fresh_map.elements:
                el_label = f"{el.name} {el.text}".lower()
                if any(w in el_label for w in query_words) and el.bounds and el.bounds.width > 5 and el.bounds.height > 5:
                    return el, fresh_map, "COORDINATE_VISUAL_FALLBACK"
            
            # If query asks for media/video and candidates exist below header
            if any(term in target_query.lower() for term in ("video", "song", "track", "result", "first")):
                body_elements = [e for e in fresh_map.elements if e.bounds and e.bounds.top > 120 and e.bounds.width > 50 and e.interactive]
                if body_elements:
                    body_elements.sort(key=lambda e: (e.bounds.top, e.bounds.left))
                    return body_elements[0], fresh_map, "VISUAL_BODY_FALLBACK"
        else:
            logger.warning(f"🔒 HIGH-risk action rejected blind coordinate fallback for '{target_query}'")
            return None, fresh_map, "HIGH_RISK_COORDINATE_FALLBACK_REJECTED"

        return None, fresh_map, "NO_RECOVERY_CANDIDATE"

    def save_as_file(
        self,
        filename: str,
        target_window: Optional[str] = None,
        overwrite: bool = True,
        expected_content: Optional[str] = None,
        directory: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Executes a complete, robust semantic Save As flow with disk state verification:
        1. Click File menu (if Save As dialog is not already open)
        2. Observe UI state
        3. Resolve 'Save As' menu item
        4. Click 'Save As'
        5. Wait for dialog
        6. Observe NEW foreground dialog window
        7. Identify filename field
        8. Type new filename / destination path
        9. Resolve 'Save' button
        10. Click 'Save'
        11. Observe overwrite/confirmation dialog if present
        12. Handle overwrite only when appropriate (click 'Yes')
        13. Wait for dialog to close
        14. Verify resulting file exists on disk
        15. Verify filename and content where possible
        """
        import os
        import time

        logger.info("💾 save_as_file: Starting semantic Save As flow for '%s'...", filename)
        self._notify_ui("COMPUTER_USE_STARTED", {"action": "save_as_file", "target": filename})

        clean_filename = filename.strip().strip('"').strip("'")
        if directory and not os.path.isabs(clean_filename):
            target_file_path = os.path.abspath(os.path.join(directory, clean_filename))
        else:
            target_file_path = os.path.abspath(clean_filename)

        # Step 0: Initial Observation to check if Save As dialog is ALREADY open
        obs = self.perception.observe(include_ocr=True)
        active_title = (obs.active_window or "").lower()

        dialog_already_open = any(d in active_title for d in ("save as", "save"))

        if not dialog_already_open:
            # 1. Click File menu
            logger.info("💾 save_as_file: Step 1 -> Clicking 'File' menu...")
            file_click = self.step("click", target_query="File", window_title=target_window, description="Click File menu")
            if not file_click.get("verified") and file_click.get("status") != "success":
                # Fallback: Send hotkey Ctrl+Shift+S or Ctrl+S
                logger.info("💾 save_as_file: File menu click unverified; attempting hotkey Ctrl+Shift+S...")
                self.step("hotkey", key="s", modifiers=["ctrl", "shift"], description="Press Ctrl+Shift+S for Save As")
            else:
                time.sleep(0.2)
                # 2 & 3 & 4. Observe, resolve, and click 'Save As'
                logger.info("💾 save_as_file: Step 3-4 -> Resolving and clicking 'Save As' menu item...")
                save_as_click = self.step("click", target_query="Save as", description="Click Save as menu item")
                if not save_as_click.get("verified") and save_as_click.get("status") != "success":
                    logger.info("💾 save_as_file: Menu item click fallback to Ctrl+Shift+S...")
                    self.step("hotkey", key="s", modifiers=["ctrl", "shift"], description="Press Ctrl+Shift+S")

            # 5 & 6. Wait for dialog & observe NEW foreground window
            logger.info("💾 save_as_file: Step 5-6 -> Waiting for Save As dialog emergence...")
            for _ in range(6):
                time.sleep(0.3)
                dialog_obs = self.perception.observe(include_ocr=True)
                if any(d in (dialog_obs.active_window or "").lower() for d in ("save as", "save")):
                    break

        # 7 & 8. Identify filename field & type filename
        logger.info("💾 save_as_file: Step 7-8 -> Identifying filename field and typing target path...")
        self.step(
            "type_text",
            target_query="File name",
            text=target_file_path if os.path.isabs(clean_filename) else clean_filename,
            description=f"Type filename '{clean_filename}' into Save As dialog"
        )

        time.sleep(0.2)

        # 9 & 10. Resolve Save button & click Save
        logger.info("💾 save_as_file: Step 9-10 -> Resolving and clicking 'Save' button...")
        save_btn_res = self.step("click", target_query="Save", description="Click Save button")
        if save_btn_res.get("status") != "success" and not save_btn_res.get("verified"):
            # Fallback: Press Enter key to submit dialog
            self.step("press_key", key="enter", description="Press Enter to Save")

        # 11 & 12. Observe overwrite/confirmation dialog if present & handle overwrite
        time.sleep(0.4)
        confirm_obs = self.perception.observe(include_ocr=True)
        if any(c in (confirm_obs.active_window or "").lower() for c in ("confirm", "replace", "save as")):
            has_replace_prompt = any("replace" in (e.text or e.name or "").lower() for e in confirm_obs.elements)
            if has_replace_prompt and overwrite:
                logger.info("💾 save_as_file: Step 12 -> Overwrite prompt detected; clicking 'Yes'...")
                self.step("click", target_query="Yes", description="Confirm overwrite Yes")
                time.sleep(0.3)

        # 13 & 14. Verify resulting file exists on disk
        logger.info("💾 save_as_file: Step 13-14 -> Verifying resulting file on disk...")
        time.sleep(0.5)

        candidates = [target_file_path]
        if not os.path.isabs(clean_filename):
            desktop_path = os.path.join(os.path.expanduser("~"), "Desktop", clean_filename)
            docs_path = os.path.join(os.path.expanduser("~"), "Documents", clean_filename)
            cwd_path = os.path.abspath(clean_filename)
            candidates.extend([desktop_path, docs_path, cwd_path])

        verified_path = None
        for p in candidates:
            if os.path.exists(p):
                verified_path = p
                break

        if verified_path and os.path.exists(verified_path):
            size = os.path.getsize(verified_path)
            content_matched = True
            if expected_content is not None:
                try:
                    with open(verified_path, "r", encoding="utf-8", errors="ignore") as f:
                        actual_content = f.read()
                    content_matched = (expected_content in actual_content)
                except Exception:
                    content_matched = False

            self._notify_ui("COMPUTER_USE_SUCCESS", {"file": verified_path, "size": size})
            return {
                "status": "success",
                "verified": True,
                "action_execution_verified": True,
                "goal_state_verified": True,
                "file_path": verified_path,
                "file_exists": True,
                "file_size": size,
                "content_verified": content_matched,
                "message": f"Successfully performed Save As and verified '{os.path.basename(verified_path)}' exists on disk ({size} bytes)."
            }
        else:
            self._notify_ui("COMPUTER_USE_FAILED", {"error": "file_not_found_on_disk"})
            return {
                "status": "verification_failed",
                "verified": False,
                "action_execution_verified": True,
                "goal_state_verified": False,
                "file_path": target_file_path,
                "file_exists": False,
                "error": f"File '{clean_filename}' could not be verified on disk after Save As execution.",
                "message": f"Save As actions completed, but output file '{clean_filename}' was not detected on disk."
            }


computer_use_engine = ComputerUseEngine()

