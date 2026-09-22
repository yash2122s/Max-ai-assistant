"""
MAX AI — Multi-Monitor & DPI-Aware Coordinate Transformation Engine
Handles conversion between UIA logical coordinates, physical display pixels,
multi-monitor offsets, and window-relative bounding boxes.
"""

import sys
import ctypes
from ctypes import wintypes
import logging
from typing import Dict, Any, List, Optional, Tuple

from core.computer_use.models import Point, Rect

logger = logging.getLogger("MAX.ComputerUse.Coordinates")


class MonitorInfo:
    def __init__(self, index: int, handle: int, rect: Rect, is_primary: bool, dpi: int = 96):
        self.index = index
        self.handle = handle
        self.rect = rect
        self.is_primary = is_primary
        self.dpi = dpi
        self.scale_factor = dpi / 96.0

    def contains_point(self, point: Point) -> bool:
        return self.rect.contains(point)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "index": self.index,
            "is_primary": self.is_primary,
            "bounds": self.rect.to_dict(),
            "dpi": self.dpi,
            "scale_factor": round(self.scale_factor, 2)
        }


class CoordinateTransformer:
    """Provides high-precision DPI-aware and multi-monitor coordinate transformations."""

    def __init__(self):
        self._user32 = None
        self._shcore = None
        self._init_win32_apis()

    def _init_win32_apis(self):
        if sys.platform != "win32":
            return
        try:
            self._user32 = ctypes.WinDLL("user32", use_last_error=True)
            try:
                self._shcore = ctypes.WinDLL("shcore", use_last_error=True)
            except Exception:
                self._shcore = None

            # Enable Per-Monitor DPI Awareness v2 if possible
            try:
                # DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4
                self._user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
                logger.info("🎯 Enabled Per-Monitor DPI Awareness v2 for Computer Use")
            except Exception:
                try:
                    # PROCESS_PER_MONITOR_DPI_AWARE = 2
                    if self._shcore:
                        self._shcore.SetProcessDpiAwareness(2)
                except Exception:
                    pass
        except Exception as e:
            logger.warning(f"Failed to initialize Win32 coordinate APIs: {e}")

    def get_monitors(self) -> List[MonitorInfo]:
        """Enumerates all physical monitors and their bounding boxes and DPI."""
        monitors: List[MonitorInfo] = []
        if not self._user32:
            # Fallback default single monitor
            monitors.append(MonitorInfo(0, 0, Rect(0, 0, 1920, 1080), True, 96))
            return monitors

        class RECT(ctypes.Structure):
            _fields_ = [("left", wintypes.LONG), ("top", wintypes.LONG),
                        ("right", wintypes.LONG), ("bottom", wintypes.LONG)]

        class MONITORINFOEX(ctypes.Structure):
            _fields_ = [
                ("cbSize", wintypes.DWORD),
                ("rcMonitor", RECT),
                ("rcWork", RECT),
                ("dwFlags", wintypes.DWORD),
                ("szDevice", wintypes.WCHAR * 32)
            ]

        def monitor_enum_proc(hMonitor, hdcMonitor, lprcMonitor, dwData):
            mi = MONITORINFOEX()
            mi.cbSize = ctypes.sizeof(MONITORINFOEX)
            if self._user32.GetMonitorInfoW(hMonitor, ctypes.byref(mi)):
                r = mi.rcMonitor
                rect = Rect(float(r.left), float(r.top), float(r.right), float(r.bottom))
                is_primary = bool(mi.dwFlags & 1)  # MONITORINFOF_PRIMARY = 1
                
                dpi = 96
                if self._shcore:
                    try:
                        dpi_x = wintypes.UINT()
                        dpi_y = wintypes.UINT()
                        # MDT_EFFECTIVE_DPI = 0
                        res = self._shcore.GetDpiForMonitor(hMonitor, 0, ctypes.byref(dpi_x), ctypes.byref(dpi_y))
                        if res == 0:
                            dpi = int(dpi_x.value)
                    except Exception:
                        pass

                monitors.append(MonitorInfo(len(monitors), int(hMonitor), rect, is_primary, dpi))
            return True

        MONITORENUMPROC = ctypes.WINFUNCTYPE(
            ctypes.c_bool, wintypes.HMONITOR, wintypes.HDC, ctypes.POINTER(RECT), wintypes.LPARAM
        )

        try:
            self._user32.EnumDisplayMonitors(0, 0, MONITORENUMPROC(monitor_enum_proc), 0)
        except Exception as e:
            logger.debug(f"EnumDisplayMonitors failed: {e}")

        if not monitors:
            monitors.append(MonitorInfo(0, 0, Rect(0, 0, 1920, 1080), True, 96))

        return monitors

    def get_monitor_for_point(self, point: Point) -> MonitorInfo:
        monitors = self.get_monitors()
        for m in monitors:
            if m.contains_point(point):
                return m
        # Default to primary or first
        for m in monitors:
            if m.is_primary:
                return m
        return monitors[0]

    def get_system_dpi_scale(self) -> float:
        """Returns the primary monitor's scaling factor (e.g. 1.0, 1.25, 1.5, 2.0)."""
        monitors = self.get_monitors()
        for m in monitors:
            if m.is_primary:
                return m.scale_factor
        return 1.0

    def logical_to_physical(self, point: Point) -> Point:
        """Transforms UIA logical point to physical screen coordinates."""
        mon = self.get_monitor_for_point(point)
        # Note: When process is Per-Monitor DPI aware v2, coordinates are already in virtual screen pixels.
        return Point(x=point.x, y=point.y)

    def physical_to_logical(self, point: Point) -> Point:
        """Transforms physical screen pixel to logical coordinate."""
        return Point(x=point.x, y=point.y)

    def window_relative_to_screen(self, window_rect: Rect, rel_point: Point) -> Point:
        """Converts window-relative coordinates into absolute screen coordinates."""
        return Point(
            x=window_rect.left + rel_point.x,
            y=window_rect.top + rel_point.y
        )

    def clamp_to_screen(self, point: Point) -> Point:
        """Clamps a point to the bounding box of the containing monitor."""
        mon = self.get_monitor_for_point(point)
        cx = max(mon.rect.left, min(point.x, mon.rect.right - 1))
        cy = max(mon.rect.top, min(point.y, mon.rect.bottom - 1))
        return Point(x=cx, y=cy)


coordinate_transformer = CoordinateTransformer()
