"""
Unit tests for CanonicalTaskStore (State Machine, Idempotency, Transitions, Cancellation).
"""

import os
import tempfile
import pytest
from unittest.mock import patch

from core.state_database import StateDatabase
from core.canonical_task_store import CanonicalTaskStore
from protocol.task_envelope import (
    TaskEnvelope,
    TaskStatus,
    TaskEventType
)


@pytest.fixture
def temp_store(tmp_path):
    db_file = tmp_path / "test_state.db"
    s_db = StateDatabase(db_path=str(db_file))
    store = CanonicalTaskStore(state_db=s_db)
    return store


class TestCanonicalTaskState:

    def test_task_creation_and_retrieval(self, temp_store):
        env = TaskEnvelope(
            goal="Launch Paint application",
            worker="windows_agent",
            task_type="direct_action"
        )
        saved_env, created = temp_store.create_task(env)
        assert created is True
        assert saved_env.task_id == env.task_id

        retrieved = temp_store.get_task(env.task_id)
        assert retrieved is not None
        assert retrieved.goal == "Launch Paint application"

    def test_idempotency_deduplication(self, temp_store):
        env1 = TaskEnvelope(
            goal="Fix Paint brush bug",
            worker="antigravity",
            task_type="code_change",
            workspace="D:/repo"
        )
        saved1, created1 = temp_store.create_task(env1)
        assert created1 is True

        # Second identical submission while first is still active (queued/running)
        env2 = TaskEnvelope(
            goal="Fix Paint brush bug",
            worker="antigravity",
            task_type="code_change",
            workspace="D:/repo"
        )
        saved2, created2 = temp_store.create_task(env2)
        assert created2 is False
        assert saved2.task_id == saved1.task_id

    def test_repeatable_task_policy(self, temp_store):
        env1 = TaskEnvelope(
            goal="Draw another circle",
            worker="windows_agent",
            task_type="gui_action"
        )
        saved1, created1 = temp_store.create_task(env1)
        assert created1 is True

        env2 = TaskEnvelope(
            goal="Draw another circle",
            worker="windows_agent",
            task_type="gui_action"
        )
        saved2, created2 = temp_store.create_task(env2)
        assert created2 is True
        assert saved2.task_id != saved1.task_id

    def test_valid_state_transitions(self, temp_store):
        env = TaskEnvelope(goal="Draw rectangle", worker="windows_agent")
        temp_store.create_task(env)

        # queued -> running
        res1 = temp_store.transition_status(env.task_id, TaskStatus.RUNNING)
        assert res1.status == "running"

        # running -> verifying
        res2 = temp_store.transition_status(env.task_id, TaskStatus.VERIFYING)
        assert res2.status == "verifying"

        # verifying -> completed (with verified_success=True)
        res3 = temp_store.transition_status(
            env.task_id,
            TaskStatus.COMPLETED,
            claimed_success=True,
            verified_success=True,
            result={"summary": "Rectangle drawn and verified."}
        )
        assert res3.status == "completed"
        assert res3.verified_success is True

    def test_invalid_state_transition_rejected(self, temp_store):
        env = TaskEnvelope(goal="Simple query", worker="windows_agent")
        temp_store.create_task(env)

        # Cannot jump from queued straight to completed
        with pytest.raises(ValueError, match="Invalid state transition"):
            temp_store.transition_status(env.task_id, TaskStatus.COMPLETED)

    def test_unverified_completion_diverted_to_verification_failed(self, temp_store):
        env = TaskEnvelope(goal="Complex task", worker="antigravity")
        temp_store.create_task(env)
        temp_store.transition_status(env.task_id, TaskStatus.RUNNING)
        temp_store.transition_status(env.task_id, TaskStatus.VERIFYING)

        # Attempting completed with verified_success=False
        res = temp_store.transition_status(
            env.task_id,
            TaskStatus.COMPLETED,
            claimed_success=True,
            verified_success=False
        )
        assert res.status == "verification_failed"

    def test_cancellation_flow(self, temp_store):
        env = TaskEnvelope(goal="Long compilation", worker="antigravity")
        temp_store.create_task(env)
        temp_store.transition_status(env.task_id, TaskStatus.RUNNING)

        ok = temp_store.request_cancellation(env.task_id, reason="User said stop")
        assert ok is True

        res = temp_store.get_task_result(env.task_id)
        assert res.status == "cancelled"

    def test_event_audit_logging(self, temp_store):
        env = TaskEnvelope(goal="Audit test task", worker="windows_agent")
        temp_store.create_task(env)
        temp_store.transition_status(env.task_id, TaskStatus.RUNNING)
        temp_store.transition_status(env.task_id, TaskStatus.VERIFYING)

        events = temp_store.get_task_events(env.task_id)
        assert len(events) >= 3
        types = [e.event_type for e in events]
        assert "TASK_CREATED" in types
        assert "TASK_STARTED" in types
        assert "TASK_VERIFICATION_STARTED" in types
