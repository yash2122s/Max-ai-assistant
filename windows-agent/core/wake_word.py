"""
MAX AI — Local Hands-Free Wake Word Engine (Real Model Inference)
Performs real-time local wake word detection on 16kHz mono PCM audio using
the embedded Kaldi/Vosk neural acoustic model with a constrained grammar FST.
Supports keyword spotting for: 'hey max', 'max', 'jarvis'.
"""

import os
import json
import time
import logging
import threading
from typing import Callable, Optional, List, Dict, Any

from config import PROJECT_ROOT

logger = logging.getLogger("MAXWindowsAgent.WakeWord")

DEFAULT_MODEL_PATH = os.path.join(PROJECT_ROOT, "storage", "models", "vosk-model-small-en-us-0.15")


class RealWakeWordEngine:
    """
    Real-time on-device wake word detector running Vosk/Kaldi neural acoustic inference.
    Processes 16kHz 16-bit mono PCM chunks from AudioCapture without cloud dependencies.
    """

    def __init__(
        self,
        model_path: Optional[str] = None,
        wake_words: Optional[List[str]] = None,
        on_detected: Optional[Callable[[str], None]] = None,
        sample_rate: int = 16000
    ):
        self.model_path = model_path or DEFAULT_MODEL_PATH
        self.wake_words = [w.lower() for w in (wake_words or ["hey max", "max", "jarvis"])]
        self.on_detected = on_detected
        self.sample_rate = sample_rate

        self._model = None
        self._recognizer = None
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()
        self._audio_queue: List[bytes] = []
        self._cooldown_s = 2.0
        self._last_trigger_time = 0.0

        self._init_model()

    def _init_model(self):
        """Initializes Vosk model and Kaldi recognizer with grammar constraint via shared vosk_manager."""
        if not os.path.exists(self.model_path):
            logger.error(f"Vosk acoustic model directory not found at: {self.model_path}")
            return

        try:
            from core.vosk_manager import vosk_manager
            self._model = vosk_manager.acquire_model(self.model_path, consumer_id="WakeWord")
            grammar = self.wake_words + ["[unk]"]
            self._recognizer = vosk_manager.create_recognizer(
                self.model_path,
                consumer_id="WakeWord",
                sample_rate=self.sample_rate,
                grammar=grammar
            )
            if self._recognizer is not None:
                logger.info(f"✅ Real acoustic wake word model ready. Grammar: {self.wake_words}")
            else:
                logger.warning("Wake word recognizer initialization failed.")
        except Exception as e:
            logger.error(f"Failed to initialize Vosk model: {e}")
            self._model = None
            self._recognizer = None

    @property
    def is_available(self) -> bool:
        return self._recognizer is not None

    @property
    def is_running(self) -> bool:
        return self._running

    def start(self):
        """Starts real-time wake word inference thread."""
        with self._lock:
            if self._running:
                return
            if not self.is_available:
                self._init_model()
            if not self.is_available:
                logger.warning("Cannot start wake word engine: model not loaded.")
                return
            self._running = True
            self._thread = threading.Thread(target=self._inference_loop, name="VoskWakeWordWorker", daemon=True)
            self._thread.start()
            logger.info(f"👂 Real wake-word detector active (Listening on 16kHz PCM for {self.wake_words})")

    def stop(self):
        """Stops the wake word engine and releases recognizer/model."""
        with self._lock:
            self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=1.0)
            self._thread = None
        if self._recognizer is not None:
            try:
                from core.vosk_manager import vosk_manager
                vosk_manager.destroy_recognizer(self._recognizer, consumer_id="WakeWord")
            except Exception:
                pass
            self._recognizer = None
        if self._model is not None:
            try:
                from core.vosk_manager import vosk_manager
                vosk_manager.release_model(self.model_path, consumer_id="WakeWord")
            except Exception:
                pass
            self._model = None
        logger.info("🛑 Real wake word engine stopped")

    def feed_audio(self, pcm_chunk: bytes):
        """
        Receives 16kHz 16-bit mono PCM chunks from AudioCapture.
        Thread-safe queue ingestion.
        """
        if not self._running or not pcm_chunk or not self._recognizer:
            return

        with self._lock:
            self._audio_queue.append(pcm_chunk)
            if len(self._audio_queue) > 50:  # Drop oldest if worker falls behind
                self._audio_queue.pop(0)

    def _inference_loop(self):
        """Continuous audio chunk inference loop."""
        while self._running:
            chunk = None
            with self._lock:
                if self._audio_queue:
                    chunk = self._audio_queue.pop(0)

            if not chunk:
                time.sleep(0.015)
                continue

            now = time.time()
            if (now - self._last_trigger_time) < self._cooldown_s:
                continue

            try:
                # AcceptWaveform returns True on full silence / utterance boundary
                if self._recognizer.AcceptWaveform(chunk):
                    res = json.loads(self._recognizer.Result())
                    recognized_text = res.get("text", "").strip().lower()
                    if recognized_text:
                        self._check_wake_text(recognized_text)
                else:
                    # Check partial result for low-latency activation
                    pres = json.loads(self._recognizer.PartialResult())
                    partial_text = pres.get("partial", "").strip().lower()
                    if partial_text:
                        self._check_wake_text(partial_text)
            except Exception as e:
                logger.debug(f"Vosk inference error: {e}")

    def _check_wake_text(self, text: str):
        """Checks if recognized text contains any configured wake phrase."""
        now = time.time()
        for word in self.wake_words:
            if word in text:
                self._last_trigger_time = now
                logger.info(f"🎙️ REAL WAKE WORD DETECTED: '{word}' (From text: '{text}')")
                # Reset recognizer state to prepare for next utterance
                try:
                    self._recognizer.Reset()
                except Exception:
                    pass
                if self.on_detected:
                    try:
                        self.on_detected(word)
                    except Exception as e:
                        logger.error(f"Wake word callback error: {e}")
                break


WakeWordEngine = RealWakeWordEngine
wake_word_engine = RealWakeWordEngine()
