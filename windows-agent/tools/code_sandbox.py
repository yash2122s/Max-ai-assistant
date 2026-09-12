"""
MAX AI — Programmatic Tool Calling (PTC) Sandbox
Executes LLM-generated Python scripts in a subprocess communicating via loopback TCP RPC
to collapse multi-step tool workflows into a single execution turn.
"""

import os
import sys
import json
import time
import socket
import secrets
import logging
import tempfile
import threading
import subprocess
from typing import Dict, Any, List, Optional

from config import PROJECT_ROOT

logger = logging.getLogger("MAXWindowsAgent.CodeSandbox")


class LocalToolRPCServer:
    """
    Lightweight Loopback TCP RPC server exposing selected MAX tools to sandbox subprocesses.
    """

    def __init__(self, auth_token: str):
        self.auth_token = auth_token
        self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_sock.bind(("127.0.0.1", 0))
        self.port = self.server_sock.getsockname()[1]
        self.server_sock.listen(5)
        self._running = True
        self._thread = threading.Thread(target=self._listen_loop, daemon=True)
        self._thread.start()

    def _listen_loop(self):
        self.server_sock.settimeout(1.0)
        while self._running:
            try:
                client_sock, _ = self.server_sock.accept()
                threading.Thread(target=self._handle_client, args=(client_sock,), daemon=True).start()
            except socket.timeout:
                continue
            except Exception:
                break

    def _handle_client(self, client_sock: socket.socket):
        try:
            client_sock.settimeout(10.0)
            data_raw = b""
            while b"\n" not in data_raw:
                chunk = client_sock.recv(4096)
                if not chunk:
                    break
                data_raw += chunk

            if not data_raw:
                client_sock.close()
                return

            req = json.loads(data_raw.decode("utf-8"))
            if req.get("token") != self.auth_token:
                resp = {"status": "error", "message": "Authentication failed"}
            else:
                tool_name = req.get("tool")
                tool_args = req.get("args", {})
                from core.unified_tool_registry import unified_tool_registry
                # Sandbox allowed tools only
                allowed_sandbox_tools = {
                    "web_search", "read_file", "write_file", "search_files", "patch", "apply_patch",
                    "execute_device_action", "list_connected_devices", "remember_fact", "recall_fact"
                }
                if tool_name not in allowed_sandbox_tools:
                    resp = {"status": "error", "message": f"Tool '{tool_name}' is not permitted in sandbox."}
                else:
                    result = unified_tool_registry.execute(tool_name, tool_args)
                    resp = {"status": "success", "result": result}

            client_sock.sendall((json.dumps(resp) + "\n").encode("utf-8"))
        except Exception as e:
            try:
                client_sock.sendall((json.dumps({"status": "error", "message": str(e)}) + "\n").encode("utf-8"))
            except Exception:
                pass
        finally:
            client_sock.close()

    def close(self):
        self._running = False
        try:
            self.server_sock.close()
        except Exception:
            pass


class ProgrammaticToolSandbox:
    """
    Manages script generation, stub injection, and execution.
    """

    def execute_code(self, code: str, timeout_sec: float = 60.0) -> Dict[str, Any]:
        """
        Executes Python code in an isolated subprocess with hermes_tools stub module available.
        """
        if not code or not code.strip():
            return {"status": "error", "message": "Code cannot be empty."}

        auth_token = secrets.token_hex(16)
        rpc_server = LocalToolRPCServer(auth_token)

        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                # 1. Write hermes_tools.py stub
                stub_code = f"""
import json
import socket

_PORT = {rpc_server.port}
_TOKEN = "{auth_token}"

def _call_tool(tool_name, args):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect(("127.0.0.1", _PORT))
    req = {{"token": _TOKEN, "tool": tool_name, "args": args}}
    s.sendall((json.dumps(req) + "\\n").encode("utf-8"))
    raw = b""
    while b"\\n" not in raw:
        chunk = s.recv(4096)
        if not chunk:
            break
        raw += chunk
    s.close()
    res = json.loads(raw.decode("utf-8"))
    if res.get("status") == "success":
        return res.get("result")
    raise RuntimeError(res.get("message", "RPC call failed"))

def web_search(query): return _call_tool("web_search", {{"query": query}})
def read_file(path): return _call_tool("read_file", {{"path": path}})
def write_file(path, content): return _call_tool("write_file", {{"path": path, "content": content}})
def search_files(pattern): return _call_tool("search_files", {{"pattern": pattern}})
def apply_patch(target_file, diff_content): return _call_tool("apply_patch", {{"target_file": target_file, "diff_content": diff_content}})
def execute_device_action(target, action, arguments=None): return _call_tool("execute_device_action", {{"target": target, "action": action, "arguments": arguments or {{}}}})
def list_connected_devices(): return _call_tool("list_connected_devices", {{}})
def remember_fact(key, value): return _call_tool("remember_fact", {{"key": key, "value": value}})
def recall_fact(query): return _call_tool("recall_fact", {{"query": query}})
"""
                with open(os.path.join(temp_dir, "hermes_tools.py"), "w", encoding="utf-8") as f:
                    f.write(stub_code)

                # 2. Write user script
                script_path = os.path.join(temp_dir, "script.py")
                with open(script_path, "w", encoding="utf-8") as f:
                    f.write(code)

                # 3. Run subprocess
                env = dict(os.environ)
                env["PYTHONPATH"] = temp_dir

                proc = subprocess.Popen(
                    [sys.executable, script_path],
                    cwd=PROJECT_ROOT,
                    env=env,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True
                )

                try:
                    stdout, stderr = proc.communicate(timeout=timeout_sec)
                    exit_code = proc.returncode
                except subprocess.TimeoutExpired:
                    proc.kill()
                    return {"status": "error", "message": f"Execution timed out after {timeout_sec} seconds."}

                return {
                    "status": "success" if exit_code == 0 else "failed",
                    "exit_code": exit_code,
                    "stdout": stdout.strip(),
                    "stderr": stderr.strip()
                }

        finally:
            rpc_server.close()


# Singleton instance
code_sandbox = ProgrammaticToolSandbox()


# ─── Tool Wrappers & Declarations ─────────────────────────────────────────────

def execute_code(code: str) -> Dict[str, Any]:
    """Execute a Python script in a sandbox that can call MAX tools programmatically via `import hermes_tools`."""
    return code_sandbox.execute_code(code=code)


SANDBOX_TOOL_DECLARATIONS = [
    {
        "name": "execute_code",
        "description": "Execute a Python script inside a programmatic tool sandbox. The script can `import hermes_tools` and invoke `web_search()`, `read_file()`, `write_file()`, `apply_patch()`, `execute_device_action()` to chain multiple operations in one fast step.",
        "parameters": {
            "type": "object",
            "properties": {
                "code": {
                    "type": "string",
                    "description": "Python source code to execute"
                }
            },
            "required": ["code"]
        }
    }
]

SANDBOX_TOOL_FUNCTIONS = {
    "execute_code": execute_code,
}
