"""
MAX UI — Dynamic Theme Manager
Manages application-wide stylesheet applications without restarts.
"""

from typing import Dict, Any, Optional
from PyQt6.QtCore import QObject, pyqtSignal
from PyQt6.QtWidgets import QApplication

from .dark import DARK_THEME_PALETTE, DARK_STYLESHEET
from .neon import NEON_THEME_PALETTE, NEON_STYLESHEET


class ThemeManager(QObject):
    theme_changed = pyqtSignal(str)  # emits theme name

    def __init__(self):
        super().__init__()
        self._current_theme_name: str = "dark"
        self._themes: Dict[str, Dict[str, Any]] = {
            "dark": {"palette": DARK_THEME_PALETTE, "stylesheet": DARK_STYLESHEET},
            "neon": {"palette": NEON_THEME_PALETTE, "stylesheet": NEON_STYLESHEET},
        }

    @property
    def current_theme(self) -> str:
        return self._current_theme_name

    @property
    def palette(self) -> Dict[str, Any]:
        return self._themes.get(self._current_theme_name, self._themes["dark"])["palette"]

    @property
    def stylesheet(self) -> str:
        return self._themes.get(self._current_theme_name, self._themes["dark"])["stylesheet"]

    def set_theme(self, theme_name: str) -> None:
        if theme_name in self._themes and theme_name != self._current_theme_name:
            self._current_theme_name = theme_name
            self.apply_theme()
            self.theme_changed.emit(theme_name)

    def toggle_theme(self) -> str:
        new_theme = "neon" if self._current_theme_name == "dark" else "dark"
        self.set_theme(new_theme)
        return new_theme

    def apply_theme(self) -> None:
        app = QApplication.instance()
        if app is not None:
            app.setStyleSheet(self.stylesheet)

    def get_color(self, key: str, fallback: str = "#38BDF8") -> str:
        return self.palette.get(key, fallback)


# Global singleton
theme_manager = ThemeManager()
