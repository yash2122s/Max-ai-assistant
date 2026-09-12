"""
MAX AI — Vision Task Coordinator (P0-1 Two-Phase Vision Acknowledgement)
Coordinates immediate auditory/text acknowledgement before expensive screen capture
and vision perception pipelines, eliminating awkward user latency without blocking execution.
"""

import time
import logging
import asyncio
from typing import Dict, Any, Optional, Callable, Set

logger = logging.getLogger("MAXWindowsAgent.VisionCoordinator")


def safe_print(*args, **kwargs):
    """Print helper that safely handles Windows console encoding errors."""
    try:
        print(*args, flush=True, **kwargs)
    except UnicodeEncodeError:
        safe_args = []
        for a in args:
            if isinstance(a, str):
                safe_args.append(a.encode("ascii", errors="replace").decode("ascii"))
            else:
                safe_args.append(a)
        print(*safe_args, flush=True, **kwargs)


class VisionTaskCoordinator:
    """
    Coordinates Two-Phase Vision Acknowledgement:
    1. Detects vision requirement before expensive screen capture / reasoning.
    2. Emits fast deterministic acknowledgement immediately via TTS/audio/console.
    3. Executes asynchronous screen capture and visual reasoning in parallel.
    4. Suppresses duplicate turn acknowledgements.
    5. Leaves normal fast commands untouched (zero delay).
    """

    VISION_TOOLS: Set[str] = {
        "take_screenshot",
        "screenshot",
        "capture_screen",
        "inspect_screen",
        "read_screen_text",
        "screen_ocr",
        "locate_on_screen",
        "find_element_on_screen",
        "visual_qa",
        "describe_screen",
        "click_coordinate",
    }

    VISION_PHRASES: tuple = (
        "on my screen",
        "on the screen",
        "look at my screen",
        "check my screen",
        "read my screen",
        "what's on my screen",
        "what is on my screen",
        "see on my screen",
        "take a screenshot",
        "capture my screen",
        "tell me what's open",
    )

    FAST_COMMAND_TOOLS: Set[str] = {
        "open_application",
        "close_application",
        "get_time_date",
        "set_volume",
        "get_volume",
        "mute_unmute",
        "get_system_info",
        "get_weather",
        "clipboard_copy",
        "clipboard_paste",
        "press_key",
        "type_text",
        "go_to_sleep",
    }

    def __init__(self, tts_player: Optional[Callable[[str], None]] = None):
        self.tts_player = tts_player
        self._turn_acknowledged: bool = False
        self._last_ack_timestamp: float = 0.0
        self._ack_dedup_window_sec: float = 2.5

    def reset_turn(self):
        """Resets acknowledgement state at the start of a new conversational turn."""
        self._turn_acknowledged = False

    def detect_vision_requirement(
        self,
        tool_name: Optional[str] = None,
        args: Optional[Dict[str, Any]] = None,
        prompt_hint: Optional[str] = None
    ) -> bool:
        """
        Determines deterministically if a request requires screen capture or visual reasoning.
        """
        # 1. Direct tool match
        if tool_name:
            clean_tool = tool_name.strip().lower()
            if clean_tool in self.VISION_TOOLS:
                return True
            if clean_tool in self.FAST_COMMAND_TOOLS:
                return False

        # 2. Argument hints
        if args and isinstance(args, dict):
            if args.get("require_vision") is True or args.get("use_vision") is True:
                return True
            if "screenshot" in str(args).lower():
                return True

        # 3. Text prompt hints
        if prompt_hint and isinstance(prompt_hint, str):
            clean_prompt = prompt_hint.lower()
            for phrase in self.VISION_PHRASES:
                if phrase in clean_prompt:
                    return True

        return False

    def send_immediate_acknowledgement(
        self,
        ack_phrase: str = "One second, I'm checking your screen.",
        custom_tts_cb: Optional[Callable[[str], None]] = None
    ) -> bool:
        """
        Immediately sends acknowledgement without blocking subsequent processing.
        Returns True if acknowledgement was dispatched, False if suppressed.
        """
        now = time.time()
        if self._turn_acknowledged or (now - self._last_ack_timestamp < self._ack_dedup_window_sec):
            logger.debug("Vision acknowledgement suppressed (already emitted in turn).")
            return False

        self._turn_acknowledged = True
        self._last_ack_timestamp = now

        # Console notification
        safe_print(f"✨ MAX: {ack_phrase}")
        logger.info(f"🗣️ Emitted instant vision acknowledgement: '{ack_phrase}'")

        # Audio / TTS callback (non-blocking)
        target_tts = custom_tts_cb or self.tts_player
        if target_tts and callable(target_tts):
            try:
                target_tts(ack_phrase)
            except Exception as e:
                logger.debug(f"TTS acknowledgement callback error: {e}")

        return True

    def acknowledge_if_vision_task(
        self,
        tool_name: Optional[str] = None,
        args: Optional[Dict[str, Any]] = None,
        prompt_hint: Optional[str] = None,
        ack_phrase: str = "One second, I'm checking your screen.",
        custom_tts_cb: Optional[Callable[[str], None]] = None
    ) -> bool:
        """
        One-shot detection and acknowledgement trigger.
        """
        if self.detect_vision_requirement(tool_name, args, prompt_hint):
            return self.send_immediate_acknowledgement(ack_phrase, custom_tts_cb)
        return False

    async def execute_two_phase_vision(
        self,
        capture_coroutine: Callable,
        reasoning_coroutine: Optional[Callable] = None,
        ack_phrase: str = "One second, I'm checking your screen.",
        *args,
        **kwargs
    ) -> Dict[str, Any]:
        """
        Executes two-phase vision:
        Phase 1: Immediate acknowledgement
        Phase 2: Capture + Vision reasoning
        """
        self.send_immediate_acknowledgement(ack_phrase)

        start_time = time.time()
        try:
            # Execute capture
            if asyncio.iscoroutinefunction(capture_coroutine):
                capture_result = await capture_coroutine(*args, **kwargs)
            else:
                loop = asyncio.get_running_loop()
                capture_result = await loop.run_in_executor(None, lambda: capture_coroutine(*args, **kwargs))

            if isinstance(capture_result, dict) and capture_result.get("status") == "error":
                return {
                    "status": "error",
                    "error_code": "CAPTURE_FAILED",
                    "message": capture_result.get("message", "Screen capture failed.")
                }

            # Optional secondary reasoning
            if reasoning_coroutine:
                if asyncio.iscoroutinefunction(reasoning_coroutine):
                    vision_result = await reasoning_coroutine(capture_result)
                else:
                    loop = asyncio.get_running_loop()
                    vision_result = await loop.run_in_executor(None, lambda: reasoning_coroutine(capture_result))
                return vision_result

            duration_ms = int((time.time() - start_time) * 1000)
            return {
                "status": "success",
                "capture": capture_result,
                "duration_ms": duration_ms
            }

        except Exception as e:
            logger.error(f"Vision coordination failed: {e}", exc_info=True)
            return {
                "status": "error",
                "error_code": "VISION_PROCESSING_ERROR",
                "message": f"Vision processing failed: {str(e)}"
            }


# Global singleton instance
vision_coordinator = VisionTaskCoordinator()
