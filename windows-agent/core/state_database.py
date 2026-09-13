"""
MAX AI — Unified State Database
SQLite database with Write-Ahead Logging (WAL) mode, FTS5 full-text search,
structured session logging, memory persistence, and audit logging.
"""

import os
import json
import time
import sqlite3
import logging
import threading
from typing import Dict, Any, List, Optional, Tuple

from config import STATE_DB_PATH, PROJECT_ROOT
from core.security import security_guard

logger = logging.getLogger("MAX.StateDB")


class StateDatabase:
    """
    Thread-safe SQLite state database for MAX Unified Agent.
    """
    _instance: Optional['StateDatabase'] = None
    _lock = threading.Lock()

    def __init__(self, db_path: Optional[str] = None):
        self.db_path = db_path or STATE_DB_PATH
        os.makedirs(os.path.dirname(os.path.abspath(self.db_path)), exist_ok=True)
        self._local = threading.local()
        self._init_db()
        self._migrate_json_memory_if_needed()

    @classmethod
    def get_instance(cls) -> 'StateDatabase':
        with cls._lock:
            if cls._instance is None:
                cls._instance = cls()
            return cls._instance

    def _get_connection(self) -> sqlite3.Connection:
        """Returns thread-local SQLite connection."""
        if not hasattr(self._local, "conn") or self._local.conn is None:
            conn = sqlite3.connect(self.db_path, timeout=30.0)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode=WAL;")
            conn.execute("PRAGMA synchronous=NORMAL;")
            conn.execute("PRAGMA foreign_keys=ON;")
            self._local.conn = conn
        return self._local.conn

    def close(self):
        """Closes thread-local database connection if open."""
        if hasattr(self._local, "conn") and self._local.conn is not None:
            try:
                self._local.conn.close()
            except Exception:
                pass
            self._local.conn = None

    def _init_db(self):
        """Initializes tables and FTS5 full-text search indexes."""
        conn = self._get_connection()
        with conn:
            # 1. Memories Table
            conn.execute("""
                CREATE TABLE IF NOT EXISTS memories (
                    key TEXT PRIMARY KEY,
                    original_key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    category TEXT DEFAULT 'general',
                    source TEXT DEFAULT 'user',
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );
            """)

            # 2. Memories FTS5 Virtual Table
            conn.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
                    key UNINDEXED,
                    original_key,
                    value,
                    tokenize='porter unicode61'
                );
            """)

            # Triggers to keep memories_fts in sync
            conn.execute("""
                CREATE TRIGGER IF NOT EXISTS trg_memories_ai AFTER INSERT ON memories BEGIN
                    INSERT INTO memories_fts(key, original_key, value)
                    VALUES (new.key, new.original_key, new.value);
                END;
            """)
            conn.execute("""
                CREATE TRIGGER IF NOT EXISTS trg_memories_ad AFTER DELETE ON memories BEGIN
                    INSERT INTO memories_fts(memories_fts, key, original_key, value)
                    VALUES('delete', old.key, old.original_key, old.value);
                END;
            """)
            conn.execute("""
                CREATE TRIGGER IF NOT EXISTS trg_memories_au AFTER UPDATE ON memories BEGIN
                    INSERT INTO memories_fts(memories_fts, key, original_key, value)
                    VALUES('delete', old.key, old.original_key, old.value);
                    INSERT INTO memories_fts(key, original_key, value)
                    VALUES (new.key, new.original_key, new.value);
                END;
            """)

            # 3. Scheduled Tasks Table
            conn.execute("""
                CREATE TABLE IF NOT EXISTS scheduled_tasks (
                    job_id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    schedule_expr TEXT NOT NULL,
                    goal TEXT NOT NULL,
                    tool_name TEXT,
                    tool_args_json TEXT,
                    is_active INTEGER DEFAULT 1,
                    last_run_at REAL,
                    next_run_at REAL,
                    run_count INTEGER DEFAULT 0,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );
            """)

            # 4. Audit Log Table
            conn.execute("""
                CREATE TABLE IF NOT EXISTS audit_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp REAL NOT NULL,
                    agent TEXT NOT NULL,
                    tool TEXT NOT NULL,
                    arguments_json TEXT,
                    target_device TEXT,
                    status TEXT NOT NULL,
                    result_summary TEXT,
                    duration_ms INTEGER,
                    confirmed INTEGER DEFAULT 0
                );
            """)

            # 5. Session History Table
            conn.execute("""
                CREATE TABLE IF NOT EXISTS session_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    turn_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    tool_calls_json TEXT,
                    timestamp REAL NOT NULL
                );
            """)

            # 6. Skill Usage Ledger Table
            conn.execute("""
                CREATE TABLE IF NOT EXISTS skill_ledger (
                    name TEXT PRIMARY KEY,
                    usage_count INTEGER DEFAULT 0,
                    failure_count INTEGER DEFAULT 0,
                    last_used_at REAL,
                    lifecycle_state TEXT DEFAULT 'active',
                    is_agent_created INTEGER DEFAULT 0,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );
            """)

            # 7. Skill Provenance Table
            conn.execute("""
                CREATE TABLE IF NOT EXISTS skill_provenance (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    version TEXT DEFAULT '0.1.0',
                    creator_agent TEXT NOT NULL,
                    source_goal TEXT,
                    trajectory_json TEXT,
                    created_at REAL NOT NULL
                );
            """)

            # 8. Persistent Reminders Table (Phase 1 Reliability)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS reminders (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    message TEXT NOT NULL,
                    run_at REAL NOT NULL,
                    recurrence TEXT,
                    task_name TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    payload_json TEXT,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );
            """)
            conn.execute("""
                CREATE INDEX IF NOT EXISTS idx_reminders_status_run_at ON reminders(status, run_at);
            """)

    def _migrate_json_memory_if_needed(self):
        """Migrates legacy storage/memory.json into SQLite if database is empty."""
        try:
            json_file = os.path.join(PROJECT_ROOT, "storage", "memory.json")
            if not os.path.exists(json_file):
                return

            conn = self._get_connection()
            cur = conn.execute("SELECT COUNT(*) as count FROM memories")
            if cur.fetchone()["count"] > 0:
                return  # Already has records

            with open(json_file, "r", encoding="utf-8") as f:
                data = json.load(f)

            now = time.time()
            with conn:
                for k, item in data.items():
                    val = item.get("value", "")
                    orig_k = item.get("original_key", k)
                    conn.execute("""
                        INSERT OR REPLACE INTO memories (key, original_key, value, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                    """, (k.lower(), orig_k, str(val), now, now))

            logger.info(f"💾 Migrated {len(data)} memories from memory.json to SQLite state.db")
        except Exception as e:
            logger.warning(f"Memory migration notice: {e}")

    # ── Memory API ────────────────────────────────────────────────────────────

    def save_memory(self, key: str, value: str, category: str = "general", source: str = "user") -> Dict[str, Any]:
        """Saves or updates a permanent memory fact."""
        clean_key = key.lower().strip().replace(" ", "_")
        safe_value = security_guard.mask_secrets(str(value).strip())
        now = time.time()

        conn = self._get_connection()
        with conn:
            conn.execute("""
                INSERT OR REPLACE INTO memories (key, original_key, value, category, source, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, (clean_key, key.strip(), safe_value, category, source, now, now))

        # Also keep memory.json updated as a human-readable backup
        self._sync_json_backup()

        return {
            "status": "success",
            "message": f"Remembered '{key}': '{safe_value}' in permanent memory.",
            "key": key,
            "value": safe_value
        }

    def recall_memory(self, query: str, limit: int = 10) -> Dict[str, Any]:
        """Searches memories using FTS5 full-text search with fallback to LIKE."""
        if not query or not query.strip():
            return {"status": "info", "message": "Search query cannot be empty.", "results": {}}

        clean_query = query.strip()
        conn = self._get_connection()
        results = {}

        # 1. Try FTS5 Match
        try:
            # Escape FTS5 special characters
            escaped_q = "".join(c if c.isalnum() or c.isspace() else " " for c in clean_query).strip()
            if escaped_q:
                fts_query = f"{escaped_q}*"
                cur = conn.execute("""
                    SELECT m.original_key, m.value FROM memories m
                    JOIN memories_fts f ON m.key = f.key
                    WHERE memories_fts MATCH ?
                    LIMIT ?
                """, (fts_query, limit))
                for row in cur:
                    results[row["original_key"]] = row["value"]
        except Exception as e:
            logger.debug(f"FTS5 query fallback: {e}")

        # 2. Substring Fallback if FTS returned nothing
        if not results:
            cur = conn.execute("""
                SELECT original_key, value FROM memories
                WHERE key LIKE ? OR value LIKE ? OR original_key LIKE ?
                LIMIT ?
            """, (f"%{clean_query}%", f"%{clean_query}%", f"%{clean_query}%", limit))
            for row in cur:
                results[row["original_key"]] = row["value"]

        if results:
            return {
                "status": "success",
                "message": f"Found {len(results)} memory entry(ies) for '{query}'",
                "results": results
            }
        return {
            "status": "info",
            "message": f"No memories found matching '{query}'.",
            "results": {}
        }

    def list_all_memories(self) -> Dict[str, Any]:
        """Returns all stored permanent memories."""
        conn = self._get_connection()
        cur = conn.execute("SELECT original_key, value FROM memories ORDER BY key ASC")
        results = {row["original_key"]: row["value"] for row in cur}
        return {
            "status": "success",
            "total_memories": len(results),
            "memories": results
        }

    def delete_memory(self, key: str) -> Dict[str, Any]:
        """Deletes a memory fact by key."""
        clean_key = key.lower().strip().replace(" ", "_")
        conn = self._get_connection()
        with conn:
            cur = conn.execute("DELETE FROM memories WHERE key = ? OR original_key = ?", (clean_key, key.strip()))
            deleted = cur.rowcount > 0

        self._sync_json_backup()
        if deleted:
            return {"status": "success", "message": f"Forgot memory for '{key}'."}
        return {"status": "info", "message": f"No memory found matching '{key}'."}

    def get_user_profile_summary(self) -> str:
        """Extracts user profile facts (preferences, habits, communication style, identity)."""
        conn = self._get_connection()
        cur = conn.execute("""
            SELECT original_key, value FROM memories 
            WHERE category IN ('user_profile', 'user', 'preference', 'habit', 'identity', 'contact')
               OR key LIKE 'user_%' OR key LIKE '%name' OR key LIKE '%preference%'
            ORDER BY key ASC
        """)
        rows = list(cur)
        if not rows:
            return ""
        lines = [f"- {r['original_key']}: {r['value']}" for r in rows]
        return "\n".join(lines)

    def get_environment_summary(self) -> str:
        """Extracts environment facts (workstation, paths, installed devices, system settings)."""
        conn = self._get_connection()
        cur = conn.execute("""
            SELECT original_key, value FROM memories 
            WHERE category IN ('environment', 'system', 'device', 'workstation', 'path', 'hardware')
               OR key LIKE 'device_%' OR key LIKE 'path_%' OR key LIKE 'sys_%'
            ORDER BY key ASC
        """)
        rows = list(cur)
        if not rows:
            return ""
        lines = [f"- {r['original_key']}: {r['value']}" for r in rows]
        return "\n".join(lines)

    def get_general_summary(self) -> str:
        """Extracts general facts not categorized under user profile or environment."""
        conn = self._get_connection()
        cur = conn.execute("""
            SELECT original_key, value FROM memories 
            WHERE category NOT IN ('user_profile', 'user', 'preference', 'habit', 'identity', 'contact',
                                   'environment', 'system', 'device', 'workstation', 'path', 'hardware')
               AND key NOT LIKE 'user_%' AND key NOT LIKE '%name' AND key NOT LIKE '%preference%'
               AND key NOT LIKE 'device_%' AND key NOT LIKE 'path_%' AND key NOT LIKE 'sys_%'
            ORDER BY key ASC
        """)
        rows = list(cur)
        if not rows:
            return ""
        lines = [f"- {r['original_key']}: {r['value']}" for r in rows]
        return "\n".join(lines)

    def get_summary_text(self) -> str:
        """Formats all memories into structured, tagged prompt snapshots (Honcho style)."""
        user_prof = self.get_user_profile_summary()
        env_facts = self.get_environment_summary()
        gen_facts = self.get_general_summary()
        
        conn = self._get_connection()
        cur = conn.execute("SELECT original_key, value FROM memories ORDER BY key ASC")
        all_rows = list(cur)
        if not all_rows:
            return "No saved memories yet."

        parts = []
        if user_prof:
            parts.append(f"<USER_PROFILE>\n{user_prof}\n</USER_PROFILE>")
        if env_facts:
            parts.append(f"<ENVIRONMENT_FACTS>\n{env_facts}\n</ENVIRONMENT_FACTS>")
        if gen_facts:
            # Also include general facts or flat list if only general facts exist
            if not user_prof and not env_facts:
                return gen_facts
            parts.append(f"<GENERAL_MEMORIES>\n{gen_facts}\n</GENERAL_MEMORIES>")
        
        return "\n\n".join(parts) if parts else "\n".join([f"- {r['original_key']}: {r['value']}" for r in all_rows])

    # ── Skill Usage Ledger API ────────────────────────────────────────────────

    def record_skill_usage(self, skill_name: str, success: bool = True, is_agent_created: bool = False):
        """Records skill invocation, failure counts, and timestamps in skill_ledger."""
        clean_name = skill_name.strip().lower()
        now = time.time()
        conn = self._get_connection()
        with conn:
            conn.execute("""
                INSERT INTO skill_ledger (name, usage_count, failure_count, last_used_at, lifecycle_state, is_agent_created, created_at, updated_at)
                VALUES (?, 1, ?, ?, 'active', ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                    usage_count = usage_count + 1,
                    failure_count = failure_count + ?,
                    last_used_at = ?,
                    updated_at = ?
            """, (clean_name, 0 if success else 1, now, 1 if is_agent_created else 0, now, now,
                  0 if success else 1, now, now))

    def record_skill_provenance(self, skill_name: str, source_goal: str, creator_agent: str = "max_agent", version: str = "0.1.0", trajectory: Optional[List[Dict[str, Any]]] = None):
        """Records provenance information for an agent-created skill."""
        clean_name = skill_name.strip().lower()
        traj_json = json.dumps(trajectory, default=str) if trajectory else "{}"
        conn = self._get_connection()
        with conn:
            conn.execute("""
                INSERT INTO skill_provenance (name, version, creator_agent, source_goal, trajectory_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (clean_name, version, creator_agent, source_goal, traj_json, time.time()))

    def get_skill_stats(self, skill_name: str) -> Optional[Dict[str, Any]]:
        """Retrieves usage statistics and lifecycle state for a given skill."""
        clean_name = skill_name.strip().lower()
        conn = self._get_connection()
        cur = conn.execute("SELECT * FROM skill_ledger WHERE name = ?", (clean_name,))
        row = cur.fetchone()
        if not row:
            return None
        return dict(row)

    def list_skill_ledger(self) -> List[Dict[str, Any]]:
        """Returns all entries in the skill ledger."""
        conn = self._get_connection()
        cur = conn.execute("SELECT * FROM skill_ledger ORDER BY usage_count DESC, last_used_at DESC")
        return [dict(r) for r in cur]

    def set_skill_lifecycle_state(self, skill_name: str, state: str) -> bool:
        """Updates the lifecycle state (active, pinned, stale, archived) of a skill."""
        clean_name = skill_name.strip().lower()
        clean_state = state.strip().lower()
        now = time.time()
        conn = self._get_connection()
        with conn:
            cur = conn.execute("""
                UPDATE skill_ledger 
                SET lifecycle_state = ?, updated_at = ?
                WHERE name = ?
            """, (clean_state, now, clean_name))
            return cur.rowcount > 0

    def _sync_json_backup(self):
        """Dumps SQLite memories to storage/memory.json for backward compatibility."""
        try:
            conn = self._get_connection()
            cur = conn.execute("SELECT key, original_key, value FROM memories ORDER BY key ASC")
            data = {}
            for row in cur:
                data[row["key"]] = {
                    "original_key": row["original_key"],
                    "value": row["value"]
                }
            json_file = os.path.join(PROJECT_ROOT, "storage", "memory.json")
            with open(json_file, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, ensure_ascii=False)
        except Exception as e:
            logger.debug(f"JSON backup sync notice: {e}")

    # ── Reminder Persistence API (Phase 1 Reliability) ────────────────────────

    def save_reminder(
        self,
        reminder_id: str,
        title: str,
        message: str,
        run_at: float,
        task_name: str,
        recurrence: Optional[str] = None,
        status: str = "PENDING",
        payload_json: Optional[str] = None,
    ) -> bool:
        """Saves or updates a reminder record in SQLite."""
        now = time.time()
        conn = self._get_connection()
        try:
            with conn:
                conn.execute("""
                    INSERT OR REPLACE INTO reminders (
                        id, title, message, run_at, recurrence, task_name, status, payload_json, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, COALESCE((SELECT created_at FROM reminders WHERE id = ?), ?), ?)
                """, (
                    reminder_id, title, message, run_at, recurrence, task_name, status,
                    payload_json, reminder_id, now, now
                ))
            return True
        except Exception as e:
            logger.error(f"Error saving reminder '{reminder_id}' to SQLite: {e}")
            return False

    def get_reminder(self, reminder_id: str) -> Optional[Dict[str, Any]]:
        """Retrieves a single reminder by ID."""
        conn = self._get_connection()
        cur = conn.execute("SELECT * FROM reminders WHERE id = ?", (reminder_id,))
        row = cur.fetchone()
        return dict(row) if row else None

    def list_active_reminders(self) -> List[Dict[str, Any]]:
        """Returns all reminders with status 'SCHEDULED' or 'PENDING'."""
        conn = self._get_connection()
        cur = conn.execute("""
            SELECT * FROM reminders
            WHERE status IN ('SCHEDULED', 'PENDING')
            ORDER BY run_at ASC
        """)
        return [dict(r) for r in cur.fetchall()]

    def list_all_reminders(self, limit: int = 50) -> List[Dict[str, Any]]:
        """Returns all reminders ordered by newest first."""
        conn = self._get_connection()
        cur = conn.execute("""
            SELECT * FROM reminders
            ORDER BY created_at DESC
            LIMIT ?
        """, (limit,))
        return [dict(r) for r in cur.fetchall()]

    def update_reminder_status(
        self,
        reminder_id: str,
        new_status: str,
        expected_status: Optional[str] = None,
    ) -> bool:
        """
        Updates the status of a reminder.
        If expected_status is provided, performs an atomic conditional update.
        """
        now = time.time()
        conn = self._get_connection()
        try:
            with conn:
                if expected_status:
                    cur = conn.execute("""
                        UPDATE reminders
                        SET status = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                    """, (new_status, now, reminder_id, expected_status))
                else:
                    cur = conn.execute("""
                        UPDATE reminders
                        SET status = ?, updated_at = ?
                        WHERE id = ?
                    """, (new_status, now, reminder_id))
                return cur.rowcount > 0
        except Exception as e:
            logger.error(f"Error updating reminder '{reminder_id}' status to '{new_status}': {e}")
            return False

    def delete_reminder_record(self, reminder_id: str) -> bool:
        """Deletes a reminder row from SQLite."""
        conn = self._get_connection()
        try:
            with conn:
                cur = conn.execute("DELETE FROM reminders WHERE id = ?", (reminder_id,))
                return cur.rowcount > 0
        except Exception as e:
            logger.error(f"Error deleting reminder '{reminder_id}' record: {e}")
            return False

    # ── Audit Logging API ─────────────────────────────────────────────────────

    def log_audit(
        self,
        agent: str,
        tool: str,
        arguments: Optional[Dict[str, Any]] = None,
        target_device: str = "windows",
        status: str = "success",
        result_summary: str = "",
        duration_ms: int = 0,
        confirmed: bool = False
    ):
        """Records a structured security audit log entry."""
        try:
            args_json = json.dumps(arguments, default=str) if arguments else "{}"
            summary_clean = str(result_summary)[:500] if result_summary else ""
            conn = self._get_connection()
            with conn:
                conn.execute("""
                    INSERT INTO audit_logs (timestamp, agent, tool, arguments_json, target_device, status, result_summary, duration_ms, confirmed)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (time.time(), agent, tool, args_json, target_device, status, summary_clean, duration_ms, 1 if confirmed else 0))
        except Exception as e:
            logger.debug(f"Audit log write notice: {e}")


# Singleton instance
state_db = StateDatabase.get_instance()
