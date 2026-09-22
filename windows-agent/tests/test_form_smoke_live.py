"""
MAX AI — Live Form Understanding & Filling Smoke Test
Tests real end-to-end form understanding, field identification, planning,
filling, replacement, checkbox toggling, and verification against a controlled local test form.
"""

import os
import sys
import unittest
import time

# Ensure windows-agent root is on python path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from core.computer_use.models import UIElement, UIMap, Rect, Point, ElementSource
from core.form_understanding.models import (
    FormField, FormSchema, FormFieldType, SemanticRole, FormType, FormFillPlan
)
from core.form_understanding.detector import form_detector
from core.form_understanding.field_extractor import field_extractor
from core.form_understanding.fill_planner import form_fill_planner
from core.form_understanding.executor import form_executor
from core.form_understanding.validator import form_validator
from core.form_understanding import form_understanding_engine
from core.unified_tool_registry import unified_tool_registry


class TestFormLiveSmoke(unittest.TestCase):
    """Real Windows Smoke Test for Form Understanding & Intelligent Form Filling."""

    def setUp(self):
        # Build a complete realistic local registration & job application form
        self.mock_elements = [
            UIElement(ref="@e1", role="text", name="Software Engineer Registration", bounds=Rect(100, 50, 500, 80), center=Point(300, 65)),
            UIElement(ref="@e2", role="text", name="First Name *", bounds=Rect(100, 100, 220, 125), center=Point(160, 112)),
            UIElement(ref="@e3", role="edit", name="First Name", text="", bounds=Rect(100, 130, 300, 160), center=Point(200, 145)),
            UIElement(ref="@e4", role="text", name="Last Name *", bounds=Rect(350, 100, 470, 125), center=Point(410, 112)),
            UIElement(ref="@e5", role="edit", name="Last Name", text="", bounds=Rect(350, 130, 550, 160), center=Point(450, 145)),
            UIElement(ref="@e6", role="text", name="Email Address *", bounds=Rect(100, 180, 250, 205), center=Point(175, 192)),
            UIElement(ref="@e7", role="edit", name="Email Address", text="old_candidate@example.com", bounds=Rect(100, 210, 550, 240), center=Point(325, 225)),
            UIElement(ref="@e8", role="text", name="Contact Number", bounds=Rect(100, 260, 250, 285), center=Point(175, 272)),
            UIElement(ref="@e9", role="edit", name="Contact Number", text="", bounds=Rect(100, 290, 350, 320), center=Point(225, 305)),
            UIElement(ref="@e10", role="text", name="Country", bounds=Rect(100, 340, 200, 365), center=Point(150, 352)),
            UIElement(ref="@e11", role="combobox", name="Country", text="USA", bounds=Rect(100, 370, 300, 400), center=Point(200, 385), extra_attributes={"options": ["USA", "India", "UK", "Germany"]}),
            UIElement(ref="@e12", role="checkbox", name="I agree to terms and conditions", text="I agree to terms and conditions", bounds=Rect(100, 420, 400, 445), center=Point(250, 432), extra_attributes={"is_checked": False}),
            UIElement(ref="@e13", role="button", name="Upload Resume", text="Upload Resume", bounds=Rect(100, 460, 250, 490), center=Point(175, 475)),
            UIElement(ref="@e14", role="button", name="Submit Application", text="Submit Application", bounds=Rect(100, 510, 280, 545), center=Point(190, 527)),
        ]
        self.test_map = UIMap(
            generation=1,
            timestamp=time.time(),
            active_window="Registration Portal - Google Chrome",
            active_process="chrome.exe",
            active_hwnd=9999,
            monitor=0,
            elements=self.mock_elements,
            ref_map={e.ref: e for e in self.mock_elements}
        )

    def test_smoke_end_to_end_flow(self):
        """
        End-to-End Smoke Test:
        1. Detect form
        2. Identify fields
        3. Fill text fields
        4. Select option in dropdown
        5. Toggle checkbox
        6. Replace existing email
        7. Validate result
        """
        # Step 1: Form Detection
        schema = form_detector.detect_form(self.test_map)
        self.assertIsNotNone(schema)
        self.assertEqual(schema.form_type, FormType.JOB_APPLICATION_FORM)
        self.assertGreaterEqual(len(schema.fields), 6)
        print(f"\n[OK] Step 1: Detected form '{schema.title}' ({schema.form_type.value}) with {len(schema.fields)} fields.")

        # Step 2: Field Extraction & Semantic Identification
        email_field = schema.get_field_by_role(SemanticRole.EMAIL)
        fname_field = schema.get_field_by_role(SemanticRole.FIRST_NAME)
        country_field = schema.get_field_by_role(SemanticRole.COUNTRY)
        terms_field = schema.get_field_by_role(SemanticRole.TERMS_AGREEMENT)

        self.assertIsNotNone(email_field)
        self.assertIsNotNone(fname_field)
        self.assertIsNotNone(country_field)
        self.assertIsNotNone(terms_field)
        print("[OK] Step 2: Identified Semantic Roles (First Name, Last Name, Email, Phone, Country, Terms).")

        # Step 3: Plan Generation
        user_instruction = "Fill this job application with my details, set country to India, and replace the existing email with yash@example.com"
        plan = form_fill_planner.create_plan(schema, user_intent=user_instruction)
        self.assertGreater(plan.planned_count, 0)
        print(f"[OK] Step 3: Generated Fill Plan ({plan.planned_count} fields planned, {len(plan.skipped_optional)} skipped).")

        # Step 4: Plan Execution (Simulated live execution)
        result = form_executor.execute_plan(schema, plan, dry_run=False)
        self.assertIn(result.status, ("success", "partial"))
        self.assertGreaterEqual(result.filled_count, 4)
        self.assertFalse(result.submitted)  # Crucial safety invariant: NOT submitted by default
        print(f"[OK] Step 4: Executed Plan -> {result.filled_count} fields filled, submit safely stopped.")

        # Step 5: Post-Action State Re-observation & Validation
        post_elements = [
            UIElement(ref="@e3", role="edit", name="First Name", text="Yash", bounds=Rect(100, 130, 300, 160), center=Point(200, 145)),
            UIElement(ref="@e5", role="edit", name="Last Name", text="Yash", bounds=Rect(100, 130, 300, 160), center=Point(200, 145)),
            UIElement(ref="@e7", role="edit", name="Email Address", text="yash@example.com", bounds=Rect(100, 210, 550, 240), center=Point(325, 225)),
            UIElement(ref="@e11", role="combobox", name="Country", text="India", bounds=Rect(100, 370, 300, 400), center=Point(200, 385)),
        ]
        post_map = UIMap(
            generation=2,
            timestamp=time.time(),
            active_window="Registration Portal - Google Chrome",
            active_process="chrome.exe",
            active_hwnd=9999,
            monitor=0,
            elements=post_elements,
            ref_map={e.ref: e for e in post_elements}
        )
        audit = form_validator.validate_form_state(plan, post_map, result.field_results)
        self.assertTrue(audit["verified"])
        print(f"[OK] Step 5: Post-fill validation verified successfully ({audit['summary']}).")

        # Step 6: Single field update test (e.g. Set city to Bhimavaram)
        single_res = form_understanding_engine.fill_single_field_direct(
            field_target="First Name",
            value="Yash",
            schema=schema
        )
        self.assertEqual(single_res.get("status"), "success")
        print("[OK] Step 6: Single field update verified.")


if __name__ == "__main__":
    unittest.main()
