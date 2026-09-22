"""
MAX UI — Command Palette Component
Quick action search and trigger modal (Ctrl+K).
"""

from typing import List, Tuple
from PyQt6.QtCore import Qt, pyqtSignal
from PyQt6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QLineEdit, QListWidget, QListWidgetItem, QLabel
)

from ..services.workspace_router import workspace_router
from ..models.ui_state import WorkspaceType


class CommandPalette(QDialog):
    """
    Spotlight / Command palette modal for rapid navigation and tool triggering.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowFlags(Qt.WindowType.FramelessWindowHint | Qt.WindowType.Dialog)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setFixedSize(500, 320)

        self._actions: List[Tuple[str, str, str]] = [
            ("Home", "🏠 Open Home Dashboard", "home"),
            ("Chat", "💬 Open JARVIS Chat", "chat"),
            ("Computer Use", "🖥️ Open Computer Use Automation", "computer_use"),
            ("Browser", "🌐 Open Browser Workspace", "browser"),
            ("Coding", "💻 Open Coding & Patch Inspector", "coding"),
            ("Files", "📁 Open Files Explorer", "files"),
            ("Tasks", "⚡ Open Active & Scheduled Tasks", "tasks"),
            ("Memory", "🧠 Open Knowledge & Memory Graph", "memory"),
            ("Devices", "📱 Open Connected Devices Ecosystem", "devices"),
            ("Settings", "⚙️ Open Agent Settings", "settings"),
        ]

        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)

        from PyQt6.QtWidgets import QFrame
        container = QFrame()
        container.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.98);
                border: 1.5px solid #38BDF8;
                border-radius: 12px;
            }
        """)
        c_layout = QVBoxLayout(container)
        c_layout.setContentsMargins(12, 12, 12, 12)
        c_layout.setSpacing(8)

        # Search Input
        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("Search actions or workspaces (Ctrl+K)...")
        self.search_input.setStyleSheet("""
            QLineEdit {
                background-color: #111827;
                color: #F9FAFB;
                border: 1px solid rgba(56, 189, 248, 0.4);
                border-radius: 8px;
                padding: 10px 12px;
                font-size: 14px;
            }
        """)
        self.search_input.textChanged.connect(self._filter_items)
        c_layout.addWidget(self.search_input)

        # Action List
        self.list_widget = QListWidget()
        self.list_widget.setStyleSheet("""
            QListWidget {
                background-color: transparent;
                border: none;
                color: #F9FAFB;
            }
            QListWidget::item {
                padding: 8px;
                border-radius: 6px;
            }
            QListWidget::item:selected {
                background-color: rgba(56, 189, 248, 0.25);
                color: #38BDF8;
            }
        """)
        self.list_widget.itemActivated.connect(self._on_item_activated)
        c_layout.addWidget(self.list_widget)

        layout.addWidget(container)
        self._populate_list(self._actions)

    def _populate_list(self, items: List[Tuple[str, str, str]]):
        self.list_widget.clear()
        for title, desc, target in items:
            item = QListWidgetItem(f"{desc}")
            item.setData(Qt.ItemDataRole.UserRole, target)
            self.list_widget.addItem(item)
        if self.list_widget.count() > 0:
            self.list_widget.setCurrentRow(0)

    def _filter_items(self, query: str):
        q = query.lower().strip()
        if not q:
            self._populate_list(self._actions)
            return

        filtered = [
            (t, d, target) for t, d, target in self._actions
            if q in t.lower() or q in d.lower() or q in target.lower()
        ]
        self._populate_list(filtered)

    def _on_item_activated(self, item: QListWidgetItem):
        target = item.data(Qt.ItemDataRole.UserRole)
        if target:
            workspace_router.navigate_to(target)
        self.accept()

    def keyPressEvent(self, event):
        if event.key() == Qt.Key.Key_Escape:
            self.reject()
        elif event.key() in (Qt.Key.Key_Return, Qt.Key.Key_Enter):
            curr = self.list_widget.currentItem()
            if curr:
                self._on_item_activated(curr)
        else:
            super().keyPressEvent(event)
