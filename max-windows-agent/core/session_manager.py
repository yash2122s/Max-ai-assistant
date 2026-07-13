import os
import json
import secrets
import random
from typing import List, Dict, Optional
from .settings_manager import PAIRED_DEVICES_PATH

class SessionManager:
    def __init__(self):
        self.pairing_code = str(random.randint(100000, 999999))
        self.paired_devices: List[Dict[str, str]] = self._load_paired_devices()

    def get_pairing_code(self) -> str:
        return self.pairing_code

    def regenerate_pairing_code(self) -> str:
        self.pairing_code = str(random.randint(100000, 999999))
        return self.pairing_code

    def _load_paired_devices(self) -> List[Dict[str, str]]:
        if os.path.exists(PAIRED_DEVICES_PATH):
            try:
                with open(PAIRED_DEVICES_PATH, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception:
                pass
        return []

    def _save_paired_devices(self):
        try:
            with open(PAIRED_DEVICES_PATH, "w", encoding="utf-8") as f:
                json.dump(self.paired_devices, f, indent=4)
        except Exception as e:
            print(f"Error saving paired devices: {e}")

    def pair_device(self, device_id: str, device_name: str, code: str) -> Optional[str]:
        if code != self.pairing_code:
            return None
        
        # Update token if device is already registered
        for device in self.paired_devices:
            if device.get("device_id") == device_id:
                token = secrets.token_hex(16)
                device["token"] = token
                device["device_name"] = device_name
                self._save_paired_devices()
                return token
                
        # Register a new device
        token = secrets.token_hex(16)
        self.paired_devices.append({
            "device_id": device_id,
            "device_name": device_name,
            "token": token
        })
        self._save_paired_devices()
        return token

    def verify_token(self, device_id: str, token: str) -> bool:
        for device in self.paired_devices:
            if device.get("device_id") == device_id and device.get("token") == token:
                return True
        return False

# Global singleton
session_manager = SessionManager()
