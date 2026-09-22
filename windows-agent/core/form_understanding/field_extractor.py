"""
MAX AI — Structural Form Field Extractor
Extracts and normalizes interactive FormField models from Windows UIA trees,
OCR tokens, and DOM accessibility hierarchies with visual proximity analysis.
"""

import re
import logging
from typing import List, Dict, Any, Optional, Tuple

from core.computer_use.models import UIElement, UIMap, Rect, Point, ElementSource
from core.form_understanding.models import (
    FormField, FormFieldType, SemanticRole
)
from core.form_understanding.semantic_mapper import semantic_mapper

logger = logging.getLogger("MAX.FormUnderstanding.FieldExtractor")


class FieldExtractor:
    """
    Extracts, normalizes, and groups FormField instances from raw screen perception elements.
    """

    def __init__(self):
        self.mapper = semantic_mapper

    def extract_fields(
        self,
        ui_map: UIMap,
        dom_elements: Optional[List[Dict[str, Any]]] = None
    ) -> List[FormField]:
        """
        Extracts all form fields from a UIMap snapshot.
        Fuses UIA, OCR, and DOM data.
        """
        fields: List[FormField] = []
        raw_elements = ui_map.elements or []

        # 1. Separate interactive controls from static label/text elements
        interactive_elements: List[UIElement] = []
        text_labels: List[UIElement] = []

        for el in raw_elements:
            role_low = el.role.lower()
            ctrl_type = el.control_type.lower()

            if role_low in ("edit", "combobox", "checkbox", "radiobutton", "slider", "list", "document", "custom") or \
               ctrl_type in ("edit", "combobox", "checkbox", "radiobutton", "slider", "list"):
                interactive_elements.append(el)
            elif role_low == "button" or ctrl_type == "button":
                # Include buttons as form controls (e.g. submit, upload, next)
                interactive_elements.append(el)
            elif role_low in ("text", "static", "label") or "ocr" in str(getattr(el, "source", "")).lower():
                text_labels.append(el)
            elif el.interactive:
                interactive_elements.append(el)

        # 2. Extract DOM fields if available (highest precision for web)
        if dom_elements:
            dom_fields = self._extract_from_dom(dom_elements)
            if dom_fields:
                return dom_fields

        # 3. Process each interactive element into a FormField
        field_counter = 1
        for el in interactive_elements:
            # Determine label by checking element name, placeholder, and nearby text
            label, nearby_text, is_req = self._resolve_label_and_proximity(el, text_labels)
            
            # Infer field type
            f_type = self._infer_field_type(el, label)
            
            # Determine initial semantic mapping
            role, confidence, expl = self.mapper.map_field(
                label=label,
                placeholder=el.extra_attributes.get("placeholder", ""),
                nearby_text=nearby_text,
                field_type=f_type,
                control_type=el.control_type
            )

            # Check if required
            required = is_req or bool(el.extra_attributes.get("required")) or \
                       "required" in label.lower() or "required" in nearby_text.lower() or \
                       "*" in (el.name or "") or "*" in nearby_text

            # Check if sensitive
            is_sensitive = (
                role in (SemanticRole.PASSWORD, SemanticRole.CONFIRM_PASSWORD, SemanticRole.OTP) or
                f_type == FormFieldType.PASSWORD or
                bool(el.extra_attributes.get("is_password")) or
                any(w in f"{label} {el.automation_id}".lower() for w in ("password", "passwd", "pin", "cvv", "otp", "secret"))
            )

            # Extract current value and state
            val = el.text if el.role.lower() == "edit" else (el.name if el.role.lower() != "button" else "")
            if val == label:
                val = ""  # If value is just repeating the label

            # Options for ComboBox / Radio
            options = el.extra_attributes.get("options", [])

            form_field = FormField(
                id=f"f{field_counter}",
                label=label or el.name or f"Field_{field_counter}",
                nearby_text=nearby_text,
                field_type=f_type,
                control_type=el.control_type or el.role,
                value=val,
                placeholder=el.extra_attributes.get("placeholder", ""),
                required=required,
                enabled=el.enabled,
                visible=el.visible,
                editable=el.role.lower() in ("edit", "document", "combobox", "custom"),
                options=options,
                selected_option=el.extra_attributes.get("selected_option"),
                is_checked=el.extra_attributes.get("is_checked"),
                bounds=el.bounds,
                center=el.center,
                source=el.source,
                confidence=el.confidence,
                semantic_role=role,
                semantic_confidence=confidence,
                group=self._infer_group(label, role),
                order=field_counter,
                is_sensitive=is_sensitive,
                ref=el.ref,
                native_handle=el.native_handle,
                extra_attributes=el.extra_attributes
            )
            fields.append(form_field)
            field_counter += 1

        # 4. Sort fields geometrically: top to bottom, left to right
        fields.sort(key=lambda f: (f.bounds.top if f.bounds else 0, f.bounds.left if f.bounds else 0))
        for idx, f in enumerate(fields, 1):
            f.order = idx

        return fields

    def _infer_field_type(self, el: UIElement, label: str) -> FormFieldType:
        """Determines the specific FormFieldType from control role and label cues."""
        role_low = el.role.lower()
        ctrl_low = (el.control_type or "").lower()
        lbl_low = (label or "").lower()
        auto_id_low = (el.automation_id or "").lower()

        # Checkbox
        if role_low == "checkbox" or ctrl_low == "checkbox":
            return FormFieldType.CHECKBOX

        # Radio
        if role_low == "radiobutton" or ctrl_low == "radiobutton":
            return FormFieldType.RADIO

        # Dropdown / ComboBox
        if role_low in ("combobox", "dropdown") or ctrl_low in ("combobox", "dropdown"):
            if any(w in lbl_low for w in ("search", "find", "auto")):
                return FormFieldType.AUTOCOMPLETE
            return FormFieldType.DROPDOWN

        # Sliders
        if role_low == "slider" or ctrl_low == "slider":
            return FormFieldType.SLIDER

        # Textarea / Multiline Document
        if role_low == "document" or "textarea" in auto_id_low or (el.bounds and el.bounds.height > 80 and role_low == "edit"):
            return FormFieldType.TEXTAREA

        # Buttons (File upload, submit, action)
        if role_low == "button" or ctrl_low == "button":
            if any(w in lbl_low for w in ("browse", "choose file", "upload", "attach")):
                return FormFieldType.FILE_UPLOAD
            return FormFieldType.UNKNOWN

        # Edit controls
        if role_low == "edit" or ctrl_low == "edit":
            if any(w in lbl_low or w in auto_id_low for w in ("password", "passwd", "pin", "cvv", "otp")):
                return FormFieldType.PASSWORD
            if any(w in lbl_low or w in auto_id_low for w in ("email", "e-mail", "mail")):
                return FormFieldType.EMAIL
            if any(w in lbl_low or w in auto_id_low for w in ("phone", "mobile", "cell", "tel")):
                return FormFieldType.PHONE
            if any(w in lbl_low or w in auto_id_low for w in ("dob", "date of birth", "birth date", "expiry", "date")):
                return FormFieldType.DATE
            if any(w in lbl_low or w in auto_id_low for w in ("age", "year", "gpa", "salary", "experience", "pincode", "zip")):
                return FormFieldType.NUMBER
            if any(w in lbl_low or w in auto_id_low for w in ("url", "website", "linkedin", "github", "portfolio")):
                return FormFieldType.URL
            if any(w in lbl_low or w in auto_id_low for w in ("search", "find")):
                return FormFieldType.SEARCH
            if any(w in lbl_low or w in auto_id_low for w in ("city", "country", "state", "university", "college", "company", "skill")):
                return FormFieldType.AUTOCOMPLETE
            return FormFieldType.TEXT

        return FormFieldType.TEXT

    def _resolve_label_and_proximity(
        self,
        target_el: UIElement,
        label_elements: List[UIElement]
    ) -> Tuple[str, str, bool]:
        """
        Finds the most probable label for a control using explicit names and visual proximity.
        Returns: (label_text, nearby_helper_text, is_required_asterisk_detected)
        """
        explicit_name = (target_el.name or "").strip()
        is_required = "*" in explicit_name

        # If the element already has a descriptive, clean name, use it
        if explicit_name and not explicit_name.lower().startswith("edit") and not explicit_name.lower().startswith("textbox"):
            clean_lbl = re.sub(r"[\*\:]", "", explicit_name).strip()
            return clean_lbl, explicit_name, is_required

        # Visual proximity search: look for labels ABOVE or to the LEFT of the control
        target_bounds = target_el.bounds
        if not target_bounds:
            return explicit_name or "Input", "", is_required

        best_label = ""
        best_dist = 999999.0
        nearby_chunks = []

        for lbl in label_elements:
            l_bounds = lbl.bounds
            if not l_bounds or not lbl.name.strip():
                continue

            lbl_text = lbl.name.strip()

            # 1. Check if label is to the LEFT of the input (same horizontal band)
            is_to_left = (
                l_bounds.right <= target_bounds.left + 20 and
                abs(l_bounds.center.y - target_bounds.center.y) <= 25 and
                (target_bounds.left - l_bounds.right) <= 250
            )

            # 2. Check if label is ABOVE the input (same vertical column)
            is_above = (
                l_bounds.bottom <= target_bounds.top + 15 and
                abs(l_bounds.center.x - target_bounds.center.x) <= 150 and
                (target_bounds.top - l_bounds.bottom) <= 60
            )

            if is_to_left or is_above:
                # Proximity distance
                dist = (target_bounds.left - l_bounds.right) if is_to_left else (target_bounds.top - l_bounds.bottom)
                if dist < best_dist:
                    best_dist = dist
                    best_label = lbl_text
                    is_required = ("*" in lbl_text) or ("required" in lbl_text.lower())

            # Collect nearby helper text within 100px
            if abs(l_bounds.center.y - target_bounds.center.y) <= 80 and abs(l_bounds.center.x - target_bounds.center.x) <= 200:
                nearby_chunks.append(lbl_text)

        final_label = best_label or explicit_name or target_el.automation_id or "Field"
        clean_final = re.sub(r"[\*\:]", "", final_label).strip()
        nearby_summary = " | ".join(nearby_chunks[:3])

        return clean_final, nearby_summary, is_required

    def _infer_group(self, label: str, role: SemanticRole) -> str:
        """Assigns a semantic group category."""
        r_val = role.value if isinstance(role, SemanticRole) else str(role)
        if any(w in r_val for w in ("NAME", "GENDER", "DATE_OF_BIRTH", "NATIONALITY")):
            return "personal_info"
        if any(w in r_val for w in ("EMAIL", "PHONE")):
            return "contact_info"
        if any(w in r_val for w in ("ADDRESS", "CITY", "STATE", "POSTAL_CODE", "COUNTRY")):
            return "address"
        if any(w in r_val for w in ("EDUCATION", "DEGREE", "GPA", "GRADUATION")):
            return "education"
        if any(w in r_val for w in ("SKILLS", "EXPERIENCE", "COMPANY", "JOB_TITLE")):
            return "experience"
        if any(w in r_val for w in ("RESUME", "COVER_LETTER", "PHOTO", "CERTIFICATE")):
            return "attachments"
        if any(w in r_val for w in ("PASSWORD", "OTP", "USERNAME")):
            return "authentication"
        return "general"

    def _extract_from_dom(self, dom_elements: List[Dict[str, Any]]) -> List[FormField]:
        """Extracts fields from structured Playwright DOM introspection."""
        fields = []
        for idx, item in enumerate(dom_elements, 1):
            label = item.get("label") or item.get("placeholder") or item.get("name") or f"Field_{idx}"
            tag = item.get("tag", "input").lower()
            i_type = item.get("type", "text").lower()

            f_type = FormFieldType.TEXT
            if tag == "textarea":
                f_type = FormFieldType.TEXTAREA
            elif tag == "select":
                f_type = FormFieldType.DROPDOWN
            elif i_type == "checkbox":
                f_type = FormFieldType.CHECKBOX
            elif i_type == "radio":
                f_type = FormFieldType.RADIO
            elif i_type == "password":
                f_type = FormFieldType.PASSWORD
            elif i_type == "email":
                f_type = FormFieldType.EMAIL
            elif i_type in ("tel", "phone"):
                f_type = FormFieldType.PHONE
            elif i_type == "file":
                f_type = FormFieldType.FILE_UPLOAD
            elif i_type in ("number", "range"):
                f_type = FormFieldType.NUMBER

            role, conf, _ = self.mapper.map_field(
                label=label,
                placeholder=item.get("placeholder", ""),
                field_type=f_type,
                control_type=tag
            )

            b_dict = item.get("bounds", {})
            bounds = Rect(
                left=b_dict.get("left", 0),
                top=b_dict.get("top", 0),
                right=b_dict.get("right", 0),
                bottom=b_dict.get("bottom", 0)
            )

            ff = FormField(
                id=f"dom_f{idx}",
                label=label,
                nearby_text=item.get("nearby_text", ""),
                field_type=f_type,
                control_type=f"{tag}:{i_type}",
                value=item.get("value", ""),
                placeholder=item.get("placeholder", ""),
                required=bool(item.get("required") or item.get("aria-required")),
                enabled=not bool(item.get("disabled")),
                visible=bool(item.get("visible", True)),
                options=item.get("options", []),
                selected_option=item.get("selected_option"),
                is_checked=item.get("checked"),
                bounds=bounds,
                center=bounds.center,
                source=ElementSource.FUSED,
                confidence=0.99,
                semantic_role=role,
                semantic_confidence=conf,
                group=self._infer_group(label, role),
                order=idx,
                is_sensitive=(f_type == FormFieldType.PASSWORD or role == SemanticRole.PASSWORD),
                selector=item.get("selector")
            )
            fields.append(ff)
        return fields


field_extractor = FieldExtractor()
