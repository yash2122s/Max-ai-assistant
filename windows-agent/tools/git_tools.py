"""
MAX AI — Git Version Control & Checkpoint Suite
Provides git status, diffs, log viewing, branch management, and safe checkpoint/rollback operations.
"""

import os
import subprocess
import logging
from typing import Dict, Any, List, Optional

logger = logging.getLogger("MAX.GitTools")


class GitTools:
    """
    Git version control capabilities for MAX code manipulation.
    """

    def _run_git(self, repo_path: str, args: List[str]) -> Dict[str, Any]:
        """Runs a git command with timeout and safety."""
        cwd = os.path.abspath(repo_path)
        if not os.path.exists(cwd):
            return {"status": "error", "message": f"Path '{cwd}' does not exist."}

        try:
            res = subprocess.run(
                ["git"] + args,
                cwd=cwd,
                capture_output=True,
                text=True,
                timeout=15.0
            )
            return {
                "status": "success" if res.returncode == 0 else "error",
                "returncode": res.returncode,
                "stdout": res.stdout.strip(),
                "stderr": res.stderr.strip()
            }
        except subprocess.TimeoutExpired:
            return {"status": "error", "message": "Git command timed out."}
        except FileNotFoundError:
            return {"status": "error", "message": "Git executable not found in system PATH."}
        except Exception as e:
            return {"status": "error", "message": f"Git execution error: {str(e)}"}

    def git_status(self, repo_path: str = ".") -> Dict[str, Any]:
        """Returns structured git status for repository."""
        res = self._run_git(repo_path, ["status", "--porcelain", "-b"])
        if res.get("status") == "error":
            return res
        return {
            "status": "success",
            "repo_path": os.path.abspath(repo_path),
            "output": res.get("stdout", "")
        }

    def git_diff(self, repo_path: str = ".", target: str = "") -> Dict[str, Any]:
        """Returns unified git diff against HEAD or specified target commit/branch."""
        args = ["diff"]
        if target:
            args.append(target)
        res = self._run_git(repo_path, args)
        if res.get("status") == "error":
            return res
        diff_out = res.get("stdout", "")
        return {
            "status": "success",
            "has_changes": len(diff_out) > 0,
            "diff": diff_out[:8000],  # bounded output
            "total_bytes": len(diff_out)
        }

    def git_log(self, repo_path: str = ".", max_count: int = 10) -> Dict[str, Any]:
        """Returns structured recent commit history."""
        args = ["log", f"-n{max_count}", "--oneline"]
        res = self._run_git(repo_path, args)
        if res.get("status") == "error":
            return res
        commits = [line for line in res.get("stdout", "").splitlines() if line]
        return {
            "status": "success",
            "count": len(commits),
            "commits": commits
        }

    def git_branch(self, repo_path: str = ".") -> Dict[str, Any]:
        """Lists repository branches and current active branch."""
        res = self._run_git(repo_path, ["branch", "-a"])
        if res.get("status") == "error":
            return res
        branches = [b.strip() for b in res.get("stdout", "").splitlines() if b.strip()]
        current = next((b[2:] for b in branches if b.startswith("* ")), "unknown")
        return {
            "status": "success",
            "current_branch": current,
            "branches": branches
        }

    def git_checkpoint(self, repo_path: str = ".", message: str = "auto-checkpoint") -> Dict[str, Any]:
        """Creates a temporary stash checkpoint before risky operations."""
        res = self._run_git(repo_path, ["stash", "create", message])
        if res.get("status") == "error":
            return res
        stash_sha = res.get("stdout", "")
        return {
            "status": "success",
            "message": f"Checkpoint created with SHA: '{stash_sha}'" if stash_sha else "No local changes to checkpoint.",
            "checkpoint_sha": stash_sha
        }

    def git_rollback(self, repo_path: str = ".") -> Dict[str, Any]:
        """Rolls back uncommitted changes safely."""
        res = self._run_git(repo_path, ["checkout", "--", "."])
        return {
            "status": "success" if res.get("status") == "success" else "error",
            "message": "Rolled back working directory changes to clean state."
        }


git_tools = GitTools()
