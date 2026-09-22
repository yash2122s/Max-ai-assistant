"""
MAX AI — Computer Use Data Models & Types
Defines typed contracts for UI perception, @ref maps, actions, and verification.
"""

from dataclasses import dataclass, field
from enum import Enum
import time
from typing import Dict, Any, List, Optional, Tuple, Union


class RiskLevel(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class ActionType(str, Enum):
    CLICK = "click"
    DOUBLE_CLICK = "double_click"
    RIGHT_CLICK = "right_click"
    MIDDLE_CLICK = "middle_click"
    MOVE_MOUSE = "move_mouse"
    MOUSE_DOWN = "mouse_down"
    MOUSE_UP = "mouse_up"
    DRAG_AND_DROP = "drag_and_drop"
    TYPE_TEXT = "type_text"
    PRESS_KEY = "press_key"
    HOTKEY = "hotkey"
    SCROLL = "scroll"
    ACTIVATE_WINDOW = "activate_window"
    WAIT = "wait"


class ElementSource(str, Enum):
    UIA = "uia"
    OCR = "ocr"
    FUSED = "fused"
    VISION = "vision"


@dataclass
class Point:
    x: float
    y: float

    def to_tuple(self) -> Tuple[int, int]:
        return round(self.x), round(self.y)


@dataclass
class Rect:
    left: float
    top: float
    right: float
    bottom: float

    @property
    def width(self) -> float:
        return max(0.0, self.right - self.left)

    @property
    def height(self) -> float:
        return max(0.0, self.bottom - self.top)

    @property
    def center(self) -> Point:
        return Point(
            x=self.left + (self.width / 2.0),
            y=self.top + (self.height / 2.0)
        )

    def contains(self, point: Point) -> bool:
        return self.left <= point.x <= self.right and self.top <= point.y <= self.bottom

    def intersects(self, other: 'Rect') -> bool:
        return not (
            self.right < other.left or
            self.left > other.right or
            self.bottom < other.top or
            self.top > other.bottom
        )

    def iou(self, other: 'Rect') -> float:
        """Intersection over Union."""
        ix1 = max(self.left, other.left)
        iy1 = max(self.top, other.top)
        ix2 = min(self.right, other.right)
        iy2 = min(self.bottom, other.bottom)

        iw = max(0.0, ix2 - ix1)
        ih = max(0.0, iy2 - iy1)
        intersection = iw * ih

        union = (self.width * self.height) + (other.width * other.height) - intersection
        if union <= 0.0:
            return 0.0
        return intersection / union

    def to_dict(self) -> Dict[str, float]:
        return {
            "left": self.left,
            "top": self.top,
            "right": self.right,
            "bottom": self.bottom,
            "width": self.width,
            "height": self.height,
            "center_x": self.center.x,
            "center_y": self.center.y
        }


@dataclass
class UIElement:
    """Normalized representation of a single interactive or readable screen element."""
    ref: str  # e.g. "@e1"
    role: str  # button, edit, text, link, menuitem, tabitem, etc.
    name: str  # accessible name or OCR text
    bounds: Rect
    center: Point
    source: ElementSource = ElementSource.UIA
    text: str = ""
    control_type: str = ""
    automation_id: str = ""
    enabled: bool = True
    visible: bool = True
    interactive: bool = True
    confidence: float = 1.0
    window: str = ""
    process: str = ""
    monitor: int = 0
    timestamp: float = field(default_factory=time.time)
    native_handle: Optional[int] = None
    extra_attributes: Dict[str, Any] = field(default_factory=dict)
    generation: int = 0

    @property
    def rect(self) -> Rect:
        return self.bounds

    def to_dict(self) -> Dict[str, Any]:
        return {
            "ref": self.ref,
            "role": self.role,
            "name": self.name,
            "text": self.text or self.name,
            "bounds": self.bounds.to_dict(),
            "center": [self.center.x, self.center.y],
            "source": self.source.value if isinstance(self.source, ElementSource) else str(self.source),
            "control_type": self.control_type,
            "automation_id": self.automation_id,
            "enabled": self.enabled,
            "visible": self.visible,
            "interactive": self.interactive,
            "confidence": round(self.confidence, 3),
            "window": self.window,
            "process": self.process,
            "monitor": self.monitor,
            "timestamp": self.timestamp,
            "generation": self.generation
        }


@dataclass
class UIMap:
    """Snapshot of the desktop UI state with ephemeral @ref bindings."""
    generation: int = 0
    timestamp: float = field(default_factory=time.time)
    active_window: str = ""
    active_process: str = ""
    active_hwnd: Optional[int] = None
    monitor: int = 0
    elements: List[UIElement] = field(default_factory=list)
    ref_map: Dict[str, UIElement] = field(default_factory=dict)
    screenshot_path: Optional[str] = None
    dpi_scale: float = 1.0

    def get_element_by_ref(self, ref: str) -> Optional[UIElement]:
        if not ref:
            return None
        norm_ref = ref.strip()
        if not norm_ref.startswith("@"):
            norm_ref = f"@{norm_ref}"
        return self.ref_map.get(norm_ref)

    def to_summary(self, max_elements: int = 40) -> str:
        """Returns a formatted summary for LLM context."""
        lines = [
            f"=== UI MAP [Gen #{self.generation}] ===",
            f"Window: '{self.active_window}' (Process: {self.active_process}, Mon: {self.monitor}, DPI: {int(self.dpi_scale * 100)}%)",
            f"Elements ({len(self.elements)} total):"
        ]
        for el in self.elements[:max_elements]:
            label = el.name or el.text or el.automation_id or "<unnamed>"
            lines.append(f"  {el.ref: <5} [{el.role: <10}] '{label[:35]}' at ({int(el.center.x)}, {int(el.center.y)})")
        if len(self.elements) > max_elements:
            lines.append(f"  ... and {len(self.elements) - max_elements} more elements.")
        return "\n".join(lines)


@dataclass
class TargetMatch:
    element: UIElement
    ref: str
    confidence: float
    strategy: str
    is_ambiguous: bool = False
    competing_candidates: List[Dict[str, Any]] = field(default_factory=list)


@dataclass
class ResolutionResult:
    query: str
    matched: Optional[TargetMatch]
    status: str  # "FOUND", "NOT_FOUND", "AMBIGUOUS"
    message: str


@dataclass
class ComputerUseAction:
    action_type: ActionType
    target_ref: Optional[str] = None
    target_query: Optional[str] = None
    coordinates: Optional[Point] = None
    target_point: Optional[Point] = None
    x: Optional[int] = None
    y: Optional[int] = None
    source_ref: Optional[str] = None
    source_query: Optional[str] = None
    source_point: Optional[Point] = None
    destination_ref: Optional[str] = None
    destination_query: Optional[str] = None
    destination_point: Optional[Point] = None
    button: str = "left"
    duration: float = 0.5
    amount: Optional[int] = None
    direction: Optional[str] = None
    text: Optional[str] = None
    key: Optional[str] = None
    modifiers: List[str] = field(default_factory=list)
    scroll_delta: int = 0
    timeout_sec: float = 10.0
    risk_level: RiskLevel = RiskLevel.LOW
    description: str = ""
    window_title: Optional[str] = None
    generation: Optional[int] = None


@dataclass
class VerificationResult:
    verified: bool
    action_execution_verified: bool = False
    goal_state_verified: bool = False
    status: str = "UNVERIFIED"  # "VERIFIED", "PARTIALLY_VERIFIED", "FAILED", "UNVERIFIED"
    score: float = 0.0
    observed_changes: List[str] = field(default_factory=list)
    execution_evidence: Dict[str, Any] = field(default_factory=dict)
    error_message: Optional[str] = None
    recovery_recommendation: Optional[str] = None
