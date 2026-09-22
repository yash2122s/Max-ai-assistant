"""
MAX UI — Tasks & Automation Workspace
Manages active, scheduled, completed, and recurring background tasks.
"""

from typing import List
from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QScrollArea, QFrame, QPushButton, QTabWidget
)

from ..components.task_card import TaskCard
from ..models.task_models import TaskItem, TaskStatus
from ..services.state_store import state_store


class TasksWorkspace(QWidget):
    """
    Renders task queue, execution milestones, scheduled reminders, and completion history.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("TasksWorkspace")
        self._init_ui()

        state_store.state.task_list_updated.connect(self._on_task_list_updated)
        state_store.state.active_task_updated.connect(self._on_active_task_updated)

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 12, 16, 16)
        layout.setSpacing(12)

        # Header
        hdr_row = QHBoxLayout()
        title_lbl = QLabel("TASKS & AUTOMATION QUEUE")
        title_lbl.setStyleSheet("font-weight: bold; font-size: 13px; color: #38BDF8; letter-spacing: 1px;")
        hdr_row.addWidget(title_lbl)
        hdr_row.addStretch()

        self.count_badge = QLabel("0 ACTIVE")
        self.count_badge.setStyleSheet("""
            background-color: rgba(56, 189, 248, 0.15);
            color: #38BDF8;
            border: 1px solid #38BDF8;
            border-radius: 6px;
            padding: 2px 8px;
            font-size: 10.5px;
            font-weight: bold;
        """)
        hdr_row.addWidget(self.count_badge)
        layout.addLayout(hdr_row)

        # Tabs for Active / Completed
        self.tabs = QTabWidget()
        self.tabs.setStyleSheet("""
            QTabWidget::pane {
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 8px;
                background-color: rgba(17, 24, 39, 0.7);
            }
            QTabBar::tab {
                background: #111827;
                color: #9CA3AF;
                padding: 6px 14px;
                margin-right: 4px;
                border-top-left-radius: 6px;
                border-top-right-radius: 6px;
                font-weight: 500;
            }
            QTabBar::tab:selected {
                background: rgba(56, 189, 248, 0.2);
                color: #38BDF8;
                border-bottom: 2px solid #38BDF8;
            }
        """)

        # Active Tab
        self.active_scroll = QScrollArea()
        self.active_scroll.setWidgetResizable(True)
        self.active_scroll.setStyleSheet("border: none; background: transparent;")
        self.active_container = QWidget()
        self.active_layout = QVBoxLayout(self.active_container)
        self.active_layout.setContentsMargins(10, 10, 10, 10)
        self.active_layout.setSpacing(10)
        self.active_layout.addStretch()
        self.active_scroll.setWidget(self.active_container)
        self.tabs.addTab(self.active_scroll, "⚡ Active Tasks")

        # History Tab
        self.history_scroll = QScrollArea()
        self.history_scroll.setWidgetResizable(True)
        self.history_scroll.setStyleSheet("border: none; background: transparent;")
        self.history_container = QWidget()
        self.history_layout = QVBoxLayout(self.history_container)
        self.history_layout.setContentsMargins(10, 10, 10, 10)
        self.history_layout.setSpacing(10)
        self.history_layout.addStretch()
        self.history_scroll.setWidget(self.history_container)
        self.tabs.addTab(self.history_scroll, "📋 Task History")

        layout.addWidget(self.tabs)

    def _on_task_list_updated(self, tasks: List[TaskItem]):
        # Clear both tabs
        while self.active_layout.count() > 1:
            item = self.active_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        while self.history_layout.count() > 1:
            item = self.history_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        active_count = 0
        for task in tasks:
            card = TaskCard(task)
            if task.status == TaskStatus.RUNNING:
                self.active_layout.insertWidget(self.active_layout.count() - 1, card)
                active_count += 1
            else:
                self.history_layout.insertWidget(self.history_layout.count() - 1, card)

        self.count_badge.setText(f"{active_count} ACTIVE")

    def _on_active_task_updated(self, task):
        self._on_task_list_updated(state_store.state.tasks)
