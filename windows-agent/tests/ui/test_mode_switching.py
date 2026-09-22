"""
Tests for Dual-Mode switching between Full-Screen and Dynamic Island.
"""

import os
import pytest
from PyQt6.QtWidgets import QApplication

from ui.shell.main_window import MainWindow
from ui.services.state_store import state_store
from ui.models.ui_state import PresentationMode, WorkspaceType


@pytest.fixture(scope="session")
def qapp():
    os.environ["QT_QPA_PLATFORM"] = "offscreen"
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    return app


def test_main_window_creation(qapp):
    win = MainWindow()
    assert win.windowTitle() == "MAX Agent"
    assert win.dynamic_island is not None

    # Test mode toggle
    state_store.set_presentation_mode(PresentationMode.DYNAMIC_ISLAND_MINI)
    assert state_store.state.presentation_mode == PresentationMode.DYNAMIC_ISLAND_MINI

    state_store.set_presentation_mode(PresentationMode.FULL_SCREEN)
    assert state_store.state.presentation_mode == PresentationMode.FULL_SCREEN

    win.close()


def test_dynamic_island_expansion(qapp):
    win = MainWindow()
    island = win.dynamic_island

    assert not island._is_expanded
    island.expand_island()
    assert island._is_expanded

    island.collapse_island()
    assert not island._is_expanded

    win.close()


def test_header_window_controls(qapp):
    win = MainWindow()
    win.show()

    # Verify buttons exist and are configured
    assert hasattr(win.header, "min_btn")
    assert hasattr(win.header, "max_btn")
    assert hasattr(win.header, "close_btn")

    assert win.header.min_btn.isVisible()
    assert win.header.max_btn.isVisible()
    assert win.header.close_btn.isVisible()

    # Verify initial maximize state and toggle
    assert win.header.max_btn.text() == "□"
    win._toggle_maximize()
    assert win.header.max_btn.text() == "❐"
    win._toggle_maximize()
    assert win.header.max_btn.text() == "□"

    # Verify minimize signal
    min_received = []
    win.header.minimize_requested.connect(lambda: min_received.append(True))
    win.header.min_btn.click()
    assert min_received == [True]

    # Verify close signal
    close_received = []
    win.close()


def test_escape_key_interrupt(qapp, monkeypatch):
    """Verify that pressing Escape triggers interruption via bridge_client and state_store."""
    win = MainWindow()
    win.show()

    interrupt_called = []
    from ui.services.ui_bridge_client import bridge_client
    monkeypatch.setattr(bridge_client, "send_interrupt", lambda: interrupt_called.append(True))

    from PyQt6.QtGui import QKeyEvent
    from PyQt6.QtCore import QEvent, Qt
    esc_event = QKeyEvent(QEvent.Type.KeyPress, Qt.Key.Key_Escape, Qt.KeyboardModifier.NoModifier)
    win.keyPressEvent(esc_event)

    assert len(interrupt_called) == 1
    win.close()


def test_close_event_shutdown_request(qapp, monkeypatch):
    """Verify that closeEvent sends shutdown request to backend."""
    win = MainWindow()
    win.show()

    shutdown_called = []
    from ui.services.ui_bridge_client import bridge_client
    monkeypatch.setattr(bridge_client, "send_shutdown_request", lambda: shutdown_called.append(True))

    from PyQt6.QtGui import QCloseEvent
    close_evt = QCloseEvent()
    win.closeEvent(close_evt)

    assert len(shutdown_called) == 1

