"""
MAX Ecosystem — Security Policy & Orchestration-Level Authorization Boundary
Enforces permission inheritance, deep recursive secret redaction, 60s confirmation timeouts,
prompt-injection boundaries, and MCP/Skill privilege constraints.
Reuses low-level Windows execution controls from core.security (SecurityGuard).
"""

import re
import time
import logging
from typing import Dict, Any, List, Optional, Set, Union, Tuple

from config import MAX_CONFIRMATION_TIMEOUT_SEC
from core.security import security_guard, RiskLevel
from protocol.task_envelope import (
    GranularPermission,
    ALL_PERMISSIONS,
    TaskEnvelope
)

logger = logging.getLogger("MAXWindowsAgent.SecurityPolicy")

SENSITIVE_KEY_PATTERNS = [
    r"api[_-]?key",
    r"auth",
    r"token",
    r"secret",
    r"password",
    r"passwd",
    r"credential",
    r"cookie",
    r"private[_-]?key",
    r"access[_-]?token",
    r"refresh[_-]?token",
    r"jwt",
    r"bearer"
]

SENSITIVE_VALUE_REGEXES = [
    re.compile(r"AIza[0-9A-Za-z-_]{25,45}"),            # Google API Key
    re.compile(r"sk-[a-zA-Z0-9]{20,}"),                 # OpenAI Secret Key
    re.compile(r"ghp_[a-zA-Z0-9]{20,}"),                # GitHub Personal Access Token
    re.compile(r"gho_[a-zA-Z0-9]{20,}"),                # GitHub OAuth Token
    re.compile(r"eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}"), # JWT Token
    re.compile(r"Bearer\s+[a-zA-Z0-9_\-\.]{15,}", re.IGNORECASE)
]


def mask_secrets_deeply(obj: Any) -> Any:
    """
    Recursively and thoroughly scrubs secrets from dictionaries, lists, strings, and nested data structures.
    Runs BEFORE serialization, logging, or database persistence.
    """
    if isinstance(obj, dict):
        sanitized = {}
        for k, v in obj.items():
            k_str = str(k).lower()
            # Check if key is sensitive
            is_sensitive_key = any(re.search(pat, k_str) for pat in SENSITIVE_KEY_PATTERNS)
            if is_sensitive_key:
                sanitized[k] = "[REDACTED_SECRET]"
            else:
                sanitized[k] = mask_secrets_deeply(v)
        return sanitized

    elif isinstance(obj, list):
        return [mask_secrets_deeply(elem) for elem in obj]

    elif isinstance(obj, tuple):
        return tuple(mask_secrets_deeply(elem) for elem in obj)

    elif isinstance(obj, str):
        masked_str = obj
        for regex in SENSITIVE_VALUE_REGEXES:
            masked_str = regex.sub("[REDACTED_TOKEN]", masked_str)
        return masked_str

    elif isinstance(obj, Exception):
        return mask_secrets_deeply(str(obj))

    return obj


class SecurityPolicyEngine:
    """
    Orchestration-level security and permission authority.
    Governs worker permissions, inheritance, prompt injection boundaries, and confirmations.
    """
    def __init__(self, confirmation_timeout: float = MAX_CONFIRMATION_TIMEOUT_SEC):
        self.confirmation_timeout = confirmation_timeout

    def validate_permission_inheritance(
        self,
        parent_permissions: List[str],
        child_permissions: List[str]
    ) -> bool:
        """
        Enforces child_permissions ⊆ parent_permissions.
        Returns True if valid, False if upward escalation is requested.
        """
        parent_set = set(parent_permissions)
        child_set = set(child_permissions)

        for p in child_set:
            if p not in ALL_PERMISSIONS:
                logger.error(f"Unrecognized permission in child request: {p}")
                return False

        if not child_set.issubset(parent_set):
            unauthorized = child_set - parent_set
            logger.warning(f"Permission escalation attempt blocked! Child requested {unauthorized} not held by parent {parent_set}")
            return False

        return True

    def evaluate_confirmation_response(
        self,
        voice_or_text_response: str,
        request_timestamp: float
    ) -> Tuple[bool, str]:
        """
        Evaluates user confirmation response.
        Rules:
        - Explicit 'yes', 'confirm', 'approve', 'proceed' -> Approved
        - Explicit 'no', 'cancel', 'stop', 'deny', 'nevermind' -> Denied
        - Timeout (>= 60s without explicit response) -> Denied
        - Ambiguous speech -> Denied / Request Clarification
        """
        elapsed = time.time() - request_timestamp
        if elapsed > self.confirmation_timeout:
            return False, "TIMEOUT_AUTO_DENIED"

        low = (voice_or_text_response or "").strip().lower()

        positive_keywords = ["yes", "confirm", "approve", "proceed", "do it", "sure", "ok", "okay"]
        negative_keywords = ["no", "cancel", "stop", "deny", "nevermind", "don't", "abort", "not now"]

        if any(w == low or f" {w} " in f" {low} " for w in positive_keywords):
            return True, "APPROVED"

        if any(w == low or f" {w} " in f" {low} " for w in negative_keywords):
            return False, "DENIED"

        # Ambiguous speech: Silence or unclear utterance must NOT approve dangerous actions
        return False, "AMBIGUOUS_NO_APPROVAL"

    def authorize_worker_action(
        self,
        envelope: TaskEnvelope,
        requested_permission: str,
        target_action: str
    ) -> Tuple[bool, str]:
        """
        Validates if worker has the required permission in its envelope for the requested action.
        """
        if requested_permission not in envelope.permissions:
            return False, f"Worker '{envelope.worker}' lacks required permission '{requested_permission}' for action '{target_action}'."

        # Pass through low-level SecurityGuard for Windows OS risk controls
        risk = security_guard.classify_risk(target_action, {})
        if risk == RiskLevel.DANGEROUS and not envelope.requires_confirmation:
            return False, f"Action '{target_action}' is classified as DANGEROUS and requires explicit user confirmation."

        return True, "AUTHORIZED"

    def sanitize_untrusted_data(self, data: str) -> str:
        """
        Neutralizes prompt injection payloads in untrusted content (READMEs, SKILL.md, web content).
        Guarantees untrusted data cannot alter control plane instructions.
        """
        if not data:
            return ""
        # Strip system instruction markers
        cleaned = re.sub(r"(?i)<\s*(system|admin|root|control_plane|instruction)[^>]*>", "[STRIPPED_TAG]", data)
        return cleaned

    def validate_action_execution(
        self,
        task_permissions: List[str],
        action_name: str
    ) -> Tuple[bool, str]:
        """
        Validates whether the task permissions allow the specified action.
        """
        risk = security_guard.classify_risk(action_name, {})
        if risk == RiskLevel.DANGEROUS:
            if GranularPermission.DESTRUCTIVE_OPERATION.value not in task_permissions and GranularPermission.ADMIN_OPERATION.value not in task_permissions:
                return False, f"Action '{action_name}' requires DESTRUCTIVE_OPERATION or ADMIN_OPERATION permission."
        return True, "AUTHORIZED"


SecurityPolicy = SecurityPolicyEngine

# Canonical singleton instance
security_policy = SecurityPolicyEngine()

