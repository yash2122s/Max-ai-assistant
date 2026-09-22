"""
MAX AI — Form Safety & Sensitive Field Protection Manager
Enforces strict submit safety boundaries, sensitive field masking, confirmation gates,
and file upload security validations.
"""

import os
import re
import logging
from typing import Dict, Any, Optional, Tuple, List, Union

from core.form_understanding.models import (
    FormField, FormFieldType, SemanticRole, FormSchema, FormType
)
from core.confirmation_gate import confirmation_gate

logger = logging.getLogger("MAX.FormUnderstanding.Safety")

# Sensitive Semantic Roles
SENSITIVE_ROLES = frozenset({
    SemanticRole.PASSWORD,
    SemanticRole.CONFIRM_PASSWORD,
    SemanticRole.OTP,
})

# High-Risk Form Categories that MUST gate submission behind Confirmation Gate
HIGH_RISK_FORM_TYPES = frozenset({
    "payment",
    "checkout",
    "financial",
    "bank",
    "purchase",
    "wire_transfer",
    "account_deletion",
    "legal_contract"
})

ALLOWED_UPLOAD_EXTENSIONS = frozenset({
    ".pdf", ".docx", ".doc", ".txt", ".rtf", ".png", ".jpg", ".jpeg", ".zip", ".json", ".csv"
})
MAX_UPLOAD_SIZE_BYTES = 25 * 1024 * 1024  # 25 MB


class FormSafetyManager:
    """
    Guards form interaction safety, sensitive data masking, and submit policies.
    """

    @classmethod
    def is_sensitive_field(cls, field: FormField) -> bool:
        """Determines if a field contains sensitive authentication/payment data."""
        if field.is_sensitive:
            return True
        if field.semantic_role in SENSITIVE_ROLES:
            return True
        if field.field_type == FormFieldType.PASSWORD:
            return True
        
        lbl_low = f"{field.label} {field.placeholder} {field.nearby_text}".lower()
        if any(w in lbl_low for w in ("password", "passwd", "pin", "cvv", "otp", "ssn", "credit card", "card number")):
            return True

        return False

    @classmethod
    def mask_value(cls, val: Any, is_sensitive: bool = False) -> str:
        """Masks sensitive values for secure logging and diagnostics."""
        if val is None:
            return ""
        s = str(val)
        if not s:
            return ""
        if is_sensitive:
            return "********"
        # Partial masking for phone numbers
        if len(s) == 10 and s.isdigit():
            return f"{s[:2]}******{s[-2:]}"
        return s

    @classmethod
    def evaluate_submit_safety(
        cls,
        schema: FormSchema,
        explicit_user_requested_submit: bool = False
    ) -> Tuple[bool, bool, Optional[str]]:
        """
        Evaluates whether submit is allowed and whether confirmation is required.
        Returns: (can_submit, requires_confirmation, confirmation_reason)
        """
        # Rule 1: Default behavior is NEVER submit unless explicitly commanded
        if not explicit_user_requested_submit:
            return False, False, "Form filling completed. Submit stopped by default for user review."

        # Rule 2: Check for High-Risk forms (Payment, Financial, Account Deletion, Legal)
        win_low = f"{schema.title} {schema.active_window}".lower()
        is_high_risk = any(term in win_low for term in HIGH_RISK_FORM_TYPES)

        if is_high_risk:
            reason = f"High-risk form detected ({schema.title}). Confirmation required before final submission."
            logger.warning(f"🔒 {reason}")
            return True, True, reason

        # Rule 3: Registration or Login forms with sensitive credentials
        if schema.form_type in (FormType.LOGIN_FORM, FormType.REGISTRATION_FORM):
            return True, False, "Standard authentication submit authorized by user command."

        return True, False, "Submit authorized by explicit user instruction."

    @classmethod
    def validate_file_upload(cls, file_path: str, field: FormField) -> Tuple[bool, str]:
        """
        Validates that a file is safe and appropriate for form upload.
        """
        if not file_path or not isinstance(file_path, str):
            return False, "No file path provided for upload."

        clean_path = os.path.abspath(file_path.strip().strip('"').strip("'"))
        if not os.path.isfile(clean_path):
            return False, f"Upload target file does not exist on disk: '{clean_path}'"

        # Check extension
        _, ext = os.path.splitext(clean_path)
        if ext.lower() not in ALLOWED_UPLOAD_EXTENSIONS:
            return False, f"File extension '{ext}' is not permitted for safe upload."

        # Check size
        try:
            size = os.path.getsize(clean_path)
            if size > MAX_UPLOAD_SIZE_BYTES:
                return False, f"File size ({size // (1024*1024)}MB) exceeds maximum permitted upload limit (25MB)."
        except Exception as e:
            return False, f"Unable to verify file size: {e}"

        return True, "File upload validated."


form_safety_manager = FormSafetyManager()
