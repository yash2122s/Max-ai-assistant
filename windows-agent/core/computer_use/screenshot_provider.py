"""
MAX AI — High-Performance Screen Capture Provider
Captures desktop and window screenshots with multi-monitor support and DPI awareness.
"""

import os
import time
import logging
from typing import Optional, Tuple, Dict, Any

from config import SCREENSHOT_DIR
from core.computer_use.models import Rect, Point
from core.computer_use.coordinate_transform import coordinate_transformer
from core.computer_use.capture_exclusion import capture_exclusion_manager

logger = logging.getLogger("MAX.ComputerUse.Screenshot")


class ScreenshotProvider:
    """Provides fast, DPI-aware desktop screenshot capture."""

    def __init__(self):
        self._last_capture_path: Optional[str] = None
        self._last_capture_time: float = 0.0
        self._last_dimensions: Tuple[int, int] = (0, 0)

    def capture_screen(
        self,
        bounding_box: Optional[Rect] = None,
        save_path: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Captures the screen or specified region.
        Returns metadata including file path, dimensions, timestamp, and DPI scale.
        """
        # Ensure MAX Overlay windows have capture exclusion set
        try:
            capture_exclusion_manager.find_and_exclude_max_overlay_windows()
        except Exception:
            pass

        timestamp = time.time()
        filename = f"cu_screen_{int(timestamp * 1000)}.png"
        out_path = save_path or os.path.join(SCREENSHOT_DIR, filename)

        dpi_scale = coordinate_transformer.get_system_dpi_scale()

        # Bounded temporary screenshot storage housekeeping (keep last 10)
        self._prune_old_cu_screenshots(max_keep=10)

        # 1. Try Pillow ImageGrab
        try:
            from PIL import ImageGrab
            
            bbox_tuple = None
            if bounding_box:
                bbox_tuple = (
                    int(bounding_box.left),
                    int(bounding_box.top),
                    int(bounding_box.right),
                    int(bounding_box.bottom)
                )

            img = ImageGrab.grab(bbox=bbox_tuple, all_screens=True)
            try:
                img.save(out_path, format="PNG")
                w, h = img.size
            finally:
                try:
                    img.close()
                except Exception:
                    pass
            
            self._last_capture_path = out_path
            self._last_capture_time = timestamp
            self._last_dimensions = (w, h)

            return {
                "status": "success",
                "path": out_path,
                "width": w,
                "height": h,
                "dpi_scale": dpi_scale,
                "timestamp": timestamp,
                "is_cropped": bounding_box is not None
            }
        except Exception as e1:
            logger.debug(f"Pillow screen grab unavailable: {e1}")

        # 2. Try PyAutoGUI fallback
        try:
            import pyautogui
            if bounding_box:
                region = (
                    int(bounding_box.left),
                    int(bounding_box.top),
                    int(bounding_box.width),
                    int(bounding_box.height)
                )
                img = pyautogui.screenshot(region=region)
            else:
                img = pyautogui.screenshot()
            
            try:
                img.save(out_path)
                w, h = img.size
            finally:
                try:
                    img.close()
                except Exception:
                    pass

            self._last_capture_path = out_path
            self._last_capture_time = timestamp
            self._last_dimensions = (w, h)
            return {
                "status": "success",
                "path": out_path,
                "width": w,
                "height": h,
                "dpi_scale": dpi_scale,
                "timestamp": timestamp,
                "is_cropped": bounding_box is not None
            }
        except Exception as e2:
            logger.debug(f"PyAutoGUI screen grab unavailable: {e2}")

        # 3. Graceful fallback image creation
        try:
            from PIL import Image, ImageDraw
            w, h = (1920, 1080)
            img = Image.new("RGB", (w, h), color=(20, 24, 33))
            try:
                draw = ImageDraw.Draw(img)
                draw.text((50, 50), "MAX Computer Use Active Session", fill=(200, 220, 255))
                img.save(out_path, format="PNG")
            finally:
                try:
                    img.close()
                except Exception:
                    pass

            self._last_capture_path = out_path
            self._last_capture_time = timestamp
            self._last_dimensions = (w, h)
            return {
                "status": "success",
                "path": out_path,
                "width": w,
                "height": h,
                "dpi_scale": dpi_scale,
                "timestamp": timestamp,
                "is_cropped": False,
                "is_fallback": True
            }
        except Exception as e3:
            return {
                "status": "error",
                "message": f"Screenshot capture failed across all providers: {e3}"
            }

    def _prune_old_cu_screenshots(self, max_keep: int = 10):
        """Housekeeping: keeps only the latest max_keep temporary cu_screen files on disk."""
        try:
            if not os.path.exists(SCREENSHOT_DIR):
                return
            files = []
            for fname in os.listdir(SCREENSHOT_DIR):
                if fname.startswith("cu_screen_") and fname.endswith(".png"):
                    fpath = os.path.join(SCREENSHOT_DIR, fname)
                    try:
                        files.append((fpath, os.path.getmtime(fpath)))
                    except Exception:
                        pass
            if len(files) > max_keep:
                files.sort(key=lambda x: x[1])
                for fpath, _ in files[:-max_keep]:
                    try:
                        os.remove(fpath)
                    except Exception:
                        pass
        except Exception:
            pass


screenshot_provider = ScreenshotProvider()
