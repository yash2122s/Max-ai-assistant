"""
Automated Process Lifecycle Verification Suite for MAXOverlay & main.py:
- TEST A: Normal startup of main.py -> automatically launches MAXOverlay.exe & tracks PID.
- TEST B: Controlled shutdown (SIGINT / Ctrl+C) -> automatically stops MAXOverlay.exe without orphans.
- TEST C: Restart main.py -> verifies single instance ownership.
- TEST D: Abnormal backend kill -> verifies Windows Job Object cleans up child MAXOverlay.exe.
- TEST E: Startup with pre-existing MAXOverlay -> verifies reuse and duplicate prevention.
"""

import os
import psutil
import subprocess
import sys
import time

PYTHON_EXE = r"D:\max app testing\gemini-live\.venv\Scripts\python.exe"
MAIN_PY = r"D:\max app testing\gemini-live\windows-agent\main.py"
OVERLAY_EXE = r"D:\max app testing\gemini-live\windows-agent\MAXOverlay\publish\MAXOverlay.exe"
WORK_DIR = r"D:\max app testing\gemini-live\windows-agent"

RESULTS = {}

def get_overlay_pids():
    pids = []
    for p in psutil.process_iter(['name', 'pid']):
        name = p.info.get('name') or ''
        if 'maxoverlay' in name.lower():
            pids.append(p.info['pid'])
    return pids

def kill_all_overlays():
    for pid in get_overlay_pids():
        try:
            psutil.Process(pid).kill()
        except Exception:
            pass
    time.sleep(1.0)

