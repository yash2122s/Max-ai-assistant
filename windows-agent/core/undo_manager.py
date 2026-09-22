"""
MAX AI — Process-Level Undo Manager with Transaction & Persistence Support
Maintains a thread-safe, bounded LIFO stack (max 50 actions) of self-validating
rollback closures for reversible system and file operations.
Supports transaction grouping (multi-step batch rollback) and disk-backed journaling.
"""

import os
import json
import time
import uuid
import logging
import tempfile
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
        operation_id: Optional[str] = None,
        transaction_id: Optional[str] = None,
    ):
        self.operation_id = operation_id or f"op_{uuid.uuid4().hex[:8]}"
        self.transaction_id = transaction_id
        self.description = description
        self.undo_fn = undo_fn
        self.metadata = metadata or {}
        self.timestamp = time.time()

    def to_dict(self) -> Dict[str, Any]:
        return {
            "operation_id": self.operation_id,
            "transaction_id": self.transaction_id,
            "description": self.description,
            "timestamp": self.timestamp,
            "time_str": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(self.timestamp)),
            "metadata": self.metadata,
        }


class TransactionRecord:
    """Represents a group of undoable actions that should be rolled back together."""

    def __init__(self, description: str, transaction_id: Optional[str] = None):
        self.transaction_id = transaction_id or f"tx_{uuid.uuid4().hex[:8]}"
        self.description = description
        self.records: List[UndoRecord] = []
        self.timestamp = time.time()

    def add_record(self, record: UndoRecord):
        record.transaction_id = self.transaction_id
        self.records.append(record)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "transaction_id": self.transaction_id,
            "description": self.description,
            "count": len(self.records),
            "timestamp": self.timestamp,
            "time_str": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(self.timestamp)),
            "items": [r.to_dict() for r in self.records],
        }


