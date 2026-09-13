"""
Real Rollback Tests for MAX Safe File Operations.
Verifies file deletion, actual restoration on disk, and content verification.
"""

import os
import tempfile
import pytest
from core.safe_fs import safe_fs
from core.undo_manager import undo_manager


def test_real_file_deletion_and_true_rollback():
    temp_dir = tempfile.mkdtemp()
    test_file = os.path.join(temp_dir, "secret_notes.txt")
    original_text = "Verified content that must be restored byte-for-byte."

    with open(test_file, "w", encoding="utf-8") as f:
        f.write(original_text)

    assert os.path.exists(test_file)

    # 1. Delete safely
    del_res = safe_fs.safe_delete(test_file)
    assert del_res["status"] == "success"
    # Ensure original file is gone from its path
    assert not os.path.exists(test_file)

    # 2. Trigger real undo
    undo_res = undo_manager.undo()
    assert undo_res["status"] == "success"
    assert undo_res["success"] is True

    # 3. VERIFY ON DISK: File must actually exist again!
    assert os.path.exists(test_file)
    with open(test_file, "r", encoding="utf-8") as f:
        restored_text = f.read()

    assert restored_text == original_text
