import ctypes
import logging
import subprocess
from .base_tool import BaseTool

logger = logging.getLogger("MAXWindowsAgent.Power")

class PowerHelper:
    def __init__(self):
        self.user32 = ctypes.windll.user32
        self.kernel32 = ctypes.windll.kernel32

    def shutdown(self, force: bool = False, timeout: int = 30) -> dict:
        try:
            flag = "/s" if not force else "/s /f"
            subprocess.run(["shutdown", flag, "/t", str(timeout)], capture_output=True, timeout=5)
            return {"status": "success", "message": f"Shutdown initiated. System will shutdown in {timeout}s."}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def restart(self, force: bool = False, timeout: int = 30) -> dict:
        try:
            flag = "/r" if not force else "/r /f"
            subprocess.run(["shutdown", flag, "/t", str(timeout)], capture_output=True, timeout=5)
            return {"status": "success", "message": f"Restart initiated. System will restart in {timeout}s."}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def sleep(self) -> dict:
        try:
            self.kernel32.SetSuspendState(0, 0, 0)
            return {"status": "success", "message": "System entering sleep mode."}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def hibernate(self) -> dict:
        try:
            self.kernel32.SetSuspendState(1, 0, 0)
            return {"status": "success", "message": "System entering hibernation."}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def lock(self) -> dict:
        try:
            self.user32.LockWorkStation()
            return {"status": "success", "message": "Workstation locked."}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def logout(self) -> dict:
        try:
            ExitWindowsEx = ctypes.windll.user32.ExitWindowsEx
            ExitWindowsEx(0, 0)
            return {"status": "success", "message": "User logging out."}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def cancel_shutdown(self) -> dict:
        try:
            subprocess.run(["shutdown", "/a"], capture_output=True, timeout=5)
            return {"status": "success", "message": "Pending shutdown cancelled."}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def get_power_status(self) -> dict:
        try:
            res = subprocess.run(["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Battery)"], capture_output=True, text=True, timeout=10)
            battery_available = res.returncode == 0 and res.stdout.strip()

            ups_info = {}
            battery_info = {}

            if battery_available:
                for line in res.stdout.splitlines():
                    if ":" in line:
                        k, v = line.split(":", 1)
                        k = k.strip()
                        v = v.strip()
                        if k == "EstimatedChargeRemaining":
                            battery_info["charge_percent"] = int(v)
                        elif k == "BatteryStatus":
                            battery_info["is_charging"] = int(v) == 2
                        elif k == "EstimatedRunTime":
                            battery_info["estimated_minutes"] = int(v) if v.isdigit() else 0

            return {
                "on_battery": not battery_info.get("is_charging", True) if battery_info else False,
                "battery": battery_info if battery_info else None,
                "ups": ups_info if ups_info else None,
                "power_plan": self._get_power_plan()
            }
        except Exception as e:
            logger.error(f"Failed to get power status: {e}")
            return {"error": str(e)}

    def _get_power_plan(self) -> str:
        try:
            res = subprocess.run(["powershell", "-NoProfile", "-Command", "(Get-CimInstance -Namespace root\\cimv2\\power -ClassName Win32_PowerPlan | Where-Object {$_.IsActive -eq $true}).ElementName"], capture_output=True, text=True, timeout=10)
            if res.returncode == 0 and res.stdout.strip():
                return res.stdout.strip()
            return "Unknown"
        except Exception:
            return "Unknown"

    def set_power_plan(self, plan: str) -> dict:
        plans = {"balanced": "381b4222-f694-41f0-9685-ff5bb260df2e",
                 "powersaver": "a1841308-3541-4fab-bc81-f71556f20b4a",
                 "highperformance": "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c"}
        guid = plans.get(plan.lower().replace(" ", ""))
        if not guid:
            return {"status": "failed", "error": f"Unknown plan: {plan}. Options: balanced, powersaver, highperformance"}
        try:
            subprocess.run(["powercfg", "/setactive", guid], capture_output=True, timeout=10)
            return {"status": "success", "message": f"Power plan changed to {plan}"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}


power_helper = PowerHelper()


class PowerTool(BaseTool):
    @property
    def name(self) -> str:
        return "power"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running power/{action}...")
        try:
            if action == "shutdown":
                result = power_helper.shutdown(
                    force=arguments.get("force", False),
                    timeout=int(arguments.get("timeout", 30))
                )
                return result
            elif action == "restart":
                result = power_helper.restart(
                    force=arguments.get("force", False),
                    timeout=int(arguments.get("timeout", 30))
                )
                return result
            elif action == "sleep":
                result = power_helper.sleep()
                return result
            elif action == "hibernate":
                result = power_helper.hibernate()
                return result
            elif action == "lock":
                result = power_helper.lock()
                return result
            elif action == "logout":
                result = power_helper.logout()
                return result
            elif action == "cancel_shutdown":
                result = power_helper.cancel_shutdown()
                return result
            elif action == "status":
                result = power_helper.get_power_status()
                return {"status": "success", "output": result}
            elif action == "set_plan":
                plan = arguments.get("plan", "balanced")
                result = power_helper.set_power_plan(plan)
                return result
            else:
                return {
                    "status": "failed",
                    "error": {"code": "UNSUPPORTED_ACTION", "message": f"Unknown power action: {action}"}
                }
        except Exception as e:
            logger.error(f"PowerTool error: {e}", exc_info=True)
            return {"status": "failed", "error": {"code": "EXECUTION_EXCEPTION", "message": str(e)}}
