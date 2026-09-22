"""
MAX AI — Authoritative Filesystem & File Control Subsystem
Single canonical, safe, verified, reversible, and high-performance filesystem controller.

Integrates:
- Centralized sandboxed path resolution & special folder aliasing (Desktop, Downloads, Documents, AppData, etc.)
- Safe natural-language & fuzzy path resolution with ambiguous candidate return
- Atomic file writes (.tmp sibling + fsync + os.replace) & pre-overwrite vault snapshots
- Streaming chunked reading with auto-encoding detection (UTF-8, UTF-16, ANSI) & line-range slicing
- Native archive handling (.zip, .tar.gz) with strict Zip-Slip traversal protection
- Cross-volume atomic move & verification with fallback
- Cryptographic hashing (SHA-256, MD5, SHA-1) & duplicate file discovery
- Batch renaming with preview & transactional rollback
- Desktop organizer with transactional grouping
- Dual-tier undo engine (in-memory LIFO + disk-backed vault & persistent journal)
"""

import os
import sys
import glob
import time
import uuid
import shutil
import zipfile
import tarfile
import logging
import subprocess
from pathlib import Path
from typing import Tuple, Optional, Dict, Any, List, Union

from core.security import security_guard
from core.verifier import action_verifier
from core.undo_manager import undo_manager
from core.safe_fs import safe_fs

logger = logging.getLogger("MAXWindowsAgent.FileManager")

# 1 MB threshold for text file undo content snapshots in memory; larger files use disk vault
UNDO_CONTENT_LIMIT_BYTES = 1_048_576

# Windows Well-Known Special Folders
USER_HOME = os.path.abspath(os.path.realpath(os.path.expanduser("~")))
SPECIAL_FOLDERS = {
    "desktop": os.path.join(USER_HOME, "Desktop"),
    "downloads": os.path.join(USER_HOME, "Downloads"),
    "download": os.path.join(USER_HOME, "Downloads"),
    "documents": os.path.join(USER_HOME, "Documents"),
    "document": os.path.join(USER_HOME, "Documents"),
    "pictures": os.path.join(USER_HOME, "Pictures"),
    "photos": os.path.join(USER_HOME, "Pictures"),
    "music": os.path.join(USER_HOME, "Music"),
    "videos": os.path.join(USER_HOME, "Videos"),
    "home": USER_HOME,
    "user": USER_HOME,
    "temp": os.path.abspath(os.environ.get("TEMP", "C:\\Temp")),
    "tmp": os.path.abspath(os.environ.get("TEMP", "C:\\Temp")),
    "appdata": os.path.abspath(os.environ.get("APPDATA", os.path.join(USER_HOME, "AppData", "Roaming"))),
    "localappdata": os.path.abspath(os.environ.get("LOCALAPPDATA", os.path.join(USER_HOME, "AppData", "Local"))),
    "programdata": os.path.abspath(os.environ.get("ProgramData", "C:\\ProgramData")),
}


def _restore_from_recycle_bin(original_path: str) -> Tuple[bool, str]:
    """Attempts to restore a deleted file from the Windows Recycle Bin using Shell.Application."""
    if sys.platform != "win32":
        return False, "Recycle Bin restore is only supported on Windows."

    try:
        import win32com.client
        shell = win32com.client.Dispatch("Shell.Application")
        bin_folder = shell.NameSpace(10)  # ssfBITBUCKET
        orig_p = os.path.abspath(original_path)
        orig_dir = os.path.dirname(orig_p).lower()
        orig_name = os.path.basename(orig_p).lower()

        for item in bin_folder.Items():
            item_parent = str(bin_folder.GetDetailsOf(item, 1)).strip().lower()
            item_name = str(item.Name).strip().lower()
            if item_parent == orig_dir and item_name == orig_name:
                item.InvokeVerb("UNDELETE")
                if os.path.exists(orig_p):
                    return True, f"'{os.path.basename(original_path)}' restored from Recycle Bin."

        return False, f"'{os.path.basename(original_path)}' was not found in the Recycle Bin."
    except Exception as e:
        return False, f"Recycle Bin restore failed: {e}"


def _resolve_user_path(path: str = None) -> str:
    """
    Robustly resolves a path string into a canonical absolute path.
    Seamlessly handles special folder aliases ('Desktop', 'Downloads', 'Documents', 'AppData', etc.)
    and relative subpaths ('Desktop/rocky/rocky.txt', 'Downloads/file.pdf').
    Rejects path traversal outside the authorized sandbox.
    """
    if not path or not str(path).strip():
        return USER_HOME

    raw = str(path).strip().strip('"').strip("'")
    raw_normalized = raw.replace("\\", "/").rstrip("/")

    # Strict Traversal Rejection
    parts = [p for p in raw_normalized.split("/") if p]
    if ".." in parts:
        raise PermissionError(f"Path traversal detected in '{path}'.")

    # Check for direct absolute Windows path (e.g. C:\... or C:/...)
    expanded = os.path.expanduser(os.path.expandvars(raw))
    if os.path.isabs(expanded):
        target = os.path.abspath(expanded)
        if not security_guard.is_safe_path(target):
            raise PermissionError(f"Access denied: '{target}' is outside the authorized sandbox.")
        return target

    raw_lower = raw_normalized.lower()

    # 1. Direct match for special folder name (e.g. "downloads", "desktop", "documents")
    if raw_lower in SPECIAL_FOLDERS:
        return SPECIAL_FOLDERS[raw_lower]

    # 2. Check if path starts with a special folder prefix (e.g. "desktop/rocky/rocky.txt" or "Downloads/report.pdf")
    for key, folder_path in SPECIAL_FOLDERS.items():
        prefix = key + "/"
        if raw_lower.startswith(prefix):
            remainder = raw_normalized[len(prefix):].lstrip("/\\")
            target = os.path.abspath(os.path.join(folder_path, remainder.replace("/", os.sep)))
            if not security_guard.is_safe_path(target):
                raise PermissionError(f"Access denied: '{target}' is outside the authorized sandbox.")
            return target

    # 3. Relative path fallback: resolve relative to user's home directory
    target = os.path.abspath(os.path.join(USER_HOME, expanded.replace("/", os.sep)))
    if not security_guard.is_safe_path(target):
        raise PermissionError(f"Access denied: '{target}' is outside the authorized sandbox.")
    return target


def resolve_path(path: str = None) -> str:
    """Public canonical path resolution export."""
    return _resolve_user_path(path)


# ─── 1. Fuzzy Path & Natural Language Resolver ───────────────────────────────

def fuzzy_resolve_path(query: str, search_roots: Optional[List[str]] = None, **kwargs) -> dict:
    """
    Safely resolves a natural language or imprecise file query into an exact file path.
    Example: 'quarterly report in downloads' -> Downloads\\Quarterly_Report_2026.pdf
    Returns structured candidate list if ambiguous.
    """
    if not query or not str(query).strip():
        return {"status": "error", "message": "Search query is required."}

    raw_query = str(query).strip().strip('"').strip("'")

    # If it's already a direct path that exists, resolve immediately
    try:
        direct = _resolve_user_path(raw_query)
        if os.path.exists(direct):
            return {"status": "success", "path": direct, "confidence": 1.0, "match_type": "exact"}
    except Exception:
        pass

    # Determine candidate search folders
    roots_to_scan = []
    q_lower = raw_query.lower()

    # Check if a folder keyword is mentioned in the query
    target_folder_found = None
    for k, f_path in SPECIAL_FOLDERS.items():
        if k in q_lower and os.path.isdir(f_path):
            roots_to_scan.append(f_path)
            target_folder_found = k

    if not roots_to_scan:
        if search_roots:
            for r in search_roots:
                try:
                    resolved_r = _resolve_user_path(r)
                    if os.path.isdir(resolved_r):
                        roots_to_scan.append(resolved_r)
                except Exception:
                    pass
        else:
            roots_to_scan = [SPECIAL_FOLDERS["desktop"], SPECIAL_FOLDERS["downloads"], SPECIAL_FOLDERS["documents"]]

    # Extract search tokens
    # Remove folder names and common stop words from query
    stop_words = {"in", "on", "the", "my", "a", "an", "file", "folder", "open", "read", "find", "get"}
    if target_folder_found:
        stop_words.add(target_folder_found)

    tokens = [t for t in q_lower.replace("\\", " ").replace("/", " ").replace(".", " .").split() if t not in stop_words]
    if not tokens:
        tokens = [q_lower]

    candidates = []
    for root in roots_to_scan:
        if not os.path.isdir(root):
            continue
        try:
            for dirpath, _, filenames in os.walk(root):
                # Max depth 3 to avoid long hangs
                rel = os.path.relpath(dirpath, root)
                if rel != "." and len(rel.split(os.sep)) > 3:
                    continue

                for fn in filenames:
                    fn_lower = fn.lower()
                    full_p = os.path.join(dirpath, fn)

                    # Score match
                    score = 0.0
                    if raw_query.lower() == fn_lower:
                        score = 1.0
                    elif raw_query.lower() in fn_lower:
                        score = 0.85
                    else:
                        matched_tokens = sum(1 for t in tokens if t in fn_lower)
                        if matched_tokens > 0:
                            score = 0.4 + (0.5 * (matched_tokens / len(tokens)))

                    if score >= 0.4:
                        candidates.append({
                            "path": full_p,
                            "name": fn,
                            "score": round(score, 2),
                            "size_bytes": os.path.getsize(full_p) if os.path.exists(full_p) else 0,
                            "folder": dirpath
                        })
        except Exception as se:
            logger.debug(f"Error scanning folder '{root}': {se}")

    candidates.sort(key=lambda x: x["score"], reverse=True)

    if not candidates:
        return {"status": "not_found", "message": f"No matching files found for query '{query}'."}

    # If top candidate is high confidence and clearly ahead of second
    top = candidates[0]
    if top["score"] >= 0.8 and (len(candidates) == 1 or (top["score"] - candidates[1]["score"] >= 0.2)):
        return {
            "status": "success",
            "path": top["path"],
            "name": top["name"],
            "confidence": top["score"],
            "match_type": "fuzzy_winner"
        }

    # If top candidates are close in score, return ambiguous response
    return {
        "status": "ambiguous",
        "message": f"Multiple potential matching files found for '{query}'. Please specify.",
        "candidates": candidates[:5]
    }


