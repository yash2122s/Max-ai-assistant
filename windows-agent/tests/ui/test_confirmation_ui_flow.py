"""
Tests for Human-in-the-loop confirmation UI approval/rejection lifecycle.
"""

import os
import pytest
from PyQt6.QtWidgets import QApplication

from ui.services.ui_bridge_client import bridge_client
from ui.services.state_store import state_store
from ui.models.ui_state import AgentState


@pytest.fixture(scope="session")
def qapp():
    os.environ["QT_QPA_PLATFORM"] = "offscreen"
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    return app


def test_confirmation_pipeline(qapp):
    # Receive confirmation event
    bridge_client._on_event_received({
        "type": "confirmation_required",
        "payload": {
            "ticket_id": "ticket_123",
            "action_name": "Delete Directory",
            "description": "Delete 10 temporary files?",
            "target": "/tmp/test",
            "expires_in_seconds": 60.0
        }
    })

    assert state_store.state.pending_confirmation is not None
    assert state_store.state.pending_confirmation.ticket_id == "ticket_123"
    assert state_store.state.agent_state == AgentState.WAITING_CONFIRMATION

    # Simulate clearing
    state_store.clear_confirmation()
    assert state_store.state.pending_confirmation is None
    assert state_store.state.agent_state == AgentState.IDLE
