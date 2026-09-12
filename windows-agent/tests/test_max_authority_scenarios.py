"""
End-to-End Scenarios Test Suite (Scenarios A through L)
Validates the complete MAX Master Multi-Agent Ecosystem Authority Model.
"""

import os
import shutil
import tempfile
import asyncio
import pytest
from unittest.mock import MagicMock, AsyncMock, patch

from protocol.task_envelope import (
    TaskEnvelope,
    TaskResult,
    TaskStatus,
    TaskEventType,
    GranularPermission
)
from core.canonical_task_store import CanonicalTaskStore
from core.state_database import StateDatabase
from core.task_classifier import task_classifier, TaskTier
from core.security_policy import SecurityPolicy
from core.verification_engine import VerificationEngine
from core.process_supervisor import ProcessSupervisor
from core.antigravity_bridge import AntigravityBridge
from core.worktree_isolation import WorktreeManager
from orchestrator.max_task_authority import MAXTaskAuthority


@pytest.fixture
def test_authority():
    temp_dir = tempfile.mkdtemp()
    db_path = os.path.join(temp_dir, "test_state.db")
    state_db = StateDatabase(db_path=db_path)
    store = CanonicalTaskStore(state_db=state_db)
    wt_mgr = WorktreeManager(storage_dir=os.path.join(temp_dir, "worktrees"), max_active=5)
    supervisor = ProcessSupervisor()
    bridge = AntigravityBridge(supervisor=supervisor)
    verifier = VerificationEngine()

    authority = MAXTaskAuthority(
        store=store,
        bridge=bridge,
        wt_manager=wt_mgr,
        verifier=verifier,
        supervisor=supervisor
    )

    yield authority
    shutil.rmtree(temp_dir, ignore_errors=True)


# ─────────────────────────────────────────────────────────────────────────────
# Scenario A — Simple Windows Execution
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_scenario_a_simple_windows(test_authority):
    """Scenario A: 'Open Paint' -> MAX -> Windows Agent -> completed (No Antigravity)."""
    res = test_authority.create_root_task(goal="Open Paint")
    assert res["is_duplicate"] is False
    assert res["worker"] == "windows_agent"
    assert res["tier"] in (TaskTier.TIER_1_DIRECT.value, TaskTier.TIER_2_PLATFORM_EXEC.value)

    task_id = res["task_id"]
    with patch.object(test_authority.verifier, "verify_task", return_value={"verified": True}):
        exec_res = await test_authority.execute_task(task_id)

    assert exec_res.status == TaskStatus.COMPLETED
    assert exec_res.verified_success is True

    record = test_authority.store.get_task_result(task_id)
    assert record.status == TaskStatus.COMPLETED.value
    assert record.verified_success is True


# ─────────────────────────────────────────────────────────────────────────────
# Scenario B — GUI Drawing
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_scenario_b_gui_drawing(test_authority):
    """Scenario B: 'Draw a blue triangle in Paint' -> Windows Agent -> Paint -> verified."""
    res = test_authority.create_root_task(goal="Draw a blue triangle in Paint")
    assert res["worker"] == "windows_agent"
    task_id = res["task_id"]

    with patch.object(test_authority.verifier, "verify_task", return_value={"verified": True, "method": "ui_state"}):
        exec_res = await test_authority.execute_task(task_id)

    assert exec_res.status == TaskStatus.COMPLETED
    assert exec_res.verified_success is True


# ─────────────────────────────────────────────────────────────────────────────
# Scenario C — Developer Task
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_scenario_c_developer_task(test_authority):
    """Scenario C: 'Fix Paint brush click bug' -> Antigravity -> isolated worktree -> verify -> merge approval."""
    res = test_authority.create_root_task(
        goal="Fix the Paint brush click bug and run tests",
        permissions=[
            GranularPermission.WORKSPACE_READ,
            GranularPermission.WORKSPACE_WRITE,
            GranularPermission.TEST_EXECUTION
        ]
    )
    assert res["worker"] == "antigravity"
    assert res["tier"] == TaskTier.TIER_4_DEVELOPER.value
    task_id = res["task_id"]

    # Mock Antigravity bridge execution returning claimed success
    with patch.object(test_authority.bridge, "run_task", new_callable=AsyncMock) as mock_run:
        mock_run.return_value = TaskResult(
            task_id=task_id,
            status=TaskStatus.COMPLETED,
            claimed_success=True,
            summary="Fixed brush click event in paint_automation.py"
        )
        with patch.object(test_authority.verifier, "verify_task", return_value={"verified": True}):
            exec_res = await test_authority.execute_task(task_id)

    assert exec_res.status == TaskStatus.COMPLETED
    assert exec_res.verified_success is True

    # Invariant: NO AUTOMATIC MERGE -> Antigravity cannot self-merge
    self_merge = test_authority.worktree_mgr.authorize_merge(
        task_id=task_id,
        approved_by="antigravity",
        verification_passed=True
    )
    assert self_merge["success"] is False

    # Explicit user authorization succeeds
    user_merge = test_authority.worktree_mgr.authorize_merge(
        task_id=task_id,
        approved_by="Yaswanth_MAX",
        verification_passed=True
    )
    assert user_merge["success"] is True


