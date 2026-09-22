"""
MAX AI — Computer Use Exceptions
Hierarchical error types for perception, resolution, actions, and verification.
"""

class ComputerUseError(Exception):
    """Base exception for all Computer Use subsystem errors."""
    def __init__(self, message: str, details: dict = None):
        super().__init__(message)
        self.message = message
        self.details = details or {}


class StaleRefError(ComputerUseError):
    """Raised when an action attempts to execute against an expired/stale @ref."""
    pass


class TargetNotFoundError(ComputerUseError):
    """Raised when target element cannot be found by resolver."""
    pass


class AmbiguousTargetError(ComputerUseError):
    """Raised when multiple candidate elements match query with equal confidence."""
    pass


class ActionExecutionError(ComputerUseError):
    """Raised when an input or automation action fails to execute."""
    pass


class VerificationFailedError(ComputerUseError):
    """Raised when Observe -> Act -> Verify verification fails."""
    pass


class SafetyViolationError(ComputerUseError):
    """Raised when an unconfirmed HIGH-risk action is blocked by Safety Gate."""
    pass


class CoordinateOutOfBoundsError(ComputerUseError):
    """Raised when coordinate translation yields invalid or out-of-screen bounds."""
    pass
