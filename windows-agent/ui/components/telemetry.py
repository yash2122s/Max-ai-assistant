"""
MAX UI — Telemetry Panel
Hardware performance gauges (CPU, RAM, GPU, Network) & active context.
"""

from typing import Dict, Any
from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QFrame, QGridLayout
)

from .progress_ring import ProgressRing
from ..services.state_store import state_store


class TelemetryPanel(QWidget):
    """
    Renders live system telemetry dials & active task context.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("TelemetryPanel")
        self._init_ui()

        state_store.state.telemetry_updated.connect(self._on_telemetry_updated)
        state_store.state.active_task_updated.connect(self._on_task_updated)

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(12, 12, 12, 12)
        layout.setSpacing(12)

        # Header Title
        title_label = QLabel("SYSTEM & CONTEXT")
        title_label.setStyleSheet("color: #38BDF8; font-weight: bold; font-size: 11px; letter-spacing: 1px;")
        layout.addWidget(title_label)

        # ── Grid of Progress Rings ───────────────────────────────────────────
        rings_frame = QFrame()
        rings_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.7);
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 10px;
                padding: 6px;
            }
        """)
        rings_layout = QGridLayout(rings_frame)
        rings_layout.setContentsMargins(8, 8, 8, 8)
        rings_layout.setHorizontalSpacing(14)
        rings_layout.setVerticalSpacing(8)

        # CPU Ring
        self.cpu_ring = ProgressRing(size=50, thickness=4.5, value=24, color="#38BDF8")
        cpu_lbl = QLabel("CPU")
        cpu_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        cpu_lbl.setStyleSheet("color: #9CA3AF; font-size: 11px;")
        rings_layout.addWidget(self.cpu_ring, 0, 0, Qt.AlignmentFlag.AlignCenter)
        rings_layout.addWidget(cpu_lbl, 1, 0, Qt.AlignmentFlag.AlignCenter)

        # RAM Ring
        self.ram_ring = ProgressRing(size=50, thickness=4.5, value=48, color="#8B5CF6")
        ram_lbl = QLabel("RAM")
        ram_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        ram_lbl.setStyleSheet("color: #9CA3AF; font-size: 11px;")
        rings_layout.addWidget(self.ram_ring, 0, 1, Qt.AlignmentFlag.AlignCenter)
        rings_layout.addWidget(ram_lbl, 1, 1, Qt.AlignmentFlag.AlignCenter)

        # GPU Ring
        self.gpu_ring = ProgressRing(size=50, thickness=4.5, value=18, color="#10B981")
        gpu_lbl = QLabel("GPU")
        gpu_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        gpu_lbl.setStyleSheet("color: #9CA3AF; font-size: 11px;")
        rings_layout.addWidget(self.gpu_ring, 0, 2, Qt.AlignmentFlag.AlignCenter)
        rings_layout.addWidget(gpu_lbl, 1, 2, Qt.AlignmentFlag.AlignCenter)

        layout.addWidget(rings_frame)

        # ── Context Card ─────────────────────────────────────────────────────
        ctx_frame = QFrame()
        ctx_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.7);
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 10px;
                padding: 8px;
            }
            QLabel {
                font-size: 11.5px;
            }
        """)
        ctx_layout = QVBoxLayout(ctx_frame)
        ctx_layout.setSpacing(6)

        ctx_title = QLabel("ACTIVE CONTEXT")
        ctx_title.setStyleSheet("color: #9CA3AF; font-weight: bold; font-size: 10px;")
        ctx_layout.addWidget(ctx_title)

        # Application item
        app_box = QHBoxLayout()
        app_tag = QLabel("App:")
        app_tag.setStyleSheet("color: #6B7280;")
        self.app_val = QLabel("Desktop")
        self.app_val.setStyleSheet("color: #F9FAFB; font-weight: 500;")
        app_box.addWidget(app_tag)
        app_box.addWidget(self.app_val)
        app_box.addStretch()
        ctx_layout.addLayout(app_box)

        # Tool item
        tool_box = QHBoxLayout()
        tool_tag = QLabel("Tool:")
        tool_tag.setStyleSheet("color: #6B7280;")
        self.tool_val = QLabel("Idle")
        self.tool_val.setStyleSheet("color: #38BDF8; font-weight: 500;")
        tool_box.addWidget(tool_tag)
        tool_box.addWidget(self.tool_val)
        tool_box.addStretch()
        ctx_layout.addLayout(tool_box)

        # Device item
        dev_box = QHBoxLayout()
        dev_tag = QLabel("Device:")
        dev_tag.setStyleSheet("color: #6B7280;")
        self.dev_val = QLabel("Windows PC (Local)")
        self.dev_val.setStyleSheet("color: #10B981; font-weight: 500;")
        dev_box.addWidget(dev_tag)
        dev_box.addWidget(self.dev_val)
        dev_box.addStretch()
        ctx_layout.addLayout(dev_box)

        layout.addWidget(ctx_frame)
        layout.addStretch()

    def _on_telemetry_updated(self, data: Dict[str, Any]):
        cpu = data.get("cpu_percent", 0.0)
        ram = data.get("ram_percent", 0.0)
        gpu = data.get("gpu_percent", 0.0)
        app = data.get("current_application", "Desktop")
        tool = data.get("current_tool", "Idle")

        self.cpu_ring.set_value(cpu)
        self.ram_ring.set_value(ram)
        self.gpu_ring.set_value(gpu)
        self.app_val.setText(str(app))
        self.tool_val.setText(str(tool) if tool else "Idle")

    def _on_task_updated(self, task):
        if task:
            self.tool_val.setText(task.tool if task.tool else task.title)
        else:
            self.tool_val.setText("Idle")
