"""
Unit tests for SecurityPolicyEngine (Permission Inheritance, Deep Redaction, Confirmation Policy).
"""

import time
import pytest
from core.security_policy import SecurityPolicyEngine, mask_secrets_deeply
from protocol.task_envelope import TaskEnvelope, GranularPermission


@pytest.fixture
def policy():
    return SecurityPolicyEngine(confirmation_timeout=60.0)


class TestSecurityPolicyAndRedaction:

    def test_permission_inheritance_valid_subset(self, policy):
        parent_perms = ["WORKSPACE_READ", "WORKSPACE_WRITE", "TEST_EXECUTION", "GIT_READ"]
        child_perms = ["WORKSPACE_READ", "TEST_EXECUTION"]

        valid = policy.validate_permission_inheritance(parent_perms, child_perms)
        assert valid is True

    def test_permission_inheritance_escalation_rejected(self, policy):
        parent_perms = ["WORKSPACE_READ"]
        child_perms = ["WORKSPACE_READ", "WORKSPACE_WRITE"]

        valid = policy.validate_permission_inheritance(parent_perms, child_perms)
        assert valid is False

    def test_deep_secret_redaction_dictionary(self):
        payload = {
            "api_key": "AIzaSyDummyGoogleKey1234567890abcdefghij",
            "db_password": "super_secret_password",
            "nested": {
                "auth_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.dummy.token",
                "normal_field": "safe_value"
            },
            "list_field": [
                {"secret_token": "sk-abcdef12345678901234567890123456"},
                "safe list item"
            ]
        }

        masked = mask_secrets_deeply(payload)
        assert masked["api_key"] == "[REDACTED_SECRET]"
        assert masked["db_password"] == "[REDACTED_SECRET]"
        assert masked["nested"]["auth_token"] == "[REDACTED_SECRET]"
        assert masked["nested"]["normal_field"] == "safe_value"
        assert masked["list_field"][0]["secret_token"] == "[REDACTED_SECRET]"
        assert masked["list_field"][1] == "safe list item"

    def test_deep_secret_redaction_in_text(self):
        text = "Error calling endpoint with key AIzaSyA123456789012345678901234567890A and Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.dummy.token123"
        masked = mask_secrets_deeply(text)
        assert "AIzaSy" not in masked
        assert "[REDACTED_TOKEN]" in masked

    def test_confirmation_response_explicit_approval(self, policy):
        approved, reason = policy.evaluate_confirmation_response("yes, proceed", time.time())
        assert approved is True
        assert reason == "APPROVED"

    def test_confirmation_response_explicit_denial(self, policy):
        approved, reason = policy.evaluate_confirmation_response("no, cancel it", time.time())
        assert approved is False
        assert reason == "DENIED"

    def test_confirmation_response_timeout_auto_denied(self, policy):
        req_time = time.time() - 65.0  # 65s ago (exceeds 60s timeout)
        approved, reason = policy.evaluate_confirmation_response("yes", req_time)
        assert approved is False
        assert reason == "TIMEOUT_AUTO_DENIED"

    def test_confirmation_response_ambiguous_denied(self, policy):
        approved, reason = policy.evaluate_confirmation_response("maybe later i guess", time.time())
        assert approved is False
        assert reason == "AMBIGUOUS_NO_APPROVAL"
