"""
Integration tests for backend event propagation into PyQt6 workspaces.
"""

import os
import pytest
from PyQt6.QtWidgets import QApplication

from ui.shell.main_window import MainWindow
from ui.services.ui_bridge_client import bridge_client
from ui.services.state_store import state_store
from ui.models.ui_state import VoiceState, WorkspaceType


@pytest.fixture(scope="session")
def qapp():
    os.environ["QT_QPA_PLATFORM"] = "offscreen"
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    return app


def test_event_propagation_to_widgets(qapp):
    win = MainWindow()

    # 1. Voice Event
    bridge_client._on_event_received({
        "type": "voice_state",
        "payload": {"state": "SPEAKING", "audio_level": 0.6}
    })
    assert state_store.state.voice_state == VoiceState.SPEAKING
    assert win.status_bar.voice_tag.text() == "🗣️ SPEAKING"

    # 2. Navigation
    state_store.set_workspace(WorkspaceType.COMPUTER_USE)
    assert win.stack.currentWidget() == win.ws_computer_use

    win.close()
