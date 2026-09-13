"""
MAX AI — Process-Level Undo Manager
Maintains a thread-safe, bounded LIFO stack (max 50 actions) of self-validating
rollback closures for reversible system and file operations.
"""

import time
import logging
import threading
from typing import Callable, Dict, Any, List, Optional, Tuple

logger = logging.getLogger("MAXWindowsAgent.UndoManager")

MAX_UNDO_STACK_SIZE = 50


class UndoRecord:
    """Represents a single registered undoable action."""

    def __init__(
        self,
        description: str,
        undo_fn: Callable[[], Tuple[bool, str]],
        metadata: Optional[Dict[str, Any]] = None,
    ):
        self.description = description
        self.undo_fn = undo_fn
        self.metadata = metadata or {}
        self.timestamp = time.time()

    def to_dict(self) -> Dict[str, Any]:
        return {
            "description": self.description,
            "timestamp": self.timestamp,
            "time_str": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(self.timestamp)),
            "metadata": self.metadata,
        }


class UndoManager:
    """
    Process-level Undo Manager with a bounded LIFO stack.
    Thread-safe and self-validating.
    """

    def __init__(self, max_size: int = MAX_UNDO_STACK_SIZE):
        self._stack: List[UndoRecord] = []
        self._lock = threading.Lock()
        self._max_size = max_size

    def push_undo(
        self,
        description: str,
        undo_fn: Callable[[], Tuple[bool, str]],
        metadata: Optional[Dict[str, Any]] = None,
    ) -> None:
        """
        Registers a rollback closure for the most recent successful operation.
        
        Args:
            description: Human-readable explanation of what will be undone (e.g. 'Volume set to 80%').
            undo_fn: Zero-arg callable returning (success: bool, message: str).
            metadata: Optional dictionary with context (e.g. {'type': 'volume', 'previous_level': 50}).
        """
        if not callable(undo_fn):
            logger.warning(f"UndoManager: Ignored push for '{description}' — undo_fn is not callable.")
            return

        record = UndoRecord(description=description, undo_fn=undo_fn, metadata=metadata)
        with self._lock:
            if len(self._stack) >= self._max_size:
                evicted = self._stack.pop(0)  # FIFO eviction of oldest record
                logger.debug(f"UndoManager: Evicted oldest undo record: {evicted.description}")
            self._stack.append(record)
            logger.info(f"Registered undoable action: {description} (Stack size: {len(self._stack)})")

    def undo(self) -> Dict[str, Any]:
        """
        Pops and executes the most recent undoable action.
        The closure validates its own preconditions before mutating state.
        
        Returns:
            Structured dictionary with success status, description, message, and error.
        """
        with self._lock:
            if not self._stack:
                return {
                    "status": "info",
                    "success": False,
                    "description": "",
                    "message": "Nothing to undo. The undo history is empty.",
                    "error": None,
                }
            record = self._stack.pop()

        logger.info(f"Executing undo: {record.description}")
        try:
            success, msg = record.undo_fn()
            if success:
                logger.info(f"Undo succeeded: {record.description} -> {msg}")
                return {
                    "status": "success",
                    "success": True,
                    "description": record.description,
                    "message": msg or f"Successfully undone: {record.description}",
                    "error": None,
                }
            else:
                logger.warning(f"Undo precondition failed for '{record.description}': {msg}")
                return {
                    "status": "error",
                    "success": False,
                    "description": record.description,
                    "message": f"Could not undo '{record.description}': {msg}",
                    "error": msg,
                }
        except Exception as e:
            logger.error(f"Error during undo execution for '{record.description}': {e}", exc_info=True)
            return {
                "status": "error",
                "success": False,
                "description": record.description,
                "message": f"Undo failed with exception: {e}",
                "error": str(e),
            }

    def get_history(self, limit: int = 10) -> List[Dict[str, Any]]:
        """
        Inspects the undo history without modifying the stack.
        Returns entries ordered from newest to oldest.
        """
        with self._lock:
            records = list(reversed(self._stack))[:limit]
            return [r.to_dict() for r in records]

    def clear(self) -> None:
        """Clears all registered undo actions."""
        with self._lock:
            self._stack.clear()
            logger.debug("UndoManager: Cleared all undo records.")

    @property
    def size(self) -> int:
        with self._lock:
            return len(self._stack)


# Process-level instance accessor
_global_undo_manager: Optional[UndoManager] = None
_instance_lock = threading.Lock()


def get_undo_manager() -> UndoManager:
    """Returns the process-level UndoManager instance."""
    global _global_undo_manager
    with _instance_lock:
        if _global_undo_manager is None:
            _global_undo_manager = UndoManager()
        return _global_undo_manager


# Canonical reference
undo_manager = get_undo_manager()
