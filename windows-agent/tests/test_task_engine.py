"""
MAX AI — Task Engine Comprehensive Test Suite
Validates Task/Step models, state machine transitions, TaskStore atomic persistence,
crash recovery, execution limits, cancellation, verification, retry, and security.
"""

import os
import sys
import time
import json
import asyncio
import unittest
import tempfile
import shutil

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from engine.models import Task, Step, TaskState, StepState, VerificationPolicy, VerificationType
from engine.state import StateValidator, TaskStateError
from engine.store import TaskStore
from engine.step_runner import StepRunner
from engine.task_engine import TaskEngine, MAX_STEPS, MAX_CONCURRENT_TASKS
from core.unified_tool_registry import unified_tool_registry


class TestTaskEngine(unittest.TestCase):

    def setUp(self):
        # Create isolated temp directory for TaskStore
        self.test_dir = tempfile.mkdtemp()
        self.test_store_path = os.path.join(self.test_dir, "tasks.json")
        self.store = TaskStore(file_path=self.test_store_path)
        self.engine = TaskEngine(store=self.store)

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_01_task_and_step_serialization(self):
        """Verify Task and Step serialization and deserialization."""
        vpolicy = VerificationPolicy(type=VerificationType.APP_LAUNCHED, target="notepad.exe", timeout_seconds=2.0)
        step = Step(
            step_id="step_1",
            task_id="task_123",
            sequence=1,
            action="open_application",
            arguments={"app_name": "notepad"},
            verification=vpolicy,
            max_attempts=3
        )
        task = Task(
            task_id="task_123",
            goal="Open notepad",
            status=TaskState.CREATED,
            steps=[step]
        )

        task_dict = task.to_dict()
        self.assertEqual(task_dict["task_id"], "task_123")
        self.assertEqual(task_dict["status"], "CREATED")
        self.assertEqual(len(task_dict["steps"]), 1)
        self.assertEqual(task_dict["steps"][0]["verification"]["type"], "app_launched")

        # Deserialization
        restored_task = Task.from_dict(task_dict)
        self.assertEqual(restored_task.task_id, "task_123")
        self.assertEqual(restored_task.status, TaskState.CREATED)
        self.assertEqual(len(restored_task.steps), 1)
        self.assertEqual(restored_task.steps[0].action, "open_application")
        self.assertEqual(restored_task.steps[0].verification.type, VerificationType.APP_LAUNCHED)

    def test_02_task_persistence_and_atomic_writes(self):
        """Verify TaskStore saves and retrieves tasks atomically."""
        step = Step(step_id="s1", task_id="t1", sequence=1, action="get_volume")
        task = Task(task_id="t1", goal="Check volume", steps=[step])

        self.store.save_task(task)
        loaded = self.store.get_task("t1")
        self.assertIsNotNone(loaded)
        self.assertEqual(loaded.goal, "Check volume")
        self.assertEqual(len(loaded.steps), 1)

    def test_03_corrupt_store_auto_recovery(self):
        """Verify TaskStore detects corruption and creates a backup without crashing."""
        # Corrupt the file
        with open(self.test_store_path, "w") as f:
            f.write("INVALID JSON CORRUPT CONTENT")

        # Loading from store should recover cleanly
        tasks = self.store.list_tasks()
        self.assertEqual(len(tasks), 0)

        # Corrupt backup file should exist
        backups = [f for f in os.listdir(self.test_dir) if "corrupt" in f]
        self.assertTrue(len(backups) >= 1)

    def test_04_state_transitions_validation(self):
        """Verify StateValidator allows valid transitions and rejects invalid ones."""
        # Valid Task transitions
        self.assertTrue(StateValidator.can_transition_task(TaskState.CREATED, TaskState.RUNNING))
        self.assertTrue(StateValidator.can_transition_task(TaskState.RUNNING, TaskState.VERIFYING))
        self.assertTrue(StateValidator.can_transition_task(TaskState.VERIFYING, TaskState.COMPLETED))
        self.assertTrue(StateValidator.can_transition_task(TaskState.RUNNING, TaskState.FAILED))
        self.assertTrue(StateValidator.can_transition_task(TaskState.RUNNING, TaskState.CANCELLED))

        # Invalid Task transitions
        self.assertFalse(StateValidator.can_transition_task(TaskState.COMPLETED, TaskState.RUNNING))
        self.assertFalse(StateValidator.can_transition_task(TaskState.CANCELLED, TaskState.RUNNING))

        with self.assertRaises(TaskStateError):
            StateValidator.validate_task_transition(TaskState.COMPLETED, TaskState.RUNNING, "t1")

    def test_05_max_steps_enforcement(self):
        """Verify creating a task with more than MAX_STEPS raises ValueError."""
        excessive_steps = [
            Step(step_id=f"s{i}", task_id="", sequence=i, action="get_volume")
            for i in range(MAX_STEPS + 2)
        ]
        with self.assertRaises(ValueError):
            self.engine.create_task(goal="Too many steps", steps=excessive_steps)

    def test_06_linear_execution_flow(self):
        """Verify tasks execute steps sequentially from Step 1 to Step N."""
        steps = [
            Step(step_id="s1", task_id="", sequence=1, action="get_volume"),
            Step(step_id="s2", task_id="", sequence=2, action="get_brightness")
        ]
        task = self.engine.create_task(goal="Volume and Brightness", steps=steps)
        self.assertEqual(task.status, TaskState.CREATED)

        async def run():
            return await self.engine.execute_task(task)

        completed_task = asyncio.run(run())
        self.assertEqual(completed_task.status, TaskState.COMPLETED)
        self.assertEqual(completed_task.current_step, 2)
        self.assertEqual(completed_task.steps[0].status, StepState.SUCCEEDED)
        self.assertEqual(completed_task.steps[1].status, StepState.SUCCEEDED)

    def test_07_max_concurrent_tasks_enforcement(self):
        """Verify TaskEngine enforces MAX_CONCURRENT_TASKS = 1."""
        step = Step(step_id="s1", task_id="", sequence=1, action="get_volume")
        task1 = self.engine.create_task(goal="Task 1", steps=[step])
        task2 = self.engine.create_task(goal="Task 2", steps=[step])

        # Manually set active task ID to simulate ongoing execution
        self.engine._active_task_id = task1.task_id

        async def run_conflict():
            await self.engine.execute_task(task2)

        with self.assertRaises(RuntimeError):
            asyncio.run(run_conflict())

        self.engine._active_task_id = None

    def test_08_cancellation_between_steps(self):
        """Verify task cancellation halts execution cleanly between steps."""
        steps = [
            Step(step_id="s1", task_id="", sequence=1, action="get_volume"),
            Step(step_id="s2", task_id="", sequence=2, action="get_brightness")
        ]
        task = self.engine.create_task(goal="Cancel Test", steps=steps)

        # Cancel task before step 2 runs
        self.engine.cancel_task(task.task_id, reason="User cancelled early")

        async def run():
            return await self.engine.execute_task(task)

        cancelled_task = asyncio.run(run())
        self.assertEqual(cancelled_task.status, TaskState.CANCELLED)

    def test_09_crash_recovery_marks_interrupted(self):
        """Verify crash recovery transitions RUNNING tasks to INTERRUPTED."""
        step = Step(step_id="s1", task_id="t_crash", sequence=1, action="get_volume", status=StepState.RUNNING)
        running_task = Task(task_id="t_crash", goal="Crash task", status=TaskState.RUNNING, steps=[step])
        self.store.save_task(running_task)

        # Simulate engine restart by running recovery
        recovered_count = self.store.recover_interrupted_tasks()
        self.assertEqual(recovered_count, 1)

        reloaded = self.store.get_task("t_crash")
        self.assertEqual(reloaded.status, TaskState.INTERRUPTED)
        self.assertEqual(reloaded.steps[0].status, StepState.INTERRUPTED)

    def test_10_retry_success_after_initial_failure(self):
        """Verify StepRunner retries when verification initially fails."""
        # Create a mock action in unified_tool_registry that succeeds on 2nd attempt
        attempt_count = 0
        def flaky_action():
            nonlocal attempt_count
            attempt_count += 1
            if attempt_count == 1:
                return {"status": "error", "message": "temporary glitch"}
            return {"status": "success", "message": "all good"}

        unified_tool_registry._functions["flaky_test_action"] = flaky_action

        step = Step(
            step_id="s_flaky",
            task_id="t_flaky",
            sequence=1,
            action="flaky_test_action",
            max_attempts=3,
            retryable=True
        )
        task = Task(task_id="t_flaky", goal="Flaky test", steps=[step])
        self.store.save_task(task)

        async def run():
            return await StepRunner.execute_step(task, step, is_cancelled=lambda: False)

        success, res_step = asyncio.run(run())
        self.assertTrue(success)
        self.assertEqual(res_step.status, StepState.SUCCEEDED)
        self.assertEqual(res_step.attempts, 2)

    def test_11_retry_exhaustion_marks_step_failed(self):
        """Verify step fails after max_attempts are exhausted."""
        def always_failing():
            return {"status": "error", "message": "persistent error"}

        unified_tool_registry._functions["failing_test_action"] = always_failing

        step = Step(
            step_id="s_fail",
            task_id="t_fail",
            sequence=1,
            action="failing_test_action",
            max_attempts=2,
            retryable=True
        )
        task = Task(task_id="t_fail", goal="Failing test", steps=[step])
        self.store.save_task(task)

        async def run():
            return await StepRunner.execute_step(task, step, is_cancelled=lambda: False)

        success, res_step = asyncio.run(run())
        self.assertFalse(success)
        self.assertEqual(res_step.status, StepState.FAILED)
        self.assertEqual(res_step.attempts, 2)

    def test_12_secret_masking_in_storage(self):
        """Verify API keys and tokens are masked when stored in TaskStore."""
        secret_arg = "AIzaSyD1234567890abcdef1234567890abcdef"
        step = Step(
            step_id="s_sec",
            task_id="t_sec",
            sequence=1,
            action="get_volume",
            arguments={"api_key": secret_arg}
        )
        task = Task(task_id="t_sec", goal=f"Use {secret_arg}", steps=[step])
        self.store.save_task(task)

        # Read raw json from disk
        with open(self.test_store_path, "r", encoding="utf-8") as f:
            raw_text = f.read()

        self.assertNotIn(secret_arg, raw_text)
        self.assertIn("MASKED", raw_text)

    def test_13_idempotency_key_format(self):
        """Verify idempotency key follows task_id:step_id:attempt format."""
        step = Step(step_id="s1", task_id="task_abc", sequence=1, action="get_volume", attempts=1)
        step.update_idempotency_key()
        self.assertEqual(step.idempotency_key, "task_abc:s1:attempt_1")


if __name__ == "__main__":
    unittest.main()
