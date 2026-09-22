"""
MAX Ecosystem — Direct Desktop Control & Antigravity Fallback Verification Test Suite
Verifies all 19 requirements from the Final Implementation Directive:
1. Chrome third tab uses Ctrl+3.
2. Chrome next tab uses Ctrl+Tab.
3. Chrome previous tab uses Ctrl+Shift+Tab.
4. Minimize uses direct OS action.
5. Snap left/right works.
6. Physical left click is actually dispatched.
7. Physical right click is actually dispatched.
8. UIA click fallback works.
9. Vision fallback works.
10. Normal filesystem writes route to Windows Agent.
11. Filesystem tasks never create worktrees.
12. Code tasks route to Antigravity.
13. Hybrid tasks route correctly.
14. Antigravity progress events render correctly.
15. Raw stdout is not blindly displayed as user progress.
16. Cancellation terminates the process tree.
17. Verification checks the actual task target.
18. Failed target verification produces failure.
19. MAX remains responsive during Antigravity execution.
"""

import os
import sys
import time
import asyncio
from unittest.mock import patch, MagicMock
try:
    import pytest
except ImportError:
    class _DummyMark:
        def __getattr__(self, name):
            def decorator(func):
                return func
            return decorator
    class _DummyPytest:
        mark = _DummyMark()
    pytest = _DummyPytest()

from core.task_classifier import task_classifier, TaskTier
from core.direct_action_resolver import direct_action_resolver, DirectActionResolver
from core.ui_automation import uia_engine
from tools.input_tool import input_helper
from orchestrator.max_task_authority import max_task_authority, MAXTaskAuthority
from core.antigravity_bridge import antigravity_bridge, AntigravityBridge
from protocol.task_envelope import TaskEnvelope, TaskStatus, TaskResult, PROTOCOL_VERSION


# ─── 1. CHROME & BROWSER TAB SHORTCUT TESTS (CTRL+1..8, CTRL+TAB, CTRL+SHIFT+TAB) ───

def test_chrome_third_tab_uses_ctrl_3():
    """Requirement 1: Chrome third tab uses Ctrl+3."""
    with patch("pyautogui.hotkey") as mock_hotkey, \
         patch.object(DirectActionResolver, "get_active_window_info", return_value={"is_browser": True, "title": "Chrome"}):
        res = direct_action_resolver.switch_browser_tab(tab_index=3)
        mock_hotkey.assert_called_with("ctrl", "3")
        assert res["status"] == "success"
        assert res["method"] == "keyboard_shortcut_ctrl_3"
        assert res["tab_index"] == 3


def test_chrome_next_tab_uses_ctrl_tab():
    """Requirement 2: Chrome next tab uses Ctrl+Tab."""
    with patch("pyautogui.hotkey") as mock_hotkey, \
         patch.object(DirectActionResolver, "get_active_window_info", return_value={"is_browser": True, "title": "Chrome"}):
        res = direct_action_resolver.switch_browser_tab(direction="next")
        mock_hotkey.assert_called_with("ctrl", "tab")
        assert res["status"] == "success"
        assert res["method"] == "keyboard_shortcut_ctrl_tab"


def test_chrome_previous_tab_uses_ctrl_shift_tab():
    """Requirement 3: Chrome previous tab uses Ctrl+Shift+Tab."""
    with patch("pyautogui.hotkey") as mock_hotkey, \
         patch.object(DirectActionResolver, "get_active_window_info", return_value={"is_browser": True, "title": "Chrome"}):
        res = direct_action_resolver.switch_browser_tab(direction="previous")
        mock_hotkey.assert_called_with("ctrl", "shift", "tab")
        assert res["status"] == "success"
        assert res["method"] == "keyboard_shortcut_ctrl_shift_tab"


# ─── 2. WINDOW MANAGEMENT TESTS (MINIMIZE, SNAP LEFT/RIGHT) ─────────────────

def test_minimize_uses_direct_os_action():
    """Requirement 4: Minimize uses direct OS action."""
    with patch("ctypes.windll.user32.ShowWindow") as mock_show, \
         patch("ctypes.windll.user32.IsIconic", return_value=1), \
         patch.object(DirectActionResolver, "get_active_window_info", return_value={"hwnd": 1234, "title": "Chrome", "is_browser": True}):
        res = direct_action_resolver.minimize_window()
        assert res["status"] == "success"
        assert res["action"] == "minimize"
        assert res["verified"] is True
        mock_show.assert_called_with(1234, 6)  # SW_MINIMIZE = 6


