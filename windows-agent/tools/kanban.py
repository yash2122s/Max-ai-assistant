"""
MAX AI — Kanban Goal Decomposition & Task Board
Provides structured multi-step task tracking, column workflows (Backlog, Todo, In Progress, Review, Done),
and persistent SQLite task persistence.
"""

import time
import uuid
import json
import logging
from typing import Dict, Any, List, Optional

from core.state_database import state_db

logger = logging.getLogger("MAXWindowsAgent.Kanban")


class KanbanBoard:
    """
    Manages Kanban board columns and persistent task items.
    """
    VALID_COLUMNS = ["backlog", "todo", "in_progress", "review", "done"]

    def __init__(self):
        self._init_table()

    def _init_table(self):
        """Initializes kanban_tasks table in SQLite state DB."""
        conn = state_db._get_connection()
        with conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS kanban_tasks (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    description TEXT,
                    column TEXT NOT NULL DEFAULT 'todo',
                    tags_json TEXT,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );
            """)

    def create_task(self, title: str, description: str = "", column: str = "todo", tags: Optional[List[str]] = None) -> Dict[str, Any]:
        """Creates a new Kanban task item."""
        if not title or not title.strip():
            return {"status": "error", "message": "Task title cannot be empty."}

        col = column.lower().strip()
        if col not in self.VALID_COLUMNS:
            col = "todo"

        task_id = f"task_{uuid.uuid4().hex[:8]}"
        now = time.time()
        tags_json = json.dumps(tags or [])

        conn = state_db._get_connection()
        with conn:
            conn.execute("""
                INSERT INTO kanban_tasks (id, title, description, column, tags_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, (task_id, title.strip(), description.strip(), col, tags_json, now, now))

        logger.info(f"📋 Kanban task created [{task_id}]: '{title}' in column '{col}'")
        return {
            "status": "success",
            "task_id": task_id,
            "title": title,
            "column": col,
            "message": f"Created Kanban task '{title}' in '{col}'."
        }

    def update_task(self, task_id: str, column: Optional[str] = None, title: Optional[str] = None, description: Optional[str] = None) -> Dict[str, Any]:
        """Updates status or details of a Kanban task."""
        conn = state_db._get_connection()
        cur = conn.execute("SELECT * FROM kanban_tasks WHERE id = ?", (task_id,))
        row = cur.fetchone()
        if not row:
            return {"status": "error", "message": f"Task '{task_id}' not found."}

        new_col = column.lower().strip() if column and column.lower().strip() in self.VALID_COLUMNS else row["column"]
        new_title = title.strip() if title else row["title"]
        new_desc = description.strip() if description is not None else row["description"]
        now = time.time()

        with conn:
            conn.execute("""
                UPDATE kanban_tasks
                SET title = ?, description = ?, column = ?, updated_at = ?
                WHERE id = ?
            """, (new_title, new_desc, new_col, now, task_id))

        return {
            "status": "success",
            "task_id": task_id,
            "title": new_title,
            "column": new_col,
            "message": f"Updated task '{task_id}' -> Column: '{new_col}'."
        }

    def list_tasks(self, column: Optional[str] = None) -> Dict[str, Any]:
        """Lists tasks on the Kanban board."""
        conn = state_db._get_connection()
        if column and column.lower().strip() in self.VALID_COLUMNS:
            cur = conn.execute("SELECT * FROM kanban_tasks WHERE column = ? ORDER BY created_at ASC", (column.lower().strip(),))
        else:
            cur = conn.execute("SELECT * FROM kanban_tasks ORDER BY created_at ASC")

        tasks = []
        for r in cur:
            tasks.append({
                "id": r["id"],
                "title": r["title"],
                "description": r["description"],
                "column": r["column"],
                "tags": json.loads(r["tags_json"]) if r["tags_json"] else [],
                "created_at": r["created_at"]
            })

        return {
            "status": "success",
            "total_tasks": len(tasks),
            "tasks": tasks
        }

    def delete_task(self, task_id: str) -> Dict[str, Any]:
        """Deletes a task from the board."""
        conn = state_db._get_connection()
        with conn:
            cur = conn.execute("DELETE FROM kanban_tasks WHERE id = ?", (task_id,))
            if cur.rowcount > 0:
                return {"status": "success", "message": f"Deleted task '{task_id}'."}
        return {"status": "error", "message": f"Task '{task_id}' not found."}


# Singleton instance
kanban_board = KanbanBoard()


# ─── Tool Declarations ────────────────────────────────────────────────────────

def kanban_task(
    action: str,
    title: Optional[str] = None,
    description: Optional[str] = None,
    column: Optional[str] = None,
    task_id: Optional[str] = None
) -> Dict[str, Any]:
    """Manage items on the Kanban project and goal tracking board."""
    clean_action = action.strip().lower()

    if clean_action == "create":
        if not title:
            return {"status": "error", "message": "Title is required for create."}
        return kanban_board.create_task(title=title, description=description or "", column=column or "todo")

    elif clean_action == "update":
        if not task_id:
            return {"status": "error", "message": "task_id is required for update."}
        return kanban_board.update_task(task_id=task_id, column=column, title=title, description=description)

    elif clean_action in ("list", "get"):
        return kanban_board.list_tasks(column=column)

    elif clean_action in ("delete", "remove"):
        if not task_id:
            return {"status": "error", "message": "task_id is required for delete."}
        return kanban_board.delete_task(task_id=task_id)

    return {"status": "error", "message": f"Unknown Kanban action '{action}'."}


KANBAN_TOOL_DECLARATIONS = [
    {
        "name": "kanban_task",
        "description": "Manage tasks on the Kanban board (columns: 'backlog', 'todo', 'in_progress', 'review', 'done') to track multi-stage project goals.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["create", "update", "list", "delete"],
                    "description": "Action to perform ('create', 'update', 'list', 'delete')"
                },
                "title": {
                    "type": "string",
                    "description": "Title of the task item"
                },
                "description": {
                    "type": "string",
                    "description": "Detailed notes or acceptance criteria for the task"
                },
                "column": {
                    "type": "string",
                    "enum": ["backlog", "todo", "in_progress", "review", "done"],
                    "description": "Target board column"
                },
                "task_id": {
                    "type": "string",
                    "description": "Unique task ID (required for update and delete)"
                }
            },
            "required": ["action"]
        }
    }
]

KANBAN_TOOL_FUNCTIONS = {
    "kanban_task": kanban_task,
}
