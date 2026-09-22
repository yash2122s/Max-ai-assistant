"""
MAX AI — Live Smoke Tests & Failure Case Validation (ASCII Safe)
Runs:
1. Smoke Test 1: App launch & YouTube search
2. Smoke Test 2: File Creation, Read-Back & Verification
3. Failure Case Test: Non-existent UI target detection
"""

import os
import sys
import time
import json

# Ensure UTF-8 stdout
if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

AGENT_ROOT = os.path.dirname(os.path.abspath(__file__))
if AGENT_ROOT not in sys.path:
    sys.path.insert(0, AGENT_ROOT)

from core.unified_tool_registry import unified_tool_registry
from core.computer_use.computer_use_engine import computer_use_engine
from core.mcp.mcp_manager import mcp_manager

def run_smoke_tests():
    print("============================================================")
    print("RUNNING MAX LIVE SMOKE TESTS")
    print("============================================================")

    # ------------------------------------------------------------
    # SMOKE TEST 1: Open Chrome and search for YouTube
    # ------------------------------------------------------------
    print("\n[SMOKE TEST 1] Open Chrome and search for YouTube...")
    try:
        # Search YouTube tool execution
        yt_res = unified_tool_registry.execute("search_youtube", {"query": "Max AI Assistant"})
        status = yt_res.get("status", "")
        print(f"  -> search_youtube result: {status} - {yt_res.get('message', '')[:80]}")
        assert status in ["success", "completed", "ok"]
        print("  [PASS] SMOKE TEST 1 PASSED")
        test1_status = "PASSED"
    except Exception as e:
        print(f"  [FAIL] SMOKE TEST 1 FAILED: {e}")
        test1_status = f"FAILED: {e}"

    # ------------------------------------------------------------
    # SMOKE TEST 2: Create a small test folder/file, read it back, and verify
    # ------------------------------------------------------------
    print("\n[SMOKE TEST 2] Create test folder/file, read it back, verify contents...")
    try:
        test_dir = os.path.join(AGENT_ROOT, "storage", "smoke_test_dir")
        test_file = os.path.join(test_dir, "smoke_verify.txt")
        test_secret = f"MAX_VERIFIED_PAYLOAD_{int(time.time())}"

        # Create folder
        dir_res = unified_tool_registry.execute("create_folder", {"path": test_dir})
        print(f"  -> create_folder: {dir_res.get('status')}")

        # Create file
        file_res = unified_tool_registry.execute("create_file", {
            "path": test_file,
            "content": test_secret
        })
        print(f"  -> create_file: {file_res.get('status')}")

        # Read back
        read_res = unified_tool_registry.execute("read_file", {"path": test_file})
        content_read = read_res.get("content", "")
        print(f"  -> read_file: status={read_res.get('status')}, length={len(content_read)}")

        assert test_secret in content_read, f"Expected '{test_secret}' in '{content_read}'"

        # Clean up
        try:
            os.remove(test_file)
            os.rmdir(test_dir)
        except Exception:
            pass

        print("  [PASS] SMOKE TEST 2 PASSED")
        test2_status = "PASSED"
    except Exception as e:
        print(f"  [FAIL] SMOKE TEST 2 FAILED: {e}")
        test2_status = f"FAILED: {e}"

    # ------------------------------------------------------------
    # FAILURE CASE TEST: Click a button that does not exist
    # ------------------------------------------------------------
    print("\n[FAILURE CASE TEST] Click a button that does not exist...")
    try:
        fail_res = unified_tool_registry.execute("computer_use_click", {
            "target": "DEFINITELY_NON_EXISTENT_BUTTON_404_NOT_FOUND"
        })
        print(f"  -> computer_use_click result status: {fail_res.get('status')}")
        print(f"  -> error code: {fail_res.get('error_code')}")
        print(f"  -> message: {fail_res.get('message')}")

        # Expected behavior: MAX should safely detect failure/target-not-found and must not blindly click.
        assert fail_res.get("status") in ["error", "target_not_found", "not_found"]
        assert fail_res.get("error_code") in ["TARGET_NOT_FOUND", "not_found", "error"]
        print("  [PASS] FAILURE CASE TEST PASSED: Successfully rejected blind execution")
        fail_test_status = "PASSED"
    except Exception as e:
        print(f"  [FAIL] FAILURE CASE TEST FAILED: {e}")
        fail_test_status = f"FAILED: {e}"

    print("\n============================================================")
    print(f"SUMMARY: Test 1: {test1_status} | Test 2: {test2_status} | Failure Case: {fail_test_status}")
    print("============================================================")

if __name__ == "__main__":
    run_smoke_tests()
