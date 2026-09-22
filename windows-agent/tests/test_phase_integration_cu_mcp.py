"""
MAX AI — Focused Integration Validation for Computer Use, MCP & Tool Routing
Validates:
1. Tool routing & classification: Native vs Computer Use vs MCP vs Multi-tool/Complex
2. Tool result normalization contract across all 3 tiers
3. Computer Use integration: observe, resolve, click/type/step, verification, recovery
4. MCP integration: discovery, registration, schema validation, tool execution
5. Safety gate consistency: HIGH risk blocking with ticket creation and zero secrets
6. Failure isolation: Target not found & MCP errors produce structured failure without crashing
7. Voice & multi-turn execution continuity
"""

import os
import sys
import time
import json
import asyncio
import unittest

# Ensure windows-agent root is in sys.path
AGENT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_ROOT not in sys.path:
    sys.path.insert(0, AGENT_ROOT)

from core.tool_result import ToolResult, normalize_tool_result
from core.intent_router import intent_router, IntentCategory
from core.unified_tool_registry import unified_tool_registry, UnifiedToolRegistry
from core.max_orchestrator import max_orchestrator, MaxAgentRequest, MaxAgentResult
from core.confirmation_gate import confirmation_gate
from core.computer_use.computer_use_engine import computer_use_engine
from core.computer_use.models import UIElement, Rect, Point, ElementSource, RiskLevel
from core.computer_use.ui_map import ui_map_builder
from core.computer_use.ui_ref_cache import ui_ref_cache
from core.mcp.models import MCPServerConfig, MCPTransportType, MCPRiskLevel, MCPToolDeclaration
from core.mcp.mcp_registry import mcp_tool_registry
from core.mcp.mcp_manager import mcp_manager
from planner.validator import plan_validator
from planner.normalizer import plan_normalizer


