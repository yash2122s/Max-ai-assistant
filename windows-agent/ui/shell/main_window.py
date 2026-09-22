"""
MAX UI — Main Application Shell Window
Full-screen capable desktop application shell with dynamic workspaces and context rail.
"""

from typing import Dict
from PyQt6.QtCore import Qt, QPoint, pyqtSignal, QEvent
from PyQt6.QtGui import QKeySequence, QShortcut
from PyQt6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QStackedWidget, QSplitter, QFrame, QApplication
)

from .header import HeaderBar
from .navigation import NavigationRail
from .status_bar import StatusBar
from .dynamic_island import DynamicIslandWindow

from ..workspaces.home import HomeWorkspace
from ..workspaces.chat import ChatWorkspace
from ..workspaces.computer_use import ComputerUseWorkspace
from ..workspaces.browser import BrowserWorkspace
from ..workspaces.coding import CodingWorkspace
from ..workspaces.files import FilesWorkspace
from ..workspaces.tasks import TasksWorkspace
from ..workspaces.memory import MemoryWorkspace
from ..workspaces.devices import DevicesWorkspace
from ..workspaces.settings import SettingsWorkspace

from ..components.telemetry import TelemetryPanel
from ..components.toast import ToastOverlay
from ..components.command_palette import CommandPalette

from ..services.state_store import state_store
from ..services.workspace_router import workspace_router
from ..services.ui_bridge_client import bridge_client
from ..services.notification_manager import notification_manager
from ..models.ui_state import WorkspaceType, PresentationMode


