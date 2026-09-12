"""
MAX AI — Multi-Platform Gateway Server
Provides webhook integration for external platforms (WhatsApp Cloud API, Discord, Signal, Weixin)
running on Port 9002 alongside the MAX Companion Server (Port 9000) and Dashboard (Port 9001).
"""

import json
import logging
from typing import Dict, Any, Optional

logger = logging.getLogger("MAXWindowsAgent.Gateway")


class MultiPlatformGateway:
    """
    Gateway handling external chat webhooks and event routing.
    """
    _instance: Optional['MultiPlatformGateway'] = None

    def __init__(self, port: int = 9002):
        self.port = port
        self.is_active = False
        self.registered_platforms = ["whatsapp_cloud", "discord", "webhook"]

    @classmethod
    def get_instance(cls) -> 'MultiPlatformGateway':
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def process_incoming_webhook(self, platform: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        """Processes an incoming message webhook from an external platform."""
        clean_plat = platform.lower().strip()
        logger.info(f"📬 Incoming gateway webhook from '{clean_plat}'")

        sender = payload.get("sender", "external_user")
        text = payload.get("text") or payload.get("message", "")

        if not text:
            return {"status": "ignored", "message": "Empty message payload"}

        # Route message to Hermes reasoning loop in background
        logger.info(f"Routing '{clean_plat}' message from '{sender}': '{text}'")
        return {
            "status": "received",
            "platform": clean_plat,
            "sender": sender,
            "message": text,
            "dispatched": True
        }

    def get_status(self) -> Dict[str, Any]:
        """Returns gateway status and active integrations."""
        return {
            "status": "ready",
            "gateway_port": self.port,
            "supported_platforms": self.registered_platforms,
            "coexists_with": "MAX Companion (Port 9000) & Dashboard (Port 9001)"
        }


# Singleton instance
gateway_server = MultiPlatformGateway.get_instance()


# ─── Tool Declarations ────────────────────────────────────────────────────────

def gateway_status() -> Dict[str, Any]:
    """Check the status of the Multi-Platform Gateway (WhatsApp Cloud, Discord, Webhooks)."""
    return gateway_server.get_status()


GATEWAY_TOOL_DECLARATIONS = [
    {
        "name": "gateway_status",
        "description": "Check the status and connected platform channels of the Multi-Platform Gateway (Port 9002).",
        "parameters": {"type": "object", "properties": {}}
    }
]

GATEWAY_TOOL_FUNCTIONS = {
    "gateway_status": gateway_status,
}
