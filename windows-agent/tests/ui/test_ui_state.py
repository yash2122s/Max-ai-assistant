"""
Tests for UiState model and signal emissions.
"""

import os
import pytest
from PyQt6.QtWidgets import QApplication

from ui.models.ui_state import UiState, PresentationMode, WorkspaceType, VoiceState, AgentState
from ui.models.chat_models import ChatMessage, MessageRole
from ui.models.task_models import TaskItem, TaskStatus, ConfirmationRequest


@pytest.fixture(scope="session")
def qapp():
    os.environ["QT_QPA_PLATFORM"] = "offscreen"
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    return app


def test_ui_state_initial_values(qapp):
    state = UiState()
    assert state.presentation_mode == PresentationMode.FULL_SCREEN
    assert state.workspace == WorkspaceType.HOME
    assert state.voice_state == VoiceState.IDLE
    assert state.agent_state == AgentState.IDLE
    assert state.audio_level == 0.0
    assert not state.is_connected


def test_ui_state_signals(qapp):
    state = UiState()
    emissions = {}

    state.workspace_changed.connect(lambda ws: emissions.update({"workspace": ws}))
    state.voice_state_changed.connect(lambda vs: emissions.update({"voice": vs}))
    state.audio_level_changed.connect(lambda al: emissions.update({"audio": al}))
    state.presentation_mode_changed.connect(lambda pm: emissions.update({"mode": pm}))

    state.set_workspace(WorkspaceType.CHAT)
    assert emissions.get("workspace") == "chat"

    state.set_voice_state(VoiceState.LISTENING)
    assert emissions.get("voice") == "listening"

    state.set_audio_level(0.75)
    assert emissions.get("audio") == 0.75

    state.set_presentation_mode(PresentationMode.DYNAMIC_ISLAND_MINI)
    assert emissions.get("mode") == "dynamic_island_mini"


def test_chat_message_flow(qapp):
    state = UiState()
    messages = []
    state.chat_message_added.connect(lambda msg: messages.append(msg))

    msg = ChatMessage(id="m1", role=MessageRole.USER, content="Hello MAX")
    state.add_chat_message(msg)

    assert len(messages) == 1
    assert messages[0].content == "Hello MAX"
    assert len(state.chat_messages) == 1

    state.clear_chat()
    assert len(state.chat_messages) == 0


def test_task_state_flow(qapp):
    state = UiState()
    active_tasks = []
    state.active_task_updated.connect(lambda t: active_tasks.append(t))

    task = TaskItem(task_id="t1", title="Test Task", progress=0.5)
    state.set_active_task(task)

    assert len(active_tasks) == 1
    assert active_tasks[0].task_id == "t1"
    assert len(state.tasks) == 1

    state.set_active_task(None)
    assert active_tasks[-1] is None


def test_confirmation_state_flow(qapp):
    state = UiState()
    conf_events = []
    state.confirmation_requested.connect(lambda c: conf_events.append(c))

    req = ConfirmationRequest(ticket_id="c1", action_name="Delete", description="Delete files?")
    state.set_pending_confirmation(req)

    assert len(conf_events) == 1
    assert conf_events[0].ticket_id == "c1"
    assert not req.is_expired

    state.clear_confirmation()
    assert state.pending_confirmation is None
