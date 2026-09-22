"""
MAX AI — Model Context Protocol (MCP) JSON-RPC 2.0 Transports
Provides robust stdio subprocess and HTTP/SSE transports for external MCP servers.
"""

import os
import sys
import json
import time
import queue
import logging
import threading
import subprocess
from typing import Dict, Any, Optional, List, Tuple

from core.mcp.models import MCPServerConfig, MCPTransportType
from core.mcp.mcp_errors import (
    MCPConnectionError, MCPTimeoutError, MCPProtocolError
)

logger = logging.getLogger("MAX.MCP.Transport")


class MCPStdioTransport:
    """JSON-RPC 2.0 transport over stdin/stdout pipes to a child subprocess."""

    def __init__(self, config: MCPServerConfig):
        self.config = config
        self._process: Optional[subprocess.Popen] = None
        self._request_id = 0
        self._pending_requests: Dict[int, queue.Queue] = {}
        self._reader_thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()
        self._running = False

    def start(self):
        """Spawns the MCP server subprocess and starts background line reader."""
        with self._lock:
            if self._running:
                return

            if not self.config.command:
                raise MCPConnectionError(f"No command specified for MCP server '{self.config.name}'")

            cmd_list = [self.config.command] + list(self.config.args)
            env = os.environ.copy()
            if self.config.env:
                env.update(self.config.env)

            try:
                logger.info(f"🚀 Spawning MCP server '{self.config.name}': {cmd_list}")
                self._process = subprocess.Popen(
                    cmd_list,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    bufsize=1,
                    env=env,
                    shell=False  # Security invariant: NO shell=True
                )
                self._running = True

                self._reader_thread = threading.Thread(
                    target=self._read_stdout_loop,
                    name=f"MCPReader-{self.config.name}",
                    daemon=True
                )
                self._reader_thread.start()

                # Perform JSON-RPC initialization handshake
                self._send_initialize()

            except Exception as e:
                self._running = False
                raise MCPConnectionError(f"Failed to start MCP server '{self.config.name}': {e}", server_name=self.config.name)

    def _send_initialize(self):
        """Sends the standard MCP 'initialize' and 'notifications/initialized' requests."""
        init_payload = {
            "protocolVersion": "2024-11-05",
            "capabilities": {
                "tools": {"listChanged": True}
            },
            "clientInfo": {
                "name": "MAX-Windows-Agent",
                "version": "1.0.0"
            }
        }
        res = self.send_request("initialize", init_payload, timeout_sec=15.0)
        logger.info(f"🤝 MCP server '{self.config.name}' initialized: {res.get('serverInfo', {})}")

        # Send initialized notification
        self.send_notification("notifications/initialized", {})

    def send_request(self, method: str, params: Optional[Dict[str, Any]] = None, timeout_sec: Optional[float] = None) -> Dict[str, Any]:
        """Sends a JSON-RPC request and blocks for response."""
        if not self._running or not self._process or self._process.poll() is not None:
            raise MCPConnectionError(f"MCP server '{self.config.name}' is not running.", server_name=self.config.name)

        timeout = timeout_sec or self.config.timeout_sec

        with self._lock:
            self._request_id += 1
            req_id = self._request_id
            resp_queue = queue.Queue(maxsize=1)
            self._pending_requests[req_id] = resp_queue

        rpc_msg = {
            "jsonrpc": "2.0",
            "id": req_id,
            "method": method,
            "params": params or {}
        }

        raw_line = json.dumps(rpc_msg) + "\n"
        try:
            self._process.stdin.write(raw_line)
            self._process.stdin.flush()
        except Exception as e:
            with self._lock:
                self._pending_requests.pop(req_id, None)
            raise MCPConnectionError(f"Failed to write to MCP stdin: {e}", server_name=self.config.name)

        try:
            response = resp_queue.get(timeout=timeout)
            if "error" in response:
                err = response["error"]
                raise MCPProtocolError(f"MCP Error [{err.get('code')}]: {err.get('message')}", server_name=self.config.name)
            return response.get("result", {})
        except queue.Empty:
            raise MCPTimeoutError(
                f"MCP request '{method}' (ID: {req_id}) timed out after {timeout}s",
                server_name=self.config.name
            )
        finally:
            with self._lock:
                self._pending_requests.pop(req_id, None)

    def send_notification(self, method: str, params: Optional[Dict[str, Any]] = None):
        """Sends a one-way notification without expecting a response."""
        if not self._running or not self._process:
            return

        rpc_msg = {
            "jsonrpc": "2.0",
            "method": method,
            "params": params or {}
        }
        raw_line = json.dumps(rpc_msg) + "\n"
        try:
            self._process.stdin.write(raw_line)
            self._process.stdin.flush()
        except Exception as e:
            logger.debug(f"Failed to send MCP notification: {e}")

    def _read_stdout_loop(self):
        """Continuously reads stdout lines from the child process and dispatches JSON-RPC responses."""
        while self._running and self._process:
            try:
                line = self._process.stdout.readline()
                if not line:
                    break
                
                line = line.strip()
                if not line:
                    continue

                try:
                    data = json.loads(line)
                except json.JSONDecodeError:
                    logger.debug(f"[{self.config.name}] Non-JSON line from MCP server: {line[:120]}")
                    continue

                req_id = data.get("id")
                if req_id is not None:
                    with self._lock:
                        q = self._pending_requests.get(req_id)
                    if q:
                        q.put(data)
                else:
                    # Notification or server request
                    logger.debug(f"[{self.config.name}] MCP server notification: {data.get('method')}")

            except Exception as e:
                logger.debug(f"MCP reader loop exit for '{self.config.name}': {e}")
                break

        self._running = False
        logger.info(f"🛑 MCP transport for '{self.config.name}' terminated.")

    def stop(self):
        """Terminates subprocess cleanly."""
        self._running = False
        if self._process:
            try:
                self._process.terminate()
                self._process.wait(timeout=2.0)
            except Exception:
                try:
                    self._process.kill()
                except Exception:
                    pass
            self._process = None
