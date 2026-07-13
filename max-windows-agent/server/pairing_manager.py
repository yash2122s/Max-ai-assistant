from typing import Optional
from core.session_manager import session_manager

class PairingManager:
    def __init__(self):
        pass

    @property
    def pairing_code(self) -> str:
        return session_manager.get_pairing_code()

    @pairing_code.setter
    def pairing_code(self, value: str):
        session_manager.pairing_code = value

    @property
    def paired_devices(self):
        return session_manager.paired_devices

    def get_pairing_code(self) -> str:
        return session_manager.get_pairing_code()

    def regenerate_pairing_code(self) -> str:
        return session_manager.regenerate_pairing_code()

    def pair_device(self, device_id: str, device_name: str, code: str) -> Optional[str]:
        return session_manager.pair_device(device_id, device_name, code)

    def verify_token(self, device_id: str, token: str) -> bool:
        return session_manager.verify_token(device_id, token)
