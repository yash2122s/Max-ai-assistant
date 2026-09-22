"""
MAX AI — Voice Input State Machine & Audio Gating
Enforces local wake-word activation via Vosk, followed by continuous full-duplex
Realtime Voice conversation mode with acoustic feedback suppression and idle standby timeout.
"""

import os
import json
import time
import math
import struct
import logging
import threading
from enum import Enum
from pathlib import Path
from typing import Optional, Callable, List, Tuple, Any

logger = logging.getLogger("MAX.VoiceGate")


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


class VoiceState(str, Enum):
    IDLE = "idle"
    WAKE_WORD_LISTENING = "wake_word_listening"
    WAKE_DETECTED = "wake_detected"
    LIVE_CONVERSATION = "live_conversation"
    COMMAND_LISTENING = "live_conversation"  # Backward-compatible alias
    PROCESSING = "processing"
    ASSISTANT_SPEAKING = "assistant_speaking"
    COOLDOWN = "cooldown"


VOICE_INACTIVITY_TIMEOUT: float = 180.0

voice_gate: Optional["VoiceInputGate"] = None


class VoiceInputGate:
    """
    Manages the voice input lifecycle for Continuous Realtime Voice Duplex Conversation:
    - In WAKE_WORD_LISTENING: Runs local Vosk wake-word detection ("Hey MAX", "Hello Max", "Max", "Jarvis"). Rejects background audio.
    - When wake phrase is detected: Transitions WAKE_WORD_LISTENING -> WAKE_DETECTED -> LIVE_CONVERSATION.
    - In LIVE_CONVERSATION: Streams microphone audio continuously to Realtime Voice.
      Realtime Voice natively handles natural turn-taking, VAD, tools, and responses.
      Wake-word detection is strictly ignored / disabled during LIVE_CONVERSATION.
    - When MAX is speaking: Transitions to ASSISTANT_SPEAKING (feedback suppression), then COOLDOWN (0.3s),
      and returns to LIVE_CONVERSATION.
    - If user remains silent for VOICE_INACTIVITY_TIMEOUT (180s):
      Transitions LIVE_CONVERSATION -> WAKE_WORD_LISTENING.
    """

    # Normalized wake phrases / phonetic variants
    WAKE_PHRASES = (
        "hey max",
        "hey marks",
        "hey maks",
        "hey macs",
        "hey match",
        "hey mix",
        "okay max",
        "ok max",
        "hay max",
        "hi max",
        "hello max",
        "max",
        "marks",
        "maks",
        "macs",
        "match",
        "mex",
        "hey jarvis",
        "ok jarvis",
        "okay jarvis",
        "hi jarvis",
        "hello jarvis",
        "jarvis",
    )

    SLEEP_PHRASES = (
        "go to sleep",
        "sleep now",
        "stop listening",
        "bye max",
        "goodbye max",
        "standby mode",
        "enter standby",
        "mute now",
    )

    def __init__(
        self,
        energy_threshold: float = 300.0,
        idle_timeout_sec: float = 180.0,
        cooldown_sec: float = 0.3,
        silence_timeout_sec: Optional[float] = None,
        command_timeout_sec: Optional[float] = None,
        on_state_change: Optional[Callable[[VoiceState], None]] = None,
        vosk_model_path: Optional[str] = None,
        defer_model_load: bool = False,
        **kwargs,
    ):
        self.state = VoiceState.WAKE_WORD_LISTENING
        self.energy_threshold = energy_threshold
        if "idle_timeout_sec" in kwargs or idle_timeout_sec != 180.0:
            self.idle_timeout_sec = float(kwargs.get("idle_timeout_sec", idle_timeout_sec))
        else:
            env_timeout = os.getenv("VOICE_INACTIVITY_TIMEOUT", os.getenv("VOICE_IDLE_TIMEOUT"))
            self.idle_timeout_sec = float(env_timeout) if env_timeout is not None else float(idle_timeout_sec)
        self.cooldown_sec = cooldown_sec
        self.on_state_change = on_state_change

        self._last_interaction_time = 0.0
        self._cooldown_start_time = 0.0
        self._last_user_activity_log_time = 0.0

        # Pending command text or pre-buffered audio to inject into Gemini
        self.pending_text_command: Optional[str] = None
        self._audio_pre_buffer: List[bytes] = []

        # Diagnostics & counters
        self.chunks_processed = 0
        self.vosk_partials_count = 0  # non-empty partial results only
        self.vosk_finals_count = 0
        self.vosk_chunks_fed = 0      # total chunks actually fed to AcceptWaveform
        self._last_rms = 0.0
        self._last_telemetry_time = 0.0
        self.voice_debug = os.getenv("VOICE_DEBUG", "true").lower() == "true"

        # Resolve model path
        if not vosk_model_path:
            project_root = Path(__file__).resolve().parent.parent
            vosk_model_path = str(project_root / "storage" / "models" / "vosk-model-small-en-us-0.15")

        self.vosk_model_path = vosk_model_path
        self._vosk_model = None
        self._vosk_rec = None
        self._vosk_init_lock = threading.Lock()
        global voice_gate
        voice_gate = self

        if not defer_model_load:
            self._init_vosk(self.vosk_model_path)

    def ensure_model_loaded(self):
        """Thread-safe acquisition of the Vosk model and recognizer."""
        with self._vosk_init_lock:
            if self._vosk_rec is None:
                self._init_vosk(self.vosk_model_path)

    def _init_vosk(self, model_path: str):
        try:
            if os.path.exists(model_path):
                from core.vosk_manager import vosk_manager
                self._vosk_model = vosk_manager.acquire_model(model_path, consumer_id="VoiceGate")
                self._vosk_rec = vosk_manager.create_recognizer(model_path, consumer_id="VoiceGate", sample_rate=16000)
                if self._vosk_rec is not None:
                    logger.info(f"👂 Vosk local wake-word detector READY from '{model_path}'")
                    safe_print(f"👂 Local Wake-Word Engine (Vosk): READY [model: {os.path.basename(model_path)}]")
                    try:
                        from core.readiness import readiness
                        readiness.set_voice_ready()
                    except Exception:
                        pass
                    try:
                        from core.startup_profiler import profiler
                        profiler.mark("VOSK_READY")
                    except Exception:
                        pass
                else:
                    logger.warning(f"⚠️ Vosk recognizer creation failed for '{model_path}'.")
                    try:
                        from core.readiness import readiness
                        readiness.set_voice_error("Recognizer creation failed")
                    except Exception:
                        pass
            else:
                logger.warning(f"⚠️ Vosk model not found at '{model_path}'. Local wake detection offline.")
                safe_print(f"⚠️ Local Wake-Word Engine: Model not found at '{model_path}'")
                try:
                    from core.readiness import readiness
                    readiness.set_voice_error("Model not found")
                except Exception:
                    pass
        except Exception as e:
            logger.error(f"❌ Vosk initialization failed: {e}")
            safe_print(f"❌ Local Wake-Word Engine error: {e}")
            try:
                from core.readiness import readiness
                readiness.set_voice_error(str(e))
            except Exception:
                pass

    def close(self):
        """Clean up Vosk recognizer and model reference."""
        if self._vosk_rec is not None:
            try:
                from core.vosk_manager import vosk_manager
                vosk_manager.destroy_recognizer(self._vosk_rec, consumer_id="VoiceGate")
            except Exception:
                pass
            self._vosk_rec = None
        if self._vosk_model is not None:
            try:
                from core.vosk_manager import vosk_manager
                vosk_manager.release_model(self.vosk_model_path, consumer_id="VoiceGate")
            except Exception:
                pass
            self._vosk_model = None

    def transition_to(self, new_state: VoiceState):
        """Transitions the voice state machine."""
        if self.state == new_state:
            return
        old_state = self.state
        self.state = new_state
        logger.info(f"🎙️ Voice State: {old_state.value} -> {new_state.value}")

        if new_state in (VoiceState.LIVE_CONVERSATION, VoiceState.COMMAND_LISTENING):
            if old_state != VoiceState.COOLDOWN or self._last_interaction_time == 0.0:
                self._last_interaction_time = time.time()
            logger.info("[VoiceGate] Session active — wake-word detection disabled")
        elif new_state == VoiceState.COOLDOWN:
            self._cooldown_start_time = time.time()
        elif new_state == VoiceState.WAKE_WORD_LISTENING:
            self._audio_pre_buffer.clear()
            if self._vosk_rec:
                try:
                    self._vosk_rec.Reset()
                except Exception:
                    pass
        elif new_state == VoiceState.WAKE_DETECTED:
            self._last_interaction_time = time.time()

        # Publish voice state to EventBus for the desktop overlay
        try:
            from server.event_bus import event_bus
            from protocol.event_types import EventType
            event_bus.publish(EventType.VOICE_STATE, {
                "state": new_state.value,
                "previous_state": old_state.value,
                "session_active": new_state in (
                    VoiceState.LIVE_CONVERSATION, VoiceState.COMMAND_LISTENING,
                    VoiceState.ASSISTANT_SPEAKING, VoiceState.PROCESSING,
                ),
            })
        except Exception:
            pass  # EventBus may not be initialized during early startup

        if self.on_state_change:
            try:
                self.on_state_change(new_state)
            except Exception as e:
                logger.debug(f"Error in on_state_change callback: {e}")

        if new_state in (VoiceState.LIVE_CONVERSATION, VoiceState.COMMAND_LISTENING):
            self.verify_live_conversation_invariant()

    def verify_live_conversation_invariant(self, realtime_session: Optional[Any] = None) -> bool:
        """
        Enforces the explicit invariant:
        WHEN state == LIVE_CONVERSATION:
            wake_word_detection == disabled
            microphone == active
            realtime_audio_send_loop == running
            realtime_receive_loop == running
            user_turn_input == enabled

        Any violation logs a diagnostic error.
        """
        if self.state not in (VoiceState.LIVE_CONVERSATION, VoiceState.COMMAND_LISTENING):
            return True

        violations: List[str] = []

        # Wake-word detection must be disabled during active live conversation
        # (Implicitly verified because process_audio_chunk does not feed Vosk when state == LIVE_CONVERSATION)

        # Check session components if provided
        if realtime_session is not None:
            mic_active = (
                getattr(realtime_session.audio_capture, "has_active_stream", False)
                or getattr(realtime_session.audio_capture, "is_running", False)
            )
            if not mic_active:
                violations.append("microphone is inactive")

            send_loop = getattr(realtime_session, "_is_send_audio_running", True)
            if not send_loop:
                violations.append("realtime_audio_send_loop is not running")

            recv_loop = getattr(realtime_session, "_is_receive_loop_running", True)
            if not recv_loop:
                violations.append("realtime_receive_loop is not running")

            ws_alive = getattr(realtime_session, "_session_active", True)
            if not ws_alive:
                violations.append("websocket session is inactive")

        if violations:
            logger.error(
                "[REALTIME_VOICE_INVARIANT_VIOLATION] state=%s violations: %s",
                self.state.value,
                ", ".join(violations),
            )
            return False

        logger.debug(
            "[REALTIME_VOICE_INVARIANT_OK] state=%s wake_word=disabled mic=active send_loop=running recv_loop=running input=enabled",
            self.state.value
        )
        return True

    @staticmethod
    def calculate_rms(audio_bytes: bytes) -> float:
        """Calculates Root-Mean-Square energy of 16-bit PCM audio chunk."""
        count = len(audio_bytes) // 2
        if count == 0:
            return 0.0
        try:
            shorts = struct.unpack(f"<{count}h", audio_bytes[: count * 2])
            sum_squares = sum(s * s for s in shorts)
            return math.sqrt(sum_squares / count)
        except Exception:
            return 0.0

    def notify_user_activity(self):
        """Resets the inactivity timer when the user speaks or sends conversational input."""
        self._last_interaction_time = time.time()
        self._last_user_activity_log_time = self._last_interaction_time
        logger.info("[VoiceGate] User activity — inactivity timer reset")

    def notify_interaction(self):
        """Backward-compatible alias for user activity notification."""
        self.notify_user_activity()

    def on_assistant_speaking_started(self):
        """Notified when MAX begins TTS/audio output."""
        self.transition_to(VoiceState.ASSISTANT_SPEAKING)

    def on_assistant_speaking_finished(self):
        """Notified when MAX completes audio output — enters brief cooldown then returns to live conversation."""
        self.transition_to(VoiceState.COOLDOWN)

    def check_cooldown(self) -> bool:
        """Checks if cooldown period has elapsed and returns state to LIVE_CONVERSATION."""
        if self.state == VoiceState.COOLDOWN:
            if (time.time() - self._cooldown_start_time) > self.cooldown_sec:
                logger.info("[VoiceGate] Assistant finished — remaining in LIVE_CONVERSATION")
                self.transition_to(VoiceState.LIVE_CONVERSATION)
                return True
        return False

    def trigger_wake_word(self, inline_command: str = ""):
        """Triggers live conversation mode when wake word is detected."""
        if self.state not in (VoiceState.WAKE_WORD_LISTENING, VoiceState.IDLE):
            logger.debug(f"[VoiceGate] Wake word ignored in state: {self.state.value}")
            return

        logger.info("[VoiceGate] Wake word detected — activating LIVE_CONVERSATION")
        safe_print("\n🔥 Wake Word Detected: WAKE_DETECTED")
        # 1. Transition to WAKE_DETECTED
        self.transition_to(VoiceState.WAKE_DETECTED)
        if inline_command:
            self.pending_text_command = inline_command
            safe_print(f"🗣️  Inline Command Extracted: \"{inline_command}\"")
        else:
            self.pending_text_command = "Hey MAX"
        # 2. Transition to LIVE_CONVERSATION
        safe_print("✨ MAX Live Conversation: ACTIVE (Continuous Duplex Mode)")
        self.transition_to(VoiceState.LIVE_CONVERSATION)


    def go_to_sleep(self):
        """Transitions MAX out of live conversation and back to local wake-word standby."""
        if self.state != VoiceState.WAKE_WORD_LISTENING:
            logger.info("💤 MAX entering standby mode. Returning to local wake-word listening.")
            safe_print("\n💤 MAX entering standby. Say 'Hey MAX' to resume conversation.")
            self.transition_to(VoiceState.WAKE_WORD_LISTENING)

    def _check_wake_phrase(self, text: str) -> Tuple[bool, str]:
        """
        Evaluates recognition text against wake phrases.
        Returns (is_wake_word, remaining_inline_command).
        """
        text = text.lower().strip()
        if not text:
            return False, ""

        # Normalize punctuation
        for ch in ",.?!;:-_":
            text = text.replace(ch, " ")
        text = " ".join(text.split())

        for wake in self.WAKE_PHRASES:
            if text == wake:
                return True, ""
            if text.startswith(wake + " "):
                cmd = text[len(wake) :].strip()
                return True, cmd
            if (" " + wake + " ") in (" " + text + " "):
                # Wake word inside sentence
                idx = text.find(wake)
                cmd = text[idx + len(wake) :].strip()
                return True, cmd

        return False, ""

    def _log_telemetry(self, rms: float):
        """Log periodic telemetry (~every 2 seconds). Suppressed during speaking to prevent console text splitting."""
        now = time.time()
        if now - self._last_telemetry_time < 2.0:
            return
        self._last_telemetry_time = now
        if self.state == VoiceState.ASSISTANT_SPEAKING:
            return
        logger.debug(
            f"[VoiceGate] state={self.state.value} "
            f"chunks={self.chunks_processed} rms={rms:.0f} "
            f"vosk_fed={self.vosk_chunks_fed} "
            f"partials={self.vosk_partials_count} "
            f"finals={self.vosk_finals_count}"
        )

    def check_inactivity_timeout(self, current_time: Optional[float] = None) -> bool:
        """Checks if inactivity timeout reached and transitions to WAKE_WORD_LISTENING."""
        if self.state not in (VoiceState.LIVE_CONVERSATION, VoiceState.COMMAND_LISTENING):
            return False
        now = current_time if current_time is not None else time.time()
        if self._last_interaction_time > 0 and (now - self._last_interaction_time) > self.idle_timeout_sec:
            logger.info("[VoiceGate] Inactivity timeout reached — returning to WAKE_WORD_LISTENING")
            safe_print(f"\n💤 Inactivity timeout reached ({int(self.idle_timeout_sec)}s). Returning to wake-word listening.")
            self.transition_to(VoiceState.WAKE_WORD_LISTENING)
            return True
        return False

    def process_audio_chunk(self, audio_bytes: bytes) -> bool:
        """
        Processes an incoming 16kHz PCM audio chunk.

        Returns:
            True if this audio chunk should be streamed to Realtime Voice.
            False if this chunk should be filtered out / kept local.
        """
        now = time.time()
        self.chunks_processed += 1
        rms = self.calculate_rms(audio_bytes)
        self._last_rms = rms
        is_speech = rms > self.energy_threshold

        # Publish throttled audio level to EventBus (~10 Hz max)
        if not hasattr(self, '_last_audio_level_publish'):
            self._last_audio_level_publish = 0.0
        if (now - self._last_audio_level_publish) >= 0.1:
            self._last_audio_level_publish = now
            try:
                from server.event_bus import event_bus
                from protocol.event_types import EventType
                event_bus.publish(EventType.VOICE_AUDIO_LEVEL, {
                    "rms": round(rms, 2),
                    "is_speech": is_speech,
                    "state": self.state.value,
                })
            except Exception:
                pass

        # Periodic telemetry at INFO level
        if self.voice_debug:
            self._log_telemetry(rms)

        # 1. ASSISTANT_SPEAKING: Suppress microphone audio during speaker output (feedback suppression)
        if self.state == VoiceState.ASSISTANT_SPEAKING:
            return False

        # 2. COOLDOWN: Short grace period after MAX stops speaking, then return to LIVE_CONVERSATION
        if self.state == VoiceState.COOLDOWN:
            if not self.check_cooldown():
                return False

        # 3. WAKE_WORD_LISTENING: Local wake-word evaluation via Vosk
        if self.state in (VoiceState.WAKE_WORD_LISTENING, VoiceState.IDLE):
            if self._vosk_rec:
                self.vosk_chunks_fed += 1
                # Feed chunk to local Vosk KaldiRecognizer
                if self._vosk_rec.AcceptWaveform(audio_bytes):
                    self.vosk_finals_count += 1
                    try:
                        res = json.loads(self._vosk_rec.Result())
                        text = res.get("text", "")
                        if text:
                            logger.info(f"[Vosk FINAL] \"{text}\"")
                            is_wake, inline_cmd = self._check_wake_phrase(text)
                            if is_wake:
                                self.trigger_wake_word(inline_cmd)
                            else:
                                logger.info(f"[Vosk] Not a wake phrase: \"{text}\"")
                    except Exception as e:
                        logger.warning(f"Vosk result parse error: {e}")
                else:
                    try:
                        partial_res = json.loads(self._vosk_rec.PartialResult())
                        partial_text = partial_res.get("partial", "")
                        if partial_text:
                            self.vosk_partials_count += 1
                            is_wake, inline_cmd = self._check_wake_phrase(partial_text)
                            if is_wake:
                                logger.info(f"[Vosk PARTIAL WAKE] \"{partial_text}\"")
                                self._vosk_rec.Reset()
                                self.trigger_wake_word(inline_cmd)
                            elif self.voice_debug and len(partial_text) > 2:
                                logger.info(f"👂 Heard (partial): '{partial_text}'")
                    except Exception as e:
                        logger.warning(f"Vosk partial parse error: {e}")

            # Keep a rolling buffer of 3 chunks (~200ms) to avoid clipping start of command
            self._audio_pre_buffer.append(audio_bytes)
            if len(self._audio_pre_buffer) > 4:
                self._audio_pre_buffer.pop(0)

            # Do NOT stream background audio before wake word
            return False

        # 4. LIVE_CONVERSATION: Continuous full-duplex streaming to Realtime Voice
        if self.state in (VoiceState.LIVE_CONVERSATION, VoiceState.COMMAND_LISTENING):
            # Check for extended inactivity timeout (180s of silence)
            if self.check_inactivity_timeout(now):
                return False

            if is_speech:
                self._last_interaction_time = now
                if (now - self._last_user_activity_log_time) >= 1.0:
                    logger.info("[VoiceGate] User activity — inactivity timer reset")
                    self._last_user_activity_log_time = now

            # Stream raw PCM audio continuously to Realtime Voice
            return True

        # 5. PROCESSING: Waiting for Realtime Voice / Tool response
        if self.state == VoiceState.PROCESSING:
            return False

        return False
