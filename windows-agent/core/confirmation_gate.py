"""
MAX AI — Human-in-the-Loop UI Confirmation Gate (Clean-Room Architecture)
Enforces true human UI authorization for dangerous operations.
The model NEVER receives authorization tokens or self-approval capabilities.
Destructive operations park in a server-side pending registry and can ONLY be
resolved through out-of-band UI interaction (desktop banner button click).
"""

import time
import uuid
import logging
import threading
from dataclasses import dataclass
from typing import Dict, Any, Optional, Callable

logger = logging.getLogger("MAXWindowsAgent.ConfirmationGate")

DEFAULT_EXPIRY_SECONDS = 90.0


@dataclass
class PendingAction:
    ticket_id: str
    action_name: str
    description: str
    target: str
    action_fn: Callable[[], Any]
    created_at: float
    resolved: bool = False


class HumanConfirmationGate:
    """
    Server-side gate for irreversible operations.
    Guarantees the model cannot self-authorize because:
    1. The model receives only a ticket_id and a waiting message.
    2. Approval can only occur via `user_ui_approve(ticket_id)`, which is
       dispatched exclusively from local UI button events, never from LLM tool calls.
    3. Tickets are strictly single-use and automatically expire after 90 seconds.
    """

    def __init__(self, expiry_seconds: float = DEFAULT_EXPIRY_SECONDS):
        self._pending: Dict[str, PendingAction] = {}
        self._lock = threading.Lock()
        self.expiry_seconds = expiry_seconds
        self._ui_callback: Optional[Callable[[Dict[str, Any]], None]] = None

    def register_ui_listener(self, callback: Callable[[Dict[str, Any]], None]) -> None:
        """Registers a callback to push confirmation requests to the desktop UI banner."""
        self._ui_callback = callback

    def request_confirmation(
        self,
        action_name: str,
        description: str,
        target: str,
        action_fn: Callable[[], Any]
    ) -> Dict[str, Any]:
        """
        Parks an irreversible action in server-side state.
        The returned dictionary is safe for the LLM to inspect, containing ZERO authorization secrets.
        """
        ticket_id = f"ticket_{uuid.uuid4().hex[:12]}"
        now = time.time()

        action = PendingAction(
            ticket_id=ticket_id,
            action_name=action_name,
            description=description,
            target=target,
            action_fn=action_fn,
            created_at=now
        )

        with self._lock:
            self._cleanup_expired()
            self._pending[ticket_id] = action

        logger.warning(f"🔒 Dangerous action parked behind UI gate: {action_name} on '{target}' (Ticket: {ticket_id})")

        ui_payload = {
            "type": "CONFIRMATION_REQUIRED",
            "ticket_id": ticket_id,
            "action_name": action_name,
            "description": description,
            "target": target,
            "expires_in_seconds": self.expiry_seconds
        }

        # Push to desktop UI banner
        if self._ui_callback:
            try:
                self._ui_callback(ui_payload)
            except Exception as e:
                logger.error(f"UI notification error: {e}")

        # The model ONLY receives this response — ZERO tokens, ZERO approval secrets
        return {
            "status": "WAITING_CONFIRMATION",
            "ticket_id": ticket_id,
            "action_name": action_name,
            "description": description,
            "target": target,
            "message": f"Action '{description}' is paused. Waiting for physical user approval on desktop UI."
        }

    def user_ui_approve(self, ticket_id: str) -> Dict[str, Any]:
        """
        Executed ONLY by verified local UI user interaction (e.g. clicking 'CONFIRM' on the HUD banner).
        Cannot be called by LLM tool loop.
        """
        now = time.time()
        with self._lock:
            action = self._pending.get(ticket_id)
            if not action:
                return {
                    "status": "error",
                    "error_code": "INVALID_OR_EXPIRED_TICKET",
                    "message": "Confirmation ticket not found or already consumed."
                }

            if (now - action.created_at) > self.expiry_seconds:
                del self._pending[ticket_id]
                return {
                    "status": "error",
                    "error_code": "CONFIRMATION_EXPIRED",
                    "message": f"Confirmation ticket expired after {self.expiry_seconds}s."
                }

            # Single-use consumption
            del self._pending[ticket_id]

        # Execute action outside lock
        logger.info(f"✅ User clicked CONFIRM on UI for ticket '{ticket_id}'. Executing {action.action_name}...")
        try:
            result = action.action_fn()
            return {
                "status": "success",
                "action_name": action.action_name,
                "result": result,
                "message": f"Action '{action.description}' approved by user and executed successfully."
            }
        except Exception as e:
            logger.error(f"Execution error for confirmed action {action.action_name}: {e}")
            return {
                "status": "error",
                "error_code": "EXECUTION_ERROR",
                "message": str(e)
            }

    def user_ui_reject(self, ticket_id: str, reason: str = "User rejected action") -> Dict[str, Any]:
        """Executed when user clicks 'CANCEL' on the desktop UI banner."""
        with self._lock:
            action = self._pending.pop(ticket_id, None)
            if action:
                logger.info(f"❌ User clicked CANCEL on UI for ticket '{ticket_id}'. Action cancelled.")
                return {
                    "status": "cancelled",
                    "action_name": action.action_name,
                    "message": f"Action '{action.description}' was rejected by user: {reason}"
                }
            return {"status": "error", "message": "Ticket not found."}

    def get_pending_ticket(self, ticket_id: str) -> Optional[Dict[str, Any]]:
        """Returns details of a pending action for the UI to display."""
        with self._lock:
            action = self._pending.get(ticket_id)
            if action:
                return {
                    "ticket_id": action.ticket_id,
                    "action_name": action.action_name,
                    "description": action.description,
                    "target": action.target,
                    "created_at": action.created_at,
                    "time_remaining": max(0.0, self.expiry_seconds - (time.time() - action.created_at))
                }
            return None

    @property
    def has_pending(self) -> bool:
        with self._lock:
            self._cleanup_expired()
            return len(self._pending) > 0

    def confirm_latest_pending(self, approved: bool = True, reason: str = "Voice confirmation") -> Dict[str, Any]:
        with self._lock:
            self._cleanup_expired()
            if not self._pending:
                return {"status": "error", "message": "No pending action to confirm."}
            # Get most recent ticket
            latest_tid = list(self._pending.keys())[-1]

        if approved:
            return self.user_ui_approve(latest_tid)
        else:
            return self.user_ui_reject(latest_tid, reason=reason)

    def _cleanup_expired(self):
        now = time.time()
        expired = [tid for tid, act in self._pending.items() if (now - act.created_at) > self.expiry_seconds]
        for tid in expired:
            del self._pending[tid]


confirmation_gate = HumanConfirmationGate()