# ─────────────────────────────────────────────────────────────────────────────
# Scenario D — Hybrid Execution
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_scenario_d_hybrid(test_authority):
    """Scenario D: 'Fix Paint automation and verify by actually drawing' -> Hybrid tier."""
    res = test_authority.create_root_task(goal="Fix Paint automation and verify by actually drawing")
    assert res["tier"] == TaskTier.TIER_5_HYBRID.value
    task_id = res["task_id"]

    with patch.object(test_authority.bridge, "run_task", new_callable=AsyncMock) as mock_run:
        mock_run.return_value = TaskResult(
            task_id=task_id,
            status=TaskStatus.COMPLETED,
            claimed_success=True,
            summary="Code fixed and ready for runtime validation"
        )
        with patch.object(test_authority.verifier, "verify_task", return_value={"verified": True}):
            exec_res = await test_authority.execute_task(task_id)

    assert exec_res.status == TaskStatus.COMPLETED


# ─────────────────────────────────────────────────────────────────────────────
# Scenario E — Worker Escalation (Windows Agent -> MAX -> Antigravity)
# ─────────────────────────────────────────────────────────────────────────────
def test_scenario_e_escalation(test_authority):
    """Scenario E: Windows Agent requests escalation to Antigravity."""
    root_res = test_authority.create_root_task(
        goal="Automate Paint workflow",
        permissions=[GranularPermission.GUI_CONTROL, GranularPermission.WORKSPACE_READ]
    )
    root_id = root_res["task_id"]

    # Windows Agent discovers bug and requests escalation
    esc_res = test_authority.handle_escalation(
        requesting_task_id=root_id,
        escalation_reason="Underlying Paint automation code has a syntax error",
        required_worker="antigravity",
        required_goal="Fix syntax error in paint_automation.py",
        requested_permissions=[GranularPermission.WORKSPACE_READ]
    )

    assert esc_res["success"] is True
    child = esc_res["child_task"]
    assert child["parent_task_id"] == root_id
    assert child["root_task_id"] == root_id
    assert child["depth"] == 1
    assert child["worker"] == "antigravity"

    # Verify parent is in waiting_escalation
    parent_rec = test_authority.store.get_task_result(root_id)
    assert parent_rec.status == TaskStatus.WAITING_ESCALATION.value


# ─────────────────────────────────────────────────────────────────────────────
# Scenario F — Duplicate Voice Task Idempotency
# ─────────────────────────────────────────────────────────────────────────────
def test_scenario_f_duplicate_voice(test_authority):
    """Scenario F: Duplicate voice task is deduplicated to single active root task."""
    res1 = test_authority.create_root_task(
        goal="Refactor state database models",
        idempotency_policy="UNIQUE"
    )
    assert res1["is_duplicate"] is False

    # Second submission with identical goal and context
    res2 = test_authority.create_root_task(
        goal="Refactor state database models",
        idempotency_policy="UNIQUE"
    )
    assert res2["is_duplicate"] is True
    assert res2["task_id"] == res1["task_id"]


# ─────────────────────────────────────────────────────────────────────────────
# Scenario G — Cancellation Propagation
# ─────────────────────────────────────────────────────────────────────────────
def test_scenario_g_cancellation(test_authority):
    """Scenario G: 'Stop' triggers canonical cancellation and terminates process trees."""
    res = test_authority.create_root_task(goal="Build large feature")
    task_id = res["task_id"]

    cancel_res = test_authority.cancel_task(task_id, reason="User said Stop")
    assert cancel_res["success"] is True
    assert cancel_res["status"] == "cancelled"

    rec = test_authority.store.get_task_result(task_id)
    assert rec.status == TaskStatus.CANCELLED.value


# ─────────────────────────────────────────────────────────────────────────────
# Scenario H — Dangerous Action & Confirmation Policy
# ─────────────────────────────────────────────────────────────────────────────
def test_scenario_h_dangerous_action_confirmation(test_authority):
    """Scenario H: Dangerous action requires confirmation; denial cancels task."""
    res = test_authority.create_root_task(goal="Delete all files in temp folder")
    task_id = res["task_id"]

    # Denial response
    deny_res = test_authority.handle_confirmation(task_id, approved=False)
    assert deny_res["status"] == "cancelled"

    rec = test_authority.store.get_task_result(task_id)
    assert rec.status == TaskStatus.CANCELLED.value


