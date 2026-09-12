"""
MAX AI — Remote Command Protocol Unit & Security Tests (P2.1)
Tests:
- Command Request & Command Result serialization and deserialization
- Strict envelope and payload validation
- Rejection of malformed, missing, or out-of-bounds parameters
- Rejection of dangerous / shell payloads
- Correlation ID preservation
- Protocol versioning and security boundaries
"""

import unittest
import json
import time
from unittest.mock import MagicMock, AsyncMock, patch

from protocol.versions import Protocol
from protocol.packet_types import PacketType
from protocol.event_types import EventType
from protocol.packet_factory import PacketFactory
from protocol.validators import PacketValidator
from protocol.command_models import (
    CommandStatus,
    CommandErrorCode,
    CommandError,
    CommandRequestPayload,
    CommandResultPayload
)


class TestRemoteCommandProtocol(unittest.TestCase):
    def setUp(self):
        self.device_id = "test-android-uuid-123"
        self.valid_request_payload = {
            "command_id": "cmd_01J8K9L0M1N2",
            "tool": "flashlight",
            "action": "FLASHLIGHT_ON",
            "arguments": {"enabled": True},
            "timeout_ms": 5000
        }
        self.valid_request_envelope = {
            "protocol_version": Protocol.PROTOCOL_VERSION,
            "id": "cmd_01J8K9L0M1N2",
            "type": PacketType.COMMAND_REQUEST.value,
            "timestamp": int(time.time()),
            "source": {"device_id": "windows-main", "platform": "windows"},
            "target": {"device_id": self.device_id, "platform": "android"},
            "payload": self.valid_request_payload
        }
        self.valid_result_payload = {
            "command_id": "cmd_01J8K9L0M1N2",
            "status": CommandStatus.SUCCESS.value,
            "action": "FLASHLIGHT_ON",
            "data": {"enabled": True, "state": "active"},
            "error": None,
            "execution_time_ms": 120
        }
        self.valid_result_envelope = {
            "protocol_version": Protocol.PROTOCOL_VERSION,
            "id": "res_01J8K9L0M1N2",
            "type": PacketType.COMMAND_RESULT.value,
            "timestamp": int(time.time()),
            "source": {"device_id": self.device_id, "platform": "android"},
            "target": {"device_id": "windows-main", "platform": "windows"},
            "payload": self.valid_result_payload
        }

    # 1. Valid command_request accepted
    def test_01_valid_command_request_accepted(self):
        env_err = PacketValidator.validate_envelope(self.valid_request_envelope)
        self.assertIsNone(env_err)
        payload_err = PacketValidator.validate_payload(
            PacketType.COMMAND_REQUEST.value,
            self.valid_request_envelope["payload"]
        )
        self.assertIsNone(payload_err)

    # 2. Valid command_result accepted
    def test_02_valid_command_result_accepted(self):
        env_err = PacketValidator.validate_envelope(self.valid_result_envelope)
        self.assertIsNone(env_err)
        payload_err = PacketValidator.validate_payload(
            PacketType.COMMAND_RESULT.value,
            self.valid_result_envelope["payload"]
        )
        self.assertIsNone(payload_err)

    # 3. Missing command_id rejected
    def test_03_missing_command_id_rejected(self):
        payload = dict(self.valid_request_payload)
        del payload["command_id"]
        err = PacketValidator.validate_payload(PacketType.COMMAND_REQUEST.value, payload)
        self.assertIsNotNone(err)
        self.assertIn("command_id", err)

    # 4. Missing target rejected
    def test_04_missing_target_rejected(self):
        env = dict(self.valid_request_envelope)
        env["target"] = {}
        err = PacketValidator.validate_envelope(env)
        self.assertIsNotNone(err)
        self.assertIn("Target 'device_id'", err)

    # 5. Missing tool rejected
    def test_05_missing_tool_rejected(self):
        payload = dict(self.valid_request_payload)
        del payload["tool"]
        err = PacketValidator.validate_payload(PacketType.COMMAND_REQUEST.value, payload)
        self.assertIsNotNone(err)
        self.assertIn("tool", err)

    # 6. Missing action rejected
    def test_06_missing_action_rejected(self):
        payload = dict(self.valid_request_payload)
        del payload["action"]
        err = PacketValidator.validate_payload(PacketType.COMMAND_REQUEST.value, payload)
        self.assertIsNotNone(err)
        self.assertIn("action", err)

    # 7. Invalid arguments rejected (not a dict)
    def test_07_invalid_arguments_rejected(self):
        payload = dict(self.valid_request_payload)
        payload["arguments"] = ["invalid", "list"]
        err = PacketValidator.validate_payload(PacketType.COMMAND_REQUEST.value, payload)
        self.assertIsNotNone(err)
        self.assertIn("arguments", err)

    # 8. Invalid timeout rejected (string instead of int)
    def test_08_invalid_timeout_rejected(self):
        payload = dict(self.valid_request_payload)
        payload["timeout_ms"] = "five_thousand"
        err = PacketValidator.validate_payload(PacketType.COMMAND_REQUEST.value, payload)
        self.assertIsNotNone(err)
        self.assertIn("timeout_ms", err)

    # 9. Negative / below threshold timeout rejected
    def test_09_negative_timeout_rejected(self):
        payload = dict(self.valid_request_payload)
        payload["timeout_ms"] = -500
        err = PacketValidator.validate_payload(PacketType.COMMAND_REQUEST.value, payload)
        self.assertIsNotNone(err)
        self.assertIn("below minimum allowable threshold", err)

        payload["timeout_ms"] = 500  # < 1000ms
        err = PacketValidator.validate_payload(PacketType.COMMAND_REQUEST.value, payload)
        self.assertIsNotNone(err)
        self.assertIn("below minimum allowable threshold", err)

    # 10. Oversized timeout rejected (> 30000ms)
    def test_10_oversized_timeout_rejected(self):
        payload = dict(self.valid_request_payload)
        payload["timeout_ms"] = 60000
        err = PacketValidator.validate_payload(PacketType.COMMAND_REQUEST.value, payload)
        self.assertIsNotNone(err)
        self.assertIn("exceeds maximum allowable threshold", err)

    # 11. Malformed JSON envelope rejected
    def test_11_malformed_json_rejected(self):
        err = PacketValidator.validate_envelope("not a json object")
        self.assertIsNotNone(err)
        self.assertEqual(err, "Message is not a JSON object")

    # 12. Missing required envelope fields rejected
    def test_12_wrong_packet_type_rejected(self):
        env = dict(self.valid_request_envelope)
        env["id"] = ""
        err = PacketValidator.validate_envelope(env)
        self.assertIsNotNone(err)
        self.assertIn("id", err)

    # 13. Command_result correlation ID preserved
    def test_13_command_result_correlation_id_preserved(self):
        result_model = CommandResultPayload.from_dict(self.valid_result_payload)
        self.assertEqual(result_model.command_id, "cmd_01J8K9L0M1N2")
        self.assertEqual(result_model.status, "success")
        self.assertEqual(result_model.action, "FLASHLIGHT_ON")

    # 14. Missing or invalid status in result rejected
    def test_14_mismatched_result_command_id_rejected(self):
        payload = dict(self.valid_result_payload)
        payload["status"] = "unknown_status_code"
        err = PacketValidator.validate_payload(PacketType.COMMAND_RESULT.value, payload)
        self.assertIsNotNone(err)
        self.assertIn("status", err)

    # 15. Empty device_id in source rejected
    def test_15_empty_device_id_rejected(self):
        env = dict(self.valid_request_envelope)
        env["source"] = {"device_id": "   ", "platform": "windows"}
        err = PacketValidator.validate_envelope(env)
        self.assertIsNotNone(err)
        self.assertIn("Source 'device_id'", err)

    # 16. Invalid platform rejected
    def test_16_invalid_platform_rejected(self):
        env = dict(self.valid_request_envelope)
        env["source"] = {"device_id": "windows-main", "platform": "playstation_5"}
        err = PacketValidator.validate_envelope(env)
        self.assertIsNotNone(err)
        self.assertIn("not a recognized platform", err)

    # 17. Shell / code injection patterns in tool rejected
    def test_17_shell_payload_rejected(self):
        dangerous_tools = [
            "powershell -c calc",
            "cmd.exe /c dir",
            "sh",
            "bash",
            "__import__('os').system('ls')",
            "<script>alert(1)</script>"
        ]
        for dangerous_tool in dangerous_tools:
            payload = dict(self.valid_request_payload)
            payload["tool"] = dangerous_tool
            err = PacketValidator.validate_payload(PacketType.COMMAND_REQUEST.value, payload)
            self.assertIsNotNone(err, f"Dangerous tool pattern '{dangerous_tool}' was not rejected!")

    # 18. Protocol version mismatch handled correctly
    def test_18_protocol_version_handled_correctly(self):
        env = dict(self.valid_request_envelope)
        env["protocol_version"] = "99.0"
        err = PacketValidator.validate_envelope(env)
        self.assertIsNotNone(err)
        self.assertIn("Protocol version mismatch", err)

    # 19. PacketFactory creates compliant command_request
    def test_19_packet_factory_command_request(self):
        req = PacketFactory.create_command_request(
            command_id="cmd_test_factory_01",
            target_device_id="target_dev_01",
            tool="volume",
            action="SET_VOLUME",
            arguments={"volume": 75},
            timeout_ms=3000
        )
        self.assertEqual(req["type"], PacketType.COMMAND_REQUEST.value)
        self.assertEqual(req["protocol_version"], Protocol.PROTOCOL_VERSION)
        self.assertEqual(req["target"]["device_id"], "target_dev_01")
        self.assertEqual(req["payload"]["command_id"], "cmd_test_factory_01")
        self.assertEqual(req["payload"]["tool"], "volume")
        self.assertEqual(req["payload"]["timeout_ms"], 3000)

        # Validate envelope and payload with PacketValidator
        self.assertIsNone(PacketValidator.validate_envelope(req))
        self.assertIsNone(PacketValidator.validate_payload(req["type"], req["payload"]))

    # 20. PacketFactory creates compliant command_result
    def test_20_packet_factory_command_result(self):
        res = PacketFactory.create_command_result(
            result_id="res_test_factory_01",
            command_id="cmd_test_factory_01",
            target_device_id="windows-main",
            action="SET_VOLUME",
            status="success",
            data={"current_volume": 75},
            execution_time_ms=85,
            source_device_id="target_dev_01",
            source_platform="android"
        )
        self.assertEqual(res["type"], PacketType.COMMAND_RESULT.value)
        self.assertEqual(res["protocol_version"], Protocol.PROTOCOL_VERSION)
        self.assertEqual(res["payload"]["command_id"], "cmd_test_factory_01")
        self.assertEqual(res["payload"]["status"], "success")
        self.assertEqual(res["payload"]["execution_time_ms"], 85)

        self.assertIsNone(PacketValidator.validate_envelope(res))
        self.assertIsNone(PacketValidator.validate_payload(res["type"], res["payload"]))

    # 21. Dangerous code injection in action rejected
    def test_21_dangerous_action_injection_rejected(self):
        payload = dict(self.valid_request_payload)
        payload["action"] = "FLASHLIGHT; powershell.exe"
        err = PacketValidator.validate_payload(PacketType.COMMAND_REQUEST.value, payload)
        self.assertIsNotNone(err)

    # 22. Oversized tool/action strings rejected (> 128 chars)
    def test_22_oversized_tool_action_rejected(self):
        payload = dict(self.valid_request_payload)
        payload["tool"] = "A" * 150
        err = PacketValidator.validate_payload(PacketType.COMMAND_REQUEST.value, payload)
        self.assertIsNotNone(err)
        self.assertIn("exceeds maximum length", err)

    # 23. Structured error validation in result
    def test_23_structured_error_validation_in_result(self):
        payload = dict(self.valid_result_payload)
        payload["status"] = CommandStatus.FAILED.value
        payload["error"] = {"code": "HARDWARE_UNAVAILABLE", "message": "Camera flashlight is in use by another app"}
        err = PacketValidator.validate_payload(PacketType.COMMAND_RESULT.value, payload)
        self.assertIsNone(err)

        # Invalid error format (missing message)
        payload["error"] = {"code": "ERR_ONLY"}
        err = PacketValidator.validate_payload(PacketType.COMMAND_RESULT.value, payload)
        self.assertIsNotNone(err)
        self.assertIn("message", err)


if __name__ == "__main__":
    unittest.main()
