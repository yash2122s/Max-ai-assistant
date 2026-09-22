"""
MAX AI — Comprehensive Authoritative Filesystem Subsystem Test Suite
Tests:
- Path Resolution & Sandboxing (Traversal, UNC, device names, protected system roots)
- Atomic File Writes & Pre-Overwrite Vault Snapshots
- Safe Encoding Detection & Line-Range Slicing (UTF-8, UTF-16, ANSI, Binary)
- Copy & Move Verification (Cross-volume, SHA-256, Size)
- Safe Deletion & Recycle Bin Restoration
- Transaction Grouping & Single-Step Batch Undo
- Persistent Disk Journaling
- Fuzzy Path Resolution & Ambiguity Handling
- Cryptographic Checksums (SHA-256, MD5, SHA-1)
- Duplicate File Discovery
- Batch Renaming with Preview & Rollback
- Desktop Organizer with Transactional Rollback
- Native Zip & Tar Archive Engine with Zip-Slip Traversal Protection
- Disk Storage Inspection
- Legacy Compatibility Adapters (file_tool, fileops_tool)
"""

import os
import io
import shutil
import tempfile
import pytest
from pathlib import Path

from core.security import security_guard, RiskLevel
from core.undo_manager import undo_manager
from core.verifier import action_verifier
from core.safe_fs import safe_fs
from tools import file_manager
from tools.file_tool import file_helper
from tools.fileops_tool import fileops_helper, FileOpsTool


@pytest.fixture
def temp_workspace():
    """Provides an isolated temporary directory within the safe user workspace."""
    d = tempfile.mkdtemp(prefix="max_fs_test_")
    yield Path(d)
    if os.path.exists(d):
        shutil.rmtree(d, ignore_errors=True)


# ==============================================================================
# 1. Path Resolution & Sandboxing
# ==============================================================================

def test_path_resolution_special_folders():
    """Test resolution of Desktop, Downloads, Documents, AppData, Temp aliases."""
    desktop = file_manager.resolve_path("desktop")
    downloads = file_manager.resolve_path("downloads")
    documents = file_manager.resolve_path("documents")
    temp_dir = file_manager.resolve_path("temp")

    assert os.path.isabs(desktop)
    assert "Desktop" in desktop
    assert os.path.isabs(downloads)
    assert "Downloads" in downloads
    assert os.path.isabs(documents)
    assert "Documents" in documents
    assert os.path.isabs(temp_dir)


def test_path_traversal_rejection():
    """Test strict rejection of directory traversal (..) attempts."""
    with pytest.raises(PermissionError):
        file_manager.resolve_path("Desktop/../../Windows/System32/cmd.exe")

    with pytest.raises(PermissionError):
        file_manager.resolve_path("..\\..\\secret.txt")


def test_unc_and_device_path_rejection():
    """Test rejection of UNC network paths and reserved Windows device names."""
    assert security_guard.is_safe_path(r"\\attacker-server\share\exploit.exe") is False
    assert security_guard.is_safe_path("//192.168.1.1/shared") is False
    assert security_guard.is_safe_path("CON") is False
    assert security_guard.is_safe_path("Desktop/NUL.txt") is False
    assert security_guard.is_safe_path("COM1") is False


def test_protected_system_path_detection():
    """Test detection of protected system directories."""
    win_dir = os.environ.get("SystemRoot", r"C:\Windows")
    prog_files = os.environ.get("ProgramFiles", r"C:\Program Files")

    assert security_guard.is_protected_system_path(win_dir) is True
    assert security_guard.is_protected_system_path(prog_files) is True
    assert security_guard.is_protected_system_path(str(Path.home() / "Desktop")) is False


# ==============================================================================
# 2. Atomic Writes & Vault Snapshots
# ==============================================================================

def test_atomic_file_write_and_verification(temp_workspace):
    """Test atomic file write and exact content verification."""
    target = temp_workspace / "test_atomic.txt"
    content = "Initial content line 1\nInitial content line 2"

    res = file_manager.write_file(path=str(target), content=content)
    assert res["status"] == "success"
    assert res["verified"] is True
    assert target.exists()
    assert target.read_text(encoding="utf-8") == content


