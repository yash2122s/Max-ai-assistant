"""
MAX UI — Reactor Core Visualizer
Concentric orbital reactor with multi-ring rotation and reactive state glowing.
"""

import math
from typing import List
import random
from PyQt6.QtCore import Qt, QTimer, QRectF, QPointF
from PyQt6.QtGui import QPainter, QColor, QRadialGradient, QPen, QBrush
from PyQt6.QtWidgets import QWidget

from ..models.ui_state import VoiceState
from ..services.state_store import state_store


class Particle:
    def __init__(self, cx: float, cy: float, max_radius: float):
        self.cx = cx
        self.cy = cy
        self.max_radius = max_radius
        self.reset()

    def reset(self):
        self.angle = random.uniform(0, math.pi * 2)
        self.dist = random.uniform(10, self.max_radius)
        self.speed = random.uniform(0.3, 1.2)
        self.size = random.uniform(1.0, 2.5)
        self.opacity = random.uniform(0.2, 0.8)

    def update(self):
        self.dist += self.speed
        if self.dist > self.max_radius:
            self.reset()
            self.dist = 10


class ReactorCore(QWidget):
    """
    Renders the primary MAX Reactor Core.
    - Concentric orbital rings with dynamic rotation speeds.
    - Central glowing core with audio-reactive scaling.
    - Floating energy particle field.
    """

    def __init__(self, size: int = 180, parent=None):
        super().__init__(parent)
        self.setFixedSize(size, size)
        self._size = size
        self._angle1 = 0.0
        self._angle2 = 0.0
        self._phase = 0.0
        self._voice_state = VoiceState.IDLE
        self._audio_level = 0.0

        # Initialize background particles
        self._particles: List[Particle] = [
            Particle(size / 2.0, size / 2.0, size / 2.0 - 5.0)
            for _ in range(32)
        ]

        # 60 FPS Render Loop
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._on_tick)
        self._timer.start(16)

        state_store.state.voice_state_changed.connect(self._on_voice_state_changed)
        state_store.state.audio_level_changed.connect(self._on_audio_level_changed)

    def _on_voice_state_changed(self, state: str):
        self._voice_state = VoiceState(state) if state in VoiceState._value2member_map_ else VoiceState.IDLE

    def _on_audio_level_changed(self, level: float):
        self._audio_level = level

    def _on_tick(self):
        # Rotate rings based on state
        speed = 2.0 if self._voice_state in (VoiceState.THINKING, VoiceState.PROCESSING) else 0.8
        self._angle1 = (self._angle1 + speed) % 360.0
        self._angle2 = (self._angle2 - speed * 1.3) % 360.0
        self._phase += 0.04

        for p in self._particles:
            p.update()

        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        cx = self.width() / 2.0
        cy = self.height() / 2.0
        radius = min(cx, cy) - 10.0

        # State theme colors
        if self._voice_state == VoiceState.SPEAKING:
            primary_color = QColor("#00F0FF")
            secondary_color = QColor("#38BDF8")
        elif self._voice_state == VoiceState.LISTENING:
            primary_color = QColor("#38BDF8")
            secondary_color = QColor("#0284C7")
        elif self._voice_state in (VoiceState.THINKING, VoiceState.PROCESSING):
            primary_color = QColor("#C084FC")
            secondary_color = QColor("#8B5CF6")
        elif self._voice_state == VoiceState.ERROR:
            primary_color = QColor("#EF4444")
            secondary_color = QColor("#B91C1C")
        else:
            primary_color = QColor("#38BDF8")
            secondary_color = QColor("#0369A1")

        # 1. Draw Background Particles
        for p in self._particles:
            px = cx + math.cos(p.angle) * p.dist
            py = cy + math.sin(p.angle) * p.dist
            p_color = QColor(primary_color.red(), primary_color.green(), primary_color.blue(), int(p.opacity * 255))
            painter.setBrush(QBrush(p_color))
            painter.setPen(Qt.PenStyle.NoPen)
            painter.drawEllipse(QPointF(px, py), p.size, p.size)

        # 2. Outer Ambient Radial Glow
        glow_radius = radius * (1.1 + self._audio_level * 0.4)
        glow_gradient = QRadialGradient(cx, cy, glow_radius)
        glow_gradient.setColorAt(0.0, QColor(primary_color.red(), primary_color.green(), primary_color.blue(), 90))
        glow_gradient.setColorAt(0.5, QColor(secondary_color.red(), secondary_color.green(), secondary_color.blue(), 30))
        glow_gradient.setColorAt(1.0, QColor(0, 0, 0, 0))

        painter.setBrush(QBrush(glow_gradient))
        painter.setPen(Qt.PenStyle.NoPen)
        painter.drawEllipse(QRectF(cx - glow_radius, cy - glow_radius, glow_radius * 2, glow_radius * 2))

        # 3. Outer Dashed Orbital Ring 1
        painter.save()
        painter.translate(cx, cy)
        painter.rotate(self._angle1)
        pen1 = QPen(QColor(primary_color.red(), primary_color.green(), primary_color.blue(), 160), 1.6)
        pen1.setDashPattern([6.0, 4.0])
        painter.setPen(pen1)
        painter.setBrush(Qt.BrushStyle.NoBrush)
        ring1_r = radius * 0.85
        painter.drawEllipse(QRectF(-ring1_r, -ring1_r, ring1_r * 2, ring1_r * 2))
        painter.restore()

        # 4. Middle Dashed Orbital Ring 2
        painter.save()
        painter.translate(cx, cy)
        painter.rotate(self._angle2)
        pen2 = QPen(QColor(secondary_color.red(), secondary_color.green(), secondary_color.blue(), 130), 1.2)
        pen2.setDashPattern([3.0, 5.0])
        painter.setPen(pen2)
        painter.setBrush(Qt.BrushStyle.NoBrush)
        ring2_r = radius * 0.65
        painter.drawEllipse(QRectF(-ring2_r, -ring2_r, ring2_r * 2, ring2_r * 2))
        painter.restore()

        # 5. Glowing Concentric Core
        breath = 0.5 + 0.5 * math.sin(self._phase)
        core_r = radius * 0.35 * (1.0 + self._audio_level * 0.6 + breath * 0.08)

        core_gradient = QRadialGradient(cx, cy, core_r)
        core_gradient.setColorAt(0.0, QColor("#FFFFFF"))
        core_gradient.setColorAt(0.4, primary_color)
        core_gradient.setColorAt(1.0, secondary_color)

        painter.setBrush(QBrush(core_gradient))
        painter.setPen(QPen(QColor(255, 255, 255, 180), 1.2))
        painter.drawEllipse(QRectF(cx - core_r, cy - core_r, core_r * 2, core_r * 2))
