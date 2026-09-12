"""
MAX AI — Voice Input State Machine & Audio Gating
Enforces local wake-word activation via Vosk, followed by continuous full-duplex
Gemini Live conversation mode with acoustic feedback suppression and idle standby timeout.
"""

import os
import json
import time
import math
import struct
import logging
from enum import Enum
from pathlib import Path
from typing import Optional, Callable, List, Tuple

logger = logging.getLogger("MAXWindowsAgent.VoiceGate")


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
    LIVE_CONVERSATION = "live_conversation"
    COMMAND_LISTENING = "live_conversation"  # Backward-compatible alias
    PROCESSING = "processing"
    ASSISTANT_SPEAKING = "assistant_speaking"
    COOLDOWN = "cooldown"


class VoiceInputGate:
    """
    Manages the voice input lifecycle for Continuous Gemini Live Duplex Conversation:
    - In WAKE_WORD_LISTENING: Runs local Vosk wake-word detection ("Hey MAX"). Rejects background audio.
    - When "Hey MAX" is detected: Transitions to LIVE_CONVERSATION.
    - In LIVE_CONVERSATION: Streams microphone audio continuously to Gemini Live.
      Gemini Live natively handles natural turn-taking, VAD, tools, and responses.
      No wake-word is needed between consecutive turns.
    - When MAX is speaking: Transitions to ASSISTANT_SPEAKING (feedback suppression), then COOLDOWN (0.3s),
      and immediately returns to LIVE_CONVERSATION for the next user query.
    - Transitions back to WAKE_WORD_LISTENING only on extended idle timeout (default 60s) or explicit sleep command.
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
        idle_timeout_sec: float = 60.0,
        cooldown_sec: float = 0.3,
        silence_timeout_sec: Optional[float] = None,
        command_timeout_sec: Optional[float] = None,
        on_state_change: Optional[Callable[[VoiceState], None]] = None,
        vosk_model_path: Optional[str] = None,
        **kwargs,
    ):
        self.state = VoiceState.WAKE_WORD_LISTENING
        self.energy_threshold = energy_threshold
        self.idle_timeout_sec = float(os.getenv("VOICE_IDLE_TIMEOUT", str(idle_timeout_sec)))
        self.cooldown_sec = cooldown_sec
        self.on_state_change = on_state_change


        self._last_interaction_time = 0.0
        self._cooldown_start_time = 0.0

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
        self._init_vosk(self.vosk_model_path)

    def _init_vosk(self, model_path: str):
        try:
            if os.path.exists(model_path):
                from vosk import Model, KaldiRecognizer
                self._vosk_model = Model(model_path)
                self._vosk_rec = KaldiRecognizer(self._vosk_model, 16000)
                logger.info(f"👂 Vosk local wake-word detector READY from '{model_path}'")
                safe_print(f"👂 Local Wake-Word Engine (Vosk): READY [model: {os.path.basename(model_path)}]")
            else:
                logger.warning(f"⚠️ Vosk model not found at '{model_path}'. Local wake detection offline.")
                safe_print(f"⚠️ Local Wake-Word Engine: Model not found at '{model_path}'")
        except Exception as e:
            logger.error(f"❌ Vosk initialization failed: {e}")
            safe_print(f"❌ Local Wake-Word Engine error: {e}")

    def transition_to(self, new_state: VoiceState):
        """Transitions the voice state machine."""
        if self.state == new_state:
            return
        old_state = self.state
        self.state = new_state
        logger.info(f"🎙️ Voice State: {old_state.value} -> {new_state.value}")

        if new_state in (VoiceState.LIVE_CONVERSATION, VoiceState.COMMAND_LISTENING):
            self._last_interaction_time = time.time()
        elif new_state == VoiceState.COOLDOWN:
            self._cooldown_start_time = time.time()
        elif new_state == VoiceState.WAKE_WORD_LISTENING:
            self._audio_pre_buffer.clear()
            if self._vosk_rec:
                try:
                    self._vosk_rec.Reset()
                except Exception:
                    pass

        if self.on_state_change:
            try:
                self.on_state_change(new_state)
            except Exception as e:
                logger.debug(f"Error in on_state_change callback: {e}")

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

    def notify_interaction(self):
        """Resets the idle timer when user or assistant activity occurs."""
        self._last_interaction_time = time.time()

    def on_assistant_speaking_started(self):
        """Notified when MAX begins TTS/audio output."""
        self.notify_interaction()
        self.transition_to(VoiceState.ASSISTANT_SPEAKING)

    def on_assistant_speaking_finished(self):
        """Notified when MAX completes audio output — enters brief cooldown then resumes live conversation."""
        self.notify_interaction()
        self.transition_to(VoiceState.COOLDOWN)

    def trigger_wake_word(self, inline_command: str = ""):
        """Triggers live conversation mode when wake word is detected."""
        if self.state in (VoiceState.WAKE_WORD_LISTENING, VoiceState.IDLE, VoiceState.COOLDOWN):
            logger.info(f"🔥 Wake word confirmed: 'MAX' (inline command: '{inline_command}')")
            safe_print(f"\n🔥 Wake Word Detected: 'MAX'")
            if inline_command:
                self.pending_text_command = inline_command
                safe_print(f"🗣️  Inline Command Extracted: \"{inline_command}\"")
            else:
                self.pending_text_command = "Hey MAX"
            safe_print(f"✨ MAX Live Conversation: ACTIVE (Continuous Duplex Mode)")
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

    def process_audio_chunk(self, audio_bytes: bytes) -> bool:
        """
        Processes an incoming 16kHz PCM audio chunk.

        Returns:
            True if this audio chunk should be streamed to Gemini Live.
            False if this chunk should be filtered out / kept local.
        """
        now = time.time()
        self.chunks_processed += 1
        rms = self.calculate_rms(audio_bytes)
        self._last_rms = rms
        is_speech = rms > self.energy_threshold

        # Periodic telemetry at INFO level
        if self.voice_debug:
            self._log_telemetry(rms)

        # 1. ASSISTANT_SPEAKING: Suppress microphone audio during speaker output (feedback suppression)
        if self.state == VoiceState.ASSISTANT_SPEAKING:
            return False

        # 2. COOLDOWN: Short 0.3s grace period after MAX stops speaking, then resume LIVE_CONVERSATION
        if self.state == VoiceState.COOLDOWN:
            if (now - self._cooldown_start_time) > self.cooldown_sec:
                self.transition_to(VoiceState.LIVE_CONVERSATION)
            return False

        # 3. WAKE_WORD_LISTENING: Local wake-word evaluation via Vosk
        if self.state == VoiceState.WAKE_WORD_LISTENING:
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

            # Do NOT stream background audio to Gemini before wake word
            return False

        # 4. LIVE_CONVERSATION: Continuous full-duplex streaming to Gemini Live
        if self.state in (VoiceState.LIVE_CONVERSATION, VoiceState.COMMAND_LISTENING):
            # Check for extended idle timeout (e.g. 60s of complete silence)
            if self._last_interaction_time > 0 and (now - self._last_interaction_time) > self.idle_timeout_sec:
                logger.info(f"⏱️ Live conversation idle timeout ({self.idle_timeout_sec}s). Returning to wake-word standby.")
                safe_print(f"\n💤 Conversation idle ({int(self.idle_timeout_sec)}s). Returning to 'Hey MAX' standby.")
                self.transition_to(VoiceState.WAKE_WORD_LISTENING)
                return False

            if is_speech:
                self._last_interaction_time = now

            # Stream raw PCM audio continuously to Gemini Live
            return True

        # 5. PROCESSING: Waiting for Gemini / Tool response
        if self.state == VoiceState.PROCESSING:
            return False

        return False