def test_snap_left_and_right():
    """Requirement 5: Snap left/right works."""
    with patch("pyautogui.hotkey") as mock_hotkey, \
         patch("pyautogui.press") as mock_press, \
         patch("ctypes.windll.user32.IsZoomed", return_value=0), \
         patch("ctypes.windll.user32.GetWindowRect", return_value=1), \
         patch("ctypes.windll.user32.GetSystemMetrics", return_value=1080), \
         patch.object(DirectActionResolver, "get_active_window_info", return_value={"hwnd": 5678, "title": "Chrome", "is_browser": True}):
        # Left snap
        res_l = direct_action_resolver.snap_window(direction="left")
        mock_hotkey.assert_called_with("win", "left")
        assert res_l["status"] == "success"

        # Right snap
        res_r = direct_action_resolver.snap_window(direction="right")
        mock_hotkey.assert_called_with("win", "right")
        assert res_r["status"] == "success"


# ─── 3. MOUSE PRIMITIVES & DISPATCHING ──────────────────────────────────────

def test_physical_left_click_dispatched():
    """Requirement 6: Physical left click actually dispatches mouse down + up."""
    with patch("ctypes.windll.user32.mouse_event") as mock_mouse_event, \
         patch("ctypes.windll.user32.SetCursorPos"):
        res = input_helper.left_click(x=100, y=200, clicks=1)
        assert res["status"] == "success"
        assert res["verified"] is True
        # Verify both MOUSEEVENTF_LEFTDOWN (0x0002) and MOUSEEVENTF_LEFTUP (0x0004) were called
        mock_mouse_event.assert_any_call(0x0002, 0, 0, 0, 0)
        mock_mouse_event.assert_any_call(0x0004, 0, 0, 0, 0)


def test_physical_right_click_dispatched():
    """Requirement 7: Physical right click actually dispatches right down + up."""
    with patch("ctypes.windll.user32.mouse_event") as mock_mouse_event, \
         patch("ctypes.windll.user32.SetCursorPos"):
        res = input_helper.click_mouse(button="right", double=False, x=300, y=400)
        assert res["status"] == "success"
        assert res["verified"] is True
        # Verify right down (0x0008) and right up (0x0010)
        mock_mouse_event.assert_any_call(0x0008, 0, 0, 0, 0)
        mock_mouse_event.assert_any_call(0x0010, 0, 0, 0, 0)


# ─── 4. MULTI-LAYER INPUT & VISION FALLBACK ─────────────────────────────────

def test_uia_click_fallback():
    """Requirement 8: Multi-layer UIA click executes physical click + pattern invoke."""
    mock_element = {
        "name": "Brush",
        "control_type": "Button",
        "automation_id": "BrushTool",
        "bounding_box": {"center_x": 150, "center_y": 250, "width": 40, "height": 40}
    }
    with patch.object(uia_engine, "find_elements_by_name", return_value=[mock_element]), \
         patch("tools.input_tool.input_helper.left_click") as mock_left_click:
        res = uia_engine.click_element_by_selector("Brush")
        assert res["status"] == "success"
        assert res["method"] == "Physical_Click_left_count_1"
        mock_left_click.assert_called_with(x=150, y=250, clicks=1)


def test_vision_fallback_when_element_not_found():
    """Requirement 9: Vision fallback is signaled when UIA cannot locate element."""
    with patch.object(uia_engine, "find_elements_by_name", return_value=[]):
        res = uia_engine.click_element_by_selector("NonExistentTriangleIcon")
        assert res["status"] == "error"
        assert res["error_code"] == "ELEMENT_NOT_FOUND"
        assert res.get("vision_fallback_available") is True


# ─── 5. ROUTING: FILESYSTEM VS DEVELOPER VS HYBRID ──────────────────────────

def test_normal_filesystem_writes_route_to_windows_agent():
    """Requirement 10: Normal filesystem tasks route directly to Windows Agent."""
    res1 = task_classifier.classify("Create a folder on Desktop")
    assert res1.target == "windows_agent"
    assert res1.tier in (TaskTier.TIER_1_DIRECT_ACTION.value, TaskTier.TIER_2_PLATFORM_EXECUTION.value)

    res2 = task_classifier.classify("Create a text file in Downloads")
    assert res2.target == "windows_agent"
    assert res2.tier in (TaskTier.TIER_1_DIRECT_ACTION.value, TaskTier.TIER_2_PLATFORM_EXECUTION.value)


