"""
MAX UI — Coding & Self-Repair Workspace
Code viewer, diff inspector, test runner output, and self-repair agent.
"""

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QFrame, QSplitter, QTextEdit, QListWidget, QListWidgetItem, QPushButton
)

from ..services.state_store import state_store


class CodingWorkspace(QWidget):
    """
    Renders code inspection, unified diffs, test executions, and self-repair pipeline.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("CodingWorkspace")
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 12, 16, 16)
        layout.setSpacing(10)

        # Header Status
        hdr_box = QHBoxLayout()
        title_lbl = QLabel("CODE & SELF-REPAIR AGENT")
        title_lbl.setStyleSheet("font-weight: bold; font-size: 13px; color: #38BDF8; letter-spacing: 1px;")
        hdr_box.addWidget(title_lbl)
        hdr_box.addStretch()

        self.agent_badge = QLabel("AGENT IDLE")
        self.agent_badge.setStyleSheet("""
            background-color: rgba(56, 189, 248, 0.15);
            color: #38BDF8;
            border: 1px solid #38BDF8;
            border-radius: 6px;
            padding: 2px 8px;
            font-size: 10.5px;
            font-weight: bold;
        """)
        hdr_box.addWidget(self.agent_badge)
        layout.addLayout(hdr_box)

        # Splitter between file list / test runner and code diff viewer
        splitter = QSplitter(Qt.Orientation.Horizontal)

        # Left panel: Files & Test Suites
        left_frame = QFrame()
        left_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.7);
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 10px;
                padding: 6px;
            }
        """)
        left_layout = QVBoxLayout(left_frame)
        left_layout.setContentsMargins(6, 6, 6, 6)

        files_hdr = QLabel("PROJECT MODULES")
        files_hdr.setStyleSheet("color: #9CA3AF; font-size: 11px; font-weight: bold;")
        left_layout.addWidget(files_hdr)

        self.file_list = QListWidget()
        self.file_list.setStyleSheet("""
            QListWidget {
                background: transparent;
                border: none;
                color: #F9FAFB;
            }
            QListWidget::item {
                padding: 6px;
                border-radius: 4px;
            }
            QListWidget::item:selected {
                background-color: rgba(56, 189, 248, 0.25);
                color: #38BDF8;
            }
        """)
        modules = [
            "core/agent_execution_loop.py",
            "core/code_repair_agent.py",
            "core/computer_use_engine.py",
            "server/ui_bridge.py",
            "tools/system_control.py",
            "tools/browser_automation.py",
        ]
        for m in modules:
            self.file_list.addItem(QListWidgetItem(f"📄 {m}"))
        self.file_list.setCurrentRow(0)
        left_layout.addWidget(self.file_list)

        splitter.addWidget(left_frame)

        # Right panel: Code / Diff / Test Output
        right_frame = QFrame()
        right_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.7);
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 10px;
                padding: 6px;
            }
        """)
        right_layout = QVBoxLayout(right_frame)
        right_layout.setContentsMargins(8, 8, 8, 8)

        code_hdr = QLabel("CODE & DIFF INSPECTOR")
        code_hdr.setStyleSheet("color: #38BDF8; font-size: 11px; font-weight: bold;")
        right_layout.addWidget(code_hdr)

        self.code_view = QTextEdit()
        self.code_view.setReadOnly(True)
        self.code_view.setStyleSheet("""
            QTextEdit {
                background-color: #0B0F19;
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 8px;
                color: #A7F3D0;
                font-family: 'Consolas', monospace;
                font-size: 12px;
                padding: 10px;
            }
        """)
        self.code_view.setPlainText(
            "# MAX Autonomous Coding & Self-Repair Agent\n"
            "# Standby. Request tasks like: 'Run tests and fix any failing assertions'\n\n"
            "def verify_and_repair(codebase):\n"
            "    test_results = run_test_suite()\n"
            "    if test_results.has_failures():\n"
            "        patch = generate_minimal_ast_diff(test_results.errors)\n"
            "        apply_patch(patch)\n"
            "        return run_test_suite().is_green()\n"
            "    return True\n"
        )
        right_layout.addWidget(self.code_view)

        splitter.addWidget(right_frame)
        splitter.setStretchFactor(0, 1)
        splitter.setStretchFactor(1, 3)

        layout.addWidget(splitter)
