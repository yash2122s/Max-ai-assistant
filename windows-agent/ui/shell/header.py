"""
MAX UI — Custom Window Header Bar
Frameless window title bar with branding, status dot, and window controls.
"""

from PyQt6.QtCore import Qt, QPoint, pyqtSignal
from PyQt6.QtWidgets import (
    QWidget, QHBoxLayout, QLabel, QPushButton, QFrame
)

from ..themes.theme_manager import theme_manager
from ..services.state_store import state_store


class HeaderBar(QFrame):
    """
    Custom frameless titlebar supporting window dragging, status dot,
    theme toggle, dynamic island minimize, and window control buttons.
    """

    minimize_requested = pyqtSignal()
    maximize_restore_requested = pyqtSignal()
    close_requested = pyqtSignal()
    island_mode_requested = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("HeaderBar")
        self.setFixedHeight(42)
        self.setStyleSheet("""
            QFrame#HeaderBar {
                background-color: rgba(11, 15, 25, 0.95);
                border-bottom: 1px solid rgba(56, 189, 248, 0.2);
            }
            QPushButton#HeaderMinBtn, QPushButton#HeaderMaxBtn {
                background: transparent;
                border: none;
                color: #D1D5DB;
                font-size: 13px;
                font-weight: bold;
                border-radius: 5px;
            }
            QPushButton#HeaderMinBtn:hover, QPushButton#HeaderMaxBtn:hover {
                color: #FFFFFF;
                background-color: rgba(255, 255, 255, 0.12);
            }
            QPushButton#HeaderMinBtn:pressed, QPushButton#HeaderMaxBtn:pressed {
                background-color: rgba(255, 255, 255, 0.20);
            }
            QPushButton#HeaderCloseBtn {
                background: transparent;
                border: none;
                color: #D1D5DB;
                font-size: 13px;
                font-weight: bold;
                border-radius: 5px;
            }
            QPushButton#HeaderCloseBtn:hover {
                color: #FFFFFF;
                background-color: #EF4444;
            }
            QPushButton#HeaderCloseBtn:pressed {
                background-color: #DC2626;
            }
        """)
        self._drag_pos = QPoint()
        self._init_ui()

        state_store.state.connection_state_changed.connect(self._on_connection_changed)

    def _init_ui(self):
        layout = QHBoxLayout(self)
        layout.setContentsMargins(16, 0, 8, 0)
        layout.setSpacing(8)

        # Brand Icon & Title
        self.logo_lbl = QLabel("MAX")
        self.logo_lbl.setStyleSheet("font-weight: 800; font-size: 15px; color: #38BDF8; letter-spacing: 2px;")
        layout.addWidget(self.logo_lbl)

        # Connection Status Dot & Label
        self.status_dot = QLabel("●")
        self.status_dot.setStyleSheet("color: #F59E0B; font-size: 10px;")
        layout.addWidget(self.status_dot)

        self.status_label = QLabel("CONNECTING...")
        self.status_label.setStyleSheet("color: #9CA3AF; font-size: 11px; font-weight: bold; letter-spacing: 0.5px;")
        layout.addWidget(self.status_label)

        layout.addStretch(1)

        # Dynamic Island Switch Button
        self.island_btn = QPushButton("🔲 Island Mode")
        self.island_btn.setToolTip("Switch to Mini Dynamic Island (Ctrl+Shift+M)")
        self.island_btn.setStyleSheet("""
            QPushButton {
                background-color: rgba(56, 189, 248, 0.12);
                color: #38BDF8;
                border: 1px solid rgba(56, 189, 248, 0.3);
                border-radius: 6px;
                padding: 4px 10px;
                font-size: 11px;
                font-weight: 600;
            }
            QPushButton:hover {
                background-color: rgba(56, 189, 248, 0.25);
            }
        """)
        self.island_btn.clicked.connect(self.island_mode_requested.emit)
        layout.addWidget(self.island_btn)

        # Theme Toggle Button
        self.theme_btn = QPushButton("🌓")
        self.theme_btn.setToolTip("Toggle Dark / Neon Theme")
        self.theme_btn.setFixedSize(30, 30)
        self.theme_btn.setStyleSheet("""
            QPushButton {
                background: transparent;
                border: 1px solid rgba(56, 189, 248, 0.2);
                border-radius: 6px;
                color: #F9FAFB;
                font-size: 13px;
            }
            QPushButton:hover {
                background: rgba(56, 189, 248, 0.15);
            }
        """)
        self.theme_btn.clicked.connect(theme_manager.toggle_theme)
        layout.addWidget(self.theme_btn)

        # Vertical Divider Separator
        sep = QFrame()
        sep.setFrameShape(QFrame.Shape.VLine)
        sep.setFrameShadow(QFrame.Shadow.Plain)
        sep.setFixedSize(1, 18)
        sep.setStyleSheet("background-color: rgba(56, 189, 248, 0.25); border: none;")
        layout.addWidget(sep)

        # Dedicated Window Controls Container (Min, Max/Restore, Close)
        self.controls_widget = QWidget()
        self.controls_widget.setObjectName("WindowControlsContainer")
        controls_layout = QHBoxLayout(self.controls_widget)
        controls_layout.setContentsMargins(0, 0, 0, 0)
        controls_layout.setSpacing(3)

        self.min_btn = QPushButton("—")
        self.min_btn.setObjectName("HeaderMinBtn")
        self.min_btn.setToolTip("Minimize")
        self.min_btn.setFixedSize(38, 30)
        self.min_btn.clicked.connect(self.minimize_requested.emit)
        controls_layout.addWidget(self.min_btn)

        self.max_btn = QPushButton("□")
        self.max_btn.setObjectName("HeaderMaxBtn")
        self.max_btn.setToolTip("Maximize")
        self.max_btn.setFixedSize(38, 30)
        self.max_btn.clicked.connect(self.maximize_restore_requested.emit)
        controls_layout.addWidget(self.max_btn)

        self.close_btn = QPushButton("✕")
        self.close_btn.setObjectName("HeaderCloseBtn")
        self.close_btn.setToolTip("Close")
        self.close_btn.setFixedSize(38, 30)
        self.close_btn.clicked.connect(self.close_requested.emit)
        controls_layout.addWidget(self.close_btn)

        layout.addWidget(self.controls_widget)

    def set_maximized_state(self, is_maximized: bool):
        """Update maximize button glyph and tooltip according to window state."""
        if is_maximized:
            self.max_btn.setText("❐")
            self.max_btn.setToolTip("Restore")
        else:
            self.max_btn.setText("□")
            self.max_btn.setToolTip("Maximize")

    def _on_connection_changed(self, connected: bool):
        if connected:
            self.status_dot.setStyleSheet("color: #10B981; font-size: 10px;")
            self.status_label.setText("ONLINE")
            self.status_label.setStyleSheet("color: #10B981; font-size: 10.5px; font-weight: bold;")
        else:
            self.status_dot.setStyleSheet("color: #F59E0B; font-size: 10px;")
            self.status_label.setText("OFFLINE")
            self.status_label.setStyleSheet("color: #F59E0B; font-size: 10.5px; font-weight: bold;")

    # Window Dragging & Double-click support
    def mouseDoubleClickEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self.maximize_restore_requested.emit()
            event.accept()
        else:
            super().mouseDoubleClickEvent(event)

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            win = self.window()
            if win:
                self._drag_pos = event.globalPosition().toPoint() - win.frameGeometry().topLeft()
                event.accept()

    def mouseMoveEvent(self, event):
        if event.buttons() == Qt.MouseButton.LeftButton:
            win = self.window()
            if win and not win.isMaximized():
                win.move(event.globalPosition().toPoint() - self._drag_pos)
                event.accept()

