"""
MAX AI — Win32 Native Clipboard Helper
Fast, low-latency, memory-safe clipboard reading and writing with pyperclip fallback.
"""

import ctypes
import hashlib
import logging

logger = logging.getLogger("MAXWindowsAgent.Clipboard")

# Define Win32 clipboard functions with explicit argtypes/restype for 64-bit safety
user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

OpenClipboard = user32.OpenClipboard
OpenClipboard.argtypes = [ctypes.c_void_p]
OpenClipboard.restype = ctypes.c_bool

CloseClipboard = user32.CloseClipboard
CloseClipboard.argtypes = []
CloseClipboard.restype = ctypes.c_bool

EmptyClipboard = user32.EmptyClipboard
EmptyClipboard.argtypes = []
EmptyClipboard.restype = ctypes.c_bool

GetClipboardData = user32.GetClipboardData
GetClipboardData.argtypes = [ctypes.c_uint]
GetClipboardData.restype = ctypes.c_void_p

SetClipboardData = user32.SetClipboardData
SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]
SetClipboardData.restype = ctypes.c_void_p

GlobalAlloc = kernel32.GlobalAlloc
GlobalAlloc.argtypes = [ctypes.c_uint, ctypes.c_size_t]
GlobalAlloc.restype = ctypes.c_void_p

GlobalLock = kernel32.GlobalLock
GlobalLock.argtypes = [ctypes.c_void_p]
GlobalLock.restype = ctypes.c_void_p

GlobalUnlock = kernel32.GlobalUnlock
GlobalUnlock.argtypes = [ctypes.c_void_p]
GlobalUnlock.restype = ctypes.c_bool

GMEM_DDESHARE = 0x2000
CF_UNICODETEXT = 13


class ClipboardHelper:
    def __init__(self):
        self.last_hash = ""

    def get_text(self) -> str:
        """Read unicode text from clipboard using Win32 API with pyperclip fallback."""
        try:
            if OpenClipboard(None):
                try:
                    handle = GetClipboardData(CF_UNICODETEXT)
                    if handle:
                        ptr = GlobalLock(handle)
                        if ptr:
                            text = ctypes.wstring_at(ptr)
                            GlobalUnlock(handle)
                            return text
                finally:
                    CloseClipboard()
        except Exception as e:
            logger.debug(f"Win32 clipboard read fallback to pyperclip: {e}")

        try:
            import pyperclip
            return pyperclip.paste() or ""
        except Exception as pe:
            logger.error(f"Failed to read from clipboard: {pe}")
            return ""

    def set_text(self, text: str) -> bool:
        """Write unicode text to clipboard using Win32 API with pyperclip fallback."""
        if text is None:
            text = ""

        try:
            text_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
            if text_hash == self.last_hash:
                return True

            if OpenClipboard(None):
                try:
                    EmptyClipboard()
                    bytes_count = (len(text) + 1) * 2
                    h_global = GlobalAlloc(GMEM_DDESHARE, bytes_count)
                    if h_global:
                        ptr = GlobalLock(h_global)
                        if ptr:
                            ctypes.memmove(ptr, ctypes.c_wchar_p(text), bytes_count)
                            GlobalUnlock(h_global)
                            res = SetClipboardData(CF_UNICODETEXT, h_global)
                            if res:
                                self.last_hash = text_hash
                                return True
                finally:
                    CloseClipboard()
        except Exception as e:
            logger.debug(f"Win32 clipboard write fallback to pyperclip: {e}")

        try:
            import pyperclip
            pyperclip.copy(text)
            self.last_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
            return True
        except Exception as pe:
            logger.error(f"Failed to write to clipboard: {pe}")
            return False


clipboard_helper = ClipboardHelper()
