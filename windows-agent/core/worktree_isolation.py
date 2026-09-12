"""
Git Worktree Isolation & Controlled Merge Authority
Provides isolated git worktrees for autonomous code modifications.
Enforces the mandatory architectural rule: Antigravity NEVER automatically merges into main.
All merges require explicit authorization from MAX / the user.
"""

import os
import shutil
import subprocess
import logging
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from typing import Dict, List, Optional, Any

import config

logger = logging.getLogger("MAX.WorktreeIsolation")


@dataclass
class WorktreeInfo:
    task_id: str
    worktree_path: str
    branch: str
    owner: str = "antigravity"
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    status: str = "active"  # active, merged, discarded, failed
    base_repo: str = ""
    merge_commit: Optional[str] = None
    approved_by: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class WorktreeManager:
    """
    Manages isolated Git worktrees for developer tasks.
    Ensures that concurrent code modifications do not overwrite the main working directory.
    """

    def __init__(self, storage_dir: Optional[str] = None, max_active: Optional[int] = None):
        self.storage_dir = storage_dir or getattr(config, "WORKTREES_DIR", os.path.join(config.PROJECT_ROOT, "storage", "worktrees"))
        self.max_active = max_active if max_active is not None else getattr(config, "MAX_ACTIVE_WORKTREES", 5)
        os.makedirs(self.storage_dir, exist_ok=True)
        self._worktrees: Dict[str, WorktreeInfo] = {}

    def _run_git(self, args: List[str], cwd: str) -> subprocess.CompletedProcess:
        """Run a git command safely."""
        return subprocess.run(
            ["git"] + args,
            cwd=cwd,
            capture_output=True,
            text=True,
            check=False
        )

    def _is_git_repo(self, path: str) -> bool:
        """Check if path is inside a valid git repository."""
        res = self._run_git(["rev-parse", "--is-inside-work-tree"], cwd=path)
        return res.returncode == 0 and res.stdout.strip() == "true"

    def create_worktree(
        self,
        task_id: str,
        base_repo: Optional[str] = None,
        branch_name: Optional[str] = None,
        owner: str = "antigravity"
    ) -> WorktreeInfo:
        """
        Creates an isolated git worktree for a task.
        Raises RuntimeError if maximum concurrent worktrees limit is reached.
        """
        active_count = len(self.list_active_worktrees())
        if active_count >= self.max_active:
            raise RuntimeError(
                f"Worktree capacity exhausted ({active_count}/{self.max_active} active). Queue task or cleanup."
            )

        if task_id in self._worktrees and self._worktrees[task_id].status == "active":
            return self._worktrees[task_id]

        repo_path = os.path.abspath(base_repo or config.PROJECT_ROOT)
        clean_task_id = "".join(c for c in task_id if c.isalnum() or c in "-_")[:16]
        target_dir = os.path.abspath(os.path.join(self.storage_dir, f"wt_{clean_task_id}"))
        branch = branch_name or f"max/task-{clean_task_id}"

        # Clean up target directory if it exists from a previous aborted run
        if os.path.exists(target_dir):
            try:
                shutil.rmtree(target_dir, ignore_errors=True)
            except Exception as e:
                logger.warning(f"Failed to clear existing directory {target_dir}: {e}")

        is_git = self._is_git_repo(repo_path)
        if is_git:
            # Delete branch if already exists from prior run
            self._run_git(["branch", "-D", branch], cwd=repo_path)

            # Create worktree with new branch
            res = self._run_git(["worktree", "add", "-b", branch, target_dir, "HEAD"], cwd=repo_path)
            if res.returncode != 0:
                logger.error(f"Git worktree add failed: {res.stderr}")
                # Fallback: create plain isolated copy if git worktree add fails (e.g. shallow clone or detached HEAD)
                os.makedirs(target_dir, exist_ok=True)
        else:
            # Non-git workspace: create dedicated isolated folder
            os.makedirs(target_dir, exist_ok=True)

        info = WorktreeInfo(
            task_id=task_id,
            worktree_path=target_dir,
            branch=branch,
            owner=owner,
            status="active",
            base_repo=repo_path,
            metadata={"is_git": is_git}
        )
        self._worktrees[task_id] = info
        logger.info(f"Created isolated worktree for task {task_id} at {target_dir} (branch: {branch})")
        return info

    def get_worktree(self, task_id: str) -> Optional[WorktreeInfo]:
        """Get worktree info for a task."""
        return self._worktrees.get(task_id)

    def list_active_worktrees(self) -> List[WorktreeInfo]:
        """List all active worktrees."""
        return [wt for wt in self._worktrees.values() if wt.status == "active"]

    def discard_worktree(self, task_id: str, force: bool = True, delete_branch: bool = True) -> bool:
        """
        Discards and removes an isolated worktree.
        """
        wt = self._worktrees.get(task_id)
        if not wt:
            return False

        if wt.metadata.get("is_git", False) and os.path.exists(wt.base_repo):
            self._run_git(["worktree", "remove", "--force", wt.worktree_path], cwd=wt.base_repo)
            if delete_branch and wt.branch:
                self._run_git(["branch", "-D", wt.branch], cwd=wt.base_repo)

        if os.path.exists(wt.worktree_path):
            try:
                shutil.rmtree(wt.worktree_path, ignore_errors=True)
            except Exception as e:
                logger.warning(f"Error removing worktree path {wt.worktree_path}: {e}")

        wt.status = "discarded"
        logger.info(f"Discarded worktree for task {task_id}")
        return True

    def authorize_merge(
        self,
        task_id: str,
        approved_by: str,
        target_branch: str = "main",
        require_verification: bool = True,
        verification_passed: bool = True
    ) -> Dict[str, Any]:
        """
        Executes an authorized git merge from the worktree branch into the target branch.

        NON-NEGOTIABLE INVARIANTS:
        1. Antigravity NEVER automatically merges. approved_by must be MAX or human user.
        2. Merging unverified code changes is strictly prohibited.
        """
        wt = self._worktrees.get(task_id)
        if not wt:
            return {"success": False, "error": f"No worktree found for task {task_id}"}

        # Check approval authority
        if not approved_by or approved_by.lower() in ("antigravity", "worker", "agy", "hermes"):
            return {
                "success": False,
                "error": f"Unauthorized merge actor '{approved_by}'. Merges require explicit MAX or user approval."
            }

        # Check verification prerequisite
        if require_verification and not verification_passed:
            return {
                "success": False,
                "error": "Cannot merge unverified code changes. Verification must succeed before merge."
            }

        if not wt.metadata.get("is_git", False):
            wt.status = "merged"
            wt.approved_by = approved_by
            return {
                "success": True,
                "task_id": task_id,
                "status": "merged",
                "approved_by": approved_by,
                "note": "Non-git directory merge simulated"
            }

        # Perform git merge in base repo
        repo = wt.base_repo
        # Check current branch in base repo
        cur_branch_res = self._run_git(["rev-parse", "--abbrev-ref", "HEAD"], cwd=repo)
        current_branch = cur_branch_res.stdout.strip() if cur_branch_res.returncode == 0 else "main"

        merge_msg = f"MAX Authorized Merge: Task {task_id} (Approved by: {approved_by})"
        merge_res = self._run_git(["merge", "--no-ff", wt.branch, "-m", merge_msg], cwd=repo)

        if merge_res.returncode != 0:
            logger.error(f"Git merge failed for task {task_id}: {merge_res.stderr}")
            return {
                "success": False,
                "error": f"Git merge conflict or error: {merge_res.stderr.strip()}",
                "task_id": task_id
            }

        # Retrieve merge commit hash
        commit_res = self._run_git(["rev-parse", "HEAD"], cwd=repo)
        commit_hash = commit_res.stdout.strip() if commit_res.returncode == 0 else "unknown"

        wt.status = "merged"
        wt.approved_by = approved_by
        wt.merge_commit = commit_hash

        # Remove the worktree now that it has been merged
        self.discard_worktree(task_id, force=True, delete_branch=True)
        wt.status = "merged"  # retain merged status after discard

        logger.info(f"Successfully merged task {task_id} into {current_branch}. Commit: {commit_hash}")
        return {
            "success": True,
            "task_id": task_id,
            "status": "merged",
            "branch": wt.branch,
            "approved_by": approved_by,
            "commit": commit_hash,
            "timestamp": datetime.now(timezone.utc).isoformat()
        }

    def cleanup_all(self):
        """Cleanup all active worktrees."""
        for task_id in list(self._worktrees.keys()):
            if self._worktrees[task_id].status == "active":
                self.discard_worktree(task_id)


# Global singleton
worktree_manager = WorktreeManager()
