"""
MAX AI — Form Fill Planner & Order Optimizer
Constructs an intelligent, ordered, and verified FieldFillPlan from FormSchema and FormDataProvider.
Categorizes fields into known, inferred, missing, ambiguous, sensitive, and skipped.
"""

import re
import logging
from typing import List, Dict, Any, Optional, Tuple, Set

from core.form_understanding.models import (
    FormSchema, FormField, FormFieldType, SemanticRole,
    FormFillPlan, FieldFillPlan, FormType
)
from core.form_understanding.data_provider import form_data_provider, FormDataProvider
from core.form_understanding.safety import form_safety_manager, FormSafetyManager

logger = logging.getLogger("MAX.FormUnderstanding.Planner")

# Semantic Ordering Priority
ROLE_ORDER_PRIORITY: Dict[SemanticRole, int] = {
    # 1. Basic Identity (10-19)
    SemanticRole.FIRST_NAME: 10,
    SemanticRole.MIDDLE_NAME: 11,
    SemanticRole.LAST_NAME: 12,
    SemanticRole.FULL_NAME: 13,
    SemanticRole.GENDER: 14,
    SemanticRole.DATE_OF_BIRTH: 15,
    SemanticRole.NATIONALITY: 16,
    
    # 2. Contact Information (20-29)
    SemanticRole.EMAIL: 20,
    SemanticRole.PHONE: 21,
    SemanticRole.ALTERNATE_PHONE: 22,
    
    # 3. Address (30-39)
    SemanticRole.ADDRESS: 30,
    SemanticRole.ADDRESS_LINE_1: 31,
    SemanticRole.ADDRESS_LINE_2: 32,
    SemanticRole.CITY: 33,
    SemanticRole.STATE: 34,
    SemanticRole.POSTAL_CODE: 35,
    SemanticRole.COUNTRY: 36,
    
    # 4. Education (40-49)
    SemanticRole.EDUCATION_INSTITUTION: 40,
    SemanticRole.DEGREE: 41,
    SemanticRole.FIELD_OF_STUDY: 42,
    SemanticRole.GRADUATION_YEAR: 43,
    SemanticRole.GPA: 44,
    
    # 5. Experience (50-59)
    SemanticRole.CURRENT_COMPANY: 50,
    SemanticRole.JOB_TITLE: 51,
    SemanticRole.EXPERIENCE: 52,
    SemanticRole.NOTICE_PERIOD: 53,
    SemanticRole.CURRENT_CTC: 54,
    SemanticRole.EXPECTED_CTC: 55,
    
    # 6. Skills (60-69)
    SemanticRole.SKILLS: 60,
    
    # 7. Links & Web (70-79)
    SemanticRole.PORTFOLIO_URL: 70,
    SemanticRole.LINKEDIN_URL: 71,
    SemanticRole.GITHUB_URL: 72,
    SemanticRole.WEBSITE_URL: 73,
    
    # 8. Attachments (80-89)
    SemanticRole.RESUME_FILE: 80,
    SemanticRole.COVER_LETTER: 81,
    SemanticRole.PHOTO_FILE: 82,
    SemanticRole.CERTIFICATE_FILE: 83,
    
    # 9. Declarations & Checkboxes (90-99)
    SemanticRole.TERMS_AGREEMENT: 90,
    SemanticRole.NEWSLETTER: 91,
    SemanticRole.REMEMBER_ME: 92,
    
    # 10. Auth (100-109)
    SemanticRole.USERNAME: 100,
    SemanticRole.PASSWORD: 101,
    SemanticRole.CONFIRM_PASSWORD: 102,
    SemanticRole.OTP: 103,
}


