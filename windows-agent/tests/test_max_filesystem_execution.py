"""
MAX AI — Windows Task Execution Filesystem & Verification Test Suite
Verifies:
- Exact path resolution (nested directories preserved without flattening)
- User-requested content extraction and propagation (no hardcoded fallback)
- Strict external state verification (ActionVerifier, VerificationEngine, StepRunner)
- Failure gating (no step execution after failure, no false completed status)
- Path security traversal rejection
- Safe real Windows smoke test with cleanup
"""

import os
import sys
import shutil
import tempfile
import asyncio
import uuid
import pytest
from pathlib import Path
from unittest.mock import patch, MagicMock

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from core.verifier import action_verifier, ActionVerifier
from core.verification_engine import verification_engine, VerificationEngine
from tools import file_manager
from tools.file_manager import _resolve_user_path, create_folder, create_file, write_file, read_file, open_file
from orchestrator.max_task_authority import max_task_authority, MAXTaskAuthority
from protocol.task_envelope import TaskEnvelope, TaskStatus, GranularPermission
from engine.models import Task, Step, TaskState, StepState, VerificationPolicy, VerificationType
from engine.step_runner import StepRunner
from engine.task_engine import TaskEngine
from planner.fast_path import fast_path_resolver
from planner.task_factory import TaskFactory
from planner.schemas import Plan, PlanStep


@pytest.fixture
def clean_test_env():
    """Provides a safe temp directory inside user space."""
    temp_dir = tempfile.mkdtemp(prefix="max_fs_test_")
    yield Path(temp_dir)
    if os.path.exists(temp_dir):
        shutil.rmtree(temp_dir, ignore_errors=True)


# ==============================================================================
# TEST 1: Goal Desktop/rocky/rocky.txt with content 'Yashwanth and MAX'
# ==============================================================================
@pytest.mark.asyncio
async def test_01_nested_path_and_content_execution():
    """Verify nested path and exact content are preserved and verified through MAXTaskAuthority."""
    desktop = Path.home() / "Desktop"
    target_folder = desktop / "rocky_test_suite_1"
    target_file = target_folder / "rocky.txt"
    expected_content = "Yashwanth and MAX"

    try:
        # Create root task with compound goal
        task_info = max_task_authority.create_root_task(
            goal=f"Create Desktop/rocky_test_suite_1 folder, create Desktop/rocky_test_suite_1/rocky.txt with content '{expected_content}' and open it.",
            permissions=[
                GranularPermission.FILESYSTEM_READ.value,
                GranularPermission.FILESYSTEM_WRITE.value,
                GranularPermission.PROCESS_READ.value,
                GranularPermission.PROCESS_CONTROL.value
            ]
        )
        task_id = task_info["task_id"]

        result = await max_task_authority.execute_task(task_id)

        assert result.status == TaskStatus.COMPLETED.value or result.status == TaskStatus.COMPLETED
        assert result.verified_success is True
        assert result.claimed_success is True

        # Verify disk state
        assert target_folder.exists()
        assert target_folder.is_dir()
        assert target_file.exists()
        assert target_file.is_file()
        assert target_file.read_text(encoding="utf-8") == expected_content

        # Verify verification payload
        assert result.verification.get("path_exists") is True
        assert result.verification.get("is_file") is True
        assert result.verification.get("content_verified") is True

    finally:
        if target_folder.exists():
            shutil.rmtree(target_folder, ignore_errors=True)


# ==============================================================================
# TEST 2: Nested Path Preservation (Desktop/a/b/c/test.txt)
# ==============================================================================
def test_02_deeply_nested_path_resolution():
    """Verify deep nested subdirectories are never flattened."""
    resolved = _resolve_user_path("Desktop/a/b/c/test.txt")
    expected_suffix = Path("Desktop") / "a" / "b" / "c" / "test.txt"
    assert str(resolved).endswith(str(expected_suffix))
    assert Path(resolved) == Path.home() / "Desktop" / "a" / "b" / "c" / "test.txt"


# ==============================================================================
# TEST 3: Exact Content Read-Back Match
# ==============================================================================
def test_03_exact_content_read_back(clean_test_env):
    """Verify exact content write and read back comparison."""
    test_file = clean_test_env / "content_test.txt"
    content = "Yashwanth and MAX"

    c_res = create_file(path=str(test_file), content=content)
    assert c_res["status"] == "success"

    r_res = read_file(path=str(test_file))
    assert r_res["status"] == "success"
    assert r_res["content"] == content

    # Action verifier check
    assert action_verifier.verify_file_content_sync(str(test_file), content) is True
    assert action_verifier.verify_file_content_sync(str(test_file), "Wrong Content") is False


