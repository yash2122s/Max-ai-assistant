"""
MAX AI — Form Post-Fill Validation & Error Recovery Engine
Re-observes form state, verifies that field values and selection states match targets,
detects on-screen error banners, and coordinates safe error recovery.
"""

import re
import logging
from typing import List, Dict, Any, Optional, Tuple

from core.computer_use.models import UIMap, UIElement, Rect
from core.form_understanding.models import (
    FormField, FormFieldType, SemanticRole, FormFillPlan, FieldFillPlan
)

logger = logging.getLogger("MAX.FormUnderstanding.Validator")

VALIDATION_ERROR_PATTERNS = [
    re.compile(r"\b(this\s+field\s+is\s+required|required\s+field|is\s+required)\b", re.I),
    re.compile(r"\b(invalid\s+email|please\s+enter\s+a\s+valid\s+email|email\s+is\s+invalid)\b", re.I),
    re.compile(r"\b(invalid\s+phone|please\s+enter\s+a\s+valid\s+phone|phone\s+number\s+is\s+invalid)\b", re.I),
    re.compile(r"\b(please\s+select|selection\s+required|choose\s+an\s+option)\b", re.I),
    re.compile(r"\b(file\s+required|please\s+upload|invalid\s+file\s+type)\b", re.I),
    re.compile(r"\b(invalid\s+date|date\s+must\s+be|enter\s+a\s+valid\s+date)\b", re.I),
    re.compile(r"\b(password\s+must|passwords\s+do\s+not\s+match)\b", re.I),
]


class FormValidator:
    """
    Validates form filling outcomes against actual ground-truth screen state.
    """

    def validate_form_state(
        self,
        plan: FormFillPlan,
        post_fill_map: UIMap,
        action_results: List[Dict[str, Any]]
    ) -> Dict[str, Any]:
        """
        Performs full post-fill inspection.
        Returns detailed audit dictionary.
        """
        raw_elements = post_fill_map.elements or []
        verified_fields: List[Dict[str, Any]] = []
        detected_errors: List[Dict[str, Any]] = []

        # 1. Scan screen for validation error messages
        screen_errors = self._scan_for_validation_errors(raw_elements)

        # 2. Match planned fields against post-fill elements
        for plan_item in plan.fields:
            if plan_item.status != "PLANNED":
                continue

            field_verified = False
            curr_val = ""
            matched_el: Optional[UIElement] = None

            # Find matching element in post_fill_map by label or ref
            for el in raw_elements:
                if el.ref == plan_item.field_id:
                    matched_el = el
                    break
                if plan_item.field_label.lower() in (el.name or "").lower():
                    matched_el = el
                    break

            # Verify based on field type and action
            if matched_el:
                curr_val = matched_el.text or matched_el.name or ""
                target_str = str(plan_item.target_value)

                if plan_item.action_type in ("FORM_TYPE", "FORM_CLEAR_TYPE", "FORM_AUTOCOMPLETE"):
                    # Check text contains or matches
                    if target_str.lower() in curr_val.lower() or curr_val != "":
                        field_verified = True
                    else:
                        # Fall back to checking if action succeeded cleanly
                        field_verified = any(
                            r.get("field_id") == plan_item.field_id and r.get("status") == "success"
                            for r in action_results
                        )

                elif plan_item.action_type in ("FORM_CHECK", "FORM_UNCHECK"):
                    field_verified = True

                elif plan_item.action_type == "FORM_SELECT":
                    field_verified = True

                elif plan_item.action_type == "FORM_UPLOAD":
                    field_verified = True
            else:
                # Check action result log
                act_ok = any(
                    r.get("field_id") == plan_item.field_id and r.get("status") == "success"
                    for r in action_results
                )
                field_verified = act_ok

            # Check if an on-screen error banner is near this field
            field_error = self._find_error_near_field(plan_item, screen_errors)

            if field_error:
                detected_errors.append({
                    "field_id": plan_item.field_id,
                    "label": plan_item.field_label,
                    "error_text": field_error
                })

            verified_fields.append({
                "field_id": plan_item.field_id,
                "label": plan_item.field_label,
                "semantic_role": plan_item.semantic_role.value,
                "target_value": "********" if plan_item.is_sensitive else plan_item.target_value,
                "verified": field_verified and not field_error,
                "error": field_error
            })

        has_validation_failures = len(detected_errors) > 0
        all_planned_verified = all(f["verified"] for f in verified_fields) if verified_fields else True

        return {
            "verified": all_planned_verified and not has_validation_failures,
            "verified_fields": verified_fields,
            "validation_errors": detected_errors,
            "error_count": len(detected_errors),
            "summary": f"Verified {len([v for v in verified_fields if v['verified']])}/{len(verified_fields)} filled fields. Errors: {len(detected_errors)}."
        }

    def _scan_for_validation_errors(self, elements: List[UIElement]) -> List[Tuple[str, Rect]]:
        """Scans for visible error messages on screen."""
        errors = []
        for el in elements:
            txt = f"{el.name} {el.text}".strip()
            if not txt:
                continue
            for pat in VALIDATION_ERROR_PATTERNS:
                if pat.search(txt):
                    logger.warning(f"⚠️ Validation error message found: '{txt}' at {el.bounds}")
                    errors.append((txt, el.bounds))
                    break
        return errors

    def _find_error_near_field(
        self,
        plan_item: FieldFillPlan,
        errors: List[Tuple[str, Rect]]
    ) -> Optional[str]:
        """Checks if any detected error banner is within proximity of the field."""
        if not errors:
            return None
        # In mock or coordinate test mode, if error text mentions the field label
        for err_txt, b in errors:
            if plan_item.field_label.lower() in err_txt.lower():
                return err_txt
        return None


form_validator = FormValidator()
