"""
MAX AI — Persistent Cron Scheduler
SQLite-backed cron scheduler supporting standard 5-field cron syntax and interval expressions
with persistent tracking across restarts and non-blocking background evaluation.
"""

import re
import time
import uuid
import json
import logging
import threading
from typing import Dict, Any, List, Optional
from datetime import datetime, timezone

try:
    from croniter import croniter
except ImportError:
    croniter = None

from core.state_database import state_db

logger = logging.getLogger("MAX.CronScheduler")


def calculate_next_run(schedule_expr: str, base_time: Optional[float] = None) -> Optional[float]:
    """
    Computes the next execution timestamp from a cron or interval expression.
    """
    now = base_time or time.time()
    clean_expr = schedule_expr.strip().lower()

    # 1. Interval pattern (e.g. 'every 10m', 'every 2h', 'every 30s', '10m', '1h')
    interval_match = re.match(r"^(?:every\s+)?(\d+)\s*(s|sec|m|min|h|hr|d|day)s?$", clean_expr)
    if interval_match:
        val = int(interval_match.group(1))
        unit = interval_match.group(2)
        multiplier = 1
        if unit.startswith("m"):
            multiplier = 60
        elif unit.startswith("h"):
            multiplier = 3600
        elif unit.startswith("d"):
            multiplier = 86400
        return now + (val * multiplier)

    # 2. Standard 5-field cron expression (e.g. '*/5 * * * *', '0 9 * * 1-5')
    if croniter is not None:
        try:
            dt_base = datetime.fromtimestamp(now, tz=timezone.utc)
            iter_cron = croniter(schedule_expr, dt_base)
            next_dt = iter_cron.get_next(datetime)
            return next_dt.timestamp()
        except Exception as e:
            logger.debug(f"Cron expression parse error for '{schedule_expr}': {e}")
            return None
    else:
        # Simple fallback for 5-field expression when croniter is not installed
        parts = clean_expr.split()
        if len(parts) == 5:
            return now + 3600.0  # Safe 1-hour placeholder
        return None


