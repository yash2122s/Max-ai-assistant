"""
Tests for DynamicCard, ToolCard, TaskCard, and ConfirmationCard components.
"""

import os
import pytest
from PyQt6.QtWidgets import QApplication

from ui.components.dynamic_card import DynamicCard
from ui.components.tool_card import ToolCard
from ui.components.task_card import TaskCard
from ui.components.confirmation_card import ConfirmationCard
from ui.models.task_models import TaskItem, TaskStatus, ConfirmationRequest


@pytest.fixture(scope="session")
def qapp():
    os.environ["QT_QPA_PLATFORM"] = "offscreen"
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    return app


def test_dynamic_card_creation(qapp):
    card = DynamicCard(title="Test Card", subtitle="Test Subtitle", icon="⚡", badge="ACTIVE")
    assert card.title == "Test Card"
    card.set_badge("DONE", "#10B981")


def test_tool_card_creation(qapp):
    card = ToolCard(
        tool_name="browser_search",
        status="running",
        input_data={"query": "python"},
    )
    assert card.tool_name == "browser_search"
    card.set_result("completed", {"results": 5})
    assert card.status == "completed"


def test_task_card_progress(qapp):
    task = TaskItem(task_id="t10", title="Run Test Suite", status=TaskStatus.RUNNING, step=1, total_steps=4, progress=0.25)
    card = TaskCard(task)
    assert card.progress_bar.value() == 25

    card.update_progress(0.75, 3, 4)
    assert card.progress_bar.value() == 75


def test_confirmation_card_actions(qapp):
    req = ConfirmationRequest(ticket_id="c99", action_name="Format Disk", description="Are you sure?")
    card = ConfirmationCard(req)
    assert card.time_lbl.text() != ""
    assert card.approve_btn is not None
    assert card.reject_btn is not None
