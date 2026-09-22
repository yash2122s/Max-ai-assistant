"""
Focused Test Suite: Unified MAX Computer-Use Authority
Validates:
1. Exactly-once click & UIA pattern selection (no double dispatch)
2. UIA Invoke / pattern execution fallback
3. Foreground activation routing via tools.app_control.activate_app_window
4. Stale ref invalidation (generation & window mismatch)
5. Automatic re-resolution on stale ref
6. Drag and drop action envelope & execution
7. Action execution verified vs Goal state verified separation
8. No-progress detection & bounded recovery in execution loop
9. Schema consistency between models, tool registry, executor, and MCP
"""

import time
import unittest
from unittest.mock import MagicMock, patch

from core.computer_use.models import (
    ActionType, ComputerUseAction, UIElement, UIMap, Rect, Point, ElementSource
)
from core.computer_use.action_authority import ActionAuthority, action_authority
from core.computer_use.ui_ref_cache import ui_ref_cache
from core.computer_use.target_resolver import target_resolver
from core.computer_use.computer_use_engine import ComputerUseEngine
from core.computer_use.verification import ComputerUseVerifier
from core.tool_result import ToolResult
from core.unified_tool_registry import unified_tool_registry
from core.agent_execution_loop import AgentExecutionLoop, TaskExecutionState
from core.mcp.models import MCPToolResult


