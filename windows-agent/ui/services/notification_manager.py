"""
MAX UI — Notification Manager
Coordinates desktop toast notifications and system alerts.
"""

from typing import List, Dict, Any, Optional
from PyQt6.QtCore import QObject, pyqtSignal

from .state_store import state_store


class NotificationManager(QObject):
    toast_queued = pyqtSignal(str, str, str)  # (title, message, level)

    def __init__(self):
        super().__init__()
        # Forward state store notifications
        state_store.state.notification_pushed.connect(self._on_notification)

    def _on_notification(self, title: str, message: str, level: str):
        self.toast_queued.emit(title, message, level)

    def show_info(self, title: str, message: str):
        state_store.state.push_notification(title, message, "info")

    def show_success(self, title: str, message: str):
        state_store.state.push_notification(title, message, "success")

    def show_warning(self, title: str, message: str):
        state_store.state.push_notification(title, message, "warning")

    def show_error(self, title: str, message: str):
        state_store.state.push_notification(title, message, "error")


# Global singleton
notification_manager = NotificationManager()
