"""
MAX UI — Memory & Knowledge Workspace
User facts, project memory graph, preferences, and session context.
"""

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit, QListWidget, QListWidgetItem, QFrame, QTextEdit
)

from ..services.state_store import state_store


class MemoryWorkspace(QWidget):
    """
    Renders permanent memory records, user facts, and contextual knowledge graphs.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("MemoryWorkspace")
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 12, 16, 16)
        layout.setSpacing(12)

        # Header
        hdr_row = QHBoxLayout()
        title_lbl = QLabel("PERMANENT MEMORY & KNOWLEDGE")
        title_lbl.setStyleSheet("font-weight: bold; font-size: 13px; color: #38BDF8; letter-spacing: 1px;")
        hdr_row.addWidget(title_lbl)
        hdr_row.addStretch()

        count_badge = QLabel("ENCRYPTED & LOCAL")
        count_badge.setStyleSheet("""
            background-color: rgba(16, 185, 129, 0.15);
            color: #10B981;
            border: 1px solid #10B981;
            border-radius: 6px;
            padding: 2px 8px;
            font-size: 10.5px;
            font-weight: bold;
        """)
        hdr_row.addWidget(count_badge)
        layout.addLayout(hdr_row)

        # Search Box
        self.search_edit = QLineEdit()
        self.search_edit.setPlaceholderText("Search permanent memory facts or project context...")
        self.search_edit.textChanged.connect(self._filter_memory)
        layout.addWidget(self.search_edit)

        # Memory List Frame
        list_frame = QFrame()
        list_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.7);
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 10px;
                padding: 6px;
            }
        """)
        lf_layout = QVBoxLayout(list_frame)
        lf_layout.setContentsMargins(4, 4, 4, 4)

        self.memory_list = QListWidget()
        self.memory_list.setStyleSheet("""
            QListWidget {
                background: transparent;
                border: none;
                color: #F9FAFB;
            }
            QListWidget::item {
                padding: 8px;
                border-bottom: 1px solid rgba(56, 189, 248, 0.1);
            }
            QListWidget::item:selected {
                background-color: rgba(56, 189, 248, 0.2);
                color: #38BDF8;
            }
        """)

        self._facts = [
            ("User Identity", "Name: Yaswanth | Role: System Architect & Developer"),
            ("Device Topology", "Primary: Windows 11 PC (Ryzen / RTX) | Mobile: Android Phone"),
            ("Preferred Workspaces", "Development in VS Code & Terminal; Gemini Live Voice interaction"),
            ("Security Policy", "Confirmation required for destructive file deletions, system reboots, or package installs"),
            ("WhatsApp & Telegram", "Explicit user permission required before opening chats"),
        ]

        for category, detail in self._facts:
            item = QListWidgetItem(f"🧠 [{category}] {detail}")
            self.memory_list.addItem(item)

        lf_layout.addWidget(self.memory_list)
        layout.addWidget(list_frame)

    def _filter_memory(self, text: str):
        q = text.lower().strip()
        self.memory_list.clear()
        for category, detail in self._facts:
            combined = f"{category} {detail}".lower()
            if not q or q in combined:
                item = QListWidgetItem(f"🧠 [{category}] {detail}")
                self.memory_list.addItem(item)
