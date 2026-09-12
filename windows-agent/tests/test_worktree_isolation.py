"""
Unit tests for Worktree Isolation & Merge Authorization
"""

import os
import shutil
import tempfile
import pytest
from core.worktree_isolation import WorktreeManager, WorktreeInfo


@pytest.fixture
def temp_worktree_manager():
    temp_dir = tempfile.mkdtemp()
    manager = WorktreeManager(storage_dir=temp_dir, max_active=3)
    yield manager
    shutil.rmtree(temp_dir, ignore_errors=True)


def test_worktree_creation(temp_worktree_manager):
    info = temp_worktree_manager.create_worktree("task-123", base_repo=".", branch_name="test-branch")
    assert isinstance(info, WorktreeInfo)
    assert info.task_id == "task-123"
    assert info.status == "active"
    assert os.path.exists(info.worktree_path)
    assert len(temp_worktree_manager.list_active_worktrees()) == 1


def test_worktree_capacity_limit(temp_worktree_manager):
    temp_worktree_manager.create_worktree("task-1")
    temp_worktree_manager.create_worktree("task-2")
    temp_worktree_manager.create_worktree("task-3")

    assert len(temp_worktree_manager.list_active_worktrees()) == 3

    # Exceeding limit of 3 should raise RuntimeError
    with pytest.raises(RuntimeError, match="Worktree capacity exhausted"):
        temp_worktree_manager.create_worktree("task-4")


def test_worktree_discard(temp_worktree_manager):
    info = temp_worktree_manager.create_worktree("task-discard")
    assert os.path.exists(info.worktree_path)

    res = temp_worktree_manager.discard_worktree("task-discard")
    assert res is True
    assert temp_worktree_manager.get_worktree("task-discard").status == "discarded"
    assert len(temp_worktree_manager.list_active_worktrees()) == 0


def test_antigravity_cannot_self_merge(temp_worktree_manager):
    temp_worktree_manager.create_worktree("task-self-merge")

    # Antigravity self-authorization MUST be rejected
    res = temp_worktree_manager.authorize_merge(
        task_id="task-self-merge",
        approved_by="antigravity",
        verification_passed=True
    )
    assert res["success"] is False
    assert "Unauthorized merge actor" in res["error"]


def test_unverified_merge_rejected(temp_worktree_manager):
    temp_worktree_manager.create_worktree("task-unverified")

    # Unverified merge MUST be rejected
    res = temp_worktree_manager.authorize_merge(
        task_id="task-unverified",
        approved_by="MAX_USER",
        require_verification=True,
        verification_passed=False
    )
    assert res["success"] is False
    assert "Cannot merge unverified code changes" in res["error"]


def test_authorized_verified_merge_succeeds(temp_worktree_manager):
    temp_worktree_manager.create_worktree("task-valid-merge")

    # MAX authorized merge with verification succeeds
    res = temp_worktree_manager.authorize_merge(
        task_id="task-valid-merge",
        approved_by="Yaswanth_MAX",
        require_verification=True,
        verification_passed=True
    )
    assert res["success"] is True
    assert res["approved_by"] == "Yaswanth_MAX"
    assert res["status"] == "merged"
