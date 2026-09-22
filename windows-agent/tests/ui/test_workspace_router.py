"""
Tests for WorkspaceRouter navigation and history.
"""

import os
import pytest
from PyQt6.QtWidgets import QApplication

from ui.services.workspace_router import WorkspaceRouter
from ui.services.state_store import StateStore
from ui.models.ui_state import WorkspaceType, UiState


@pytest.fixture(scope="session")
def qapp():
    os.environ["QT_QPA_PLATFORM"] = "offscreen"
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    return app


def test_router_navigation(qapp):
    state = UiState()
    store = StateStore(state)
    router = WorkspaceRouter()

    events = []
    router.workspace_navigated.connect(lambda ws: events.append(ws))

    router.navigate_to(WorkspaceType.CHAT)
    assert events[-1] == "chat"

    router.navigate_to(WorkspaceType.CODING)
    assert events[-1] == "coding"
    assert router.can_go_back()

    prev = router.go_back()
    assert prev == WorkspaceType.CHAT

    forward = router.go_forward()
    assert forward == WorkspaceType.CODING
