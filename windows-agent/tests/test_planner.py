"""
MAX AI — Task Planner Foundation Unit & Security Tests
Validates plan schema parsing, tool allowlist enforcement, step limits,
duplicate ID rejection, script payload detection, and server-authoritative confirmation.
"""

import os
import sys
import json
import unittest

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from planner.schemas import Plan, PlanStep, PlanValidationResult
from planner.validator import PlanValidator, plan_validator, MAX_PLAN_STEPS
from planner.prompt import get_planner_system_prompt, build_tool_catalog
from core.unified_tool_registry import unified_tool_registry


class TestPlannerFoundation(unittest.TestCase):

    def setUp(self):
        self.validator = PlanValidator()

    def test_01_valid_plan_accepted(self):
        """Verify a valid structured plan is accepted cleanly."""
        raw_plan = {
            "goal": "Open Notepad and type greeting",
            "steps": [
                {
                    "id": "step_1",
                    "tool": "open_application",
                    "args": {"app_name": "notepad"},
                    "description": "Open Notepad",
                    "requires_confirmation": False
                },
                {
                    "id": "step_2",
                    "tool": "type_text",
                    "args": {"text": "Hello MAX"},
                    "description": "Type greeting text",
                    "requires_confirmation": False
                }
            ]
        }
        res = self.validator.validate(raw_plan)
        self.assertTrue(res.valid)
        self.assertIsNotNone(res.plan)
        self.assertEqual(res.plan.goal, "Open Notepad and type greeting")
        self.assertEqual(len(res.plan.steps), 2)
        self.assertEqual(res.plan.steps[0].tool, "open_application")

    def test_02_empty_goal_rejected(self):
        """Verify plan with empty goal is rejected."""
        raw_plan = {
            "goal": "   ",
            "steps": [
                {"id": "step_1", "tool": "get_volume", "args": {}}
            ]
        }
        res = self.validator.validate(raw_plan)
        self.assertFalse(res.valid)
        self.assertEqual(res.error.code, "MISSING_GOAL")

    def test_03_missing_steps_rejected(self):
        """Verify plan without steps array is rejected."""
        raw_plan = {"goal": "Do something", "steps": []}
        res = self.validator.validate(raw_plan)
        self.assertFalse(res.valid)
        self.assertEqual(res.error.code, "EMPTY_STEPS")

    def test_04_excessive_steps_rejected(self):
        """Verify plan with more than 8 steps is rejected."""
        steps = [
            {"id": f"step_{i}", "tool": "get_volume", "args": {}}
            for i in range(MAX_PLAN_STEPS + 1)
        ]
        raw_plan = {"goal": "Too many steps", "steps": steps}
        res = self.validator.validate(raw_plan)
        self.assertFalse(res.valid)
        self.assertEqual(res.error.code, "EXCESSIVE_STEPS")

    def test_05_duplicate_step_ids_rejected(self):
        """Verify duplicate step IDs within a plan are rejected."""
        raw_plan = {
            "goal": "Duplicate ID test",
            "steps": [
                {"id": "step_1", "tool": "get_volume", "args": {}},
                {"id": "step_1", "tool": "get_brightness", "args": {}}
            ]
        }
        res = self.validator.validate(raw_plan)
        self.assertFalse(res.valid)
        self.assertEqual(res.error.code, "DUPLICATE_STEP_ID")

    def test_06_unknown_tool_rejected(self):
        """Verify hallucinated or unregistered tools are strictly rejected."""
        raw_plan = {
            "goal": "Hack system",
            "steps": [
                {"id": "step_1", "tool": "non_existent_exploit_tool", "args": {}}
            ]
        }
        res = self.validator.validate(raw_plan)
        self.assertFalse(res.valid)
        self.assertEqual(res.error.code, "UNKNOWN_TOOL")

    def test_07_invalid_arguments_rejected(self):
        """Verify non-dictionary arguments are rejected."""
        raw_plan = {
            "goal": "Invalid args",
            "steps": [
                {"id": "step_1", "tool": "open_application", "args": "notepad"}
            ]
        }
        res = self.validator.validate(raw_plan)
        self.assertFalse(res.valid)
        self.assertEqual(res.error.code, "INVALID_ARGS")

    def test_08_prohibited_script_payload_rejected(self):
        """Verify prohibited script/code injection payload in args is rejected."""
        raw_plan = {
            "goal": "Inject script",
            "steps": [
                {
                    "id": "step_1",
                    "tool": "open_application",
                    "args": {"app_name": "notepad; powershell -Command Invoke-Expression ..."}
                }
            ]
        }
        res = self.validator.validate(raw_plan)
        self.assertFalse(res.valid)
        self.assertEqual(res.error.code, "DANGEROUS_PAYLOAD")

    def test_09_planner_cannot_bypass_confirmation(self):
        """Verify dangerous tools always require confirmation regardless of planner declaration."""
        # delete_file is a server-classified dangerous tool
        raw_plan = {
            "goal": "Delete important file",
            "steps": [
                {
                    "id": "step_1",
                    "tool": "delete_file",
                    "args": {"filepath": os.path.join(os.path.expanduser("~"), "test.txt")},
                    "requires_confirmation": False  # Planner attempts to bypass
                }
            ]
        }
        res = self.validator.validate(raw_plan)
        self.assertTrue(res.valid)
        # Server must override requires_confirmation to True
        self.assertTrue(res.plan.steps[0].requires_confirmation)

    def test_10_dynamic_tool_catalog_in_prompt(self):
        """Verify planner system prompt dynamically includes registered tools."""
        prompt = get_planner_system_prompt()
        self.assertIn("open_application", prompt)
        self.assertIn("type_text", prompt)
        self.assertIn("click_ui_element", prompt)
        self.assertIn("ALLOWED TOOLS CATALOG", prompt)

    def test_11_path_traversal_in_plan_rejected(self):
        """Verify path traversal in step arguments is blocked by SecurityGuard."""
        raw_plan = {
            "goal": "Read sensitive file",
            "steps": [
                {
                    "id": "step_1",
                    "tool": "open_file",
                    "args": {"file_path": r"C:\Windows\System32\drivers\etc\hosts\..\..\..\..\cmd.exe"}
                }
            ]
        }
        res = self.validator.validate(raw_plan)
        self.assertFalse(res.valid)
        self.assertEqual(res.error.code, "SECURITY_VIOLATION")

    def test_12_planning_client_success_mocked(self):
        """Verify GeminiPlanningClient processes successful Gemini REST response into validated Plan."""
        from unittest.mock import MagicMock
        from planner.client import GeminiPlanningClient

        mock_session = MagicMock()
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "candidates": [
                {
                    "content": {
                        "parts": [
                            {
                                "text": json.dumps({
                                    "goal": "Open Notepad and type Hello",
                                    "steps": [
                                        {
                                            "id": "step_1",
                                            "tool": "open_application",
                                            "args": {"app_name": "notepad"},
                                            "description": "Open Notepad"
                                        },
                                        {
                                            "id": "step_2",
                                            "tool": "type_text",
                                            "args": {"text": "Hello MAX"},
                                            "description": "Type greeting text"
                                        }
                                    ]
                                })
                            }
                        ]
                    }
                }
            ]
        }
        mock_session.post.return_value = mock_response

        client = GeminiPlanningClient(api_key="AIzaSyMockKeyForTesting1234567890abcdef")
        res = client.plan("Open Notepad and type Hello", session_http=mock_session)

        self.assertTrue(res.valid)
        self.assertIsNotNone(res.plan)
        self.assertEqual(len(res.plan.steps), 2)
        self.assertEqual(res.plan.steps[0].tool, "open_application")
        self.assertEqual(res.plan.steps[1].tool, "type_text")

        # Verify API key was passed in headers, not in URL
        called_url = mock_session.post.call_args[0][0]
        called_headers = mock_session.post.call_args[1]["headers"]
        self.assertNotIn("AIzaSyMockKey", called_url)
        self.assertEqual(called_headers.get("x-goog-api-key"), "AIzaSyMockKeyForTesting1234567890abcdef")

    def test_13_planning_client_malformed_json_mocked(self):
        """Verify GeminiPlanningClient handles malformed JSON from model cleanly."""
        from unittest.mock import MagicMock
        from planner.client import GeminiPlanningClient

        mock_session = MagicMock()
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "candidates": [
                {
                    "content": {
                        "parts": [{"text": "THIS IS NOT VALID JSON {{"}]
                    }
                }
            ]
        }
        mock_session.post.return_value = mock_response

        client = GeminiPlanningClient(api_key="AIzaSyMockKeyForTesting1234567890abcdef")
        res = client.plan("Open Notepad", session_http=mock_session)

        self.assertFalse(res.valid)
        self.assertEqual(res.error.code, "MALFORMED_JSON")

    def test_14_planning_client_http_error_mocked(self):
        """Verify GeminiPlanningClient handles HTTP error response."""
        from unittest.mock import MagicMock
        from planner.client import GeminiPlanningClient

        mock_session = MagicMock()
        mock_response = MagicMock()
        mock_response.status_code = 429
        mock_response.json.return_value = {
            "error": {"status": "RESOURCE_EXHAUSTED", "message": "Quota exceeded"}
        }
        mock_session.post.return_value = mock_response

        client = GeminiPlanningClient(api_key="AIzaSyMockKeyForTesting1234567890abcdef")
        res = client.plan("Open Notepad", session_http=mock_session)

        self.assertFalse(res.valid)
        self.assertEqual(res.error.code, "HTTP_429")

    def test_15_planning_client_timeout_mocked(self):
        """Verify GeminiPlanningClient handles request timeout gracefully."""
        import requests
        from unittest.mock import MagicMock
        from planner.client import GeminiPlanningClient

        mock_session = MagicMock()
        mock_session.post.side_effect = requests.exceptions.Timeout("Connection timed out")

        client = GeminiPlanningClient(api_key="AIzaSyMockKeyForTesting1234567890abcdef")
        res = client.plan("Open Notepad", session_http=mock_session)

        self.assertFalse(res.valid)
        self.assertEqual(res.error.code, "TIMEOUT")

    def test_16_planning_client_unknown_tool_rejection_mocked(self):
        """Verify model output with hallucinated tool is caught and rejected by validator."""
        from unittest.mock import MagicMock
        from planner.client import GeminiPlanningClient

        mock_session = MagicMock()
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "candidates": [
                {
                    "content": {
                        "parts": [
                            {
                                "text": json.dumps({
                                    "goal": "Unsafe action",
                                    "steps": [
                                        {
                                            "id": "step_1",
                                            "tool": "hallucinated_unregistered_tool",
                                            "args": {}
                                        }
                                    ]
                                })
                            }
                        ]
                    }
                }
            ]
        }
        mock_session.post.return_value = mock_response

        client = GeminiPlanningClient(api_key="AIzaSyMockKeyForTesting1234567890abcdef")
        res = client.plan("Do hallucinated action", session_http=mock_session)

        self.assertFalse(res.valid)
        self.assertEqual(res.error.code, "UNKNOWN_TOOL")

    # ─── P2.2-D PlanNormalizer Tests ──────────────────────────────────────────

    def test_17_normalizer_canonical_tool_unchanged(self):
        """Verify already canonical tool names and arguments remain untouched."""
        from planner.normalizer import plan_normalizer

        plan = Plan(
            goal="Open notepad",
            steps=[
                PlanStep(id="s1", tool="open_application", args={"app_name": "notepad"})
            ]
        )
        ok, norm_plan, err = plan_normalizer.normalize(plan)
        self.assertTrue(ok)
        self.assertEqual(norm_plan.steps[0].tool, "open_application")
        self.assertEqual(norm_plan.steps[0].args["app_name"], "notepad")

    def test_18_normalizer_valid_tool_alias_normalized(self):
        """Verify valid tool aliases map deterministically to canonical names."""
        from planner.normalizer import plan_normalizer

        plan = Plan(
            goal="Test aliases",
            steps=[
                PlanStep(id="s1", tool="launch_app", args={"app_name": "calc"}),
                PlanStep(id="s2", tool="type", args={"text": "123"}),
                PlanStep(id="s3", tool="screenshot", args={})
            ]
        )
        ok, norm_plan, err = plan_normalizer.normalize(plan)
        self.assertTrue(ok)
        self.assertEqual(norm_plan.steps[0].tool, "open_application")
        self.assertEqual(norm_plan.steps[1].tool, "type_text")
        self.assertEqual(norm_plan.steps[2].tool, "take_screenshot")

    def test_19_normalizer_argument_alias_normalized(self):
        """Verify parameter aliases (e.g. app -> app_name, string -> text) are normalized."""
        from planner.normalizer import plan_normalizer

        plan = Plan(
            goal="Argument aliases",
            steps=[
                PlanStep(id="s1", tool="open_application", args={"app": "notepad.exe"}),
                PlanStep(id="s2", tool="type_text", args={"string": "Hello world"}),
                PlanStep(id="s3", tool="set_volume", args={"volume": "75%"})
            ]
        )
        ok, norm_plan, err = plan_normalizer.normalize(plan)
        self.assertTrue(ok)
        self.assertEqual(norm_plan.steps[0].args["app_name"], "notepad")
        self.assertEqual(norm_plan.steps[1].args["text"], "Hello world")
        self.assertEqual(norm_plan.steps[2].args["level"], 75)

    def test_20_normalizer_unknown_tool_rejected(self):
        """Verify unresolvable tool aliases fail normalization without guessing."""
        from planner.normalizer import plan_normalizer

        plan = Plan(
            goal="Unknown tool",
            steps=[PlanStep(id="s1", tool="completely_unknown_action", args={})]
        )
        ok, norm_plan, err = plan_normalizer.normalize(plan)
        self.assertFalse(ok)
        self.assertEqual(err.code, "UNRESOLVABLE_TOOL")

    def test_21_normalizer_preserves_confirmation(self):
        """Verify dangerous tools preserve requires_confirmation = True during normalization."""
        from planner.normalizer import plan_normalizer

        plan = Plan(
            goal="Delete file",
            steps=[
                PlanStep(
                    id="s1",
                    tool="delete_file",
                    args={"file": os.path.join(os.path.expanduser("~"), "tmp.txt")},
                    requires_confirmation=False
                )
            ]
        )
        ok, norm_plan, err = plan_normalizer.normalize(plan)
        self.assertTrue(ok)
        self.assertTrue(norm_plan.steps[0].requires_confirmation)

    # ─── P2.2-D TaskFactory Tests ─────────────────────────────────────────────

    def test_22_task_factory_creates_task(self):
        """Verify TaskFactory converts a Plan into an executable Task with Steps."""
        from planner.task_factory import task_factory
        from engine.models import TaskState, StepState, VerificationType

        plan = Plan(
            goal="Launch Notepad and type",
            steps=[
                PlanStep(id="step_1", tool="open_application", args={"app_name": "notepad"}),
                PlanStep(id="step_2", tool="type_text", args={"text": "Hello MAX"})
            ]
        )
        task = task_factory.create_task_from_plan(plan)

        self.assertIsNotNone(task)
        self.assertTrue(task.task_id.startswith("task_"))
        self.assertEqual(task.goal, "Launch Notepad and type")
        self.assertEqual(task.status, TaskState.CREATED)
        self.assertEqual(len(task.steps), 2)

        # Step 1 checks
        s1 = task.steps[0]
        self.assertEqual(s1.step_id, "step_1")
        self.assertEqual(s1.sequence, 1)
        self.assertEqual(s1.action, "open_application")
        self.assertEqual(s1.arguments["app_name"], "notepad")
        self.assertEqual(s1.status, StepState.PENDING)
        self.assertEqual(s1.verification.type, VerificationType.APP_LAUNCHED)
        self.assertEqual(s1.verification.target, "notepad")
        self.assertTrue(s1.retryable)
        self.assertEqual(s1.max_attempts, 3)
        self.assertEqual(s1.idempotency_key, f"{task.task_id}:step_1:attempt_0")

        # Step 2 checks
        s2 = task.steps[1]
        self.assertEqual(s2.step_id, "step_2")
        self.assertEqual(s2.sequence, 2)
        self.assertEqual(s2.action, "type_text")
        self.assertEqual(s2.verification.type, VerificationType.NONE)
        self.assertFalse(s2.retryable)
        self.assertEqual(s2.max_attempts, 1)

    # ─── P2.2-D FastPathResolver Tests ────────────────────────────────────────

    def test_23_fast_path_open_app(self):
        """Verify single-shot open app matches fast path."""
        from planner.fast_path import fast_path_resolver

        plan = fast_path_resolver.resolve("Open Notepad")
        self.assertIsNotNone(plan)
        self.assertEqual(plan.steps[0].tool, "open_application")
        self.assertEqual(plan.steps[0].args["app_name"], "notepad")

    def test_24_fast_path_close_app(self):
        """Verify single-shot close app matches fast path."""
        from planner.fast_path import fast_path_resolver

        plan = fast_path_resolver.resolve("Close Calculator")
        self.assertIsNotNone(plan)
        self.assertEqual(plan.steps[0].tool, "close_application")
        self.assertEqual(plan.steps[0].args["app_name"], "calculator")

    def test_25_fast_path_screenshot(self):
        """Verify screenshot command matches fast path."""
        from planner.fast_path import fast_path_resolver

        plan = fast_path_resolver.resolve("take screenshot")
        self.assertIsNotNone(plan)
        self.assertEqual(plan.steps[0].tool, "take_screenshot")

    def test_26_fast_path_volume(self):
        """Verify volume commands match fast path."""
        from planner.fast_path import fast_path_resolver

        plan = fast_path_resolver.resolve("set volume to 80%")
        self.assertIsNotNone(plan)
        self.assertEqual(plan.steps[0].tool, "set_volume")
        self.assertEqual(plan.steps[0].args["level"], 80)

        plan_mute = fast_path_resolver.resolve("mute volume")
        self.assertIsNotNone(plan_mute)
        self.assertEqual(plan_mute.steps[0].tool, "mute_unmute")

    def test_27_fast_path_multi_step_returns_none(self):
        """Verify multi-step compound commands return None to allow LLM planning."""
        from planner.fast_path import fast_path_resolver

        self.assertIsNone(fast_path_resolver.resolve("Open Notepad and type Hello"))
        self.assertIsNone(fast_path_resolver.resolve("Open Chrome then search Google"))
        self.assertIsNone(fast_path_resolver.resolve("Close VS Code, run tests"))

    def test_28_fast_path_passes_plan_validator(self):
        """Verify fast-path generated plans pass through PlanValidator with 100% compliance."""
        from planner.fast_path import fast_path_resolver
        from planner.validator import plan_validator

        plan = fast_path_resolver.resolve("Open Notepad")
        res = plan_validator.validate(plan.to_dict())
        self.assertTrue(res.valid)

    # ─── End-to-End P2.2 Integration Test ─────────────────────────────────────

    def test_29_e2e_plan_to_task_engine_execution(self):
        """
        Verify end-to-end integration:
        Raw Plan -> PlanValidator -> PlanNormalizer -> TaskFactory -> Task -> TaskEngine
        """
        import asyncio
        import tempfile
        import shutil
        from planner.validator import plan_validator
        from planner.normalizer import plan_normalizer
        from planner.task_factory import task_factory
        from engine.task_engine import TaskEngine
        from engine.store import TaskStore
        from engine.models import TaskState, StepState

        # 1. Simulate Raw Plan from LLM / Fast-Path
        raw_plan = {
            "goal": "Check system specs and volume",
            "steps": [
                {
                    "id": "s1",
                    "tool": "system_info",  # Will normalize to get_system_info
                    "args": {},
                    "description": "Get system info"
                },
                {
                    "id": "s2",
                    "tool": "volume_get",   # Will normalize to get_volume
                    "args": {},
                    "description": "Get current volume"
                }
            ]
        }

        # 2. Validate
        val_res = plan_validator.validate(raw_plan)
        self.assertTrue(val_res.valid)

        # 3. Normalize
        ok, norm_plan, err = plan_normalizer.normalize(val_res.plan)
        self.assertTrue(ok)
        self.assertEqual(norm_plan.steps[0].tool, "get_system_info")
        self.assertEqual(norm_plan.steps[1].tool, "get_volume")

        # 4. Task Factory
        task = task_factory.create_task_from_plan(norm_plan)
        self.assertEqual(task.status, TaskState.CREATED)
        self.assertEqual(len(task.steps), 2)

        # 5. Execute in isolated TaskEngine
        test_dir = tempfile.mkdtemp()
        try:
            store = TaskStore(file_path=os.path.join(test_dir, "tasks.json"))
            engine = TaskEngine(store=store)

            async def run():
                return await engine.execute_task(task)

            completed_task = asyncio.run(run())
            self.assertEqual(completed_task.status, TaskState.COMPLETED)
            self.assertEqual(completed_task.steps[0].status, StepState.SUCCEEDED)
            self.assertEqual(completed_task.steps[1].status, StepState.SUCCEEDED)
        finally:
            shutil.rmtree(test_dir, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()


