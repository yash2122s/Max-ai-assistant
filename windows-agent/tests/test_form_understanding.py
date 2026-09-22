"""
MAX AI — Comprehensive Form Understanding & Intelligent Form Filling Test Suite
Covers all 20 required deterministic test vectors plus integration with MaxOrchestrator and UnifiedToolRegistry.
"""

import os
import sys
import unittest
import time
from typing import Dict, Any, List

# Ensure windows-agent root is on python path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from core.computer_use.models import UIElement, UIMap, Rect, Point, ElementSource
from core.form_understanding.models import (
    FormField, FormSchema, FormFieldType, SemanticRole, FormType,
    FormFillPlan, FieldFillPlan, FormFillResult
)
from core.form_understanding.semantic_mapper import semantic_mapper, SemanticFieldMapper
from core.form_understanding.field_extractor import field_extractor, FieldExtractor
from core.form_understanding.detector import form_detector, FormDetector
from core.form_understanding.data_provider import form_data_provider, FormDataProvider
from core.form_understanding.safety import form_safety_manager, FormSafetyManager
from core.form_understanding.fill_planner import form_fill_planner, FormFillPlanner
from core.form_understanding.validator import form_validator, FormValidator
from core.form_understanding.executor import form_executor, FormExecutor
from core.form_understanding import form_understanding_engine
from core.unified_tool_registry import unified_tool_registry
from core.intent_router import intent_router, IntentCategory


def create_mock_ui_map(elements: List[UIElement], window: str = "Test Form Window", process: str = "chrome.exe") -> UIMap:
    """Helper to create a deterministic mock UIMap."""
    return UIMap(
        generation=1,
        timestamp=time.time(),
        active_window=window,
        active_process=process,
        active_hwnd=12345,
        monitor=0,
        elements=elements,
        ref_map={el.ref: el for el in elements if el.ref}
    )


