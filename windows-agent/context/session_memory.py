"""
MAX AI — Consumed Session Memory (P1-4)
Manages single-consumption previous session summaries for active context injection.
Injects previous session summary exactly once on new session startup, then marks it CONSUMED
to prevent prompt pollution while preserving permanent memory completely intact.
"""

import time
import logging
from enum import Enum
from typing import Dict, Any, Optional

from core.security import security_guard
from context.models import ContextRecord, ContextCategory, ContextOrigin

logger = logging.getLogger("MAXWindowsAgent.SessionMemory")


class SummaryState(str, Enum):
    UNCONSUMED = "unconsumed"
    CONSUMED = "consumed"
    EMPTY = "empty"


class SessionMemoryManager:
    """
    Manages session summaries with explicit UNCONSUMED -> CONSUMED state transitions.
    """

    def __init__(self):
        self._summary_text: Optional[str] = None
        self._timestamp: float = 0.0
        self._state: SummaryState = SummaryState.EMPTY
        self._metadata: Dict[str, Any] = {}

    @property
    def state(self) -> SummaryState:
        return self._state

    def save_session_summary(self, summary_text: str, metadata: Optional[Dict[str, Any]] = None) -> bool:
        """
        Stores a compact summary of the concluded conversation session,
        marking it UNCONSUMED for the next session.
        """
        if not summary_text or not summary_text.strip():
            self._summary_text = None
            self._state = SummaryState.EMPTY
            return False

        # Apply secret masking immediately
        safe_summary = security_guard.mask_secrets(summary_text.strip())

        # Defensive sanitize against prompt injection tokens
        safe_summary = safe_summary.replace("<PASSIVE_CONTEXT>", "").replace("</PASSIVE_CONTEXT>", "")
        safe_summary = safe_summary.replace("<PREVIOUS_SESSION_SUMMARY>", "").replace("</PREVIOUS_SESSION_SUMMARY>", "")

        self._summary_text = safe_summary
        self._timestamp = time.time()
        self._metadata = metadata or {}
        self._state = SummaryState.UNCONSUMED

        logger.info("💾 Saved compact previous session summary (marked UNCONSUMED)")
        return True

    def get_and_consume_summary(self) -> Optional[ContextRecord]:
        """
        Retrieves the unconsumed session summary as a ContextRecord and
        immediately marks it CONSUMED so it is never re-injected.
        """
        if self._state != SummaryState.UNCONSUMED or not self._summary_text:
            return None

        # Build ContextRecord
        record = ContextRecord(
            record_id="previous_session_summary",
            category=ContextCategory.ACTIVE_TASK,
            origin=ContextOrigin.LOCAL_WINDOWS,
            payload={
                "summary": self._summary_text,
                "timestamp": self._timestamp,
                "metadata": self._metadata,
                "consumed": True
            },
            updated_at=time.time(),
            ttl_seconds=300.0
        )

        # Transition to CONSUMED
        self._state = SummaryState.CONSUMED
        logger.info("🔄 Injected previous session summary into context and transitioned state to CONSUMED")
        return record

    def peek_summary(self) -> Optional[str]:
        """Peeks at summary text without changing state."""
        return self._summary_text

    def clear(self):
        """Clears session summary."""
        self._summary_text = None
        self._timestamp = 0.0
        self._state = SummaryState.EMPTY
        self._metadata = {}


# Global singleton instance
session_memory_manager = SessionMemoryManager()
