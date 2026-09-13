"""
Unit and Concurrency Tests for UndoManager (Phase 1 Reliability)
"""

import os
import tempfile
import threading
import pytest
from core.undo_manager import UndoManager, get_undo_manager


def test_undo_manager_basic_push_and_pop():
    mgr = UndoManager(max_size=10)
    assert mgr.size == 0

    state = {"value": 10}

    def _rollback():
        state["value"] = 5
        return True, "Value rolled back to 5"

    mgr.push_undo("Set value to 10", _rollback, metadata={"prev": 5})
    assert mgr.size == 1

    history = mgr.get_history()
    assert len(history) == 1
    assert history[0]["description"] == "Set value to 10"
    assert history[0]["metadata"]["prev"] == 5

    res = mgr.undo()
    assert res["status"] == "success"
    assert res["success"] is True
    assert state["value"] == 5
    assert mgr.size == 0


def test_undo_manager_empty_stack():
    mgr = UndoManager(max_size=10)
    res = mgr.undo()
    assert res["status"] == "info"
    assert res["success"] is False
    assert "empty" in res["message"].lower()


def test_undo_manager_bounded_stack_eviction():
    mgr = UndoManager(max_size=5)
    for i in range(10):
        mgr.push_undo(f"Action {i}", lambda: (True, "Done"), metadata={"index": i})

    assert mgr.size == 5
    history = mgr.get_history(limit=10)
    # The newest action should be Action 9, oldest kept is Action 5
    assert history[0]["description"] == "Action 9"
    assert history[-1]["description"] == "Action 5"


def test_undo_manager_self_validating_failure():
    mgr = UndoManager(max_size=10)

    # Rollback function where precondition fails
    def _failing_rollback():
        return False, "Target file was locked by another process"

    mgr.push_undo("Unsafe edit", _failing_rollback)
    res = mgr.undo()
    assert res["status"] == "error"
    assert res["success"] is False
    assert "locked" in res["message"]


def test_undo_manager_concurrent_threads():
    mgr = UndoManager(max_size=50)
    lock = threading.Lock()
    counter = {"val": 0}

    def worker_push(idx):
        for _ in range(10):
            def _rollback(i=idx):
                with lock:
                    counter["val"] += 1
                return True, f"Undone {i}"
            mgr.push_undo(f"Action from thread {idx}", _rollback)

    threads = [threading.Thread(target=worker_push, args=(t,)) for t in range(10)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert mgr.size == 50  # Capped at max_size

    # Concurrently pop and execute undos
    def worker_pop():
        for _ in range(10):
            mgr.undo()

    pop_threads = [threading.Thread(target=worker_pop) for _ in range(5)]
    for t in pop_threads:
        t.start()
    for t in pop_threads:
        t.join()

    assert counter["val"] == 50
    assert mgr.size == 0


def test_file_undo_safeguards_text_file():
    with tempfile.TemporaryDirectory() as tmpdir:
        test_file = os.path.join(tmpdir, "notes.txt")
        with open(test_file, "w", encoding="utf-8") as f:
            f.write("Initial content")

        mgr = UndoManager()

        # Simulate edit with pre-edit snapshot
        old_content = "Initial content"
        with open(test_file, "w", encoding="utf-8") as f:
            f.write("Modified content")

        def _rollback_file(p=test_file, old=old_content):
            if not os.path.exists(p):
                return False, "File missing"
            with open(p, "w", encoding="utf-8") as f_res:
                f_res.write(old)
            return True, "Restored initial content"

        mgr.push_undo("Edit notes.txt", _rollback_file)
        res = mgr.undo()

        assert res["status"] == "success"
        with open(test_file, "r", encoding="utf-8") as f:
            assert f.read() == "Initial content"


def test_file_undo_safeguards_safe_delete_refusal_on_non_empty():
    with tempfile.TemporaryDirectory() as tmpdir:
        test_dir = os.path.join(tmpdir, "my_folder")
        os.makedirs(test_dir, exist_ok=True)

        # Folder now has an unexpected file created in between
        nested_file = os.path.join(test_dir, "important.docx")
        with open(nested_file, "w") as f:
            f.write("data")

        mgr = UndoManager()

        def _rollback_dir(p=test_dir):
            if not os.path.exists(p):
                return False, "Dir missing"
            if os.listdir(p):
                return False, "Folder is not empty; refusing to delete"
            os.rmdir(p)
            return True, "Removed folder"

        mgr.push_undo("Create my_folder", _rollback_dir)
        res = mgr.undo()

        assert res["status"] == "error"
        assert "not empty" in res["message"]
        assert os.path.exists(test_dir)  # Preserved!
