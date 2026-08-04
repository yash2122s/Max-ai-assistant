import os
import json
import secrets
import random
import hmac
import hashlib
from typing import List, Dict, Optional
from .settings_manager import PAIRED_DEVICES_PATH

import time

class SessionManager:
    def __init__(self):
        self.pairing_code = ""
        self.pairing_code_timestamp = 0.0
        self.regenerate_pairing_code()
        self.paired_devices: List[Dict[str, str]] = self._load_paired_devices()
        self.active_challenges: Dict[str, Dict[str, float]] = {}

    def get_pairing_code(self) -> str:
        # Auto-regenerate if code is older than 120 seconds
        if time.time() - self.pairing_code_timestamp > 120:
            self.regenerate_pairing_code()
        return self.pairing_code

    def regenerate_pairing_code(self) -> str:
        self.pairing_code = str(random.randint(100000, 999999))
        self.pairing_code_timestamp = time.time()
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
        # Expired code check
        if time.time() - self.pairing_code_timestamp > 120:
            self.regenerate_pairing_code()
            return None

        if code != self.pairing_code:
            # Invalidate even on failure to prevent brute forcing
            self.regenerate_pairing_code()
            return None
        
        # Single-use code: invalidate immediately
        self.regenerate_pairing_code()
        
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

    def generate_auth_challenge(self, device_id: str) -> str:
        challenge = secrets.token_hex(32)
        self.active_challenges[device_id] = {
            "challenge": challenge,
            "timestamp": time.time()
        }
        return challenge

    def verify_auth_response(self, device_id: str, signature: str) -> bool:
        if device_id not in self.active_challenges:
            return False
            
        challenge_data = self.active_challenges[device_id]
        challenge = challenge_data["challenge"]
        timestamp = challenge_data["timestamp"]
        
        # Invalidate challenge immediately (single-use replay protection)
        del self.active_challenges[device_id]
        
        # Enforce 5.0 seconds challenge response TTL
        if time.time() - timestamp > 5.0:
            return False
            
        pair_token = None
        for device in self.paired_devices:
            if device.get("device_id") == device_id:
                pair_token = device.get("token")
                break
                
        if not pair_token:
            return False
            
        expected_signature = hmac.new(
            pair_token.encode("utf-8"),
            challenge.encode("utf-8"),
            hashlib.sha256
        ).hexdigest()
        
        # Constant-time comparison to block timing attacks
        return hmac.compare_digest(expected_signature, signature)

# Global singleton
session_manager = SessionManager()

