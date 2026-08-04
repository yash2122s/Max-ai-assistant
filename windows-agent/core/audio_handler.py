"""
JARVIS Audio Handler
Real-time microphone capture and speaker playback for Gemini Live API.
Handles barge-in (stops playback when user speaks).
Uses a shared PyAudio singleton to prevent PortAudio thread conflicts on Windows.
"""

import asyncio
import logging
import threading
import pyaudio

from config import (
    INPUT_SAMPLE_RATE,
    INPUT_CHANNELS,
    INPUT_CHUNK_SIZE,
    OUTPUT_SAMPLE_RATE,
    OUTPUT_CHANNELS,
)

logger = logging.getLogger("jarvis.audio")

# ── Shared PyAudio Singleton ──────────────────────────────────────────────────
_shared_pa: pyaudio.PyAudio | None = None
_pa_lock = threading.Lock()


def get_shared_pyaudio() -> pyaudio.PyAudio:
    """Thread-safe getter for shared PyAudio instance (prevents PortAudio crash on Windows)."""
    global _shared_pa
    with _pa_lock:
        if _shared_pa is None:
            _shared_pa = pyaudio.PyAudio()
        return _shared_pa


def terminate_shared_pyaudio():
    """Safely terminate shared PyAudio instance on application exit."""
    global _shared_pa
    with _pa_lock:
        if _shared_pa is not None:
            try:
                _shared_pa.terminate()
            except Exception:
                pass
            _shared_pa = None


class AudioCapture:
    """
    Continuously captures microphone audio as raw PCM bytes.
    Feeds audio into an async queue for streaming to Gemini.
    """

    def __init__(self):
        self._queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=100)
        self._running = False
        self._thread: threading.Thread | None = None
        self._pa: pyaudio.PyAudio | None = None
        self._stream = None
        self._loop: asyncio.AbstractEventLoop | None = None

    @property
    def queue(self) -> asyncio.Queue[bytes]:
        return self._queue

    @property
    def is_running(self) -> bool:
        return self._running

    def start(self, loop: asyncio.AbstractEventLoop):
        """Start capturing audio from the microphone."""
        if self._running:
            return

        self._loop = loop
        self._running = True
        self._thread = threading.Thread(target=self._capture_loop, daemon=True)
        self._thread.start()
        logger.info("🎤 Microphone capture started")

    def stop(self):
        """Stop capturing audio."""
        self._running = False
        if self._stream:
            try:
                if self._stream.is_active():
                    self._stream.stop_stream()
                self._stream.close()
            except Exception:
                pass
            self._stream = None
        logger.info("🎤 Microphone capture stopped")

    def _capture_loop(self):
        """Background thread: captures mic audio and pushes to async queue."""
        try:
            self._pa = get_shared_pyaudio()
            self._stream = self._pa.open(
                format=pyaudio.paInt16,
                channels=INPUT_CHANNELS,
                rate=INPUT_SAMPLE_RATE,
                input=True,
                frames_per_buffer=INPUT_CHUNK_SIZE,
            )

            logger.info(
                f"🎤 Mic opened: {INPUT_SAMPLE_RATE}Hz, {INPUT_CHANNELS}ch, chunk={INPUT_CHUNK_SIZE}"
            )

            while self._running:
                try:
                    data = self._stream.read(
                        INPUT_CHUNK_SIZE, exception_on_overflow=False
                    )
                    if self._loop and self._loop.is_running():
                        self._loop.call_soon_threadsafe(
                            self._queue_put_nowait, data
                        )
                except OSError as e:
                    logger.warning(f"Mic read error: {e}")
                    continue
        except Exception as e:
            logger.error(f"🎤 Microphone initialization failed: {e}")
            logger.error("Make sure a microphone is connected and accessible.")
            self._running = False
        finally:
            self.stop()

    def _queue_put_nowait(self, data: bytes):
        """Thread-safe queue put — drops oldest if full."""
        try:
            if self._queue.full():
                try:
                    self._queue.get_nowait()  # Drop oldest chunk
                except asyncio.QueueEmpty:
                    pass
            self._queue.put_nowait(data)
        except Exception:
            pass


class AudioPlayer:
    """
    Plays raw PCM audio chunks received from Gemini.
    Supports barge-in: can be interrupted immediately when user speaks.
    """

    def __init__(self):
        self._pa: pyaudio.PyAudio | None = None
        self._stream = None
        self._playing = False
        self._interrupted = False
        self._lock = threading.Lock()

    @property
    def is_playing(self) -> bool:
        return self._playing

    def start(self):
        """Initialize the audio output stream."""
        try:
            self._pa = get_shared_pyaudio()
            self._stream = self._pa.open(
                format=pyaudio.paInt16,
                channels=OUTPUT_CHANNELS,
                rate=OUTPUT_SAMPLE_RATE,
                output=True,
                frames_per_buffer=4096,
            )
            logger.info(
                f"🔊 Speaker opened: {OUTPUT_SAMPLE_RATE}Hz, {OUTPUT_CHANNELS}ch"
            )
        except Exception as e:
            logger.error(f"🔊 Speaker initialization failed: {e}")

    def play_chunk(self, audio_data: bytes):
        """
        Play an audio chunk through the speaker.
        Ignores data if interrupted (barge-in).
        """
        with self._lock:
            if self._interrupted:
                return
            self._playing = True

        try:
            if self._stream and audio_data:
                self._stream.write(audio_data)
        except Exception as e:
            logger.warning(f"Speaker write error: {e}")
        finally:
            with self._lock:
                self._playing = False

    def interrupt(self):
        """
        Interrupt playback immediately (barge-in).
        Called when the user starts speaking while JARVIS is talking.
        """
        with self._lock:
            self._interrupted = True
            self._playing = False
        logger.debug("🔇 Playback interrupted (barge-in)")

    def resume(self):
        """Resume playback after barge-in is cleared."""
        with self._lock:
            self._interrupted = False

    def stop(self):
        """Clean up audio output resources."""
        self._playing = False
        if self._stream:
            try:
                if self._stream.is_active():
                    self._stream.stop_stream()
                self._stream.close()
            except Exception:
                pass
            self._stream = None
        logger.info("🔊 Speaker stopped")
