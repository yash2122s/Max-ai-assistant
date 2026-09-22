"""
MAX AI — Screen Capture Exclusion Manager
Uses Windows SetWindowDisplayAffinity (WDA_EXCLUDEFROMCAPTURE = 0x00000011)
to exclude the MAX WPF Overlay and HUD windows from MAX's own screenshots and perception.
"""

import sys
import ctypes
from ctypes import wintypes
import logging
from typing import Optional, List

logger = logging.getLogger("MAX.ComputerUse.CaptureExclusion")

WDA_NONE = 0x00000000
WDA_MONITOR = 0x00000001
WDA_EXCLUDEFROMCAPTURE = 0x00000011  # Windows 10 version 2004 (build 19041) and newer


class CaptureExclusionManager:
    """Manages window display affinities to hide MAX UI overlay from screenshots."""

    def __init__(self):
        self._user32 = None
        if sys.platform == "win32":
            try:
                self._user32 = ctypes.WinDLL("user32", use_last_error=True)
            except Exception as e:
                logger.warning(f"Could not load user32.dll for capture exclusion: {e}")

    def set_exclude_from_capture(self, hwnd: int) -> bool:
        """
        Sets WDA_EXCLUDEFROMCAPTURE on the specified window handle.
        Returns True if successful.
        """
        if not self._user32 or not hwnd:
            return False

        try:
            # SetWindowDisplayAffinity(HWND hWnd, DWORD dwAffinity)
            result = self._user32.SetWindowDisplayAffinity(
                wintypes.HWND(hwnd),
                wintypes.DWORD(WDA_EXCLUDEFROMCAPTURE)
            )
            if result:
                logger.info(f"🛡️ Set WDA_EXCLUDEFROMCAPTURE on HWND 0x{hwnd:X}")
                return True
            else:
                err = ctypes.get_last_error()
                logger.debug(f"SetWindowDisplayAffinity failed for HWND 0x{hwnd:X} (Error {err})")
                return False
        except Exception as e:
            logger.debug(f"Error applying capture exclusion to HWND 0x{hwnd:X}: {e}")
            return False

    def remove_exclude_from_capture(self, hwnd: int) -> bool:
        """Removes capture exclusion affinity from HWND."""
        if not self._user32 or not hwnd:
            return False
        try:
            result = self._user32.SetWindowDisplayAffinity(
                wintypes.HWND(hwnd),
                wintypes.DWORD(WDA_NONE)
            )
            return bool(result)
        except Exception:
            return False

    def find_and_exclude_max_overlay_windows(self) -> List[int]:
        """
        Finds open MAX overlay windows by class/title and applies capture exclusion.
        """
        if not self._user32:
            return []

        excluded = []

        def enum_cb(hwnd, lparam):
            try:
                length = self._user32.GetWindowTextLengthW(hwnd)
                if length > 0:
                    buff = ctypes.create_unicode_buffer(length + 1)
                    self._user32.GetWindowTextW(hwnd, buff, length + 1)
                    title = buff.value
                    if "MAXOverlay" in title or "MAX Dynamic Island" in title or "MAX HUD" in title:
                        if self.set_exclude_from_capture(hwnd):
                            excluded.append(hwnd)
            except Exception:
                pass
            return True

        WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)
        try:
            self._user32.EnumWindows(WNDENUMPROC(enum_cb), 0)
        except Exception as e:
            logger.debug(f"Error enumerating windows for exclusion: {e}")

        return excluded


capture_exclusion_manager = CaptureExclusionManager()