# ─────────────────────────────────────────────────────────────────────────────
# Scenario I — False Success Rejection
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_scenario_i_false_success_rejection(test_authority):
    """Scenario I: Worker claims completed, but tests fail -> status is VERIFICATION_FAILED."""
    res = test_authority.create_root_task(goal="Fix database bug")
    task_id = res["task_id"]

    with patch.object(test_authority.bridge, "run_task", new_callable=AsyncMock) as mock_run:
        mock_run.return_value = TaskResult(
            task_id=task_id,
            status=TaskStatus.COMPLETED,
            claimed_success=True,
            summary="Applied code changes"
        )
        # Verifier returns False
        with patch.object(test_authority.verifier, "verify_task", return_value={"verified": False, "error": "2 unit tests failed"}):
            exec_res = await test_authority.execute_task(task_id)

    assert exec_res.status == TaskStatus.VERIFICATION_FAILED
    assert exec_res.verified_success is False

    rec = test_authority.store.get_task_result(task_id)
    assert rec.status == TaskStatus.VERIFICATION_FAILED.value
    assert rec.verified_success is False


# ─────────────────────────────────────────────────────────────────────────────
# Scenario J — Auth Expired
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_scenario_j_auth_expired(test_authority):
    """Scenario J: Auth expired error is parsed into AUTH_EXPIRED status."""
    res = test_authority.create_root_task(goal="Update cloud repository")
    task_id = res["task_id"]

    with patch.object(test_authority.bridge, "run_task", new_callable=AsyncMock) as mock_run:
        mock_run.return_value = TaskResult(
            task_id=task_id,
            status=TaskStatus.AUTH_EXPIRED,
            claimed_success=False,
            error={"code": "AUTH_EXPIRED", "message": "API token has expired"}
        )
        exec_res = await test_authority.execute_task(task_id)

    assert exec_res.status == TaskStatus.AUTH_EXPIRED
    rec = test_authority.store.get_task_result(task_id)
    assert rec.status == TaskStatus.AUTH_EXPIRED.value


# ─────────────────────────────────────────────────────────────────────────────
# Scenario K — Quota Exceeded
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_scenario_k_quota_exceeded(test_authority):
    """Scenario K: Quota exceeded error halts unbounded retry."""
    res = test_authority.create_root_task(goal="Generate large codebase")
    task_id = res["task_id"]

    with patch.object(test_authority.bridge, "run_task", new_callable=AsyncMock) as mock_run:
        mock_run.return_value = TaskResult(
            task_id=task_id,
            status=TaskStatus.QUOTA_EXCEEDED,
            claimed_success=False,
            error={"code": "QUOTA_EXCEEDED", "message": "API Rate limit exceeded"}
        )
        exec_res = await test_authority.execute_task(task_id)

    assert exec_res.status == TaskStatus.QUOTA_EXCEEDED
    rec = test_authority.store.get_task_result(task_id)
    assert rec.status == TaskStatus.QUOTA_EXCEEDED.value


# ─────────────────────────────────────────────────────────────────────────────
# Scenario L — Controlled Merge Authorization
# ─────────────────────────────────────────────────────────────────────────────
def test_scenario_l_merge_authorization(test_authority):
    """Scenario L: Code changes require explicit MAX/User approval; unverified code blocked."""
    root_res = test_authority.create_root_task(goal="Implement secure logging")
    task_id = root_res["task_id"]

    # Create worktree
    wt = test_authority.worktree_mgr.create_worktree(task_id)

    # 1. Unverified merge attempt fails
    res_unverified = test_authority.worktree_mgr.authorize_merge(
        task_id=task_id,
        approved_by="MAX_USER",
        require_verification=True,
        verification_passed=False
    )
    assert res_unverified["success"] is False

    # 2. Antigravity self-authorization fails
    res_self = test_authority.worktree_mgr.authorize_merge(
        task_id=task_id,
        approved_by="antigravity",
        require_verification=True,
        verification_passed=True
    )
    assert res_self["success"] is False

    # 3. User authorized + verified succeeds
    res_ok = test_authority.worktree_mgr.authorize_merge(
        task_id=task_id,
        approved_by="Yaswanth_MAX",
        require_verification=True,
        verification_passed=True
    )
    assert res_ok["success"] is True
    assert res_ok["status"] == "merged"
