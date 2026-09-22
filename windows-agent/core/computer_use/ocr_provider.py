"""
MAX AI — Optical Character Recognition (OCR) Perception Provider
Extracts visual text lines and bounding boxes from screenshot regions.
"""

import sys
import os
import time
import logging
from typing import List, Dict, Any, Optional

from core.computer_use.models import UIElement, Rect, Point, ElementSource

logger = logging.getLogger("MAX.ComputerUse.OCRProvider")


class OCRProvider:
    """Extracts text tokens and bounding boxes from screen captures."""

    def __init__(self):
        self._ocr_backend = None
        self._backend_name = "none"
        self._initialized = False

    def _ensure_init(self):
        if not self._initialized:
            self._initialized = True
            self._init_ocr()

    def _init_ocr(self):
        try:
            from core.readiness import readiness
            readiness.set("ocr", "INITIALIZING")
        except Exception:
            readiness = None

        try:
            from core.startup_profiler import profiler
            profiler.mark("OCR_INIT_START")
        except Exception:
            profiler = None

        def _finish_ocr():
            if readiness:
                readiness.set("ocr", "READY")
            if profiler:
                try:
                    profiler.mark("OCR_READY")
                except Exception:
                    pass

        # 1. Try Windows WinRT OCR (Windows.Media.Ocr)
        if sys.platform == "win32":
            try:
                import winsdk.windows.media.ocr as win_ocr
                import winsdk.windows.globalization as win_glob
                lang = win_glob.Language("en-US")
                if win_ocr.OcrEngine.is_language_supported(lang):
                    self._ocr_backend = win_ocr.OcrEngine.try_create_from_language(lang)
                    self._backend_name = "winrt"
                    logger.info("✅ OCRProvider: Native WinRT OCR engine initialized")
                    _finish_ocr()
                    return
            except Exception:
                pass

        # 2. Try EasyOCR
        try:
            import easyocr
            self._ocr_backend = easyocr.Reader(['en'], gpu=False)
            self._backend_name = "easyocr"
            logger.info("✅ OCRProvider: EasyOCR backend initialized")
            _finish_ocr()
            return
        except Exception:
            pass

        # 3. Try PyTesseract
        try:
            import pytesseract
            self._ocr_backend = pytesseract
            self._backend_name = "tesseract"
            logger.info("✅ OCRProvider: PyTesseract backend initialized")
            _finish_ocr()
            return
        except Exception:
            pass

        logger.info("ℹ️ OCRProvider: Operating in lightweight perception mode (UIA-primary)")
        _finish_ocr()

    @property
    def backend_name(self) -> str:
        self._ensure_init()
        return self._backend_name

    def extract_text_elements(
        self,
        image_path: str,
        window_title: str = "",
        process_name: str = ""
    ) -> List[UIElement]:
        """
        Runs OCR on the given image and extracts text bounding boxes as UIElements.
        """
        self._ensure_init()
        if not os.path.exists(image_path):
            return []

        elements: List[UIElement] = []
        now = time.time()

        if self._backend_name == "easyocr":
            try:
                results = self._ocr_backend.readtext(image_path)
                for bbox, text, conf in results:
                    clean_text = str(text).strip()
                    if not clean_text or conf < 0.3:
                        continue
                    # bbox: [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]
                    xs = [p[0] for p in bbox]
                    ys = [p[1] for p in bbox]
                    bounds = Rect(min(xs), min(ys), max(xs), max(ys))
                    el = UIElement(
                        ref="",
                        role="text",
                        name=clean_text,
                        text=clean_text,
                        bounds=bounds,
                        center=bounds.center,
                        source=ElementSource.OCR,
                        control_type="OCRText",
                        confidence=float(conf),
                        window=window_title,
                        process=process_name,
                        timestamp=now
                    )
                    elements.append(el)
            except Exception as e:
                logger.debug(f"EasyOCR extraction error: {e}")

        elif self._backend_name == "tesseract":
            try:
                from PIL import Image
                with Image.open(image_path) as img:
                    data = self._ocr_backend.image_to_data(img, output_type=self._ocr_backend.Output.DICT)
                n_boxes = len(data['text'])
                for i in range(n_boxes):
                    text = data['text'][i].strip()
                    conf = float(data['conf'][i])
                    if not text or conf < 30.0:
                        continue
                    x = float(data['left'][i])
                    y = float(data['top'][i])
                    w = float(data['width'][i])
                    h = float(data['height'][i])
                    bounds = Rect(x, y, x + w, y + h)
                    el = UIElement(
                        ref="",
                        role="text",
                        name=text,
                        text=text,
                        bounds=bounds,
                        center=bounds.center,
                        source=ElementSource.OCR,
                        control_type="OCRText",
                        confidence=conf / 100.0,
                        window=window_title,
                        process=process_name,
                        timestamp=now
                    )
                    elements.append(el)
            except Exception as e:
                logger.debug(f"PyTesseract extraction error: {e}")

        return elements


ocr_provider = OCRProvider()
_ocr_provider_instance = None


def get_ocr_provider() -> OCRProvider:
    """Returns the lazy singleton OCRProvider instance."""
    global _ocr_provider_instance
    if _ocr_provider_instance is None:
        _ocr_provider_instance = ocr_provider
    return _ocr_provider_instance