class UndoManager:
    """
    Process-level Undo Manager with bounded LIFO stack, transaction grouping,
    and disk-backed recovery journaling.
    """

    def __init__(self, max_size: int = MAX_UNDO_STACK_SIZE, journal_dir: Optional[str] = None):
        self._stack: List[Any] = []  # Can contain UndoRecord or TransactionRecord
        self._lock = threading.Lock()
        self._max_size = max_size
        self._active_transaction: Optional[TransactionRecord] = None
        self._journal_dir = journal_dir or os.path.join(tempfile.gettempdir(), "max_file_vault")
        self._journal_path = os.path.join(self._journal_dir, "max_undo_journal.jsonl")
        try:
            os.makedirs(self._journal_dir, exist_ok=True)
        except Exception:
            pass

    def begin_transaction(self, description: str) -> str:
        """
        Starts a new transaction group for multi-step filesystem operations.
        All subsequent push_undo calls will be grouped until commit_transaction() is called.
        """
        with self._lock:
            if self._active_transaction is not None:
                self._stack.append(self._active_transaction)
            self._active_transaction = TransactionRecord(description=description)
            tx_id = self._active_transaction.transaction_id
            logger.info(f"UndoManager: Began transaction '{description}' (ID: {tx_id})")
            return tx_id

    def commit_transaction(self) -> Optional[str]:
        """Commits the active transaction group to the undo stack."""
        with self._lock:
            if self._active_transaction is None:
                return None
            tx = self._active_transaction
            self._active_transaction = None
            if tx.records:
                if len(self._stack) >= self._max_size:
                    self._stack.pop(0)
                self._stack.append(tx)
                logger.info(f"UndoManager: Committed transaction '{tx.description}' with {len(tx.records)} operations.")
                return tx.transaction_id
            return None

    def rollback_active_transaction(self) -> Dict[str, Any]:
        """Rolls back the currently open (uncommitted) transaction immediately."""
        with self._lock:
            tx = self._active_transaction
            self._active_transaction = None

        if not tx or not tx.records:
            return {"status": "info", "message": "No active transaction to rollback."}

        logger.info(f"UndoManager: Rolling back uncommitted transaction '{tx.description}'")
        failed = []
        succeeded = 0
        for rec in reversed(tx.records):
            try:
                ok, msg = rec.undo_fn()
                if ok:
                    succeeded += 1
                else:
                    failed.append(f"{rec.description}: {msg}")
            except Exception as e:
                failed.append(f"{rec.description}: {e}")

        return {
            "status": "success" if not failed else "partial",
            "transaction_id": tx.transaction_id,
            "succeeded": succeeded,
            "failed_count": len(failed),
            "errors": failed,
        }

    def is_in_transaction(self) -> bool:
        with self._lock:
            return self._active_transaction is not None

    def push_undo(
        self,
        description: str,
        undo_fn: Callable[[], Tuple[bool, str]],
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        """
        Registers a rollback closure for the most recent successful operation.
        """
        if not callable(undo_fn):
            logger.warning(f"UndoManager: Ignored push for '{description}' — undo_fn is not callable.")
            return ""

        record = UndoRecord(description=description, undo_fn=undo_fn, metadata=metadata)
        
        with self._lock:
            if self._active_transaction is not None:
                self._active_transaction.add_record(record)
                logger.info(f"UndoManager: Added action to transaction '{self._active_transaction.description}': {description}")
            else:
                if len(self._stack) >= self._max_size:
                    evicted = self._stack.pop(0)
                    logger.debug(f"UndoManager: Evicted oldest undo record: {getattr(evicted, 'description', 'record')}")
                self._stack.append(record)
                logger.info(f"UndoManager: Registered undoable action: {description} (Stack size: {len(self._stack)})")

        self._append_journal_entry(record)
        return record.operation_id

    def undo(self) -> Dict[str, Any]:
        """
        Pops and executes the most recent undoable action or transaction.
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
            item = self._stack.pop()

        if isinstance(item, TransactionRecord):
            logger.info(f"UndoManager: Executing transaction undo for '{item.description}' ({len(item.records)} actions)")
            errors = []
            succeeded_count = 0
            for rec in reversed(item.records):
                try:
                    ok, msg = rec.undo_fn()
                    if ok:
                        succeeded_count += 1
                    else:
                        errors.append(f"{rec.description}: {msg}")
                except Exception as ex:
                    errors.append(f"{rec.description}: {ex}")

            if not errors:
                return {
                    "status": "success",
                    "success": True,
                    "description": item.description,
                    "message": f"Successfully rolled back transaction '{item.description}' ({succeeded_count} actions undone).",
                    "transaction_id": item.transaction_id,
                    "actions_undone": succeeded_count,
                    "error": None,
                }
            else:
                return {
                    "status": "partial" if succeeded_count > 0 else "error",
                    "success": False,
                    "description": item.description,
                    "message": f"Partial rollback for transaction '{item.description}': {succeeded_count} succeeded, {len(errors)} failed.",
                    "transaction_id": item.transaction_id,
                    "actions_undone": succeeded_count,
                    "error": "; ".join(errors),
                }

        # Single UndoRecord
        logger.info(f"UndoManager: Executing undo: {item.description}")
        try:
            success, msg = item.undo_fn()
            if success:
                logger.info(f"Undo succeeded: {item.description} -> {msg}")
                return {
                    "status": "success",
                    "success": True,
                    "description": item.description,
                    "message": msg or f"Successfully undone: {item.description}",
                    "operation_id": item.operation_id,
                    "error": None,
                }
            else:
                logger.warning(f"Undo precondition failed for '{item.description}': {msg}")
                return {
                    "status": "error",
                    "success": False,
                    "description": item.description,
                    "message": f"Could not undo '{item.description}': {msg}",
                    "operation_id": item.operation_id,
                    "error": msg,
                }
        except Exception as e:
            logger.error(f"Error during undo execution for '{item.description}': {e}", exc_info=True)
            return {
                "status": "error",
                "success": False,
                "description": item.description,
                "message": f"Undo failed with exception: {e}",
                "operation_id": item.operation_id,
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
            self._active_transaction = None
            logger.debug("UndoManager: Cleared all undo records.")

    @property
    def size(self) -> int:
        with self._lock:
            return len(self._stack)

    def can_undo(self) -> bool:
        with self._lock:
            return len(self._stack) > 0

    def _append_journal_entry(self, record: UndoRecord):
        """Appends a structured JSON record to the persistent disk journal."""
        try:
            entry = {
                "operation_id": record.operation_id,
                "transaction_id": record.transaction_id,
                "description": record.description,
                "timestamp": record.timestamp,
                "metadata": record.metadata,
            }
            with open(self._journal_path, "a", encoding="utf-8") as jf:
                jf.write(json.dumps(entry) + "\n")
        except Exception as e:
            logger.debug(f"UndoManager: Could not write journal entry: {e}")


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
