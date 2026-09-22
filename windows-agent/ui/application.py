"""
MAX UI — Application Bootstrap Subclass
Sets DPI awareness, app metadata, and initial theme.
"""

import sys
from PyQt6.QtCore import Qt
from PyQt6.QtGui import QFont
from PyQt6.QtWidgets import QApplication

from .themes.theme_manager import theme_manager


class MAXApplication(QApplication):
    def __init__(self, args=None):
        super().__init__(args or sys.argv)
        self.setApplicationName("MAX")
        self.setOrganizationName("MAX AI Ecosystem")
        self.setApplicationVersion("2.0.0")

        # Set Global High Quality Font
        font = QFont("Segoe UI Variable", 10)
        font.setStyleHint(QFont.StyleHint.SansSerif)
        self.setFont(font)

        # Apply default theme
        theme_manager.apply_theme()
