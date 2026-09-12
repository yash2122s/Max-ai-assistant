"""
MAX AI — Orchestrator Package
Exports OrchestrationRouter and DeviceRouter singleton instances.
"""

from .router import OrchestrationRouter, orchestration_router
from .device_router import DeviceRouter, device_router

__all__ = [
    "OrchestrationRouter",
    "orchestration_router",
    "DeviceRouter",
    "device_router",
]
