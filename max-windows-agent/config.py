import os
import json

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
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

def load_config():
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r") as f:
                return json.load(f)
        except Exception:
            pass
    
    # Save default config if not found
    default_config = {
        "port": DEFAULT_PORT,
        "host": DEFAULT_HOST,
        "agent_name": "MAX Windows Agent",
        "protocol_version": PROTOCOL_VERSION,
        "pairing_enabled": True
    }
    save_config(default_config)
    return default_config

def save_config(config_data):
    with open(CONFIG_PATH, "w") as f:
        json.dump(config_data, f, indent=4)
