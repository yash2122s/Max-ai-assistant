"""
MAX AI — Interactive Windows PTY & Process Registry
Provides real interactive pseudo-terminal sessions on Windows using persistent interactive shells
with background process registry, stdout streaming, and graceful lifecycle cleanup.
"""

import os
import sys
import time
import uuid
import queue
import logging
import threading
import subprocess
from typing import Dict, Any, List, Optional

logger = logging.getLogger("MAXWindowsAgent.PTYManager")


class InteractivePTYSession:
    """
    Manages a single interactive shell session on Windows.
    """

    def __init__(self, session_id: str, command: str = "cmd.exe", cwd: Optional[str] = None):
        self.session_id = session_id
        self.command = command
        self.cwd = cwd or os.getcwd()
        self.proc: Optional[subprocess.Popen] = None
        self.is_alive = False
        self.output_buffer = queue.Queue()
        self._reader_thread: Optional[threading.Thread] = None
        self._start_time = time.time()
        self._start_pty()

    def _start_pty(self):
        """Starts the interactive shell process."""
        shell_cmd = self.command
        if "cmd" in self.command.lower() and "/k" not in self.command.lower():
            shell_cmd = f"{self.command} /k"
        elif "powershell" in self.command.lower() and "-noexit" not in self.command.lower():
            shell_cmd = f"{self.command} -NoExit"

        try:
            self.proc = subprocess.Popen(
                shell_cmd,
                cwd=self.cwd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                shell=False
            )
            self.is_alive = True
            self._reader_thread = threading.Thread(target=self._read_loop_subprocess, daemon=True)
            self._reader_thread.start()
            logger.info(f"🖥️ Interactive terminal session started [{self.session_id}]: '{shell_cmd}'")
        except Exception as e:
            self.is_alive = False
            logger.error(f"Failed to start terminal session [{self.session_id}]: {e}")

    def _read_loop_subprocess(self):
        """Reads lines from standard subprocess stdout."""
        while self.is_alive and self.proc and self.proc.stdout:
            try:
                line = self.proc.stdout.readline()
                if line:
                    self.output_buffer.put(line)
                elif self.proc.poll() is not None:
                    self.is_alive = False
                    break
                else:
                    time.sleep(0.02)
            except Exception:
                self.is_alive = False
                break

    def write(self, data: str) -> bool:
        """Sends keystrokes or commands into the terminal."""
        if not self.is_alive or not self.proc or not self.proc.stdin:
            return False
        try:
            clean_data = data if data.endswith("\n") else data + "\n"
            self.proc.stdin.write(clean_data)
            self.proc.stdin.flush()
            return True
        except Exception as e:
            logger.error(f"Error writing to terminal [{self.session_id}]: {e}")
            self.is_alive = False
            return False

    def read_all(self) -> str:
        """Drains and returns all available output currently in the buffer."""
        lines = []
        while not self.output_buffer.empty():
            try:
                lines.append(self.output_buffer.get_nowait())
            except queue.Empty:
                break
        return "".join(lines)

    def close(self):
        """Terminates the shell process and releases resources."""
        self.is_alive = False
        try:
            if self.proc:
                self.proc.terminate()
                self.proc.kill()
        except Exception:
            pass


