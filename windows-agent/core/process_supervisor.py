"""
MAX Ecosystem — Process Supervisor & Process Tree Cancellation
Monitors worker subprocesses, enforces child deadlines, cleans up orphan processes,
and performs tree-wide process termination on Windows and POSIX systems.
"""

import os
import sys
import time
import signal
import psutil
import logging
import threading
import subprocess
from dataclasses import dataclass, field
from typing import Dict, Any, List, Optional, Set

from config import MAX_WORKER_PROCESSES

logger = logging.getLogger("MAXWindowsAgent.ProcessSupervisor")


@dataclass
class SupervisedProcess:
    task_id: str
    pid: int
    worker: str
    workspace: Optional[str]
    start_time: float = field(default_factory=time.time)
    deadline: Optional[float] = None
    process_handle: Optional[subprocess.Popen] = None
    is_terminated: bool = False


class ProcessSupervisor:
    """
    Supervises worker processes and process trees.
    Enforces child timeouts, orphan cleanup, and tree-wide process termination.
    """
    _instance: Optional['ProcessSupervisor'] = None
    _lock = threading.Lock()

    def __init__(self, max_processes: int = MAX_WORKER_PROCESSES):
        self.max_processes = max_processes
        self._processes: Dict[str, SupervisedProcess] = {}
        self._watchdog_thread: Optional[threading.Thread] = None
        self._running = True
        self._start_watchdog()

    @classmethod
    def get_instance(cls) -> 'ProcessSupervisor':
        with cls._lock:
            if cls._instance is None:
                cls._instance = cls()
            return cls._instance

    def _start_watchdog(self):
        if self._watchdog_thread is None or not self._watchdog_thread.is_alive():
            self._watchdog_thread = threading.Thread(target=self._watchdog_loop, daemon=True, name="ProcessSupervisorWatchdog")
            self._watchdog_thread.start()

    def _watchdog_loop(self):
        while self._running:
            time.sleep(0.5)
            now = time.time()
            with self._lock:
                to_kill = []
                for tid, sp in list(self._processes.items()):
                    if sp.is_terminated:
                        continue
                    # Check deadline timeout
                    if sp.deadline and now > sp.deadline:
                        logger.warning(f"Process for task {tid} (PID {sp.pid}) exceeded deadline ({sp.deadline}). Enforcing timeout.")
                        to_kill.append((tid, "TIMEOUT"))

                for tid, reason in to_kill:
                    self.terminate_process_tree(tid, reason=reason, force=True)

    def register_process(
        self,
        task_id: str,
        pid: int,
        worker: str,
        workspace: Optional[str] = None,
        timeout_sec: Optional[float] = None,
        parent_remaining_timeout_sec: Optional[float] = None,
        process_handle: Optional[subprocess.Popen] = None
    ) -> SupervisedProcess:
        """
        Registers a worker subprocess under supervision.
        Enforces child_deadline <= parent_deadline.
        """
        effective_timeout = timeout_sec
        if parent_remaining_timeout_sec is not None:
            if effective_timeout is not None:
                effective_timeout = min(effective_timeout, parent_remaining_timeout_sec)
            else:
                effective_timeout = parent_remaining_timeout_sec

        deadline = (time.time() + effective_timeout) if effective_timeout else None

        with self._lock:
            # Enforce max concurrency limit
            active_count = sum(1 for p in self._processes.values() if not p.is_terminated)
            if active_count >= self.max_processes:
                raise ResourceWarning(f"Max worker process concurrency ({self.max_processes}) reached. Task must queue.")

            sp = SupervisedProcess(
                task_id=task_id,
                pid=pid,
                worker=worker,
                workspace=workspace,
                start_time=time.time(),
                deadline=deadline,
                process_handle=process_handle
            )
            self._processes[task_id] = sp
            logger.info(f"Registered supervised worker '{worker}' (PID {pid}) for task {task_id} with deadline {deadline}")
            return sp

    def terminate_process_tree(self, task_id: str, reason: str = "Cancellation", force: bool = True) -> bool:
        """
        Terminates the entire process tree (parent PID + all descendants) cleanly without leaving orphans.
        Uses taskkill /T /F on Windows or psutil tree kill on POSIX.
        """
        with self._lock:
            sp = self._processes.get(task_id)
            if not sp or sp.is_terminated:
                return True
            sp.is_terminated = True
            pid = sp.pid

        logger.info(f"Terminating process tree for task {task_id} (PID {pid}), reason: {reason}")

        try:
            if sys.platform == "win32":
                # Windows taskkill /T /F terminates parent and all child processes
                cmd = f"taskkill /PID {pid} /T /F" if force else f"taskkill /PID {pid} /T"
                subprocess.run(cmd, shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
            else:
                parent = psutil.Process(pid)
                children = parent.children(recursive=True)
                for child in children:
                    try:
                        child.kill() if force else child.terminate()
                    except (psutil.NoSuchProcess, psutil.AccessDenied):
                        pass
                parent.kill() if force else parent.terminate()

        except Exception as e:
            logger.warning(f"Error terminating process tree for PID {pid}: {e}")
            # Fallback direct psutil kill
            try:
                proc = psutil.Process(pid)
                proc.kill()
            except Exception:
                pass

        return True

    def get_process(self, task_id: str) -> Optional[SupervisedProcess]:
        with self._lock:
            return self._processes.get(task_id)

    def unregister_process(self, task_id: str):
        with self._lock:
            if task_id in self._processes:
                self._processes[task_id].is_terminated = True
                del self._processes[task_id]

    def cleanup_orphans(self):
        """Finds and terminates any orphaned worker processes on system startup."""
        logger.info("Scanning for orphaned worker processes...")
        for proc in psutil.process_iter(["pid", "name", "cmdline"]):
            try:
                cmd = " ".join(proc.info.get("cmdline") or []).lower()
                if "agy" in cmd or "antigravity" in cmd or "hermes_worker" in cmd:
                    logger.info(f"Terminating orphaned worker process PID {proc.info['pid']}")
                    proc.kill()
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue


# Canonical singleton instance
process_supervisor = ProcessSupervisor()
