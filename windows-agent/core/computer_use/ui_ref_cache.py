"""
MAX AI — Ephemeral @ref Cache & Lifecycle Validator
Maintains generation-scoped @ref bindings and prevents stale-reference execution.
"""

import time
import logging
from typing import Dict, Optional, Tuple

from core.computer_use.models import UIElement, UIMap
from core.computer_use.errors import StaleRefError

logger = logging.getLogger("MAX.ComputerUse.RefCache")


class UIRefCache:
    """Tracks active UIMap generation and validates @ref freshness."""

    def __init__(self, ttl_seconds: float = 30.0):
        self._current_generation: int = 0
        self._current_map: Optional[UIMap] = None
        self._last_updated: float = 0.0
        self.ttl_seconds = ttl_seconds

    @property
    def current_generation(self) -> int:
        return self._current_generation

    @property
    def current_map(self) -> Optional[UIMap]:
        return self._current_map

    def store_map(self, ui_map: UIMap) -> int:
        """Stores newly generated UIMap and updates generation index."""
        self._current_generation = ui_map.generation
        self._current_map = ui_map
        self._last_updated = time.time()
        logger.info(f"🗺️ Stored UIMap Gen #{self._current_generation} with {len(ui_map.elements)} elements for '{ui_map.active_window}'")
        return self._current_generation

    def validate_and_get(
        self,
        ref: str,
        expected_generation: Optional[int] = None,
        expected_window: Optional[str] = None
    ) -> Tuple[UIElement, UIMap]:
        """
        Validates that a @ref is valid, fresh, and belongs to the current UI state.
        Raises StaleRefError if the generation is expired, the active window switched, or the ref is missing.
        """
        if not self._current_map:
            raise StaleRefError("No active UI Map present. Please perform an observation first.")

        now = time.time()
        if (now - self._last_updated) > self.ttl_seconds:
            raise StaleRefError(
                f"UI Map Gen #{self._current_generation} expired ({round(now - self._last_updated, 1)}s old > {self.ttl_seconds}s TTL). Re-observation required."
            )

        if expected_generation is not None and expected_generation != self._current_generation:
            raise StaleRefError(
                f"Stale @ref '{ref}': requested Gen #{expected_generation} but active map is Gen #{self._current_generation}. Re-observation required."
            )

        norm_ref = ref.strip()
        if not norm_ref.startswith("@"):
            norm_ref = f"@{norm_ref}"

        element = self._current_map.get_element_by_ref(norm_ref)
        if not element:
            raise StaleRefError(
                f"Element with ref '{ref}' not found in current UI Map Gen #{self._current_generation}."
            )

        if expected_window and self._current_map.active_window:
            # Check for major window mismatch
            exp_clean = expected_window.lower().strip()
            curr_clean = self._current_map.active_window.lower().strip()
            if exp_clean not in curr_clean and curr_clean not in exp_clean:
                raise StaleRefError(
                    f"Active window switched from '{expected_window}' to '{self._current_map.active_window}'. Ref '{ref}' is invalid."
                )

        return element, self._current_map

    def is_stale(
        self,
        ref: str,
        expected_generation: Optional[int] = None,
        expected_window: Optional[str] = None
    ) -> bool:
        """Returns True if the ref is stale, expired, or invalid."""
        try:
            self.validate_and_get(ref, expected_generation, expected_window)
            return False
        except Exception:
            return True

    def invalidate(self):
        """Invalidates current @ref cache."""
        self._current_map = None
        self._last_updated = 0.0

    def clear(self):
        """Alias for invalidate()."""
        self.invalidate()


ui_ref_cache = UIRefCache()
