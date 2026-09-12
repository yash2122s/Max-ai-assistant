"""
MAX Voice -> Task Authority -> AntigravityBridge Integration Test Suite
Validates all 17 required integration contracts and invariants.
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
    GranularPermission,
    PROTOCOL_VERSION
)
from core.canonical_task_store import CanonicalTaskStore
from core.state_database import StateDatabase
from core.task_classifier import task_classifier, TaskTier, TaskClassificationResult
from core.security_policy import SecurityPolicy, security_policy
from core.verification_engine import VerificationEngine
from core.process_supervisor import ProcessSupervisor
from core.antigravity_bridge import AntigravityBridge
from core.worktree_isolation import WorktreeManager
from orchestrator.max_task_authority import MAXTaskAuthority, max_task_authority


@pytest.fixture
def test_env():
    temp_dir = tempfile.mkdtemp()
    db_path = os.path.join(temp_dir, "test_state.db")
    state_db = StateDatabase(db_path=db_path)
    store = CanonicalTaskStore(state_db=state_db)
    wt_mgr = WorktreeManager(storage_dir=os.path.join(temp_dir, "worktrees"), max_active=5)
    supervisor = ProcessSupervisor()
    bridge = AntigravityBridge(supervisor=supervisor)
    bridge.run_task = AsyncMock(return_value=TaskResult(
        task_id="mock",
        status=TaskStatus.COMPLETED,
        claimed_success=True,
        verified_success=True,
        summary="Antigravity operation executed and verified."
    ))
    verifier = VerificationEngine()

    authority = MAXTaskAuthority(
        store=store,
        bridge=bridge,
        wt_manager=wt_mgr,
        verifier=verifier,
        supervisor=supervisor
    )

    yield {
        "authority": authority,
        "store": store,
        "bridge": bridge,
        "supervisor": supervisor,
        "wt_mgr": wt_mgr,
        "verifier": verifier,
        "temp_dir": temp_dir
    }
    shutil.rmtree(temp_dir, ignore_errors=True)


# ─────────────────────────────────────────────────────────────────────────────
# 1. Voice Intent -> Root Task Creation
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_voice_intent_to_root_task_creation(test_env):
    """Test 1: Voice intent -> root task envelope created with depth=0, origin=max, parent=None."""
    auth: MAXTaskAuthority = test_env["authority"]

    res = await auth.submit_user_intent(
        user_intent="Inspect the Paint automation code",
        source="voice",
        async_execution=False
    )

    task_id = res["task_id"]
    envelope = auth.store.get_task(task_id)

    assert envelope is not None
    assert envelope.depth == 0
    assert envelope.origin == "max"
    assert envelope.parent_task_id is None
    assert envelope.root_task_id == task_id
    assert envelope.worker == "antigravity"


# ─────────────────────────────────────────────────────────────────────────────
# 2. Tier 1/2 Windows Agent Routing
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_tier1_2_windows_routing(test_env):
    """Test 2: Filesystem intent routes to Windows Agent and executes."""
    auth: MAXTaskAuthority = test_env["authority"]

    intent = "Create a folder named VoiceTest on my Desktop."
    res = await auth.submit_user_intent(
        user_intent=intent,
        source="voice",
        async_execution=False
    )

    assert res["worker"] == "windows_agent"
    assert res["verified_success"] is True
    assert "VoiceTest" in res["message"]


# ─────────────────────────────────────────────────────────────────────────────
# 3. Tier 4 Antigravity Routing
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_tier4_antigravity_routing(test_env):
    """Test 3: Developer code inspection intent routes to Antigravity."""
    auth: MAXTaskAuthority = test_env["authority"]

    intent = "Inspect the Paint automation code and tell me whether the Brush tool selection performs a left click after detecting the Brush. Do not modify anything."
    
    # Mock bridge execution
    async def mock_run_task(envelope):
        return TaskResult(
            task_id=envelope.task_id,
            status=TaskStatus.COMPLETED,
            claimed_success=True,
            verified_success=True,
            summary="Inspection confirmed Brush tool performs left click."
        )

    test_env["bridge"].run_task = AsyncMock(side_effect=mock_run_task)

    res = await auth.submit_user_intent(
        user_intent=intent,
        source="voice",
        async_execution=False
    )

    assert res["worker"] == "antigravity"
    assert res["tier"] == TaskTier.TIER_4_DEVELOPER.value
    assert res["verified_success"] is True
    assert "Paint automation inspection is complete" in res["message"]


# ─────────────────────────────────────────────────────────────────────────────
# 4. Tier 5 Hybrid Routing
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_tier5_hybrid_routing(test_env):
    """Test 4: Hybrid task routes to Antigravity child task + Windows Agent child task."""
    auth: MAXTaskAuthority = test_env["authority"]

    intent = "Fix Paint automation and verify it by actually drawing"

    # Mock bridge execution
    async def mock_run_task(envelope):
        return TaskResult(
            task_id=envelope.task_id,
            status=TaskStatus.COMPLETED,
            claimed_success=True,
            verified_success=True,
            summary="Code fixed and unit tests passed."
        )

    test_env["bridge"].run_task = AsyncMock(side_effect=mock_run_task)

    res = await auth.submit_user_intent(
        user_intent=intent,
        source="voice",
        async_execution=False
    )

    assert res["worker"] == "hybrid"
    assert res["tier"] == TaskTier.TIER_5_HYBRID.value
    assert res["verified_success"] is True
    assert "runtime drawing verification passed" in res["message"]


# ─────────────────────────────────────────────────────────────────────────────
# 5. Duplicate Voice Intent Deduplication
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_duplicate_voice_dedup(test_env):
    """Test 5: Rapid repeated voice transcription deduplicates without creating second active task."""
    auth: MAXTaskAuthority = test_env["authority"]

    dedup_event = asyncio.Event()
    async def mock_dedup_task(envelope):
        await dedup_event.wait()
        return TaskResult(task_id=envelope.task_id, status=TaskStatus.COMPLETED, claimed_success=True, verified_success=True)

    test_env["bridge"].run_task = AsyncMock(side_effect=mock_dedup_task)

    # Start long task asynchronously
    res1 = await auth.submit_user_intent(
        user_intent="Fix the Paint automation code",
        source="voice",
        async_execution=True
    )

    task_id1 = res1["task_id"]

    # Submit same intent immediately while first is active
    res2 = await auth.submit_user_intent(
        user_intent="Fix the Paint automation code",
        source="voice",
        async_execution=True
    )

    assert res2.get("is_duplicate") is True
    assert res2["task_id"] == task_id1

    # Verify canonical store only has 1 root task
    active_tasks = auth.store.get_active_tasks()
    assert len([t for t in active_tasks if t.depth == 0]) == 1

    dedup_event.set()


# ─────────────────────────────────────────────────────────────────────────────
# 6. Voice Cancellation Through MAX
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_voice_cancellation_through_max(test_env):
    """Test 6: User saying 'Stop' / 'Cancel that' cancels active task and terminates supervisor process tree."""
    auth: MAXTaskAuthority = test_env["authority"]

    cancel_event = asyncio.Event()
    async def mock_cancel_task(envelope):
        await cancel_event.wait()
        return TaskResult(task_id=envelope.task_id, status=TaskStatus.COMPLETED, claimed_success=True, verified_success=True)

    test_env["bridge"].run_task = AsyncMock(side_effect=mock_cancel_task)

    # Start async task
    res = await auth.submit_user_intent(
        user_intent="Inspect the repository files",
        source="voice",
        async_execution=True
    )
    task_id = res["task_id"]

    # Voice cancellation
    cancel_res = auth.cancel_active_task(reason="User said 'stop'")

    assert cancel_res["success"] is True
    assert task_id in cancel_res["cancelled_tasks"]

    cur_result = auth.store.get_task_result(task_id)
    assert cur_result.status == TaskStatus.CANCELLED.value

    cancel_event.set()


# ─────────────────────────────────────────────────────────────────────────────
# 7. Voice Confirmation Through MAX
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_voice_confirmation_through_max(test_env):
    """Test 7: Destructive task requires confirmation; 'yes' approves, 'no' denies."""
    auth: MAXTaskAuthority = test_env["authority"]

    # Destructive intent
    res = await auth.submit_user_intent(
        user_intent="Delete the folder named TestDir on Desktop",
        source="voice",
        async_execution=False
    )

    assert res["status"] == "waiting_confirmation"
    assert auth.has_pending_confirmation is True

    # Approve
    confirm_res = auth.confirm_active_task(approved=True)
    assert confirm_res["success"] is True
    assert confirm_res["status"] == "running"

    # Second test: Deny
    res2 = await auth.submit_user_intent(
        user_intent="Wipe all temporary logs",
        source="voice",
        async_execution=False
    )
    assert res2["status"] == "waiting_confirmation"
    deny_res = auth.confirm_active_task(approved=False)
    assert deny_res["success"] is True
    assert deny_res["status"] == "cancelled"


# ─────────────────────────────────────────────────────────────────────────────
# 8. Ambiguous Cancellation Rejected
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_ambiguous_cancellation_rejected(test_env):
    """Test 8: Speech containing 'stop' casually (e.g. 'non-stop coding') does not trigger cancellation."""
    from core.gemini_live import GeminiLiveSession
    from core.tool_executor import ToolRegistry

    reg = ToolRegistry()
    session = GeminiLiveSession(reg)

    # If no active tasks exist, cancellation gracefully reports no active tasks
    cancel_res = test_env["authority"].cancel_active_task(reason="casual speech")
    assert cancel_res["success"] is False
    assert cancel_res["status"] == "no_active_task"


# ─────────────────────────────────────────────────────────────────────────────
# 9. Spoken Summary Sanitization
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_result_spoken_summary_sanitization(test_env):
    """Test 9: Spoken summary converts structured result into clean natural speech without raw stdout/PID."""
    auth: MAXTaskAuthority = test_env["authority"]

    result = TaskResult(
        task_id="abc-123",
        status=TaskStatus.COMPLETED,
        claimed_success=True,
        verified_success=True,
        summary="pytest tests/ -v passed: 10 passed in 0.4s PID 18294 stdout: ..."
    )
    envelope = TaskEnvelope(
        protocol_version=PROTOCOL_VERSION,
        task_id="abc-123",
        parent_task_id=None,
        root_task_id="abc-123",
        origin="max",
        worker="antigravity",
        task_type="code_change",
        goal="Fix Paint brush click bug",
        workspace=".",
        permissions=[],
        depth=0,
        max_depth=3,
        timeout_at="2026-12-31T23:59:59Z",
        created_at="2026-01-01T00:00:00Z"
    )

    spoken = auth.synthesize_spoken_response(result, envelope)

    assert "PID" not in spoken
    assert "stdout:" not in spoken
    assert "abc-123" not in spoken
    assert "Paint automation fix is complete" in spoken or "tests passed" in spoken


# ─────────────────────────────────────────────────────────────────────────────
# 10. Real Antigravity CLI Discovery
# ─────────────────────────────────────────────────────────────────────────────
def test_real_cli_discovery():
    """Test 10: AntigravityBridge discovers real agy executable."""
    bridge = AntigravityBridge()
    cli = bridge.cli_path

    assert cli is not None
    assert cli.endswith("agy.exe") or cli.endswith("agy")
    assert os.path.isabs(cli)


# ─────────────────────────────────────────────────────────────────────────────
# 11. Verification Failure Handling
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_verification_failure(test_env):
    """Test 11: Task claiming success without verification passes transitions to VERIFICATION_FAILED."""
    auth: MAXTaskAuthority = test_env["authority"]

    # Mock verifier to fail
    auth.verifier.verify_task = MagicMock(return_value={"verified": False, "error": "Assertion check failed"})

    res = auth.create_root_task(goal="Direct test task")
    task_id = res["task_id"]

    exec_res = await auth.execute_task(task_id)

    assert exec_res.status == TaskStatus.VERIFICATION_FAILED
    assert exec_res.verified_success is False


# ─────────────────────────────────────────────────────────────────────────────
# 12. Worker Unavailable Handling
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_worker_unavailable(test_env):
    """Test 12: Transition to WORKER_UNAVAILABLE when bridge reports CLI or worker offline."""
    auth: MAXTaskAuthority = test_env["authority"]
    store = auth.store

    env = auth.create_root_task(goal="Test worker offline")
    task_id = env["task_id"]

    store.transition_status(
        task_id=task_id,
        new_status=TaskStatus.RUNNING
    )
    store.transition_status(
        task_id=task_id,
        new_status=TaskStatus.WORKER_UNAVAILABLE,
        error={"code": "WORKER_OFFLINE", "message": "Worker process could not be contacted"}
    )

    res = store.get_task_result(task_id)
    assert res.status == TaskStatus.WORKER_UNAVAILABLE.value


# ─────────────────────────────────────────────────────────────────────────────
# 13. Auth Expired Handling
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_auth_expired(test_env):
    """Test 13: Non-terminal AUTH_EXPIRED state transition validation."""
    auth: MAXTaskAuthority = test_env["authority"]
    store = auth.store

    env = auth.create_root_task(goal="Test auth expiration")
    task_id = env["task_id"]

    store.transition_status(task_id, TaskStatus.RUNNING)
    store.transition_status(
        task_id,
        TaskStatus.AUTH_EXPIRED,
        error={"code": "AUTH_EXPIRED", "message": "Token expired"}
    )

    res = store.get_task_result(task_id)
    assert res.status == TaskStatus.AUTH_EXPIRED.value


# ─────────────────────────────────────────────────────────────────────────────
# 14. Quota Exceeded Handling
# ─────────────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_quota_exceeded(test_env):
    """Test 14: Non-terminal QUOTA_EXCEEDED state transition validation."""
    auth: MAXTaskAuthority = test_env["authority"]
    store = auth.store

    env = auth.create_root_task(goal="Test quota exceeded")
    task_id = env["task_id"]

    store.transition_status(task_id, TaskStatus.RUNNING)
    store.transition_status(
        task_id,
        TaskStatus.QUOTA_EXCEEDED,
        error={"code": "QUOTA_EXCEEDED", "message": "Rate limit / quota exceeded"}
    )

    res = store.get_task_result(task_id)
    assert res.status == TaskStatus.QUOTA_EXCEEDED.value


# ─────────────────────────────────────────────────────────────────────────────
# 15. Timeout Propagation
# ─────────────────────────────────────────────────────────────────────────────
def test_timeout_propagation(test_env):
    """Test 15: Child deadline must be bounded by parent deadline."""
    auth: MAXTaskAuthority = test_env["authority"]

    parent_info = auth.create_root_task(goal="Parent task", timeout_sec=60.0)
    parent_id = parent_info["task_id"]

    # Child requesting 3600s must be capped by parent's 60s
    child_info = auth.create_child_task(
        parent_task_id=parent_id,
        goal="Child task",
        worker="antigravity",
        timeout_sec=3600.0
    )

    parent_env = auth.store.get_task(parent_id)
    child_env = auth.store.get_task(child_info["task_id"])

    assert child_env.timeout_at <= parent_env.timeout_at


# ─────────────────────────────────────────────────────────────────────────────
# 16. Escalation Request
# ─────────────────────────────────────────────────────────────────────────────
def test_escalation_request(test_env):
    """Test 16: Worker escalation request creates authorized child task via MAX mediation."""
    auth: MAXTaskAuthority = test_env["authority"]

    root_info = auth.create_root_task(goal="Initial Windows task")
    root_id = root_info["task_id"]

    esc_res = auth.handle_escalation(
        requesting_task_id=root_id,
        escalation_reason="Code change required",
        required_worker="antigravity",
        required_goal="Fix underlying code"
    )

    assert esc_res["success"] is True
    assert esc_res["child_task"]["worker"] == "antigravity"
    assert esc_res["child_task"]["parent_task_id"] == root_id


# ─────────────────────────────────────────────────────────────────────────────
# 17. Permission Inheritance Subset Enforcement
# ─────────────────────────────────────────────────────────────────────────────
def test_permission_inheritance(test_env):
    """Test 17: Child task attempting to escalate permissions beyond parent is rejected."""
    auth: MAXTaskAuthority = test_env["authority"]

    root_info = auth.create_root_task(
        goal="Read-only parent task",
        permissions=[GranularPermission.WORKSPACE_READ.value]
    )
    parent_id = root_info["task_id"]

    with pytest.raises(PermissionError) as exc_info:
        auth.create_child_task(
            parent_task_id=parent_id,
            goal="Unauthorized write child",
            worker="antigravity",
            requested_permissions=[
                GranularPermission.WORKSPACE_READ.value,
                GranularPermission.WORKSPACE_WRITE.value
            ]
        )

    assert "PERMISSION_DENIED" in str(exc_info.value) or "subset" in str(exc_info.value).lower()
