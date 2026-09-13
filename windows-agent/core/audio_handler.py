"""
JARVIS Audio Handler for MAX Windows Agent
Real-time microphone capture and speaker playback for Realtime Voice API.
Handles barge-in (stops playback when user speaks).
Uses a shared PyAudio singleton to prevent PortAudio thread conflicts on Windows.

Shutdown guarantees:
- The capture thread is NON-daemon: interpreter finalization will always join it,
  so it can never be torn down while inside a GIL-releasing PortAudio call.
- A module-level atexit hook stops every live capture before `threading._shutdown`
  runs, closing the crash window for every exit path (including Ctrl+C paths that
  skip the app's own shutdown).
- stop() is deterministic and idempotent: stop event -> join (bounded) -> if the
  device stalls, stop_stream() unblocks the pending read -> thread's own finally
  closes the stream. PyAudio is terminated only after the thread is joined.
"""

import asyncio
import atexit
import logging
import queue
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

# ── Live capture registry + atexit safety net ────────────────────────────────
_live_captures: set["AudioCapture"] = set()
_live_captures_lock = threading.Lock()
_atexit_registered = False


def get_shared_pyaudio() -> pyaudio.PyAudio:
    """Thread-safe getter for shared PyAudio instance (prevents PortAudio crash on Windows)."""
    global _shared_pa
    with _pa_lock:
        if _shared_pa is None:
            _shared_pa = pyaudio.PyAudio()
        return _shared_pa


def terminate_shared_pyaudio():
    """Safely terminate shared PyAudio instance on application exit (idempotent)."""
    global _shared_pa
    with _pa_lock:
        if _shared_pa is not None:
            try:
                logger.info("🛑 Terminating PyAudio")
                _shared_pa.terminate()
            except Exception as e:
                logger.warning(f"PyAudio terminate error: {e}")
            _shared_pa = None
            logger.info("✅ Audio resources released")


def _atexit_stop_captures():
    """Stop every live AudioCapture instance before interpreter finalization."""
    with _live_captures_lock:
        captures = list(_live_captures)
    for cap in captures:
        try:
            cap.stop()
        except Exception:
            pass


