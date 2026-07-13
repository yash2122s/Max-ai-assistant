import os
import shutil
import subprocess
from .base_tool import BaseTool

class CmdTool(BaseTool):
    def __init__(self):
        self.cwd = os.getcwd()

    @property
    def name(self) -> str:
        return "cmd"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running cmd/{action}...")
        
        try:
            if action == "dir":
                target_path = arguments.get("path", ".")
                target_path = os.path.expanduser(target_path)
                full_path = os.path.abspath(os.path.join(self.cwd, target_path))
                
                if not os.path.exists(full_path):
                    return {
                        "status": "failed", 
                        "error": {
                            "code": "NOT_FOUND",
                            "message": f"Directory not found: {target_path}",
                            "retryable": False
                        }
                    }
                
                entries = os.listdir(full_path)
                lines = [f"Directory listing of {full_path}:"]
                for entry in entries:
                    entry_path = os.path.join(full_path, entry)
                    is_dir = os.path.isdir(entry_path)
                    type_label = "<DIR>" if is_dir else f"{os.path.getsize(entry_path)} bytes"
                    lines.append(f"  {entry}   {type_label}")
                
                return {"status": "success", "output": "\n".join(lines)}
                
            elif action == "echo":
                message = arguments.get("message", "")
                return {"status": "success", "output": message}
                
            elif action == "cd":
                target_path = arguments.get("path", ".")
                target_path = os.path.expanduser(target_path)
                full_path = os.path.abspath(os.path.join(self.cwd, target_path))
                
                if not os.path.exists(full_path):
                    return {
                        "status": "failed",
                        "error": {
                            "code": "NOT_FOUND",
                            "message": f"Path not found: {target_path}",
                            "retryable": False
                        }
                    }
                
                self.cwd = full_path
                return {"status": "success", "output": f"Directory changed to {self.cwd}"}
                
            elif action == "where":
                program = arguments.get("program", "")
                if not program:
                    return {
                        "status": "failed",
                        "error": {
                            "code": "BAD_ARGUMENT",
                            "message": "Program parameter is required",
                            "retryable": False
                        }
                    }
                
                # Check via shutil.which first
                program_path = shutil.which(program)
                if program_path:
                    return {"status": "success", "output": program_path}
                
                # Fallback to system where command
                result = subprocess.run(
                    ["where", program],
                    capture_output=True,
                    text=True,
                    shell=True,
                    cwd=self.cwd
                )
                if result.returncode == 0:
                    return {"status": "success", "output": result.stdout.strip()}
                else:
                    return {
                        "status": "failed",
                        "error": {
                            "code": "NOT_FOUND",
                            "message": f"Could not locate program: {program}",
                            "retryable": False
                        }
                    }
            
            else:
                return {
                    "status": "failed",
                    "error": {
                        "code": "UNSUPPORTED_ACTION",
                        "message": f"Unknown cmd action: {action}",
                        "retryable": False
                    }
                }
                
        except Exception as e:
            return {
                "status": "failed",
                "error": {
                    "code": "EXECUTION_EXCEPTION",
                    "message": str(e),
                    "retryable": False
                }
            }
