"""
MAX UI — Avatar Component
Circular glowing status beacon and avatar indicator.
"""

from PyQt6.QtCore import Qt, QTimer, QRectF
from PyQt6.QtGui import QPainter, QColor, QRadialGradient, QPen, QBrush
from PyQt6.QtWidgets import QWidget

from ..models.ui_state import VoiceState
from ..services.state_store import state_store


class MaxAvatar(QWidget):
    """
    Renders the MAX AI glowing circular avatar with reactive status halo.
    """

    def __init__(self, size: int = 48, parent=None):
        super().__init__(parent)
        self._size = size
        self.setFixedSize(size, size)
        self._phase = 0.0
        self._voice_state = VoiceState.IDLE
        self._audio_level = 0.0

        # Animation timer (~60 FPS)
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._on_tick)
        self._timer.start(16)

        # Connect to state store
        state_store.state.voice_state_changed.connect(self._on_voice_state_changed)
        state_store.state.audio_level_changed.connect(self._on_audio_level_changed)

    def _on_voice_state_changed(self, state: str):
        self._voice_state = VoiceState(state) if state in VoiceState._value2member_map_ else VoiceState.IDLE
        self.update()

    def _on_audio_level_changed(self, level: float):
        self._audio_level = level

    def _on_tick(self):
        self._phase += 0.05
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        center_x = self.width() / 2.0
        center_y = self.height() / 2.0
        base_radius = (min(self.width(), self.height()) / 2.0) - 4.0

        # Colors based on state
        if self._voice_state == VoiceState.SPEAKING:
            glow_color = QColor("#00F0FF")
            core_color = QColor("#38BDF8")
            pulse_mult = 1.0 + self._audio_level * 0.5
        elif self._voice_state == VoiceState.LISTENING:
            glow_color = QColor("#38BDF8")
            core_color = QColor("#0284C7")
            pulse_mult = 1.0 + self._audio_level * 0.3
        elif self._voice_state in (VoiceState.THINKING, VoiceState.PROCESSING):
            glow_color = QColor("#8B5CF6")
            core_color = QColor("#6D28D9")
            pulse_mult = 1.0 + 0.15 * (0.5 + 0.5 * (self._phase % 1.0))
        elif self._voice_state == VoiceState.ERROR:
            glow_color = QColor("#EF4444")
            core_color = QColor("#B91C1C")
            pulse_mult = 1.0
        else:
            # Idle breathing
            import math
            glow_color = QColor("#38BDF8")
            core_color = QColor("#0369A1")
            breath = 0.5 + 0.5 * math.sin(self._phase)
            pulse_mult = 0.95 + 0.08 * breath

        # Outer Halo Gradient
        halo_gradient = QRadialGradient(center_x, center_y, base_radius * 1.3 * pulse_mult)
        halo_gradient.setColorAt(0.0, QColor(glow_color.red(), glow_color.green(), glow_color.blue(), 100))
        halo_gradient.setColorAt(0.6, QColor(glow_color.red(), glow_color.green(), glow_color.blue(), 35))
        halo_gradient.setColorAt(1.0, QColor(glow_color.red(), glow_color.green(), glow_color.blue(), 0))

        painter.setBrush(QBrush(halo_gradient))
        painter.setPen(Qt.PenStyle.NoPen)
        painter.drawEllipse(QRectF(
            center_x - base_radius * 1.3 * pulse_mult,
            center_y - base_radius * 1.3 * pulse_mult,
            base_radius * 2.6 * pulse_mult,
            base_radius * 2.6 * pulse_mult
        ))

        # Core Circle
        core_gradient = QRadialGradient(center_x, center_y, base_radius)
        core_gradient.setColorAt(0.0, glow_color)
        core_gradient.setColorAt(1.0, core_color)

        painter.setBrush(QBrush(core_gradient))
        painter.setPen(QPen(QColor(255, 255, 255, 120), 1.5))
        painter.drawEllipse(QRectF(
            center_x - base_radius,
            center_y - base_radius,
            base_radius * 2.0,
            base_radius * 2.0
        ))