def _register_atexit():
    """Register the capture-stop hook so it runs BEFORE threading._shutdown.

    threading._shutdown (registered when the threading module is first imported)
    joins every non-daemon thread at interpreter finalization. If a capture
    thread were still alive at that point it would either hang the join (device
    stall) or, if daemon, be torn down mid PortAudio call -> PyEval_RestoreThread
    fatal. Registering through _thread._register_atexit pushes our hook onto the
    C-level atexit stack above threading._shutdown, so it stops and joins the
    capture thread first.
    """
    global _atexit_registered
    if _atexit_registered:
        return
    try:
        import threading
        if hasattr(threading, "_register_atexit"):
            threading._register_atexit(_atexit_stop_captures)
        else:
            atexit.register(_atexit_stop_captures)
    except Exception:
        atexit.register(_atexit_stop_captures)
    _atexit_registered = True


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
        self._stop_event = threading.Event()
        self._lifecycle_lock = threading.Lock()
        _register_atexit()

    @property
    def queue(self) -> asyncio.Queue[bytes]:
        return self._queue

    @property
    def is_running(self) -> bool:
        return self._running

    @property
    def is_thread_alive(self) -> bool:
        t = self._thread
        return t is not None and t.is_alive()

    def start(self, loop: asyncio.AbstractEventLoop):
        """Start capturing audio from the microphone."""
        with self._lifecycle_lock:
            if self._running:
                return
            self._loop = loop
            self._running = True
            self._stop_event.clear()
            # NON-daemon: guarantees threading._shutdown joins this thread at
            # interpreter exit instead of abandoning it mid PortAudio call.
            self._thread = threading.Thread(
                target=self._capture_loop, name="AudioCapture", daemon=False
            )
            with _live_captures_lock:
                _live_captures.add(self)
            self._thread.start()
            logger.info("🎤 Audio capture started")

    def stop(self):
        """Stop capturing: signal stop, join the thread, then let it close the stream.

        Idempotent and safe to call from any thread / any exit path.
        """
        with self._lifecycle_lock:
            thread = self._thread
            if thread is None:
                self._running = False
                return
            self._running = False
            self._stop_event.set()
            logger.info("🛑 Audio shutdown requested")

            # Signal stream to stop to wake up any blocking read immediately
            self._force_stop_stream()

            if thread.is_alive():
                logger.info("🛑 Waiting for capture thread")
                thread.join(timeout=2.0)

            if thread.is_alive():
                logger.warning("⚠️ Audio capture thread still alive after shutdown attempts")


            with _live_captures_lock:
                _live_captures.discard(self)
            self._thread = None
            logger.info("✅ Audio capture shutdown complete")

    def _force_stop_stream(self):
        """Idempotently stop_stream() the current input stream (thread-safe)."""
        stream = self._stream
        if stream is None:
            return
        try:
            if stream.is_active():
                stream.stop_stream()
        except Exception:
            pass

    def _get_best_input_device_index(self, pa: pyaudio.PyAudio) -> int | None:
        """Finds the best microphone input device for 16kHz speech recognition.
        
        Priority order:
        1. MME device with 'Microphone' in name (produces clean 16kHz PCM via Windows mixer)
        2. Any MME input device
        3. DirectSound input device (fallback — may produce corrupted resampled audio)
        4. Any available input device
        
        Note: DirectSound's internal resampler (44100→16000Hz) can produce PCM data
        that has energy but is undecodable by speech recognizers like Vosk. MME
        devices use the Windows audio mixer which resamples correctly.
        """
        mme_mic = None       # MME device with 'Microphone' in name
        mme_any = None       # Any MME input device
        dsound_dev = None    # DirectSound input device
        fallback = None      # Any input device
        
        for i in range(pa.get_device_count()):
            try:
                info = pa.get_device_info_by_index(i)
                if info.get("maxInputChannels", 0) <= 0:
                    continue
                    
                api = pa.get_host_api_info_by_index(info.get("hostApi"))
                api_name = api.get("name", "")
                dev_name = info.get("name", "").lower()
                
                if "MME" in api_name:
                    if "microphone" in dev_name and mme_mic is None:
                        mme_mic = i
                    elif mme_any is None:
                        mme_any = i
                elif "DirectSound" in api_name:
                    if dsound_dev is None:
                        dsound_dev = i
                elif fallback is None:
                    fallback = i
            except Exception:
                pass
        
        chosen = next((x for x in (mme_mic, mme_any, dsound_dev, fallback) if x is not None), None)
        if chosen is not None:
            info = pa.get_device_info_by_index(chosen)
            api = pa.get_host_api_info_by_index(info.get("hostApi"))
            logger.info(
                f"🎤 Selected audio device [{chosen}]: {info.get('name')} "
                f"(API: {api.get('name')}, native: {info.get('defaultSampleRate')}Hz)"
            )
        return chosen

    def _capture_loop(self):
        """Background thread: captures mic audio and pushes to async queue."""
        try:
            self._pa = get_shared_pyaudio()
            dev_idx = self._get_best_input_device_index(self._pa)
            dev_name = (
                self._pa.get_device_info_by_index(dev_idx).get("name", "Unknown")
                if dev_idx is not None
                else "Default"
            )

            self._stream = self._pa.open(
                format=pyaudio.paInt16,
                channels=INPUT_CHANNELS,
                rate=INPUT_SAMPLE_RATE,
                input=True,
                input_device_index=dev_idx,
                frames_per_buffer=INPUT_CHUNK_SIZE,
            )

            logger.info(
                f"🎤 Mic opened [dev {dev_idx}: {dev_name}]: {INPUT_SAMPLE_RATE}Hz, {INPUT_CHANNELS}ch, chunk={INPUT_CHUNK_SIZE}"
            )

            while not self._stop_event.is_set():
                try:
                    if self._stream is None or not self._stream.is_active():
                        break
                    data = self._stream.read(
                        INPUT_CHUNK_SIZE, exception_on_overflow=False
                    )
                    # Feed wake word engine
                    try:
                        from core.wake_word import wake_word_engine
                        if wake_word_engine.is_running:
                            wake_word_engine.feed_audio(data)
                    except Exception:
                        pass

                    if self._loop and self._loop.is_running():
                        self._loop.call_soon_threadsafe(
                            self._queue_put_nowait, data
                        )
                except (OSError, IOError) as e:
                    logger.warning(f"Mic read error: {e}")
                    break
                except Exception as e:
                    logger.warning(f"Mic read unexpected error: {e}")
                    break

        except Exception as e:
            logger.error(f"🎤 Microphone initialization failed: {e}")
            logger.error("Make sure a microphone is connected and accessible.")
        finally:
            self._running = False
            if self._stream is not None:
                try:
                    logger.info("🛑 Stopping PyAudio stream")
                    if self._stream.is_active():
                        self._stream.stop_stream()
                    logger.info("🛑 Closing PyAudio stream")
                    self._stream.close()
                except Exception:
                    pass
                self._stream = None
            logger.info("✅ Audio capture thread exited")

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