class FormFillPlanner:
    """
    Generates a deterministic, safely ordered FormFillPlan.
    """

    def __init__(self):
        self.data_provider = form_data_provider
        self.safety_manager = form_safety_manager

    def create_plan(
        self,
        schema: FormSchema,
        user_intent: str = "Fill this form",
        custom_data: Optional[Dict[str, Any]] = None
    ) -> FormFillPlan:
        """
        Synthesizes FormSchema with available data into an actionable FormFillPlan.
        """
        intent_low = user_intent.lower()
        only_required = "only required" in intent_low or "required fields" in intent_low
        section_filter = self._parse_section_filter(intent_low)
        explicit_submit = "and submit" in intent_low or "submit" in intent_low.split() or "apply" in intent_low.split()

        # Build data bundle
        data_bundle = self.data_provider.get_data_bundle(
            explicit_command=user_intent,
            custom_overrides=custom_data
        )

        planned_fields: List[FieldFillPlan] = []
        missing_required: List[str] = []
        skipped_optional: List[str] = []
        ambiguous_fields: List[str] = []
        sensitive_fields: List[str] = []

        for field in schema.fields:
            # Skip buttons in field fill list (handled separately)
            if field.field_type == FormFieldType.UNKNOWN and field.semantic_role in (
                SemanticRole.SUBMIT_BUTTON, SemanticRole.NEXT_STEP_BUTTON, SemanticRole.PREV_STEP_BUTTON
            ):
                continue

            # Section filter check
            if section_filter and field.group != section_filter:
                continue

            # Only required filter check
            if only_required and not field.required:
                skipped_optional.append(field.label)
                continue

            # Check if sensitive
            is_sensitive = self.safety_manager.is_sensitive_field(field)
            if is_sensitive and field.semantic_role in (SemanticRole.PASSWORD, SemanticRole.CONFIRM_PASSWORD, SemanticRole.OTP):
                # Only fill password if explicitly supplied in custom_data
                val, src = self.data_provider.get_value_for_role(field.semantic_role, field.field_type, field.label, data_bundle)
                if not val and field.required:
                    sensitive_fields.append(field.label)
                    missing_required.append(f"{field.label} (Sensitive - Password required)")
                    continue

            # Retrieve value for field
            val, src = self.data_provider.get_value_for_role(
                role=field.semantic_role,
                field_type=field.field_type,
                field_label=field.label,
                data_bundle=data_bundle
            )

            # Determine action type
            action_type = self._determine_action_type(field, val)

            if val is not None:
                # Value is known / available
                plan_item = FieldFillPlan(
                    field_id=field.id,
                    semantic_role=field.semantic_role,
                    field_label=field.label,
                    field_type=field.field_type,
                    target_value=val,
                    action_type=action_type,
                    confidence=field.semantic_confidence or 0.95,
                    is_required=field.required,
                    is_sensitive=is_sensitive,
                    status="PLANNED",
                    reason=f"Matched via {src}",
                    source=src
                )
                planned_fields.append(plan_item)
            else:
                # Value is not available
                if field.required:
                    missing_required.append(field.label)
                    plan_item = FieldFillPlan(
                        field_id=field.id,
                        semantic_role=field.semantic_role,
                        field_label=field.label,
                        field_type=field.field_type,
                        target_value=None,
                        action_type="FORM_SKIP",
                        confidence=0.0,
                        is_required=True,
                        is_sensitive=is_sensitive,
                        status="MISSING",
                        reason="Required field has no available data in profile or command",
                        source="none"
                    )
                    planned_fields.append(plan_item)
                else:
                    skipped_optional.append(field.label)
                    plan_item = FieldFillPlan(
                        field_id=field.id,
                        semantic_role=field.semantic_role,
                        field_label=field.label,
                        field_type=field.field_type,
                        target_value=None,
                        action_type="FORM_SKIP",
                        confidence=0.0,
                        is_required=False,
                        is_sensitive=is_sensitive,
                        status="SKIPPED",
                        reason="Optional field with no data available",
                        source="none"
                    )
                    planned_fields.append(plan_item)

        # 4. Sort planned fields into intelligent execution order
        planned_fields.sort(key=lambda item: (
            ROLE_ORDER_PRIORITY.get(item.semantic_role, 50),
            0 if item.status == "PLANNED" else 1
        ))

        # 5. Evaluate submit permissions and confirmation
        can_submit, req_confirm, confirm_reason = self.safety_manager.evaluate_submit_safety(
            schema=schema,
            explicit_user_requested_submit=explicit_submit
        )

        # 6. Generate summary text
        summary_lines = [
            f"FORM FILL PLAN: '{schema.title}' ({schema.form_type.value})",
            f"Planned fields: {len([p for p in planned_fields if p.status == 'PLANNED'])}",
            f"Skipped optional: {len(skipped_optional)}",
            f"Missing required: {len(missing_required)} ({', '.join(missing_required) if missing_required else 'None'})",
            f"Submit planned: {can_submit and not missing_required}"
        ]

        return FormFillPlan(
            form_id=schema.form_id,
            form_type=schema.form_type,
            title=schema.title,
            fields=planned_fields,
            planned_count=len([p for p in planned_fields if p.status == "PLANNED"]),
            missing_required=missing_required,
            skipped_optional=skipped_optional,
            ambiguous_fields=ambiguous_fields,
            sensitive_fields=sensitive_fields,
            should_submit=can_submit and not missing_required,
            requires_confirmation=req_confirm,
            confirmation_reason=confirm_reason,
            summary="\n".join(summary_lines)
        )

    def _determine_action_type(self, field: FormField, target_value: Any) -> str:
        """Selects the concrete interaction verb for a field."""
        f_type = field.field_type

        if f_type == FormFieldType.CHECKBOX:
            if target_value is True or str(target_value).lower() in ("true", "yes", "1", "check"):
                return "FORM_CHECK"
            return "FORM_UNCHECK"

        if f_type == FormFieldType.RADIO:
            return "FORM_CHOOSE"

        if f_type in (FormFieldType.DROPDOWN, FormFieldType.COMBOBOX):
            return "FORM_SELECT"

        if f_type == FormFieldType.AUTOCOMPLETE:
            return "FORM_AUTOCOMPLETE"

        if f_type == FormFieldType.FILE_UPLOAD:
            return "FORM_UPLOAD"

        if field.value and str(field.value) != str(target_value):
            return "FORM_CLEAR_TYPE"

        return "FORM_TYPE"

    def _parse_section_filter(self, intent: str) -> Optional[str]:
        """Detects if user asked to fill a specific section only."""
        if "address section" in intent or "fill address" in intent:
            return "address"
        if "education section" in intent or "fill education" in intent:
            return "education"
        if "experience section" in intent or "fill experience" in intent:
            return "experience"
        if "personal details" in intent or "personal section" in intent:
            return "personal_info"
        return None


form_fill_planner = FormFillPlanner()
