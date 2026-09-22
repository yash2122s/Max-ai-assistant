"""
MAX UI — Home Workspace
Central launchpad with Reactor Core, quick actions, and active tasks.
"""

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QScrollArea, QFrame, QGridLayout
)

from ..components.reactor import ReactorCore
from ..components.waveform import AudioWaveform
from ..components.task_card import TaskCard
from ..components.confirmation_card import ConfirmationCard
from ..services.state_store import state_store
from ..services.workspace_router import workspace_router
from ..models.ui_state import WorkspaceType


class HomeWorkspace(QWidget):
    """
    Renders the primary MAX Home dashboard.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("HomeWorkspace")
        self._init_ui()

        state_store.state.active_task_updated.connect(self._on_task_updated)
        state_store.state.confirmation_requested.connect(self._on_confirmation_requested)
        state_store.state.confirmation_cleared.connect(self._on_confirmation_cleared)

    def _init_ui(self):
        main_layout = QVBoxLayout(self)
        main_layout.setContentsMargins(24, 20, 24, 20)
        main_layout.setSpacing(16)

        # Scroll Area for responsive sizing
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setStyleSheet("QScrollArea { border: none; background: transparent; }")

        content_widget = QWidget()
        self.content_layout = QVBoxLayout(content_widget)
        self.content_layout.setContentsMargins(0, 0, 0, 0)
        self.content_layout.setSpacing(20)

        # ── 1. Hero / Reactor Core Assembly ──────────────────────────────────
        hero_box = QVBoxLayout()
        hero_box.setAlignment(Qt.AlignmentFlag.AlignCenter)
        hero_box.setSpacing(8)

        self.reactor = ReactorCore(size=140)
        hero_box.addWidget(self.reactor, 0, Qt.AlignmentFlag.AlignCenter)

        title_lbl = QLabel("MAX")
        title_lbl.setStyleSheet("font-size: 26px; font-weight: 800; color: #F9FAFB; letter-spacing: 2px;")
        hero_box.addWidget(title_lbl, 0, Qt.AlignmentFlag.AlignCenter)

        self.status_lbl = QLabel('"How can I help you today?"')
        self.status_lbl.setStyleSheet("font-size: 14px; color: #38BDF8; font-style: italic;")
        hero_box.addWidget(self.status_lbl, 0, Qt.AlignmentFlag.AlignCenter)

        # Audio Waveform bar
        self.waveform = AudioWaveform(bar_count=20, height=28)
        self.waveform.setFixedWidth(260)
        hero_box.addWidget(self.waveform, 0, Qt.AlignmentFlag.AlignCenter)

        self.content_layout.addLayout(hero_box)

        # ── 2. Pending Confirmation Deck (Dynamic) ───────────────────────────
        self.confirmation_container = QWidget()
        self.confirmation_layout = QVBoxLayout(self.confirmation_container)
        self.confirmation_layout.setContentsMargins(0, 0, 0, 0)
        self.confirmation_container.setVisible(False)
        self.content_layout.addWidget(self.confirmation_container)

        # ── 3. Quick Action Launchers ─────────────────────────────────────────
        actions_frame = QFrame()
        actions_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.6);
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 12px;
                padding: 12px;
            }
        """)
        actions_layout = QGridLayout(actions_frame)
        actions_layout.setSpacing(10)

        buttons_data = [
            ("💬 Chat", "chat"),
            ("🖥️ Computer Use", "computer_use"),
            ("🌐 Browser", "browser"),
            ("💻 Coding", "coding"),
            ("📁 Files", "files"),
            ("⚡ Tasks", "tasks"),
        ]

        for i, (text, ws_name) in enumerate(buttons_data):
            btn = QPushButton(text)
            btn.setStyleSheet("""
                QPushButton {
                    background-color: rgba(31, 41, 55, 0.8);
                    color: #F9FAFB;
                    border: 1px solid rgba(56, 189, 248, 0.2);
                    border-radius: 8px;
                    padding: 10px;
                    font-size: 13px;
                    font-weight: 600;
                }
                QPushButton:hover {
                    background-color: rgba(56, 189, 248, 0.2);
                    border-color: #38BDF8;
                }
            """)
            btn.clicked.connect(lambda _, w=ws_name: workspace_router.navigate_to(w))
            actions_layout.addWidget(btn, i // 3, i % 3)

        self.content_layout.addWidget(actions_frame)

        # ── 4. Active Task Section ────────────────────────────────────────────
        self.task_section = QWidget()
        self.task_layout = QVBoxLayout(self.task_section)
        self.task_layout.setContentsMargins(0, 0, 0, 0)
        self.task_layout.setSpacing(8)

        task_title = QLabel("CURRENT TASK EXECUTION")
        task_title.setStyleSheet("font-weight: bold; font-size: 11px; color: #38BDF8; letter-spacing: 1px;")
        self.task_layout.addWidget(task_title)

        self.task_container = QWidget()
        self.task_container_layout = QVBoxLayout(self.task_container)
        self.task_container_layout.setContentsMargins(0, 0, 0, 0)
        self.task_layout.addWidget(self.task_container)

        self.task_section.setVisible(False)
        self.content_layout.addWidget(self.task_section)

        self.content_layout.addStretch()
        scroll.setWidget(content_widget)
        main_layout.addWidget(scroll)

    def _on_task_updated(self, task):
        # Clear existing
        while self.task_container_layout.count():
            item = self.task_container_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        if task:
            card = TaskCard(task)
            self.task_container_layout.addWidget(card)
            self.task_section.setVisible(True)
            self.status_lbl.setText(f"Executing: {task.title}")
        else:
            self.task_section.setVisible(False)
            self.status_lbl.setText('"How can I help you today?"')

    def _on_confirmation_requested(self, req):
        while self.confirmation_layout.count():
            item = self.confirmation_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        card = ConfirmationCard(req)
        self.confirmation_layout.addWidget(card)
        self.confirmation_container.setVisible(True)

    def _on_confirmation_cleared(self):
        while self.confirmation_layout.count():
            item = self.confirmation_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        self.confirmation_container.setVisible(False)
