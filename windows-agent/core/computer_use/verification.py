"""
MAX AI — Computer Use Post-Action Verification Engine
Verifies whether a UI action produced the intended effect (state change,
window focus, text modification, or element transition).
"""

import time
import logging
from typing import Dict, Any, Optional, List

from core.computer_use.models import (
    UIMap, ComputerUseAction, ActionType, VerificationResult
)

logger = logging.getLogger("MAX.ComputerUse.Verification")


class ComputerUseVerifier:
    """Verifies that an executed action produced the intended state change."""

    def verify_action(
        self,
        action: ComputerUseAction,
        pre_map: UIMap,
        post_map: UIMap,
        action_result: Dict[str, Any]
    ) -> VerificationResult:
        """
        Evaluates state differences between pre_map and post_map based on action type.
        """
        observed_changes: List[str] = []
        verified = False
        score = 0.0

        observed_changes: List[str] = []
        action_execution_verified = (action_result.get("status") == "success")
        goal_state_verified = False
        score = 0.0

        if not action_execution_verified:
            return VerificationResult(
                verified=False,
                action_execution_verified=False,
                goal_state_verified=False,
                status="FAILED",
                score=0.0,
                observed_changes=[],
                execution_evidence=action_result.get("execution_evidence", {}),
                error_message=f"Action execution reported failure: {action_result.get('error', 'unknown error')}",
                recovery_recommendation="Retry action after re-observation or check target coordinates."
            )

        # Execution evidence passed from ActionAuthority
        evidence = dict(action_result.get("execution_evidence", {}))
        evidence["pre_window"] = pre_map.active_window
        evidence["post_window"] = post_map.active_window
        evidence["pre_element_count"] = len(pre_map.elements)
        evidence["post_element_count"] = len(post_map.elements)

        # 1. Window transition checks
        pre_win = pre_map.active_window or "Unknown"
        post_win = post_map.active_window or "Unknown"
        window_changed = (pre_win != post_win)
        is_dialog_transition = (
            any(d in post_win.lower() for d in ("save as", "save", "open", "confirm", "replace", "dialog"))
            and not any(d in pre_win.lower() for d in ("save as", "save", "open", "confirm", "replace", "dialog"))
        )
        window_transition_verified = (window_changed or is_dialog_transition)

        # Structured CU_WINDOW log required by MAX specification
        logger.info(
            "[CU_WINDOW] previous_window='%s' new_window='%s' window_transition_verified=%s",
            pre_win, post_win, window_transition_verified
        )

        if window_changed:
            change_msg = f"Active window transitioned from '{pre_win}' -> '{post_win}'"
            observed_changes.append(change_msg)
            logger.info(f"🔎 {change_msg}")

        process_changed = (pre_map.active_process != post_map.active_process)
        if process_changed:
            change_msg = f"Active process transitioned from '{pre_map.active_process}' -> '{post_map.active_process}'"
            observed_changes.append(change_msg)
            logger.info(f"🔎 {change_msg}")

        # 2. Action-Specific Verification Heuristics
        a_type = action.action_type
        element_diff = abs(len(pre_map.elements) - len(post_map.elements))
        target_text = (action.target_query or (action.description or "")).lower()

        # Check if target is a window/dialog-spawning action
        is_window_spawning_action = any(
            w in target_text for w in ("save as", "save_as", "open...", "open file", "file menu", "browse", "properties", "settings")
        )

        if a_type in (ActionType.CLICK, ActionType.DOUBLE_CLICK, ActionType.RIGHT_CLICK, ActionType.MIDDLE_CLICK):
            is_media_action = any(m in target_text for m in ("song", "video", "play", "track", "first", "result"))
            player_detected = any("player" in (e.automation_id or e.name or "").lower() for e in post_map.elements)

            if is_window_spawning_action:
                if window_transition_verified or element_diff >= 3:
                    goal_state_verified = True
                    score = 1.0
                    observed_changes.append(f"Window transition verified for '{action.target_query or target_text}': Active window '{post_win}'.")
                else:
                    # Target was expected to open a window/dialog, but failed to do so
                    goal_state_verified = False
                    score = 0.40
                    logger.warning(
                        "⚠️ Window-dependent action '%s' failed transition check (pre='%s', post='%s', elem_diff=%d)",
                        action.target_query or target_text, pre_win, post_win, element_diff
                    )
            elif is_media_action and (player_detected or window_changed):
                goal_state_verified = True
                score = 0.95
                observed_changes.append("Media navigation verified: Video player or stream container active.")
            elif window_changed or process_changed or element_diff >= 2 or player_detected:
                goal_state_verified = True
                score = 0.95
                observed_changes.append(f"UI state transition verified (elements: {len(pre_map.elements)} -> {len(post_map.elements)})")
            elif is_media_action:
                goal_state_verified = True
                score = 0.85
                observed_changes.append("Media click dispatched; awaiting streaming/render buffer.")
            else:
                # Physical click succeeded, but static UI (e.g. click to focus or no immediate visual popup)
                # UIA pattern alone without any UI change must NOT count as full verified success
                goal_state_verified = False
                score = 0.70 if evidence.get("backend_used") != "uia_semantic_pattern" else 0.50
                observed_changes.append("Action executed cleanly; UI elements remained stable.")

        elif a_type == ActionType.TYPE_TEXT:
            typed_text = action.text or ""
            # Check if typed text appears in any post-map element text or name
            text_observed = any(typed_text.lower() in (e.text or e.name or "").lower() for e in post_map.elements if e.text or e.name)
            if text_observed:
                goal_state_verified = True
                score = 1.0
                observed_changes.append(f"Typed text '{typed_text[:20]}' confirmed in UI state.")
            elif element_diff > 0 or window_changed:
                goal_state_verified = True
                score = 0.90
                observed_changes.append(f"Typed text dispatched with observable UI transition.")
            else:
                # Execution verified, but goal state unconfirmed by text inspector
                goal_state_verified = False
                score = 0.80
                observed_changes.append(f"Typed text of length {len(typed_text)} dispatched to target focus.")

        elif a_type in (ActionType.PRESS_KEY, ActionType.HOTKEY):
            if window_changed or process_changed or element_diff > 0:
                goal_state_verified = True
                score = 0.95
                observed_changes.append(f"Key event triggered UI transition.")
            else:
                goal_state_verified = False
                score = 0.80
                observed_changes.append(f"Dispatched key sequence {action.key or action.modifiers} without immediate layout change.")

        elif a_type == ActionType.ACTIVATE_WINDOW:
            if action.target_query and action.target_query.lower() in post_map.active_window.lower():
                goal_state_verified = True
                score = 1.0
                observed_changes.append(f"Window '{post_map.active_window}' confirmed in foreground.")
            elif action.window_title and action.window_title.lower() in post_map.active_window.lower():
                goal_state_verified = True
                score = 1.0
                observed_changes.append(f"Window '{post_map.active_window}' confirmed in foreground.")
            elif window_changed:
                goal_state_verified = True
                score = 0.90
                observed_changes.append(f"Window transitioned to '{post_map.active_window}'.")
            else:
                goal_state_verified = False
                score = 0.70

        elif a_type == ActionType.SCROLL:
            if element_diff > 0:
                goal_state_verified = True
                score = 0.90
                observed_changes.append(f"Scroll verified by element layout delta.")
            else:
                goal_state_verified = False
                score = 0.80
                observed_changes.append(f"Dispatched scroll delta {action.scroll_delta}.")

        elif a_type == ActionType.DRAG_AND_DROP:
            if window_changed or element_diff > 0:
                goal_state_verified = True
                score = 0.95
                observed_changes.append("Drag and drop produced observable UI state change.")
            else:
                goal_state_verified = False
                score = 0.80
                observed_changes.append("Drag and drop path completed; target resting state stable.")

        elif a_type == ActionType.WAIT:
            goal_state_verified = True
            score = 1.0
            observed_changes.append("Wait duration elapsed.")

        # If a window-spawning action failed to produce a window transition, reject verified status
        if is_window_spawning_action and not window_transition_verified and element_diff < 3:
            verified = False
            status_str = "UNVERIFIED"
            err_msg = f"Target window transition for '{action.target_query or target_text}' could not be verified in foreground."
        else:
            verified = bool(action_execution_verified and (goal_state_verified or score >= 0.70))
            status_str = "VERIFIED" if (action_execution_verified and goal_state_verified) else ("PARTIALLY_VERIFIED" if action_execution_verified else "UNVERIFIED")
            err_msg = None if verified else f"Post-action verification incomplete (score: {score:.2f}, goal_verified: {goal_state_verified})"

        return VerificationResult(
            verified=verified,
            action_execution_verified=action_execution_verified,
            goal_state_verified=goal_state_verified,
            status=status_str,
            score=score,
            observed_changes=observed_changes,
            execution_evidence=evidence,
            error_message=err_msg
        )


computer_use_verifier = ComputerUseVerifier()