# ─── 2. Atomic File Creation & Writing ───────────────────────────────────────

def create_file(path: str = None, content: str = "", **kwargs) -> dict:
    """
    Create a new file with atomic write semantics and rollback support.
    """
    try:
        raw_path = path or kwargs.get("file_path") or kwargs.get("target")
        file_name = kwargs.get("file_name") or kwargs.get("name")
        location = kwargs.get("location") or kwargs.get("folder") or kwargs.get("in_folder") or kwargs.get("directory")
        text_content = content if content is not None else kwargs.get("text", "")

        if not raw_path:
            if file_name and location:
                loc_resolved = _resolve_user_path(location)
                target_path = os.path.abspath(os.path.join(loc_resolved, file_name))
            elif file_name:
                target_path = os.path.abspath(os.path.join(SPECIAL_FOLDERS["desktop"], file_name))
            else:
                return {"status": "error", "message": "File path or name is required.", "verified": False}
        else:
            if file_name and not raw_path.endswith(file_name):
                base_resolved = _resolve_user_path(raw_path)
                target_path = os.path.abspath(os.path.join(base_resolved, file_name))
            else:
                target_path = _resolve_user_path(raw_path)

        if not security_guard.is_safe_path(target_path):
            return {"status": "error", "message": f"Access denied: Cannot create file '{target_path}' outside sandbox.", "verified": False}

        file_existed = os.path.exists(target_path) and os.path.isfile(target_path)
        vault_snapshot = None
        if file_existed:
            vault_snapshot = safe_fs.snapshot_for_overwrite(target_path)

        # Ensure parent directory exists
        parent_dir = os.path.dirname(target_path)
        if parent_dir and not os.path.exists(parent_dir):
            os.makedirs(parent_dir, exist_ok=True)

        # Atomic Write Pattern: Write to temporary sibling file on same volume, then replace
        temp_file = os.path.join(parent_dir, f".tmp_{uuid.uuid4().hex[:8]}_{os.path.basename(target_path)}")
        try:
            with open(temp_file, "w", encoding="utf-8") as f:
                f.write(text_content)
                f.flush()
                os.fsync(f.fileno())
            os.replace(temp_file, target_path)
        finally:
            if os.path.exists(temp_file):
                try:
                    os.remove(temp_file)
                except Exception:
                    pass

        verified = action_verifier.verify_file_created_sync(target_path, text_content if text_content else None)
        if not verified:
            return {"status": "error", "message": f"File creation verification failed for: {target_path}", "path": target_path, "verified": False}

        # Register Undo Closure
        if not file_existed:
            def _rollback_create_file(p=target_path):
                if not os.path.exists(p):
                    return False, f"File '{os.path.basename(p)}' no longer exists."
                try:
                    os.remove(p)
                    return True, f"Removed created file '{os.path.basename(p)}'."
                except Exception as ex:
                    return False, f"Could not remove file: {ex}"

            op_id = undo_manager.push_undo(
                description=f"Create file '{os.path.basename(target_path)}'",
                undo_fn=_rollback_create_file,
                metadata={"type": "create_file", "path": target_path},
            )
        else:
            def _rollback_overwrite_file(p=target_path, snap=vault_snapshot):
                if snap and os.path.exists(snap):
                    try:
                        shutil.copy2(snap, p)
                        return True, f"Restored overwritten content of '{os.path.basename(p)}'."
                    except Exception as ex:
                        return False, f"Could not restore snapshot: {ex}"
                return False, "Snapshot missing for rollback."

            op_id = undo_manager.push_undo(
                description=f"Overwrite file '{os.path.basename(target_path)}'",
                undo_fn=_rollback_overwrite_file,
                metadata={"type": "overwrite_file", "path": target_path, "vault_snapshot": vault_snapshot},
            )

        return {
            "status": "success",
            "operation": "create_file",
            "message": f"Created text file: {os.path.basename(target_path)}",
            "path": target_path,
            "bytes_written": len(text_content.encode("utf-8")),
            "operation_id": op_id,
            "verified": True
        }
    except Exception as e:
        logger.error(f"Error creating file: {e}")
        return {"status": "error", "message": f"Failed to create file: {str(e)}", "verified": False}


def create_text_file(path: str = None, content: str = "", **kwargs) -> dict:
    """Explicit tool alias for creating a text file."""
    return create_file(path=path, content=content, **kwargs)


def write_file(path: str = None, content: str = "", append: bool = False, encoding: str = "utf-8", **kwargs) -> dict:
    """
    Write or append text content to a target file with atomic replace and verification.
    """
    try:
        raw_path = path or kwargs.get("file_path") or kwargs.get("target")
        file_name = kwargs.get("file_name") or kwargs.get("name")
        location = kwargs.get("location") or kwargs.get("folder") or kwargs.get("in_folder") or kwargs.get("directory")
        text_content = content if content is not None else kwargs.get("text", kwargs.get("data", ""))
        is_append = bool(append or kwargs.get("is_append", False))

        if not raw_path:
            if file_name and location:
                loc_resolved = _resolve_user_path(location)
                target_path = os.path.abspath(os.path.join(loc_resolved, file_name))
            elif file_name:
                target_path = os.path.abspath(os.path.join(SPECIAL_FOLDERS["desktop"], file_name))
            else:
                return {"status": "error", "message": "File path or name is required.", "verified": False}
        else:
            if file_name and not raw_path.endswith(file_name):
                base_resolved = _resolve_user_path(raw_path)
                target_path = os.path.abspath(os.path.join(base_resolved, file_name))
            else:
                target_path = _resolve_user_path(raw_path)

        if not security_guard.is_safe_path(target_path):
            return {"status": "error", "message": f"Access denied: Cannot write file '{target_path}' outside sandbox.", "verified": False}

        file_existed = os.path.exists(target_path) and os.path.isfile(target_path)
        vault_snapshot = safe_fs.snapshot_for_overwrite(target_path) if file_existed else None

        parent_dir = os.path.dirname(target_path)
        if parent_dir and not os.path.exists(parent_dir):
            os.makedirs(parent_dir, exist_ok=True)

        if is_append and file_existed:
            # Atomic append: read existing, append, write to temp, replace
            try:
                with open(target_path, "r", encoding=encoding, errors="replace") as f_curr:
                    existing_text = f_curr.read()
            except Exception:
                existing_text = ""
            full_write_content = existing_text + text_content
        else:
            full_write_content = text_content

        temp_file = os.path.join(parent_dir, f".tmp_{uuid.uuid4().hex[:8]}_{os.path.basename(target_path)}")
        try:
            with open(temp_file, "w", encoding=encoding) as f:
                f.write(full_write_content)
                f.flush()
                os.fsync(f.fileno())
            os.replace(temp_file, target_path)
        finally:
            if os.path.exists(temp_file):
                try:
                    os.remove(temp_file)
                except Exception:
                    pass

        verified = action_verifier.verify_file_content_sync(target_path, full_write_content)
        if not verified:
            return {"status": "error", "message": f"Write content verification failed for: {target_path}", "path": target_path, "verified": False}

        if not file_existed:
            def _rollback_write_new(p=target_path):
                if os.path.exists(p):
                    try:
                        os.remove(p)
                        return True, f"Removed written file '{os.path.basename(p)}'."
                    except Exception as ex:
                        return False, f"Could not remove file: {ex}"
                return False, "File not found for rollback."

            op_id = undo_manager.push_undo(
                description=f"Write file '{os.path.basename(target_path)}'",
                undo_fn=_rollback_write_new,
                metadata={"type": "write_file", "path": target_path},
            )
        else:
            def _rollback_write_existing(p=target_path, snap=vault_snapshot):
                if snap and os.path.exists(snap):
                    try:
                        shutil.copy2(snap, p)
                        return True, f"Restored previous content of '{os.path.basename(p)}'."
                    except Exception as ex:
                        return False, f"Could not restore file: {ex}"
                return False, "Snapshot missing for rollback."

            op_id = undo_manager.push_undo(
                description=f"Overwrite/Append file '{os.path.basename(target_path)}'",
                undo_fn=_rollback_write_existing,
                metadata={"type": "write_file", "path": target_path, "vault_snapshot": vault_snapshot},
            )

        return {
            "status": "success",
            "operation": "write_file",
            "message": f"Wrote {len(text_content)} characters to {os.path.basename(target_path)}",
            "path": target_path,
            "bytes_written": len(text_content.encode(encoding)),
            "operation_id": op_id,
            "verified": True
        }
    except Exception as e:
        logger.error(f"Error writing file: {e}")
        return {"status": "error", "message": f"Failed to write file: {str(e)}", "verified": False}


