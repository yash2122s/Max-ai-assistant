"""
Unit and Integration Tests for PersistentScheduler & SQLite State Machine (Phase 1 Reliability)
"""

import os
import csv
import time
import json
import sqlite3
import tempfile
import pytest
from unittest.mock import patch, MagicMock

from core.state_database import StateDatabase
from tools.persistent_scheduler import PersistentScheduler


@pytest.fixture
def temp_db():
    with tempfile.TemporaryDirectory() as tmpdir:
        db_path = os.path.join(tmpdir, "test_state.db")
        db = StateDatabase(db_path=db_path)
        try:
            yield db
        finally:
            db.close()


def test_sqlite_reminder_crud_lifecycle(temp_db):
    now = time.time()
    rem_id = "rem_test_001"
    
    # 1. Save reminder as PENDING
    saved = temp_db.save_reminder(
        reminder_id=rem_id,
        title="Meeting",
        message="Standup at 10 AM",
        run_at=now + 600,
        task_name="MAX_Reminder_rem_test_001",
        status="PENDING",
    )
    assert saved is True

    # 2. Get reminder
    rec = temp_db.get_reminder(rem_id)
    assert rec is not None
    assert rec["title"] == "Meeting"
    assert rec["status"] == "PENDING"

    # 3. Transactional state transition: PENDING -> SCHEDULED
    updated = temp_db.update_reminder_status(rem_id, "SCHEDULED", expected_status="PENDING")
    assert updated is True

    rec = temp_db.get_reminder(rem_id)
    assert rec["status"] == "SCHEDULED"

    # 4. List active
    active = temp_db.list_active_reminders()
    assert len(active) == 1
    assert active[0]["id"] == rem_id

    # 5. Atomic runner transition: SCHEDULED -> COMPLETED
    completed = temp_db.update_reminder_status(rem_id, "COMPLETED", expected_status="SCHEDULED")
    assert completed is True

    # 6. Idempotent check: Attempting duplicate completion fails
    duplicate = temp_db.update_reminder_status(rem_id, "COMPLETED", expected_status="SCHEDULED")
    assert duplicate is False


def test_persistent_scheduler_set_and_cancel():
    scheduler = PersistentScheduler.get_instance()
    
    # Mock subprocess.run for schtasks execution
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout="", stderr="")

        res = scheduler.set_reminder(message="Call Doctor", minutes=30, title="Health")
        assert res["status"] == "success"
        rem_id = res["reminder_id"]
        assert rem_id.startswith("rem_")

        # Verify list_reminders finds it
        list_res = scheduler.list_reminders(active_only=True)
        assert list_res["status"] == "success"
        matching = [r for r in list_res["reminders"] if r["id"] == rem_id]
        assert len(matching) == 1
        assert matching[0]["message"] == "Call Doctor"

        # Cancel reminder
        cancel_res = scheduler.cancel_reminder(rem_id)
        assert cancel_res["status"] == "success"
        assert "Cancelled" in cancel_res["message"]


def test_deterministic_csv_reconciliation():
    scheduler = PersistentScheduler.get_instance()

    # Mock schtasks /query /fo CSV output with several tasks
    csv_output = (
        '"TaskName","Next Run Time","Status"\n'
        '"\\MAX_Reminder_rem_existing_01","09/12/2026 10:00:00 AM","Ready"\n'
        '"\\Microsoft\\Windows\\UpdateOrchestrator\\Schedule Scan","N/A","Disabled"\n'
        '"MAX_Reminder_rem_existing_02","09/12/2026 11:30:00 AM","Ready"\n'
    )

    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout=csv_output, stderr="")

        res = scheduler.reconcile()
        assert res["status"] == "success"
        assert "repaired" in res
        assert "missed" in res


def test_missed_reminder_during_downtime(temp_db):
    now = time.time()
    old_id = "rem_old_001"

    # Insert a task that was scheduled 10 minutes ago
    temp_db.save_reminder(
        reminder_id=old_id,
        title="Old Reminder",
        message="Missed during PC shutdown",
        run_at=now - 600,
        task_name="MAX_Reminder_rem_old_001",
        status="SCHEDULED",
    )

    scheduler = PersistentScheduler.get_instance()
    with patch("tools.persistent_scheduler.state_db", temp_db):
        with patch("subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=0, stdout="", stderr="")
            res = scheduler.reconcile()
            assert res["missed"] >= 1

            rec = temp_db.get_reminder(old_id)
            assert rec["status"] == "MISSED_DURING_DOWNTIME"