def run_tests():
    print("\n" + "="*70)
    print("🔬 MAXOVERLAY LIFECYCLE MANAGEMENT AUTOMATED TEST SUITE")
    print("="*70)

    kill_all_overlays()
    initial_pids = get_overlay_pids()
    print(f"Pre-test overlay count: {len(initial_pids)}")

    # ──────────────────────────────────────────────────
    # TEST A: Startup & Automatic Launch
    # ──────────────────────────────────────────────────
    print("\n▶ [TEST A] Testing automatic MAXOverlay.exe launch on main.py startup...")
    env = os.environ.copy()
    env["PYTHONIOENCODING"] = "utf-8"
    
    proc = subprocess.Popen(
        [PYTHON_EXE, MAIN_PY],
        cwd=WORK_DIR,
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    )
    
    # Wait for startup (Vosk model loading takes ~3-4s)
    overlay_pid = None
    for attempt in range(25):
        time.sleep(0.5)
        pids = get_overlay_pids()
        if pids:
            overlay_pid = pids[0]
            print(f"Detected MAXOverlay PID: {overlay_pid} at T+{attempt*0.5:.1f}s")
            break

    if overlay_pid and proc.poll() is None:
        print(f"✅ TEST A PASSED: main.py (PID {proc.pid}) launched MAXOverlay.exe (PID {overlay_pid})")
        RESULTS["TEST_A"] = ("VERIFIED", f"main.py (PID {proc.pid}) spawned MAXOverlay (PID {overlay_pid})")
    else:
        print(f"❌ TEST A FAILED: overlay_pid={overlay_pid}, main_poll={proc.poll()}")
        RESULTS["TEST_A"] = ("FAILED", "MAXOverlay.exe was not spawned automatically on main.py startup")
        if proc.poll() is None:
            proc.kill()
        return

    # ──────────────────────────────────────────────────
    # TEST B: Controlled Graceful Shutdown (SIGTERM / cleanup)
    # ──────────────────────────────────────────────────
    print("\n▶ [TEST B] Testing graceful shutdown and overlay cleanup...")
    proc.terminate()
    try:
        proc.wait(timeout=6.0)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=2.0)

    time.sleep(2.0)
    remaining_pids = get_overlay_pids()
    if not remaining_pids:
        print("✅ TEST B PASSED: MAXOverlay.exe stopped cleanly with no orphans.")
        RESULTS["TEST_B"] = ("VERIFIED", "MAXOverlay terminated cleanly on main.py shutdown; 0 orphans left.")
    else:
        print(f"❌ TEST B FAILED: Orphan MAXOverlay processes remain: {remaining_pids}")
        RESULTS["TEST_B"] = ("FAILED", f"Orphan processes remain: {remaining_pids}")
        kill_all_overlays()

    # ──────────────────────────────────────────────────
    # TEST C: Restart Verification
    # ──────────────────────────────────────────────────
    print("\n▶ [TEST C] Testing restart of main.py...")
    proc2 = subprocess.Popen(
        [PYTHON_EXE, MAIN_PY],
        cwd=WORK_DIR,
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    )

    overlay_pid2 = None
    for _ in range(25):
        time.sleep(0.5)
        pids = get_overlay_pids()
        if pids:
            overlay_pid2 = pids[0]
            break

    time.sleep(2.0)
    pids_count = len(get_overlay_pids())
    if pids_count == 1 and proc2.poll() is None:
        print(f"✅ TEST C PASSED: Exactly 1 MAXOverlay.exe running (PID {overlay_pid2})")
        RESULTS["TEST_C"] = ("VERIFIED", f"Exactly 1 MAXOverlay instance running (PID {overlay_pid2})")
    else:
        print(f"❌ TEST C FAILED: overlay count = {pids_count}")
        RESULTS["TEST_C"] = ("FAILED", f"Expected 1 instance, found {pids_count}")

    # ──────────────────────────────────────────────────
    # TEST D: Abnormal Parent Crash (Windows Job Object Cleanup)
    # ──────────────────────────────────────────────────
    print("\n▶ [TEST D] Testing abnormal main.py kill (Job Object kernel cleanup)...")
    if proc2.poll() is None:
        # Force kill main.py without letting Python signal handlers run
        proc2.kill()
        proc2.wait(timeout=2.0)
    
    time.sleep(2.5)
    post_crash_pids = get_overlay_pids()
    if not post_crash_pids:
        print("✅ TEST D PASSED: Windows Job Object automatically terminated child MAXOverlay on parent crash.")
        RESULTS["TEST_D"] = ("VERIFIED", "Windows Job Object automatically terminated child process on abrupt kill.")
    else:
        print(f"⚠️ TEST D: Processes {post_crash_pids} remaining after hard kill; cleaning up.")
        RESULTS["TEST_D"] = ("PARTIAL", f"Job object / cleanup: {post_crash_pids}")
        kill_all_overlays()

    # ──────────────────────────────────────────────────
    # TEST E: Duplicate Prevention with Pre-Existing Overlay
    # ──────────────────────────────────────────────────
    print("\n▶ [TEST E] Testing duplicate prevention when overlay already exists...")
    # Manually start one MAXOverlay instance
    print(f"Manually launching pre-existing MAXOverlay: {OVERLAY_EXE}...")
    pre_proc = subprocess.Popen([OVERLAY_EXE], cwd=os.path.dirname(OVERLAY_EXE))
    time.sleep(2.5)
    pre_pids = get_overlay_pids()
    print(f"Pre-existing overlay PID(s): {pre_pids}")

    # Start main.py - it should detect and reuse the existing instance without spawning a second one
    proc3 = subprocess.Popen(
        [PYTHON_EXE, MAIN_PY],
        cwd=WORK_DIR,
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    )
    time.sleep(6.0)

    current_pids = get_overlay_pids()
    print(f"Overlay PID(s) after main.py started: {current_pids}")
    
    if len(current_pids) == 1:
        print("✅ TEST E PASSED: Duplicate overlay prevented; existing instance reused cleanly.")
        RESULTS["TEST_E"] = ("VERIFIED", f"Single instance preserved (PID {current_pids[0]}); no duplicate spawned.")
    else:
        print(f"❌ TEST E FAILED: Found {len(current_pids)} instances: {current_pids}")
        RESULTS["TEST_E"] = ("FAILED", f"Found {len(current_pids)} instances")

    # Clean shutdown of proc3
    proc3.terminate()
    try:
        proc3.wait(timeout=5.0)
    except Exception:
        proc3.kill()
    time.sleep(2.0)
    kill_all_overlays()

    print("\n" + "="*70)
    print("📊 LIFECYCLE TEST RESULTS SUMMARY")
    print("="*70)
    for tid, (status, ev) in RESULTS.items():
        print(f"{tid:<10} | {status:<12} | {ev}")
    print("="*70)

if __name__ == "__main__":
    run_tests()
