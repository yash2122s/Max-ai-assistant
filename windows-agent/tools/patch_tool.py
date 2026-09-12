"""
MAX AI — Unified Diff Patching Engine
Applies standard unified diff patches to files with AST syntax verification,
fuzzy context line matching, dry-run simulation, and atomic rollback on failure.
"""

import os
import re
import ast
import shutil
import logging
from typing import Dict, Any, List, Optional, Tuple

from config import PROJECT_ROOT
from core.code_repair_agent import PathSandbox

logger = logging.getLogger("MAXWindowsAgent.PatchTool")


class PatchChunk:
    def __init__(self, old_start: int, old_len: int, new_start: int, new_len: int):
        self.old_start = old_start
        self.old_len = old_len
        self.new_start = new_start
        self.new_len = new_len
        self.lines: List[str] = []


class UnifiedDiffParser:
    """
    Parses unified diff text into structured file patch chunks.
    """
    CHUNK_HEADER_RE = re.compile(r"^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@")

    @classmethod
    def parse_diff(cls, diff_text: str) -> List[PatchChunk]:
        chunks: List[PatchChunk] = []
        current_chunk: Optional[PatchChunk] = None

        for line in diff_text.splitlines():
            # Check for @@ chunk header
            m = cls.CHUNK_HEADER_RE.match(line)
            if m:
                old_start = int(m.group(1))
                old_len = int(m.group(2)) if m.group(2) else 1
                new_start = int(m.group(3))
                new_len = int(m.group(4)) if m.group(4) else 1
                current_chunk = PatchChunk(old_start, old_len, new_start, new_len)
                chunks.append(current_chunk)
                continue

            if current_chunk is not None:
                if line.startswith(("+", "-", " ", "\\")):
                    current_chunk.lines.append(line)

        return chunks


class PatchApplier:
    """
    Applies parsed patch chunks to target file lines with fuzzy context matching.
    """

    @staticmethod
    def apply_chunks_to_lines(orig_lines: List[str], chunks: List[PatchChunk]) -> Tuple[bool, List[str], str]:
        working_lines = list(orig_lines)
        offset = 0

        for chunk_idx, chunk in enumerate(chunks, start=1):
            # Extract expected old context/removal lines
            expected_old = [l[1:] for l in chunk.lines if l.startswith((" ", "-"))]
            replacement = [l[1:] for l in chunk.lines if l.startswith((" ", "+"))]

            if not expected_old and not replacement:
                continue

            # Start looking near chunk.old_start + offset (1-indexed -> 0-indexed)
            target_idx = max(0, chunk.old_start - 1 + offset)

            # Match attempt 1: Exact slice match at target_idx
            matched_pos = -1
            match_len = len(expected_old)

            if target_idx + match_len <= len(working_lines):
                if working_lines[target_idx : target_idx + match_len] == expected_old:
                    matched_pos = target_idx

            # Match attempt 2: Scan nearby lines within a 20-line window
            if matched_pos == -1:
                search_min = max(0, target_idx - 20)
                search_max = min(len(working_lines) - match_len + 1, target_idx + 20)
                for pos in range(search_min, search_max):
                    if working_lines[pos : pos + match_len] == expected_old:
                        matched_pos = pos
                        break

            # Match attempt 3: Stripped/whitespace-insensitive match
            if matched_pos == -1:
                norm_expected = [l.strip() for l in expected_old]
                for pos in range(len(working_lines) - match_len + 1):
                    slice_norm = [l.strip() for l in working_lines[pos : pos + match_len]]
                    if slice_norm == norm_expected:
                        matched_pos = pos
                        break

            if matched_pos == -1:
                return False, orig_lines, f"Failed to match context for chunk #{chunk_idx} near line {chunk.old_start}"

            # Replace lines
            working_lines[matched_pos : matched_pos + match_len] = replacement
            # Update running offset
            offset += len(replacement) - match_len

        return True, working_lines, "OK"


