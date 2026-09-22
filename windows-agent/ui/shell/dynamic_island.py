"""
MAX UI — Dynamic Island Floating Overlay Window
Top-center floating Dynamic Island & HUD capsule with fluid expansion and dual-mode synchronization.
"""

import sys
from PyQt6.QtCore import Qt, QRect, QPropertyAnimation, QEasingCurve, pyqtSignal, QPoint
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QFrame, QPushButton, QProgressBar, QApplication
)

from ..components.reactor import ReactorCore
from ..components.waveform import AudioWaveform
from ..services.state_store import state_store
from ..services.ui_bridge_client import bridge_client
from ..models.ui_state import PresentationMode, VoiceState, AgentState


class DynamicIslandWindow(QWidget):
    """
    Topmost floating Dynamic Island capsule.
    Expands from 220x42 (idle pill) to 480x110 (expanded HUD).
    """

    expand_to_fullscreen_requested = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("DynamicIslandWindow")
        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint |
            Qt.WindowType.WindowStaysOnTopHint |
            Qt.WindowType.Tool
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)

        self._is_expanded = False
        self._min_width, self._min_height = 220, 42
        self._exp_width, self._exp_height = 480, 110

        self.setFixedSize(self._min_width, self._min_height)
        self._init_ui()
        self._position_top_center()

        # Connect to state updates
        state_store.state.voice_state_changed.connect(self._on_voice_state)
        state_store.state.active_task_updated.connect(self._on_task_updated)
        state_store.state.confirmation_requested.connect(self._on_confirmation_requested)
        state_store.state.confirmation_cleared.connect(self._on_confirmation_cleared)
        state_store.state.assistant_transcript_updated.connect(self._on_assistant_transcript)
        state_store.state.user_transcript_updated.connect(self._on_user_transcript)

    def _init_ui(self):
        self.root_layout = QVBoxLayout(self)
        self.root_layout.setContentsMargins(0, 0, 0, 0)

        # Outer Glass Capsule
        self.capsule = QFrame(self)
        self.capsule.setObjectName("IslandCapsule")
        self.capsule.setStyleSheet("""
            QFrame#IslandCapsule {
                background-color: rgba(11, 15, 25, 0.95);
                border: 1.5px solid rgba(56, 189, 248, 0.4);
                border-radius: 21px;
            }
        """)
        self.capsule_layout = QVBoxLayout(self.capsule)
        self.capsule_layout.setContentsMargins(12, 6, 12, 6)

        # ── 1. Compact View (220 x 42) ───────────────────────────────────────
        self.compact_widget = QWidget()
        comp_layout = QHBoxLayout(self.compact_widget)
        comp_layout.setContentsMargins(0, 0, 0, 0)
        comp_layout.setSpacing(8)

        self.compact_reactor = ReactorCore(size=22)
        comp_layout.addWidget(self.compact_reactor)

        self.compact_title = QLabel("MAX")
        self.compact_title.setStyleSheet("font-weight: 800; font-size: 13px; color: #38BDF8; letter-spacing: 1px;")
        comp_layout.addWidget(self.compact_title)

        comp_layout.addStretch()

        self.compact_dot = QLabel("●")
        self.compact_dot.setStyleSheet("color: #10B981; font-size: 9px;")
        comp_layout.addWidget(self.compact_dot)

        self.capsule_layout.addWidget(self.compact_widget)

        # ── 2. Expanded HUD View (480 x 110) ─────────────────────────────────
        self.expanded_widget = QWidget()
        exp_layout = QVBoxLayout(self.expanded_widget)
        exp_layout.setContentsMargins(0, 0, 0, 0)
        exp_layout.setSpacing(6)

        # Top row: Reactor + Status + Full-Screen Button
        top_row = QHBoxLayout()
        self.exp_reactor = ReactorCore(size=24)
        top_row.addWidget(self.exp_reactor)

        self.exp_status_lbl = QLabel("LISTENING")
        self.exp_status_lbl.setStyleSheet("font-weight: bold; font-size: 11px; color: #38BDF8;")
        top_row.addWidget(self.exp_status_lbl)

        top_row.addStretch()

        # Full-Screen Expand Button
        self.fs_btn = QPushButton("⛶ App")
        self.fs_btn.setFixedSize(55, 20)
        self.fs_btn.setStyleSheet("""
            QPushButton {
                background-color: rgba(56, 189, 248, 0.15);
                color: #38BDF8;
                border: 1px solid rgba(56, 189, 248, 0.3);
                border-radius: 4px;
                font-size: 10px;
                font-weight: bold;
            }
            QPushButton:hover {
                background-color: rgba(56, 189, 248, 0.35);
            }
        """)
        self.fs_btn.clicked.connect(self.expand_to_fullscreen_requested.emit)
        top_row.addWidget(self.fs_btn)
        exp_layout.addLayout(top_row)

        # Middle row: Waveform / Progress
        self.exp_waveform = AudioWaveform(bar_count=16, height=22)
        exp_layout.addWidget(self.exp_waveform)

        # Subtitle / Speech transcript
        self.exp_transcript = QLabel("")
        self.exp_transcript.setStyleSheet("color: #9CA3AF; font-size: 11px;")
        exp_layout.addWidget(self.exp_transcript)

        self.expanded_widget.setVisible(False)
        self.capsule_layout.addWidget(self.expanded_widget)

        self.root_layout.addWidget(self.capsule)

    def _position_top_center(self):
        screen = QApplication.primaryScreen()
        if screen:
            rect = screen.geometry()
            x = (rect.width() - self.width()) // 2
            y = 12
            self.move(x, y)

    def expand_island(self):
        if self._is_expanded:
            return
        self._is_expanded = True
        self.compact_widget.setVisible(False)
        self.expanded_widget.setVisible(True)

        self.anim = QPropertyAnimation(self, b"geometry")
        self.anim.setDuration(220)
        self.anim.setEasingCurve(QEasingCurve.Type.OutCubic)

        screen = QApplication.primaryScreen()
        sw = screen.geometry().width() if screen else 1920
        new_x = (sw - self._exp_width) // 2

        self.anim.setStartValue(QRect(self.x(), self.y(), self._min_width, self._min_height))
        self.anim.setEndValue(QRect(new_x, 12, self._exp_width, self._exp_height))
        self.anim.start()

    def collapse_island(self):
        if not self._is_expanded:
            return
        self._is_expanded = False
        self.expanded_widget.setVisible(False)
        self.compact_widget.setVisible(True)

        self.anim = QPropertyAnimation(self, b"geometry")
        self.anim.setDuration(220)
        self.anim.setEasingCurve(QEasingCurve.Type.OutCubic)

        screen = QApplication.primaryScreen()
        sw = screen.geometry().width() if screen else 1920
        new_x = (sw - self._min_width) // 2

        self.anim.setStartValue(QRect(self.x(), self.y(), self._exp_width, self._exp_height))
        self.anim.setEndValue(QRect(new_x, 12, self._min_width, self._min_height))
        self.anim.start()

    def _on_voice_state(self, state: str):
        st = VoiceState(state) if state in VoiceState._value2member_map_ else VoiceState.IDLE
        if st != VoiceState.IDLE:
            self.expand_island()
            self.exp_status_lbl.setText(st.value.upper())
        else:
            if not state_store.state.active_task and not state_store.state.pending_confirmation:
                self.collapse_island()

    def _on_task_updated(self, task):
        if task:
            self.expand_island()
            self.exp_status_lbl.setText(f"TASK: {task.title.upper()} ({int(task.progress * 100)}%)")
        else:
            if state_store.state.voice_state == VoiceState.IDLE:
                self.collapse_island()

    def _on_confirmation_requested(self, req):
        self.expand_island()
        self.exp_status_lbl.setText("⚠️ ACTION REQUIRED")
        self.exp_transcript.setText(f"{req.action_name}: {req.description}")

    def _on_confirmation_cleared(self):
        if state_store.state.voice_state == VoiceState.IDLE and not state_store.state.active_task:
            self.collapse_island()

    def _on_assistant_transcript(self, text: str):
        if text:
            self.exp_transcript.setText(f"✨ {text}")

    def _on_user_transcript(self, text: str):
        if text:
            self.exp_transcript.setText(f"🗣️ {text}")
