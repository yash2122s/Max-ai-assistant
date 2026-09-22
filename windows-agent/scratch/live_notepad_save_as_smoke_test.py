"""
REAL Windows E2E Smoke Test: Notepad + Save As Dialog Flow Verification
Opens Notepad, types text, invokes Save As flow, saves to disk, verifies on disk, and cleans up.
"""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import time
import subprocess

from tools.app_control import open_application, close_application
from core.computer_use.computer_use_engine import computer_use_engine


def run_smoke_test():
    test_filename = "max_e2e_save_demo.txt"
    test_filepath = os.path.abspath(test_filename)
    test_content = "MAX Real Windows Save As Verification Content"

    # Clean up previous file if any
    if os.path.exists(test_filepath):
        try:
            os.remove(test_filepath)
        except Exception:
            pass

    print("\n========================================================")
    print("[*] Starting REAL Windows E2E Smoke Test: Save As Dialog")
    print("========================================================")

    # 1. Open Notepad
    print("1. Opening Notepad...")
    open_res = open_application("notepad")
    print(f"   Open Notepad result: {open_res.get('status')} (verified: {open_res.get('verified')})")
    time.sleep(1.2)

    try:
        # 2. Type content into Notepad
        print("2. Typing test content into Notepad...")
        type_content_res = computer_use_engine.step(
            action_type="type_text",
            target_query="Text Editor",
            text=test_content,
            description="Type content into Notepad"
        )
        print(f"   Type content result: {type_content_res.get('status')} (verified: {type_content_res.get('verified')})")
        time.sleep(0.5)

        # 3. Execute Semantic Save As Flow
        print(f"3. Executing semantic Save As for '{test_filepath}'...")
        save_res = computer_use_engine.save_as_file(
            filename=test_filepath,
            target_window="Notepad",
            overwrite=True,
            expected_content="MAX Real Windows"
        )
        print(f"   Save As result: {save_res.get('status')} (verified: {save_res.get('verified')})")
        print(f"   Message: {save_res.get('message')}")
        print(f"   File on disk exists: {save_res.get('file_exists')}")

        # 4. Assertions on Real Hardware State
        assert os.path.exists(test_filepath), f"File {test_filepath} was not created on disk!"
        file_size = os.path.getsize(test_filepath)
        print(f"   Verified file exists on disk: {test_filepath} ({file_size} bytes)")
        assert file_size > 0, "File is 0 bytes!"

        print("\n[SUCCESS] REAL Windows E2E Smoke Test PASSED SUCCESSFULLY!")
        return True

    finally:
        # Clean up Notepad
        print("\n[CLEANUP] Closing Notepad...")
        try:
            close_application("notepad", force=True)
        except Exception as e:
            print(f"Close notepad notice: {e}")

        # Clean up test file
        if os.path.exists(test_filepath):
            try:
                os.remove(test_filepath)
                print(f"[CLEANUP] Cleaned up temporary test file: {test_filepath}")
            except Exception:
                pass


if __name__ == "__main__":
    success = run_smoke_test()
    sys.exit(0 if success else 1)
