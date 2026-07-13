import os
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
    # Retrieve current configuration data from settings manager
    settings_manager.load()
    return settings_manager.config_data

def save_config(config_data):
    # Set and persist config data using settings manager
    settings_manager.config_data = config_data
    settings_manager.save()
