"""
MAX UI — Navigation Rail Sidebar
Sidebar navigation controlling active workspace selection.
"""

from typing import Dict
from PyQt6.QtCore import Qt, pyqtSignal
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QPushButton, QLabel, QFrame, QButtonGroup
)

from ..services.workspace_router import workspace_router
from ..services.state_store import state_store
from ..models.ui_state import WorkspaceType


class NavButton(QPushButton):
    def __init__(self, workspace_type: WorkspaceType, icon: str, label: str, parent=None):
        super().__init__(f"  {icon}  {label}", parent)
        self.workspace_type = workspace_type
        self.setCheckable(True)
        self.setFixedHeight(38)
        self.setStyleSheet("""
            QPushButton {
                text-align: left;
                background-color: transparent;
                color: #9CA3AF;
                border: none;
                border-radius: 8px;
                padding-left: 10px;
                font-size: 12.5px;
                font-weight: 500;
            }
            QPushButton:hover {
                background-color: rgba(56, 189, 248, 0.12);
                color: #F9FAFB;
            }
            QPushButton:checked {
                background-color: rgba(56, 189, 248, 0.22);
                color: #38BDF8;
                font-weight: bold;
                border-left: 3px solid #38BDF8;
            }
        """)


class NavigationRail(QFrame):
    """
    Vertical sidebar containing navigation buttons for all 10 agent workspaces.
    """

    workspace_selected = pyqtSignal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("NavigationRail")
        self.setFixedWidth(160)
        self.setStyleSheet("""
            QFrame#NavigationRail {
                background-color: rgba(11, 15, 25, 0.90);
                border-right: 1px solid rgba(56, 189, 248, 0.15);
            }
        """)
        self._buttons: Dict[str, NavButton] = {}
        self._button_group = QButtonGroup(self)
        self._button_group.setExclusive(True)

        self._init_ui()
        state_store.state.workspace_changed.connect(self._on_workspace_changed)

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(8, 12, 8, 12)
        layout.setSpacing(4)

        nav_items = [
            (WorkspaceType.HOME, "🏠", "Home"),
            (WorkspaceType.CHAT, "💬", "Chat"),
            (WorkspaceType.COMPUTER_USE, "🖥️", "Computer"),
            (WorkspaceType.BROWSER, "🌐", "Browser"),
            (WorkspaceType.CODING, "💻", "Coding"),
            (WorkspaceType.FILES, "📁", "Files"),
            (WorkspaceType.TASKS, "⚡", "Tasks"),
            (WorkspaceType.MEMORY, "🧠", "Memory"),
            (WorkspaceType.DEVICES, "📱", "Devices"),
            (WorkspaceType.SETTINGS, "⚙️", "Settings"),
        ]

        for ws_type, icon, label in nav_items:
            btn = NavButton(ws_type, icon, label, self)
            btn.clicked.connect(lambda _, w=ws_type: self._on_btn_clicked(w))
            self._button_group.addButton(btn)
            self._buttons[ws_type.value] = btn
            layout.addWidget(btn)

        layout.addStretch()

        # Set default selection
        if WorkspaceType.HOME.value in self._buttons:
            self._buttons[WorkspaceType.HOME.value].setChecked(True)

    def _on_btn_clicked(self, ws_type: WorkspaceType):
        workspace_router.navigate_to(ws_type)
        self.workspace_selected.emit(ws_type.value)

    def _on_workspace_changed(self, ws_name: str):
        if ws_name in self._buttons:
            self._buttons[ws_name].setChecked(True)
