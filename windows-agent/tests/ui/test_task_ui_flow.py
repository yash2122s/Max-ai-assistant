"""
Tests for Task lifecycle UI flow (Start -> Progress -> Complete).
"""

import os
import pytest
from PyQt6.QtWidgets import QApplication

from ui.services.ui_bridge_client import bridge_client
from ui.services.state_store import state_store
from ui.models.ui_state import AgentState
from ui.models.task_models import TaskStatus


@pytest.fixture(scope="session")
def qapp():
    os.environ["QT_QPA_PLATFORM"] = "offscreen"
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    return app


def test_task_execution_lifecycle(qapp):
    # 1. Task Start
    bridge_client._on_event_received({
        "type": "task_started",
        "payload": {
            "task_id": "tsk_42",
            "title": "Analyze Codebase",
            "tool": "ripgrep",
            "step": 1,
            "total_steps": 3,
            "progress": 0.33
        }
    })

    assert state_store.state.active_task is not None
    assert state_store.state.active_task.task_id == "tsk_42"
    assert state_store.state.agent_state == AgentState.EXECUTING_TASK

    # 2. Task Complete
    bridge_client._on_event_received({
        "type": "task_completed",
        "payload": {"task_id": "tsk_42"}
    })

    assert state_store.state.active_task is None
    assert state_store.state.agent_state == AgentState.IDLE
