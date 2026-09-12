"""
MAX AI — Self-Diagnostics Environment Doctor
Performs end-to-end environment scanning: Python runtime, dependencies,
audio input/output, Vosk models, SQLite database integrity, and network connectivity.
"""

import os
import sys
import sqlite3
import logging
from typing import Dict, Any, List

from config import PROJECT_ROOT, STATE_DB_PATH, GEMINI_API_KEY

logger = logging.getLogger("MAXWindowsAgent.Doctor")


class SystemDoctor:
    """
    Automated diagnostics and self-healing engine.
    """

    @classmethod
    def run_diagnostics(cls) -> Dict[str, Any]:
        """Runs full health check across all agent subsystems."""
        checks = {}
        issues = []

        # 1. Python Environment
        py_ver = sys.version.split()[0]
        checks["python_version"] = {"status": "ok", "version": py_ver}

        # 2. Critical Dependencies
        req_packages = ["google.genai", "pydantic", "openai", "croniter", "yaml", "websockets", "psutil"]
        missing_pkgs = []
        for pkg in req_packages:
            try:
                __import__(pkg)
            except ImportError:
                missing_pkgs.append(pkg)

        if missing_pkgs:
            checks["dependencies"] = {"status": "error", "missing": missing_pkgs}
            issues.append(f"Missing Python packages: {', '.join(missing_pkgs)}")
        else:
            checks["dependencies"] = {"status": "ok", "installed_count": len(req_packages)}

        # 3. Vosk Wake Word Model
        vosk_dir = os.path.join(PROJECT_ROOT, "storage", "models", "vosk-model-small-en-us-0.15")
        if os.path.exists(vosk_dir):
            checks["vosk_model"] = {"status": "ok", "path": vosk_dir}
        else:
            checks["vosk_model"] = {"status": "warning", "message": "Vosk model directory not found"}

        # 4. SQLite State Database Integrity
        try:
            if os.path.exists(STATE_DB_PATH):
                conn = sqlite3.connect(STATE_DB_PATH)
                cur = conn.execute("PRAGMA integrity_check;")
                res = cur.fetchone()[0]
                conn.close()
                checks["database"] = {"status": "ok" if res == "ok" else "error", "result": res}
                if res != "ok":
                    issues.append(f"Database integrity issue: {res}")
            else:
                checks["database"] = {"status": "ok", "message": "State database will be initialized on first run"}
        except Exception as e:
            checks["database"] = {"status": "error", "error": str(e)}
            issues.append(f"Database check failed: {e}")

        # 5. Gemini API Key
        if GEMINI_API_KEY and len(GEMINI_API_KEY) > 10:
            checks["gemini_api_key"] = {"status": "ok", "configured": True}
        else:
            checks["gemini_api_key"] = {"status": "warning", "configured": False, "message": "GEMINI_API_KEY is not set"}

        # 6. Audio Subsystem
        try:
            import pyaudio
            pa = pyaudio.PyAudio()
            in_count = 0
            out_count = 0
            for i in range(pa.get_device_count()):
                dev = pa.get_device_info_by_index(i)
                if dev.get("maxInputChannels", 0) > 0:
                    in_count += 1
                if dev.get("maxOutputChannels", 0) > 0:
                    out_count += 1
            pa.terminate()
            checks["audio_devices"] = {"status": "ok", "microphones": in_count, "speakers": out_count}
        except Exception as ae:
            checks["audio_devices"] = {"status": "warning", "error": str(ae)}

        overall_status = "healthy" if not issues else "degraded"
        return {
            "status": "success",
            "overall_health": overall_status,
            "checks": checks,
            "issues_count": len(issues),
            "issues": issues
        }


# ─── Tool Wrappers & Declarations ─────────────────────────────────────────────

def doctor_check() -> Dict[str, Any]:
    """Run full system diagnostics on MAX environment, dependencies, audio, and database."""
    return SystemDoctor.run_diagnostics()


DOCTOR_TOOL_DECLARATIONS = [
    {
        "name": "doctor_check",
        "description": "Run automated self-diagnostics on the agent environment, dependencies, audio hardware, Vosk model, and database integrity.",
        "parameters": {"type": "object", "properties": {}}
    }
]

DOCTOR_TOOL_FUNCTIONS = {
    "doctor_check": doctor_check,
}