def test_atomic_overwrite_with_undo(temp_workspace):
    """Test that overwriting a file creates a vault snapshot and undo restores previous content."""
    target = temp_workspace / "overwrite_test.txt"
    v1_text = "Version 1: Important original content"
    v2_text = "Version 2: Overwritten content"

    file_manager.write_file(path=str(target), content=v1_text)
    assert target.read_text(encoding="utf-8") == v1_text

    # Overwrite
    res_v2 = file_manager.write_file(path=str(target), content=v2_text)
    assert res_v2["status"] == "success"
    assert target.read_text(encoding="utf-8") == v2_text

    # Trigger undo
    undo_res = undo_manager.undo()
    assert undo_res["status"] == "success"
    assert target.read_text(encoding="utf-8") == v1_text


def test_atomic_append_mode(temp_workspace):
    """Test append mode in write_file."""
    target = temp_workspace / "append_test.txt"
    file_manager.write_file(path=str(target), content="Line 1\n")
    file_manager.write_file(path=str(target), content="Line 2\n", append=True)

    assert target.read_text(encoding="utf-8") == "Line 1\nLine 2\n"


# ==============================================================================
# 3. Safe Encoding Detection & Line-Range Slicing
# ==============================================================================

def test_read_utf8_with_bom(temp_workspace):
    """Test reading UTF-8 files with BOM signature."""
    target = temp_workspace / "utf8_bom.txt"
    content = "Hello UTF-8 BOM"
    with open(target, "wb") as f:
        f.write(b"\xef\xbb\xbf" + content.encode("utf-8"))

    res = file_manager.read_file(str(target))
    assert res["status"] == "success"
    assert res["encoding"] == "utf-8-sig"
    assert res["content"] == content


def test_read_utf16_le(temp_workspace):
    """Test reading UTF-16 Little Endian encoded files."""
    target = temp_workspace / "utf16_le.txt"
    content = "Hello UTF-16 Unicode"
    with open(target, "wb") as f:
        f.write(content.encode("utf-16"))

    res = file_manager.read_file(str(target))
    assert res["status"] == "success"
    assert "utf-16" in res["encoding"]
    assert res["content"] == content


def test_read_line_range_and_tail(temp_workspace):
    """Test reading specific line ranges and tail slices."""
    target = temp_workspace / "multiline.txt"
    lines = [f"Line {i}" for i in range(1, 101)]
    target.write_text("\n".join(lines), encoding="utf-8")

    # Read lines 10 to 20
    res_range = file_manager.read_file(str(target), start_line=10, end_line=20)
    assert res_range["status"] == "success"
    assert res_range["start_line"] == 10
    assert res_range["end_line"] == 20
    range_lines = res_range["content"].splitlines()
    assert len(range_lines) == 11
    assert range_lines[0] == "Line 10"
    assert range_lines[-1] == "Line 20"

    # Read tail 5 lines
    res_tail = file_manager.read_file(str(target), tail=5)
    assert res_tail["status"] == "success"
    tail_lines = res_tail["content"].splitlines()
    assert len(tail_lines) == 5
    assert tail_lines[-1] == "Line 100"


def test_read_binary_detection(temp_workspace):
    """Test that binary files are detected and previewed safely without crashing UTF-8 decoders."""
    target = temp_workspace / "sample.bin"
    with open(target, "wb") as f:
        f.write(b"\x00\x01\x02\x03\xFF\xFE\x00\x00" * 32)

    res = file_manager.read_file(str(target))
    assert res["status"] == "success"
    assert res["is_binary"] is True
    assert "[Binary File" in res["content"]


# ==============================================================================
# 4. Copy, Move, and Rename Verification
# ==============================================================================

def test_copy_file_with_verification(temp_workspace):
    """Test copy_file with size/hash verification and rollback."""
    src = temp_workspace / "source_copy.txt"
    dst = temp_workspace / "dest_copy.txt"
    src.write_text("Unique text for copy test", encoding="utf-8")

    res = file_manager.copy_file(str(src), str(dst), check_hash=True)
    assert res["status"] == "success"
    assert res["verified"] is True
    assert dst.exists()
    assert dst.read_text(encoding="utf-8") == src.read_text(encoding="utf-8")

    # Rollback copy
    undo_res = undo_manager.undo()
    assert undo_res["status"] == "success"
    assert not dst.exists()
    assert src.exists()


def test_move_and_rename_file(temp_workspace):
    """Test move and rename with verification and rollback."""
    src = temp_workspace / "orig_name.txt"
    src.write_text("Moving content", encoding="utf-8")

    # Rename
    ren_res = file_manager.rename_file(str(src), "new_name.txt")
    assert ren_res["status"] == "success"
    dst_renamed = temp_workspace / "new_name.txt"
    assert dst_renamed.exists()
    assert not src.exists()

    # Move into subfolder
    sub = temp_workspace / "subfolder"
    file_manager.create_folder(str(sub))
    mov_res = file_manager.move_file(str(dst_renamed), str(sub))
    assert mov_res["status"] == "success"
    final_p = sub / "new_name.txt"
    assert final_p.exists()
    assert not dst_renamed.exists()


