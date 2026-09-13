"""
MAX AI — Persistent Task Scheduler Adapter
Orchestrates SQLite metadata with Windows Task Scheduler (schtasks.exe)
for durable, reboot-surviving reminders with transactional creation,
crash recovery reconciliation, and an idempotent runner.
"""

import os
import csv
import sys
import json
import time
import uuid
import shutil
import logging
import datetime
import subprocess
import threading
from pathlib import Path
from typing import Dict, Any, List, Optional, Tuple

from config import STATE_DB_PATH
from core.state_database import state_db

logger = logging.getLogger("MAXWindowsAgent.PersistentScheduler")

BASE_AGENT_DIR = Path.home() / ".max_agent"
REMINDERS_DIR = BASE_AGENT_DIR / "reminders"
SCHEDULER_DIR = BASE_AGENT_DIR / "scheduler"
RUNNER_SCRIPT_PATH = SCHEDULER_DIR / "reminder_runner.py"

_CNW: dict = (
    {"creationflags": subprocess.CREATE_NO_WINDOW}
    if sys.platform == "win32" else {}
)


def _get_python_executable() -> str:
    """Returns pythonw.exe if available to avoid popups, otherwise sys.executable."""
    if sys.platform == "win32":
        py_dir = Path(sys.executable).parent
        pythonw = py_dir / "pythonw.exe"
        if pythonw.exists():
            return str(pythonw)
    return sys.executable


def _ensure_directories_and_runner():
    """Ensures ~/.max_agent/reminders and ~/.max_agent/scheduler exist, and generates reminder_runner.py."""
    REMINDERS_DIR.mkdir(parents=True, exist_ok=True)
    SCHEDULER_DIR.mkdir(parents=True, exist_ok=True)

    db_path_literal = repr(str(STATE_DB_PATH))
    runner_code = '''# MAX AI — Idempotent Reminder Runner
# Invoked by Windows Task Scheduler: python reminder_runner.py --id <reminder_id>

import os
import sys
import json
import time
import sqlite3
import argparse
from pathlib import Path

STATE_DB_PATH = __STATE_DB_PATH__
REMINDERS_DIR = Path.home() / ".max_agent" / "reminders"


def _show_notification(title: str, message: str):
    notified = False
    
    # 1. PowerShell WinRT Toast Notification
    try:
        title_esc = str(title).replace("'", "''").replace('"', '`"')
        msg_esc = str(message).replace("'", "''").replace('"', '`"')
        ps_cmd = (
            "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null; "
            "$template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02); "
            "$text = $template.GetElementsByTagName('text'); "
            f"$text[0].AppendChild($template.CreateTextNode('{title_esc}')) | Out-Null; "
            f"$text[1].AppendChild($template.CreateTextNode('{msg_esc}')) | Out-Null; "
            "$notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('MAX'); "
            "$notifier.Show([Windows.UI.Notifications.ToastNotification]::new($template))"
        )
        import subprocess
        res = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps_cmd], capture_output=True, timeout=10)
        if res.returncode == 0:
            notified = True
    except Exception:
        pass

    # 2. win10toast fallback
    if not notified:
        try:
            from win10toast import ToastNotifier
            ToastNotifier().show_toast(title, message, duration=15, threaded=False)
            notified = True
        except Exception:
            pass

    # 3. msg.exe fallback
    if not notified:
        try:
            import subprocess
            subprocess.run(["msg", "*", "/TIME:30", f"{title}: {message}"], check=False)
            notified = True
        except Exception:
            pass

    # 4. Message Box fallback
    if not notified:
        try:
            import subprocess
            msg_clean = str(message).replace('"', '`"')
            t_clean = str(title).replace('"', '`"')
            subprocess.run(["powershell", "-NoProfile", "-Command", f'[System.Windows.MessageBox]::Show("{msg_clean}", "{t_clean}")'], check=False)
        except Exception:
            pass

    # Audio chime
    try:
        import winsound
        for freq in (800, 1000, 1200):
            winsound.Beep(freq, 150)
            time.sleep(0.05)
    except Exception:
        pass


def main():
    parser = argparse.ArgumentParser(description="MAX Reminder Runner")
    parser.add_argument("--id", required=True, help="Reminder ID")
    args = parser.parse_args()
    reminder_id = args.id.strip()

    if not reminder_id:
        sys.exit(1)

    # 1. Atomic SQLite update: Transition SCHEDULED -> COMPLETED
    now = time.time()
    title = "MAX Reminder"
    message = "You have a scheduled reminder."

    try:
        conn = sqlite3.connect(STATE_DB_PATH, timeout=15.0)
        conn.row_factory = sqlite3.Row
        with conn:
            cur = conn.execute("""
                UPDATE reminders
                SET status = 'COMPLETED', updated_at = ?
                WHERE id = ? AND status = 'SCHEDULED'
            """, (now, reminder_id))
            if cur.rowcount == 0:
                # Already processed, cancelled, or missing — exit quietly
                sys.exit(0)

            # Retrieve details for display
            row = conn.execute("SELECT * FROM reminders WHERE id = ?", (reminder_id,)).fetchone()
            if row:
                title = row["title"] or title
                message = row["message"] or message
    except Exception as e:
        # Fallback to local JSON payload if DB is locked
        pass

    # 2. Try payload file if present
    payload_file = REMINDERS_DIR / f"{reminder_id}.json"
    if payload_file.exists():
        try:
            data = json.loads(payload_file.read_text(encoding="utf-8"))
            title = data.get("title", title)
            message = data.get("message", message)
        except Exception:
            pass

    # 3. Fire notification & audio
    _show_notification(title, message)

    # 4. Clean up payload JSON
    try:
        if payload_file.exists():
            payload_file.unlink()
    except Exception:
        pass


if __name__ == "__main__":
    main()
'''.replace("__STATE_DB_PATH__", db_path_literal)
    try:
        RUNNER_SCRIPT_PATH.write_text(runner_code, encoding="utf-8")
        logger.debug(f"Ensured reminder runner script at: {RUNNER_SCRIPT_PATH}")
    except Exception as e:
        logger.warning(f"Notice writing reminder_runner.py: {e}")


