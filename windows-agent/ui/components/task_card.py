"""
MAX UI — Task Card Component
Renders task execution progress, steps, and action controls.
"""

from typing import Optional
from PyQt6.QtCore import Qt, pyqtSignal
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QProgressBar, QPushButton
)

from .dynamic_card import DynamicCard
from ..models.task_models import TaskItem, TaskStatus


class TaskCard(DynamicCard):
    """
    Renders active, completed, or failed task status with an animated progress bar.
    """

    cancel_requested = pyqtSignal(str)  # task_id

    def __init__(self, task: TaskItem, parent=None):
        status_color = "#38BDF8" if task.status == TaskStatus.RUNNING else (
            "#10B981" if task.status == TaskStatus.COMPLETED else "#EF4444"
        )
        super().__init__(
            title=task.title,
            subtitle=f"Tool: {task.tool}" if task.tool else f"Step {task.step}/{task.total_steps}",
            icon="⚡",
            badge=task.status.value.upper(),
            badge_color=status_color,
            parent=parent,
        )
        self.task = task
        self._init_task_body()

    def _init_task_body(self):
        # Progress Bar & Percentage
        prog_row = QHBoxLayout()
        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(int(self.task.progress * 100))
        prog_row.addWidget(self.progress_bar)

        self.pct_label = QLabel(f"{int(self.task.progress * 100)}%")
        self.pct_label.setStyleSheet("font-weight: bold; color: #38BDF8; font-size: 11px;")
        prog_row.addWidget(self.pct_label)
        self.body_layout.addLayout(prog_row)

        # Step Subtitle & Action row
        action_row = QHBoxLayout()
        self.step_label = QLabel(f"Step {self.task.step} of {self.task.total_steps}")
        self.step_label.setStyleSheet("color: #9CA3AF; font-size: 11px;")
        action_row.addWidget(self.step_label)
        action_row.addStretch()

        if self.task.status == TaskStatus.RUNNING:
            self.cancel_btn = QPushButton("Cancel")
            self.cancel_btn.setObjectName("DangerButton")
            self.cancel_btn.setFixedSize(70, 24)
            self.cancel_btn.clicked.connect(lambda: self.cancel_requested.emit(self.task.task_id))
            action_row.addWidget(self.cancel_btn)

        self.body_layout.addLayout(action_row)

    def update_progress(self, progress: float, step: int, total_steps: int):
        self.task.progress = progress
        self.task.step = step
        self.task.total_steps = total_steps

        self.progress_bar.setValue(int(progress * 100))
        self.pct_label.setText(f"{int(progress * 100)}%")
        self.step_label.setText(f"Step {step} of {total_steps}")
        self.subtitle_label.setText(f"Step {step}/{total_steps}")