# ─── 3. Streaming File Reading with Encoding Detection & Line Slicing ────────

def _detect_encoding_and_read(file_path: str, offset: int = 0, limit: Optional[int] = None) -> Tuple[str, str, bool]:
    """
    Detects file encoding (BOM, UTF-8, UTF-16, ANSI) and safely reads content.
    Returns (content_str, encoding_name, is_binary).
    """
    with open(file_path, "rb") as f:
        header = f.read(4)
        f.seek(offset)
        raw_bytes = f.read(limit) if limit else f.read()

    # 1. BOM Detection
    if header.startswith(b"\xef\xbb\xbf"):
        try:
            return raw_bytes.decode("utf-8-sig"), "utf-8-sig", False
        except Exception:
            pass
    elif header.startswith(b"\xff\xfe\x00\x00") or header.startswith(b"\x00\x00\xfe\xff"):
        try:
            return raw_bytes.decode("utf-32"), "utf-32", False
        except Exception:
            pass
    elif header.startswith(b"\xff\xfe") or header.startswith(b"\xfe\xff"):
        try:
            return raw_bytes.decode("utf-16"), "utf-16", False
        except Exception:
            pass

    # 2. Binary File Heuristic (null bytes or high concentration of control characters)
    check_chunk = raw_bytes[:1024]
    if b"\x00" in check_chunk:
        # Check if non-BOM UTF-16
        try:
            decoded = raw_bytes.decode("utf-16")
            # Ensure decoded string consists of printable/text characters
            control_chars = sum(1 for ch in decoded[:256] if ord(ch) < 32 and ch not in "\r\n\t\b")
            if control_chars < len(decoded[:256]) * 0.1:
                return decoded, "utf-16", False
        except Exception:
            pass

        # Genuine binary file
        preview_hex = raw_bytes[:64].hex(" ")
        return f"[Binary File - Size: {len(raw_bytes)} bytes | Preview: {preview_hex}]", "binary", True

    # 3. Try UTF-8
    try:
        text = raw_bytes.decode("utf-8")
        return text, "utf-8", False
    except UnicodeDecodeError:
        pass

    # 4. Fallback to Windows-1252 ANSI
    try:
        text = raw_bytes.decode("windows-1252")
        return text, "windows-1252", False
    except Exception:
        text = raw_bytes.decode("utf-8", errors="replace")
        return text, "utf-8-lossy", False


