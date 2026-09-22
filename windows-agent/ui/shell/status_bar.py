"""
MAX UI — Bottom Status Bar
Displays live voice state, current task subtitle, audio waveform, and quick metrics.
"""

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget, QHBoxLayout, QLabel, QFrame
)

from ..components.waveform import AudioWaveform
from ..services.state_store import state_store
from ..models.ui_state import VoiceState


class StatusBar(QFrame):
    """
    Bottom status bar for real-time telemetry and voice state feedback.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("StatusBar")
        self.setFixedHeight(30)
        self.setStyleSheet("""
            QFrame#StatusBar {
                background-color: rgba(11, 15, 25, 0.95);
                border-top: 1px solid rgba(56, 189, 248, 0.15);
            }
            QLabel {
                font-size: 11px;
            }
        """)
        self._init_ui()

        state_store.state.voice_state_changed.connect(self._on_voice_state_changed)
        state_store.state.active_task_updated.connect(self._on_task_updated)
        state_store.state.telemetry_updated.connect(self._on_telemetry_updated)

    def _init_ui(self):
        layout = QHBoxLayout(self)
        layout.setContentsMargins(14, 0, 14, 0)
        layout.setSpacing(12)

        # Voice State Tag
        self.voice_tag = QLabel("🎙️ IDLE")
        self.voice_tag.setStyleSheet("color: #9CA3AF; font-weight: bold;")
        layout.addWidget(self.voice_tag)

        # Current Task / Activity Subtitle
        self.task_sub = QLabel("Standby")
        self.task_sub.setStyleSheet("color: #38BDF8; font-weight: 500;")
        layout.addWidget(self.task_sub)

        layout.addStretch()

        # Audio Waveform Mini
        self.mini_waveform = AudioWaveform(bar_count=12, height=18)
        self.mini_waveform.setFixedWidth(70)
        layout.addWidget(self.mini_waveform)

        # Quick Telemetry
        self.telemetry_lbl = QLabel("CPU 0% | RAM 0%")
        self.telemetry_lbl.setStyleSheet("color: #6B7280; font-family: 'Consolas', monospace;")
        layout.addWidget(self.telemetry_lbl)

    def _on_voice_state_changed(self, state: str):
        st = VoiceState(state) if state in VoiceState._value2member_map_ else VoiceState.IDLE
        if st == VoiceState.LISTENING:
            self.voice_tag.setText("🎙️ LISTENING")
            self.voice_tag.setStyleSheet("color: #38BDF8; font-weight: bold;")
        elif st in (VoiceState.THINKING, VoiceState.PROCESSING):
            self.voice_tag.setText("🧠 THINKING")
            self.voice_tag.setStyleSheet("color: #8B5CF6; font-weight: bold;")
        elif st == VoiceState.SPEAKING:
            self.voice_tag.setText("🗣️ SPEAKING")
            self.voice_tag.setStyleSheet("color: #00F0FF; font-weight: bold;")
        elif st == VoiceState.WAKE_DETECTED:
            self.voice_tag.setText("⚡ WAKE")
            self.voice_tag.setStyleSheet("color: #00F0FF; font-weight: bold;")
        else:
            self.voice_tag.setText("🎙️ IDLE")
            self.voice_tag.setStyleSheet("color: #9CA3AF; font-weight: bold;")

    def _on_task_updated(self, task):
        if task:
            self.task_sub.setText(f"Task: {task.title} ({int(task.progress * 100)}%)")
        else:
            self.task_sub.setText("Standby")

    def _on_telemetry_updated(self, data):
        cpu = int(data.get("cpu_percent", 0))
        ram = int(data.get("ram_percent", 0))
        self.telemetry_lbl.setText(f"CPU {cpu}% | RAM {ram}%")
