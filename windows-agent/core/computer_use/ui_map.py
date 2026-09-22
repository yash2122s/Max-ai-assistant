"""
MAX AI — UI Map Builder & Element Merger
Fuses UIA accessibility elements with OCR text bounding boxes,
deduplicates overlapping regions, and assigns deterministic, ephemeral @ref tags.
"""

import time
import logging
from typing import List, Dict, Any, Optional

from core.computer_use.models import UIElement, UIMap, Rect, Point, ElementSource
from core.computer_use.ui_ref_cache import ui_ref_cache

logger = logging.getLogger("MAX.ComputerUse.UIMap")

_global_generation_counter = 0


class UIMapBuilder:
    """Builds clean, deduplicated UIMap instances with ephemeral @refs."""

    @staticmethod
    def build_ui_map(
        uia_elements: List[UIElement],
        ocr_elements: List[UIElement],
        active_window: str,
        active_process: str,
        active_hwnd: int,
        monitor: int = 0,
        screenshot_path: Optional[str] = None,
        dpi_scale: float = 1.0
    ) -> UIMap:
        global _global_generation_counter
        _global_generation_counter += 1
        gen_id = _global_generation_counter

        # 1. Start with UIA elements as semantic foundation
        fused_elements: List[UIElement] = list(uia_elements)

        # 2. Merge OCR elements that do not heavily overlap existing UIA elements
        for ocr_el in ocr_elements:
            overlapping_uia = None
            for uia_el in fused_elements:
                # If bounding boxes overlap significantly (IoU > 0.4 or center contained)
                if uia_el.bounds.iou(ocr_el.bounds) > 0.4 or uia_el.bounds.contains(ocr_el.center):
                    overlapping_uia = uia_el
                    break

            if overlapping_uia:
                # Enhance UIA element with OCR text if UIA had blank name
                if not overlapping_uia.name.strip() and ocr_el.text.strip():
                    overlapping_uia.name = ocr_el.text.strip()
                    overlapping_uia.text = ocr_el.text.strip()
                    overlapping_uia.source = ElementSource.FUSED
            else:
                # Unique visual text element from OCR
                fused_elements.append(ocr_el)

        # 3. Sort elements in natural reading order (top-to-bottom, left-to-right)
        # Using a row-bucket quantization (e.g. 20px)
        fused_elements.sort(
            key=lambda el: (
                round(el.bounds.top / 20.0) * 20.0,
                el.bounds.left
            )
        )

        # 4. Assign deterministic, clean ephemeral @refs: @e1, @e2, @e3, ...
        ref_map: Dict[str, UIElement] = {}
        for idx, el in enumerate(fused_elements, start=1):
            ref_tag = f"@e{idx}"
            el.ref = ref_tag
            el.generation = gen_id
            if not el.window and active_window:
                el.window = active_window
            if not el.process and active_process:
                el.process = active_process
            if not el.native_handle and active_hwnd:
                el.native_handle = active_hwnd
            ref_map[ref_tag] = el

        ui_map = UIMap(
            generation=gen_id,
            timestamp=time.time(),
            active_window=active_window,
            active_process=active_process,
            active_hwnd=active_hwnd,
            monitor=monitor,
            elements=fused_elements,
            ref_map=ref_map,
            screenshot_path=screenshot_path,
            dpi_scale=dpi_scale
        )

        # Cache the map
        ui_ref_cache.store_map(ui_map)

        return ui_map


ui_map_builder = UIMapBuilder()
