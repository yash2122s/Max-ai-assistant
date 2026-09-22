"""
MAX UI — Audio Waveform Visualizer
16-bar symmetrical audio frequency visualizer.
"""

import math
from typing import List
from PyQt6.QtCore import Qt, QTimer, QRectF
from PyQt6.QtGui import QPainter, QColor, QLinearGradient, QBrush, QPen
from PyQt6.QtWidgets import QWidget

from ..models.ui_state import VoiceState
from ..services.state_store import state_store


class AudioWaveform(QWidget):
    """
    Renders 16 vertical audio frequency bars with symmetrical heights
    and smooth attack/decay filtering.
    """

    def __init__(self, bar_count: int = 16, height: int = 36, parent=None):
        super().__init__(parent)
        self.bar_count = bar_count
        self.setFixedHeight(height)
        self.setMinimumWidth(120)

        self._audio_level = 0.0
        self._smoothed_levels: List[float] = [0.1] * bar_count
        self._phase = 0.0
        self._voice_state = VoiceState.IDLE

        # 60 FPS Render Timer
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._on_tick)
        self._timer.start(16)

        state_store.state.audio_level_changed.connect(self._on_audio_level_changed)
        state_store.state.voice_state_changed.connect(self._on_voice_state_changed)

    def _on_audio_level_changed(self, level: float):
        self._audio_level = level

    def _on_voice_state_changed(self, state: str):
        self._voice_state = VoiceState(state) if state in VoiceState._value2member_map_ else VoiceState.IDLE

    def _on_tick(self):
        self._phase += 0.08
        mid = self.bar_count / 2.0

        # Calculate target heights for each bar (bell-curve shaped distribution)
        for i in range(self.bar_count):
            dist_from_center = abs(i - mid) / mid
            envelope = math.exp(-2.5 * dist_from_center * dist_from_center)
            harmonic = math.sin(self._phase + i * 0.4) * 0.15

            target = max(0.08, (self._audio_level * 0.85 + harmonic + 0.05) * envelope)
            target = min(1.0, max(0.05, target))

            # Fast attack (0.45), smooth decay (0.15)
            curr = self._smoothed_levels[i]
            if target > curr:
                self._smoothed_levels[i] += (target - curr) * 0.45
            else:
                self._smoothed_levels[i] += (target - curr) * 0.15

        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        w = self.width()
        h = self.height()
        spacing = 3.0
        total_spacing = spacing * (self.bar_count - 1)
        bar_width = max(2.0, (w - total_spacing) / self.bar_count)

        # Gradients based on speaking vs listening
        if self._voice_state == VoiceState.SPEAKING:
            c1 = QColor("#00F0FF")
            c2 = QColor("#38BDF8")
        elif self._voice_state in (VoiceState.THINKING, VoiceState.PROCESSING):
            c1 = QColor("#C084FC")
            c2 = QColor("#8B5CF6")
        else:
            c1 = QColor("#38BDF8")
            c2 = QColor("#0284C7")

        for i, val in enumerate(self._smoothed_levels):
            x = i * (bar_width + spacing)
            bar_height = max(4.0, h * val * 0.9)
            y = (h - bar_height) / 2.0

            gradient = QLinearGradient(x, y, x, y + bar_height)
            gradient.setColorAt(0.0, c1)
            gradient.setColorAt(1.0, c2)

            painter.setBrush(QBrush(gradient))
            painter.setPen(Qt.PenStyle.NoPen)
            painter.drawRoundedRect(QRectF(x, y, bar_width, bar_height), bar_width / 2.0, bar_width / 2.0)
