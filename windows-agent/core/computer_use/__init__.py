"""
MAX AI — Computer Use Subsystem Package
Blinky-inspired native Windows Computer Use perception, target resolution, action execution, and verification.
"""

from core.computer_use.models import (
    UIElement, UIMap, ComputerUseAction, ActionType, RiskLevel,
    Point, Rect, TargetMatch, ResolutionResult, VerificationResult, ElementSource
)
from core.computer_use.errors import (
    ComputerUseError, StaleRefError, TargetNotFoundError,
    AmbiguousTargetError, ActionExecutionError, VerificationFailedError,
    SafetyViolationError, CoordinateOutOfBoundsError
)
from core.computer_use.screen_perception import screen_perception, ScreenPerceptionCoordinator
from core.computer_use.screenshot_provider import screenshot_provider, ScreenshotProvider
from core.computer_use.uia_provider import uia_provider, UIAProvider
from core.computer_use.ocr_provider import ocr_provider, OCRProvider
from core.computer_use.ui_map import ui_map_builder, UIMapBuilder
from core.computer_use.ui_ref_cache import ui_ref_cache, UIRefCache
from core.computer_use.target_resolver import target_resolver, TargetResolver
from core.computer_use.coordinate_transform import coordinate_transformer, CoordinateTransformer
from core.computer_use.action_executor import action_executor, ActionExecutor
from core.computer_use.action_authority import action_authority, ActionAuthority
from core.computer_use.verification import computer_use_verifier, ComputerUseVerifier
from core.computer_use.capture_exclusion import capture_exclusion_manager, CaptureExclusionManager
from core.computer_use.computer_use_engine import computer_use_engine, ComputerUseEngine

__all__ = [
    "UIElement",
    "UIMap",
    "ComputerUseAction",
    "ActionType",
    "RiskLevel",
    "Point",
    "Rect",
    "TargetMatch",
    "ResolutionResult",
    "VerificationResult",
    "ElementSource",
    "ComputerUseError",
    "StaleRefError",
    "TargetNotFoundError",
    "AmbiguousTargetError",
    "ActionExecutionError",
    "VerificationFailedError",
    "SafetyViolationError",
    "CoordinateOutOfBoundsError",
    "screen_perception",
    "ScreenPerceptionCoordinator",
    "screenshot_provider",
    "ScreenshotProvider",
    "uia_provider",
    "UIAProvider",
    "ocr_provider",
    "OCRProvider",
    "ui_map_builder",
    "UIMapBuilder",
    "ui_ref_cache",
    "UIRefCache",
    "target_resolver",
    "TargetResolver",
    "coordinate_transformer",
    "CoordinateTransformer",
    "action_executor",
    "ActionExecutor",
    "action_authority",
    "ActionAuthority",
    "computer_use_verifier",
    "ComputerUseVerifier",
    "capture_exclusion_manager",
    "CaptureExclusionManager",
    "computer_use_engine",
    "ComputerUseEngine",
]