class ProcessRegistry:
    """
    Central registry tracking active interactive PTY sessions and daemon processes.
    """
    _instance: Optional['ProcessRegistry'] = None
    _lock = threading.Lock()

    def __init__(self):
        self.sessions: Dict[str, InteractivePTYSession] = {}

    @classmethod
    def get_instance(cls) -> 'ProcessRegistry':
        with cls._lock:
            if cls._instance is None:
                cls._instance = cls()
            return cls._instance

    def create_session(self, command: str = "cmd.exe", cwd: Optional[str] = None) -> Dict[str, Any]:
        """Creates and registers a new interactive PTY terminal session."""
        session_id = f"pty_{uuid.uuid4().hex[:8]}"
        session = InteractivePTYSession(session_id, command=command, cwd=cwd)
        with self._lock:
            self.sessions[session_id] = session

        # Collect initial prompt banner
        time.sleep(0.15)
        initial_output = session.read_all()

        return {
            "status": "success",
            "session_id": session_id,
            "command": command,
            "is_alive": session.is_alive,
            "initial_output": initial_output
        }

    def write_to_session(self, session_id: str, input_text: str) -> Dict[str, Any]:
        """Writes input into an active session and collects the response."""
        session = self.sessions.get(session_id)
        if not session:
            return {"status": "error", "message": f"Session '{session_id}' not found."}

        if not session.is_alive:
            return {"status": "error", "message": f"Session '{session_id}' has terminated."}

        success = session.write(input_text)
        if not success:
            return {"status": "error", "message": "Failed to write to terminal."}

        # Poll for response up to 0.5s
        output = ""
        for _ in range(10):
            time.sleep(0.05)
            chunk = session.read_all()
            if chunk:
                output += chunk
                break

        return {
            "status": "success",
            "session_id": session_id,
            "output": output,
            "is_alive": session.is_alive
        }

    def read_from_session(self, session_id: str) -> Dict[str, Any]:
        """Reads output from a terminal session."""
        session = self.sessions.get(session_id)
        if not session:
            return {"status": "error", "message": f"Session '{session_id}' not found."}

        output = session.read_all()
        return {
            "status": "success",
            "session_id": session_id,
            "output": output,
            "is_alive": session.is_alive
        }

    def close_session(self, session_id: str) -> Dict[str, Any]:
        """Closes a terminal session."""
        with self._lock:
            session = self.sessions.pop(session_id, None)
        if session:
            session.close()
            return {"status": "success", "message": f"Terminal session '{session_id}' closed."}
        return {"status": "error", "message": f"Session '{session_id}' not found."}

    def list_sessions(self) -> Dict[str, Any]:
        """Lists all active terminal sessions."""
        active = []
        for sid, sess in list(self.sessions.items()):
            active.append({
                "session_id": sid,
                "command": sess.command,
                "is_alive": sess.is_alive,
                "uptime_sec": int(time.time() - sess._start_time)
            })
        return {"status": "success", "sessions": active, "count": len(active)}


# Singleton instance
process_registry = ProcessRegistry.get_instance()


# ─── Tool Declarations ────────────────────────────────────────────────────────

def start_terminal_session(command: str = "cmd.exe") -> Dict[str, Any]:
    """Start an interactive persistent terminal session (PowerShell/CMD)."""
    return process_registry.create_session(command=command)


def write_terminal(session_id: str, input_text: str) -> Dict[str, Any]:
    """Send commands or keystrokes to an active interactive terminal session."""
    return process_registry.write_to_session(session_id=session_id, input_text=input_text)


def read_terminal(session_id: str) -> Dict[str, Any]:
    """Read pending output from an active interactive terminal session."""
    return process_registry.read_from_session(session_id=session_id)


def close_terminal_session(session_id: str) -> Dict[str, Any]:
    """Close and clean up an active interactive terminal session."""
    return process_registry.close_session(session_id=session_id)


def list_terminal_sessions() -> Dict[str, Any]:
    """List all running interactive terminal sessions."""
    return process_registry.list_sessions()


PTY_TOOL_DECLARATIONS = [
    {
        "name": "start_terminal_session",
        "description": "Start an interactive persistent Windows PTY terminal session (PowerShell or CMD) that maintains state across multiple commands.",
        "parameters": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "Shell command to spawn (default: 'cmd.exe' or 'powershell.exe')"
                }
            }
        }
    },
    {
        "name": "write_terminal",
        "description": "Send a command or keystroke input to an active interactive terminal session.",
        "parameters": {
            "type": "object",
            "properties": {
                "session_id": {
                    "type": "string",
                    "description": "The unique terminal session ID"
                },
                "input_text": {
                    "type": "string",
                    "description": "Command or text input to execute in the terminal"
                }
            },
            "required": ["session_id", "input_text"]
        }
    },
    {
        "name": "read_terminal",
        "description": "Read any new output from an active interactive terminal session.",
        "parameters": {
            "type": "object",
            "properties": {
                "session_id": {
                    "type": "string",
                    "description": "The unique terminal session ID"
                }
            },
            "required": ["session_id"]
        }
    },
    {
        "name": "close_terminal_session",
        "description": "Terminate and clean up an active interactive terminal session.",
        "parameters": {
            "type": "object",
            "properties": {
                "session_id": {
                    "type": "string",
                    "description": "The unique terminal session ID"
                }
            },
            "required": ["session_id"]
        }
    },
    {
        "name": "list_terminal_sessions",
        "description": "List all currently running interactive terminal sessions and their uptimes.",
        "parameters": {"type": "object", "properties": {}}
    }
]

PTY_TOOL_FUNCTIONS = {
    "start_terminal_session": start_terminal_session,
    "write_terminal": write_terminal,
    "read_terminal": read_terminal,
    "close_terminal_session": close_terminal_session,
    "list_terminal_sessions": list_terminal_sessions,
}
