"""
MAX AI — Central System Readiness Coordinator
Tracks asynchronous lifecycle milestones across Core, Servers, Voice, Gemini, Overlay, UIA, and OCR.
Provides non-blocking event gates and failure isolation to prevent race conditions during parallel startup.
"""
import asyncio
import logging
from typing import Dict, Any, Optional

logger = logging.getLogger("MAX.Readiness")

# Standard Subsystem Lifecycle States
STATE_NOT_STARTED = "NOT_STARTED"
STATE_INITIALIZING = "INITIALIZING"
STATE_STARTING = "STARTING"
STATE_CONNECTING = "CONNECTING"
STATE_READY = "READY"
STATE_ERROR = "ERROR"
STATE_DISABLED = "DISABLED"

VALID_STATES = {
    STATE_NOT_STARTED,
    STATE_INITIALIZING,
    STATE_STARTING,
    STATE_CONNECTING,
    STATE_READY,
    STATE_ERROR,
    STATE_DISABLED,
}

TERMINAL_STATES = {STATE_READY, STATE_ERROR, STATE_DISABLED}

COMPONENTS = ("core", "servers", "voice", "gemini", "overlay", "uia", "ocr")


class ReadinessManager:
    """Centralized singleton coordinator for subsystem readiness and lifecycle states."""
    _instance: Optional["ReadinessManager"] = None

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._init_state()
        return cls._instance

    def _init_state(self):
        self._states: Dict[str, str] = {
            "core": STATE_ONLINE if hasattr(self, "STATE_ONLINE") else STATE_READY,
            "servers": STATE_READY,
            "voice": STATE_INITIALIZING,
            "gemini": STATE_CONNECTING,
            "overlay": STATE_STARTING,
            "uia": STATE_NOT_STARTED,
            "ocr": STATE_NOT_STARTED,
        }
        self._errors: Dict[str, str] = {c: "" for c in COMPONENTS}
        self._events: Dict[str, asyncio.Event] = {c: asyncio.Event() for c in COMPONENTS}

        # Convenience references for backwards compatibility
        self.voice = self._events["voice"]
        self.overlay = self._events["overlay"]
        self.gemini = self._events["gemini"]
        self.core = self._events["core"]
        self.servers = self._events["servers"]
        self.uia = self._events["uia"]
        self.ocr = self._events["ocr"]

        # Mark core and servers initial event
        self._events["core"].set()
        self._events["servers"].set()

    def set(self, component: str, state: str, error: str = "") -> None:
        """
        Update the state of a component.
        If the state is a terminal state (READY, ERROR, DISABLED), its event is set
        so that any waiters on wait_for(component) wake up immediately.
        """
        comp = component.lower().strip()
        st = state.upper().strip()

        if comp not in self._states:
            self._states[comp] = st
            self._errors[comp] = error
            self._events[comp] = asyncio.Event()

        self._states[comp] = st
        if error:
            self._errors[comp] = error
        elif st in (STATE_READY, STATE_CONNECTING, STATE_INITIALIZING, STATE_STARTING):
            self._errors[comp] = ""

        # Waking condition: Unblock waiters on terminal states
        if st in TERMINAL_STATES or "ERROR" in st:
            self._events[comp].set()
            if st == STATE_READY:
                logger.info("🟢 Readiness: [%s] is READY", comp.upper())
            elif "ERROR" in st:
                logger.warning("🔴 Readiness: [%s] entered ERROR: %s", comp.upper(), error)
            elif st == STATE_DISABLED:
                logger.info("⚪ Readiness: [%s] is DISABLED", comp.upper())
        else:
            # If transitioning back to a pending state (e.g. reconnecting)
            if self._events[comp].is_set() and st not in TERMINAL_STATES:
                self._events[comp].clear()

    def get(self, component: str) -> str:
        """Return the current state of the given component."""
        comp = component.lower().strip()
        state = self._states.get(comp, STATE_NOT_STARTED)
        err = self._errors.get(comp, "")
        if state == STATE_ERROR and err:
            return f"ERROR ({err})"
        return state

    def get_error(self, component: str) -> str:
        """Return error detail for the given component if any."""
        return self._errors.get(component.lower().strip(), "")

    def is_ready(self, component: str) -> bool:
        """Check if the given component is in the READY state."""
        return self._states.get(component.lower().strip()) == STATE_READY

    async def wait_for(self, component: str, timeout: Optional[float] = None) -> str:
        """
        Wait for a component to reach a terminal state (READY, ERROR, or DISABLED).
        Never deadlocks if a component errors out or is disabled.
        Returns the terminal state string.
        """
        comp = component.lower().strip()
        if comp not in self._events:
            self.set(comp, STATE_NOT_STARTED)

        event = self._events[comp]
        current_state = self._states.get(comp, STATE_NOT_STARTED)

        if current_state in TERMINAL_STATES:
            return self.get(comp)

        if timeout is not None:
            await asyncio.wait_for(event.wait(), timeout=timeout)
        else:
            await event.wait()

        return self.get(comp)

    def snapshot(self) -> Dict[str, str]:
        """
        Return a complete snapshot dictionary of all component states.
        Format matches:
        {
          "core": "READY",
          "servers": "READY",
          "voice": "READY",
          "gemini": "CONNECTING",
          "overlay": "READY",
          "uia": "NOT_STARTED",
          "ocr": "NOT_STARTED"
        }
        """
        return {c: self._states.get(c, STATE_NOT_STARTED) for c in COMPONENTS}

    # Backward compatibility properties and methods
    @property
    def voice_status(self) -> str:
        return self.get("voice")

    @property
    def overlay_status(self) -> str:
        return self.get("overlay")

    @property
    def gemini_status(self) -> str:
        return self.get("gemini")

    @property
    def core_status(self) -> str:
        return self.get("core")

    def set_voice_ready(self):
        self.set("voice", STATE_READY)

    def set_voice_error(self, err: str = ""):
        self.set("voice", STATE_ERROR, error=err)

    def set_voice_disabled(self):
        self.set("voice", STATE_DISABLED)

    def set_overlay_ready(self):
        self.set("overlay", STATE_READY)

    def set_overlay_error(self, err: str = ""):
        self.set("overlay", STATE_ERROR, error=err)

    def set_overlay_disabled(self):
        self.set("overlay", STATE_DISABLED)

    def set_gemini_ready(self):
        self.set("gemini", STATE_READY)

    def set_gemini_error(self, err: str = ""):
        self.set("gemini", STATE_ERROR, error=err)

    def set_gemini_disabled(self):
        self.set("gemini", STATE_DISABLED)

    @property
    def is_degraded(self) -> bool:
        """True if any subsystem is in an ERROR state."""
        return any(
            self._states.get(c) == STATE_ERROR
            for c in ("core", "servers", "voice", "gemini", "overlay")
        )

    @property
    def is_fully_operational(self) -> bool:
        """
        True only if all required background subsystems are READY.
        UIA and OCR are lazy and expected to be NOT_STARTED or READY.
        """
        primary = ("core", "servers", "voice", "gemini", "overlay")
        return all(self._states.get(c) == STATE_READY for c in primary)

    @property
    def is_system_ready(self) -> bool:
        """True if system is either FULLY_OPERATIONAL or READY (with optional services disabled)."""
        return self.get_system_health() in ("FULLY_OPERATIONAL", "READY")

    def get_summary(self) -> Dict[str, str]:
        """Backward compatibility alias for snapshot()."""
        return self.snapshot()

    def get_system_health(self) -> str:
        """
        Categorizes system state into:
        - FULLY_OPERATIONAL: Everything configured is READY.
        - DEGRADED: One or more components entered ERROR.
        - READY: Non-critical optional services DISABLED, remaining core services READY.
        - INITIALIZING: Still waiting for background tasks to complete.
        """
        if self.is_degraded:
            return "DEGRADED"

        primary = ("core", "servers", "voice", "gemini", "overlay")
        states = [self._states.get(c) for c in primary]

        if all(s == STATE_READY for s in states):
            return "FULLY_OPERATIONAL"

        if all(s in (STATE_READY, STATE_DISABLED) for s in states):
            return "READY"

        return "INITIALIZING"

    def reset(self):
        """Reset all states and events (primarily for isolated test fixtures)."""
        self._init_state()


# Global singleton instance
readiness = ReadinessManager()
ReadinessState = ReadinessManager  # Alias for backward compatibility