# ==============================================================================
# TEST 4: Fake Success Without Actual File Creation
# ==============================================================================
def test_04_fake_file_creation_rejected():
    """Verify verifier returns False if file creation claims success but file does not exist."""
    fake_path = str(Path.home() / "Desktop" / "non_existent_fake_file_9999.txt")
    if os.path.exists(fake_path):
        os.remove(fake_path)

    # ActionVerifier must return False
    verified = action_verifier.verify_file_created_sync(fake_path)
    assert verified is False

    # VerificationEngine must return False
    envelope = TaskEnvelope(
        task_id="test_fake",
        goal=f"Create file {fake_path}",
        task_type="file_operation",
        permissions=[GranularPermission.FILESYSTEM_WRITE.value]
    )
    res = verification_engine.verify_task(envelope, {"claimed_success": True, "path": fake_path})
    assert res[0] is False
    assert "File not found" in str(res[1]) or "failed" in str(res[1]).lower()


# ==============================================================================
# TEST 5: Open Non-Existent File Fails (No Blank Notepad Fallback)
# ==============================================================================
def test_05_open_missing_path_fails():
    """Verify open_file fails immediately with error when target does not exist."""
    non_existent = str(Path.home() / "Desktop" / "missing_file_definitely_not_here_12345.txt")
    if os.path.exists(non_existent):
        os.remove(non_existent)

    res = open_file(path=non_existent)
    assert res["status"] == "error"
    assert res.get("verified", False) is False
    assert "does not exist" in res["message"]


# ==============================================================================
# TEST 6: Write Success Status Without Actual Write Fails Verification
# ==============================================================================
def test_06_write_mismatch_verification_fails(clean_test_env):
    """Verify write verification fails when actual disk content does not match expected."""
    target = clean_test_env / "write_mismatch.txt"
    target.write_text("Old content on disk", encoding="utf-8")

    # Claim write with expected content 'New content', but disk has 'Old content'
    assert action_verifier.verify_file_content_sync(str(target), "New content") is False

    envelope = TaskEnvelope(
        task_id="test_write_mismatch",
        goal=f"Write to {target} with content 'New content'",
        task_type="file_operation",
        metadata={"content": "New content"},
        permissions=[GranularPermission.FILESYSTEM_WRITE.value]
    )
    v_res = verification_engine.verify_task(envelope, {
        "claimed_success": True,
        "path": str(target),
        "content": "New content"
    })
    assert v_res[0] is False
    assert "Content mismatch" in str(v_res[1])


# ==============================================================================
# TEST 7: Previous Step Fails -> Next Dependent Step Does Not Execute
# ==============================================================================
@pytest.mark.asyncio
async def test_07_step_failure_halts_pipeline(clean_test_env):
    """Verify TaskEngine immediately halts execution when a step fails verification."""
    step2_executed = False

    async def fake_step2_exec(*args, **kwargs):
        nonlocal step2_executed
        step2_executed = True
        return {"status": "success"}

    step1 = Step(
        step_id="step_1",
        task_id="task_fail_test",
        sequence=1,
        action="create_file",
        arguments={"path": str(clean_test_env / "non_creatable_invalid_dir" / "sub" / "file.txt")},
        retryable=False,
        max_attempts=1
    )
    step2 = Step(
        step_id="step_2",
        task_id="task_fail_test",
        sequence=2,
        action="open_file",
        arguments={"path": str(clean_test_env / "dummy.txt")},
        retryable=False,
        max_attempts=1
    )

    engine = TaskEngine.get_instance()
    task = engine.create_task(
        goal="Test step failure halting",
        steps=[step1, step2]
    )

    with patch("core.unified_tool_registry.unified_tool_registry.execute_async") as mock_exec_async:
        mock_exec_async.return_value = {"status": "error", "message": "Failed to create"}

        result_task = await engine.execute_task(task.task_id)

    assert result_task.status == TaskState.FAILED
    assert result_task.steps[0].status == StepState.FAILED
    assert result_task.steps[1].status == StepState.PENDING or result_task.steps[1].status == StepState.BLOCKED
    assert step2_executed is False


