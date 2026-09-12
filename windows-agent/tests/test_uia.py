"""
MAX AI — Microsoft UI Automation (UIA) Tests
Validates COM engine instantiation, root desktop retrieval, and tree inspection.
"""

import os
import sys
import unittest

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from core.ui_automation import uia_engine


class TestUIAutomation(unittest.TestCase):

    def test_01_engine_initialization(self):
        """Verify UIA COM engine initializes without exception."""
        self.assertIsNotNone(uia_engine)

    def test_02_root_element_inspection(self):
        """Verify root desktop element query works if UIA is available."""
        if uia_engine.is_available:
            root = uia_engine.get_root_element()
            self.assertIsNotNone(root)
            props = uia_engine.get_element_properties(root)
            self.assertIsInstance(props, dict)
            self.assertIn("name", props)

    def test_03_active_tree_inspection(self):
        """Verify active UI tree inspection returns a list of interactive controls."""
        if uia_engine.is_available:
            controls = uia_engine.inspect_active_ui_tree(max_elements=5)
            self.assertIsInstance(controls, list)


if __name__ == "__main__":
    unittest.main()
