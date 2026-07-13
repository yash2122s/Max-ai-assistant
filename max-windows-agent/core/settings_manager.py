import os
import json
import time

# Setup base paths pointing to max-windows-agent/
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STORAGE_DIR = os.path.join(BASE_DIR, "storage")
LOG_DIR = os.path.join(STORAGE_DIR, "logs")

# Ensure storage directories exist
os.makedirs(STORAGE_DIR, exist_ok=True)
os.makedirs(LOG_DIR, exist_ok=True)

CONFIG_PATH = os.path.join(STORAGE_DIR, "config.json")
PAIRED_DEVICES_PATH = os.path.join(STORAGE_DIR, "paired_devices.json")
LOG_PATH = os.path.join(LOG_DIR, "max-agent.log")

DEFAULT_PORT = 9000
DEFAULT_HOST = "0.0.0.0"
PROTOCOL_VERSION = 1

class SettingsManager:
    def __init__(self):
        self.start_time = time.time()
        self.config_data = {}
        self.load()

    def load(self):
        if os.path.exists(CONFIG_PATH):
            try:
                with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                    self.config_data = json.load(f)
                    return
            except Exception:
                pass
        
        # Default config setup
        self.config_data = {
            "port": DEFAULT_PORT,
            "host": DEFAULT_HOST,
            "agent_name": "MAX Windows Agent",
            "protocol_version": PROTOCOL_VERSION,
            "pairing_enabled": True
        }
        self.save()

    def save(self):
        try:
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(self.config_data, f, indent=4)
        except Exception as e:
            print(f"Error saving config settings: {e}")

    def get(self, key, default=None):
        return self.config_data.get(key, default)

    def set(self, key, value):
        self.config_data[key] = value
        self.save()

# Instantiate singleton
settings_manager = SettingsManager()
