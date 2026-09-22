"""
MAX UI — Computer View Component
Renders live screen capture, OCR/UIA bounding boxes, and action markers.
"""

import os
from typing import Optional, List, Tuple
from PyQt6.QtCore import Qt, QRectF, QPointF, pyqtSignal
from PyQt6.QtGui import QPainter, QColor, QPen, QBrush, QPixmap, QImage, QFont, QMouseEvent, QPaintEvent
from PyQt6.QtWidgets import QWidget, QVBoxLayout, QLabel

from ..models.computer_use_models import ComputerUseState, PerceptionBox, ActionPhase


class ComputerView(QWidget):
    """
    Renders the transformed screen capture frame with live overlay
    of bounding boxes, labels, and target action crosshairs.
    """
    coordinates_clicked = pyqtSignal(int, int)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumSize(400, 250)
        self.setMouseTracking(True)
        self._pixmap: Optional[QPixmap] = None
        self._state: ComputerUseState = ComputerUseState()
        self._last_transform: Tuple[float, float, float, float] = (0.0, 0.0, 1.0, 1.0)  # dx, dy, sx, sy

    def set_screenshot(self, image_bytes: bytes):
        image = QImage()
        if image.loadFromData(image_bytes):
            self._pixmap = QPixmap.fromImage(image)
            self.update()

    def set_screenshot_file(self, file_path: str):
        if file_path and os.path.exists(file_path):
            pix = QPixmap(file_path)
            if not pix.isNull():
                self._pixmap = pix
                self.update()

    def update_state(self, state: ComputerUseState):
        self._state = state
        if state.screenshot_bytes:
            self.set_screenshot(state.screenshot_bytes)
        elif state.screenshot_path and os.path.exists(state.screenshot_path):
            self.set_screenshot_file(state.screenshot_path)
        else:
            self.update()

    def mousePressEvent(self, a0: Optional[QMouseEvent]):
        if a0 is not None and a0.button() == Qt.MouseButton.LeftButton and self._pixmap and not self._pixmap.isNull():
            dx, dy, sx, sy = self._last_transform
            if sx > 0 and sy > 0:
                px = a0.position().x()
                py = a0.position().y()
                if dx <= px <= dx + self._pixmap.width() * sx and dy <= py <= dy + self._pixmap.height() * sy:
                    orig_x = int((px - dx) / sx)
                    orig_y = int((py - dy) / sy)
                    orig_x = max(0, min(self._pixmap.width(), orig_x))
                    orig_y = max(0, min(self._pixmap.height(), orig_y))
                    self._state.target_coordinates = (orig_x, orig_y)
                    self.coordinates_clicked.emit(orig_x, orig_y)
                    self.update()
        if a0 is not None:
            super().mousePressEvent(a0)

    def paintEvent(self, a0: Optional[QPaintEvent]):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        w = self.width()
        h = self.height()

        # Background canvas
        painter.fillRect(self.rect(), QColor("#0B0F19"))

        # If screenshot available, draw it scaled to aspect ratio
        if self._pixmap and not self._pixmap.isNull():
            scaled = self._pixmap.scaled(
                w, h,
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation
            )
            dx = (w - scaled.width()) / 2.0
            dy = (h - scaled.height()) / 2.0
            painter.drawPixmap(int(dx), int(dy), scaled)
            scale_x = scaled.width() / float(self._pixmap.width())
            scale_y = scaled.height() / float(self._pixmap.height())
            self._last_transform = (dx, dy, scale_x, scale_y)
        else:
            self._last_transform = (0.0, 0.0, 1.0, 1.0)
            # Placeholder Grid Pattern
            painter.setPen(QPen(QColor(56, 189, 248, 25), 1))
            step = 30
            for x in range(0, w, step):
                painter.drawLine(x, 0, x, h)
            for y in range(0, h, step):
                painter.drawLine(0, y, w, y)

            dx = 0
            dy = 0
            scale_x = 1.0
            scale_y = 1.0

            painter.setPen(QColor("#6B7280"))
            painter.setFont(QFont("Segoe UI Variable", 12))
            painter.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, "Computer Vision Standby\n[Awaiting Screen Observation]")

        # Draw Detected Perception Boxes
        for box in self._state.all_boxes:
            bx = dx + box.x * scale_x
            by = dy + box.y * scale_y
            bw = box.width * scale_x
            bh = box.height * scale_y

            pen = QPen(QColor("#38BDF8"), 1.2)
            painter.setPen(pen)
            painter.setBrush(QBrush(QColor(56, 189, 248, 20)))
            painter.drawRect(QRectF(bx, by, bw, bh))

            # Label tag
            tag_rect = QRectF(bx, max(0, by - 14), min(100, bw), 14)
            painter.fillRect(tag_rect, QColor("#111827"))
            painter.setPen(QColor("#38BDF8"))
            painter.setFont(QFont("Segoe UI Variable", 8, QFont.Weight.Bold))
            painter.drawText(tag_rect, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, f" {box.ref_id}")

        # Highlight Target Box if specified
        if self._state.target_box:
            tb = self._state.target_box
            tbx = dx + tb.x * scale_x
            tby = dy + tb.y * scale_y
            tbw = tb.width * scale_x
            tbh = tb.height * scale_y
            painter.setPen(QPen(QColor("#00F0FF"), 2.0))
            painter.setBrush(QBrush(QColor(0, 240, 255, 40)))
            painter.drawRect(QRectF(tbx, tby, tbw, tbh))

        # Highlight Target Coordinates Crosshair
        if self._state.target_coordinates:
            tx, ty = self._state.target_coordinates
            cx = dx + tx * scale_x
            cy = dy + ty * scale_y

            # Glowing Target Ring
            painter.setPen(QPen(QColor("#00F0FF"), 2.0))
            painter.setBrush(QBrush(QColor(0, 240, 255, 60)))
            painter.drawEllipse(QPointF(cx, cy), 14, 14)

            # Crosshair Lines
            painter.setPen(QPen(QColor("#00F0FF"), 1.5))
            painter.drawLine(int(cx - 20), int(cy), int(cx + 20), int(cy))
            painter.drawLine(int(cx), int(cy - 20), int(cx), int(cy + 20))
