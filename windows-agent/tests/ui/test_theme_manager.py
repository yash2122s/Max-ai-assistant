"""
Tests for ThemeManager stylesheet application and switching.
"""

import os
import pytest
from PyQt6.QtWidgets import QApplication

from ui.themes.theme_manager import ThemeManager


@pytest.fixture(scope="session")
def qapp():
    os.environ["QT_QPA_PLATFORM"] = "offscreen"
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    return app


def test_theme_switching(qapp):
    tm = ThemeManager()
    assert tm.current_theme == "dark"
    assert "bg_main" in tm.palette

    emissions = []
    tm.theme_changed.connect(lambda t: emissions.append(t))

    tm.set_theme("neon")
    assert tm.current_theme == "neon"
    assert emissions[-1] == "neon"

    tm.toggle_theme()
    assert tm.current_theme == "dark"
