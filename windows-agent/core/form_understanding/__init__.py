"""
MAX AI — Form Understanding & Intelligent Form Filling Engine
Core subsystem package providing perception, semantic mapping, multi-source data resolution,
fill planning, safe execution, and post-fill verification for Windows & Web forms.
"""

from typing import Dict, Any, Optional, List

from core.form_understanding.models import (
    FormField, FormSchema, FieldFillPlan, FormFillPlan, FormFillResult,
    FormFieldType, SemanticRole, FormType
)
from core.form_understanding.semantic_mapper import semantic_mapper, SemanticFieldMapper
from core.form_understanding.field_extractor import field_extractor, FieldExtractor
from core.form_understanding.detector import form_detector, FormDetector
from core.form_understanding.data_provider import form_data_provider, FormDataProvider
from core.form_understanding.safety import form_safety_manager, FormSafetyManager
from core.form_understanding.fill_planner import form_fill_planner, FormFillPlanner
from core.form_understanding.validator import form_validator, FormValidator
from core.form_understanding.executor import form_executor, FormExecutor
from core.computer_use.computer_use_engine import computer_use_engine
from core.tool_result import ToolResult, normalize_tool_result


class FormUnderstandingEngine:
    """
    High-level facade unifying Form Detection, Field Extraction, Planning, Execution, and Verification.
    """

    def __init__(self):
        self.detector = form_detector
        self.extractor = field_extractor
        self.mapper = semantic_mapper
        self.data_provider = form_data_provider
        self.planner = form_fill_planner
        self.executor = form_executor
        self.validator = form_validator
        self.safety = form_safety_manager
        self.computer_use = computer_use_engine

    def understand_current_form(self, include_ocr: bool = True) -> Dict[str, Any]:
        """
        Inspects the screen, detects visible forms, and extracts structural schema and summary.
        """
        # 1. Observe screen via Computer Use perception
        ui_map = self.computer_use.perception.observe(include_ocr=include_ocr)

        # 2. Detect and extract form schema
        schema = self.detector.detect_form(ui_map)
        if not schema:
            return {
                "status": "not_found",
                "message": "No form detected on current screen.",
                "fields": [],
                "form_type": "unknown"
            }

        return {
            "status": "success",
            "form_id": schema.form_id,
            "title": schema.title,
            "form_type": schema.form_type.value,
            "active_window": schema.active_window,
            "field_count": len(schema.fields),
            "is_multi_step": schema.is_multi_step,
            "current_step": schema.current_step,
            "has_captcha": schema.has_captcha,
            "summary": schema.to_summary(),
            "fields": [f.to_dict() for f in schema.fields]
        }

    def fill_current_form(
        self,
        instruction: str = "Fill this form",
        custom_data: Optional[Dict[str, Any]] = None,
        dry_run: bool = False
    ) -> Dict[str, Any]:
        """
        Closed-loop workflow:
        1. Observe screen -> 2. Detect Form -> 3. Match Data & Plan -> 4. Execute Safely -> 5. Verify & Report.
        """
        # 1. Observe
        ui_map = self.computer_use.perception.observe(include_ocr=True)

        # 2. Detect Form
        schema = self.detector.detect_form(ui_map)
        if not schema:
            return {
                "status": "not_found",
                "message": "No form found to fill on screen.",
                "filled_count": 0
            }

        # 3. Create Fill Plan
        plan = self.planner.create_plan(
            schema=schema,
            user_intent=instruction,
            custom_data=custom_data
        )

        # 4. Execute Plan & Verify
        result = self.executor.execute_plan(
            schema=schema,
            plan=plan,
            dry_run=dry_run
        )

        return result.to_dict()

    def analyze_missing_info(self, instruction: str = "") -> Dict[str, Any]:
        """
        Inspects the form, checks required fields against available data, and reports what is missing.
        """
        ui_map = self.computer_use.perception.observe(include_ocr=True)
        schema = self.detector.detect_form(ui_map)
        if not schema:
            return {
                "status": "not_found",
                "message": "No form detected on current screen."
            }

        plan = self.planner.create_plan(
            schema=schema,
            user_intent=instruction
        )

        known_fields = [f.field_label for f in plan.fields if f.status == "PLANNED"]
        missing_required = plan.missing_required
        skipped_optional = plan.skipped_optional

        summary_lines = [
            f"FORM ANALYSIS: '{schema.title}' ({schema.form_type.value})",
            f"Available & Ready to fill ({len(known_fields)}): {', '.join(known_fields) if known_fields else 'None'}",
            f"Missing Required Info ({len(missing_required)}): {', '.join(missing_required) if missing_required else 'None'}",
            f"Optional Fields Skipped ({len(skipped_optional)}): {', '.join(skipped_optional) if skipped_optional else 'None'}"
        ]

        return {
            "status": "success",
            "form_title": schema.title,
            "form_type": schema.form_type.value,
            "ready_to_fill": known_fields,
            "missing_required": missing_required,
            "skipped_optional": skipped_optional,
            "summary": "\n".join(summary_lines),
            "message": "\n".join(summary_lines)
        }

    def fill_single_field_direct(
        self,
        field_target: str,
        value: Any,
        is_checked: Optional[bool] = None,
        schema: Optional[FormSchema] = None
    ) -> Dict[str, Any]:
        """
        Directly fills or updates a specific named field (e.g. 'Set city to Bhimavaram', 'Select Java').
        """
        if schema is None:
            ui_map = self.computer_use.perception.observe(include_ocr=True)
            schema = self.detector.detect_form(ui_map)

        if not schema:
            # Fall back to generic Computer Use typing / clicking
            return self.computer_use.step(
                action_type="type_text",
                target_query=field_target,
                text=str(value)
            )

        # Match target field in schema
        matched_field = None
        for f in schema.fields:
            if field_target.lower() in f.label.lower() or field_target.lower() in (f.placeholder or "").lower():
                matched_field = f
                break

        if not matched_field:
            return self.computer_use.step(
                action_type="type_text",
                target_query=field_target,
                text=str(value)
            )

        # Build single field plan
        action_type = "FORM_TYPE"
        if matched_field.field_type == FormFieldType.CHECKBOX:
            action_type = "FORM_CHECK" if (is_checked or str(value).lower() in ("true", "yes", "check")) else "FORM_UNCHECK"
        elif matched_field.field_type in (FormFieldType.DROPDOWN, FormFieldType.COMBOBOX):
            action_type = "FORM_SELECT"
        elif matched_field.field_type == FormFieldType.RADIO:
            action_type = "FORM_CHOOSE"
        elif matched_field.field_type == FormFieldType.AUTOCOMPLETE:
            action_type = "FORM_AUTOCOMPLETE"
        elif matched_field.field_type == FormFieldType.FILE_UPLOAD:
            action_type = "FORM_UPLOAD"
        elif matched_field.value:
            action_type = "FORM_CLEAR_TYPE"

        plan_item = FieldFillPlan(
            field_id=matched_field.id,
            semantic_role=matched_field.semantic_role,
            field_label=matched_field.label,
            field_type=matched_field.field_type,
            target_value=value,
            action_type=action_type,
            status="PLANNED"
        )

        res = self.executor._execute_field_action(matched_field, plan_item)
        return res


form_understanding_engine = FormUnderstandingEngine()

__all__ = [
    "FormField",
    "FormSchema",
    "FieldFillPlan",
    "FormFillPlan",
    "FormFillResult",
    "FormFieldType",
    "SemanticRole",
    "FormType",
    "form_detector",
    "FormDetector",
    "field_extractor",
    "FieldExtractor",
    "semantic_mapper",
    "SemanticFieldMapper",
    "form_data_provider",
    "FormDataProvider",
    "form_safety_manager",
    "FormSafetyManager",
    "form_fill_planner",
    "FormFillPlanner",
    "form_validator",
    "FormValidator",
    "form_executor",
    "FormExecutor",
    "form_understanding_engine",
    "FormUnderstandingEngine"
]
