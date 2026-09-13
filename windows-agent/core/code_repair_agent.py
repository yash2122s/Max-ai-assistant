"""
MAX AI — Self-Repairing Code Agent (P0-3)
Safely diagnoses Python tracebacks, validates file paths within sandbox boundaries,
proposes AST-validated code patches, executes targeted unit tests, and iterates within
strictly bounded attempts without allowing arbitrary LLM execution.
"""

import os
import re
import ast
import time
import shutil
import logging
import asyncio
import subprocess
from dataclasses import dataclass
from typing import Dict, Any, List, Optional, Tuple, Callable

logger = logging.getLogger("MAXWindowsAgent.CodeRepairAgent")


@dataclass
class TracebackInfo:
    """Parsed traceback details."""
    file_path: str
    line_number: Optional[int]
    exception_type: str
    error_message: str
    raw_traceback: str


class TracebackParser:
    """
    Extracts structured diagnostics from raw Python traceback output.
    """
    FILE_LINE_REGEX = re.compile(r'File\s+["\']([^"\']+)["\'],\s+line\s+(\d+)(?:,\s+in\s+([^\n]+))?')
    EXCEPTION_REGEX = re.compile(r'([A-Za-z_][A-Za-z0-9_]*Error|[A-Za-z_][A-Za-z0-9_]*Exception|AssertionError):\s*(.+)')

    @classmethod
    def parse(cls, traceback_text: str) -> Optional[TracebackInfo]:
        if not traceback_text or not isinstance(traceback_text, str):
            return None

        # Find all File ..., line ... matches (take the innermost/last one as the failure location)
        file_matches = cls.FILE_LINE_REGEX.findall(traceback_text)
        if not file_matches:
            return None

        target_file, target_line, _ = file_matches[-1]
        line_no = int(target_line) if target_line.isdigit() else None

        # Find exception type & message
        exc_type = "Exception"
        exc_msg = "Unknown error"

        for line in reversed(traceback_text.strip().splitlines()):
            exc_match = cls.EXCEPTION_REGEX.search(line)
            if exc_match:
                exc_type = exc_match.group(1).strip()
                exc_msg = exc_match.group(2).strip()
                break

        return TracebackInfo(
            file_path=target_file.strip(),
            line_number=line_no,
            exception_type=exc_type,
            error_message=exc_msg,
            raw_traceback=traceback_text.strip()
        )


class PathSandbox:
    """
    Enforces strict file boundary validation to prevent path traversal
    and out-of-workspace modifications.
    """
    BLOCKED_PATTERNS = [
        r"\.env",
        r"\.git",
        r"\.ssh",
        r"System32",
        r"Windows[\\/]",
        r"Program Files",
        r"appdata[\\/]local[\\/]programs",
    ]

    def __init__(self, allowed_roots: Optional[List[str]] = None):
        if allowed_roots:
            self.allowed_roots = [os.path.normcase(os.path.realpath(os.path.abspath(r))) for r in allowed_roots]
        else:
            # Default to current project root
            base_dir = os.path.normcase(os.path.realpath(os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))))
            self.allowed_roots = [base_dir]

    def is_safe_path(self, target_path: str) -> Tuple[bool, str]:
        """
        Verifies if target_path is safe to inspect or patch.
        Returns (is_safe, canonical_or_error_msg).
        """
        if not target_path or not isinstance(target_path, str):
            return False, "Invalid path"

        abs_path = os.path.realpath(os.path.abspath(target_path))
        norm = os.path.normcase(abs_path)

        # Check blocked patterns
        for pat in self.BLOCKED_PATTERNS:
            if re.search(pat, norm, re.IGNORECASE):
                return False, f"Access to protected or sensitive path blocked: {pat}"

        # Verify within allowed root
        is_inside = any(
            os.path.commonpath([norm, root]) == root
            for root in self.allowed_roots
        )
        if not is_inside:
            return False, f"Path '{abs_path}' is outside permitted workspace roots."

        return True, abs_path


