import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pyaudio
import time
import math
import struct
import threading
import queue
import logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("test.speaker")

class SpeakerOutputManager:
    """
    Robust authoritative manager for audio output stream and playback.
    
    Guarantees:
    - Single stream owner with thread-safe writes and state transitions.
    - Automatic recovery and reopening on PortAudio errors (-9999, -9988, etc.).
    - Comprehensive structured logging (OPEN, READY, WRITE, STOP_REQUESTED, CLOSED, REOPEN_ATTEMPT, REOPEN_SUCCESS, REOPEN_FAILED).
    - Invariant: IF VoiceGate.state == LIVE_CONVERSATION THEN speaker output MUST remain available.
    - Jitter buffering for smooth playback and zero-latency flush on turn complete.
    """
    
    def __init__(self, sample_rate: int = 24000, channels: int = 1):
        self._sample_rate = sample_rate
        self._channels = channels
        self._pa: pyaudio.PyAudio | None = None
        self._stream: pyaudio.Stream | None = None
        self._stream_lock = threading.RLock()
        self._queue: queue.Queue[bytes | None] = queue.Queue(maxsize=500)
        self._worker_thread: threading.Thread | None = None
        self._running = False
        self._playing = False
        self._interrupted = False
        self._is_buffering = True
        self._prebuffer_target = 2
        self._voice_gate = None
        self._gemini_session = None
        self._chunks_written = 0
        self._bytes_written = 0
        self._reopen_count = 0
        self._lock = threading.Lock()

    def set_voice_gate(self, voice_gate):
        """Set VoiceGate reference for invariant checking and diagnostic logging."""
        self._voice_gate = voice_gate

    def set_gemini_session(self, session):
        """Set GeminiLiveSession reference for session state diagnostics."""
        self._gemini_session = session

    @property
    def is_playing(self) -> bool:
        return self._playing or not self._queue.empty()

    @property
    def is_stream_active(self) -> bool:
        with self._stream_lock:
            if self._stream is None:
                return False
            try:
                return self._stream.is_active() and not self._stream.is_stopped()
            except Exception:
                return False

    def _get_best_output_device_index(self, pa: pyaudio.PyAudio) -> int | None:
        """Find the best output device index."""
        try:
            def_info = pa.get_default_output_device_info()
            if def_info.get("maxOutputChannels", 0) > 0:
                return def_info.get("index")
        except Exception:
            pass

        for i in range(pa.get_device_count()):
            try:
                info = pa.get_device_info_by_index(i)
                if info.get("maxOutputChannels", 0) > 0:
                    return i
            except Exception:
                pass
        return None

    def _open_stream_internal(self) -> bool:
        """Internal helper to open PortAudio output stream. Must be called with _stream_lock held."""
        try:
            from core.audio_handler import get_shared_pyaudio
            self._pa = get_shared_pyaudio()
            dev_idx = self._get_best_output_device_index(self._pa)
            dev_name = (
                self._pa.get_device_info_by_index(dev_idx).get("name", "Default")
                if dev_idx is not None
                else "Default"
            )

            # Close existing stream if any
            if self._stream is not None:
                try:
                    if self._stream.is_active():
                        self._stream.stop_stream()
                    self._stream.close()
                except Exception:
                    pass
                self._stream = None

            logger.info("SPEAKER: OPEN (dev_index=%s, dev_name='%s', rate=%d, channels=%d)", dev_idx, dev_name, self._sample_rate, self._channels)
            self._stream = self._pa.open(
                format=pyaudio.paInt16,
                channels=self._channels,
                rate=self._sample_rate,
                output=True,
                output_device_index=dev_idx,
                frames_per_buffer=1024,
            )
            logger.info("SPEAKER: READY (stream_id=%s, active=%s, stopped=%s)", id(self._stream), self._stream.is_active(), self._stream.is_stopped())
            return True
        except Exception as e:
            logger.error("SPEAKER: OPEN_FAILED (error: %s)", e)
            self._stream = None
            return False

    def start(self):
        """Start the speaker manager and worker thread."""
        with self._stream_lock:
            if self._running:
                return
            self._running = True
            self._interrupted = False
            self._is_buffering = True
            self._open_stream_internal()

            self._worker_thread = threading.Thread(
                target=self._playback_loop,
                daemon=True,
                name="MAX-SpeakerManager-Worker",
            )
            self._worker_thread.start()
            logger.info("🔊 SpeakerOutputManager active (24kHz mono)")

    def ensure_stream_ready(self) -> bool:
        """
        Enforces the invariant:
        IF VoiceGate.state == LIVE_CONVERSATION THEN speaker output MUST remain available.
        Checks if stream is active and reopens if closed/invalid.
        """
        with self._stream_lock:
            if not self._running:
                return False
            if self._stream is not None:
                try:
                    if self._stream.is_active() and not self._stream.is_stopped():
                        return True
                except Exception:
                    pass
            # Stream is not ready, reopen
            return self._reopen_stream(reason="ensure_stream_ready check failed")

    def _log_write_error(self, error: Exception, chunk_len: int):
        """Explicit structured logging for write errors with full diagnostic telemetry."""
        with self._stream_lock:
            stream_id = id(self._stream) if self._stream else None
            is_active = False
            is_stopped = True
            if self._stream:
                try:
                    is_active = self._stream.is_active()
                    is_stopped = self._stream.is_stopped()
                except Exception:
                    pass

        voice_state = "UNKNOWN"
        if self._voice_gate is not None and hasattr(self._voice_gate, "state"):
            voice_state = getattr(self._voice_gate.state, "value", str(self._voice_gate.state))

        session_state = "UNKNOWN"
        if self._gemini_session is not None:
            session_state = f"active={getattr(self._gemini_session, '_session_active', 'N/A')}"

        thread_name = threading.current_thread().name

        logger.error(
            "SPEAKER: WRITE_ERROR [Err: %s] | stream_id=%s, active=%s, stopped=%s, "
            "chunk_bytes=%d, voice_state=%s, session_state=%s, thread='%s'",
            error, stream_id, is_active, is_stopped, chunk_len, voice_state, session_state, thread_name
        )

    def _reopen_stream(self, reason: str = "") -> bool:
        """Attempt to reopen the speaker stream after a PortAudio failure."""
        with self._stream_lock:
            if not self._running:
                return False
            self._reopen_count += 1
            logger.warning("SPEAKER: REOPEN_ATTEMPT (attempt=%d, reason='%s')", self._reopen_count, reason)
            
            # Close old stream cleanly
            if self._stream is not None:
                try:
                    if self._stream.is_active():
                        self._stream.stop_stream()
                    self._stream.close()
                except Exception:
                    pass
                self._stream = None

            # Small backoff before reopening to let Windows audio driver stabilize
            time.sleep(0.05)

            success = self._open_stream_internal()
            if success:
                logger.info("SPEAKER: REOPEN_SUCCESS (stream_id=%s, attempt=%d)", id(self._stream), self._reopen_count)
            else:
                logger.error("SPEAKER: REOPEN_FAILED (attempt=%d)", self._reopen_count)
            return success

    def _playback_loop(self):
        """Dedicated background playback worker thread with jitter buffer and automatic recovery."""
        logger.info("🔊 Playback worker thread running")
        while self._running:
            try:
                with self._lock:
                    buffering = self._is_buffering
                    q_size = self._queue.qsize()

                if buffering and q_size < self._prebuffer_target:
                    time.sleep(0.015)
                    continue

                chunk = self._queue.get(timeout=0.1)
                if chunk is None:
                    break

                with self._lock:
                    self._is_buffering = False
                    if self._interrupted or not self._running:
                        self._playing = False
                        self._queue.task_done()
                        continue
                    self._playing = True

                # Write chunk under stream lock
                write_success = False
                for attempt in range(2):
                    with self._stream_lock:
                        if not self._running or self._interrupted:
                            break
                        if self._stream is None:
                            self._reopen_stream(reason="stream was None before write")
                        
                        if self._stream is not None:
                            try:
                                self._stream.write(chunk, exception_on_underflow=False)
                                self._chunks_written += 1
                                self._bytes_written += len(chunk)
                                if self._chunks_written % 50 == 1:
                                    logger.debug("SPEAKER: WRITE (chunk=%d bytes, total_chunks=%d)", len(chunk), self._chunks_written)
                                write_success = True
                                break
                            except Exception as e:
                                self._log_write_error(e, len(chunk))
                    
                    # If write failed, attempt recovery and retry write
                    if not write_success and self._running and not self._interrupted:
                        logger.warning("SPEAKER: Recovering from write failure...")
                        self._reopen_stream(reason="write failure recovery")
                        time.sleep(0.02)

                with self._lock:
                    if self._queue.empty():
                        self._playing = False
                        self._is_buffering = True
                self._queue.task_done()

            except queue.Empty:
                with self._lock:
                    self._playing = False
                    self._is_buffering = True
            except Exception as e:
                logger.debug("Playback worker unexpected loop exception: %s", e)

        logger.info("🔊 Playback worker thread terminated")

    def play_chunk(self, audio_data: bytes):
        """Enqueues an audio chunk for non-blocking speaker playback."""
        with self._lock:
            if self._interrupted or not audio_data or not self._running:
                return

        try:
            self._queue.put_nowait(audio_data)
        except queue.Full:
            try:
                self._queue.get_nowait()
                self._queue.put_nowait(audio_data)
            except Exception:
                pass

    def flush(self):
        """Signals turn complete to drain buffered chunks immediately."""
        with self._lock:
            self._is_buffering = False

    def interrupt(self):
        """Interrupt playback immediately (barge-in). Drains queue with 0ms lag."""
        with self._lock:
            self._interrupted = True
            self._playing = False
            self._is_buffering = True

        while not self._queue.empty():
            try:
                self._queue.get_nowait()
                self._queue.task_done()
            except Exception:
                break
        logger.debug("🔇 Playback interrupted (barge-in queue flushed)")

    def resume(self):
        """Resume playback for new speech turn."""
        with self._lock:
            self._interrupted = False
            self._is_buffering = True

    def stop(self):
        """Stop speaker manager and release audio resources."""
        logger.info("SPEAKER: STOP_REQUESTED")
        self._running = False
        with self._lock:
            self._playing = False
            self._interrupted = True

        try:
            self._queue.put_nowait(None)
        except Exception:
            pass

        if self._worker_thread and self._worker_thread.is_alive():
            self._worker_thread.join(timeout=0.5)
            self._worker_thread = None

        with self._stream_lock:
            stream, self._stream = self._stream, None
        if stream:
            try:
                if stream.is_active():
                    stream.stop_stream()
                stream.close()
            except Exception:
                pass
            logger.info("SPEAKER: CLOSED")


# Test SpeakerOutputManager
print("--- TESTING SpeakerOutputManager ---")
mgr = SpeakerOutputManager()
mgr.start()

# Generate 24kHz tone
samples = [int(32767 * 0.1 * math.sin(2 * math.pi * 440 * n / 24000)) for n in range(24000)]
pcm = struct.pack(f"<{len(samples)}h", *samples)

print("Playing first burst...")
for i in range(0, len(pcm), 1024):
    mgr.play_chunk(pcm[i:i+1024])
    time.sleep(0.01)

mgr.flush()
time.sleep(1.5)

# Simulate intentional stream closure to verify auto-recovery
print("\nSimulating intentional stream corruption / closure to test error recovery...")
with mgr._stream_lock:
    if mgr._stream:
        mgr._stream.close() # Deliberately close underlying stream!

print("Sending second burst to closed stream...")
mgr.resume()
for i in range(0, len(pcm), 1024):
    mgr.play_chunk(pcm[i:i+1024])
    time.sleep(0.01)

mgr.flush()
time.sleep(2.0)

print(f"Post-recovery status: is_stream_active={mgr.is_stream_active}, reopens={mgr._reopen_count}")
mgr.stop()
print("Test completed.")