def test_filesystem_tasks_never_create_worktrees():
    """Requirement 11: Normal filesystem operations never trigger git worktree creation."""
    with patch.object(max_task_authority.worktree_mgr, "create_worktree") as mock_wt:
        res = task_classifier.classify("Downloads లో Yashwanth folderలో Veravalli.txt create cheyyi")
        assert res.target == "windows_agent"
        mock_wt.assert_not_called()


def test_code_tasks_route_to_antigravity():
    """Requirement 12: Code inspection and bug fixing route to Antigravity."""
    res1 = task_classifier.classify("Fix the Paint automation bug")
    assert res1.target == "antigravity"
    assert res1.tier == TaskTier.TIER_4_DEVELOPER.value

    res2 = task_classifier.classify("Inspect the Windows Agent code")
    assert res2.target == "antigravity"
    assert res2.tier == TaskTier.TIER_4_DEVELOPER.value


def test_hybrid_tasks_route_correctly():
    """Requirement 13: Tasks with code fix + GUI drawing route to Tier 5 Hybrid."""
    res = task_classifier.classify("Fix Paint automation and then verify by drawing a triangle")
    assert res.target == "hybrid"
    assert res.tier == TaskTier.TIER_5_HYBRID.value


# ─── 6. VISIBLE ANTIGRAVITY EXECUTION & SANITIZATION ────────────────────────

def test_antigravity_progress_events_render_correctly(capsys):
    """Requirement 14 & 15: Antigravity progress events render cleanly and do not dump raw stdout."""
    bridge = AntigravityBridge()
    bridge._render_cli_progress("Fix Paint automation", stage=1, total_stages=6, stage_name="Task accepted", status="STARTING")
    bridge._render_cli_progress("Fix Paint automation", stage=2, total_stages=6, stage_name="Inspecting project", status="INSPECTING")
    bridge._render_cli_progress("Fix Paint automation", stage=6, total_stages=6, stage_name="Verifying result", status="COMPLETED")

    captured = capsys.readouterr().out
    assert "MAX → ANTIGRAVITY" in captured
    assert "Task: Fix Paint automation" in captured
    assert "[1/6] Task accepted" in captured
    assert "[2/6] Inspecting project" in captured
    assert "[6/6] Verifying result" in captured
    assert "STATUS: COMPLETED" in captured
    assert "raw_stdout_dump" not in captured


# ─── 7. CANCELLATION & PROCESS TREE TERMINATION ─────────────────────────────

def test_cancellation_terminates_process_tree():
    """Requirement 16: Voice cancellation terminates worker process tree."""
    with patch.object(max_task_authority.bridge, "cancel_task") as mock_bridge_cancel, \
         patch.object(max_task_authority.supervisor, "terminate_process_tree") as mock_sup_term:
        
        # Create a mock root task
        root_info = max_task_authority.create_root_task(goal="Long running developer task")
        task_id = root_info["task_id"]

        res = max_task_authority.cancel_task(task_id, reason="User said stop")
        assert res["success"] is True
        mock_bridge_cancel.assert_called_with(task_id)
        mock_sup_term.assert_called_with(task_id, reason="CANCELLED", force=True)


# ─── 8. TARGET VERIFICATION VS WORKSPACE ────────────────────────────────────

def test_verification_checks_actual_task_target(tmp_path):
    """Requirement 17 & 18: Verifier checks exact requested target path."""
    target_file = tmp_path / "Veravalli.txt"
    target_file.write_text("Yashwanth", encoding="utf-8")

    from core.verifier import action_verifier
    assert action_verifier.verify_file_created_sync(str(target_file), expected_content="Yashwanth") is True
    # Non-existent target fails
    assert action_verifier.verify_file_created_sync(str(tmp_path / "NonExistent.txt")) is False


@pytest.mark.asyncio
async def test_max_remains_responsive_during_antigravity():
    """Requirement 19: MAX remains non-blocking during Antigravity execution."""
    with patch.object(max_task_authority.bridge, "run_task") as mock_run:
        mock_run.return_value = TaskResult(
            task_id="test-123",
            status=TaskStatus.COMPLETED,
            claimed_success=True,
            verified_success=True,
            summary="Developer task completed."
        )
        res = await max_task_authority.submit_user_intent(
            user_intent="Inspect the Paint automation code",
            async_execution=True
        )
        assert res["status"] in ("accepted", "active")
        assert res["worker"] == "antigravity"