class CronScheduler:
    """
    Authoritative persistent background scheduler.
    """
    _instance: Optional['CronScheduler'] = None
    _lock = threading.Lock()

    def __init__(self):
        self.state_db = state_db
        self._running = False
        self._worker_thread: Optional[threading.Thread] = None

    @classmethod
    def get_instance(cls) -> 'CronScheduler':
        with cls._lock:
            if cls._instance is None:
                cls._instance = cls()
            return cls._instance

    def start(self):
        """Starts the background scheduler loop."""
        if self._running:
            return
        self._running = True
        self._worker_thread = threading.Thread(target=self._scheduler_loop, name="MAXCronWorker", daemon=True)
        self._worker_thread.start()
        logger.info("⏱️ Background Cron Scheduler started")

    def stop(self):
        """Stops the scheduler loop."""
        self._running = False

    def _scheduler_loop(self):
        """Background loop checking and executing due jobs every 10 seconds."""
        curator_tick_counter = 0
        while self._running:
            try:
                self._check_and_run_due_jobs()
                
                # Periodic skill curation check every ~1 hour (360 iterations * 10s)
                curator_tick_counter += 1
                if curator_tick_counter >= 360:
                    curator_tick_counter = 0
                    try:
                        from core.learning_loop import BackgroundCurator
                        BackgroundCurator.run_curation_pass()
                    except Exception as ce:
                        logger.debug(f"Curator periodic tick notice: {ce}")
            except Exception as e:
                logger.error(f"Error in scheduler tick: {e}", exc_info=True)
            time.sleep(10.0)

    def _check_and_run_due_jobs(self):
        """Finds and executes any active jobs whose next_run_at <= current time."""
        now = time.time()
        conn = self.state_db._get_connection()
        cur = conn.execute("""
            SELECT * FROM scheduled_tasks
            WHERE is_active = 1 AND next_run_at IS NOT NULL AND next_run_at <= ?
        """, (now,))
        due_jobs = list(cur)

        for job in due_jobs:
            job_id = job["job_id"]
            name = job["name"]
            goal = job["goal"]
            sched = job["schedule_expr"]
            tool_name = job["tool_name"]
            tool_args_str = job["tool_args_json"]

            logger.info(f"⏰ Cron job due: '{name}' ({job_id}) -> Goal: '{goal}'")

            # Execute job in separate thread to prevent scheduler tick delay
            threading.Thread(
                target=self._execute_job_payload,
                args=(job_id, name, goal, tool_name, tool_args_str),
                name=f"CronJob-{job_id[:6]}",
                daemon=True
            ).start()

            # Advance next_run_at
            next_run = calculate_next_run(sched, base_time=now)
            with conn:
                conn.execute("""
                    UPDATE scheduled_tasks
                    SET last_run_at = ?, next_run_at = ?, run_count = run_count + 1, updated_at = ?
                    WHERE job_id = ?
                """, (now, next_run, now, job_id))

    def _execute_job_payload(self, job_id: str, name: str, goal: str, tool_name: Optional[str], tool_args_str: Optional[str]):
        """Executes the task action via UnifiedToolRegistry or Orchestrator."""
        start_t = time.time()
        status = "success"
        result_msg = ""
        try:
            from core.unified_tool_registry import unified_tool_registry
            if tool_name:
                args = json.loads(tool_args_str) if tool_args_str else {}
                res = unified_tool_registry.execute(tool_name, args)
                result_msg = str(res)
            else:
                # Goal orchestration
                from orchestrator.router import orchestration_router
                import asyncio
                res = asyncio.run(orchestration_router.execute_goal(goal, source_device="cron", async_execution=False))
                result_msg = str(res)
        except Exception as e:
            status = "failed"
            result_msg = str(e)
            logger.error(f"❌ Cron job execution failed for '{name}': {e}")

        dur_ms = int((time.time() - start_t) * 1000)
        self.state_db.log_audit(
            agent="cron_scheduler",
            tool=tool_name or "execute_goal",
            arguments={"goal": goal, "job_id": job_id},
            status=status,
            result_summary=result_msg[:200],
            duration_ms=dur_ms
        )

    # ── CRUD Operations ───────────────────────────────────────────────────────

    def add_job(
        self,
        name: str,
        schedule_expr: str,
        goal: str,
        tool_name: Optional[str] = None,
        tool_args: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        """Registers a new scheduled job."""
        if not name or not schedule_expr or not goal:
            return {"status": "error", "message": "Name, schedule expression, and goal are required."}

        next_run = calculate_next_run(schedule_expr)
        if next_run is None:
            return {"status": "error", "message": f"Invalid schedule expression '{schedule_expr}'. Use standard cron (e.g. '0 9 * * *') or interval ('every 1h')."}

        job_id = f"job_{uuid.uuid4().hex[:8]}"
        now = time.time()
        tool_args_json = json.dumps(tool_args) if tool_args else "{}"

        conn = self.state_db._get_connection()
        with conn:
            conn.execute("""
                INSERT INTO scheduled_tasks (job_id, name, schedule_expr, goal, tool_name, tool_args_json, is_active, last_run_at, next_run_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, NULL, ?, ?, ?)
            """, (job_id, name.strip(), schedule_expr.strip(), goal.strip(), tool_name, tool_args_json, next_run, now, now))

        next_dt_str = datetime.fromtimestamp(next_run, tz=timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')
        return {
            "status": "success",
            "message": f"Scheduled job '{name}' created ({job_id}). Next execution at {next_dt_str}.",
            "job_id": job_id,
            "next_run_at": next_run,
            "next_run_formatted": next_dt_str
        }

    def list_jobs(self) -> Dict[str, Any]:
        """Lists all registered cron jobs and their statuses."""
        conn = self.state_db._get_connection()
        cur = conn.execute("SELECT * FROM scheduled_tasks ORDER BY created_at DESC")
        jobs = []
        for row in cur:
            next_str = datetime.fromtimestamp(row["next_run_at"], tz=timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC') if row["next_run_at"] else "None"
            last_str = datetime.fromtimestamp(row["last_run_at"], tz=timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC') if row["last_run_at"] else "Never"
            jobs.append({
                "job_id": row["job_id"],
                "name": row["name"],
                "schedule": row["schedule_expr"],
                "goal": row["goal"],
                "is_active": bool(row["is_active"]),
                "last_run": last_str,
                "next_run": next_str,
                "run_count": row["run_count"]
            })

        return {
            "status": "success",
            "total_jobs": len(jobs),
            "jobs": jobs
        }

    def pause_job(self, job_id: str) -> Dict[str, Any]:
        """Pauses a scheduled job."""
        conn = self.state_db._get_connection()
        with conn:
            cur = conn.execute("UPDATE scheduled_tasks SET is_active = 0, updated_at = ? WHERE job_id = ?", (time.time(), job_id))
            if cur.rowcount > 0:
                return {"status": "success", "message": f"Job '{job_id}' paused."}
        return {"status": "error", "message": f"Job '{job_id}' not found."}

    def resume_job(self, job_id: str) -> Dict[str, Any]:
        """Resumes a paused job and recalibrates next run time."""
        conn = self.state_db._get_connection()
        cur = conn.execute("SELECT schedule_expr FROM scheduled_tasks WHERE job_id = ?", (job_id,))
        row = cur.fetchone()
        if not row:
            return {"status": "error", "message": f"Job '{job_id}' not found."}

        next_run = calculate_next_run(row["schedule_expr"])
        with conn:
            conn.execute("UPDATE scheduled_tasks SET is_active = 1, next_run_at = ?, updated_at = ? WHERE job_id = ?", (next_run, time.time(), job_id))
        return {"status": "success", "message": f"Job '{job_id}' resumed. Next run at {datetime.fromtimestamp(next_run, tz=timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}."}

    def delete_job(self, job_id: str) -> Dict[str, Any]:
        """Deletes a scheduled job."""
        conn = self.state_db._get_connection()
        with conn:
            cur = conn.execute("DELETE FROM scheduled_tasks WHERE job_id = ?", (job_id,))
            if cur.rowcount > 0:
                return {"status": "success", "message": f"Job '{job_id}' deleted."}
        return {"status": "error", "message": f"Job '{job_id}' not found."}


# Global singleton instance
cron_scheduler = CronScheduler.get_instance()


# ─── Tool Wrappers & Declarations ─────────────────────────────────────────────

def cronjob(
    action: str,
    name: Optional[str] = None,
    schedule: Optional[str] = None,
    goal: Optional[str] = None,
    job_id: Optional[str] = None
) -> Dict[str, Any]:
    """Manage background scheduled jobs and automated tasks."""
    clean_action = action.strip().lower()

    if clean_action == "create":
        if not name or not schedule or not goal:
            return {"status": "error", "message": "Parameters 'name', 'schedule', and 'goal' are required for create."}
        return cron_scheduler.add_job(name=name, schedule_expr=schedule, goal=goal)

    elif clean_action in ("list", "list_jobs"):
        return cron_scheduler.list_jobs()

    elif clean_action == "pause":
        if not job_id:
            return {"status": "error", "message": "Parameter 'job_id' is required for pause."}
        return cron_scheduler.pause_job(job_id=job_id)

    elif clean_action == "resume":
        if not job_id:
            return {"status": "error", "message": "Parameter 'job_id' is required for resume."}
        return cron_scheduler.resume_job(job_id=job_id)

    elif clean_action in ("delete", "remove"):
        if not job_id:
            return {"status": "error", "message": "Parameter 'job_id' is required for delete."}
        return cron_scheduler.delete_job(job_id=job_id)

    return {"status": "error", "message": f"Unknown cron action '{action}'."}


CRON_TOOL_DECLARATIONS = [
    {
        "name": "cronjob",
        "description": "Manage scheduled background jobs, recurring timers, and automated tasks on Windows (e.g. check jobs every morning, run routine every 1h).",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["create", "list", "pause", "resume", "delete"],
                    "description": "Action to perform ('create', 'list', 'pause', 'resume', 'delete')"
                },
                "name": {
                    "type": "string",
                    "description": "Human-readable label for the scheduled task"
                },
                "schedule": {
                    "type": "string",
                    "description": "Standard 5-field cron (e.g. '0 9 * * *', '*/30 * * * *') or interval expression (e.g. 'every 1h', 'every 10m')"
                },
                "goal": {
                    "type": "string",
                    "description": "The exact natural language goal or task command to execute when the schedule triggers"
                },
                "job_id": {
                    "type": "string",
                    "description": "The unique job ID (required for pause, resume, delete)"
                }
            },
            "required": ["action"]
        }
    }
]

CRON_TOOL_FUNCTIONS = {
    "cronjob": cronjob,
}
