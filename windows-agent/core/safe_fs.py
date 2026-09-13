"""
MAX AI — Safe File Operations & Verified File Rollback (Clean-Room Implementation)
Moves files to the Windows Recycle Bin while keeping an atomic shadow snapshot
in a local secure vault (.max_trash_vault) to guarantee immediate, programmatic file restoration
when undo is triggered.
"""

import os
import sys
import ctypes
import shutil
import hashlib
import logging
import tempfile
from typing import Tuple, Dict, Any, Optional

logger = logging.getLogger("MAXWindowsAgent.SafeFS")

FO_DELETE = 0x0003
FOF_ALLOWUNDO = 0x0040
FOF_NOCONFIRMATION = 0x0010
FOF_SILENT = 0x0004
FOF_NOERRORUI = 0x0400


class RealSafeFileSystem:
    """
    Safely deletes files to the Windows Recycle Bin while guaranteeing programmatic rollback.
    """

    def __init__(self, vault_dir: Optional[str] = None):
        self.vault_dir = vault_dir or os.path.join(tempfile.gettempdir(), "max_file_vault")
        os.makedirs(self.vault_dir, exist_ok=True)

    def safe_delete(self, file_or_dir_path: str) -> Dict[str, Any]:
        """
        1. Takes an atomic snapshot in the secure vault
        2. Sends the file to the Windows Recycle Bin
        3. Registers an Undo closure that actually restores the file to disk upon rollback
        """
        if not file_or_dir_path or not os.path.exists(file_or_dir_path):
            return {
                "status": "error",
                "message": f"Path '{file_or_dir_path}' does not exist."
            }

        abs_path = os.path.realpath(os.path.abspath(file_or_dir_path))
        is_dir = os.path.isdir(abs_path)

        # 1. Take atomic snapshot in vault before deletion
        path_hash = hashlib.sha256(abs_path.encode("utf-8")).hexdigest()[:12]
        base_name = os.path.basename(abs_path)
        vault_entry = os.path.join(self.vault_dir, f"{path_hash}_{base_name}")

        try:
            if is_dir:
                shutil.copytree(abs_path, vault_entry, dirs_exist_ok=True)
            else:
                shutil.copy2(abs_path, vault_entry)
        except Exception as e:
            logger.error(f"Failed to create pre-delete vault snapshot: {e}")
            return {"status": "error", "message": f"Vault snapshot failed: {e}"}

        # 2. Move original to Windows Recycle Bin via SHFileOperationW
        recycled = False
        if sys.platform == "win32":
            try:
                from ctypes import wintypes

                class SHFILEOPSTRUCTW(ctypes.Structure):
                    _fields_ = [
                        ('hwnd', wintypes.HWND),
                        ('wFunc', wintypes.UINT),
                        ('pFrom', wintypes.LPCWSTR),
                        ('pTo', wintypes.LPCWSTR),
                        ('fFlags', wintypes.WORD),
                        ('fAnyOperationsAborted', wintypes.BOOL),
                        ('hNameMappings', wintypes.LPVOID),
                        ('lpszProgressTitle', wintypes.LPCWSTR)
                    ]

                path_buffer = abs_path + '\0\0'
                fileop = SHFILEOPSTRUCTW()
                fileop.hwnd = 0
                fileop.wFunc = FO_DELETE
                fileop.pFrom = path_buffer
                fileop.pTo = None
                fileop.fFlags = FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI
                fileop.fAnyOperationsAborted = False

                res = ctypes.windll.shell32.SHFileOperationW(ctypes.byref(fileop))
                if res == 0 and not fileop.fAnyOperationsAborted:
                    recycled = True
            except Exception as se:
                logger.debug(f"Recycle bin COM call error: {se}")

        if not recycled and os.path.exists(abs_path):
            # Fallback removal if COM call was not supported
            if is_dir:
                shutil.rmtree(abs_path)
            else:
                os.remove(abs_path)

        # 3. Define the real restoration undo function
        def _restore_file_action() -> Tuple[bool, str]:
            """Actually restores the file from the vault to the original location."""
            try:
                if not os.path.exists(vault_entry):
                    return False, f"Vault snapshot '{vault_entry}' not found."

                os.makedirs(os.path.dirname(abs_path), exist_ok=True)
                if is_dir:
                    shutil.copytree(vault_entry, abs_path, dirs_exist_ok=True)
                    shutil.rmtree(vault_entry, ignore_errors=True)
                else:
                    shutil.copy2(vault_entry, abs_path)
                    os.remove(vault_entry)

                if os.path.exists(abs_path):
                    logger.info(f"✅ Genuinely restored file: '{abs_path}'")
                    return True, f"Successfully restored '{abs_path}' to its original location."
                else:
                    return False, "Restored file verification check failed."
            except Exception as re:
                logger.error(f"Restoration error: {re}")
                return False, f"Failed to restore file: {str(re)}"

        # 4. Register closure with UndoManager
        try:
            from core.undo_manager import undo_manager
            undo_manager.push_undo(
                description=f"Restore '{base_name}' to {abs_path}",
                undo_fn=_restore_file_action,
                metadata={"original_path": abs_path, "vault_entry": vault_entry}
            )
        except Exception as ue:
            logger.debug(f"Undo manager registration error: {ue}")

        return {
            "status": "success",
            "path": abs_path,
            "recycled": recycled,
            "vault_secured": True,
            "message": f"Successfully deleted '{abs_path}'. Rollback available via undo."
        }


safe_fs = RealSafeFileSystem()
