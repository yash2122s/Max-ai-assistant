"""
Tests for UiBridgeClient event dispatching and WebSocket packet decoding.
"""

import os
import pytest
from PyQt6.QtWidgets import QApplication

from ui.services.ui_bridge_client import UiBridgeClient
from ui.services.state_store import state_store
from ui.models.ui_state import VoiceState


@pytest.fixture(scope="session")
def qapp():
    os.environ["QT_QPA_PLATFORM"] = "offscreen"
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    return app


def test_bridge_event_dispatching(qapp):
    client = UiBridgeClient(uri="ws://127.0.0.1:9002")

    # Simulate incoming voice state event
    client._on_event_received({
        "type": "voice_state",
        "payload": {"state": "LISTENING", "audio_level": 0.8}
    })
    assert state_store.state.voice_state == VoiceState.LISTENING
    assert state_store.state.audio_level == 0.8

    # Simulate transcript event
    client._on_event_received({
        "type": "transcript_user",
        "payload": {"text": "Open Chrome"}
    })
    assert state_store.state.user_transcript == "Open Chrome"

    # Simulate task event
    client._on_event_received({
        "type": "task_started",
        "payload": {
            "task_id": "t99",
            "title": "Open Application",
            "step": 1,
            "total_steps": 2,
            "progress": 0.5
        }
    })
    assert state_store.state.active_task is not None
    assert state_store.state.active_task.title == "Open Application"
