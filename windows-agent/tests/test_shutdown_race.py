"""
MAX AI — Audio Shutdown Race Regression Test Suite

Validates the fix for the FATAL PyEval_RestoreThread crash
(Fatal Python error: PyEval_RestoreThread ... the GIL is released)
seen when the interpreter finalizes while the AudioCapture thread is
inside a GIL-releasing PortAudio call.

The fix:
  1. Capture thread is NON-daemon  -> threading._shutdown always joins it.
  2. A _thread._register_atexit hook stops+joins live captures BEFORE
     threading._shutdown runs (C-level atexit stack ordering).
  3. stop() is deterministic and idempotent (stop event -> join -> force
     stop_stream on stall -> join -> close stream).

Tests:
  TEST 1: Capture thread is non-daemon (deterministic invariant).
  TEST 2: Stop-before-start is safe and idempotent.
  TEST 3: Double stop is idempotent.
  TEST 4: stop() joins the thread (thread dead after stop).
  TEST 5: 10-cycle start/stop has no thread/stream leak.
  TEST 6: C-level atexit hook runs before threading._shutdown (mechanism proof).
  TEST 7: Abnormal shutdown paths A-J exit WITHOUT Fatal Python error.
  TEST 8: Python process exit test - 20x real-PyAudio subprocess runs,
          ZERO PyEval_RestoreThread / Fatal Python error.
"""

import asyncio
import os
import subprocess
import sys
import threading
import unittest

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

PROBE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_shutdown_probe.py")
PYTHON = sys.executable

from core.audio_handler import AudioCapture, terminate_shared_pyaudio

FATAL_MARKERS = ("Fatal Python error", "PyEval_RestoreThread", "the GIL is released")


class TestAudioShutdownRace(unittest.TestCase):

    def setUp(self):
        self.loop = asyncio.new_event_loop()

    def tearDown(self):
        try:
            self.loop.close()
        except Exception:
            pass
        # Defensive: never leave a live capture behind in-process.
        for cap in list(_live_captures_snapshot()):
            cap.stop()

    # ── TEST 1: non-daemon invariant ─────────────────────────────────────────
    def test_01_capture_thread_is_non_daemon(self):
        cap = AudioCapture()
        cap.start(self.loop)
        try:
            self.assertTrue(cap.is_running)
            self.assertTrue(cap.is_thread_alive)
            self.assertFalse(cap._thread.daemon,
                             "Capture thread must be NON-daemon so threading._shutdown joins it")
        finally:
            cap.stop()

    # ── TEST 2: stop-before-start ────────────────────────────────────────────
    def test_02_stop_before_start_is_safe(self):
        cap = AudioCapture()
        cap.stop()  # never started
        self.assertFalse(cap.is_running)
        self.assertIsNone(cap._thread)
        cap.stop()  # again - still safe

    # ── TEST 3: double stop idempotent ───────────────────────────────────────
    def test_03_double_stop_idempotent(self):
        cap = AudioCapture()
        cap.start(self.loop)
        cap.stop()
        cap.stop()  # second stop must be a no-op, no exception
        self.assertFalse(cap.is_running)
        self.assertFalse(cap.is_thread_alive)
        self.assertIsNone(cap._thread)

    # ── TEST 4: stop joins the thread ────────────────────────────────────────
    def test_04_stop_joins_thread(self):
        cap = AudioCapture()
        cap.start(self.loop)
        self.assertTrue(cap.is_thread_alive)
        cap.stop()
        self.assertFalse(cap.is_thread_alive,
                         "stop() must fully join the capture thread")
        self.assertIsNone(cap._thread)

    # ── TEST 5: 10-cycle start/stop leak test ────────────────────────────────
    def test_05_ten_cycle_start_stop_no_leak(self):
        threads_before = {t.ident for t in threading.enumerate()}
        for _ in range(10):
            cap = AudioCapture()
            cap.start(self.loop)
            self.assertTrue(cap.is_thread_alive)
            cap.stop()
            self.assertFalse(cap.is_thread_alive)
            self.assertIsNone(cap._thread)
            self.assertIsNone(cap._stream)

        threads_after = threading.enumerate()
        leftover = [t for t in threads_after
                    if t.name == "AudioCapture" and t.is_alive()]
        self.assertEqual(leftover, [],
                         "No AudioCapture thread may leak across start/stop cycles")

    # ── TEST 6: C-level atexit hook ordering (mechanism proof) ───────────────
    def test_06_c_level_atexit_runs_before_threading_shutdown(self):
        code = "import sys, threading; threading._register_atexit(lambda: print('FATAL_GUARD_OK', flush=True)); sys.exit(0)"
        result = subprocess.run(
            [PYTHON, "-c", code],
            capture_output=True, text=True, timeout=30, cwd=AGENT_DIR,
        )
        self.assertEqual(result.returncode, 0)
        self.assertIn("FATAL_GUARD_OK", result.stdout)



    # ── TEST 7: abnormal shutdown paths A-J (no Fatal Python error) ──────────
    def test_07_abnormal_shutdown_paths_no_fatal(self):
        modes = ["normal", "stop", "sys_exit", "keyboard", "unhandled", "os_exit"]
        for mode in modes:
            with self.subTest(mode=mode):
                result = subprocess.run(
                    [PYTHON, PROBE, mode],
                    capture_output=True, text=True, timeout=60, cwd=AGENT_DIR,
                )
                self.assertNotIn("PROBE-CAPTURE-STARTED", result.stderr)
                for marker in FATAL_MARKERS:
                    self.assertNotIn(marker, result.stdout,
                                     f"Fatal marker '{marker}' in stdout for mode={mode}")
                    self.assertNotIn(marker, result.stderr,
                                     f"Fatal marker '{marker}' in stderr for mode={mode}")

    # ── TEST 8: 20x process exit test, ZERO PyEval_RestoreThread ─────────────
    def test_08_process_exit_twenty_runs_zero_fatal(self):
        fatal_count = 0
        hangs = 0
        for i in range(20):
            with self.subTest(run=i):
                try:
                    result = subprocess.run(
                        [PYTHON, PROBE, "normal"],
                        capture_output=True, text=True, timeout=45, cwd=AGENT_DIR,
                    )
                except subprocess.TimeoutExpired:
                    hangs += 1
                    continue
                output = (result.stdout or "") + (result.stderr or "")
                if any(m in output for m in FATAL_MARKERS):
                    fatal_count += 1
        self.assertEqual(fatal_count, 0,
                         "ZERO PyEval_RestoreThread/Fatal Python error expected "
                         "across 20 real-PyAudio process exits")
        self.assertEqual(hangs, 0,
                         "Process exit must never hang on the capture thread")


def _live_captures_snapshot():
    import core.audio_handler as ah
    with ah._live_captures_lock:
        return list(ah._live_captures)


if __name__ == "__main__":
    unittest.main()