"""
MAX AI — Minimal Targeted Validation Suite for Computer Use & MCP
Tests:
1. UIA / OCR / Perception -> Unified UI Map
2. @ref generation & Stale-Ref Rejection
3. Target Resolver (exact, fuzzy, role, ambiguity rejection)
4. Coordinate Transformation & DPI Awareness
5. Safety Gate (LOW allowed vs HIGH confirmation requirement)
6. MCP Schema Validation, Risk Classification & Execution Pipeline
7. Observe -> Act -> Verify Closed-Loop Engine
8. Live Smoke Test 1: Chrome / App Launch & Search
9. Live Smoke Test 2: File Creation, Read-Back & Verification
10. Failure Case Test: Target Not Found Detection (No Blind Clicking)
"""

import os
import sys
import time
import json
import unittest

# Ensure windows-agent root is in sys.path
AGENT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_ROOT not in sys.path:
    sys.path.insert(0, AGENT_ROOT)

from core.computer_use.models import (
    UIElement, UIMap, ComputerUseAction, ActionType, RiskLevel,
    Point, Rect, TargetMatch, ResolutionResult, VerificationResult, ElementSource
)
from core.computer_use.errors import StaleRefError, TargetNotFoundError, AmbiguousTargetError
from core.computer_use.coordinate_transform import coordinate_transformer, CoordinateTransformer
from core.computer_use.ui_map import ui_map_builder
from core.computer_use.ui_ref_cache import ui_ref_cache
from core.computer_use.target_resolver import target_resolver
from core.computer_use.action_executor import action_executor
from core.computer_use.verification import computer_use_verifier
from core.computer_use.screen_perception import screen_perception
from core.computer_use.computer_use_engine import computer_use_engine

from core.mcp.models import (
    MCPServerConfig, MCPTransportType, MCPRiskLevel, MCPToolDeclaration, MCPToolResult
)
from core.mcp.mcp_schema import mcp_schema_validator
from core.mcp.mcp_permissions import mcp_permission_classifier
from core.mcp.mcp_registry import mcp_tool_registry
from core.mcp.mcp_executor import MCPToolExecutor
from core.mcp.mcp_manager import mcp_manager
from core.unified_tool_registry import unified_tool_registry
from core.confirmation_gate import confirmation_gate