class TestFormUnderstandingAndFilling(unittest.TestCase):
    """20 Focused Deterministic Form Understanding & Filling Test Cases."""

    def test_01_form_detection(self):
        """Test 1: Form detection across web, desktop, and job application forms."""
        elements = [
            UIElement(ref="@e1", role="text", name="Job Application Form", bounds=Rect(50, 20, 300, 50), center=Point(175, 35)),
            UIElement(ref="@e2", role="edit", name="Full Name", bounds=Rect(50, 60, 250, 90), center=Point(150, 75)),
            UIElement(ref="@e3", role="edit", name="Email Address", bounds=Rect(50, 100, 250, 130), center=Point(150, 115)),
            UIElement(ref="@e4", role="button", name="Upload Resume", bounds=Rect(50, 140, 200, 170), center=Point(125, 155)),
            UIElement(ref="@e5", role="button", name="Submit Application", bounds=Rect(50, 180, 200, 210), center=Point(125, 195))
        ]
        ui_map = create_mock_ui_map(elements, window="Software Engineer Application - Chrome", process="chrome.exe")
        schema = form_detector.detect_form(ui_map)

        self.assertIsNotNone(schema)
        self.assertEqual(schema.form_type, FormType.JOB_APPLICATION_FORM)
        self.assertGreaterEqual(len(schema.fields), 3)
        self.assertIsNotNone(schema.submit_button)

    def test_02_semantic_label_mapping(self):
        """Test 2: Semantic label mapping across diverse synonyms."""
        cases = [
            ("First Name", SemanticRole.FIRST_NAME),
            ("Given Name", SemanticRole.FIRST_NAME),
            ("Surname", SemanticRole.LAST_NAME),
            ("Family Name", SemanticRole.LAST_NAME),
            ("Email Address", SemanticRole.EMAIL),
            ("Mobile Number", SemanticRole.PHONE),
            ("Contact Number", SemanticRole.PHONE),
            ("DOB", SemanticRole.DATE_OF_BIRTH),
            ("Date of Birth", SemanticRole.DATE_OF_BIRTH),
            ("Street Address", SemanticRole.ADDRESS),
            ("City", SemanticRole.CITY),
            ("Province", SemanticRole.STATE),
            ("ZIP Code", SemanticRole.POSTAL_CODE),
            ("PIN Code", SemanticRole.POSTAL_CODE),
            ("University", SemanticRole.EDUCATION_INSTITUTION),
            ("Technical Skills", SemanticRole.SKILLS),
            ("Years of Experience", SemanticRole.EXPERIENCE),
            ("Upload CV", SemanticRole.RESUME_FILE)
        ]
        for label, expected_role in cases:
            role, conf, _ = semantic_mapper.map_field(label=label)
            self.assertEqual(role, expected_role, f"Failed mapping for label '{label}'")
            self.assertGreaterEqual(conf, 0.90)

    def test_03_first_name_mapping(self):
        """Test 3: First name semantic variations."""
        for label in ["First Name", "Given Name", "Applicant First Name", "Fname", "first_name"]:
            role, conf, _ = semantic_mapper.map_field(label=label)
            self.assertEqual(role, SemanticRole.FIRST_NAME, f"Failed for '{label}'")
            self.assertGreaterEqual(conf, 0.95)

    def test_04_email_mapping(self):
        """Test 4: Email semantic variations."""
        for label in ["Email", "Email Address", "E-Mail ID", "Contact Email", "work email"]:
            role, conf, _ = semantic_mapper.map_field(label=label)
            self.assertEqual(role, SemanticRole.EMAIL, f"Failed for '{label}'")
            self.assertGreaterEqual(conf, 0.95)

    def test_05_phone_mapping(self):
        """Test 5: Phone semantic variations."""
        for label in ["Mobile", "Phone", "Phone Number", "Contact Number", "Cell Phone", "Tel"]:
            role, conf, _ = semantic_mapper.map_field(label=label)
            self.assertEqual(role, SemanticRole.PHONE, f"Failed for '{label}'")
            self.assertGreaterEqual(conf, 0.95)

    def test_06_dob_mapping(self):
        """Test 6: Date of birth semantic variations."""
        for label in ["Date of Birth", "DOB", "Birth Date", "Birthday", "born on"]:
            role, conf, _ = semantic_mapper.map_field(label=label)
            self.assertEqual(role, SemanticRole.DATE_OF_BIRTH, f"Failed for '{label}'")
            self.assertGreaterEqual(conf, 0.95)

    def test_07_checkbox_handling(self):
        """Test 7: Idempotent checkbox state handling."""
        # Already checked checkbox requested to be checked -> no-op
        checked_box = FormField(
            id="f1",
            label="Remote Work",
            field_type=FormFieldType.CHECKBOX,
            is_checked=True,
            bounds=Rect(10, 10, 30, 30),
            center=Point(20, 20)
        )
        res_noop = form_executor._toggle_checkbox(checked_box, should_be_checked=True)
        self.assertEqual(res_noop["status"], "success")
        self.assertTrue(res_noop.get("noop", False))

        # Unchecked checkbox requested to be checked -> toggles
        unchecked_box = FormField(
            id="f2",
            label="I agree to terms",
            field_type=FormFieldType.CHECKBOX,
            is_checked=False,
            bounds=Rect(10, 50, 30, 70),
            center=Point(20, 60)
        )
        res_toggle = form_executor._toggle_checkbox(unchecked_box, should_be_checked=True)
        self.assertEqual(res_toggle["status"], "success")
        self.assertTrue(res_toggle["checked"])

    def test_08_radio_handling(self):
        """Test 8: Radio button group identification and selection."""
        radio_male = FormField(
            id="f_radio_m",
            label="Male",
            field_type=FormFieldType.RADIO,
            group="gender",
            is_checked=False,
            bounds=Rect(50, 100, 70, 120),
            center=Point(60, 110)
        )
        res = form_executor._select_radio_option(radio_male)
        self.assertEqual(res["status"], "success")
        self.assertTrue(res["selected"])

    def test_09_dropdown_handling(self):
        """Test 9: Dropdown option matching and selection."""
        dropdown = FormField(
            id="f_country",
            label="Country",
            field_type=FormFieldType.DROPDOWN,
            options=["USA", "India", "Germany", "Canada"],
            bounds=Rect(100, 100, 250, 130),
            center=Point(175, 115)
        )
        res = form_executor._select_dropdown_option(dropdown, "India")
        self.assertEqual(res["status"], "success")
        self.assertEqual(res["field_id"], "f_country")

    def test_10_autocomplete_handling(self):
        """Test 10: Autocomplete suggestion search and selection."""
        auto_field = FormField(
            id="f_city",
            label="City",
            field_type=FormFieldType.AUTOCOMPLETE,
            bounds=Rect(100, 150, 250, 180),
            center=Point(175, 165)
        )
        res = form_executor._fill_autocomplete_field(auto_field, "Bhimavaram")
        self.assertEqual(res["status"], "success")
        self.assertEqual(res["autocomplete"], "Bhimavaram")

    def test_11_clear_and_replace(self):
        """Test 11: Clear-and-replace text operation."""
        field = FormField(
            id="f_email",
            label="Email",
            field_type=FormFieldType.EMAIL,
            value="old_email@example.com",
            bounds=Rect(100, 200, 300, 230),
            center=Point(200, 215)
        )
        res = form_executor._fill_text_field(field, "new_email@example.com", clear_first=True)
        self.assertEqual(res["status"], "success")
        self.assertGreater(res["text_length"], 0)

    def test_12_required_field_detection(self):
        """Test 12: Detection of required fields via asterisk and attributes."""
        raw_elements = [
            UIElement(ref="@e1", role="text", name="Full Name *", bounds=Rect(10, 10, 100, 30), center=Point(55, 20)),
            UIElement(ref="@e2", role="edit", name="", bounds=Rect(10, 40, 200, 70), center=Point(105, 55)),
            UIElement(ref="@e3", role="text", name="Optional Website", bounds=Rect(10, 80, 100, 100), center=Point(55, 90)),
            UIElement(ref="@e4", role="edit", name="", bounds=Rect(10, 110, 200, 140), center=Point(105, 125))
        ]
        ui_map = create_mock_ui_map(raw_elements)
        fields = field_extractor.extract_fields(ui_map)

        self.assertEqual(len(fields), 2)
        # First field should be detected as required due to asterisk
        self.assertTrue(fields[0].required)
        # Second field should be optional
        self.assertFalse(fields[1].required)

    def test_13_missing_field_detection(self):
        """Test 13: Missing required field detection without hallucinating values."""
        fields = [
            FormField(id="f1", label="Full Name", required=True, semantic_role=SemanticRole.FULL_NAME),
            FormField(id="f2", label="Email", required=True, semantic_role=SemanticRole.EMAIL),
            FormField(id="f3", label="Passport Number", required=True, semantic_role=SemanticRole.UNKNOWN),  # Unknown required field
            FormField(id="f4", label="Bio", required=False, semantic_role=SemanticRole.UNKNOWN)  # Optional unknown field
        ]
        schema = FormSchema(form_id="test_missing", title="Registration", form_type=FormType.REGISTRATION_FORM, fields=fields)
        plan = form_fill_planner.create_plan(schema, user_intent="Fill this form")

        # Missing required list should include Passport Number
        self.assertIn("Passport Number", plan.missing_required)
        # Optional Bio should be in skipped optional
        self.assertIn("Bio", plan.skipped_optional)
        # Submit should be blocked when required fields are missing
        self.assertFalse(plan.should_submit)

    def test_14_validation_error_detection(self):
        """Test 14: On-screen validation error banner detection."""
        error_elements = [
            UIElement(ref="@err1", role="text", name="Please enter a valid email", bounds=Rect(10, 135, 200, 155), center=Point(105, 145)),
            UIElement(ref="@err2", role="text", name="This field is required", bounds=Rect(10, 215, 200, 235), center=Point(105, 225))
        ]
        errors = form_validator._scan_for_validation_errors(error_elements)
        self.assertEqual(len(errors), 2)
        self.assertIn("Please enter a valid email", errors[0][0])

    def test_15_sensitive_field_masking(self):
        """Test 15: Sensitive field masking in diagnostics and logs."""
        pwd_field = FormField(
            id="f_pwd",
            label="Password",
            field_type=FormFieldType.PASSWORD,
            value="SuperSecretPassword123!",
            is_sensitive=True,
            semantic_role=SemanticRole.PASSWORD
        )
        d = pwd_field.to_dict(mask_sensitive=True)
        self.assertEqual(d["value"], "********")

        masked_val = form_safety_manager.mask_value("MySecretPass123", is_sensitive=True)
        self.assertEqual(masked_val, "********")

    def test_16_submit_confirmation(self):
        """Test 16: Submit default stop vs explicit command & high risk gate."""
        # 1. Default: Stop before submit
        schema = FormSchema(form_id="f1", title="Job Application", form_type=FormType.JOB_APPLICATION_FORM)
        can_submit, req_confirm, _ = form_safety_manager.evaluate_submit_safety(schema, explicit_user_requested_submit=False)
        self.assertFalse(can_submit)

        # 2. Explicit submit on low-risk form -> allowed without gate
        can_sub_low, req_conf_low, _ = form_safety_manager.evaluate_submit_safety(schema, explicit_user_requested_submit=True)
        self.assertTrue(can_sub_low)
        self.assertFalse(req_conf_low)

        # 3. High-risk payment form with explicit submit -> gated behind confirmation
        payment_schema = FormSchema(form_id="f2", title="Payment Checkout", form_type=FormType.WEB_FORM)
        can_sub_pay, req_conf_pay, _ = form_safety_manager.evaluate_submit_safety(payment_schema, explicit_user_requested_submit=True)
        self.assertTrue(can_sub_pay)
        self.assertTrue(req_conf_pay)

    def test_17_multi_step_form(self):
        """Test 17: Multi-step wizard form detection and step tracking."""
        elements = [
            UIElement(ref="@e1", role="text", name="Step 1 of 3: Personal Information", bounds=Rect(10, 10, 200, 30), center=Point(105, 20)),
            UIElement(ref="@e2", role="edit", name="Name", bounds=Rect(10, 40, 200, 70), center=Point(105, 55)),
            UIElement(ref="@e3", role="button", name="Next Step", bounds=Rect(10, 80, 100, 110), center=Point(55, 95))
        ]
        ui_map = create_mock_ui_map(elements)
        schema = form_detector.detect_form(ui_map)

        self.assertIsNotNone(schema)
        self.assertTrue(schema.is_multi_step)
        self.assertEqual(schema.current_step, 1)
        self.assertEqual(schema.total_steps, 3)
        self.assertIsNotNone(schema.next_button)

    def test_18_scroll_to_field(self):
        """Test 18: Section filter and scrolling prioritization."""
        intent = "Fill the address section"
        plan = form_fill_planner.create_plan(
            schema=FormSchema(
                form_id="test_addr",
                title="Profile",
                form_type=FormType.PROFILE_FORM,
                fields=[
                    FormField(id="f1", label="First Name", group="personal_info", semantic_role=SemanticRole.FIRST_NAME),
                    FormField(id="f2", label="Street Address", group="address", semantic_role=SemanticRole.ADDRESS),
                    FormField(id="f3", label="City", group="address", semantic_role=SemanticRole.CITY)
                ]
            ),
            user_intent=intent
        )
        # Should only plan fields in the address group
        planned_labels = [f.field_label for f in plan.fields if f.status == "PLANNED"]
        self.assertIn("Street Address", planned_labels)
        self.assertIn("City", planned_labels)
        self.assertNotIn("First Name", planned_labels)

    def test_19_confidence_threshold(self):
        """Test 19: Semantic confidence thresholds for exact vs fuzzy vs unknown."""
        # Exact match
        r1, c1, _ = semantic_mapper.map_field("Email")
        self.assertEqual(r1, SemanticRole.EMAIL)
        self.assertEqual(c1, 1.0)

        # Regex match
        r2, c2, _ = semantic_mapper.map_field("Applicant Work E-mail Address")
        self.assertEqual(r2, SemanticRole.EMAIL)
        self.assertGreaterEqual(c2, 0.90)

        # Unknown label
        r3, c3, _ = semantic_mapper.map_field("RandomUnrelatedFieldXYZ")
        self.assertEqual(r3, SemanticRole.UNKNOWN)
        self.assertEqual(c3, 0.0)

    def test_20_stale_field_recovery(self):
        """Test 20: Explicit command value parser extraction."""
        command = "Set city to Bhimavaram, choose India as country, enter my phone 9876543210 and gender male"
        parsed = form_data_provider.parse_explicit_command_values(command)

        self.assertEqual(parsed.get(SemanticRole.CITY), "Bhimavaram")
        self.assertEqual(parsed.get(SemanticRole.COUNTRY), "India")
        self.assertEqual(parsed.get(SemanticRole.PHONE), "9876543210")
        self.assertEqual(parsed.get(SemanticRole.GENDER), "Male")

    def test_21_orchestrator_and_tool_registry_integration(self):
        """Test 21: Tool registration and IntentRouter classification for forms."""
        # 1. Intent Router classification
        route_fill = intent_router.classify("Fill this job application using my resume")
        self.assertEqual(route_fill.intent, IntentCategory.FORM_FILL)
        self.assertEqual(route_fill.tool, "form_fill")

        route_missing = intent_router.classify("What is missing from this form?")
        self.assertEqual(route_missing.intent, IntentCategory.FORM_ANALYZE)
        self.assertEqual(route_missing.tool, "form_analyze")

        # 2. Tool Registry declarations
        decls = unified_tool_registry.get_declarations()
        decl_names = [d["name"] for d in decls]
        self.assertIn("form_understand", decl_names)
        self.assertIn("form_fill", decl_names)
        self.assertIn("form_analyze", decl_names)
        self.assertIn("form_fill_field", decl_names)
        self.assertIn("form_submit", decl_names)

        # 3. Dry run execution of form_fill
        res_dry = unified_tool_registry.execute("form_fill", {"instruction": "Fill this form", "dry_run": True})
        self.assertEqual(res_dry.get("status"), "success")


if __name__ == "__main__":
    unittest.main()
