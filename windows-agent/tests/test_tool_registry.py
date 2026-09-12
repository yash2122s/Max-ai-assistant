"""
MAX AI — Unified Tool Registry Unit Tests
Validates canonical registry, tool declarations, confirmation gating,
and consolidation of duplicate registry paths.
"""

import os
import sys
import unittest

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from core.unified_tool_registry import unified_tool_registry
from core.tool_executor import ToolRegistry as ExecutorRegistry
from tools.tool_registry import ToolRegistry as ClassRegistry
from tools.windows_agent_tool import WindowsAgentTool


class TestToolRegistry(unittest.TestCase):

    def test_01_unified_declarations(self):
        """Verify unified tool declarations include voice, system, app, and UIA tools."""
        declarations = unified_tool_registry.declarations
        decl_names = [d["name"] for d in declarations]

        self.assertIn("open_application", decl_names)
        self.assertIn("close_application", decl_names)
        self.assertIn("click_ui_element", decl_names)
        self.assertIn("inspect_ui_elements", decl_names)
        self.assertIn("remember_fact", decl_names)
        self.assertIn("get_volume", decl_names)

    def test_02_executor_registry_delegation(self):
        """Verify core.tool_executor.ToolRegistry delegates to UnifiedToolRegistry."""
        exec_registry = ExecutorRegistry()
        self.assertEqual(len(exec_registry.declarations), len(unified_tool_registry.declarations))

    def test_03_class_registry_registration(self):
        """Verify tools.tool_registry.ToolRegistry registers BaseTools cleanly."""
        agent_tool = WindowsAgentTool()
        ClassRegistry.register(agent_tool)
        retrieved = ClassRegistry.get_tool("windows_agent")
        self.assertIsNotNone(retrieved)
        self.assertEqual(retrieved.name, "windows_agent")

    def test_04_dangerous_tool_confirmation_gate(self):
        """Verify dangerous tools trigger awaiting_confirmation gate."""
        # delete_file is a dangerous tool requiring confirmation
        res = unified_tool_registry.execute("delete_file", {"filepath": "non_existent_test_file.txt"})
        self.assertEqual(res.get("status"), "awaiting_confirmation")
        self.assertTrue(unified_tool_registry.has_pending_confirmation)

        # Cancel confirmation
        cancel_res = unified_tool_registry.cancel_pending()
        self.assertEqual(cancel_res.get("status"), "cancelled")
        self.assertFalse(unified_tool_registry.has_pending_confirmation)


if __name__ == "__main__":
    unittest.main()