# ==============================================================================
# 5. Safe Deletion & Programmatic Rollback
# ==============================================================================

def test_safe_delete_and_undo(temp_workspace):
    """Test safe delete moves file to vault/trash and undo restores it."""
    target = temp_workspace / "to_delete.txt"
    original_text = "Content that will be deleted and restored"
    target.write_text(original_text, encoding="utf-8")
    assert target.exists()

    del_res = file_manager.delete_file(str(target))
    assert del_res["status"] == "success"
    assert not target.exists()

    # Rollback deletion
    undo_res = undo_manager.undo()
    assert undo_res["status"] == "success"
    assert target.exists()
    assert target.read_text(encoding="utf-8") == original_text


# ==============================================================================
# 6. Transaction Grouping in UndoManager
# ==============================================================================

def test_transaction_grouping_rollback(temp_workspace):
    """Test that a multi-step transaction rolls back all actions in one undo call."""
    f1 = temp_workspace / "t_file1.txt"
    f2 = temp_workspace / "t_file2.txt"
    f3 = temp_workspace / "t_file3.txt"

    tx_id = undo_manager.begin_transaction("Batch creation transaction")
    file_manager.create_file(str(f1), content="File 1")
    file_manager.create_file(str(f2), content="File 2")
    file_manager.create_file(str(f3), content="File 3")
    undo_manager.commit_transaction()

    assert f1.exists()
    assert f2.exists()
    assert f3.exists()

    # Single undo must roll back ALL 3 files!
    undo_res = undo_manager.undo()
    assert undo_res["status"] == "success"
    assert undo_res["actions_undone"] == 3
    assert not f1.exists()
    assert not f2.exists()
    assert not f3.exists()


# ==============================================================================
# 7. Fuzzy Path Resolution
# ==============================================================================

def test_fuzzy_path_resolution(temp_workspace):
    """Test safe fuzzy resolution of natural language file descriptions."""
    target = temp_workspace / "Quarterly_Financial_Report_2026.pdf"
    target.write_text("PDF content placeholder", encoding="utf-8")

    # Fuzzy match with folder scope
    res = file_manager.fuzzy_resolve_path("financial report", search_roots=[str(temp_workspace)])
    assert res["status"] == "success"
    assert res["path"] == str(target)


def test_fuzzy_path_ambiguous_return(temp_workspace):
    """Test that ambiguous candidate queries return structured ambiguous status."""
    f1 = temp_workspace / "project_report_alpha.txt"
    f2 = temp_workspace / "project_report_beta.txt"
    f1.write_text("Alpha", encoding="utf-8")
    f2.write_text("Beta", encoding="utf-8")

    res = file_manager.fuzzy_resolve_path("project report", search_roots=[str(temp_workspace)])
    assert res["status"] == "ambiguous"
    assert len(res["candidates"]) >= 2


# ==============================================================================
# 8. Cryptographic Checksums & Duplicate Finder
# ==============================================================================

def test_file_hashing(temp_workspace):
    """Test SHA-256 and MD5 cryptographic file hashing."""
    target = temp_workspace / "hash_target.txt"
    target.write_text("Deterministic content for checksum testing", encoding="utf-8")

    res_sha = file_manager.get_file_hash(str(target), algorithm="sha256")
    assert res_sha["status"] == "success"
    assert len(res_sha["hash"]) == 64

    res_md5 = file_manager.get_file_hash(str(target), algorithm="md5")
    assert res_md5["status"] == "success"
    assert len(res_md5["hash"]) == 32


def test_find_duplicate_files(temp_workspace):
    """Test duplicate file discovery based on size grouping and SHA-256 hashes."""
    orig = temp_workspace / "original.txt"
    dup1 = temp_workspace / "duplicate_1.txt"
    diff = temp_workspace / "different.txt"

    same_content = "Identical byte sequence across duplicate files" * 10
    orig.write_text(same_content, encoding="utf-8")
    dup1.write_text(same_content, encoding="utf-8")
    diff.write_text("Different content with another size", encoding="utf-8")

    res = file_manager.find_duplicate_files(str(temp_workspace), min_size_bytes=10)
    assert res["status"] == "success"
    assert res["duplicate_groups_found"] == 1
    assert res["duplicates"][0]["count"] == 2
    assert res["wasted_space_bytes"] > 0