def read_file(
    path: str = None,
    start_line: Optional[int] = None,
    end_line: Optional[int] = None,
    offset: int = 0,
    limit: Optional[int] = None,
    tail: Optional[int] = None,
    encoding: Optional[str] = None,
    **kwargs
) -> dict:
    """
    Read content from a file with auto-encoding detection, line slicing, and streaming support.
    
    Args:
        path: Path to the file to read.
        start_line: 1-indexed starting line number.
        end_line: 1-indexed ending line number (inclusive).
        tail: If specified, reads only the last N lines.
        offset: Byte offset to begin reading from.
        limit: Max bytes to read.
        encoding: Optional explicit encoding (defaults to auto-detection).
    """
    try:
        raw_path = path or kwargs.get("file_path") or kwargs.get("target")
        if not raw_path:
            return {"status": "error", "message": "File path is required."}

        target_path = _resolve_user_path(raw_path)
        if not security_guard.is_safe_path(target_path):
            return {"status": "error", "message": f"Access denied: Cannot read file '{target_path}' outside sandbox."}

        if not os.path.exists(target_path):
            return {"status": "error", "message": f"File not found: {target_path}"}

        if not os.path.isfile(target_path):
            return {"status": "error", "message": f"Path is a directory, not a file: {target_path}"}

        total_size = os.path.getsize(target_path)

        if encoding:
            with open(target_path, "r", encoding=encoding, errors="replace") as f:
                if offset > 0:
                    f.seek(offset)
                content = f.read(limit) if limit else f.read()
            detected_enc = encoding
            is_bin = False
        else:
            content, detected_enc, is_bin = _detect_encoding_and_read(target_path, offset=offset, limit=limit)

        if is_bin:
            return {
                "status": "success",
                "path": target_path,
                "content": content,
                "encoding": "binary",
                "is_binary": True,
                "size_bytes": total_size,
                "verified": True
            }

        lines = content.splitlines()
        total_lines = len(lines)
        truncated = False

        if tail is not None and int(tail) > 0:
            tail_count = int(tail)
            lines = lines[-tail_count:]
            content = "\n".join(lines)
            start_l = max(1, total_lines - tail_count + 1)
            end_l = total_lines
            truncated = total_lines > tail_count
        elif start_line is not None or end_line is not None:
            s_idx = max(0, int(start_line or 1) - 1)
            e_idx = int(end_line) if end_line is not None else total_lines
            lines = lines[s_idx:e_idx]
            content = "\n".join(lines)
            start_l = s_idx + 1
            end_l = min(e_idx, total_lines)
            truncated = (s_idx > 0 or e_idx < total_lines)
        else:
            start_l = 1
            end_l = total_lines

        return {
            "status": "success",
            "path": target_path,
            "content": content,
            "encoding": detected_enc,
            "total_lines": total_lines,
            "start_line": start_l,
            "end_line": end_l,
            "truncated": truncated,
            "size_bytes": total_size,
            "is_binary": False,
            "verified": True
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to read file: {str(e)}"}


# ─── 4. Directory Management & Tree Visualization ────────────────────────────

def create_folder(path: str = None, **kwargs) -> dict:
    """
    Create a new folder/directory.
    """
    try:
        raw_path = path or kwargs.get("folder_path") or kwargs.get("dir_path")
        folder_name = kwargs.get("folder_name") or kwargs.get("name")
        location = kwargs.get("location") or kwargs.get("parent_dir") or kwargs.get("in_folder")

        if not raw_path:
            if folder_name and location:
                loc_resolved = _resolve_user_path(location)
                target_path = os.path.abspath(os.path.join(loc_resolved, folder_name))
            elif folder_name:
                target_path = os.path.abspath(os.path.join(SPECIAL_FOLDERS["desktop"], folder_name))
            else:
                return {"status": "error", "message": "Folder path or name is required."}
        else:
            if folder_name and not raw_path.endswith(folder_name):
                base_resolved = _resolve_user_path(raw_path)
                target_path = os.path.abspath(os.path.join(base_resolved, folder_name))
            else:
                target_path = _resolve_user_path(raw_path)

        if not security_guard.is_safe_path(target_path):
            return {"status": "error", "message": f"Access denied: Cannot create folder '{target_path}' outside sandbox."}

        common_file_exts = (".txt", ".csv", ".py", ".json", ".md", ".doc", ".docx", ".pdf", ".log", ".html", ".xml", ".yaml", ".yml", ".ini", ".cfg", ".png", ".jpg", ".zip")
        if any(target_path.lower().endswith(ext) for ext in common_file_exts):
            return {
                "status": "error",
                "message": f"Cannot create folder '{os.path.basename(target_path)}' because it ends with a file extension. Use 'create_text_file' instead.",
                "verified": False
            }

        if os.path.exists(target_path):
            return {
                "status": "info",
                "message": f"Folder already exists: {target_path}",
                "path": target_path,
                "verified": os.path.isdir(target_path)
            }

        os.makedirs(target_path, exist_ok=True)
        verified = action_verifier.verify_folder_created_sync(target_path)

        def _rollback_create_folder(p=target_path):
            if not os.path.exists(p):
                return False, f"Folder '{os.path.basename(p)}' no longer exists."
            if not os.path.isdir(p):
                return False, f"Path '{os.path.basename(p)}' is not a directory."
            if os.listdir(p):
                return False, f"Folder '{os.path.basename(p)}' is not empty; refusing to delete."
            try:
                os.rmdir(p)
                return True, f"Removed created folder '{os.path.basename(p)}'."
            except Exception as ex:
                return False, f"Could not remove folder: {ex}"

        op_id = undo_manager.push_undo(
            description=f"Create folder '{os.path.basename(target_path)}'",
            undo_fn=_rollback_create_folder,
            metadata={"type": "create_folder", "path": target_path},
        )

        return {
            "status": "success",
            "operation": "create_folder",
            "message": f"Created folder: {os.path.basename(target_path)}",
            "path": target_path,
            "operation_id": op_id,
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to create folder: {str(e)}"}


def get_folder_contents(path: str = None, **kwargs) -> dict:
    """List the contents of a directory with detailed metadata and sorting."""
    try:
        raw_path = path or kwargs.get("folder") or kwargs.get("directory") or "Desktop"
        target_dir = _resolve_user_path(raw_path)

        if not security_guard.is_safe_path(target_dir):
            return {"status": "error", "message": f"Access denied: Path '{target_dir}' is outside sandbox."}

        if not os.path.isdir(target_dir):
            return {"status": "error", "message": f"Directory not found: {target_dir}"}

        items = []
        for item in sorted(os.listdir(target_dir)):
            full_path = os.path.join(target_dir, item)
            try:
                stat = os.stat(full_path)
                is_d = os.path.isdir(full_path)
                items.append({
                    "name": item,
                    "path": full_path,
                    "type": "directory" if is_d else "file",
                    "is_directory": is_d,
                    "size_bytes": stat.st_size if not is_d else 0,
                    "size_kb": round(stat.st_size / 1024, 1) if not is_d else None,
                    "modified_at": int(stat.st_mtime),
                    "created_at": int(stat.st_ctime)
                })
            except OSError:
                items.append({"name": item, "path": full_path, "type": "unknown", "is_directory": False, "size_kb": None})

        items.sort(key=lambda x: (0 if x.get("is_directory") else 1, x.get("name", "").lower()))

        return {
            "status": "success",
            "path": target_dir,
            "contents": items[:100],
            "total_items": len(items),
            "total_files": sum(1 for i in items if not i.get("is_directory")),
            "total_dirs": sum(1 for i in items if i.get("is_directory")),
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def list_directory_tree(directory_path: str = ".", max_depth: int = 2, **kwargs) -> dict:
    """Generates a structured ASCII directory tree with depth control."""
    try:
        base_dir = _resolve_user_path(directory_path)
        if not os.path.exists(base_dir):
            return {"status": "error", "message": f"Directory '{directory_path}' does not exist."}

        tree_lines = []
        base_p = Path(base_dir)

        def build_tree(current_dir: Path, prefix: str = "", depth: int = 0):
            if depth > max_depth:
                return
            try:
                entries = sorted(list(current_dir.iterdir()), key=lambda e: (not e.is_dir(), e.name.lower()))
                entries = [e for e in entries if e.name not in (".git", "node_modules", "__pycache__", ".venv")]
                for i, entry in enumerate(entries):
                    is_last = (i == len(entries) - 1)
                    connector = "└── " if is_last else "├── "
                    tree_lines.append(f"{prefix}{connector}{entry.name}{'/' if entry.is_dir() else ''}")
                    if entry.is_dir():
                        ext_prefix = "    " if is_last else "│   "
                        build_tree(entry, prefix + ext_prefix, depth + 1)
            except PermissionError:
                tree_lines.append(f"{prefix}└── [Permission Denied]")

        tree_lines.append(base_p.name + "/")
        build_tree(base_p, depth=1)

        return {
            "status": "success",
            "directory": str(base_p),
            "tree": "\n".join(tree_lines),
            "line_count": len(tree_lines)
        }
    except Exception as e:
        return {"status": "error", "message": f"Tree build error: {e}"}


# ─── 5. Move & Copy with Cross-Volume Support ────────────────────────────────

def move_file(source: str, destination: str, overwrite: bool = False, **kwargs) -> dict:
    """
    Move or relocate a file/folder with cross-volume safety and verification.
    """
    try:
        src = _resolve_user_path(source)
        dst = _resolve_user_path(destination)

        if not security_guard.is_safe_path(src) or not security_guard.is_safe_path(dst):
            return {"status": "error", "message": "Access denied: Source and destination must reside within sandbox."}

        if not os.path.exists(src):
            return {"status": "error", "message": f"Source not found: {src}"}

        if os.path.isdir(dst):
            actual_dst = os.path.join(dst, os.path.basename(src))
        else:
            actual_dst = dst

        # Destination overwrite check & backup
        dst_snapshot = None
        if os.path.exists(actual_dst):
            if not overwrite:
                return {"status": "error", "message": f"Destination already exists: {actual_dst}. Set overwrite=True to replace."}
            dst_snapshot = safe_fs.snapshot_for_overwrite(actual_dst)

        # Check if source and destination are on different drive letters (cross-volume)
        src_drive = os.path.splitdrive(src)[0].lower()
        dst_drive = os.path.splitdrive(actual_dst)[0].lower()
        is_cross_volume = src_drive != dst_drive

        if is_cross_volume:
            # Cross-volume pattern: Copy -> Verify -> Delete source
            if os.path.isdir(src):
                shutil.copytree(src, actual_dst, dirs_exist_ok=overwrite)
            else:
                os.makedirs(os.path.dirname(actual_dst), exist_ok=True)
                shutil.copy2(src, actual_dst)

            verified_copy = action_verifier.verify_file_copied_sync(src, actual_dst, check_hash=os.path.isfile(src))
            if not verified_copy:
                if os.path.exists(actual_dst):
                    try:
                        os.remove(actual_dst) if os.path.isfile(actual_dst) else shutil.rmtree(actual_dst)
                    except Exception:
                        pass
                return {"status": "error", "message": "Cross-volume move verification failed during copy phase. Source preserved."}

            # Delete source safely via safe_fs
            del_res = safe_fs.safe_delete(src)
            if del_res.get("status") != "success" and os.path.exists(src):
                # Fallback removal
                os.remove(src) if os.path.isfile(src) else shutil.rmtree(src)
        else:
            os.makedirs(os.path.dirname(actual_dst), exist_ok=True)
            shutil.move(src, actual_dst)

        verified = action_verifier.verify_file_moved_sync(src, actual_dst)

        def _rollback_move_file(s=src, d=actual_dst, snap=dst_snapshot):
            if not os.path.exists(d):
                return False, f"Target '{os.path.basename(d)}' no longer exists at destination."
            if os.path.exists(s):
                return False, f"Original location '{s}' is already occupied."
            try:
                shutil.move(d, s)
                if snap and os.path.exists(snap):
                    shutil.copy2(snap, d)
                return True, f"Moved '{os.path.basename(d)}' back to '{s}'."
            except Exception as ex:
                return False, f"Could not move file back: {ex}"

        op_id = undo_manager.push_undo(
            description=f"Move '{os.path.basename(src)}' to '{actual_dst}'",
            undo_fn=_rollback_move_file,
            metadata={"type": "move_file", "src": src, "dst": actual_dst, "snapshot": dst_snapshot},
        )

        return {
            "status": "success",
            "operation": "move_file",
            "message": f"Moved {os.path.basename(src)} to {actual_dst}",
            "source": src,
            "destination": actual_dst,
            "operation_id": op_id,
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def copy_file(source: str, destination: str, overwrite: bool = False, check_hash: bool = False, **kwargs) -> dict:
    """Copy a file or directory with size/hash verification and undo tracking."""
    try:
        src = _resolve_user_path(source)
        dst = _resolve_user_path(destination)

        if not security_guard.is_safe_path(src) or not security_guard.is_safe_path(dst):
            return {"status": "error", "message": "Access denied: Source and destination must reside within sandbox."}

        if not os.path.exists(src):
            return {"status": "error", "message": f"Source not found: {src}"}

        if os.path.isdir(dst):
            actual_dst = os.path.join(dst, os.path.basename(src))
        else:
            actual_dst = dst

        if os.path.exists(actual_dst) and not overwrite:
            return {"status": "error", "message": f"Destination already exists: {actual_dst}. Set overwrite=True to replace."}

        if os.path.isdir(src):
            shutil.copytree(src, actual_dst, dirs_exist_ok=overwrite)
        else:
            os.makedirs(os.path.dirname(actual_dst), exist_ok=True)
            shutil.copy2(src, actual_dst)

        verified = action_verifier.verify_file_copied_sync(src, actual_dst, check_hash=check_hash)

        def _rollback_copy(d=actual_dst):
            if os.path.exists(d):
                try:
                    if os.path.isdir(d):
                        shutil.rmtree(d)
                    else:
                        os.remove(d)
                    return True, f"Removed copied target '{os.path.basename(d)}'."
                except Exception as ex:
                    return False, f"Could not remove copy: {ex}"
            return False, "Copied file no longer exists."

        op_id = undo_manager.push_undo(
            description=f"Copy '{os.path.basename(src)}' to '{actual_dst}'",
            undo_fn=_rollback_copy,
            metadata={"type": "copy_file", "src": src, "dst": actual_dst},
        )

        return {
            "status": "success",
            "operation": "copy_file",
            "message": f"Copied {os.path.basename(src)} to {actual_dst}",
            "source": src,
            "destination": actual_dst,
            "operation_id": op_id,
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to copy: {str(e)}"}


# ─── 6. Rename & Batch Rename ────────────────────────────────────────────────

def rename_file(source: str, new_name: str, **kwargs) -> dict:
    """Rename a single file or folder in place."""
    try:
        src = _resolve_user_path(source)
        if not os.path.exists(src):
            return {"status": "error", "message": f"File not found: {src}"}

        parent_dir = os.path.dirname(src)
        dst = os.path.join(parent_dir, os.path.basename(new_name))
        
        if os.path.exists(dst):
            return {"status": "error", "message": f"A file named '{new_name}' already exists in that folder."}

        os.rename(src, dst)
        verified = action_verifier.verify_file_moved_sync(src, dst)

        def _rollback_rename(s=src, d=dst):
            if not os.path.exists(d):
                return False, f"Renamed file '{os.path.basename(d)}' no longer exists."
            if os.path.exists(s):
                return False, f"Original name '{os.path.basename(s)}' is already taken."
            try:
                os.rename(d, s)
                return True, f"Renamed '{os.path.basename(d)}' back to '{os.path.basename(s)}'."
            except Exception as ex:
                return False, f"Could not rename file back: {ex}"

        op_id = undo_manager.push_undo(
            description=f"Rename '{os.path.basename(src)}' to '{os.path.basename(dst)}'",
            undo_fn=_rollback_rename,
            metadata={"type": "rename_file", "src": src, "dst": dst},
        )

        return {
            "status": "success",
            "operation": "rename_file",
            "message": f"Renamed {os.path.basename(src)} to {os.path.basename(dst)}",
            "path": dst,
            "operation_id": op_id,
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to rename file: {str(e)}"}


def batch_rename(
    directory: str,
    pattern: str = "",
    replacement: str = "",
    prefix: str = "",
    suffix: str = "",
    numbering_start: Optional[int] = None,
    numbering_padding: int = 3,
    dry_run: bool = False,
    **kwargs
) -> dict:
    """
    Safely renames multiple files matching a pattern or applies sequential numbering.
    Runs preview dry-run or executes as a single transactional rollback unit.
    """
    try:
        import re
        target_dir = _resolve_user_path(directory)
        if not security_guard.is_safe_path(target_dir):
            return {"status": "error", "message": "Access denied: Directory outside sandbox."}
        if not os.path.isdir(target_dir):
            return {"status": "error", "message": f"Directory not found: {target_dir}"}

        entries = sorted([f for f in os.listdir(target_dir) if os.path.isfile(os.path.join(target_dir, f))])
        if pattern:
            regex = re.compile(pattern, re.IGNORECASE)
            matching_files = [f for f in entries if regex.search(f)]
        else:
            matching_files = entries

        if not matching_files:
            return {"status": "info", "message": f"No files matched pattern '{pattern}' in {target_dir}."}

        # Build rename plan
        plan = []
        counter = numbering_start if numbering_start is not None else 1

        for fn in matching_files:
            stem, ext = os.path.splitext(fn)
            new_stem = stem

            if pattern:
                new_stem = regex.sub(replacement, new_stem)

            if numbering_start is not None:
                pad_num = str(counter).zfill(numbering_padding)
                new_stem = f"{new_stem}_{pad_num}" if new_stem else pad_num
                counter += 1

            if prefix:
                new_stem = f"{prefix}{new_stem}"
            if suffix:
                new_stem = f"{new_stem}{suffix}"

            new_fn = f"{new_stem}{ext}"
            old_p = os.path.join(target_dir, fn)
            new_p = os.path.join(target_dir, new_fn)

            if fn != new_fn:
                plan.append({"old_name": fn, "new_name": new_fn, "old_path": old_p, "new_path": new_p})

        if not plan:
            return {"status": "info", "message": "No files required renaming based on the specified criteria."}

        # Collision detection
        new_names_set = set()
        for item in plan:
            if item["new_name"] in new_names_set or (os.path.exists(item["new_path"]) and item["new_path"] != item["old_path"]):
                return {
                    "status": "error",
                    "message": f"Collision detected: '{item['new_name']}' already exists. Aborting batch rename plan.",
                    "plan": plan
                }
            new_names_set.add(item["new_name"])

        if dry_run:
            return {
                "status": "preview",
                "message": f"Dry-run preview: {len(plan)} files will be renamed.",
                "total_items": len(plan),
                "plan": plan
            }

        # Execute as a single Transaction
        tx_id = undo_manager.begin_transaction(f"Batch rename {len(plan)} files in {os.path.basename(target_dir)}")
        executed = []
        try:
            for item in plan:
                os.rename(item["old_path"], item["new_path"])
                executed.append(item)

                def _rollback_single_rename(o=item["old_path"], n=item["new_path"]):
                    if os.path.exists(n):
                        os.rename(n, o)
                        return True, f"Restored {os.path.basename(o)}"
                    return False, f"Could not find {os.path.basename(n)}"

                undo_manager.push_undo(
                    description=f"Rename {item['old_name']} -> {item['new_name']}",
                    undo_fn=_rollback_single_rename
                )

            undo_manager.commit_transaction()
            return {
                "status": "success",
                "operation": "batch_rename",
                "message": f"Successfully renamed {len(executed)} files.",
                "transaction_id": tx_id,
                "renamed_count": len(executed),
                "items": executed,
                "verified": True
            }
        except Exception as exec_err:
            undo_manager.rollback_active_transaction()
            return {"status": "error", "message": f"Batch rename failed mid-execution and was rolled back: {exec_err}"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ─── 7. Safe Delete & Recycle Bin ────────────────────────────────────────────

def delete_file(path: str, **kwargs) -> dict:
    """Delete a file safely to the Windows Recycle Bin with instant rollback."""
    try:
        target = _resolve_user_path(path)
        if not security_guard.is_safe_path(target):
            return {"status": "error", "message": f"Access denied: Cannot delete path '{target}' outside sandbox."}

        if not os.path.exists(target):
            return {"status": "error", "message": f"Not found: {target}"}

        if os.path.isdir(target):
            return {"status": "error", "message": f"Path '{target}' is a directory. Use delete_folder instead."}

        del_res = safe_fs.safe_delete(target)
        verified = action_verifier.verify_path_deleted_sync(target)

        return {
            "status": "success",
            "operation": "delete_file",
            "message": f"Deleted file: {os.path.basename(target)} (Rollback available via undo)",
            "path": target,
            "recycled": del_res.get("recycled", True),
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def delete_folder(path: str, force: bool = False, **kwargs) -> dict:
    """Delete an empty or non-empty folder safely with rollback."""
    try:
        target = _resolve_user_path(path)
        if not security_guard.is_safe_path(target):
            return {"status": "error", "message": f"Access denied: Cannot delete folder '{target}' outside sandbox."}

        if not os.path.exists(target):
            return {"status": "error", "message": f"Not found: {target}"}

        if os.path.isfile(target):
            return {"status": "error", "message": f"Path '{target}' is a file. Use delete_file instead."}

        is_empty = len(os.listdir(target)) == 0
        if not is_empty and not force:
            return {"status": "error", "message": "Folder is not empty. Set force=True to delete recursively."}

        del_res = safe_fs.safe_delete(target)
        verified = action_verifier.verify_path_deleted_sync(target)

        return {
            "status": "success",
            "operation": "delete_folder",
            "message": f"Deleted folder: {os.path.basename(target)}",
            "path": target,
            "recycled": del_res.get("recycled", True),
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def restore_recycle_bin_item(path: str = None, **kwargs) -> dict:
    """Restore a deleted file or folder from the Windows Recycle Bin or safe vault."""
    try:
        raw_path = path or kwargs.get("file_path") or kwargs.get("file_name") or kwargs.get("target")
        if not raw_path:
            return {"status": "error", "message": "File path or name to restore is required."}

        target = _resolve_user_path(raw_path)
        success, msg = _restore_from_recycle_bin(target)
        if not success:
            # Try matching by filename across special folders
            file_name = os.path.basename(target)
            for folder_key in ("desktop", "downloads", "documents"):
                candidate = os.path.join(SPECIAL_FOLDERS[folder_key], file_name)
                success, msg = _restore_from_recycle_bin(candidate)
                if success:
                    target = candidate
                    break

        if success:
            return {
                "status": "success",
                "operation": "restore_recycle_bin_item",
                "message": msg,
                "path": target,
                "verified": True,
            }
        else:
            return {"status": "error", "message": msg}
    except Exception as e:
        return {"status": "error", "message": f"Error restoring item from Recycle Bin: {e}"}


def undelete_file(path: str = None, **kwargs) -> dict:
    """Alias for restore_recycle_bin_item."""
    return restore_recycle_bin_item(path=path, **kwargs)


# ─── 8. Desktop Organizer with Single-Transaction Undo ───────────────────────

def organize_desktop() -> dict:
    """
    Organizes files on the Desktop into categorized subfolders:
    Images, Documents, Media, Code, Archives, Installers.
    Groups all movements into ONE transaction for single-step undo.
    """
    try:
        desktop_dir = SPECIAL_FOLDERS["desktop"]
        if not os.path.exists(desktop_dir):
            return {"status": "error", "message": "Desktop folder not found."}

        ext_map = {
            "Images": {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg", ".ico", ".tiff"},
            "Documents": {".pdf", ".docx", ".doc", ".txt", ".xlsx", ".xls", ".pptx", ".csv", ".md", ".rtf"},
            "Media": {".mp3", ".mp4", ".wav", ".mkv", ".avi", ".flac", ".aac", ".mov", ".wmv"},
            "Code": {".py", ".js", ".ts", ".html", ".css", ".java", ".c", ".cpp", ".json", ".sh", ".ps1"},
            "Archives": {".zip", ".rar", ".7z", ".tar", ".gz", ".bz2"},
            "Installers": {".exe", ".msi"},
        }
        skip_exts = {".lnk", ".url", ".ini"}

        to_move = []
        for entry in os.scandir(desktop_dir):
            if not entry.is_file() or entry.name.startswith("."):
                continue
            ext = os.path.splitext(entry.name)[1].lower()
            if ext in skip_exts:
                continue

            for category, exts in ext_map.items():
                if ext in exts:
                    to_move.append((entry.path, entry.name, category))
                    break

        if not to_move:
            return {"status": "success", "message": "Desktop is already organized. No loose files found."}

        tx_id = undo_manager.begin_transaction("Organize Desktop")
        moved_counts: Dict[str, int] = {}

        for src_path, filename, category in to_move:
            cat_folder = os.path.join(desktop_dir, category)
            os.makedirs(cat_folder, exist_ok=True)
            dst_path = os.path.join(cat_folder, filename)

            try:
                shutil.move(src_path, dst_path)
                moved_counts[category] = moved_counts.get(category, 0) + 1

                def _rollback_organize(s=src_path, d=dst_path):
                    if os.path.exists(d):
                        try:
                            shutil.move(d, s)
                            return True, f"Moved {os.path.basename(d)} back to Desktop"
                        except Exception as e:
                            return False, f"Could not move back: {e}"
                    return False, f"{os.path.basename(d)} missing"

                undo_manager.push_undo(
                    description=f"Move {filename} to {category}",
                    undo_fn=_rollback_organize
                )
            except Exception as me:
                logger.warning(f"Could not organize '{filename}': {me}")

        undo_manager.commit_transaction()
        summary = ", ".join(f"{count} {cat}" for cat, count in moved_counts.items())

        return {
            "status": "success",
            "operation": "organize_desktop",
            "message": f"Organized Desktop: {summary}",
            "transaction_id": tx_id,
            "organized": moved_counts,
            "total_organized": sum(moved_counts.values()),
            "verified": True
        }
    except Exception as e:
        return {"status": "error", "message": f"Desktop organization error: {e}"}


# ─── 9. File Hashing, Storage & Duplicate Finder ─────────────────────────────

def get_file_hash(path: str, algorithm: str = "sha256", **kwargs) -> dict:
    """Computes streaming cryptographic checksum (SHA-256, MD5, SHA-1) of a file."""
    try:
        target = _resolve_user_path(path)
        if not security_guard.is_safe_path(target):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.exists(target) or not os.path.isfile(target):
            return {"status": "error", "message": f"File not found: {target}"}

        h_val = safe_fs.compute_file_hash(target, algorithm=algorithm)
        return {
            "status": "success",
            "path": target,
            "algorithm": algorithm.lower().strip(),
            "hash": h_val,
            "size_bytes": os.path.getsize(target),
            "verified": True
        }
    except Exception as e:
        return {"status": "error", "message": f"Hashing failed: {e}"}


def get_disk_storage(drive_or_path: str = "C:", **kwargs) -> dict:
    """Inspects storage capacity and available disk space for drive volumes."""
    try:
        p_clean = str(drive_or_path).strip().rstrip("\\/")
        if len(p_clean) == 1 and p_clean.isalpha():
            p_clean = f"{p_clean}:\\"
        elif len(p_clean) == 2 and p_clean[1] == ":":
            p_clean = f"{p_clean}\\"

        total, used, free = shutil.disk_usage(p_clean)
        pct_used = round((used / total) * 100, 1) if total > 0 else 0

        return {
            "status": "success",
            "path": p_clean,
            "total_bytes": total,
            "used_bytes": used,
            "free_bytes": free,
            "total_gb": round(total / (1024 ** 3), 2),
            "used_gb": round(used / (1024 ** 3), 2),
            "free_gb": round(free / (1024 ** 3), 2),
            "percent_used": pct_used,
        }
    except Exception as e:
        return {"status": "error", "message": f"Disk inspection error: {e}"}


def find_duplicate_files(directory: str = "Desktop", max_depth: int = 4, min_size_bytes: int = 1024, **kwargs) -> dict:
    """
    Discovers duplicate files in a directory using size grouping and SHA-256 hashing.
    Discovery-only; does not delete files.
    """
    try:
        target_dir = _resolve_user_path(directory)
        if not security_guard.is_safe_path(target_dir):
            return {"status": "error", "message": "Access denied: Path outside sandbox."}
        if not os.path.isdir(target_dir):
            return {"status": "error", "message": f"Directory not found: {target_dir}"}

        # Step 1: Group files by file size
        size_map: Dict[int, List[str]] = {}
        for root, dirs, files in os.walk(target_dir):
            rel = os.path.relpath(root, target_dir)
            if rel != "." and len(rel.split(os.sep)) > max_depth:
                dirs.clear()
                continue

            for fn in files:
                fp = os.path.join(root, fn)
                try:
                    sz = os.path.getsize(fp)
                    if sz >= min_size_bytes:
                        size_map.setdefault(sz, []).append(fp)
                except OSError:
                    continue

        # Step 2: Compute SHA-256 for size candidates (>1 file per size)
        duplicate_clusters = []
        wasted_bytes = 0

        for sz, file_list in size_map.items():
            if len(file_list) < 2:
                continue

            hash_map: Dict[str, List[str]] = {}
            for fp in file_list:
                try:
                    f_hash = safe_fs.compute_file_hash(fp, algorithm="sha256")
                    hash_map.setdefault(f_hash, []).append(fp)
                except Exception:
                    continue

            for h_val, matches in hash_map.items():
                if len(matches) > 1:
                    wasted = (len(matches) - 1) * sz
                    wasted_bytes += wasted
                    duplicate_clusters.append({
                        "hash": h_val,
                        "file_size_bytes": sz,
                        "file_size_mb": round(sz / (1024 * 1024), 2),
                        "count": len(matches),
                        "paths": matches
                    })

        return {
            "status": "success",
            "directory": target_dir,
            "duplicate_groups_found": len(duplicate_clusters),
            "wasted_space_bytes": wasted_bytes,
            "wasted_space_mb": round(wasted_bytes / (1024 * 1024), 2),
            "duplicates": duplicate_clusters
        }
    except Exception as e:
        return {"status": "error", "message": f"Duplicate scan error: {e}"}


def get_largest_files(directory: str = "Desktop", count: int = 10, **kwargs) -> dict:
    """Finds the largest files in a directory (sorted descending by size)."""
    try:
        raw_dir = directory or kwargs.get("folder") or "Desktop"
        target_dir = _resolve_user_path(raw_dir)

        if not security_guard.is_safe_path(target_dir):
            return {"status": "error", "message": f"Access denied: Directory '{target_dir}' is outside sandbox."}

        if not os.path.isdir(target_dir):
            return {"status": "error", "message": f"Directory not found: {target_dir}"}

        max_count = max(1, min(int(count or 10), 50))
        file_list = []

        for root, _, files in os.walk(target_dir):
            for f in files:
                full_p = os.path.join(root, f)
                try:
                    if security_guard.is_safe_path(full_p):
                        sz = os.path.getsize(full_p)
                        file_list.append((sz, full_p))
                except OSError:
                    continue

        file_list.sort(key=lambda x: x[0], reverse=True)
        top_files = []
        for sz, p in file_list[:max_count]:
            top_files.append({
                "path": p,
                "name": os.path.basename(p),
                "size_bytes": sz,
                "size_mb": round(sz / (1024 * 1024), 2),
            })

        return {
            "status": "success",
            "directory": target_dir,
            "count": len(top_files),
            "largest_files": top_files,
        }
    except Exception as e:
        return {"status": "error", "message": f"Error scanning largest files: {e}"}


def get_file_info(path: str, **kwargs) -> dict:
    """Returns detailed file metadata, timestamps, permissions, and type info."""
    try:
        abs_path = _resolve_user_path(path)
        if not os.path.exists(abs_path):
            return {"status": "error", "message": f"Not found: {path}"}

        stat_info = os.stat(abs_path)
        is_dir = os.path.isdir(abs_path)
        ext = os.path.splitext(abs_path)[1].lower() if not is_dir else ""

        info = {
            "path": abs_path,
            "name": os.path.basename(abs_path),
            "type": "directory" if is_dir else "file",
            "is_directory": is_dir,
            "extension": ext,
            "size_bytes": stat_info.st_size,
            "size_kb": round(stat_info.st_size / 1024, 2),
            "size_mb": round(stat_info.st_size / (1024 * 1024), 2),
            "created_at": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stat_info.st_ctime)),
            "modified_at": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stat_info.st_mtime)),
            "accessed_at": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stat_info.st_atime)),
            "hidden": os.path.basename(abs_path).startswith(".") or bool(getattr(stat_info, "st_file_attributes", 0) & 2),
        }

        return {"status": "success", "output": info, **info}
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ─── 10. Native Zip / Archive Operations ─────────────────────────────────────

def create_archive(source: str, output_path: str = "", archive_type: str = "zip", **kwargs) -> dict:
    """Compresses a file or directory into a .zip or .tar.gz archive using native Python."""
    try:
        src = _resolve_user_path(source)
        if not os.path.exists(src):
            return {"status": "error", "message": f"Source not found: {source}"}

        arch_t = archive_type.lower().strip()
        if not output_path:
            ext = ".tar.gz" if arch_t in ("tar", "tar.gz", "tgz") else ".zip"
            dst = src + ext
        else:
            dst = _resolve_user_path(output_path)

        if not security_guard.is_safe_path(dst):
            return {"status": "error", "message": "Access denied: Output path outside sandbox."}

        os.makedirs(os.path.dirname(dst), exist_ok=True)

        if arch_t in ("tar", "tar.gz", "tgz"):
            with tarfile.open(dst, "w:gz") as tar:
                tar.add(src, arcname=os.path.basename(src))
        else:
            with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zipf:
                if os.path.isdir(src):
                    for root, _, files in os.walk(src):
                        for file in files:
                            full_p = os.path.join(root, file)
                            rel_p = os.path.relpath(full_p, src)
                            zipf.write(full_p, arcname=rel_p)
                else:
                    zipf.write(src, arcname=os.path.basename(src))

        verified = os.path.exists(dst) and os.path.getsize(dst) > 0

        def _rollback_archive(p=dst):
            if os.path.exists(p):
                try:
                    os.remove(p)
                    return True, f"Removed created archive '{os.path.basename(p)}'."
                except Exception as ex:
                    return False, f"Could not remove archive: {ex}"
            return False, "Archive not found."

        op_id = undo_manager.push_undo(
            description=f"Create archive '{os.path.basename(dst)}'",
            undo_fn=_rollback_archive,
            metadata={"type": "create_archive", "path": dst},
        )

        return {
            "status": "success",
            "operation": "create_archive",
            "message": f"Created archive: {os.path.basename(dst)}",
            "path": dst,
            "size_bytes": os.path.getsize(dst),
            "operation_id": op_id,
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": f"Archive creation failed: {e}"}


def extract_archive(archive_path: str, destination: str = "", **kwargs) -> dict:
    """Extracts an archive (.zip, .tar.gz) with strict Zip-Slip traversal protection."""
    try:
        src = _resolve_user_path(archive_path)
        if not os.path.exists(src):
            return {"status": "error", "message": f"Archive not found: {archive_path}"}

        if not destination:
            dst = os.path.splitext(src)[0]
        else:
            dst = _resolve_user_path(destination)

        if not security_guard.is_safe_path(dst):
            return {"status": "error", "message": "Access denied: Destination path outside sandbox."}

        os.makedirs(dst, exist_ok=True)
        canonical_dest = os.path.realpath(dst)

        extracted_files = []
        if zipfile.is_zipfile(src):
            with zipfile.ZipFile(src, "r") as zipf:
                for member in zipf.infolist():
                    member_path = os.path.realpath(os.path.join(canonical_dest, member.filename))
                    if not member_path.startswith(canonical_dest + os.sep) and member_path != canonical_dest:
                        return {"status": "error", "message": f"Zip Slip vulnerability detected in member '{member.filename}'."}
                zipf.extractall(canonical_dest)
                extracted_files = zipf.namelist()
        elif tarfile.is_tarfile(src):
            with tarfile.open(src, "r:*") as tar:
                for member in tar.getmembers():
                    member_path = os.path.realpath(os.path.join(canonical_dest, member.name))
                    if not member_path.startswith(canonical_dest + os.sep) and member_path != canonical_dest:
                        return {"status": "error", "message": f"Tar Slip vulnerability detected in member '{member.name}'."}
                tar.extractall(canonical_dest)
                extracted_files = [m.name for m in tar.getmembers()]
        else:
            return {"status": "error", "message": "Unsupported archive format."}

        verified = action_verifier.verify_archive_extracted_sync(canonical_dest, min_files=1)

        return {
            "status": "success",
            "operation": "extract_archive",
            "message": f"Extracted {len(extracted_files)} items to {os.path.basename(canonical_dest)}",
            "destination": canonical_dest,
            "extracted_count": len(extracted_files),
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": f"Extraction failed: {e}"}


def list_archive(archive_path: str, **kwargs) -> dict:
    """Lists entries inside a .zip or .tar archive without extracting."""
    try:
        src = _resolve_user_path(archive_path)
        if not os.path.exists(src):
            return {"status": "error", "message": f"Archive not found: {archive_path}"}

        entries = []
        if zipfile.is_zipfile(src):
            with zipfile.ZipFile(src, "r") as zipf:
                for info in zipf.infolist():
                    entries.append({
                        "name": info.filename,
                        "size_bytes": info.file_size,
                        "compressed_bytes": info.compress_size,
                        "is_dir": info.is_dir(),
                    })
        elif tarfile.is_tarfile(src):
            with tarfile.open(src, "r:*") as tar:
                for member in tar.getmembers():
                    entries.append({
                        "name": member.name,
                        "size_bytes": member.size,
                        "is_dir": member.isdir(),
                    })
        else:
            return {"status": "error", "message": "Unsupported archive format."}

        return {
            "status": "success",
            "archive": src,
            "total_entries": len(entries),
            "entries": entries[:100]
        }
    except Exception as e:
        return {"status": "error", "message": f"Error listing archive: {e}"}


# ─── 11. Search & Launch Operations ──────────────────────────────────────────

def search_files(query: str, directory: str = None, **kwargs) -> dict:
    """Search for files by name or glob pattern in a directory."""
    try:
        dir_raw = directory or kwargs.get("folder") or kwargs.get("path")
        target_dir = _resolve_user_path(dir_raw)

        if not security_guard.is_safe_path(target_dir):
            return {"status": "error", "message": f"Access denied: Directory '{target_dir}' is outside sandbox.", "verified": False}

        if not os.path.isdir(target_dir):
            return {"status": "error", "message": f"Directory not found: {target_dir}", "verified": False}

        if "*" not in query and "?" not in query:
            pattern_query = f"*{query}*"
        else:
            pattern_query = query

        pattern = os.path.join(target_dir, "**", pattern_query)
        matches = glob.glob(pattern, recursive=True)

        max_results = int(kwargs.get("max_results", 20))
        results = []
        for match in matches[:max_results]:
            try:
                if not security_guard.is_safe_path(match):
                    continue
                stat = os.stat(match)
                results.append({
                    "path": match,
                    "name": os.path.basename(match),
                    "size_bytes": stat.st_size,
                    "size_kb": round(stat.st_size / 1024, 1),
                    "is_directory": os.path.isdir(match),
                    "modified_at": int(stat.st_mtime)
                })
            except OSError:
                continue

        return {
            "status": "success",
            "query": query,
            "directory": target_dir,
            "results": results,
            "total_found": len(matches),
            "showing": min(len(matches), max_results),
            "verified": True
        }
    except Exception as e:
        return {"status": "error", "message": str(e), "verified": False}


def open_file(path: str = None, **kwargs) -> dict:
    """Open a file with its default application or a folder in Explorer."""
    try:
        raw_path = path or kwargs.get("file_path") or kwargs.get("file_name") or kwargs.get("target")
        if not raw_path:
            return {"status": "error", "message": "Path is required.", "verified": False}

        target = _resolve_user_path(raw_path)
        if not security_guard.is_safe_path(target, allow_system_executables=True):
            return {"status": "error", "message": f"Access denied: Path '{target}' is outside sandbox.", "verified": False}

        if not os.path.exists(target):
            return {"status": "error", "message": f"Path does not exist: {target}", "verified": False}

        if os.path.isdir(target):
            return open_folder(path=target)

        if not os.path.isfile(target):
            return {"status": "error", "message": f"Target is not a regular file: {target}", "verified": False}

        try:
            os.startfile(target)
        except Exception:
            subprocess.Popen(["cmd.exe", "/c", "start", "", target], shell=False)

        file_name = os.path.basename(target)
        return {
            "status": "success",
            "operation": "open_file",
            "message": f"Opened {file_name}",
            "path": target,
            "verified": True
        }
    except Exception as e:
        return {"status": "error", "message": str(e), "verified": False}


def open_folder(path: str = "Desktop", **kwargs) -> dict:
    """Open a folder in Windows File Explorer."""
    try:
        raw_path = path or kwargs.get("folder_name") or kwargs.get("directory") or kwargs.get("folder") or "Desktop"
        target_dir = _resolve_user_path(raw_path)

        if not security_guard.is_safe_path(target_dir):
            return {"status": "error", "message": f"Access denied: Folder '{target_dir}' is outside sandbox.", "verified": False}

        if not os.path.exists(target_dir):
            return {"status": "error", "message": f"Folder not found: {target_dir}", "verified": False}

        try:
            os.startfile(target_dir)
        except Exception:
            subprocess.Popen(["explorer.exe", target_dir], shell=False)

        folder_name = os.path.basename(target_dir) or target_dir
        return {
            "status": "success",
            "operation": "open_folder",
            "message": f"Opened {folder_name} folder",
            "path": target_dir,
            "verified": True
        }
    except Exception as e:
        return {"status": "error", "message": f"Failed to open folder: {str(e)}", "verified": False}


def undo_last_action(**kwargs) -> dict:
    """Rolls back the most recent reversible action or transaction."""
    return undo_manager.undo()


def list_undo_history(limit: int = 10, **kwargs) -> dict:
    """Lists registered reversible undo records without executing rollback."""
    hist = undo_manager.get_history(limit=int(limit or 10))
    return {"status": "success", "history": hist, "count": len(hist)}


# ─── Tool Declarations ──────────────────────────────────────────────────────

TOOL_DECLARATIONS = [
    {
        "name": "search_files",
        "description": "Search for files by name or pattern in a directory. Supports wildcards like *.pdf, *.docx.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "File name or glob pattern (e.g., '*.pdf', 'report', '*.py')"},
                "directory": {"type": "string", "description": "Directory to search in (e.g. 'Desktop', 'Downloads', 'Documents'). Defaults to user home."},
            },
            "required": ["query"],
        },
    },
    {
        "name": "fuzzy_resolve_path",
        "description": "Safely resolves natural language or imprecise file descriptions (e.g. 'read quarterly report in downloads') into exact file paths.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Natural language query or partial name"},
            },
            "required": ["query"],
        },
    },
    {
        "name": "open_file",
        "description": "Open a file with its default Windows application.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Full path or relative path to the file to open (e.g., 'Downloads/report.pdf', 'Desktop/notes.txt')"}
            },
            "required": ["path"],
        },
    },
    {
        "name": "open_folder",
        "description": "Open a folder in Windows File Explorer (e.g. 'Downloads', 'Desktop', 'Documents', or any folder path).",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Folder name or path to open (e.g. 'Downloads', 'Desktop', 'Documents')"}
            },
            "required": ["path"],
        },
    },
    {
        "name": "create_text_file",
        "description": "Create a new text file (.txt, .md, .py, etc.) with specified content on Desktop or in any directory with atomic write.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path or name to create (e.g. 'Desktop/abc.txt', 'notes.md')"},
                "content": {"type": "string", "description": "Text content to write into the file"}
            },
            "required": ["path"],
        },
    },
    {
        "name": "create_file",
        "description": "Create a new file with specified content at a target path.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path to create (e.g. 'Desktop/test.txt', 'Downloads/notes.md')"},
                "content": {"type": "string", "description": "Content to write into the file"}
            },
            "required": ["path"],
        },
    },
    {
        "name": "write_file",
        "description": "Write text content to a target file with atomic replace and content verification.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path to write (e.g. 'Desktop/test.txt', 'Downloads/notes.md')"},
                "content": {"type": "string", "description": "Text content to write into the file"},
                "append": {"type": "boolean", "description": "Whether to append to file instead of overwrite (default: False)"}
            },
            "required": ["path", "content"],
        },
    },
    {
        "name": "read_file",
        "description": "Read text content from a file with auto-encoding detection, line slicing, and streaming support.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path to read (e.g. 'Desktop/notes.txt', 'Downloads/report.csv')"},
                "start_line": {"type": "integer", "description": "Optional 1-indexed starting line number"},
                "end_line": {"type": "integer", "description": "Optional 1-indexed ending line number"},
                "tail": {"type": "integer", "description": "Optional number of trailing lines to read"},
                "encoding": {"type": "string", "description": "Optional explicit encoding (default: auto-detect)"}
            },
            "required": ["path"],
        },
    },
    {
        "name": "create_folder",
        "description": "Create a new directory/folder. DO NOT use for text files. (e.g. 'Desktop/MyFolder', 'Downloads/Project').",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Folder path to create (e.g. 'Desktop/NewFolder', 'Downloads/Project')"}
            },
            "required": ["path"],
        },
    },
    {
        "name": "copy_file",
        "description": "Copy a file or directory to a new destination with verification.",
        "parameters": {
            "type": "object",
            "properties": {
                "source": {"type": "string", "description": "Source file path"},
                "destination": {"type": "string", "description": "Destination file path or directory"},
                "overwrite": {"type": "boolean", "description": "Whether to overwrite destination (default: False)"}
            },
            "required": ["source", "destination"],
        },
    },
    {
        "name": "rename_file",
        "description": "Rename a file or folder in place.",
        "parameters": {
            "type": "object",
            "properties": {
                "source": {"type": "string", "description": "Current file path"},
                "new_name": {"type": "string", "description": "New file name (e.g. 'new_name.txt')"},
            },
            "required": ["source", "new_name"],
        },
    },
    {
        "name": "batch_rename",
        "description": "Safely rename multiple files in a directory matching a pattern with dry-run preview and undo.",
        "parameters": {
            "type": "object",
            "properties": {
                "directory": {"type": "string", "description": "Directory containing files to rename"},
                "pattern": {"type": "string", "description": "Regex or substring pattern to match"},
                "replacement": {"type": "string", "description": "Replacement string for matched pattern"},
                "prefix": {"type": "string", "description": "Optional prefix to add to names"},
                "suffix": {"type": "string", "description": "Optional suffix to add before extension"},
                "numbering_start": {"type": "integer", "description": "Optional sequential starting number"},
                "dry_run": {"type": "boolean", "description": "If true, returns preview plan without modifying files"}
            },
            "required": ["directory"],
        },
    },
    {
        "name": "move_file",
        "description": "Move or relocate a file or folder with cross-volume safety.",
        "parameters": {
            "type": "object",
            "properties": {
                "source": {"type": "string", "description": "Current path of the file/folder"},
                "destination": {"type": "string", "description": "New path/location"},
                "overwrite": {"type": "boolean", "description": "Whether to overwrite destination (default: False)"}
            },
            "required": ["source", "destination"],
        },
    },
    {
        "name": "delete_file",
        "description": "Delete a file to the Windows Recycle Bin with rollback capability.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path to the file to delete"}
            },
            "required": ["path"],
        },
    },
    {
        "name": "delete_folder",
        "description": "Delete a folder with rollback capability.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path to the folder to delete"},
                "force": {"type": "boolean", "description": "Whether to force delete non-empty folders (default: False)"}
            },
            "required": ["path"],
        },
    },
    {
        "name": "get_folder_contents",
        "description": "List all files and folders in a directory (e.g. 'Desktop', 'Downloads', 'Documents').",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Directory path (e.g. 'Desktop', 'Downloads', 'Documents'). Defaults to Desktop."}
            },
        },
    },
    {
        "name": "list_directory_tree",
        "description": "Generate a structured ASCII tree view of a directory.",
        "parameters": {
            "type": "object",
            "properties": {
                "directory_path": {"type": "string", "description": "Directory to visualize"},
                "max_depth": {"type": "integer", "description": "Maximum tree depth (default: 2)"}
            }
        }
    },
    {
        "name": "organize_desktop",
        "description": "Clean and organize files on Desktop into categorized folders (Images, Documents, Media, Code, Archives, Installers) in a single undoable transaction.",
        "parameters": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "get_largest_files",
        "description": "Find the largest files in a directory (sorted descending by size). Useful for cleaning disk space.",
        "parameters": {
            "type": "object",
            "properties": {
                "directory": {"type": "string", "description": "Directory to search (defaults to 'Desktop')."},
                "count": {"type": "integer", "description": "Number of largest files to return (default: 10)."}
            }
        },
    },
    {
        "name": "get_file_hash",
        "description": "Calculate SHA-256, MD5, or SHA-1 cryptographic checksum of a file.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path to hash"},
                "algorithm": {"type": "string", "description": "Hash algorithm ('sha256', 'md5', 'sha1'). Default: sha256"}
            },
            "required": ["path"]
        }
    },
    {
        "name": "get_disk_storage",
        "description": "Inspect disk storage capacity, used space, and free space on drive volumes (e.g. 'C:', 'D:').",
        "parameters": {
            "type": "object",
            "properties": {
                "drive_or_path": {"type": "string", "description": "Drive letter or path (default: 'C:')"}
            }
        }
    },
    {
        "name": "find_duplicate_files",
        "description": "Discover duplicate files in a directory using size grouping and SHA-256 hashing. Discovery only.",
        "parameters": {
            "type": "object",
            "properties": {
                "directory": {"type": "string", "description": "Directory to scan for duplicates (default: 'Desktop')"},
                "max_depth": {"type": "integer", "description": "Max folder depth to scan (default: 4)"}
            }
        }
    },
    {
        "name": "create_archive",
        "description": "Create a .zip or .tar.gz archive from a file or directory using native Python.",
        "parameters": {
            "type": "object",
            "properties": {
                "source": {"type": "string", "description": "Source file or directory to compress"},
                "output_path": {"type": "string", "description": "Destination archive file path"},
                "archive_type": {"type": "string", "description": "Archive format ('zip' or 'tar.gz'). Default: zip"}
            },
            "required": ["source"]
        }
    },
    {
        "name": "extract_archive",
        "description": "Extract a .zip or .tar.gz archive with Zip-Slip path traversal protection.",
        "parameters": {
            "type": "object",
            "properties": {
                "archive_path": {"type": "string", "description": "Path to the archive file"},
                "destination": {"type": "string", "description": "Destination folder to extract into"}
            },
            "required": ["archive_path"]
        }
    },
    {
        "name": "list_archive",
        "description": "List all entries inside a .zip or .tar archive without extracting.",
        "parameters": {
            "type": "object",
            "properties": {
                "archive_path": {"type": "string", "description": "Path to the archive file"}
            },
            "required": ["archive_path"]
        }
    },
    {
        "name": "restore_recycle_bin_item",
        "description": "Restore a deleted file or folder from the Windows Recycle Bin (UNDELETE).",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Original file path or name of the deleted item to restore from the Recycle Bin."}
            },
            "required": ["path"],
        },
    },
    {
        "name": "undo_last_action",
        "description": "Undo/rollback the most recent reversible action or transaction.",
        "parameters": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "list_undo_history",
        "description": "List reversible actions available in the undo stack.",
        "parameters": {
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "description": "Max records to list (default: 10)"}
            }
        }
    }
]

