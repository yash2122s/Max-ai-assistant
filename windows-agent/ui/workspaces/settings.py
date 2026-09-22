"""
MAX UI — Settings Workspace
Configuration drawer for Voice, Gemini, Computer Use, Security, and Themes.
"""

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QFrame, QTabWidget, QLineEdit, QComboBox, QCheckBox, QPushButton
)

from ..themes.theme_manager import theme_manager
from ..services.state_store import state_store


class SettingsWorkspace(QWidget):
    """
    Renders user configuration tabs and dynamic appearance controls.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("SettingsWorkspace")
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 12, 16, 16)
        layout.setSpacing(12)

        # Header
        hdr_row = QHBoxLayout()
        title_lbl = QLabel("MAX AGENT SETTINGS")
        title_lbl.setStyleSheet("font-weight: bold; font-size: 13px; color: #38BDF8; letter-spacing: 1px;")
        hdr_row.addWidget(title_lbl)
        hdr_row.addStretch()

        save_btn = QPushButton("Save Settings")
        save_btn.setObjectName("PrimaryButton")
        save_btn.setFixedSize(110, 28)
        save_btn.clicked.connect(self._on_save)
        hdr_row.addWidget(save_btn)
        layout.addLayout(hdr_row)

        # Tabs
        self.tabs = QTabWidget()
        self.tabs.setStyleSheet("""
            QTabWidget::pane {
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 8px;
                background-color: rgba(17, 24, 39, 0.7);
            }
            QTabBar::tab {
                background: #111827;
                color: #9CA3AF;
                padding: 6px 14px;
                margin-right: 4px;
                border-top-left-radius: 6px;
                border-top-right-radius: 6px;
                font-weight: 500;
            }
            QTabBar::tab:selected {
                background: rgba(56, 189, 248, 0.2);
                color: #38BDF8;
                border-bottom: 2px solid #38BDF8;
            }
        """)

        # ── Tab 1: Appearance ────────────────────────────────────────────────
        app_tab = QWidget()
        app_layout = QVBoxLayout(app_tab)
        app_layout.setContentsMargins(14, 14, 14, 14)
        app_layout.setSpacing(12)

        theme_lbl = QLabel("Active Color Theme:")
        theme_lbl.setStyleSheet("font-weight: bold; color: #F9FAFB;")
        app_layout.addWidget(theme_lbl)

        theme_box = QHBoxLayout()
        self.theme_combo = QComboBox()
        self.theme_combo.addItems(["Dark Slate & Cyan (Default)", "Cyberpunk Neon Violet"])
        self.theme_combo.setCurrentIndex(0 if theme_manager.current_theme == "dark" else 1)
        self.theme_combo.currentIndexChanged.connect(self._on_theme_changed)
        theme_box.addWidget(self.theme_combo)
        theme_box.addStretch()
        app_layout.addLayout(theme_box)

        self.transparency_check = QCheckBox("Enable Glassmorphism / Mica Transparency")
        self.transparency_check.setChecked(True)
        app_layout.addWidget(self.transparency_check)

        self.particles_check = QCheckBox("Enable GPU Energy Particles Atmosphere")
        self.particles_check.setChecked(True)
        app_layout.addWidget(self.particles_check)

        app_layout.addStretch()
        self.tabs.addTab(app_tab, "🎨 Appearance")

        # ── Tab 2: Voice & Audio ─────────────────────────────────────────────
        voice_tab = QWidget()
        v_layout = QVBoxLayout(voice_tab)
        v_layout.setContentsMargins(14, 14, 14, 14)
        v_layout.setSpacing(12)

        v_layout.addWidget(QLabel("Wake Word Engine: Vosk + Gemini Live"))
        
        self.auto_listen_check = QCheckBox("Continuous Conversation Mode (Hands-free)")
        self.auto_listen_check.setChecked(True)
        v_layout.addWidget(self.auto_listen_check)

        v_layout.addStretch()
        self.tabs.addTab(voice_tab, "🎙️ Voice & Audio")

        # ── Tab 3: Computer Use & Security ───────────────────────────────────
        sec_tab = QWidget()
        s_layout = QVBoxLayout(sec_tab)
        s_layout.setContentsMargins(14, 14, 14, 14)
        s_layout.setSpacing(12)

        self.confirm_check = QCheckBox("Enforce Human Confirmation on Destructive Actions (Recommended)")
        self.confirm_check.setChecked(True)
        s_layout.addWidget(self.confirm_check)

        self.screen_excl_check = QCheckBox("Exclude MAX UI from Screen Recordings (WDA_EXCLUDEFROMCAPTURE)")
        self.screen_excl_check.setChecked(True)
        s_layout.addWidget(self.screen_excl_check)

        s_layout.addStretch()
        self.tabs.addTab(sec_tab, "🛡️ Security & Safety")

        layout.addWidget(self.tabs)

    def _on_theme_changed(self, index: int):
        theme_name = "dark" if index == 0 else "neon"
        theme_manager.set_theme(theme_name)

    def _on_save(self):
        state_store.state.push_notification("Settings Saved", "Preferences updated successfully.", "success")
