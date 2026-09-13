"""
Tests for MAX UI-Originated Confirmation Gate.
Verifies that the model cannot self-authorize, UI receives notifications,
and actions only execute upon out-of-band user_ui_approve().
"""

import pytest
from core.confirmation_gate import HumanConfirmationGate


def test_ui_confirmation_flow():
    gate = HumanConfirmationGate(expiry_seconds=10.0)
    ui_notifications = []
    executed = False

    gate.register_ui_listener(lambda payload: ui_notifications.append(payload))

    def dangerous_action():
        nonlocal executed
        executed = True
        return "format_completed"

    # 1. Action is requested
    model_res = gate.request_confirmation(
        action_name="format_partition",
        description="Format D: volume",
        target="D:",
        action_fn=dangerous_action
    )

    # Verify model response contains ZERO authorization tokens or secrets
    assert model_res["status"] == "WAITING_CONFIRMATION"
    assert "token" not in model_res
    assert "secret" not in model_res
    ticket_id = model_res["ticket_id"]
    assert executed is False

    # Verify desktop UI received the notification event
    assert len(ui_notifications) == 1
    assert ui_notifications[0]["ticket_id"] == ticket_id

    # 2. User clicks CONFIRM on the UI banner
    approval_res = gate.user_ui_approve(ticket_id)
    assert approval_res["status"] == "success"
    assert executed is True

    # 3. Ticket is consumed and cannot be replayed
    replay_res = gate.user_ui_approve(ticket_id)
    assert replay_res["status"] == "error"
    assert replay_res["error_code"] == "INVALID_OR_EXPIRED_TICKET"


def test_ui_rejection_flow():
    gate = HumanConfirmationGate(expiry_seconds=10.0)
    executed = False

    model_res = gate.request_confirmation(
        action_name="delete_system_file",
        description="Delete config",
        target="config.sys",
        action_fn=lambda: setattr(executed, "val", True)
    )
    ticket_id = model_res["ticket_id"]

    # User clicks CANCEL on the UI banner
    rej = gate.user_ui_reject(ticket_id, reason="Did not ask for this")
    assert rej["status"] == "cancelled"
    assert executed is False

    # Cannot approve after rejection
    app_res = gate.user_ui_approve(ticket_id)
    assert app_res["status"] == "error"
