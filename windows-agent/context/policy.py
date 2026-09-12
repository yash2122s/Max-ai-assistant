"""
MAX AI — Context Policy
Enforces validation, secret masking, payload size limits, and passive-data constraints
for runtime context records.
"""

import json
import logging
from typing import Dict, Any, Tuple, Optional, Set

from core.security import security_guard
from .models import ContextRecord, ContextCategory, ContextOrigin

logger = logging.getLogger("MAXWindowsAgent.ContextPolicy")

# Maximum permitted payload size (in bytes) to prevent memory bloating
MAX_PAYLOAD_SIZE_BYTES = 16384  # 16 KB

# Categories allowed to be injected into LLM context
ALLOWED_LLM_CATEGORIES: Set[ContextCategory] = {
    ContextCategory.ACTIVE_WINDOW,
    ContextCategory.ACTIVE_APP,
    ContextCategory.FOCUSED_UI,
    ContextCategory.DEVICE_STATE,
    ContextCategory.ACTIVE_TASK,
    ContextCategory.NOTIFICATIONS,
}


class ContextPolicy:
    """
    Central policy validator for ContextRecord creation, sanitization, and admission.
    """

    @staticmethod
    def validate_and_sanitize(record: ContextRecord) -> Tuple[bool, Optional[ContextRecord], Optional[str]]:
        """
        Validates record schema, applies secret masking to strings in payload,
        and enforces size limits.
        
        Returns:
            (is_valid, sanitized_record, error_message)
        """
        if not isinstance(record, ContextRecord):
            return False, None, "Invalid object type: expected ContextRecord"

        # 1. Validate Category
        if not isinstance(record.category, ContextCategory):
            try:
                record.category = ContextCategory(record.category)
            except Exception:
                return False, None, f"Invalid ContextCategory: '{record.category}'"

        # 2. Validate Origin
        if not isinstance(record.origin, ContextOrigin):
            try:
                record.origin = ContextOrigin(record.origin)
            except Exception:
                return False, None, f"Invalid ContextOrigin: '{record.origin}'"

        # 3. Validate Payload structure
        if not isinstance(record.payload, dict):
            return False, None, "ContextRecord payload must be a dictionary"

        # 4. Check Payload Size
        try:
            payload_json = json.dumps(record.payload, ensure_ascii=False)
            if len(payload_json.encode("utf-8")) > MAX_PAYLOAD_SIZE_BYTES:
                return False, None, f"Payload size exceeds limit ({len(payload_json)} > {MAX_PAYLOAD_SIZE_BYTES} bytes)"
        except (TypeError, OverflowError) as e:
            return False, None, f"Payload is not JSON-serializable: {e}"

        # 5. Sanitize Strings & Mask Secrets recursively
        sanitized_payload = ContextPolicy._sanitize_dict(record.payload)

        sanitized_record = ContextRecord(
            record_id=record.record_id,
            category=record.category,
            origin=record.origin,
            payload=sanitized_payload,
            updated_at=record.updated_at,
            ttl_seconds=record.ttl_seconds,
            schema_version=record.schema_version
        )

        return True, sanitized_record, None

    @staticmethod
    def _sanitize_dict(d: Dict[str, Any]) -> Dict[str, Any]:
        """Recursively masks secrets in string values of a dictionary."""
        sanitized = {}
        for k, v in d.items():
            if isinstance(v, str):
                # Mask secrets and defuse closing context tags
                masked = security_guard.mask_secrets(v)
                masked = masked.replace("</PASSIVE_CONTEXT>", "[TAG_FILTERED]")
                sanitized[k] = masked
            elif isinstance(v, dict):
                sanitized[k] = ContextPolicy._sanitize_dict(v)
            elif isinstance(v, list):
                sanitized[k] = [
                    (security_guard.mask_secrets(item).replace("</PASSIVE_CONTEXT>", "[TAG_FILTERED]") if isinstance(item, str)
                     else (ContextPolicy._sanitize_dict(item) if isinstance(item, dict) else item))
                    for item in v
                ]
            else:
                sanitized[k] = v
        return sanitized

    @staticmethod
    def is_allowed_for_llm(category: ContextCategory) -> bool:
        """Returns True if the category is permitted in LLM prompt context."""
        return category in ALLOWED_LLM_CATEGORIES


context_policy = ContextPolicy()
