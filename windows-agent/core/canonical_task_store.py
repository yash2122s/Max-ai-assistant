"""
MAX Ecosystem — Canonical Task Store
Authoritative task domain and state management layer backed by persistent SQLite StateDatabase.
Provides strict state transition validation, parent-child hierarchy, idempotency deduplication,
and event audit logging.
"""

import hashlib
import json
import logging
import sqlite3
import threading
from typing import Dict, Any, List, Optional, Tuple, Union

from config import STATE_DB_PATH
from core.state_database import StateDatabase
from protocol.task_envelope import (
    TaskEnvelope,
    TaskResult,
    TaskEvent,
    TaskStatus,
    TaskEventType,
    current_iso_time
)

logger = logging.getLogger("MAXWindowsAgent.CanonicalTaskStore")


# Valid State Transitions Graph
VALID_TRANSITIONS: Dict[str, List[str]] = {
    TaskStatus.QUEUED.value: [
        TaskStatus.RUNNING.value,
        TaskStatus.WAITING_CONFIRMATION.value,
        TaskStatus.WAITING_ESCALATION.value,
        TaskStatus.CANCELLED.value,
        TaskStatus.FAILED.value,
        TaskStatus.PERMISSION_DENIED.value
    ],
    TaskStatus.RUNNING.value: [
        TaskStatus.WAITING_CONFIRMATION.value,
        TaskStatus.WAITING_CLARIFICATION.value,
        TaskStatus.WAITING_ESCALATION.value,
        TaskStatus.VERIFYING.value,
        TaskStatus.COMPLETED.value,
        TaskStatus.FAILED.value,
        TaskStatus.CANCELLED.value,
        TaskStatus.TIMED_OUT.value,
        TaskStatus.PARTIALLY_COMPLETED.value,
        TaskStatus.PERMISSION_DENIED.value,
        TaskStatus.AUTH_REQUIRED.value,
        TaskStatus.AUTH_EXPIRED.value,
        TaskStatus.QUOTA_EXCEEDED.value,
        TaskStatus.RATE_LIMITED.value,
        TaskStatus.CLI_UNAVAILABLE.value,
        TaskStatus.WORKER_UNAVAILABLE.value
    ],
    TaskStatus.WAITING_CONFIRMATION.value: [
        TaskStatus.RUNNING.value,
        TaskStatus.CANCELLED.value,
        TaskStatus.PERMISSION_DENIED.value,
        TaskStatus.FAILED.value
    ],
    TaskStatus.WAITING_CLARIFICATION.value: [
        TaskStatus.RUNNING.value,
        TaskStatus.CANCELLED.value,
        TaskStatus.FAILED.value
    ],
    TaskStatus.WAITING_ESCALATION.value: [
        TaskStatus.RUNNING.value,
        TaskStatus.FAILED.value,
        TaskStatus.PARTIALLY_COMPLETED.value,
        TaskStatus.CANCELLED.value
    ],
    TaskStatus.VERIFYING.value: [
        TaskStatus.COMPLETED.value,
        TaskStatus.VERIFICATION_FAILED.value,
        TaskStatus.FAILED.value,
        TaskStatus.CANCELLED.value
    ],
    # Non-terminal transient error recoveries
    TaskStatus.AUTH_REQUIRED.value: [TaskStatus.RUNNING.value, TaskStatus.FAILED.value, TaskStatus.CANCELLED.value],
    TaskStatus.AUTH_EXPIRED.value: [TaskStatus.RUNNING.value, TaskStatus.FAILED.value, TaskStatus.CANCELLED.value],
    TaskStatus.QUOTA_EXCEEDED.value: [TaskStatus.RUNNING.value, TaskStatus.FAILED.value, TaskStatus.CANCELLED.value],
    TaskStatus.RATE_LIMITED.value: [TaskStatus.RUNNING.value, TaskStatus.FAILED.value, TaskStatus.CANCELLED.value],
    TaskStatus.CLI_UNAVAILABLE.value: [TaskStatus.RUNNING.value, TaskStatus.FAILED.value, TaskStatus.CANCELLED.value],
    TaskStatus.WORKER_UNAVAILABLE.value: [TaskStatus.RUNNING.value, TaskStatus.FAILED.value, TaskStatus.CANCELLED.value],
    # Terminal States: No transitions out of terminal states
    TaskStatus.COMPLETED.value: [],
    TaskStatus.FAILED.value: [],
    TaskStatus.CANCELLED.value: [],
    TaskStatus.TIMED_OUT.value: [],
    TaskStatus.VERIFICATION_FAILED.value: [],
    TaskStatus.PERMISSION_DENIED.value: [],
    TaskStatus.PARTIALLY_COMPLETED.value: []
}

