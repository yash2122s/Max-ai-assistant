"""
MAX UI — Base Dynamic Card Component
Polymorphic glassmorphism card container.
"""

from typing import Optional
from PyQt6.QtCore import Qt, pyqtSignal
from PyQt6.QtWidgets import (
    QWidget, QFrame, QVBoxLayout, QHBoxLayout, QLabel, QPushButton
)


class DynamicCard(QFrame):
    """
    Base glassmorphic card for rendering polymorphic events, tool outputs,
    and task progress items in the central workspace.
    """

    action_triggered = pyqtSignal(str, object)  # (action_name, payload)

    def __init__(self, title: str, subtitle: str = "",
                 icon: str = "⚡", badge: str = "",
                 badge_color: str = "#38BDF8", parent=None):
        super().__init__(parent)
        self.title = title
        self.subtitle = subtitle
        self.icon = icon
        self.badge = badge
        self.badge_color = badge_color
        
        self.setObjectName("DynamicCard")
        self.setStyleSheet("""
            QFrame#DynamicCard {
                background-color: rgba(17, 24, 39, 0.85);
                border: 1px solid rgba(56, 189, 248, 0.22);
                border-radius: 12px;
                padding: 10px;
            }
            QFrame#DynamicCard:hover {
                border: 1px solid rgba(56, 189, 248, 0.45);
            }
        """)

        self._init_card_ui()

    def _init_card_ui(self):
        self.main_layout = QVBoxLayout(self)
        self.main_layout.setContentsMargins(12, 10, 12, 10)
        self.main_layout.setSpacing(8)

        # ── Header Row ───────────────────────────────────────────────────────
        self.header_layout = QHBoxLayout()
        self.header_layout.setSpacing(8)

        # Icon
        self.icon_label = QLabel(self.icon)
        self.icon_label.setStyleSheet("font-size: 16px;")
        self.header_layout.addWidget(self.icon_label)

        # Titles
        titles_box = QVBoxLayout()
        titles_box.setSpacing(2)
        self.title_label = QLabel(self.title)
        self.title_label.setStyleSheet("font-weight: bold; font-size: 13px; color: #F9FAFB;")
        titles_box.addWidget(self.title_label)

        if self.subtitle:
            self.subtitle_label = QLabel(self.subtitle)
            self.subtitle_label.setStyleSheet("font-size: 11px; color: #9CA3AF;")
            titles_box.addWidget(self.subtitle_label)

        self.header_layout.addLayout(titles_box)
        self.header_layout.addStretch()

        # Badge
        if self.badge:
            self.badge_label = QLabel(self.badge)
            self.badge_label.setStyleSheet(f"""
                background-color: rgba(56, 189, 248, 0.15);
                color: {self.badge_color};
                border: 1px solid {self.badge_color};
                border-radius: 6px;
                padding: 2px 8px;
                font-size: 10.5px;
                font-weight: bold;
            """)
            self.header_layout.addWidget(self.badge_label)

        self.main_layout.addLayout(self.header_layout)

        # ── Content Body Container ────────────────────────────────────────────
        self.body_container = QWidget()
        self.body_layout = QVBoxLayout(self.body_container)
        self.body_layout.setContentsMargins(0, 4, 0, 4)
        self.body_layout.setSpacing(6)
        self.main_layout.addWidget(self.body_container)

    def set_badge(self, text: str, color_hex: str = "#38BDF8"):
        if hasattr(self, "badge_label"):
            self.badge_label.setText(text)
            self.badge_label.setStyleSheet(f"""
                background-color: rgba(56, 189, 248, 0.15);
                color: {color_hex};
                border: 1px solid {color_hex};
                border-radius: 6px;
                padding: 2px 8px;
                font-size: 10.5px;
                font-weight: bold;
            """)