class PersistentScheduler:
    """
    Authoritative Windows Task Scheduler (schtasks) adapter with SQLite source of truth.
    """
    _instance: Optional['PersistentScheduler'] = None
    _lock = threading.Lock()

    def __init__(self):
        _ensure_directories_and_runner()

    @classmethod
    def get_instance(cls) -> 'PersistentScheduler':
        with cls._lock:
            if cls._instance is None:
                cls._instance = cls()
            return cls._instance

    def set_reminder(
        self,
        message: str,
        minutes: Optional[int] = None,
        run_at_timestamp: Optional[float] = None,
        title: str = "MAX Reminder",
        recurrence: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Creates a persistent reminder via Windows Task Scheduler and SQLite.
        
        Args:
            message: Text message of the reminder.
            minutes: Number of minutes from now (e.g. 5, 30, 60).
            run_at_timestamp: Specific unix timestamp to trigger.
            title: Title for the notification.
            recurrence: Optional recurrence ('daily', 'weekly', etc.).
        """
        now = time.time()
        clean_msg = str(message or "").strip()
        if not clean_msg:
            return {"status": "error", "message": "Reminder message cannot be empty."}

        # Determine target timestamp
        if run_at_timestamp is not None and run_at_timestamp > now:
            target_time = run_at_timestamp
        elif minutes is not None and int(minutes) > 0:
            target_time = now + (int(minutes) * 60)
        else:
            target_time = now + 60  # Default 1 minute

        dt_target = datetime.datetime.fromtimestamp(target_time)
        time_str = dt_target.strftime("%H:%M")
        date_str = dt_target.strftime("%m/%d/%Y")
        readable_trigger = dt_target.strftime("%I:%M %p on %b %d, %Y")

        reminder_id = f"rem_{uuid.uuid4().hex[:12]}"
        task_name = f"MAX_Reminder_{reminder_id}"

        payload = {
            "id": reminder_id,
            "title": title,
            "message": clean_msg,
            "run_at": target_time,
            "recurrence": recurrence,
            "task_name": task_name,
        }
        payload_json = json.dumps(payload, ensure_ascii=False)

        # 1. Insert into SQLite as PENDING
        saved = state_db.save_reminder(
            reminder_id=reminder_id,
            title=title,
            message=clean_msg,
            run_at=target_time,
            task_name=task_name,
            recurrence=recurrence,
            status="PENDING",
            payload_json=payload_json,
        )
        if not saved:
            return {"status": "error", "message": "Failed to record reminder in state database."}

        # 2. Write payload JSON
        payload_file = REMINDERS_DIR / f"{reminder_id}.json"
        try:
            payload_file.write_text(payload_json, encoding="utf-8")
        except Exception as pe:
            logger.warning(f"Notice saving payload file: {pe}")

        # 3. Register with Windows schtasks
        if sys.platform == "win32":
            py_exe = _get_python_executable()
            runner_cmd = f'"{py_exe}" "{str(RUNNER_SCRIPT_PATH)}" --id {reminder_id}'

            cmd = [
                "schtasks", "/create",
                "/tn", task_name,
                "/tr", runner_cmd,
                "/sc", "once",
                "/st", time_str,
                "/sd", date_str,
                "/f"
            ]

            try:
                res = subprocess.run(cmd, capture_output=True, text=True, timeout=10, **_CNW)
                if res.returncode == 0:
                    state_db.update_reminder_status(reminder_id, "SCHEDULED")
                    logger.info(f"⏰ Persistent reminder scheduled: '{clean_msg}' at {readable_trigger} (Task: {task_name})")
                    return {
                        "status": "success",
                        "reminder_id": reminder_id,
                        "task_name": task_name,
                        "title": title,
                        "message": f"Reminder set for {readable_trigger}: '{clean_msg}'",
                        "run_at": target_time,
                        "run_at_str": readable_trigger,
                    }
                else:
                    err_msg = res.stderr.strip() or res.stdout.strip() or "schtasks returned non-zero"
                    state_db.update_reminder_status(reminder_id, "FAILED")
                    logger.error(f"schtasks creation failed for {task_name}: {err_msg}")
                    return {
                        "status": "error",
                        "message": f"Could not register reminder with Windows Task Scheduler: {err_msg}",
                        "reminder_id": reminder_id,
                    }
            except Exception as se:
                state_db.update_reminder_status(reminder_id, "FAILED")
                logger.error(f"Exception invoking schtasks: {se}")
                return {"status": "error", "message": f"Exception scheduling reminder: {se}"}
        else:
            # Fallback for non-Windows development
            state_db.update_reminder_status(reminder_id, "SCHEDULED")
            return {
                "status": "success",
                "reminder_id": reminder_id,
                "task_name": task_name,
                "title": title,
                "message": f"Reminder recorded for {readable_trigger} (simulated non-Windows)",
                "run_at": target_time,
                "run_at_str": readable_trigger,
            }

    def cancel_reminder(self, reminder_id: str) -> Dict[str, Any]:
        """
        Cancels a scheduled reminder by ID in both SQLite and Windows schtasks.
        """
        clean_id = reminder_id.strip()
        rec = state_db.get_reminder(clean_id)
        if not rec:
            return {"status": "error", "message": f"Reminder '{clean_id}' not found."}

        if rec["status"] not in ("SCHEDULED", "PENDING"):
            return {
                "status": "info",
                "message": f"Reminder '{clean_id}' is already {rec['status']}.",
            }

        task_name = rec["task_name"]

        # Transactional cancellation: PENDING -> schtasks delete -> CANCELLED
        state_db.update_reminder_status(clean_id, "CANCELLING")

        if sys.platform == "win32":
            cmd = ["schtasks", "/delete", "/tn", task_name, "/f"]
            try:
                subprocess.run(cmd, capture_output=True, text=True, timeout=10, **_CNW)
            except Exception as e:
                logger.warning(f"Notice deleting task from schtasks: {e}")

        state_db.update_reminder_status(clean_id, "CANCELLED")

        # Cleanup payload file
        payload_file = REMINDERS_DIR / f"{clean_id}.json"
        if payload_file.exists():
            try:
                payload_file.unlink()
            except Exception:
                pass

        logger.info(f"🚫 Cancelled reminder: {clean_id} ({task_name})")
        return {
            "status": "success",
            "message": f"Cancelled reminder '{clean_id}' ('{rec['message']}').",
            "reminder_id": clean_id,
        }

    def list_reminders(self, active_only: bool = True) -> Dict[str, Any]:
        """
        Lists reminders from SQLite with formatted dates and status.
        """
        if active_only:
            items = state_db.list_active_reminders()
        else:
            items = state_db.list_all_reminders()

        formatted = []
        now = time.time()
        for r in items:
            run_at = r.get("run_at", 0)
            dt = datetime.datetime.fromtimestamp(run_at) if run_at else None
            formatted.append({
                "id": r["id"],
                "title": r.get("title", "MAX Reminder"),
                "message": r.get("message", ""),
                "status": r.get("status", "UNKNOWN"),
                "run_at": run_at,
                "run_at_str": dt.strftime("%Y-%m-%d %I:%M %p") if dt else "N/A",
                "is_past": bool(run_at < now) if run_at else False,
                "task_name": r.get("task_name", ""),
            })

        return {
            "status": "success",
            "count": len(formatted),
            "reminders": formatted,
        }

    def reconcile(self) -> Dict[str, Any]:
        """
        Crash & restart reconciliation:
        1. Loads active SCHEDULED reminders from SQLite.
        2. Queries schtasks CSV output to discover existing OS tasks.
        3. Identifies missed tasks (run_at < now - 300) and marks MISSED_DURING_DOWNTIME.
        4. Identifies active tasks missing from schtasks and re-registers them.
        """
        _ensure_directories_and_runner()
        now = time.time()
        active_records = state_db.list_active_reminders()

        if not active_records:
            return {"status": "success", "message": "No active reminders to reconcile.", "repaired": 0, "missed": 0}

        os_task_names = set()

        # Parse CSV output from schtasks
        if sys.platform == "win32":
            try:
                res = subprocess.run(["schtasks", "/query", "/fo", "CSV", "/nh"], capture_output=True, text=True, timeout=15, **_CNW)
                if res.returncode == 0:
                    reader = csv.reader(res.stdout.splitlines())
                    for row in reader:
                        if row and len(row) > 0:
                            raw_name = row[0].strip().lstrip("\\")
                            if raw_name.startswith("MAX_Reminder_"):
                                os_task_names.add(raw_name)
                                os_task_names.add(f"\\{raw_name}")
            except Exception as e:
                logger.warning(f"Reconciliation: Error querying schtasks: {e}")

        repaired_count = 0
        missed_count = 0

        for r in active_records:
            r_id = r["id"]
            run_at = r.get("run_at", 0)
            task_name = r.get("task_name", f"MAX_Reminder_{r_id}")

            # Case A: Task was missed while workstation was off / agent stopped
            if run_at < (now - 300):
                state_db.update_reminder_status(r_id, "MISSED_DURING_DOWNTIME")
                missed_count += 1
                logger.info(f"⏰ Marked missed reminder: '{r.get('message')}' (Due at {datetime.datetime.fromtimestamp(run_at)})")

            # Case B: Future task missing from Task Scheduler -> Recreate
            elif sys.platform == "win32" and task_name not in os_task_names and f"\\{task_name}" not in os_task_names:
                dt_target = datetime.datetime.fromtimestamp(run_at)
                time_str = dt_target.strftime("%H:%M")
                date_str = dt_target.strftime("%m/%d/%Y")
                py_exe = _get_python_executable()
                runner_cmd = f'"{py_exe}" "{str(RUNNER_SCRIPT_PATH)}" --id {r_id}'

                cmd = [
                    "schtasks", "/create",
                    "/tn", task_name,
                    "/tr", runner_cmd,
                    "/sc", "once",
                    "/st", time_str,
                    "/sd", date_str,
                    "/f"
                ]
                try:
                    res = subprocess.run(cmd, capture_output=True, text=True, timeout=10, **_CNW)
                    if res.returncode == 0:
                        repaired_count += 1
                        logger.info(f"🔄 Reconciled & restored missing Task Scheduler entry: {task_name}")
                except Exception as ex:
                    logger.warning(f"Reconciliation failed for {task_name}: {ex}")

        return {
            "status": "success",
            "message": f"Reconciliation complete: {repaired_count} restored, {missed_count} marked missed.",
            "repaired": repaired_count,
            "missed": missed_count,
            "total_active": len(active_records),
        }


# Global instance
persistent_scheduler = PersistentScheduler.get_instance()