ACTIVE_STATUSES = {
    TaskStatus.QUEUED.value,
    TaskStatus.RUNNING.value,
    TaskStatus.WAITING_CONFIRMATION.value,
    TaskStatus.WAITING_CLARIFICATION.value,
    TaskStatus.WAITING_ESCALATION.value,
    TaskStatus.VERIFYING.value
}


class CanonicalTaskStore:
    """
    Canonical Task Domain and State Authority.
    Uses existing SQLite StateDatabase connection to guarantee single source of truth.
    """
    _instance: Optional['CanonicalTaskStore'] = None
    _lock = threading.Lock()

    def __init__(self, state_db: Optional[StateDatabase] = None):
        self.state_db = state_db or StateDatabase.get_instance()
        self._init_tables()

    @classmethod
    def get_instance(cls) -> 'CanonicalTaskStore':
        with cls._lock:
            if cls._instance is None:
                cls._instance = cls()
            return cls._instance

    def _get_conn(self) -> sqlite3.Connection:
        return self.state_db._get_connection()

    def _init_tables(self):
        conn = self._get_conn()
        with conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS canonical_tasks (
                    task_id TEXT PRIMARY KEY,
                    parent_task_id TEXT,
                    root_task_id TEXT NOT NULL,
                    origin TEXT NOT NULL,
                    worker TEXT NOT NULL,
                    task_type TEXT NOT NULL,
                    status TEXT NOT NULL,
                    depth INTEGER NOT NULL,
                    permissions_json TEXT NOT NULL,
                    goal TEXT NOT NULL,
                    workspace TEXT,
                    created_at TEXT NOT NULL,
                    started_at TEXT,
                    finished_at TEXT,
                    timeout_at TEXT,
                    cancel_requested INTEGER DEFAULT 0,
                    verification_status TEXT DEFAULT 'pending',
                    idempotency_key TEXT,
                    claimed_success INTEGER DEFAULT 0,
                    verified_success INTEGER DEFAULT 0,
                    envelope_json TEXT NOT NULL,
                    result_json TEXT,
                    error_json TEXT
                );
            """)

            conn.execute("""
                CREATE TABLE IF NOT EXISTS canonical_task_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id TEXT NOT NULL,
                    root_task_id TEXT NOT NULL,
                    parent_task_id TEXT,
                    timestamp TEXT NOT NULL,
                    source TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    message TEXT NOT NULL,
                    metadata_json TEXT
                );
            """)

            conn.execute("CREATE INDEX IF NOT EXISTS idx_tasks_root ON canonical_tasks(root_task_id);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_tasks_parent ON canonical_tasks(parent_task_id);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status ON canonical_tasks(status);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_tasks_idempotency ON canonical_tasks(idempotency_key);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_events_task ON canonical_task_events(task_id);")

    @staticmethod
    def compute_idempotency_key(
        workspace: Optional[str],
        task_type: str,
        target: str,
        normalized_intent: str,
        canonical_arguments: Optional[Dict[str, Any]] = None
    ) -> str:
        """
        Computes SHA-256 idempotency key from workspace, task_type, target, intent, and arguments.
        """
        norm_args = json.dumps(canonical_arguments or {}, sort_keys=True)
        raw = f"{workspace or 'default'}::{task_type}::{target}::{normalized_intent.strip().lower()}::{norm_args}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    def is_repeatable_command(self, intent: str, task_type: str) -> bool:
        """
        Determines whether a command is intentionally repeatable (e.g. 'draw another circle', 'volume up 5').
        """
        low = intent.lower().strip()
        repeatable_keywords = ["another", "again", "repeat", "one more", "increase", "decrease", "more", "next"]
        for kw in repeatable_keywords:
            if kw in low:
                return True
        if task_type in ("volume_adjustment", "incremental_action"):
            return True
        return False

    def create_task(
        self,
        envelope: TaskEnvelope,
        idempotency_policy: str = "UNIQUE"
    ) -> Tuple[TaskEnvelope, bool]:
        """
        Stores task in canonical store. If an equivalent active task already exists under UNIQUE policy,
        returns (existing_envelope, False). Otherwise returns (created_envelope, True).
        """
        conn = self._get_conn()
        intent = envelope.goal
        task_type = envelope.task_type

        # Check repeatability
        if self.is_repeatable_command(intent, task_type) or idempotency_policy == "REPEATABLE":
            idempotency_key = f"rep_{envelope.task_id}"
        else:
            idempotency_key = self.compute_idempotency_key(
                workspace=envelope.workspace,
                task_type=envelope.task_type,
                target=envelope.worker,
                normalized_intent=envelope.goal,
                canonical_arguments=envelope.metadata
            )

        with conn:
            # Check for existing active root task with identical key
            if envelope.depth == 0 and not idempotency_key.startswith("rep_"):
                row = conn.execute(
                    "SELECT envelope_json, status FROM canonical_tasks WHERE idempotency_key = ? AND status IN (?, ?, ?, ?, ?, ?)",
                    (idempotency_key, TaskStatus.QUEUED.value, TaskStatus.RUNNING.value,
                     TaskStatus.WAITING_CONFIRMATION.value, TaskStatus.WAITING_CLARIFICATION.value,
                     TaskStatus.WAITING_ESCALATION.value, TaskStatus.VERIFYING.value)
                ).fetchone()

                if row:
                    logger.info(f"Deduplicated root task: reusing existing active task with key {idempotency_key[:12]}")
                    existing_env = TaskEnvelope.from_json(row["envelope_json"])
                    return existing_env, False

            # Insert new task
            conn.execute("""
                INSERT INTO canonical_tasks (
                    task_id, parent_task_id, root_task_id, origin, worker, task_type,
                    status, depth, permissions_json, goal, workspace, created_at,
                    timeout_at, cancel_requested, verification_status, idempotency_key,
                    envelope_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'pending', ?, ?)
            """, (
                envelope.task_id,
                envelope.parent_task_id,
                envelope.root_task_id,
                envelope.origin,
                envelope.worker,
                envelope.task_type,
                TaskStatus.QUEUED.value,
                envelope.depth,
                json.dumps(envelope.permissions),
                envelope.goal,
                envelope.workspace,
                envelope.created_at,
                envelope.timeout_at,
                idempotency_key,
                envelope.to_json()
            ))

        self.record_event(TaskEvent(
            task_id=envelope.task_id,
            root_task_id=envelope.root_task_id,
            parent_task_id=envelope.parent_task_id,
            source=envelope.origin,
            event_type=TaskEventType.TASK_CREATED.value,
            message=f"Task '{envelope.goal}' created for worker '{envelope.worker}'."
        ))

        return envelope, True

    def transition_status(
        self,
        task_id: str,
        new_status: Union[TaskStatus, str],
        claimed_success: Optional[bool] = None,
        verified_success: Optional[bool] = None,
        result: Optional[Union[TaskResult, Dict[str, Any]]] = None,
        error: Optional[Dict[str, Any]] = None
    ) -> TaskResult:
        """
        Validates and performs atomic state machine transition. Rejects invalid transitions.
        """
        status_val = new_status.value if isinstance(new_status, TaskStatus) else str(new_status)
        conn = self._get_conn()

        with conn:
            row = conn.execute("SELECT * FROM canonical_tasks WHERE task_id = ?", (task_id,)).fetchone()
            if not row:
                raise KeyError(f"Task '{task_id}' not found in canonical store.")

            current_status = row["status"]
            if current_status == status_val:
                # Idempotent re-affirmation
                return self.get_task_result(task_id)

            allowed = VALID_TRANSITIONS.get(current_status, [])
            if status_val not in allowed:
                raise ValueError(f"Invalid state transition: Cannot transition task from '{current_status}' to '{status_val}'.")

            # Handle race condition with cancellation
            cancel_req = bool(row["cancel_requested"])
            final_status = status_val
            if cancel_req and status_val not in (TaskStatus.CANCELLED.value, TaskStatus.COMPLETED.value):
                logger.info(f"Cancellation requested for task {task_id}; forcing status to CANCELLED instead of {status_val}")
                final_status = TaskStatus.CANCELLED.value

            now = current_iso_time()
            started_at = row["started_at"] or (now if final_status == TaskStatus.RUNNING.value else None)
            finished_at = now if final_status in (
                TaskStatus.COMPLETED.value, TaskStatus.FAILED.value, TaskStatus.CANCELLED.value,
                TaskStatus.TIMED_OUT.value, TaskStatus.VERIFICATION_FAILED.value, TaskStatus.PERMISSION_DENIED.value
            ) else None

            res_dict = result.to_dict() if isinstance(result, TaskResult) else (result or {})
            err_dict = error or res_dict.get("error")

            c_success = int(claimed_success) if claimed_success is not None else int(row["claimed_success"])
            v_success = int(verified_success) if verified_success is not None else int(row["verified_success"])

            # Strict completion invariant: completion requires verified_success == 1
            if final_status == TaskStatus.COMPLETED.value and not v_success:
                logger.warning(f"Task {task_id} attempted completion without verified_success=True. Diverting to VERIFICATION_FAILED.")
                final_status = TaskStatus.VERIFICATION_FAILED.value

            conn.execute("""
                UPDATE canonical_tasks
                SET status = ?, started_at = COALESCE(started_at, ?), finished_at = ?,
                    claimed_success = ?, verified_success = ?, result_json = ?, error_json = ?
                WHERE task_id = ?
            """, (
                final_status,
                started_at,
                finished_at,
                c_success,
                v_success,
                json.dumps(res_dict) if res_dict else None,
                json.dumps(err_dict) if err_dict else None,
                task_id
            ))

        # Record corresponding event
        evt_type = self._map_status_to_event_type(final_status)
        self.record_event(TaskEvent(
            task_id=task_id,
            root_task_id=row["root_task_id"],
            parent_task_id=row["parent_task_id"],
            source=row["worker"],
            event_type=evt_type,
            message=f"Task transitioned to status '{final_status}'."
        ))

        return self.get_task_result(task_id)

    def request_cancellation(self, task_id: str, reason: str = "User requested cancellation") -> bool:
        """
        Marks cancellation requested. If task is non-terminal, transitions to CANCELLED.
        """
        conn = self._get_conn()
        with conn:
            row = conn.execute("SELECT * FROM canonical_tasks WHERE task_id = ?", (task_id,)).fetchone()
            if not row:
                return False

            conn.execute("UPDATE canonical_tasks SET cancel_requested = 1 WHERE task_id = ?", (task_id,))

            if row["status"] in ACTIVE_STATUSES:
                self.transition_status(
                    task_id=task_id,
                    new_status=TaskStatus.CANCELLED,
                    error={"code": "CANCELLED", "message": reason}
                )

        self.record_event(TaskEvent(
            task_id=task_id,
            root_task_id=row["root_task_id"],
            parent_task_id=row["parent_task_id"],
            source="max",
            event_type=TaskEventType.TASK_CANCEL_REQUESTED.value,
            message=reason
        ))
        return True

    def record_event(self, event: TaskEvent):
        """Records task event in canonical event audit table."""
        conn = self._get_conn()
        with conn:
            conn.execute("""
                INSERT INTO canonical_task_events (
                    task_id, root_task_id, parent_task_id, timestamp, source,
                    event_type, message, metadata_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                event.task_id,
                event.root_task_id,
                event.parent_task_id,
                event.timestamp,
                event.source,
                event.event_type,
                event.message,
                json.dumps(event.metadata) if event.metadata else None
            ))

    def get_task(self, task_id: str) -> Optional[TaskEnvelope]:
        conn = self._get_conn()
        row = conn.execute("SELECT envelope_json FROM canonical_tasks WHERE task_id = ?", (task_id,)).fetchone()
        if row:
            return TaskEnvelope.from_json(row["envelope_json"])
        return None

    def get_task_result(self, task_id: str) -> TaskResult:
        conn = self._get_conn()
        row = conn.execute("SELECT * FROM canonical_tasks WHERE task_id = ?", (task_id,)).fetchone()
        if not row:
            raise KeyError(f"Task '{task_id}' not found.")

        res_data = json.loads(row["result_json"]) if row["result_json"] else {}
        err_data = json.loads(row["error_json"]) if row["error_json"] else None

        return TaskResult(
            task_id=task_id,
            status=row["status"],
            claimed_success=bool(row["claimed_success"]),
            verified_success=bool(row["verified_success"]),
            summary=res_data.get("summary", ""),
            artifacts=res_data.get("artifacts", []),
            verification=res_data.get("verification", {}),
            error=err_data,
            metrics=res_data.get("metrics", {})
        )

    def get_task_events(self, task_id: str) -> List[TaskEvent]:
        conn = self._get_conn()
        rows = conn.execute("SELECT * FROM canonical_task_events WHERE task_id = ? ORDER BY id ASC", (task_id,)).fetchall()
        events = []
        for r in rows:
            meta = json.loads(r["metadata_json"]) if r["metadata_json"] else {}
            events.append(TaskEvent(
                task_id=r["task_id"],
                root_task_id=r["root_task_id"],
                parent_task_id=r["parent_task_id"],
                timestamp=r["timestamp"],
                source=r["source"],
                event_type=r["event_type"],
                message=r["message"],
                metadata=meta
            ))
        return events

    def get_active_tasks(self) -> List[TaskEnvelope]:
        conn = self._get_conn()
        rows = conn.execute(
            "SELECT envelope_json FROM canonical_tasks WHERE status IN (?, ?, ?, ?, ?, ?)",
            (TaskStatus.QUEUED.value, TaskStatus.RUNNING.value, TaskStatus.WAITING_CONFIRMATION.value,
             TaskStatus.WAITING_CLARIFICATION.value, TaskStatus.WAITING_ESCALATION.value, TaskStatus.VERIFYING.value)
        ).fetchall()
        return [TaskEnvelope.from_json(r["envelope_json"]) for r in rows]

    def recover_non_terminal_tasks_on_startup(self) -> List[str]:
        """
        Identifies non-terminal tasks from a previous crash/restart and transitions them to 'interrupted' or 'failed'.
        """
        conn = self._get_conn()
        recovered = []
        with conn:
            rows = conn.execute(
                "SELECT task_id FROM canonical_tasks WHERE status IN (?, ?, ?, ?, ?, ?)",
                (TaskStatus.QUEUED.value, TaskStatus.RUNNING.value, TaskStatus.WAITING_CONFIRMATION.value,
                 TaskStatus.WAITING_CLARIFICATION.value, TaskStatus.WAITING_ESCALATION.value, TaskStatus.VERIFYING.value)
            ).fetchall()

            for r in rows:
                tid = r["task_id"]
                conn.execute(
                    "UPDATE canonical_tasks SET status = ?, finished_at = ?, error_json = ? WHERE task_id = ?",
                    (TaskStatus.FAILED.value, current_iso_time(), json.dumps({"code": "INTERRUPTED", "message": "Task interrupted by system restart."}), tid)
                )
                recovered.append(tid)

        if recovered:
            logger.info(f"Recovered {len(recovered)} non-terminal tasks interrupted by system restart.")
        return recovered

    def _map_status_to_event_type(self, status: str) -> str:
        mapping = {
            TaskStatus.QUEUED.value: TaskEventType.TASK_CREATED.value,
            TaskStatus.RUNNING.value: TaskEventType.TASK_STARTED.value,
            TaskStatus.WAITING_CONFIRMATION.value: TaskEventType.TASK_WAITING_CONFIRMATION.value,
            TaskStatus.WAITING_CLARIFICATION.value: TaskEventType.TASK_WAITING_CLARIFICATION.value,
            TaskStatus.WAITING_ESCALATION.value: TaskEventType.TASK_ESCALATION_REQUESTED.value,
            TaskStatus.VERIFYING.value: TaskEventType.TASK_VERIFICATION_STARTED.value,
            TaskStatus.COMPLETED.value: TaskEventType.TASK_COMPLETED.value,
            TaskStatus.FAILED.value: TaskEventType.TASK_FAILED.value,
            TaskStatus.CANCELLED.value: TaskEventType.TASK_CANCELLED.value,
            TaskStatus.TIMED_OUT.value: TaskEventType.TASK_TIMED_OUT.value,
            TaskStatus.VERIFICATION_FAILED.value: TaskEventType.TASK_VERIFICATION_FAILED.value,
            TaskStatus.PERMISSION_DENIED.value: TaskEventType.TASK_PERMISSION_DENIED.value
        }
        return mapping.get(status, TaskEventType.TASK_PROGRESS.value)


# Canonical singleton instance
canonical_task_store = CanonicalTaskStore()
