"""
MAX AI — Screen Perception Coordinator
Orchestrates screenshot capture, Windows UIA tree traversal, and OCR perception
into a unified, normalized UIMap.
"""

import time
import logging
from typing import Optional, Dict, Any

from core.computer_use.models import UIMap
from core.computer_use.screenshot_provider import screenshot_provider
from core.computer_use.uia_provider import uia_provider
from core.computer_use.ocr_provider import ocr_provider
from core.computer_use.ui_map import ui_map_builder
from core.computer_use.coordinate_transform import coordinate_transformer

logger = logging.getLogger("MAX.ComputerUse.Perception")


class ScreenPerceptionCoordinator:
    """Perceives desktop screen state via multi-modal sensor fusion (Screenshot + UIA + OCR)."""

    def __init__(self):
        self.screenshot_provider = screenshot_provider
        self.uia_provider = uia_provider
        self.ocr_provider = ocr_provider
        self.ui_map_builder = ui_map_builder
        self._first_cycle_recorded = False

    def observe(
        self,
        include_ocr: bool = True,
        max_elements: int = 150
    ) -> UIMap:
        """
        Captures current desktop state, extracts UIA hierarchy, runs OCR if enabled,
        and constructs a fresh UIMap with new @refs.
        """
        t0 = time.time()
        logger.info("👀 ScreenPerception: Starting screen observation cycle...")

        # 1. Capture screen
        shot_res = self.screenshot_provider.capture_screen()
        screenshot_path = shot_res.get("path") if shot_res.get("status") == "success" else None

        # 2. Get active window metadata
        win_info = self.uia_provider.get_active_window_info()
        active_window = win_info.get("title", "Desktop")
        active_process = win_info.get("process", "explorer.exe")
        active_hwnd = win_info.get("hwnd", 0)

        # 3. Extract UIA elements
        uia_elements = self.uia_provider.inspect_active_window_elements(max_elements=max_elements)

        # 4. Extract OCR elements if screenshot available and OCR requested
        ocr_elements = []
        if include_ocr and screenshot_path:
            ocr_elements = self.ocr_provider.extract_text_elements(
                image_path=screenshot_path,
                window_title=active_window,
                process_name=active_process
            )

        # 5. Build Unified UIMap
        dpi_scale = coordinate_transformer.get_system_dpi_scale()
        ui_map = self.ui_map_builder.build_ui_map(
            uia_elements=uia_elements,
            ocr_elements=ocr_elements,
            active_window=active_window,
            active_process=active_process,
            active_hwnd=active_hwnd,
            monitor=0,
            screenshot_path=screenshot_path,
            dpi_scale=dpi_scale
        )

        elapsed_ms = int((time.time() - t0) * 1000)
        logger.info(
            f"✅ ScreenPerception: Completed in {elapsed_ms}ms (Gen #{ui_map.generation}, "
            f"{len(ui_map.elements)} elements, window: '{active_window}')"
        )

        if not self._first_cycle_recorded:
            self._first_cycle_recorded = True
            try:
                from core.memory_diagnostics import memory_diagnostics
                memory_diagnostics.record_checkpoint("after one Computer Use perception cycle")
            except Exception:
                pass

        return ui_map


screen_perception = ScreenPerceptionCoordinator()