# ==============================================================================
# TEST 8: Worker Claims Success=True But Verifier Returns False -> VERIFICATION_FAILED
# ==============================================================================
@pytest.mark.asyncio
async def test_08_claimed_success_overridden_by_verifier_failure():
    """Verify worker claimed_success=True never overrides verifier returning False."""
    fake_target = str(Path.home() / "Desktop" / "definitely_ghost_file_789.txt")
    if os.path.exists(fake_target):
        os.remove(fake_target)

    test_task_id = f"test_claim_override_{uuid.uuid4().hex}"
    envelope = TaskEnvelope(
        task_id=test_task_id,
        goal=f"Create file {fake_target}",
        worker="windows_agent",
        task_type="file_operation",
        permissions=[GranularPermission.FILESYSTEM_WRITE.value]
    )
    try:
        max_task_authority.store.create_task(envelope)

        with patch.object(max_task_authority, "_execute_windows_agent_task") as mock_exec:
            from protocol.task_envelope import TaskResult
            mock_exec.return_value = TaskResult(
                task_id=test_task_id,
                status=TaskStatus.COMPLETED,
                claimed_success=True,
                verified_success=False,
                summary="Worker claims it created the file",
                artifacts=[fake_target]
            )

            final_res = await max_task_authority.execute_task(test_task_id)

        assert final_res.status == TaskStatus.VERIFICATION_FAILED.value or final_res.status == TaskStatus.VERIFICATION_FAILED
        assert final_res.verified_success is False
    finally:
        try:
            conn = max_task_authority.store._get_conn()
            with conn:
                conn.execute("DELETE FROM canonical_tasks WHERE task_id = ?", (test_task_id,))
        except Exception:
            pass


# ==============================================================================
# TEST 9: Traversal Path Is Blocked By Path Security Policy
# ==============================================================================
def test_09_traversal_path_blocked():
    """Verify traversal paths like Desktop/../secret.txt are rejected."""
    with pytest.raises(PermissionError):
        _resolve_user_path("Desktop/../secret.txt")


# ==============================================================================
# TEST 10: Existing File Replacement Is Verified
# ==============================================================================
def test_10_file_replacement_and_overwrite_verified(clean_test_env):
    """Verify replacing/overwriting an existing file updates content and passes verification."""
    target_file = clean_test_env / "replace_target.txt"
    target_file.write_text("Initial Content", encoding="utf-8")

    # Overwrite via write_file
    new_content = "Replaced Content Version 2"
    w_res = write_file(path=str(target_file), content=new_content)
    assert w_res["status"] == "success"

    # Verify read back
    assert action_verifier.verify_file_content_sync(str(target_file), new_content) is True
    assert target_file.read_text(encoding="utf-8") == new_content


# ==============================================================================
# REAL WINDOWS SMOKE TEST: Desktop/MAX_VERIFY_TEST/test.txt
# ==============================================================================
@pytest.mark.asyncio
async def test_11_real_windows_smoke_test():
    """
    Real Windows Smoke Test:
    Create Desktop/MAX_VERIFY_TEST folder, create Desktop/MAX_VERIFY_TEST/test.txt,
    write 'MAX verified filesystem test', read back, verify, open, verify open, and cleanup.
    """
    desktop = Path.home() / "Desktop"
    test_folder = desktop / "MAX_VERIFY_TEST"
    test_file = test_folder / "test.txt"
    content = "MAX verified filesystem test"

    try:
        # 1. Create folder
        f_res = create_folder(str(test_folder))
        assert f_res["status"] == "success"
        assert action_verifier.verify_folder_created_sync(str(test_folder)) is True

        # 2. Create file
        c_res = create_file(path=str(test_file), content=content)
        assert c_res["status"] == "success"
        assert action_verifier.verify_file_created_sync(str(test_file), expected_content=content) is True

        # 3. Write content
        w_res = write_file(path=str(test_file), content=content)
        assert w_res["status"] == "success"
        assert action_verifier.verify_file_content_sync(str(test_file), content) is True

        # 4. Read back
        r_res = read_file(path=str(test_file))
        assert r_res["status"] == "success"
        assert r_res["content"] == content

        # 5. Open file
        o_res = open_file(path=str(test_file))
        assert o_res["status"] == "success"
        assert o_res.get("verified", False) is True

        # 6. Verification Engine Validation
        envelope = TaskEnvelope(
            task_id="smoke_test_env",
            goal=f"Create {test_file} with content '{content}'",
            task_type="file_operation",
            permissions=[GranularPermission.FILESYSTEM_READ.value, GranularPermission.FILESYSTEM_WRITE.value]
        )
        v_out = verification_engine.verify_task(envelope, {
            "claimed_success": True,
            "path": str(test_file),
            "content": content
        })
        assert v_out[0] is True
        assert v_out[1].get("content_verified") is True

    finally:
        # Cleanup test artifacts
        if test_folder.exists():
            shutil.rmtree(test_folder, ignore_errors=True)
