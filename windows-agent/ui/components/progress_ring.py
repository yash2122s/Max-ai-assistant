"""
MAX UI — Radial Progress Ring
Custom radial gauge for system telemetry and task step progression.
"""

from PyQt6.QtCore import Qt, QRectF
from PyQt6.QtGui import QPainter, QColor, QPen, QFont
from PyQt6.QtWidgets import QWidget


class ProgressRing(QWidget):
    def __init__(self, size: int = 56, thickness: float = 5.0,
                 value: float = 0.0, label: str = "",
                 color: str = "#38BDF8", parent=None):
        super().__init__(parent)
        self.setFixedSize(size, size)
        self._size = size
        self._thickness = thickness
        self._value = max(0.0, min(100.0, value))
        self._label = label
        self._color = QColor(color)

    @property
    def value(self) -> float:
        return self._value

    def set_value(self, val: float):
        self._value = max(0.0, min(100.0, float(val)))
        self.update()

    def set_label(self, label: str):
        self._label = label
        self.update()

    def set_color(self, color_hex: str):
        self._color = QColor(color_hex)
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        margin = self._thickness / 2.0 + 2.0
        rect = QRectF(margin, margin, self.width() - margin * 2, self.height() - margin * 2)

        # 1. Background Track
        track_pen = QPen(QColor(self._color.red(), self._color.green(), self._color.blue(), 40), self._thickness)
        track_pen.setCapStyle(Qt.PenCapStyle.RoundCap)
        painter.setPen(track_pen)
        painter.setBrush(Qt.BrushStyle.NoBrush)
        painter.drawEllipse(rect)

        # 2. Active Progress Arc
        if self._value > 0:
            arc_pen = QPen(self._color, self._thickness)
            arc_pen.setCapStyle(Qt.PenCapStyle.RoundCap)
            painter.setPen(arc_pen)
            # In Qt, 16 units = 1 degree. Start at 90 deg (12 o'clock), span clockwise (negative)
            start_angle = 90 * 16
            span_angle = -int((self._value / 100.0) * 360 * 16)
            painter.drawArc(rect, start_angle, span_angle)

        # 3. Center Text
        painter.setPen(QColor("#F9FAFB"))
        font = QFont("Segoe UI Variable", 9, QFont.Weight.Bold)
        painter.setFont(font)
        text = self._label if self._label else f"{int(self._value)}%"
        painter.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, text)