TOOL_FUNCTIONS = {
    "search_files": search_files,
    "fuzzy_resolve_path": fuzzy_resolve_path,
    "fuzzy_resolve": fuzzy_resolve_path,
    "open_file": open_file,
    "open_folder": open_folder,
    "open_directory": open_folder,
    "create_file": create_file,
    "create_text_file": create_text_file,
    "write_file": write_file,
    "read_file": read_file,
    "read_text_file": read_file,
    "create_folder": create_folder,
    "create_directory": create_folder,
    "copy_file": copy_file,
    "rename_file": rename_file,
    "batch_rename": batch_rename,
    "move_file": move_file,
    "delete_file": delete_file,
    "delete_folder": delete_folder,
    "delete_directory": delete_folder,
    "get_folder_contents": get_folder_contents,
    "list_folder": get_folder_contents,
    "list_files": get_folder_contents,
    "list_directory_tree": list_directory_tree,
    "organize_desktop": organize_desktop,
    "get_largest_files": get_largest_files,
    "largest_files": get_largest_files,
    "get_file_hash": get_file_hash,
    "hash_file": get_file_hash,
    "get_file_info": get_file_info,
    "file_info": get_file_info,
    "get_disk_storage": get_disk_storage,
    "disk_usage": get_disk_storage,
    "find_duplicate_files": find_duplicate_files,
    "find_duplicates": find_duplicate_files,
    "create_archive": create_archive,
    "extract_archive": extract_archive,
    "list_archive": list_archive,
    "restore_recycle_bin_item": restore_recycle_bin_item,
    "restore_file": restore_recycle_bin_item,
    "undelete_file": undelete_file,
    "undelete": undelete_file,
    "undo_last_action": undo_last_action,
    "undo": undo_last_action,
    "list_undo_history": list_undo_history,
}
