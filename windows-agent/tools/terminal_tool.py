import asyncio
import subprocess
import logging
import time

logger = logging.getLogger("MAXWindowsAgent.Terminal")

# Track active subprocesses globally
active_processes = {}
# Track cancelled tasks to return correct protocol status codes
cancelled_tasks = set()

class TerminalHelper:
    def classify_command(self, command: str) -> str:
        cmd_lower = command.lower()
        dangerous_keywords = ["del ", "format ", "reg ", "shutdown", "rmdir", "remove-item"]
        medium_keywords = ["taskkill", "stop-service", "kill"]
        
        for kw in dangerous_keywords:
            if kw in cmd_lower:
                return "DANGEROUS"
        for kw in medium_keywords:
            if kw in cmd_lower:
                return "MEDIUM"
        return "SAFE"

    def show_pc_confirmation(self, command: str) -> bool:
        try:
            import ctypes
            # MB_YESNO = 4, MB_ICONWARNING = 0x30, IDYES = 6
            result = ctypes.windll.user32.MessageBoxW(
                0,
                f"MAX AI is requesting to run a dangerous terminal command:\n\n{command}\n\nDo you want to allow this command to run?",
                "MAX Agent Security Alert",
                4 | 0x30
            )
            return result == 6
        except Exception as e:
            logger.error(f"Error showing PC confirmation dialog: {e}")
            return False

    async def run_command(self, command: str, request_id: str, send_progress, confirmed: bool = False) -> dict:
        """
        Executes a shell command asynchronously, streaming batched outputs back to the client.
        """
        logger.info(f"Starting terminal task '{request_id}' with command: {command} (confirmed={confirmed})")
        
        safety = self.classify_command(command)
        if safety == "DANGEROUS":
            # Prompt native PC UI warning dialog in background thread
            loop = asyncio.get_running_loop()
            allowed = await loop.run_in_executor(None, self.show_pc_confirmation, command)
            if not allowed:
                return {
                    "status": "failed",
                    "error": {
                        "code": "PERMISSION_DENIED",
                        "message": "Execution of dangerous command was denied on the PC."
                    }
                }
        elif safety == "MEDIUM" and not confirmed:
            return {
                "status": "failed",
                "error": {
                    "code": "CONFIRMATION_REQUIRED",
                    "message": f"Command '{command}' requires user confirmation. Ask the user if they wish to proceed."
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
        
        try:
            while True:
                try:
                    # Read line with timeout to allow periodic flushing
                    line = await asyncio.wait_for(process.stdout.readline(), timeout=0.1)
                    if not line:
                        break
                    decoded_line = line.decode("utf-8", errors="ignore")
                    buffer.append(decoded_line)
                    
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
                
            # Wait for process exit code
            rc = await process.wait()
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
