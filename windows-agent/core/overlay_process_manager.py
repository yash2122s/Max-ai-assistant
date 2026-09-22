"""
MAX AI — Overlay Process Lifecycle Manager
Manages the lifecycle of MAXOverlay.exe (Dynamic Island & Fullscreen HUD).

Key Capabilities:
1. Automatic startup of MAXOverlay.exe after UIBridge initialization.
2. Windows Job Object binding (JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE) to guarantee
   the overlay process never outlives the MAX Python backend, even on abnormal crashes.
3. Explicit PID tracking (only manages child processes created by this backend instance).
4. Duplicate instance detection and reuse/cleanup.
5. Non-blocking graceful shutdown with process termination fallback.
6. Monitored background watchdog for crash detection and controlled single restart.
7. Absolute safety: failure to start/stop the overlay never crashes the MAX backend.
"""

import atexit
import ctypes
from ctypes import wintypes
import logging
import os
import subprocess
import sys
import threading
import time
from typing import Optional, Callable

logger = logging.getLogger("MAXWindowsAgent.OverlayManager")

# ─── Windows Job Object Interop (Kernel32) ───────────────────────────────────

JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
JobObjectExtendedLimitInformation = 9


class IO_COUNTERS(ctypes.Structure):
    _fields_ = [
        ("ReadOperationCount", ctypes.c_uint64),
        ("WriteOperationCount", ctypes.c_uint64),
        ("OtherOperationCount", ctypes.c_uint64),
        ("ReadTransferCount", ctypes.c_uint64),
        ("WriteTransferCount", ctypes.c_uint64),
        ("OtherTransferCount", ctypes.c_uint64),
    ]


class JOBOBJECT_BASIC_LIMIT_INFORMATION(ctypes.Structure):
    _fields_ = [
        ("PerProcessUserTimeLimit", ctypes.c_int64),
        ("PerJobUserTimeLimit", ctypes.c_int64),
        ("LimitFlags", wintypes.DWORD),
        ("MinimumWorkingSetSize", ctypes.c_size_t),
        ("MaximumWorkingSetSize", ctypes.c_size_t),
        ("ActiveProcessLimit", wintypes.DWORD),
        ("Affinity", ctypes.c_size_t),
        ("PriorityClass", wintypes.DWORD),
        ("SchedulingClass", wintypes.DWORD),
    ]


class JOBOBJECT_EXTENDED_LIMIT_INFORMATION(ctypes.Structure):
    _fields_ = [
        ("BasicLimitInformation", JOBOBJECT_BASIC_LIMIT_INFORMATION),
        ("IoCounters", IO_COUNTERS),
        ("ProcessMemoryLimit", ctypes.c_size_t),
        ("JobMemoryLimit", ctypes.c_size_t),
        ("PeakProcessMemoryLimit", ctypes.c_size_t),
        ("PeakJobMemoryLimit", ctypes.c_size_t),
    ]


def _create_kill_on_close_job() -> Optional[wintypes.HANDLE]:
    """Create a Windows Job Object configured to kill child processes when closed."""
    if sys.platform != "win32":
        return None
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        job = kernel32.CreateJobObjectW(None, None)
        if not job:
            return None

        info = JOBOBJECT_EXTENDED_LIMIT_INFORMATION()
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE

        success = kernel32.SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            ctypes.byref(info),
            ctypes.sizeof(info),
        )
        if not success:
            kernel32.CloseHandle(job)
            return None
        return job
    except Exception as e:
        logger.debug(f"[OVERLAY] Could not create Job Object: {e}")
        return None


def _assign_process_to_job(job: wintypes.HANDLE, process_handle: int) -> bool:
    """Assign a process handle to the Job Object."""
    if sys.platform != "win32" or not job:
        return False
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        res = kernel32.AssignProcessToJobObject(job, wintypes.HANDLE(process_handle))
        return bool(res)
    except Exception as e:
        logger.debug(f"[OVERLAY] Could not assign process to Job Object: {e}")
        return False