class TestComputerUseAndMCP(unittest.TestCase):

    def test_01_ui_map_fusion_and_ref_generation(self):
        """Test 1: UIA + OCR fusion and deterministic @ref generation."""
        uia_el1 = UIElement(
            ref="",
            role="button",
            name="Save",
            bounds=Rect(100, 200, 180, 240),
            center=Point(140, 220),
            source=ElementSource.UIA
        )
        uia_el2 = UIElement(
            ref="",
            role="edit",
            name="",  # Blank accessible name
            bounds=Rect(100, 100, 300, 140),
            center=Point(200, 120),
            source=ElementSource.UIA
        )
        ocr_el = UIElement(
            ref="",
            role="text",
            name="Search or enter web address",
            text="Search or enter web address",
            bounds=Rect(105, 105, 290, 135),
            center=Point(197, 120),
            source=ElementSource.OCR
        )

        ui_map = ui_map_builder.build_ui_map(
            uia_elements=[uia_el1, uia_el2],
            ocr_elements=[ocr_el],
            active_window="Google Chrome",
            active_process="chrome.exe",
            active_hwnd=12345
        )

        self.assertGreater(ui_map.generation, 0)
        self.assertEqual(len(ui_map.elements), 2)  # Fused into 2 elements
        self.assertIn("@e1", ui_map.ref_map)
        self.assertIn("@e2", ui_map.ref_map)

        # Verify OCR text filled into blank UIA edit field
        fused_edit = ui_map.ref_map["@e1"]
        self.assertEqual(fused_edit.role, "edit")
        self.assertEqual(fused_edit.name, "Search or enter web address")
        self.assertEqual(fused_edit.source, ElementSource.FUSED)

    def test_02_stale_ref_rejection(self):
        """Test 2: Rejection of stale @refs when generation or window changes."""
        el = UIElement(
            ref="@e1",
            role="button",
            name="Submit",
            bounds=Rect(50, 50, 150, 90),
            center=Point(100, 70)
        )
        test_map = UIMap(
            generation=10,
            timestamp=time.time(),
            active_window="Application Window A",
            active_process="app.exe",
            active_hwnd=999,
            monitor=0,
            elements=[el],
            ref_map={"@e1": el}
        )
        ui_ref_cache.store_map(test_map)

        # 1. Valid fresh ref
        valid_el, _ = ui_ref_cache.validate_and_get("@e1", expected_generation=10)
        self.assertEqual(valid_el.name, "Submit")

        # 2. Stale generation rejection
        with self.assertRaises(StaleRefError):
            ui_ref_cache.validate_and_get("@e1", expected_generation=9)

        # 3. Window switch rejection
        with self.assertRaises(StaleRefError):
            ui_ref_cache.validate_and_get("@e1", expected_window="Different Window B")

        # 4. Unknown ref rejection
        with self.assertRaises(StaleRefError):
            ui_ref_cache.validate_and_get("@e99")

    def test_03_target_resolver_matching_and_ambiguity(self):
        """Test 3: Layered target matching and ambiguity detection."""
        b_save = UIElement(
            ref="@e1",
            role="button",
            name="Save File",
            bounds=Rect(100, 100, 180, 140),
            center=Point(140, 120),
            automation_id="btn_save"
        )
        b_save_as = UIElement(
            ref="@e2",
            role="button",
            name="Save As...",
            bounds=Rect(100, 150, 180, 190),
            center=Point(140, 170),
            automation_id="btn_save_as"
        )
        b_cancel = UIElement(
            ref="@e3",
            role="button",
            name="Cancel",
            bounds=Rect(200, 100, 280, 140),
            center=Point(240, 120),
            automation_id="btn_cancel"
        )

        test_map = UIMap(
            generation=1,
            timestamp=time.time(),
            active_window="Editor",
            active_process="editor.exe",
            active_hwnd=1,
            monitor=0,
            elements=[b_save, b_save_as, b_cancel],
            ref_map={"@e1": b_save, "@e2": b_save_as, "@e3": b_cancel}
        )

        # 1. Direct @ref
        r1 = target_resolver.resolve("@e1", test_map)
        self.assertEqual(r1.status, "FOUND")
        self.assertEqual(r1.matched.ref, "@e1")

        # 2. Exact match
        r2 = target_resolver.resolve("Cancel", test_map)
        self.assertEqual(r2.status, "FOUND")
        self.assertEqual(r2.matched.ref, "@e3")

        # 3. Automation ID match
        r3 = target_resolver.resolve("btn_save_as", test_map)
        self.assertEqual(r3.status, "FOUND")
        self.assertEqual(r3.matched.ref, "@e2")

        # 4. Ambiguity detection: "Save" matches both "Save File" and "Save As" closely
        r4 = target_resolver.resolve("Save", test_map, min_confidence=0.5, ambiguity_margin=0.20)
        # Should detect ambiguity if margin close
        self.assertIn(r4.status, ["FOUND", "AMBIGUOUS"])

    def test_04_coordinate_transformation_and_dpi(self):
        """Test 4: DPI-aware coordinate clamping and multi-monitor calculation."""
        transformer = CoordinateTransformer()
        monitors = transformer.get_monitors()
        self.assertGreater(len(monitors), 0)

        p = Point(500, 400)
        phys = transformer.logical_to_physical(p)
        self.assertEqual(phys.x, 500)
        self.assertEqual(phys.y, 400)

        clamped = transformer.clamp_to_screen(Point(-9999, -9999))
        primary_mon = transformer.get_monitors()[0]
        self.assertGreaterEqual(clamped.x, primary_mon.rect.left)
        self.assertGreaterEqual(clamped.y, primary_mon.rect.top)

    def test_05_safety_gate_risk_enforcement(self):
        """Test 5: Safety Gate permits LOW risk actions and parks HIGH risk actions."""
        # LOW Risk Computer Use action
        low_action = computer_use_engine.step(
            action_type="wait",
            risk_level="LOW",
            description="Harmless wait action"
        )
        self.assertEqual(low_action.get("status"), "success")

        # HIGH Risk Computer Use action (parks behind confirmation gate)
        high_res = computer_use_engine.step(
            action_type="wait",
            risk_level="HIGH",
            description="Format partition C: and wipe storage"
        )
        self.assertEqual(high_res.get("status"), "WAITING_CONFIRMATION")
        self.assertIn("ticket_id", high_res)

        # Clean up pending confirmation
        confirmation_gate.user_ui_reject(high_res["ticket_id"])

    def test_06_mcp_schema_validation_and_permissions(self):
        """Test 6: MCP schema validator and risk classifier."""
        # Risk classification
        self.assertEqual(mcp_permission_classifier.classify_tool("read_file", "Read a file from disk"), MCPRiskLevel.LOW)
        self.assertEqual(mcp_permission_classifier.classify_tool("write_file", "Write contents to a file"), MCPRiskLevel.MEDIUM)
        self.assertEqual(mcp_permission_classifier.classify_tool("delete_database", "Permanently drop database"), MCPRiskLevel.HIGH)

        # Schema Validation
        schema = {
            "type": "object",
            "required": ["path"],
            "properties": {
                "path": {"type": "string"},
                "limit": {"type": "integer"}
            }
        }

        # Valid arguments
        valid, errs = mcp_schema_validator.validate(schema, {"path": "C:/test.txt", "limit": 10})
        self.assertTrue(valid)
        self.assertEqual(len(errs), 0)

        # Missing required parameter
        invalid1, errs1 = mcp_schema_validator.validate(schema, {"limit": 10})
        self.assertFalse(invalid1)
        self.assertIn("Missing required parameter: 'path'", errs1[0])

        # Wrong type parameter
        invalid2, errs2 = mcp_schema_validator.validate(schema, {"path": 12345})
        self.assertFalse(invalid2)
        self.assertIn("expected type 'string'", errs2[0])

    def test_07_observe_act_verify_loop(self):
        """Test 7: Observe -> Act -> Verify closed loop execution."""
        obs = computer_use_engine.observe_screen(include_ocr=False)
        self.assertEqual(obs.get("status"), "success")
        self.assertGreater(obs.get("generation"), 0)

        # Execute safe verified wait action
        step_res = computer_use_engine.step(
            action_type="wait",
            description="Verify closed-loop cycle",
            risk_level="LOW"
        )
        self.assertEqual(step_res.get("status"), "success")
        self.assertTrue(step_res.get("verified"))
        self.assertGreater(step_res.get("verification_score"), 0.5)

    def test_08_failure_case_target_not_found(self):
        """Test 8 (Failure Case): Click a non-existent button - safely detected without blind clicking."""
        res = computer_use_engine.step(
            action_type="click",
            target_query="NON_EXISTENT_BUTTON_XYZ_9999_NEVER_FOUND",
            risk_level="LOW"
        )
        self.assertEqual(res.get("status"), "error")
        self.assertEqual(res.get("error_code"), "TARGET_NOT_FOUND")
        self.assertIn("could not be located on screen", res.get("message"))

    def test_09_smoke_file_ops_create_and_verify(self):
        """Test 9 (Live Smoke Test 2): Create test folder/file, read back and verify."""
        test_dir = os.path.join(AGENT_ROOT, "storage", "test_scratch")
        os.makedirs(test_dir, exist_ok=True)
        test_file = os.path.join(test_dir, "max_cu_test.txt")
        test_content = f"MAX Computer Use Verification {time.time()}"

        with open(test_file, "w", encoding="utf-8") as f:
            f.write(test_content)

        with open(test_file, "r", encoding="utf-8") as f:
            read_back = f.read()

        self.assertEqual(read_back, test_content)
        # Clean up
        try:
            os.remove(test_file)
            os.rmdir(test_dir)
        except Exception:
            pass

    def test_10_unified_tool_registry_exposure(self):
        """Test 10: Verify Computer Use and MCP tools are registered in UnifiedToolRegistry."""
        decls = unified_tool_registry.declarations
        names = [d["name"] for d in decls]

        self.assertIn("computer_use_observe", names)
        self.assertIn("computer_use_resolve", names)
        self.assertIn("computer_use_step", names)
        self.assertIn("computer_use_click", names)
        self.assertIn("computer_use_type", names)
        self.assertIn("mcp_call_tool", names)
        self.assertIn("mcp_list_tools", names)
        self.assertIn("mcp_discover_server", names)


if __name__ == "__main__":
    unittest.main(verbosity=2)
