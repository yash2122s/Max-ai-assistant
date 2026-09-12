"""
MAX AI — Orchestrator Unit and Integration Test Suite
Validates goal input validation, FastPath vs Planner routing, TaskFactory creation,
TaskEngine execution, concurrency limits, cancellation, timeout handling, and security gates.
"""

import os
import sys
import json
import asyncio
import unittest
import tempfile
import shutil
from unittest.mock import MagicMock

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from orchestrator.router import OrchestrationRouter
from planner.schemas import Plan, PlanStep, PlanValidationResult, PlannerError
from planner.validator import PlanValidator
from planner.normalizer import PlanNormalizer
from planner.task_factory import TaskFactory
from planner.fast_path import FastPathResolver
from planner.client import GeminiPlanningClient
from engine.task_engine import TaskEngine
from engine.store import TaskStore
from engine.models import Task, Step, TaskState, StepState, VerificationType


class TestOrchestrator(unittest.TestCase):

    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.store = TaskStore(file_path=os.path.join(self.test_dir, "tasks.json"))
        self.engine = TaskEngine(store=self.store)
        self.fast_path = FastPathResolver()
        self.validator = PlanValidator()
        self.normalizer = PlanNormalizer()
        self.factory = TaskFactory()
        self.mock_planner = MagicMock()

        self.router = OrchestrationRouter(
            engine=self.engine,
            fast_path=self.fast_path,
            planner=self.mock_planner,
            validator=self.validator,
            normalizer=self.normalizer,
            factory=self.factory
        )

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_01_empty_goal_rejected(self):
        """Verify empty or whitespace goal returns rejected status."""
        async def run():
            return await self.router.execute_goal("   ")

        res = asyncio.run(run())
        self.assertEqual(res["status"], "rejected")
        self.assertEqual(res["error_code"], "EMPTY_GOAL")

    def test_02_fast_path_match(self):
        """Verify deterministic command routes through FastPath without calling LLM planner."""
        async def run():
            return await self.router.execute_goal("Open Notepad", async_execution=False)

        res = asyncio.run(run())
        self.assertEqual(res["status"], "completed")
        self.assertEqual(res["source"], "fast_path")
        self.assertIsNotNone(res["task_id"])
        self.mock_planner.plan.assert_not_called()

    def test_03_fast_path_miss_calls_planner(self):
        """Verify compound task misses FastPath and invokes GeminiPlanningClient."""
        # Setup mock planner response for multi-step goal
        valid_plan = Plan(
            goal="Open Notepad and type Hello MAX",
            steps=[
                PlanStep(id="s1", tool="open_application", args={"app_name": "notepad"}),
                PlanStep(id="s2", tool="type_text", args={"text": "Hello MAX"})
            ]
        )
        self.mock_planner.plan.return_value = PlanValidationResult(valid=True, plan=valid_plan)

        async def run():
            return await self.router.execute_goal("Open Notepad and type Hello MAX", async_execution=False)

        res = asyncio.run(run())
        self.assertEqual(res["status"], "completed")
        self.assertEqual(res["source"], "planner")
        self.assertEqual(res["total_steps"], 2)
        self.mock_planner.plan.assert_called_once()

    def test_04_planner_failure_handled_gracefully(self):
        """Verify planner failure (timeout/HTTP error) returns structured error response."""
        self.mock_planner.plan.return_value = PlanValidationResult(
            valid=False,
            error=PlannerError(code="TIMEOUT", message="Planning request timed out.")
        )

        async def run():
            return await self.router.execute_goal("Open Chrome and search python", async_execution=False)

        res = asyncio.run(run())
        self.assertEqual(res["status"], "failed")
        self.assertEqual(res["error_code"], "TIMEOUT")
        self.assertIn("timed out", res["message"])

    def test_05_validator_rejection_in_router(self):
        """Verify router rejects plans with unvalidated/disallowed tools."""
        invalid_plan = Plan(
            goal="Hack system",
            steps=[PlanStep(id="s1", tool="unregistered_tool_exploit", args={})]
        )
        self.mock_planner.plan.return_value = PlanValidationResult(valid=True, plan=invalid_plan)

        async def run():
            return await self.router.execute_goal("Do malicious action", async_execution=False)

        res = asyncio.run(run())
        self.assertEqual(res["status"], "rejected")
        self.assertEqual(res["error_code"], "UNKNOWN_TOOL")

    def test_06_async_execution_returns_accepted(self):
        """Verify async_execution=True returns 'accepted' immediately with task_id."""
        async def run():
            return await self.router.execute_goal("take screenshot", async_execution=True)

        res = asyncio.run(run())
        self.assertEqual(res["status"], "accepted")
        self.assertIsNotNone(res["task_id"])
        self.assertEqual(res["message"], "Okay bangaram, I'm on it.")

    def test_07_second_task_rejected_when_busy(self):
        """Verify concurrency limit: new task rejected when another task is active."""
        # Manually mark engine busy
        self.engine._active_task_id = "task_already_running"

        async def run():
            return await self.router.execute_goal("Open Calculator")

        res = asyncio.run(run())
        self.assertEqual(res["status"], "busy")
        self.assertEqual(res["error_code"], "ENGINE_BUSY")
        self.assertIn("already working on another task", res["message"])

        self.engine._active_task_id = None

    def test_08_cancellation_active_task(self):
        """Verify cancelling an active task transitions task to CANCELLED."""
        # Create a pending task in store
        task = self.engine.create_task(
            goal="Test cancellation",
            steps=[Step(step_id="s1", task_id="", sequence=1, action="get_volume")]
        )

        res = self.router.cancel_task(task.task_id, reason="User said stop")
        self.assertEqual(res["status"], "cancelled")
        self.assertEqual(res["task_id"], task.task_id)

        # Check in store
        reloaded = self.store.get_task(task.task_id)
        self.assertEqual(reloaded.status, TaskState.CANCELLED)

    def test_09_cancellation_no_active_task(self):
        """Verify cancelling when no task is running returns clean ignored message."""
        res = self.router.cancel_task()
        self.assertEqual(res["status"], "ignored")
        self.assertIn("No active task", res["message"])

    def test_10_get_status(self):
        """Verify get_status returns current task state and steps."""
        task = self.engine.create_task(
            goal="Status test",
            steps=[Step(step_id="s1", task_id="", sequence=1, action="get_volume")]
        )
        status_res = self.router.get_status(task.task_id)
        self.assertEqual(status_res["status"], "created")
        self.assertEqual(status_res["goal"], "Status test")
        self.assertEqual(status_res["total_steps"], 1)

    def test_11_no_direct_tool_execution_from_orchestrator(self):
        """Verify orchestrator only constructs and passes tasks to TaskEngine, never directly calling tools."""
        # Mock engine.execute_task to verify it is called, but UnifiedToolRegistry.execute is never called by the router
        from core.unified_tool_registry import unified_tool_registry
        orig_execute = unified_tool_registry.execute
        exec_called = False

        def spy_execute(tool_name, args):
            nonlocal exec_called
            exec_called = True
            return orig_execute(tool_name, args)

        unified_tool_registry.execute = spy_execute

        # Patch _run_task_async on router so background task execution doesn't trigger tool execution during assertion
        async def dummy_run_task(task):
            pass

        self.router._run_task_async = dummy_run_task

        async def run():
            return await self.router.execute_goal("Open Notepad", async_execution=True)

        res = asyncio.run(run())
        self.assertEqual(res["status"], "accepted")
        self.assertFalse(exec_called)  # Tool execution must NOT occur synchronously in the orchestrator

        unified_tool_registry.execute = orig_execute



if __name__ == "__main__":
    unittest.main()