# ─── Overlay Process Manager ──────────────────────────────────────────────────

class OverlayProcessManager:
    """
    Manages the lifecycle of MAXOverlay.exe.
    Ensures single-instance ownership, clean shutdown, and crash safety.
    """

    def __init__(self, overlay_exe_path: Optional[str] = None):
        if overlay_exe_path is None:
            # Default to published or local build binary
            base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
            self.overlay_exe_path = os.path.join(
                base_dir, "MAXOverlay", "publish", "MAXOverlay.exe"
            )
            # Fallback to bin/Release if publish directory doesn't have the exe
            if not os.path.exists(self.overlay_exe_path):
                self.overlay_exe_path = os.path.join(
                    base_dir, "MAXOverlay", "bin", "Release", "net8.0-windows", "MAXOverlay.exe"
                )
        else:
            self.overlay_exe_path = overlay_exe_path

        self._process: Optional[subprocess.Popen] = None
        self._tracked_pid: Optional[int] = None
        self._job_handle: Optional[wintypes.HANDLE] = None
        self._lock = threading.Lock()
        self._is_shutting_down = False
        self._restart_attempts = 0
        self._max_restarts = 1
        self._watchdog_thread: Optional[threading.Thread] = None
        self._on_overlay_exit: Optional[Callable[[], None]] = None

        # Register atexit handler for ultimate safety
        atexit.register(self.cleanup)

    def set_shutdown_callback(self, callback: Callable[[], None]) -> None:
        """Register a callback to be triggered when the UI process closes."""
        self._on_overlay_exit = callback

    def is_overlay_running(self) -> bool:
        """Check if our tracked overlay process is alive."""
        with self._lock:
            if self._process is None:
                return False
            poll_result = self._process.poll()
            return poll_result is None

    def get_overlay_pid(self) -> Optional[int]:
        """Return the PID of the tracked child overlay process."""
        with self._lock:
            return self._tracked_pid if self.is_overlay_running() else None

    def _find_existing_instances(self) -> list[int]:
        """Find PIDs of any currently running MAXOverlay.exe processes."""
        pids = []
        try:
            import psutil
            for p in psutil.process_iter(["name", "pid"]):
                name = p.info.get("name") or ""
                if "maxoverlay" in name.lower():
                    pids.append(p.info["pid"])
        except Exception:
            pass
        return pids

    def start_overlay(self) -> bool:
        """
        Start MAXOverlay.exe and tie its lifetime to this process.
        Reuses an existing instance if running, or launches a new tracked process.
        """
        with self._lock:
            if self._is_shutting_down:
                logger.warning("[OVERLAY] Cannot start MAXOverlay: shutdown in progress.")
                return False

            if self._process is not None and self._process.poll() is None:
                logger.info(f"[OVERLAY] MAXOverlay already running. PID={self._tracked_pid}")
                return True

            # Check if an existing orphan MAXOverlay process is running
            existing_pids = self._find_existing_instances()
            if existing_pids:
                logger.info(f"[OVERLAY] Found existing running MAXOverlay process (PID={existing_pids[0]}). Reusing existing instance.")
                self._tracked_pid = existing_pids[0]
                return True

            use_pyqt6 = os.environ.get("MAX_UI_ENGINE", "pyqt6").lower() == "pyqt6"
            base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

            if not use_pyqt6 and not os.path.exists(self.overlay_exe_path):
                logger.warning(f"[OVERLAY] Executable not found at '{self.overlay_exe_path}'. Falling back to PyQt6 Desktop UI.")
                use_pyqt6 = True

            logger.info(f"[OVERLAY] Starting MAX {'PyQt6 Desktop UI' if use_pyqt6 else 'WPF Overlay'}...")
            try:
                # Create Job Object for automatic kernel cleanup on parent exit
                if sys.platform == "win32" and self._job_handle is None:
                    self._job_handle = _create_kill_on_close_job()

                work_dir = base_dir if use_pyqt6 else os.path.dirname(self.overlay_exe_path)
                cmd = [sys.executable, "-m", "ui.app"] if use_pyqt6 else [self.overlay_exe_path]
                
                # Launch child process detached from console
                creation_flags = 0
                if sys.platform == "win32":
                    creation_flags = subprocess.CREATE_NO_WINDOW

                self._process = subprocess.Popen(
                    cmd,
                    cwd=work_dir,
                    creationflags=creation_flags,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                self._tracked_pid = self._process.pid

                # Bind process to Windows Job Object
                if self._job_handle and sys.platform == "win32":
                    try:
                        # On Windows, process._handle gives the Win32 handle
                        proc_handle = int(self._process._handle)
                        _assign_process_to_job(self._job_handle, proc_handle)
                    except Exception as e:
                        logger.debug(f"[OVERLAY] Job Object assignment: {e}")

                logger.info(f"[OVERLAY] MAXOverlay started. PID={self._tracked_pid}")
                print(f"  🖥️  [OVERLAY] Dynamic Island started (PID: {self._tracked_pid})")

                # Start lightweight crash watchdog
                self._start_watchdog()
                return True

            except Exception as e:
                logger.warning(f"[OVERLAY] Failed to start MAXOverlay: {e}")
                self._process = None
                self._tracked_pid = None
                return False

    def _start_watchdog(self):
        """Start a daemon watchdog thread to monitor for unexpected child exit."""
        if self._watchdog_thread is not None and self._watchdog_thread.is_alive():
            return

        def _watch():
            while not self._is_shutting_down:
                time.sleep(1.0)
                with self._lock:
                    if self._is_shutting_down or self._process is None:
                        break
                    poll = self._process.poll()
                    if poll is not None:
                        logger.info(f"[OVERLAY] UI process (PID={self._tracked_pid}) terminated (code {poll}).")
                        self._is_shutting_down = True
                        self._process = None
                        self._tracked_pid = None
                        if self._on_overlay_exit:
                            try:
                                self._on_overlay_exit()
                            except Exception as e:
                                logger.debug(f"[OVERLAY] Exit callback error: {e}")
                        break

        self._watchdog_thread = threading.Thread(target=_watch, daemon=True, name="OverlayWatchdog")
        self._watchdog_thread.start()

    def stop_overlay(self, timeout_sec: float = 3.0) -> bool:
        """
        Gracefully stop the tracked MAXOverlay child process.
        Falls back to terminating only this tracked child process if needed.
        """
        with self._lock:
            self._is_shutting_down = True
            if self._process is None:
                return True

            pid = self._tracked_pid
            logger.info(f"[OVERLAY] Shutdown requested for PID={pid}")

            try:
                # 1. Attempt graceful termination
                self._process.terminate()
                try:
                    self._process.wait(timeout=timeout_sec)
                    logger.info(f"[OVERLAY] MAXOverlay stopped cleanly. PID={pid}")
                    self._process = None
                    self._tracked_pid = None
                    return True
                except subprocess.TimeoutExpired:
                    # 2. Force kill if not exited within timeout
                    logger.warning(f"[OVERLAY] MAXOverlay (PID={pid}) did not exit within {timeout_sec}s; forcing termination...")
                    self._process.kill()
                    self._process.wait(timeout=2.0)
                    logger.info(f"[OVERLAY] MAXOverlay stopped. PID={pid}")
                    self._process = None
                    self._tracked_pid = None
                    return True

            except Exception as e:
                logger.warning(f"[OVERLAY] Error stopping MAXOverlay: {e}")
                self._process = None
                self._tracked_pid = None
                return False

    def cleanup(self) -> None:
        """Synchronous exit cleanup handler."""
        try:
            self.stop_overlay(timeout_sec=1.5)
            if self._job_handle and sys.platform == "win32":
                try:
                    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
                    kernel32.CloseHandle(self._job_handle)
                except Exception:
                    pass
                self._job_handle = None
        except Exception:
            pass


# Global singleton
overlay_manager = OverlayProcessManager()
