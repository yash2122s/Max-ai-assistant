"""
MAX UI — Tool Card Component
Renders specialized tool invocations, inputs, and outputs.
"""

from typing import Dict, Any, Optional
from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QTextEdit, QPushButton
)

from .dynamic_card import DynamicCard


class ToolCard(DynamicCard):
    """
    Renders tool execution status, parameters, and returned results.
    """

    def __init__(self, tool_name: str, status: str = "running",
                 input_data: Optional[Dict[str, Any]] = None,
                 output_data: Optional[Dict[str, Any]] = None,
                 parent=None):
        badge_color = "#38BDF8" if status == "running" else ("#10B981" if status == "completed" else "#EF4444")
        super().__init__(
            title=f"Tool: {tool_name}",
            subtitle=f"Status: {status.upper()}",
            icon="🛠️",
            badge=status.upper(),
            badge_color=badge_color,
            parent=parent,
        )
        self.tool_name = tool_name
        self.status = status
        self.input_data = input_data or {}
        self.output_data = output_data or {}

        self._populate_body()

    def _populate_body(self):
        # Parameters preview
        if self.input_data:
            param_lbl = QLabel(f"Args: {str(self.input_data)}")
            param_lbl.setStyleSheet("color: #9CA3AF; font-size: 11.5px; font-family: 'Consolas', monospace;")
            param_lbl.setWordWrap(True)
            self.body_layout.addWidget(param_lbl)

        # Output preview
        if self.output_data:
            out_lbl = QLabel(f"Result: {str(self.output_data)}")
            out_lbl.setStyleSheet("color: #38BDF8; font-size: 11.5px; font-family: 'Consolas', monospace;")
            out_lbl.setWordWrap(True)
            self.body_layout.addWidget(out_lbl)

    def set_result(self, status: str, output: Dict[str, Any]):
        self.status = status
        self.output_data = output
        badge_color = "#10B981" if status == "completed" else "#EF4444"
        self.set_badge(status.upper(), badge_color)
        self.subtitle_label.setText(f"Status: {status.upper()}")

        out_lbl = QLabel(f"Result: {str(output)}")
        out_lbl.setStyleSheet("color: #38BDF8; font-size: 11.5px; font-family: 'Consolas', monospace;")
        out_lbl.setWordWrap(True)
        self.body_layout.addWidget(out_lbl)
