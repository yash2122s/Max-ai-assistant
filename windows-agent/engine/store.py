"""
MAX AI — Authoritative Task Store
Provides atomic persistence, corruption recovery, write-ahead safety,
and startup crash recovery for tasks and steps in storage/tasks.json.
"""

import os
import json
import time
import shutil
import logging
import threading
from typing import Dict, Any, List, Optional
from core.settings_manager import STORAGE_DIR
from .models import Task, Step, TaskState, StepState

logger = logging.getLogger("MAXWindowsAgent.TaskStore")

TASKS_FILE = os.path.join(STORAGE_DIR, "tasks.json")
SCHEMA_VERSION = "1.0"


class TaskStore:
    """
    Thread-safe, atomic persistent store for MAX tasks.
    """
    _instance: Optional['TaskStore'] = None

    def __init__(self, file_path: str = TASKS_FILE):
        self.file_path = file_path
        self._lock = threading.Lock()
        os.makedirs(os.path.dirname(self.file_path), exist_ok=True)
        self._ensure_store_initialized()

    @classmethod
    def get_instance(cls) -> 'TaskStore':
        if cls._instance is None:
            cls._instance = TaskStore()
        return cls._instance

    def _ensure_store_initialized(self):
        """Initializes empty tasks store if not present."""
        with self._lock:
            if not os.path.exists(self.file_path):
                self._write_store_unlocked({"schema_version": SCHEMA_VERSION, "tasks": {}})

    def _read_store_unlocked(self) -> Dict[str, Any]:
        """Reads tasks from disk with corruption handling."""
        if not os.path.exists(self.file_path):
            return {"schema_version": SCHEMA_VERSION, "tasks": {}}
        try:
            with open(self.file_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if not isinstance(data, dict) or "tasks" not in data:
                    raise ValueError("Invalid tasks store format")
                return data
        except Exception as e:
            logger.error(f"TaskStore corruption detected: {e}. Backing up corrupt file...")
            backup_path = f"{self.file_path}.corrupt.{int(time.time())}"
            try:
                shutil.copyfile(self.file_path, backup_path)
            except Exception:
                pass
            fresh_data = {"schema_version": SCHEMA_VERSION, "tasks": {}}
            self._write_store_unlocked(fresh_data)
            return fresh_data

    def _write_store_unlocked(self, data: Dict[str, Any]):
        """Performs atomic write via temp file rename."""
        temp_path = f"{self.file_path}.tmp"
        try:
            with open(temp_path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, ensure_ascii=False)
                f.flush()
                os.fsync(f.fileno())
            os.replace(temp_path, self.file_path)
        except Exception as e:
            logger.error(f"Error during atomic write to {self.file_path}: {e}")
            if os.path.exists(temp_path):
                try:
                    os.remove(temp_path)
                except Exception:
                    pass
            raise

    def save_task(self, task: Task) -> bool:
        """Saves or updates a task with write-ahead persistence."""
        with self._lock:
            store = self._read_store_unlocked()
            task.updated_at = time.time()
            store["tasks"][task.task_id] = task.to_dict(mask_secrets=True)
            self._write_store_unlocked(store)
            return True

    def get_task(self, task_id: str) -> Optional[Task]:
        """Retrieves a task by task_id."""
        with self._lock:
            store = self._read_store_unlocked()
            data = store["tasks"].get(task_id)
            if not data:
                return None
            return Task.from_dict(data)

    def list_tasks(self, limit: int = 50) -> List[Task]:
        """Lists recent tasks ordered by updated_at descending."""
        with self._lock:
            store = self._read_store_unlocked()
            tasks = [Task.from_dict(t) for t in store["tasks"].values()]
            tasks.sort(key=lambda x: x.updated_at, reverse=True)
            return tasks[:limit]

    def delete_task(self, task_id: str) -> bool:
        """Deletes a task by ID."""
        with self._lock:
            store = self._read_store_unlocked()
            if task_id in store["tasks"]:
                del store["tasks"][task_id]
                self._write_store_unlocked(store)
                return True
            return False

    def update_step(self, task_id: str, step: Step) -> bool:
        """Updates a specific step inside a task and persists."""
        with self._lock:
            store = self._read_store_unlocked()
            t_data = store["tasks"].get(task_id)
            if not t_data:
                return False
            task = Task.from_dict(t_data)
            updated = False
            for i, s in enumerate(task.steps):
                if s.step_id == step.step_id:
                    task.steps[i] = step
                    updated = True
                    break
            if updated:
                task.updated_at = time.time()
                store["tasks"][task_id] = task.to_dict(mask_secrets=True)
                self._write_store_unlocked(store)
                return True
            return False

    def recover_interrupted_tasks(self) -> int:
        """
        Scans for active/running tasks on process startup and marks them INTERRUPTED.
        Prevents blind side-effect resumption on crash.
        """
        recovered_count = 0
        with self._lock:
            store = self._read_store_unlocked()
            changed = False
            for task_id, t_dict in store["tasks"].items():
                task = Task.from_dict(t_dict)
                is_task_running = task.status in (
                    TaskState.RUNNING,
                    TaskState.VERIFYING,
                    TaskState.RETRYING,
                    TaskState.PLANNING
                )
                step_interrupted = False
                for step in task.steps:
                    if step.status in (StepState.RUNNING, StepState.VERIFYING, StepState.RETRYING):
                        step.status = StepState.INTERRUPTED
                        step_interrupted = True

                if is_task_running or step_interrupted:
                    task.status = TaskState.INTERRUPTED
                    task.error = {
                        "code": "TASK_INTERRUPTED",
                        "message": "Task execution was interrupted by an unexpected process shutdown or restart."
                    }
                    task.updated_at = time.time()
                    store["tasks"][task_id] = task.to_dict(mask_secrets=True)
                    changed = True
                    recovered_count += 1
                    logger.warning(f"Crash recovery: marked task '{task_id}' as INTERRUPTED.")

            if changed:
                self._write_store_unlocked(store)

        return recovered_count


# Canonical singleton instance
task_store = TaskStore.get_instance()
