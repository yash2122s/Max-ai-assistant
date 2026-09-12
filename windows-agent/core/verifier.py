"""
MAX AI — Action Verification & Controlled Retry Engine
Provides post-execution verification and deterministic retry fallbacks
for Windows and ecosystem actions.
"""

import os
import time
import logging
import asyncio
from typing import Optional, Callable, Any, Dict, Tuple

logger = logging.getLogger("MAXWindowsAgent.Verifier")


class ActionVerifier:
    """
    Verifies that automation actions succeeded by observing post-execution state.
    Provides both asynchronous and synchronous verification helpers.
    """

    @staticmethod
    def verify_app_running_sync(target_name: str) -> bool:
        """Synchronously check if an application or window is running."""
        import psutil
        target_lower = target_name.lower().strip().replace(".exe", "")
        for proc in psutil.process_iter(["name"]):
            try:
                pname = proc.info.get("name", "").lower().replace(".exe", "")
                if target_lower in pname or pname.startswith(target_lower):
                    return True
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
        return False

    @staticmethod
    def verify_app_closed_sync(target_name: str, timeout_seconds: float = 1.5, poll_interval: float = 0.2) -> bool:
        """Synchronously poll until application process and visible windows are closed."""
        import psutil
        start = time.time()
        target_lower = target_name.lower().strip().replace(".exe", "")

        while time.time() - start < timeout_seconds:
            still_running = False
            for proc in psutil.process_iter(["name"]):
                try:
                    pname = proc.info.get("name", "").lower().replace(".exe", "")
                    if target_lower == pname or target_lower in pname:
                        still_running = True
                        break
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    continue

            if not still_running:
                return True
            time.sleep(poll_interval)

        return False

    @staticmethod
    async def verify_app_launched(target_name: str, timeout_seconds: float = 3.0, poll_interval: float = 0.5) -> bool:
        """Polls until the application or a window matching target_name appears."""
        from tools.app_tool import app_helper
        import psutil
        start = time.time()
        target_lower = target_name.lower().strip().replace(".exe", "")

        while time.time() - start < timeout_seconds:
            try:
                res = app_helper.is_running(target_lower)
                if res.get("is_running") or res.get("running"):
                    return True

                # Direct psutil fallback
                for proc in psutil.process_iter(["name"]):
                    try:
                        pname = proc.info.get("name", "").lower().replace(".exe", "")
                        if target_lower in pname or pname.startswith(target_lower):
                            return True
                    except (psutil.NoSuchProcess, psutil.AccessDenied):
                        continue
            except Exception:
                pass
            await asyncio.sleep(poll_interval)

        return False

    @staticmethod
    async def verify_window_closed(target_title: str, timeout_seconds: float = 2.0, poll_interval: float = 0.4) -> bool:
        """Polls until the window title disappears from active windows."""
        from tools.system_tool import system_helper
        start = time.time()
        target_lower = target_title.lower().strip()

        while time.time() - start < timeout_seconds:
            windows = system_helper.list_windows()
            matching = [w for w in windows if target_lower in w.get("title", "").lower()]
            if not matching:
                return True
            await asyncio.sleep(poll_interval)

        return False

    @staticmethod
    def verify_folder_created_sync(path: str) -> bool:
        """Verify that a directory exists and is strictly a directory (not a file)."""
        if not path:
            return False
        return os.path.exists(path) and os.path.isdir(path) and not os.path.isfile(path)

    @staticmethod
    def verify_file_created_sync(path: str, expected_content: Optional[str] = None) -> bool:
        """Verify that a file exists, is strictly a regular file (not a folder), and optionally verify content."""
        if not path or not os.path.exists(path):
            return False
        if not os.path.isfile(path) or os.path.isdir(path):
            return False
        if expected_content is not None and expected_content != "":
            try:
                with open(path, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
                return expected_content in content
            except Exception:
                return False
        return True

    @staticmethod
    def verify_path_deleted_sync(path: str) -> bool:
        """Verify that a file or folder path no longer exists."""
        if not path:
            return True
        return not os.path.exists(path)

    @staticmethod
    def verify_file_moved_sync(source: str, destination: str) -> bool:
        """Verify that source was removed and destination exists as a file or folder."""
        return not os.path.exists(source) and os.path.exists(destination)

    @staticmethod
    def verify_file_copied_sync(source: str, destination: str) -> bool:
        """Verify that both source and destination exist."""
        return os.path.exists(source) and os.path.exists(destination)

    @staticmethod
    def verify_clipboard_contains_sync(expected: str) -> bool:
        """Verify that the clipboard contains the expected text substring."""
        try:
            from tools.clipboard_tool import clipboard_helper
            text = clipboard_helper.get_text()
            if text and expected in text:
                return True
        except Exception:
            pass
        try:
            import pyperclip
            text = pyperclip.paste()
            return bool(text and expected in text)
        except Exception:
            return False

    @staticmethod
    async def execute_with_retry(
        action_fn: Callable[[], Any],
        verify_fn: Optional[Callable[[], Any]] = None,
        max_attempts: int = 2,
        delay_seconds: float = 0.5
    ) -> Tuple[bool, Any]:
        """Executes an action with optional verification and automatic retry."""
        last_result = None
        for attempt in range(1, max_attempts + 1):
            try:
                if asyncio.iscoroutinefunction(action_fn):
                    last_result = await action_fn()
                else:
                    last_result = action_fn()

                if verify_fn is not None:
                    if asyncio.iscoroutinefunction(verify_fn):
                        verified = await verify_fn()
                    else:
                        verified = verify_fn()

                    if verified:
                        logger.info(f"Action verified successfully on attempt {attempt}.")
                        return True, last_result
                else:
                    return True, last_result
            except Exception as e:
                logger.warning(f"Action execution attempt {attempt} failed: {e}")

            if attempt < max_attempts:
                await asyncio.sleep(delay_seconds)

        return False, last_result


action_verifier = ActionVerifier()
