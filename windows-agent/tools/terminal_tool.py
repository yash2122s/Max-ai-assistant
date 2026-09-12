import asyncio
import subprocess
import logging
import time

logger = logging.getLogger("MAXWindowsAgent.Terminal")

# Track active subprocesses globally
active_processes = {}
# Track cancelled tasks to return correct protocol status codes
cancelled_tasks = set()

from core.security import security_guard

class TerminalHelper:
    def classify_command(self, command: str) -> str:
        return security_guard.classify_command_risk(command)

    def show_pc_confirmation(self, command: str) -> bool:
        try:
            import ctypes
            # MB_YESNO = 4, MB_ICONWARNING = 0x30, IDYES = 6
            result = ctypes.windll.user32.MessageBoxW(
                0,
                f"MAX AI is requesting to run a high-risk terminal command:\n\n{command}\n\nDo you want to allow this command to run?",
                "MAX Agent Security Alert",
                4 | 0x30
            )
            return result == 6
        except Exception as e:
            logger.error(f"Error showing PC confirmation dialog: {e}")
            return False

    async def run_command(self, command: str, request_id: str, send_progress, confirmed: bool = False, timeout_seconds: float = 120.0) -> dict:
        """
        Executes a shell command asynchronously, streaming batched outputs back to the client.
        Enforces security risk classification, timeout, and secret masking.
        """
        logger.info(f"Starting terminal task '{request_id}' with command: {command} (confirmed={confirmed})")
        
        safety = self.classify_command(command)
        if safety == "CRITICAL_BLOCKED":
            logger.warning(f"BLOCKED critical destructive command: {command}")
            return {
                "status": "failed",
                "error": {
                    "code": "SECURITY_VIOLATION",
                    "message": "Execution blocked: Destructive system-critical operations are not permitted."
                }
            }
        elif safety == "CONFIRM_REQUIRED" and not confirmed:
            # Prompt native PC UI warning dialog in background thread
            loop = asyncio.get_running_loop()
            allowed = await loop.run_in_executor(None, self.show_pc_confirmation, command)
            if not allowed:
                return {
                    "status": "failed",
                    "error": {
                        "code": "PERMISSION_DENIED",
                        "message": "Execution of high-risk command was denied."
                    }
                }

        
        try:
            import sys
            if sys.platform == "win32":
                shell_executable = "powershell.exe"
                shell_args = ["-NoProfile", "-Command", command]
            else:
                shell_executable = "sh"
                shell_args = ["-c", command]

            process = await asyncio.create_subprocess_exec(
                shell_executable,
                *shell_args,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
            )

        except Exception as e:
            logger.error(f"Failed to spawn subprocess: {e}")
            return {
                "status": "failed",
                "error": {
                    "code": "INTERNAL_ERROR",
                    "message": f"Could not launch command: {e}"
                }
            }

        active_processes[request_id] = process
        
        buffer = []
        last_flush = time.time()
        start_exec_time = time.time()
        
        try:
            while True:
                # Check overall timeout limit
                if time.time() - start_exec_time > timeout_seconds:
                    logger.warning(f"Terminal task '{request_id}' exceeded timeout limit of {timeout_seconds}s. Terminating...")
                    self.terminate_process_tree(process.pid)
                    return {
                        "status": "failed",
                        "error": {
                            "code": "TIMEOUT",
                            "message": f"Command timed out after {timeout_seconds}s."
                        }
                    }

                try:
                    # Read line with timeout to allow periodic flushing
                    line = await asyncio.wait_for(process.stdout.readline(), timeout=0.1)
                    if not line:
                        break
                    decoded_line = line.decode("utf-8", errors="ignore")
                    buffer.append(security_guard.mask_secrets(decoded_line))
                    
                    # Flush if buffer is large or 250ms has elapsed
                    if len(buffer) >= 50 or (time.time() - last_flush) >= 0.25:
                        await send_progress("".join(buffer))
                        buffer = []
                        last_flush = time.time()
                except asyncio.TimeoutError:
                    if buffer:
                        await send_progress("".join(buffer))
                        buffer = []
                        last_flush = time.time()
                    
                    if process.returncode is not None:
                        break
            
            # Final flush
            if buffer:
                await send_progress("".join(buffer))
                
            # Wait for process exit code with remaining timeout
            remaining_time = max(1.0, timeout_seconds - (time.time() - start_exec_time))
            try:
                rc = await asyncio.wait_for(process.wait(), timeout=remaining_time)
            except asyncio.TimeoutError:
                self.terminate_process_tree(process.pid)
                return {
                    "status": "failed",
                    "error": {
                        "code": "TIMEOUT",
                        "message": f"Command timed out after {timeout_seconds}s."
                    }
                }

            logger.info(f"Terminal task '{request_id}' completed with return code {rc}")
            
            # Check if this task was explicitly cancelled
            if request_id in cancelled_tasks:
                cancelled_tasks.remove(request_id)
                return {
                    "status": "failed",
                    "error": {
                        "code": "TIMEOUT",
                        "message": "Task execution was cancelled or timed out."
                    }
                }
            
            if rc == 0:
                return {"status": "success", "output": f"Command completed successfully (exit code 0)."}
            else:
                return {
                    "status": "failed",
                    "error": {
                        "code": "EXECUTION_FAILED",
                        "message": f"Command failed with non-zero exit code: {rc}"
                    }
                }
        except asyncio.CancelledError:
            logger.info(f"Terminal task '{request_id}' was cancelled dynamically. Terminating process tree...")
            self.terminate_process_tree(process.pid)
            return {
                "status": "failed",
                "error": {
                    "code": "TIMEOUT",
                    "message": "Task execution was cancelled or timed out."
                }
            }
        finally:
            active_processes.pop(request_id, None)
            cancelled_tasks.discard(request_id)

    def terminate_process_tree(self, pid: int):
        try:
            # Use taskkill to cleanly tear down child processes spawned by build scripts
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)
            logger.info(f"Successfully killed process tree for PID {pid}")
        except Exception as e:
            logger.error(f"Failed to kill process tree for PID {pid}: {e}")

    def kill_process_by_name(self, name: str) -> bool:
        try:
            result = subprocess.run(
                ["taskkill", "/F", "/IM", name if name.endswith(".exe") else f"{name}.exe"],
                capture_output=True,
                text=True
            )
            return result.returncode == 0
        except Exception as e:
            logger.error(f"Failed to kill process {name}: {e}")
            return False

    def kill_process_by_pid(self, pid: int) -> bool:
        try:
            result = subprocess.run(
                ["taskkill", "/F", "/PID", str(pid)],
                capture_output=True,
                text=True
            )
            return result.returncode == 0
        except Exception as e:
            logger.error(f"Failed to kill PID {pid}: {e}")
            return False

    def cancel_task(self, request_id: str) -> bool:
        process = active_processes.get(request_id)
        if process:
            cancelled_tasks.add(request_id)
            logger.info(f"Cancelling active task {request_id} (PID {process.pid})")
            self.terminate_process_tree(process.pid)
            return True
        return False

terminal_helper = TerminalHelper()