class PatchValidator:
    """
    Validates Python syntax and security rules before applying a patch.
    """
    PROHIBITED_CALLS = ["eval(", "exec(", "__import__", "os.system(", "subprocess.Popen(", "rmdir /s"]

    @classmethod
    def validate_code_syntax(cls, code_content: str) -> Tuple[bool, Optional[str]]:
        """
        Verifies that code_content is valid Python syntax.
        """
        try:
            ast.parse(code_content)
            return True, None
        except SyntaxError as e:
            return False, f"SyntaxError at line {e.lineno}: {e.msg}"

    @classmethod
    def validate_security_rules(cls, code_content: str) -> Tuple[bool, Optional[str]]:
        """
        Ensures patch does not introduce unauthorized code execution primitives.
        """
        for token in cls.PROHIBITED_CALLS:
            if token in code_content:
                return False, f"Patch contains prohibited security token: '{token}'"
        return True, None


class CodeRepairAgent:
    """
    Self-Repairing Code Agent with bounded repair loops:
    1. Parse traceback
    2. Validate file & sandbox
    3. Read code context
    4. Propose & validate patch syntax
    5. Create backup (.bak)
    6. Apply patch
    7. Run targeted test
    8. Re-evaluate / retry up to max_attempts (default 3)
    9. Stop and roll back if failed after max_attempts
    """

    def __init__(
        self,
        max_attempts: int = 3,
        sandbox: Optional[PathSandbox] = None,
        workspace_root: Optional[str] = None
    ):
        self.max_attempts = max_attempts
        self.sandbox = sandbox or PathSandbox([workspace_root] if workspace_root else None)

    def read_file_context(self, file_path: str, focus_line: Optional[int] = None, window: int = 20) -> Dict[str, Any]:
        """
        Safely reads file lines around focus_line.
        """
        is_safe, canonical_path = self.sandbox.is_safe_path(file_path)
        if not is_safe:
            return {"status": "error", "message": canonical_path}

        if not os.path.exists(canonical_path):
            return {"status": "error", "message": f"File '{canonical_path}' does not exist."}

        try:
            with open(canonical_path, "r", encoding="utf-8", errors="replace") as f:
                lines = f.readlines()

            total_lines = len(lines)
            if focus_line and 1 <= focus_line <= total_lines:
                start = max(0, focus_line - window // 2 - 1)
                end = min(total_lines, focus_line + window // 2)
                context_snippet = "".join(f"{i+1:4d} | {lines[i]}" for i in range(start, end))
            else:
                context_snippet = "".join(f"{i+1:4d} | {lines[i]}" for i in range(min(total_lines, 50)))

            return {
                "status": "success",
                "file_path": canonical_path,
                "total_lines": total_lines,
                "snippet": context_snippet,
                "raw_content": "".join(lines)
            }
        except Exception as e:
            return {"status": "error", "message": str(e)}

    def apply_patch_safely(self, file_path: str, new_content: str) -> Dict[str, Any]:
        """
        Validates and writes patch with automatic backup (.bak).
        """
        is_safe, canonical_path = self.sandbox.is_safe_path(file_path)
        if not is_safe:
            return {"status": "error", "message": canonical_path}

        # 1. Validate Syntax
        valid_syntax, syn_err = PatchValidator.validate_code_syntax(new_content)
        if not valid_syntax:
            return {"status": "error", "error_code": "SYNTAX_ERROR", "message": syn_err}

        # 2. Validate Security
        valid_sec, sec_err = PatchValidator.validate_security_rules(new_content)
        if not valid_sec:
            return {"status": "error", "error_code": "SECURITY_VIOLATION", "message": sec_err}

        # 3. Create Backup
        backup_path = canonical_path + ".bak"
        try:
            if os.path.exists(canonical_path):
                shutil.copy2(canonical_path, backup_path)

            # Write patch
            with open(canonical_path, "w", encoding="utf-8") as f:
                f.write(new_content)

            return {
                "status": "success",
                "file_path": canonical_path,
                "backup_path": backup_path,
                "message": "Patch applied successfully."
            }
        except Exception as e:
            # Attempt rollback on write failure
            if os.path.exists(backup_path):
                shutil.copy2(backup_path, canonical_path)
            return {"status": "error", "message": f"Failed to apply patch: {str(e)}"}

    def rollback_patch(self, file_path: str) -> bool:
        """
        Restores file from .bak backup.
        """
        is_safe, canonical_path = self.sandbox.is_safe_path(file_path)
        if not is_safe:
            return False

        backup_path = canonical_path + ".bak"
        if os.path.exists(backup_path):
            try:
                shutil.copy2(backup_path, canonical_path)
                os.remove(backup_path)
                logger.info(f"Rolled back patch for {canonical_path}")
                return True
            except Exception as e:
                logger.error(f"Failed to rollback {canonical_path}: {e}")
        return False

    def run_targeted_test(self, test_command: str, timeout_sec: float = 15.0, cwd: Optional[str] = None) -> Dict[str, Any]:
        """
        Executes a targeted test command safely.
        """
        if not test_command or not isinstance(test_command, str):
            return {"status": "error", "message": "Test command cannot be empty"}

        # Command allowlist: Only pytest or unittest test runners allowed
        clean_cmd = test_command.strip()
        is_allowed = clean_cmd.startswith("pytest") or clean_cmd.startswith("py -m pytest") or clean_cmd.startswith("python -m unittest") or clean_cmd.startswith("py -m unittest")
        if not is_allowed:
            return {
                "status": "error",
                "error_code": "TEST_COMMAND_DISALLOWED",
                "message": f"Only pytest/unittest test commands are permitted. Got: '{clean_cmd}'"
            }

        import shlex
        import sys

        try:
            argv = shlex.split(clean_cmd, posix=False)
        except Exception as se:
            return {"status": "error", "message": f"Malformed test command: {se}"}

        if not argv:
            return {"status": "error", "message": "Empty test argv"}

        # Validate executable is Python/pytest runner
        first_token = argv[0].lower()
        if first_token in ("py", "python", "python3"):
            argv[0] = sys.executable
        elif first_token.endswith("pytest") or first_token.endswith("pytest.exe"):
            argv = [sys.executable, "-m", "pytest"] + argv[1:]
        elif first_token not in (sys.executable.lower(), "pytest"):
            argv = [sys.executable, "-m", "pytest"] + argv

        # Validate working directory against sandbox
        target_cwd = cwd or (self.sandbox.allowed_roots[0] if self.sandbox.allowed_roots else os.getcwd())
        is_safe, canonical_cwd = self.sandbox.is_safe_path(target_cwd)
        if not is_safe or not os.path.exists(canonical_cwd):
            canonical_cwd = self.sandbox.allowed_roots[0] if self.sandbox.allowed_roots else os.getcwd()

        # Restrict environment
        safe_env = os.environ.copy()
        safe_env["PYTHONDONTWRITEBYTECODE"] = "1"
        safe_env["PYTHONUNBUFFERED"] = "1"
        safe_env.pop("PYTHONSTARTUP", None)

        try:
            proc = subprocess.run(
                argv,
                shell=False,
                cwd=cwd,
                env=safe_env,
                capture_output=True,
                text=True,
                timeout=timeout_sec
            )
            passed = (proc.returncode == 0)
            return {
                "status": "success" if passed else "failed",
                "passed": passed,
                "returncode": proc.returncode,
                "stdout": proc.stdout,
                "stderr": proc.stderr
            }
        except subprocess.TimeoutExpired as te:
            return {
                "status": "error",
                "error_code": "TEST_TIMEOUT",
                "passed": False,
                "stdout": te.stdout or "",
                "stderr": te.stderr or "",
                "message": f"Test command timed out after {timeout_sec}s"
            }
        except Exception as e:
            return {
                "status": "error",
                "passed": False,
                "message": str(e)
            }

    def run_test_suite(self, target_path: str, timeout_sec: float = 60.0) -> Dict[str, Any]:
        """
        Executes a full test suite on a file or directory using pytest/unittest.
        Returns parsed test outcomes, total count, passed, failed, and traceback if any.
        """
        cmd = f"py -m pytest {target_path} -v"
        res = self.run_targeted_test(cmd, timeout_sec=timeout_sec)
        stdout = res.get("stdout", "")
        stderr = res.get("stderr", "")

        # Extract summary counts
        passed = res.get("passed", False)
        import re
        summary_match = re.search(r'(=+)\s+([\d\w\s,]+)\s+in\s+([\d\.]+s)\s+(=+)', stdout)
        summary_text = summary_match.group(2) if summary_match else ("Passed" if passed else "Failed")

        return {
            "status": "success" if passed else "failed",
            "passed": passed,
            "target": target_path,
            "summary": summary_text,
            "stdout": stdout,
            "stderr": stderr
        }

    async def execute_repair_loop(
        self,
        traceback_text: str,
        propose_patch_func: Callable[[TracebackInfo, str], str],
        test_command: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Bounded self-repair loop:
        Repeats up to self.max_attempts.
        """
        tb_info = TracebackParser.parse(traceback_text)
        if not tb_info:
            return {
                "status": "error",
                "error_code": "UNPARSABLE_TRACEBACK",
                "message": "Could not parse target file and line from traceback."
            }

        is_safe, canonical_path = self.sandbox.is_safe_path(tb_info.file_path)
        if not is_safe:
            return {
                "status": "error",
                "error_code": "SANDBOX_VIOLATION",
                "message": canonical_path
            }

        history: List[Dict[str, Any]] = []

        for attempt in range(1, self.max_attempts + 1):
            logger.info(f"🔧 Code repair attempt {attempt}/{self.max_attempts} on {canonical_path}:{tb_info.line_number}")

            # 1. Read current content
            ctx = self.read_file_context(canonical_path, tb_info.line_number)
            if ctx.get("status") != "success":
                return ctx

            raw_code = ctx["raw_content"]

            # 2. Propose patch
            try:
                if asyncio.iscoroutinefunction(propose_patch_func):
                    patched_code = await propose_patch_func(tb_info, raw_code)
                else:
                    patched_code = propose_patch_func(tb_info, raw_code)
            except Exception as e:
                return {"status": "error", "message": f"Patch proposer error: {str(e)}"}

            # 3. Apply patch safely
            apply_res = self.apply_patch_safely(canonical_path, patched_code)
            if apply_res.get("status") != "success":
                history.append({"attempt": attempt, "result": "patch_failed", "error": apply_res.get("message")})
                continue

            # 4. If test command provided, run it
            if test_command:
                test_res = self.run_targeted_test(test_command)
                if test_res.get("passed"):
                    # Success! Clean up backup and return
                    backup = canonical_path + ".bak"
                    if os.path.exists(backup):
                        os.remove(backup)
                    return {
                        "status": "success",
                        "attempts_used": attempt,
                        "file_path": canonical_path,
                        "history": history,
                        "message": f"Successfully repaired and verified in {attempt} attempt(s)."
                    }
                else:
                    # Test failed -> rollback for next attempt
                    self.rollback_patch(canonical_path)
                    history.append({
                        "attempt": attempt,
                        "result": "test_failed",
                        "stdout": test_res.get("stdout"),
                        "stderr": test_res.get("stderr")
                    })
            else:
                # No test command specified; patch applied and syntax validated
                return {
                    "status": "success",
                    "attempts_used": attempt,
                    "file_path": canonical_path,
                    "message": "Patch applied and AST syntax validated."
                }

        # Max attempts exhausted
        return {
            "status": "error",
            "error_code": "MAX_ATTEMPTS_EXHAUSTED",
            "message": f"Code repair failed after {self.max_attempts} attempts.",
            "file_path": canonical_path,
            "root_cause": f"{tb_info.exception_type}: {tb_info.error_message}",
            "history": history
        }


# Global singleton instance
code_repair_agent = CodeRepairAgent()
