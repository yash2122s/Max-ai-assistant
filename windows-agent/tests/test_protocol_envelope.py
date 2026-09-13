"""
Unit tests for Task Protocol v1.0 (TaskEnvelope, TaskResult, TaskEvent).
"""

import pytest
from protocol.task_envelope import (
    TaskEnvelope,
    TaskResult,
    TaskEvent,
    TaskStatus,
    TaskEventType,
    GranularPermission,
    PROTOCOL_VERSION
)


class TestTaskProtocolEnvelope:

    def test_task_envelope_creation_defaults(self):
        env = TaskEnvelope(goal="Open Paint and draw triangle")
        assert env.protocol_version == "1.0"
        assert env.task_id != ""
        assert env.root_task_id == env.task_id
        assert env.depth == 0
        assert env.max_depth == 3
        assert env.origin == "max"
        assert env.worker == "windows_agent"

    def test_task_envelope_serialization_roundtrip(self):
        env = TaskEnvelope(
            goal="Fix Paint brush bug",
            worker="antigravity",
            task_type="code_change",
            permissions=["WORKSPACE_READ", "WORKSPACE_WRITE", "TEST_EXECUTION"],
            depth=1,
            requires_confirmation=True
        )
        json_str = env.to_json()
        restored = TaskEnvelope.from_json(json_str)

        assert restored.task_id == env.task_id
        assert restored.goal == env.goal
        assert restored.worker == "antigravity"
        assert restored.permissions == ["WORKSPACE_READ", "WORKSPACE_WRITE", "TEST_EXECUTION"]
        assert restored.requires_confirmation is True

    def test_task_envelope_rejects_unrecognized_permission(self):
        with pytest.raises(ValueError, match="Unrecognized permission"):
            TaskEnvelope(
                goal="Hack system",
                permissions=["INVALID_SUPER_USER_PRIVILEGE"]
            )

    def test_task_envelope_depth_overflow_rejected(self):
        with pytest.raises(ValueError, match="exceeds maximum delegation depth"):
            TaskEnvelope(
                goal="Too deep",
                depth=4,
                max_depth=3
            )

    def test_child_creation_valid_subset_permissions(self):
        parent = TaskEnvelope(
            goal="Refactor agent",
            permissions=["WORKSPACE_READ", "WORKSPACE_WRITE", "TEST_EXECUTION", "GIT_READ"],
            depth=0
        )
        child = parent.create_child(
            worker="antigravity",
            task_type="code_change",
            goal="Run pytest suite",
            permissions=["WORKSPACE_READ", "TEST_EXECUTION"]
        )
        assert child.parent_task_id == parent.task_id
        assert child.root_task_id == parent.root_task_id
        assert child.depth == 1
        assert set(child.permissions) == {"WORKSPACE_READ", "TEST_EXECUTION"}

    def test_child_creation_escalation_rejected(self):
        parent = TaskEnvelope(
            goal="Read only inspection",
            permissions=["WORKSPACE_READ"],
            depth=0
        )
        # Attempting to grant WORKSPACE_WRITE to child
        with pytest.raises(PermissionError, match="Permission escalation rejected"):
            parent.create_child(
                worker="antigravity",
                task_type="code_change",
                goal="Modify source files",
                permissions=["WORKSPACE_READ", "WORKSPACE_WRITE"]
            )

    def test_child_creation_depth_limit_enforced(self):
        root = TaskEnvelope(goal="Root task", depth=0, max_depth=2)
        child1 = root.create_child(worker="max_agent", task_type="reasoning", goal="Subplan")
        assert child1.depth == 1

        child2 = child1.create_child(worker="antigravity", task_type="code_change", goal="Execute")
        assert child2.depth == 2

        # depth 3 exceeds max_depth=2
        with pytest.raises(ValueError, match="max delegation depth"):
            child2.create_child(worker="windows_agent", task_type="gui_action", goal="Verify")


class TestTaskResultAndEvents:

    def test_task_result_status_validation(self):
        res = TaskResult(
            task_id="test-123",
            status=TaskStatus.COMPLETED.value,
            claimed_success=True,
            verified_success=True,
            summary="All tests passed."
        )
        assert res.status == "completed"
        assert res.verified_success is True

        with pytest.raises(ValueError, match="Invalid task status"):
            TaskResult(task_id="test-123", status="SUPER_SUCCESSFUL")

    def test_task_event_validation(self):
        evt = TaskEvent(
            task_id="task-456",
            root_task_id="root-456",
            event_type=TaskEventType.TASK_STARTED.value,
            message="Worker initialized."
        )
        assert evt.event_type == "TASK_STARTED"

        with pytest.raises(ValueError, match="Invalid event type"):
            TaskEvent(
                task_id="task-456",
                root_task_id="root-456",
                event_type="UNRECOGNIZED_EVENT"
            )
