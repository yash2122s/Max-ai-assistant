"""
JARVIS Configuration
Central configuration for the Windows AI Agent and Companion Server.
"""

import os
from dotenv import load_dotenv

load_dotenv()

# Server & Companion settings imports
try:
    from core.settings_manager import (
        BASE_DIR,
        STORAGE_DIR,
        LOG_DIR,
        CONFIG_PATH,
        PAIRED_DEVICES_PATH,
        LOG_PATH,
        DEFAULT_PORT,
        DEFAULT_HOST,
        PROTOCOL_VERSION,
        settings_manager
    )
    def load_config():
        settings_manager.load()
        return settings_manager.config_data

    def save_config(config_data):
        settings_manager.config_data = config_data
        settings_manager.save()
except Exception:
    PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
    LOG_DIR = os.path.join(PROJECT_ROOT, "logs")
    os.makedirs(LOG_DIR, exist_ok=True)
    LOG_PATH = os.path.join(LOG_DIR, "agent.log")

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
GEMINI_MODEL = "gemini-3.1-flash-live-preview"  # Gemini Live API model for Google AI Studio

# ─── Audio Settings ───────────────────────────────────────────────────────────
INPUT_SAMPLE_RATE = 16000
INPUT_CHANNELS = 1
INPUT_CHUNK_SIZE = 1024  # ~64ms chunks at 16kHz
INPUT_FORMAT_WIDTH = 2   # 16-bit = 2 bytes

OUTPUT_SAMPLE_RATE = 24000
OUTPUT_CHANNELS = 1
OUTPUT_FORMAT_WIDTH = 2  # 16-bit = 2 bytes

# ─── JARVIS Personality ──────────────────────────────────────────────────────
JARVIS_NAME = os.getenv("JARVIS_NAME", "JARVIS")
JARVIS_VOICE = os.getenv("JARVIS_VOICE", "Aoede")  # Female voice preset (Aoede / Kore)

SYSTEM_PROMPT = f"""You are {JARVIS_NAME}, an advanced Windows AI assistant inspired by JARVIS from Iron Man.

LANGUAGE & VOICE INSTRUCTIONS (TENGLISH):
- You MUST understand and speak fluently in TENGLISH (Telugu language written using English/Latin script).
- Respond in natural Tenglish using English letters (e.g., "Namaskaram sir! Chrome open chestunnanu", "Sure boss, volume 50 percent pettanu", "WhatsApp lo Bannu ki message pampanu sir").

TOOL AUTOMATION RULES:
- For "Notepad open chesi X ani type cheyyi": Call open_application("notepad"), then call type_text("X").
- For "WhatsApp open chesi X ki Y ani message pettu/cheyyi": Call send_whatsapp_message(contact_name="X", message="Y").
- For "Present screen paina ey app undhi?" / "Current app name enti?": Call get_active_window().
- For "Ee app close cheyyi" / "Current window close cheyyi" / "Screen paina unna app close cheyyi": Call close_active_window().
- For "gurthupettuko" / "remember this" / "idi remember cheyyi" (e.g. "na name Yaswanth", "bannu number 987..."): Call remember_fact(key=..., value=...).
- For "gurthundha?" / "recall cheyyi" / "na details enti?": Call recall_fact(query=...) or list_memories().
- For "Chrome open cheyyi", "Volume 50 percent pettu", "Screenshot theeyi", "Battery entha undhi?": Call appropriate tool immediately.

CRITICAL RULES:
1. NEVER execute destructive actions (shutdown, restart, delete files) without explicit confirmation.
2. When a tool completes, report the result concisely in Tenglish (1-2 short sentences).
3. Address the user as "sir" or "boss".
"""

# ─── Security ─────────────────────────────────────────────────────────────────
DANGEROUS_TOOLS = frozenset({
    "shutdown_restart",
    "run_command",
    "move_file",
    "delete_file",
    "lock_screen",
})

ALLOWED_COMMANDS = frozenset({
    "ipconfig", "systeminfo", "hostname", "whoami",
    "dir", "cls", "echo", "ping", "tracert", "nslookup",
    "tasklist", "netstat", "wmic",
})

# ─── Reconnection ────────────────────────────────────────────────────────────
RECONNECT_BASE_DELAY = 1.0    # Initial retry delay in seconds
RECONNECT_MAX_DELAY = 30.0    # Max retry delay (exponential backoff cap)
RECONNECT_MAX_ATTEMPTS = 10   # Give up after N consecutive failures

# ─── Paths ────────────────────────────────────────────────────────────────────
PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
SCREENSHOT_DIR = os.path.join(PROJECT_ROOT, "screenshots")
os.makedirs(SCREENSHOT_DIR, exist_ok=True)
