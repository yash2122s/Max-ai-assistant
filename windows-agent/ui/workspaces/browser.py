"""
MAX UI — Browser Automation Workspace
Monitors automated browser navigation, search extraction, and active URLs.
"""

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit, QFrame, QTextBrowser, QPushButton
)

from ..services.state_store import state_store


class BrowserWorkspace(QWidget):
    """
    Renders browser automation view with active URL bar, navigation status,
    and extracted page data summary.
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("BrowserWorkspace")
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 12, 16, 16)
        layout.setSpacing(12)

        # ── 1. Top URL / Navigation Bar ──────────────────────────────────────
        url_frame = QFrame()
        url_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.85);
                border: 1px solid rgba(56, 189, 248, 0.25);
                border-radius: 10px;
                padding: 4px 8px;
            }
        """)
        url_layout = QHBoxLayout(url_frame)
        url_layout.setContentsMargins(4, 4, 4, 4)
        url_layout.setSpacing(8)

        nav_icon = QLabel("🌐")
        nav_icon.setStyleSheet("font-size: 14px;")
        url_layout.addWidget(nav_icon)

        self.url_bar = QLineEdit("https://www.google.com")
        self.url_bar.setReadOnly(True)
        self.url_bar.setStyleSheet("""
            QLineEdit {
                background: transparent;
                border: none;
                color: #38BDF8;
                font-family: 'Segoe UI', sans-serif;
                font-size: 13px;
                font-weight: 500;
            }
        """)
        url_layout.addWidget(self.url_bar)

        self.status_badge = QLabel("READY")
        self.status_badge.setStyleSheet("""
            background-color: rgba(16, 185, 129, 0.15);
            color: #10B981;
            border: 1px solid #10B981;
            border-radius: 6px;
            padding: 2px 8px;
            font-size: 10.5px;
            font-weight: bold;
        """)
        url_layout.addWidget(self.status_badge)

        layout.addWidget(url_frame)

        # ── 2. Content & Web Extraction Inspector ────────────────────────────
        content_frame = QFrame()
        content_frame.setStyleSheet("""
            QFrame {
                background-color: rgba(17, 24, 39, 0.7);
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 12px;
                padding: 12px;
            }
        """)
        c_layout = QVBoxLayout(content_frame)
        c_layout.setContentsMargins(10, 10, 10, 10)
        c_layout.setSpacing(8)

        hdr_lbl = QLabel("EXTRACTED CONTENT & PAGE SUMMARY")
        hdr_lbl.setStyleSheet("color: #38BDF8; font-weight: bold; font-size: 11px; letter-spacing: 1px;")
        c_layout.addWidget(hdr_lbl)

        self.extracted_browser = QTextBrowser()
        self.extracted_browser.setOpenExternalLinks(True)
        self.extracted_browser.setStyleSheet("""
            QTextBrowser {
                background-color: #0B0F19;
                border: 1px solid rgba(56, 189, 248, 0.15);
                border-radius: 8px;
                color: #F9FAFB;
                font-size: 12.5px;
                padding: 10px;
            }
        """)
        self.extracted_browser.setHtml("""
            <h3 style='color: #38BDF8;'>Browser Automation Standby</h3>
            <p style='color: #9CA3AF;'>MAX is ready to navigate, search, fill forms, and summarize web content via Google Chrome / Edge automation.</p>
            <ul style='color: #D1D5DB;'>
                <li>Natural web searching & deep content scraping</li>
                <li>Interactive form filling & structured extraction</li>
                <li>Video transcript processing</li>
            </ul>
        """)
        c_layout.addWidget(self.extracted_browser)

        layout.addWidget(content_frame)
