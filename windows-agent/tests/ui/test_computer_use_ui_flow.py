"""
Tests for Computer Use perception and action overlay flow.
"""

import os
import pytest
from PyQt6.QtWidgets import QApplication

from ui.services.ui_bridge_client import bridge_client
from ui.services.state_store import state_store
from ui.models.computer_use_models import ActionPhase


@pytest.fixture(scope="session")
def qapp():
    os.environ["QT_QPA_PLATFORM"] = "offscreen"
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    return app


def test_computer_use_perception_flow(qapp):
    bridge_client._on_event_received({
        "type": "computer_use",
        "payload": {
            "phase": "RESOLVE",
            "current_application": "Google Chrome",
            "action_type": "click",
            "coordinates": [450, 320],
            "status": "Resolved search input box"
        }
    })

    cu = state_store.state.computer_use
    assert cu.phase == ActionPhase.RESOLVE
    assert cu.current_application == "Google Chrome"
    assert cu.target_coordinates == (450, 320)
    assert cu.status_message == "Resolved search input box"


def test_computer_use_observed_with_boxes_and_screenshot(qapp, tmp_path):
    # Create a dummy image
    test_img = tmp_path / "test_shot.png"
    from PIL import Image
    im = Image.new("RGB", (640, 480), color=(10, 20, 30))
    im.save(str(test_img))

    bridge_client._on_event_received({
        "type": "computer_use",
        "payload": {
            "event_name": "COMPUTER_USE_OBSERVED",
            "active_window": "Notepad",
            "active_process": "notepad.exe",
            "screenshot_path": str(test_img),
            "elements": [
                {"ref_id": "@ref_01", "label": "File menu", "x": 10, "y": 10, "width": 40, "height": 20, "source": "uia"},
                {"ref_id": "@ref_02", "label": "Edit menu", "x": 55, "y": 10, "width": 40, "height": 20, "source": "uia"},
            ],
            "status": "Observed 2 elements in 'Notepad'"
        }
    })

    cu = state_store.state.computer_use
    assert cu.phase == ActionPhase.OBSERVE
    assert cu.current_application == "Notepad"
    assert len(cu.all_boxes) == 2
    assert cu.all_boxes[0].ref_id == "@ref_01"
    assert cu.all_boxes[0].label == "File menu"
    assert cu.screenshot_bytes is not None
    assert len(cu.screenshot_bytes) > 0


def test_computer_use_inferred_phases_never_default_to_act(qapp):
    # An event with no 'phase' key and event_name 'COMPUTER_USE_VERIFYING' must infer VERIFY, not ACT
    bridge_client._on_event_received({
        "type": "computer_use",
        "payload": {
            "event_name": "COMPUTER_USE_VERIFYING",
            "status": "Re-observing and verifying"
        }
    })
    cu = state_store.state.computer_use
    assert cu.phase == ActionPhase.VERIFY

    # An event with no phase key and no event_name must default to IDLE, not ACT
    bridge_client._on_event_received({
        "type": "computer_use",
        "payload": {}
    })
    cu = state_store.state.computer_use
    assert cu.phase == ActionPhase.IDLE


def test_computer_use_outbound_requests(qapp):
    sent_messages = []
    bridge_client.worker.send_message_threadsafe = lambda message: sent_messages.append(message)

    bridge_client.request_observe_screen(include_ocr=True)
    assert len(sent_messages) == 1
    assert sent_messages[0]["type"] == "computer_use_observe"

    bridge_client.request_resolve_target("Search button")
    assert len(sent_messages) == 2
    assert sent_messages[1]["type"] == "computer_use_resolve"
    assert sent_messages[1]["query"] == "Search button"

    bridge_client.request_computer_use_step("click", target_query="Start")
    assert len(sent_messages) == 3
    assert sent_messages[2]["type"] == "computer_use_step"
    assert sent_messages[2]["action_type"] == "click"
    assert sent_messages[2]["target_query"] == "Start"
