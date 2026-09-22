"""
MAX UI — Chat Message Component
Message bubble with role badge, markdown formatting, and copy button.
"""

from typing import Optional
from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget, QFrame, QVBoxLayout, QHBoxLayout, QLabel, QTextBrowser, QPushButton, QApplication
)

from ..models.chat_models import ChatMessage, MessageRole


class ChatMessageWidget(QFrame):
    """
    Renders an individual chat message from user, assistant, system, or tool.
    """

    def __init__(self, message: ChatMessage, parent=None):
        super().__init__(parent)
        self.message = message
        self._init_ui()

    def _init_ui(self):
        is_user = self.message.role == MessageRole.USER
        bg_color = "rgba(56, 189, 248, 0.12)" if is_user else "rgba(17, 24, 39, 0.85)"
        border_color = "rgba(56, 189, 248, 0.35)" if is_user else "rgba(56, 189, 248, 0.15)"

        self.setStyleSheet(f"""
            QFrame {{
                background-color: {bg_color};
                border: 1px solid {border_color};
                border-radius: 12px;
                padding: 6px;
            }}
        """)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 8, 10, 8)
        layout.setSpacing(6)

        # Header with role badge and copy button
        hdr_row = QHBoxLayout()
        role_label = QLabel("YOU" if is_user else "MAX")
        role_color = "#38BDF8" if is_user else "#00F0FF"
        role_label.setStyleSheet(f"font-weight: bold; font-size: 11px; color: {role_color};")
        hdr_row.addWidget(role_label)

        # Time
        import datetime
        t_str = datetime.datetime.fromtimestamp(self.message.timestamp).strftime("%H:%M")
        time_label = QLabel(t_str)
        time_label.setStyleSheet("color: #6B7280; font-size: 10px;")
        hdr_row.addWidget(time_label)

        hdr_row.addStretch()

        # Copy button
        copy_btn = QPushButton("Copy")
        copy_btn.setFixedSize(50, 20)
        copy_btn.setStyleSheet("""
            QPushButton {
                background-color: transparent;
                color: #9CA3AF;
                border: none;
                font-size: 10px;
            }
            QPushButton:hover {
                color: #38BDF8;
            }
        """)
        copy_btn.clicked.connect(self._copy_text)
        hdr_row.addWidget(copy_btn)

        layout.addLayout(hdr_row)

        # Message Content
        self.content_browser = QTextBrowser()
        self.content_browser.setOpenExternalLinks(True)
        self.content_browser.setReadOnly(True)
        self.content_browser.setStyleSheet("""
            QTextBrowser {
                background-color: transparent;
                border: none;
                color: #F9FAFB;
                font-size: 13px;
                selection-background-color: #38BDF8;
                selection-color: #0B0F19;
            }
        """)
        # Convert plain text or markdown to simple rich text
        self.content_browser.setMarkdown(self.message.content)
        self.content_browser.document().adjustSize()
        h = int(self.content_browser.document().size().height()) + 10
        self.content_browser.setFixedHeight(max(30, min(400, h)))

        layout.addWidget(self.content_browser)

    def _copy_text(self):
        clipboard = QApplication.clipboard()
        if clipboard:
            clipboard.setText(self.message.content)