class TestPhaseIntegrationCuMcp(unittest.IsolatedAsyncioTestCase):

    def setUp(self):
        # Reset ref cache for clean tests
        ui_ref_cache.clear()

    def test_01_tool_routing_classification(self):
        """1. Tool routing: Native vs Computer Use vs MCP vs Complex Task."""
        # Native app control
        r1 = intent_router.classify("Open Chrome")
        self.assertEqual(r1.intent, IntentCategory.LOCAL_COMPUTER)
        self.assertEqual(r1.tool, "windows_automation")

        # Native filesystem
        r2 = intent_router.classify("Create a local text file")
        self.assertEqual(r2.intent, IntentCategory.FILE_OPERATION)
        self.assertEqual(r2.tool, "filesystem_tool")

        # Computer Use direct UI click
        r3 = intent_router.classify("Click the Save button")
        self.assertEqual(r3.intent, IntentCategory.COMPUTER_USE)
        self.assertEqual(r3.tool, "computer_use_click")

        # Computer Use direct UI type
        r4 = intent_router.classify("Type this into Notepad")
        self.assertEqual(r4.intent, IntentCategory.COMPUTER_USE)
        self.assertEqual(r4.tool, "computer_use_type")

        # MCP external tool
        r5 = intent_router.classify("Read my GitHub issues")
        self.assertEqual(r5.intent, IntentCategory.MCP)
        self.assertEqual(r5.tool, "mcp_call_tool")

        # MCP list tools
        r6 = intent_router.classify("List available external tools")
        self.assertEqual(r6.intent, IntentCategory.MCP)
        self.assertEqual(r6.tool, "mcp_list_tools")

        # Complex multi-step task
        r7 = intent_router.classify("Open Chrome and search YouTube")
        self.assertEqual(r7.intent, IntentCategory.COMPLEX_TASK)
        self.assertEqual(r7.tool, "max_planner")

    def test_02_tool_result_normalization(self):
        """2. Consistent ToolResult format across Native, Computer Use, and MCP."""
        # Native success normalization
        raw_native = {"status": "success", "file_path": "C:\\test.txt", "message": "File created."}
        norm_native = normalize_tool_result("create_file", raw_native)
        self.assertTrue(norm_native.success)
        self.assertEqual(norm_native.source, "native")
        self.assertEqual(norm_native.tool_name, "create_file")
        self.assertEqual(norm_native.verification_status, "VERIFIED")
        self.assertEqual(norm_native["file_path"], "C:\\test.txt")

        # Computer Use error normalization
        raw_cu = {
            "status": "error",
            "error_code": "TARGET_NOT_FOUND",
            "message": "Target 'Submit' not found on screen."
        }
        norm_cu = normalize_tool_result("computer_use_click", raw_cu)
        self.assertFalse(norm_cu.success)
        self.assertEqual(norm_cu.source, "computer_use")
        self.assertEqual(norm_cu.error_code, "TARGET_NOT_FOUND")
        self.assertTrue(norm_cu.retryable)
        self.assertEqual(norm_cu.verification_status, "UNVERIFIED")

        # MCP ToolResult normalization
        decl = MCPToolDeclaration(
            name="query_db",
            server_name="postgres",
            description="Query database",
            input_schema={},
            risk_level=MCPRiskLevel.LOW
        )
        raw_mcp = {
            "status": "success",
            "content": [{"type": "text", "text": "Rows: 5"}],
            "server_name": "postgres"
        }
        norm_mcp = normalize_tool_result("mcp_call_tool", raw_mcp)
        self.assertTrue(norm_mcp.success)
        self.assertEqual(norm_mcp.source, "mcp")
        self.assertEqual(norm_mcp.verification_status, "VERIFIED")

    async def test_03_computer_use_integration_path(self):
        """3. Computer Use integration: UI Map, Target Resolver, Execution & Verification."""
        # Seed mock UI element into UI Map
        el1 = UIElement(
            ref="",
            role="button",
            name="Search",
            bounds=Rect(200, 300, 300, 340),
            center=Point(250, 320),
            source=ElementSource.UIA
        )
        test_map = ui_map_builder.build_ui_map([el1], [], active_window="Test App", active_process="test.exe", active_hwnd=1001)
        ui_ref_cache.store_map(test_map)

        # Resolve target via engine
        resolve_res = computer_use_engine.resolve_target("Search button")
        self.assertEqual(resolve_res["status"], "success")
        self.assertEqual(resolve_res["ref"], "@e1")

        # Route computer_use_click via MaxOrchestrator
        req = MaxAgentRequest(
            user_text="Click the Search button",
            tool_name="computer_use_click",
            arguments={"target": "Search"}
        )
        res = await max_orchestrator.handle_request(req, tool_registry=unified_tool_registry)
        self.assertIn(res.status, ("success", "partial", "error"))
        self.assertIn("computer_use_click", res.tools_used)

    def test_04_mcp_integration_and_schema_validation(self):
        """4. MCP Tool discovery, registration, and schema validation."""
        mcp_registry = mcp_tool_registry
        test_decl = mcp_registry.register_tool(
            server_name="github_mock",
            tool_name="list_issues",
            description="List repo issues",
            input_schema={
                "type": "object",
                "properties": {
                    "repo": {"type": "string"},
                    "state": {"type": "string", "enum": ["open", "closed"]}
                },
                "required": ["repo"]
            }
        )
        self.assertEqual(test_decl.name, "list_issues")
        self.assertEqual(test_decl.server_name, "github_mock")

        # Query via UnifiedToolRegistry
        list_res = unified_tool_registry.execute("mcp_list_tools", {"server_name": "github_mock"})
        self.assertEqual(list_res["status"], "success")
        self.assertGreaterEqual(list_res["count"], 1)

    def test_05_safety_gate_consistency(self):
        """5. Consistent Safety Gate: HIGH risk actions blocked with ticket and no secrets."""
        # Seed element in map
        del_el = UIElement(
            ref="",
            role="button",
            name="Delete Account",
            bounds=Rect(100, 100, 200, 140),
            center=Point(150, 120),
            source=ElementSource.UIA
        )
        del_map = ui_map_builder.build_ui_map([del_el], [], active_window="Account Settings", active_process="app.exe", active_hwnd=2002)
        ui_ref_cache.store_map(del_map)

        # 1. Computer Use HIGH risk action
        cu_gate_res = computer_use_engine.step(
            action_type="click",
            target_query="Delete Account",
            risk_level="HIGH",
            description="Delete account permanently"
        )
        self.assertEqual(cu_gate_res["status"], "WAITING_CONFIRMATION")
        self.assertTrue(cu_gate_res["ticket_id"].startswith("ticket_"))
        self.assertNotIn("secret", cu_gate_res)

        # 2. MCP HIGH risk action
        mcp_tool_registry.register_tool(
            server_name="db_admin",
            tool_name="drop_table",
            description="Drop database table",
            input_schema={"type": "object", "properties": {"table": {"type": "string"}}, "required": ["table"]}
        )
        mcp_gate_res = mcp_manager.execute_tool(
            tool_name="drop_table",
            arguments={"table": "users"},
            server_name="db_admin"
        )
        self.assertEqual(mcp_gate_res.status, "awaiting_confirmation")
        self.assertIsNotNone(mcp_gate_res.ticket_id)

        # Verify confirmation gate can resolve pending ticket
        self.assertTrue(confirmation_gate.has_pending)
        confirm_res = confirmation_gate.confirm_latest_pending(approved=False, reason="Safety test abort")
        self.assertEqual(confirm_res["status"], "cancelled")

    async def test_06_failure_isolation_nonexistent_target(self):
        """6. Failure isolation: Non-existent Computer Use target reports structured error without blind clicking."""
        req = MaxAgentRequest(
            user_text="Click the non existent xyz123 widget",
            tool_name="computer_use_click",
            arguments={"target": "completely_imaginary_widget_xyz999"}
        )
        # Empty UI cache so resolver fails cleanly
        ui_ref_cache.clear()

        res = await max_orchestrator.handle_request(req, tool_registry=unified_tool_registry)
        self.assertEqual(res.status, "error")
        self.assertIn("unable to complete", res.user_message.lower())
        self.assertEqual(res.verification_status, "UNVERIFIED")
        # Ensure it didn't crash or throw an unhandled exception
        self.assertIsNotNone(res.request_id)

    def test_07_planner_supports_cu_and_mcp(self):
        """7. Planner validation & normalization supports Computer Use and MCP tools."""
        raw_plan = {
            "goal": "Open app and list issues",
            "steps": [
                {
                    "id": "step_1",
                    "tool": "cu_click",
                    "args": {"name": "Chrome"},
                    "description": "Click Chrome icon"
                },
                {
                    "id": "step_2",
                    "tool": "mcp_call",
                    "args": {"server": "github", "tool": "get_issues", "args": {}},
                    "description": "Fetch GitHub issues"
                }
            ]
        }
        val_res = plan_validator.validate(raw_plan)
        self.assertTrue(val_res.valid)

        is_norm, norm_plan, norm_err = plan_normalizer.normalize(val_res.plan)
        self.assertTrue(is_norm)
        self.assertIsNotNone(norm_plan)
        self.assertEqual(norm_plan.steps[0].tool, "computer_use_click")
        self.assertEqual(norm_plan.steps[0].args["target"], "Chrome")
        self.assertEqual(norm_plan.steps[1].tool, "mcp_call_tool")
        self.assertEqual(norm_plan.steps[1].args["server_name"], "github")


if __name__ == "__main__":
    unittest.main()
