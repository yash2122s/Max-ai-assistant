"""
MAX UI — Toast Notification Overlay
Non-blocking animated pop-in toast notification stack.
"""

from typing import List
from PyQt6.QtCore import Qt, QTimer, QPropertyAnimation, QEasingCurve, QRect, QPoint
from PyQt6.QtWidgets import (
    QWidget, QFrame, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QGraphicsOpacityEffect
)

from ..services.notification_manager import notification_manager


class ToastNotification(QFrame):
    def __init__(self, title: str, message: str, level: str = "info", parent=None):
        super().__init__(parent)
        self.level = level
        
        color_map = {
            "info": ("#38BDF8", "ℹ️"),
            "success": ("#10B981", "✓"),
            "warning": ("#F59E0B", "⚠️"),
            "error": ("#EF4444", "✕"),
        }
        color, icon = color_map.get(level, ("#38BDF8", "ℹ️"))

        self.setStyleSheet(f"""
            QFrame {{
                background-color: rgba(17, 24, 39, 0.95);
                border: 1px solid {color};
                border-left: 4px solid {color};
                border-radius: 8px;
                padding: 6px;
            }}
        """)
        self.setFixedWidth(280)

        layout = QHBoxLayout(self)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(8)

        # Icon
        icon_lbl = QLabel(icon)
        icon_lbl.setStyleSheet(f"font-size: 14px; color: {color};")
        layout.addWidget(icon_lbl)

        # Text
        text_layout = QVBoxLayout()
        text_layout.setSpacing(2)
        title_lbl = QLabel(title)
        title_lbl.setStyleSheet("font-weight: bold; font-size: 12px; color: #F9FAFB;")
        text_layout.addWidget(title_lbl)

        msg_lbl = QLabel(message)
        msg_lbl.setStyleSheet("font-size: 11px; color: #9CA3AF;")
        msg_lbl.setWordWrap(True)
        text_layout.addWidget(msg_lbl)
        layout.addLayout(text_layout)

        # Close button
        close_btn = QPushButton("✕")
        close_btn.setFixedSize(18, 18)
        close_btn.setStyleSheet("""
            QPushButton {
                background: transparent;
                border: none;
                color: #6B7280;
                font-size: 10px;
            }
            QPushButton:hover {
                color: #F9FAFB;
            }
        """)
        close_btn.clicked.connect(self.close)
        layout.addWidget(close_btn, 0, Qt.AlignmentFlag.AlignTop)

        # Auto-dismiss timer (4 seconds)
        QTimer.singleShot(4000, self.close)


class ToastOverlay(QWidget):
    """
    Floating toast stack container positioned at bottom-right of parent window.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, True)
        
        self.layout = QVBoxLayout(self)
        self.layout.setAlignment(Qt.AlignmentFlag.AlignBottom | Qt.AlignmentFlag.AlignRight)
        self.layout.setContentsMargins(16, 16, 16, 16)
        self.layout.setSpacing(8)

        notification_manager.toast_queued.connect(self.show_toast)

    def show_toast(self, title: str, message: str, level: str = "info"):
        toast = ToastNotification(title, message, level, self)
        self.layout.addWidget(toast)
