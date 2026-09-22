"""
MAX UI — Computer Use Data Models
"""

from dataclasses import dataclass, field
from enum import Enum
import time
from typing import Optional, List, Tuple, Dict, Any


class ActionPhase(str, Enum):
    IDLE = "IDLE"
    OBSERVE = "OBSERVE"
    RESOLVE = "RESOLVE"
    SAFETY = "SAFETY"
    ACT = "ACT"
    VERIFY = "VERIFY"
    DONE = "DONE"
    FAILED = "FAILED"


@dataclass
class PerceptionBox:
    ref_id: str
    label: str
    x: int
    y: int
    width: int
    height: int
    source: str = "uia"  # "uia", "ocr", "vision"
    confidence: float = 1.0


@dataclass
class ComputerUseState:
    phase: ActionPhase = ActionPhase.IDLE
    current_application: str = ""
    current_url: str = ""
    target_description: str = ""
    action_type: str = ""  # "click", "type", "scroll", etc.
    target_coordinates: Optional[Tuple[int, int]] = None
    target_box: Optional[PerceptionBox] = None
    all_boxes: List[PerceptionBox] = field(default_factory=list)
    screenshot_bytes: Optional[bytes] = None
    screenshot_path: Optional[str] = None
    step_number: int = 0
    total_steps: int = 0
    status_message: str = ""
    verification_passed: Optional[bool] = None
    error: Optional[str] = None
    last_updated: float = field(default_factory=time.time)
