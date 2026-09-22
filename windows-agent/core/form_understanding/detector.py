"""
MAX AI — Form Detector & Structure Classifier
Analyzes desktop/browser UI snapshots, detects forms, classifies form categories,
identifies step progression, and spots CAPTCHAs.
"""

import re
import logging
from typing import List, Dict, Any, Optional, Tuple

from core.computer_use.models import UIMap
from core.form_understanding.models import (
    FormSchema, FormType, FormField, FormFieldType, SemanticRole
)
from core.form_understanding.field_extractor import field_extractor

logger = logging.getLogger("MAX.FormUnderstanding.Detector")

BROWSER_PROCESSES = frozenset({
    "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "vivaldi.exe"
})

CAPTCHA_PATTERNS = [
    re.compile(r"\b(captcha|recaptcha|hcaptcha|turnstile|cloudflare)\b", re.I),
    re.compile(r"\b(verify\s+you\s+are\s+(a\s+)?human|i'?m\s+not\s+a\s+robot|security\s+check)\b", re.I),
    re.compile(r"\b(bot\s+detection|press\s+and\s+hold\s+to\s+verify)\b", re.I),
]


class FormDetector:
    """
    Detects if the active window contains a form and constructs a structured FormSchema.
    """

    def __init__(self):
        self.extractor = field_extractor

    def detect_form(
        self,
        ui_map: UIMap,
        dom_elements: Optional[List[Dict[str, Any]]] = None
    ) -> Optional[FormSchema]:
        """
        Inspects screen UI hierarchy and extracts a FormSchema if a form is present.
        Returns None if no form is detected.
        """
        raw_elements = ui_map.elements or []
        active_window = ui_map.active_window or "Desktop"
        active_process = ui_map.active_process or "unknown"
        active_hwnd = ui_map.active_hwnd or 0

        # 1. Check for CAPTCHA
        has_captcha, captcha_msg = self._detect_captcha(raw_elements)

        # 2. Extract normalized FormFields
        fields = self.extractor.extract_fields(ui_map, dom_elements)

        # Filter out navigation/menu items that aren't form-related
        form_fields = [f for f in fields if f.field_type != FormFieldType.UNKNOWN or f.semantic_role != SemanticRole.UNKNOWN]

        # Minimum criteria: at least 1 input control or search bar
        if not form_fields and not has_captcha:
            return None

        # 3. Identify Submit and Navigation buttons
        submit_btn = None
        next_btn = None
        prev_btn = None

        for f in fields:
            if f.semantic_role == SemanticRole.SUBMIT_BUTTON:
                submit_btn = f
            elif f.semantic_role == SemanticRole.NEXT_STEP_BUTTON:
                next_btn = f
            elif f.semantic_role == SemanticRole.PREV_STEP_BUTTON:
                prev_btn = f

        # Also search among raw buttons for submit-like actions
        if not submit_btn:
            for el in raw_elements:
                if el.role.lower() == "button":
                    btn_name = (el.name or "").lower().strip()
                    if any(w in btn_name for w in ("submit", "apply", "register", "sign up", "log in", "login", "save", "confirm", "send")):
                        submit_btn = FormField(
                            id=f"btn_submit",
                            label=el.name,
                            field_type=FormFieldType.UNKNOWN,
                            control_type="Button",
                            bounds=el.bounds,
                            center=el.center,
                            source=el.source,
                            semantic_role=SemanticRole.SUBMIT_BUTTON,
                            ref=el.ref
                        )
                        break

        # 4. Check for Multi-Step progression
        is_multi_step, current_step, total_steps = self._detect_multi_step(raw_elements, next_btn, prev_btn)

        # 5. Classify Form Type
        form_type = self._classify_form_type(
            fields=form_fields,
            active_window=active_window,
            active_process=active_process,
            is_multi_step=is_multi_step
        )

        # 6. Disclose Form Title
        title = self._detect_form_title(raw_elements, active_window, form_type)

        schema = FormSchema(
            form_id=f"form_{int(ui_map.timestamp)}",
            title=title,
            form_type=form_type,
            fields=form_fields,
            active_window=active_window,
            active_process=active_process,
            active_hwnd=active_hwnd,
            is_multi_step=is_multi_step,
            current_step=current_step,
            total_steps=total_steps,
            submit_button=submit_btn,
            next_button=next_btn,
            prev_button=prev_btn,
            has_captcha=has_captcha,
            captcha_message=captcha_msg,
            confidence=0.95 if form_fields else 0.80
        )

        logger.info(f"📋 FormDetector: Detected {form_type.value} '{title}' with {len(form_fields)} fields.")
        return schema

    def _detect_captcha(self, raw_elements: List[Any]) -> Tuple[bool, Optional[str]]:
        """Scans for CAPTCHA security challenges."""
        for el in raw_elements:
            txt = f"{getattr(el, 'name', '')} {getattr(el, 'text', '')} {getattr(el, 'automation_id', '')}"
            for pat in CAPTCHA_PATTERNS:
                if pat.search(txt):
                    logger.warning(f"🔒 CAPTCHA detected on screen: {txt[:60]}")
                    return True, "CAPTCHA challenge detected on screen. Manual completion required."
        return False, None

    def _detect_multi_step(
        self,
        raw_elements: List[Any],
        next_btn: Optional[FormField],
        prev_btn: Optional[FormField]
    ) -> Tuple[bool, int, Optional[int]]:
        """Detects multi-step wizard / form indicator."""
        for el in raw_elements:
            txt = f"{getattr(el, 'name', '')} {getattr(el, 'text', '')}".lower()
            match = re.search(r"step\s+([0-9]+)\s*(?:of|\/)\s*([0-9]+)", txt)
            if match:
                curr = int(match.group(1))
                tot = int(match.group(2))
                return True, curr, tot

            match_step = re.search(r"\bstep\s+([0-9]+)\b", txt)
            if match_step and (next_btn or prev_btn):
                return True, int(match_step.group(1)), None

        if next_btn or prev_btn:
            return True, 1, None

        return False, 1, None

    def _classify_form_type(
        self,
        fields: List[FormField],
        active_window: str,
        active_process: str,
        is_multi_step: bool
    ) -> FormType:
        """Determines the specific category of form."""
        roles = {f.semantic_role for f in fields}
        types = {f.field_type for f in fields}
        win_low = active_window.lower()
        proc_low = active_process.lower()
        is_browser = proc_low in BROWSER_PROCESSES

        if is_multi_step:
            return FormType.MULTI_STEP_FORM

        # Job Application Form
        if SemanticRole.RESUME_FILE in roles or SemanticRole.SKILLS in roles or \
           SemanticRole.EXPERIENCE in roles or any(w in win_low for w in ("job", "career", "apply", "applicant", "workday", "greenhouse", "lever")):
            return FormType.JOB_APPLICATION_FORM

        # Login Form
        if (SemanticRole.PASSWORD in roles or SemanticRole.OTP in roles) and \
           SemanticRole.CONFIRM_PASSWORD not in roles and \
           len(fields) <= 4:
            return FormType.LOGIN_FORM

        # Registration / Sign-Up Form
        if (SemanticRole.CONFIRM_PASSWORD in roles or SemanticRole.PASSWORD in roles) and \
           (SemanticRole.EMAIL in roles or SemanticRole.USERNAME in roles) and \
           len(fields) >= 3:
            return FormType.REGISTRATION_FORM

        # Address Form
        if (SemanticRole.POSTAL_CODE in roles or SemanticRole.CITY in roles or SemanticRole.STATE in roles) and \
           (SemanticRole.ADDRESS in roles or SemanticRole.ADDRESS_LINE_1 in roles):
            return FormType.ADDRESS_FORM

        # Contact Form
        if SemanticRole.EMAIL in roles and (SemanticRole.PHONE in roles or FormFieldType.TEXTAREA in types) and \
           len(fields) <= 6:
            return FormType.CONTACT_FORM

        # Profile Form
        if SemanticRole.FULL_NAME in roles or (SemanticRole.FIRST_NAME in roles and SemanticRole.LAST_NAME in roles):
            if any(w in win_low for w in ("profile", "account", "user info", "edit profile")):
                return FormType.PROFILE_FORM

        # Search Form
        if len(fields) == 1 and (SemanticRole.SEARCH_QUERY in roles or fields[0].field_type == FormFieldType.SEARCH):
            return FormType.SEARCH_FORM

        # Settings Form
        if any(w in win_low for w in ("settings", "preferences", "options", "configuration")) or \
           (FormFieldType.CHECKBOX in types and FormFieldType.TOGGLE in types):
            return FormType.SETTINGS_FORM

        # Dialog Form (Desktop modals)
        if any(w in win_low for w in ("dialog", "properties", "options", "connect", "prompt", "setup")):
            return FormType.DIALOG_FORM

        # Default web vs desktop
        if is_browser:
            return FormType.WEB_FORM
        return FormType.DESKTOP_FORM

    def _detect_form_title(
        self,
        raw_elements: List[Any],
        active_window: str,
        form_type: FormType
    ) -> str:
        """Finds the most descriptive title for the form."""
        # Top header text
        candidates = []
        for el in raw_elements:
            b = getattr(el, "bounds", None)
            name = (getattr(el, "name", "") or "").strip()
            if b and b.top < 150 and len(name) > 3 and getattr(el, "role", "").lower() in ("text", "static", "header", "title"):
                candidates.append((name, b.top))

        if candidates:
            candidates.sort(key=lambda c: c[1])
            return candidates[0][0]

        # Use clean active window title
        clean_win = re.sub(r" - (Google Chrome|Microsoft Edge|Mozilla Firefox|Brave)$", "", active_window).strip()
        if clean_win:
            return clean_win

        return form_type.value.replace("_", " ").title()


form_detector = FormDetector()