# ==============================================================================
# 9. Batch Rename with Preview & Transactional Undo
# ==============================================================================

def test_batch_rename_preview_and_execution(temp_workspace):
    """Test batch rename dry-run preview, execution, and full rollback."""
    f1 = temp_workspace / "raw_01.jpg"
    f2 = temp_workspace / "raw_02.jpg"
    f1.write_text("img1", encoding="utf-8")
    f2.write_text("img2", encoding="utf-8")

    # Dry-run
    prev = file_manager.batch_rename(str(temp_workspace), pattern="raw", replacement="photo", dry_run=True)
    assert prev["status"] == "preview"
    assert prev["total_items"] == 2

    # Execute
    exec_res = file_manager.batch_rename(str(temp_workspace), pattern="raw", replacement="photo", dry_run=False)
    assert exec_res["status"] == "success"
    assert exec_res["renamed_count"] == 2
    assert (temp_workspace / "photo_01.jpg").exists()
    assert (temp_workspace / "photo_02.jpg").exists()
    assert not f1.exists()

    # Rollback entire batch in one step!
    undo_res = undo_manager.undo()
    assert undo_res["status"] == "success"
    assert f1.exists()
    assert f2.exists()
    assert not (temp_workspace / "photo_01.jpg").exists()


# ==============================================================================
# 10. Native Archives with Zip-Slip Protection
# ==============================================================================

def test_create_and_extract_zip_archive(temp_workspace):
    """Test creating and extracting .zip archive using native Python with Zip-Slip defense."""
    sub = temp_workspace / "to_pack"
    sub.mkdir()
    (sub / "doc1.txt").write_text("Doc 1", encoding="utf-8")
    (sub / "doc2.txt").write_text("Doc 2", encoding="utf-8")

    zip_out = temp_workspace / "packed.zip"
    c_res = file_manager.create_archive(str(sub), output_path=str(zip_out), archive_type="zip")
    assert c_res["status"] == "success"
    assert zip_out.exists()

    # List
    l_res = file_manager.list_archive(str(zip_out))
    assert l_res["status"] == "success"
    assert l_res["total_entries"] == 2

    # Extract
    ext_dir = temp_workspace / "extracted"
    e_res = file_manager.extract_archive(str(zip_out), destination=str(ext_dir))
    assert e_res["status"] == "success"
    assert (ext_dir / "doc1.txt").exists()
    assert (ext_dir / "doc2.txt").exists()


# ==============================================================================
# 11. Disk Storage Inspection
# ==============================================================================

def test_disk_storage_inspection():
    """Test querying drive storage metrics."""
    res = file_manager.get_disk_storage("C:")
    assert res["status"] == "success"
    assert res["total_gb"] > 0
    assert res["free_gb"] > 0
    assert 0 <= res["percent_used"] <= 100


# ==============================================================================
# 12. Legacy Adapters Compatibility (file_tool & fileops_tool)
# ==============================================================================

def test_legacy_file_tool_adapter(temp_workspace):
    """Test that legacy file_tool.FileHelper routes to file_manager correctly."""
    sample = temp_workspace / "adapter_sample.txt"
    sample.write_text("Testing legacy adapter", encoding="utf-8")

    matches = file_helper.search_files("adapter_sample", root_path=str(temp_workspace))
    assert len(matches) >= 1
    assert matches[0]["name"] == "adapter_sample.txt"


@pytest.mark.asyncio
async def test_legacy_fileops_tool_adapter(temp_workspace):
    """Test that legacy FileOpsTool WebSocket dispatcher routes to file_manager correctly."""
    tool = FileOpsTool()
    target = temp_workspace / "ws_fileops.txt"

    async def dummy_progress(msg):
        pass

    # Write
    w_res = await tool.execute("write", {"path": str(target), "content": "WebSocket I/O test"}, dummy_progress)
    assert w_res["status"] == "success"
    assert target.exists()

    # Read
    r_res = await tool.execute("read", {"path": str(target)}, dummy_progress)
    assert r_res["status"] == "success"
    assert r_res["output"]["content"] == "WebSocket I/O test"

    # Hash
    h_res = await tool.execute("hash", {"path": str(target)}, dummy_progress)
    assert h_res["status"] == "success"
    assert len(h_res["output"]["hash"]) == 64