class SpeakerOutputManager:
    """
    Authoritative manager for Realtime Voice audio playback on Windows.
    
    Guarantees:
    - Single stream owner with thread-safe writes and state transitions.
    - Automatic recovery and reopening on PortAudio errors (-9999, -9988, -9985, etc.).
    - Comprehensive structured logging (OPEN, READY, WRITE, STOP_REQUESTED, CLOSED, REOPEN_ATTEMPT, REOPEN_SUCCESS, REOPEN_FAILED).
    - Invariant: IF VoiceGate.state == LIVE_CONVERSATION THEN speaker output MUST remain available.
    - Jitter buffering for smooth playback and zero-latency flush on turn complete.
    """

    def __init__(self, sample_rate: int = OUTPUT_SAMPLE_RATE, channels: int = OUTPUT_CHANNELS):
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

    def set_realtime_session(self, session):
        """Set RealtimeVoiceSession reference for session state diagnostics."""
        self._gemini_session = session

    def set_gemini_session(self, session):
        """Set RealtimeVoiceSession reference (backward compatible)."""
        self.set_realtime_session(session)

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

            logger.info(
                "SPEAKER: OPEN (dev_index=%s, dev_name='%s', rate=%d, channels=%d)",
                dev_idx, dev_name, self._sample_rate, self._channels
            )
            self._stream = self._pa.open(
                format=pyaudio.paInt16,
                channels=self._channels,
                rate=self._sample_rate,
                output=True,
                output_device_index=dev_idx,
                frames_per_buffer=1024,
            )
            logger.info(
                "SPEAKER: READY (stream_id=%s, active=%s, stopped=%s)",
                id(self._stream), self._stream.is_active(), self._stream.is_stopped()
            )
            return True
        except Exception as e:
            logger.error("SPEAKER: OPEN_FAILED (error: %s)", e)
            self._stream = None
            return False

    def start(self):
        """Initialize the audio output stream and background playback worker thread."""
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
            logger.info(
                f"🔊 Speaker opened: {self._sample_rate}Hz, {self._channels}ch (SpeakerOutputManager active)"
            )

    def ensure_stream_ready(self) -> bool:
        """
        Enforces the invariant:
        IF VoiceGate.state == LIVE_CONVERSATION THEN speaker output MUST remain available.
        Checks if stream is active and reopens if closed or invalid.
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
            # Stream is not ready, attempt reopen
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
            import time
            time.sleep(0.05)

            success = self._open_stream_internal()
            if success:
                logger.info("SPEAKER: REOPEN_SUCCESS (stream_id=%s, attempt=%d)", id(self._stream), self._reopen_count)
            else:
                logger.error("SPEAKER: REOPEN_FAILED (attempt=%d)", self._reopen_count)
            return success

    def _playback_loop(self):
        """Background worker thread that continuously drains audio queue to the speaker stream with jitter buffering."""
        import time
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
                                    logger.debug(
                                        "SPEAKER: WRITE (chunk=%d bytes, total_chunks=%d)",
                                        len(chunk), self._chunks_written
                                    )
                                write_success = True
                                break
                            except Exception as e:
                                self._log_write_error(e, len(chunk))

                    # If write failed, recover and retry
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
                logger.debug(f"Audio playback worker exception: {e}")

        logger.info("🔊 Playback worker thread terminated")

    def play_chunk(self, audio_data: bytes):
        """
        Enqueues an audio chunk for non-blocking speaker playback.
        Returns immediately (<0.01ms) so the asyncio event loop never stalls.
        """
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
        """Signals that speech chunk stream for the current turn is finished so any buffered chunks play out immediately."""
        with self._lock:
            self._is_buffering = False

    def interrupt(self):
        """
        Interrupt playback immediately (barge-in).
        Flushes all pending queued audio chunks with 0ms lag.
        """
        with self._lock:
            self._interrupted = True
            self._playing = False
            self._is_buffering = True

        # Drain all remaining chunks in the queue
        while not self._queue.empty():
            try:
                self._queue.get_nowait()
                self._queue.task_done()
            except Exception:
                break
        logger.debug("🔇 Playback interrupted (barge-in queue flushed)")

    def resume(self):
        """Resume playback after barge-in is cleared."""
        with self._lock:
            self._interrupted = False
            self._is_buffering = True

    def stop(self):
        """Clean up audio output resources and stop the playback worker thread."""
        logger.info("SPEAKER: STOP_REQUESTED")
        self._running = False
        with self._lock:
            self._playing = False
            self._interrupted = True

        # Signal worker thread to terminate
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


# Canonical backward-compatible alias
AudioPlayer = SpeakerOutputManager