class PatchEngine:
    """
    Main Patching Service validating paths, AST syntax, and atomic write.
    """

    def __init__(self, sandbox: Optional[PathSandbox] = None):
        self.sandbox = sandbox or PathSandbox([PROJECT_ROOT])

    def apply_patch(
        self,
        target_file: str,
        diff_content: str,
        dry_run: bool = False
    ) -> Dict[str, Any]:
        """
        Applies a unified diff patch to a target file.
        """
        if not target_file or not target_file.strip():
            return {"status": "error", "message": "Target file cannot be empty."}

        if not diff_content or not diff_content.strip():
            return {"status": "error", "message": "Diff content cannot be empty."}

        # 1. Path Safety & Sandbox Check
        is_safe, resolved_path_or_err = self.sandbox.is_safe_path(target_file.strip())
        if not is_safe:
            return {"status": "error", "message": f"Security violation: {resolved_path_or_err}"}

        abs_path = resolved_path_or_err

        # 2. Check existence
        if not os.path.exists(abs_path):
            # Check if creating a new file via patch
            if "--- /dev/null" in diff_content or "--- a/dev/null" in diff_content or "+++ " in diff_content:
                lines = []
                for line in diff_content.splitlines():
                    if line.startswith("+") and not line.startswith("+++"):
                        lines.append(line[1:])
                new_text = "\n".join(lines)
                if not dry_run:
                    os.makedirs(os.path.dirname(abs_path), exist_ok=True)
                    with open(abs_path, "w", encoding="utf-8") as f:
                        f.write(new_text)
                return {
                    "status": "success",
                    "message": f"Successfully created new file '{os.path.basename(abs_path)}' via patch.",
                    "file": abs_path,
                    "dry_run": dry_run
                }
            return {"status": "error", "message": f"Target file does not exist: {abs_path}"}

        # 3. Read original file
        try:
            with open(abs_path, "r", encoding="utf-8", errors="replace") as f:
                orig_content = f.read()
        except Exception as e:
            return {"status": "error", "message": f"Could not read {abs_path}: {e}"}

        orig_lines = orig_content.splitlines()

        # 4. Parse diff chunks
        chunks = UnifiedDiffParser.parse_diff(diff_content)
        if not chunks:
            return {"status": "error", "message": "No valid unified diff chunks found in patch content."}

        # 5. Apply chunks
        success, patched_lines, err_msg = PatchApplier.apply_chunks_to_lines(orig_lines, chunks)
        if not success:
            return {"status": "error", "message": f"Patch application failed: {err_msg}"}

        patched_content = "\n".join(patched_lines)
        if orig_content.endswith("\n"):
            patched_content += "\n"

        # 6. Python AST Syntax Verification if patching a Python file
        if abs_path.endswith(".py"):
            try:
                ast.parse(patched_content, filename=abs_path)
            except SyntaxError as syn_err:
                return {
                    "status": "error",
                    "message": f"Patched code contains syntax error at line {syn_err.lineno}: {syn_err.msg}",
                    "syntax_error": str(syn_err)
                }

        # 7. Atomic Write (unless dry run)
        if not dry_run:
            backup_path = abs_path + ".patch_bak"
            try:
                shutil.copy2(abs_path, backup_path)
                with open(abs_path, "w", encoding="utf-8") as f:
                    f.write(patched_content)
                if os.path.exists(backup_path):
                    os.remove(backup_path)
            except Exception as e:
                if os.path.exists(backup_path):
                    shutil.copy2(backup_path, abs_path)
                    os.remove(backup_path)
                return {"status": "error", "message": f"Failed to write patched file: {e}"}

        logger.info(f"✅ Successfully patched {os.path.basename(abs_path)} ({len(chunks)} chunk(s))")
        return {
            "status": "success",
            "message": f"Successfully applied patch to '{os.path.basename(abs_path)}' ({len(chunks)} chunks).",
            "file": abs_path,
            "chunks_applied": len(chunks),
            "dry_run": dry_run
        }


# Singleton instance
patch_engine = PatchEngine()


# ─── Tool Wrappers & Declarations ─────────────────────────────────────────────

def apply_patch(target_file: str, diff_content: str, dry_run: bool = False) -> Dict[str, Any]:
    """Applies a unified diff patch to modify code or text files cleanly."""
    return patch_engine.apply_patch(target_file=target_file, diff_content=diff_content, dry_run=dry_run)


PATCH_TOOL_DECLARATIONS = [
    {
        "name": "apply_patch",
        "description": "Applies a unified diff patch (with @@ line headers, - and + lines) to modify a file with AST syntax validation and automatic rollback.",
        "parameters": {
            "type": "object",
            "properties": {
                "target_file": {
                    "type": "string",
                    "description": "Relative or absolute path of the file to patch."
                },
                "diff_content": {
                    "type": "string",
                    "description": "The exact unified diff patch content to apply."
                },
                "dry_run": {
                    "type": "boolean",
                    "description": "If true, simulates patch application without modifying the file on disk."
                }
            },
            "required": ["target_file", "diff_content"]
        }
    }
]

PATCH_TOOL_FUNCTIONS = {
    "apply_patch": apply_patch,
}