class MainWindow(QMainWindow):
    """
    Primary desktop application shell for MAX Windows Agent.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("MAXMainWindow")
        self.setWindowTitle("MAX Agent")
        self.setMinimumSize(850, 560)

        # Frameless window configuration with custom header
        self.setWindowFlags(Qt.WindowType.FramelessWindowHint | Qt.WindowType.Window)

        # Standalone Dynamic Island overlay instance
        self.dynamic_island = DynamicIslandWindow()
        self.dynamic_island.expand_to_fullscreen_requested.connect(self._show_fullscreen_app)

        self._workspace_map: Dict[str, int] = {}
        self._init_ui()
        self._init_shortcuts()
        self._init_window_geometry()

        # Connect state store signals
        state_store.state.workspace_changed.connect(self._on_workspace_changed)
        state_store.state.presentation_mode_changed.connect(self._on_presentation_mode_changed)

    def _init_ui(self):
        central_widget = QWidget(self)
        self.setCentralWidget(central_widget)

        root_layout = QVBoxLayout(central_widget)
        root_layout.setContentsMargins(0, 0, 0, 0)
        root_layout.setSpacing(0)

        # ── 1. Top Custom Header Bar ─────────────────────────────────────────
        self.header = HeaderBar(self)
        self.header.minimize_requested.connect(self.showMinimized)
        self.header.maximize_restore_requested.connect(self._toggle_maximize)
        self.header.close_requested.connect(self.close)
        self.header.island_mode_requested.connect(self._switch_to_island_mode)
        root_layout.addWidget(self.header)

        # ── 2. Center Workspace Splitter (Nav Rail | Workspace Stack | Telemetry Rail) ──
        body_widget = QWidget()
        body_layout = QHBoxLayout(body_widget)
        body_layout.setContentsMargins(0, 0, 0, 0)
        body_layout.setSpacing(0)

        # Left Navigation Rail
        self.nav_rail = NavigationRail(self)
        body_layout.addWidget(self.nav_rail)

        # Center Workspaces Stack
        self.stack = QStackedWidget(self)
        self.stack.setStyleSheet("background-color: #0B0F19;")

        self.ws_home = HomeWorkspace(self)
        self.ws_chat = ChatWorkspace(self)
        self.ws_computer_use = ComputerUseWorkspace(self)
        self.ws_browser = BrowserWorkspace(self)
        self.ws_coding = CodingWorkspace(self)
        self.ws_files = FilesWorkspace(self)
        self.ws_tasks = TasksWorkspace(self)
        self.ws_memory = MemoryWorkspace(self)
        self.ws_devices = DevicesWorkspace(self)
        self.ws_settings = SettingsWorkspace(self)

        workspaces = [
            (WorkspaceType.HOME.value, self.ws_home),
            (WorkspaceType.CHAT.value, self.ws_chat),
            (WorkspaceType.COMPUTER_USE.value, self.ws_computer_use),
            (WorkspaceType.BROWSER.value, self.ws_browser),
            (WorkspaceType.CODING.value, self.ws_coding),
            (WorkspaceType.FILES.value, self.ws_files),
            (WorkspaceType.TASKS.value, self.ws_tasks),
            (WorkspaceType.MEMORY.value, self.ws_memory),
            (WorkspaceType.DEVICES.value, self.ws_devices),
            (WorkspaceType.SETTINGS.value, self.ws_settings),
        ]

        for idx, (ws_name, widget) in enumerate(workspaces):
            self.stack.addWidget(widget)
            self._workspace_map[ws_name] = idx

        body_layout.addWidget(self.stack, 1)

        # Right Telemetry Rail
        self.telemetry_panel = TelemetryPanel(self)
        self.telemetry_panel.setFixedWidth(230)
        self.telemetry_panel.setStyleSheet("background-color: rgba(11, 15, 25, 0.90); border-left: 1px solid rgba(56, 189, 248, 0.15);")
        body_layout.addWidget(self.telemetry_panel)

        root_layout.addWidget(body_widget, 1)

        # ── 3. Bottom Status Bar ──────────────────────────────────────────────
        self.status_bar = StatusBar(self)
        root_layout.addWidget(self.status_bar)

        # ── 4. Toast Notification Overlay ─────────────────────────────────────
        self.toast_overlay = ToastOverlay(self)
        self.toast_overlay.setGeometry(max(0, self.width() - 320), max(0, self.height() - 340), min(300, self.width()), min(280, self.height()))
        self.toast_overlay.raise_()

    def _init_window_geometry(self):
        """
        Safely size and position window within the available screen workspace,
        accounting for Windows DPI scaling and taskbar boundaries so controls are never clipped.
        """
        screen = QApplication.primaryScreen()
        if screen:
            avail = screen.availableGeometry()
            target_w = min(1200, max(850, avail.width() - 40))
            target_h = min(780, max(560, avail.height() - 40))
            self.resize(target_w, target_h)
            x = avail.x() + max(0, (avail.width() - target_w) // 2)
            y = avail.y() + max(0, (avail.height() - target_h) // 2)
            self.move(x, y)
        else:
            self.resize(1100, 680)

    def _init_shortcuts(self):
        # Ctrl+K -> Command Palette
        sc_palette = QShortcut(QKeySequence("Ctrl+K"), self)
        sc_palette.activated.connect(self._open_command_palette)

        # Ctrl+Shift+M -> Toggle Island Mode
        sc_island = QShortcut(QKeySequence("Ctrl+Shift+M"), self)
        sc_island.activated.connect(self._toggle_island_mode)

        # Ctrl+Shift+F -> Toggle Fullscreen
        sc_fs = QShortcut(QKeySequence("Ctrl+Shift+F"), self)
        sc_fs.activated.connect(self._toggle_fullscreen)

        # Ctrl+Space -> Focus Chat / Voice command
        sc_chat = QShortcut(QKeySequence("Ctrl+Space"), self)
        sc_chat.activated.connect(lambda: workspace_router.navigate_to(WorkspaceType.CHAT))

        # Esc -> Immediate Interruption (stops speech, audio, and active tasks)
        sc_esc = QShortcut(QKeySequence(Qt.Key.Key_Escape), self)
        sc_esc.activated.connect(self._handle_escape_interrupt)

    def _handle_escape_interrupt(self):
        """Immediately interrupts speech playback and active tasks on Escape key."""
        try:
            bridge_client.send_interrupt()
        except Exception:
            pass

        # Reset local UI states
        state_store.handle_interrupt("User pressed Escape")
        notification_manager.show_warning("Interrupted", "Active speech & tasks cancelled (Esc)")

    def keyPressEvent(self, event):
        if event.key() == Qt.Key.Key_Escape:
            self._handle_escape_interrupt()
            event.accept()
            return
        super().keyPressEvent(event)

    def _toggle_maximize(self):
        if self.isMaximized():
            self.showNormal()
        else:
            self.showMaximized()
        if hasattr(self, "header") and self.header:
            self.header.set_maximized_state(self.isMaximized())

    def _toggle_fullscreen(self):
        if self.isFullScreen():
            self.showNormal()
        else:
            self.showFullScreen()
        if hasattr(self, "header") and self.header:
            self.header.set_maximized_state(self.isMaximized())

    def changeEvent(self, event):
        if event.type() == QEvent.Type.WindowStateChange:
            if hasattr(self, "header") and self.header:
                self.header.set_maximized_state(self.isMaximized())
        super().changeEvent(event)

    def _switch_to_island_mode(self):
        state_store.set_presentation_mode(PresentationMode.DYNAMIC_ISLAND_MINI)

    def _show_fullscreen_app(self):
        state_store.set_presentation_mode(PresentationMode.FULL_SCREEN)

    def _toggle_island_mode(self):
        state_store.toggle_presentation_mode()

    def _open_command_palette(self):
        palette = CommandPalette(self)
        palette.exec()

    def _on_workspace_changed(self, ws_name: str):
        if ws_name in self._workspace_map:
            self.stack.setCurrentIndex(self._workspace_map[ws_name])

    def _on_presentation_mode_changed(self, mode_str: str):
        if mode_str in (PresentationMode.DYNAMIC_ISLAND_MINI.value, PresentationMode.DYNAMIC_ISLAND_EXPANDED.value):
            self.hide()
            self.dynamic_island.show()
        else:
            self.dynamic_island.hide()
            self.show()
            self.activateWindow()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self.toast_overlay.setGeometry(max(0, self.width() - 320), max(0, self.height() - 340), min(300, self.width()), min(280, self.height()))

    def closeEvent(self, event):
        try:
            # Request backend shutdown when user clicks close or closes the UI
            bridge_client.send_shutdown_request()
        except Exception:
            pass
        self.dynamic_island.close()
        super().closeEvent(event)