class TestComputerUseAuthorityUnified(unittest.TestCase):

    def setUp(self):
        ui_ref_cache.clear()

    # 1. Exactly-Once Click & Pattern Selection
    def test_01_exactly_once_click_and_pattern_selection(self):
        """Verify that clicking an element chooses a single backend path and executes exactly once."""
        auth = ActionAuthority()
        target = UIElement(
            ref="@e1",
            role="button",
            name="Save",
            bounds=Rect(10, 10, 50, 30),
            center=Point(30, 20),
            native_handle=12345,
            generation=1
        )
        action = ComputerUseAction(action_type=ActionType.CLICK, target_ref="@e1")

        # Mock UIA pattern invoke returning True
        with patch.object(auth, "_invoke_uia_pattern_by_handle", return_value=True) as mock_invoke, \
             patch("tools.input_tool.input_helper.left_click") as mock_phys:
            res = auth.execute(action, target_element=target)
            self.assertEqual(res.get("status"), "success")
            self.assertEqual(res.get("backend"), "uia_semantic_pattern")
            self.assertTrue(res.get("action_execution_verified"))
            # Must invoke once and NOT execute physical click
            mock_invoke.assert_called_once_with(12345, "button")
            mock_phys.assert_not_called()

    # 2. UIA Invoke Fallback to Physical Click
    def test_02_uia_invoke_fallback_to_physical(self):
        """Verify that when semantic UIA pattern fails, it falls back to element bounding-box physical interaction."""
        auth = ActionAuthority()
        target = UIElement(
            ref="@e1",
            role="button",
            name="Submit",
            bounds=Rect(100, 100, 200, 140),
            center=Point(150, 120),
            native_handle=12345,
            generation=1
        )
        action = ComputerUseAction(action_type=ActionType.CLICK, target_ref="@e1")

        # Mock semantic invoke returning False -> fallback to physical click
        with patch.object(auth, "_invoke_uia_pattern_by_handle", return_value=False) as mock_invoke, \
             patch("tools.input_tool.input_helper.left_click", return_value={"status": "success"}) as mock_phys, \
             patch("tools.input_tool.input_helper.move_mouse", return_value={"status": "success"}) as mock_move:
            res = auth.execute(action, target_element=target)
            mock_invoke.assert_called_once()
            mock_phys.assert_called_once_with(150, 120, clicks=1)
            self.assertEqual(res.get("status"), "success")
            self.assertEqual(res.get("backend"), "win32_input_helper")

    # 3. Foreground Activation Routing
    def test_03_foreground_activation_routes_to_app_control(self):
        """Verify that activate_window authoritatively routes through tools.app_control.activate_app_window."""
        auth = ActionAuthority()
        action = ComputerUseAction(
            action_type=ActionType.ACTIVATE_WINDOW,
            target_query="Notepad",
            window_title="Notepad"
        )

        with patch("tools.app_control.activate_app_window", return_value={"status": "success", "verified": True, "hwnd": 9999, "window_title": "Untitled - Notepad"}) as mock_activate:
            res = auth.execute(action)
            mock_activate.assert_called_once()
            self.assertEqual(res.get("status"), "success")
            self.assertEqual(res.get("backend"), "tools.app_control.activate_app_window")
            self.assertTrue(res.get("action_execution_verified"))

    # 4. Stale Ref Invalidation
    def test_04_stale_ref_invalidation(self):
        """Verify that ui_ref_cache correctly identifies stale references across generations and windows."""
        el = UIElement(
            ref="@e10",
            role="button",
            name="Cancel",
            bounds=Rect(0, 0, 10, 10),
            center=Point(5, 5),
            window="Old Window",
            generation=1
        )
        test_map = UIMap(
            generation=1,
            timestamp=time.time(),
            active_window="Old Window",
            elements=[el],
            ref_map={"@e10": el}
        )
        ui_ref_cache.store_map(test_map)

        # Matching generation & window -> not stale
        self.assertFalse(ui_ref_cache.is_stale("@e10", expected_generation=1, expected_window="Old Window"))

        # Older generation -> stale
        self.assertTrue(ui_ref_cache.is_stale("@e10", expected_generation=2, expected_window="Old Window"))

        # Different window -> stale
        self.assertTrue(ui_ref_cache.is_stale("@e10", expected_generation=1, expected_window="New Window"))

    # 5. Automatic Stale-Ref Re-Resolution in ComputerUseEngine
    def test_05_automatic_stale_ref_re_resolution(self):
        """Verify that ComputerUseEngine detects stale ref, re-observes, and resolves updated element."""
        engine = ComputerUseEngine()

        # Seed old stale element at gen 1
        old_el = UIElement(ref="@e5", role="button", name="OK", bounds=Rect(0, 0, 10, 10), center=Point(5, 5), generation=1)
        old_map = UIMap(
            generation=1,
            timestamp=time.time(),
            active_window="Dialog",
            elements=[old_el],
            ref_map={"@e5": old_el}
        )
        ui_ref_cache.store_map(old_map)

        # Fresh elements at gen 2
        fresh_el = UIElement(ref="@e1", role="button", name="OK", bounds=Rect(50, 50, 100, 80), center=Point(75, 65), generation=2)
        fresh_map = UIMap(
            generation=2,
            timestamp=time.time(),
            active_window="Dialog",
            elements=[fresh_el],
            ref_map={"@e1": fresh_el}
        )

        with patch.object(engine.perception, "observe", return_value=fresh_map) as mock_obs, \
             patch.object(engine.authority, "execute", return_value={"status": "success", "backend": "uia_semantic_pattern", "action_execution_verified": True}) as mock_exec:
            res = engine.step(
                action_type="click",
                target_ref="@e5",
                target_query="OK",
                generation=2,  # Ref @e5 is from gen 1, so it is stale!
                risk_level="LOW"
            )
            # Must have re-observed due to stale ref
            mock_obs.assert_called()
            self.assertEqual(res.get("status"), "success")
            self.assertTrue(res.get("action_execution_verified"))

    # 6. Drag and Drop Action Envelope
    def test_06_drag_and_drop_action_envelope(self):
        """Verify drag_and_drop executes mouse down, movement interpolation, and mouse up."""
        auth = ActionAuthority()
        src = UIElement(ref="@e1", role="listitem", name="Item 1", bounds=Rect(10, 10, 50, 30), center=Point(30, 20), generation=1)
        dst = UIElement(ref="@e2", role="listitem", name="Folder", bounds=Rect(200, 200, 250, 250), center=Point(225, 225), generation=1)

        action = ComputerUseAction(
            action_type=ActionType.DRAG_AND_DROP,
            source_ref="@e1",
            destination_ref="@e2",
            duration=0.5
        )

        with patch("tools.input_tool.input_helper.drag_and_drop", return_value={"status": "success", "from": [30, 20], "to": [225, 225]}) as mock_drag:
            res = auth.execute(action, target_element=src, pre_map=UIMap(generation=1, timestamp=time.time(), elements=[src, dst], ref_map={"@e1": src, "@e2": dst}))
            mock_drag.assert_called_once_with(start_x=30, start_y=20, end_x=225, end_y=225, duration_sec=0.5, button='left', steps=20)
            self.assertEqual(res.get("status"), "success")
            self.assertEqual(res.get("backend"), "win32_drag_and_drop")
            self.assertTrue(res.get("action_execution_verified"))

    # 7. Action Execution Verified vs Goal State Verified
    def test_07_action_vs_goal_verification(self):
        """Verify that action_execution_verified and goal_state_verified are strictly separated."""
        verifier = ComputerUseVerifier()
        pre_map = UIMap(
            generation=1,
            timestamp=time.time(),
            active_window="Editor",
            elements=[UIElement(ref="@e1", role="edit", name="Text Field", bounds=Rect(0, 0, 100, 30), center=Point(50, 15))]
        )
        # Post map has the identical elements and no text change
        post_map = UIMap(
            generation=2,
            timestamp=time.time(),
            active_window="Editor",
            elements=[UIElement(ref="@e1", role="edit", name="Text Field", bounds=Rect(0, 0, 100, 30), center=Point(50, 15))]
        )
        action = ComputerUseAction(action_type=ActionType.TYPE_TEXT, target_ref="@e1", text="secret_value")
        action_res = {
            "status": "success",
            "action": "type_text",
            "action_execution_verified": True,
            "execution_evidence": {"backend": "win32_sendinput"}
        }

        ver_res = verifier.verify_action(action, pre_map, post_map, action_res)
        # Action was executed, but goal state (text visible) was not observed
        self.assertTrue(ver_res.action_execution_verified)
        self.assertFalse(ver_res.goal_state_verified)
        self.assertEqual(ver_res.status, "PARTIALLY_VERIFIED")

        # ToolResult reflects this separation
        tr = ToolResult(
            success=True,
            tool_name="type_text",
            source="computer_use",
            status="success",
            data={"typed": "secret_value"},
            action_execution_verified=ver_res.action_execution_verified,
            goal_state_verified=ver_res.goal_state_verified
        )
        tr_dict = tr.to_dict()
        self.assertTrue(tr_dict["action_execution_verified"])
        self.assertFalse(tr_dict["goal_state_verified"])

    # 8. Long-Task No-Progress Detection & Bounded Recovery
    def test_08_no_progress_detection_and_bounded_recovery(self):
        """Verify AgentExecutionLoop halts safely when no progress is made across 5 consecutive turns."""
        loop = AgentExecutionLoop(max_iterations=10, timeout_sec=10.0)

        turn_counter = 0
        def fake_chat_completion(messages, tools):
            nonlocal turn_counter
            turn_counter += 1
            return {
                "content": "",
                "tool_calls": [{"name": "computer_use_click", "arguments": {"target": f"GhostButton_{turn_counter}"}}]
            }

        mock_registry = MagicMock()
        import asyncio
        async def mock_exec(name, args):
            return {"status": "error", "message": "Element not found"}
        mock_registry.execute_async = mock_exec

        with patch("core.provider_manager.provider_manager.chat_completion", side_effect=fake_chat_completion), \
             patch("core.intent_router.intent_router.classify") as mock_classify:
            from core.intent_router import IntentCategory, RouteDecision
            mock_classify.return_value = RouteDecision(
                intent=IntentCategory.COMPUTER_USE,
                tool="computer_use_step",
                should_bypass_web_search=True,
                direct_answer=None,
                reason="test",
                confidence=0.9
            )

            result = asyncio.run(loop.execute_goal_loop("Click the ghost button", tool_registry=mock_registry))

            self.assertEqual(result.get("status"), "error")
            task_state = result.get("task_state", {})
            self.assertEqual(task_state.get("final_state"), "FAILED")
            self.assertGreaterEqual(task_state.get("recovery_count", 0), 1)
            # Should have terminated early around turn 5 instead of continuing to 10
            self.assertLessEqual(result.get("iterations"), 6)

    # 9. Schema Consistency across Tool Registry, Models, and MCP
    def test_09_full_action_schema_consistency(self):
        """Verify that all ActionType values are supported across models, registry, and MCP schema."""
        expected_actions = {
            "click", "double_click", "right_click", "middle_click",
            "move_mouse", "mouse_down", "mouse_up", "drag_and_drop",
            "type_text", "press_key", "hotkey", "scroll",
            "activate_window", "wait"
        }
        # Check ActionType enum
        enum_values = {a.value for a in ActionType}
        self.assertTrue(expected_actions.issubset(enum_values), f"Missing actions in ActionType: {expected_actions - enum_values}")

        # Check UnifiedToolRegistry computer_use_step schema
        step_decl = None
        for d in unified_tool_registry.declarations:
            if d.get("name") == "computer_use_step":
                step_decl = d
                break
        self.assertIsNotNone(step_decl, "computer_use_step not found in UnifiedToolRegistry")
        schema_actions = set(step_decl["parameters"]["properties"]["action_type"]["enum"])
        self.assertTrue(expected_actions.issubset(schema_actions), f"Missing actions in computer_use_step schema: {expected_actions - schema_actions}")

        # Check MCPToolResult compatibility with ToolResult
        mcp_res = MCPToolResult(
            tool_name="computer_use_step",
            server_name="max",
            status="success",
            action_execution_verified=True,
            goal_state_verified=True,
            execution_evidence={"backend": "uia"}
        )
        m_dict = mcp_res.to_dict()
        self.assertTrue(m_dict["action_execution_verified"])
        self.assertTrue(m_dict["goal_state_verified"])
        self.assertEqual(m_dict["execution_evidence"]["backend"], "uia")


if __name__ == "__main__":
    unittest.main()
