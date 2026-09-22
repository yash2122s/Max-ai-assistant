"""
MAX AI — Memory & Resource Diagnostics
Lightweight development diagnostics tracking process RSS, Vosk model instances,
recognizers, active audio streams, playback workers, and async tasks at key lifecycle checkpoints.
"""

import os
import asyncio
import logging
import threading
from typing import Dict, Any, List, Optional

logger = logging.getLogger("MAX.Diagnostics.Memory")


class MemoryDiagnostics:
    """
    Lightweight resource tracker recording process RSS and subsystem handles across lifecycle milestones.
    """
    _instance: Optional['MemoryDiagnostics'] = None
    _lock = threading.Lock()

    def __init__(self):
        self.checkpoints: List[Dict[str, Any]] = []
        self._proc = None
        try:
            import psutil
            self._proc = psutil.Process(os.getpid())
        except Exception:
            pass

    @classmethod
    def get_instance(cls) -> 'MemoryDiagnostics':
        with cls._lock:
            if cls._instance is None:
                cls._instance = cls()
            return cls._instance

    def get_rss_mb(self) -> float:
        """Returns current Python process Resident Set Size (RSS) in MB."""
        if self._proc is not None:
            try:
                return self._proc.memory_info().rss / (1024.0 * 1024.0)
            except Exception:
                pass
        return 0.0

    def record_checkpoint(self, stage: str) -> Dict[str, Any]:
        """
        Captures and logs a memory checkpoint.
        Safe, fast (<0.1ms), and zero-overhead.
        """
        rss = self.get_rss_mb()

        # 1. Vosk metrics
        vosk_loads = 0
        vosk_models = 0
        vosk_recs = 0
        try:
            from core.vosk_manager import vosk_manager
            vosk_loads = vosk_manager.get_load_count()
            vosk_models = vosk_manager.get_model_object_count()
            vosk_recs = vosk_manager.get_recognizer_count()
        except Exception:
            pass

        # 2. Audio streams & workers
        audio_streams = 0
        playback_workers = 0
        try:
            from core.audio_handler import get_active_audio_stream_count, get_playback_worker_count
            audio_streams = get_active_audio_stream_count()
            playback_workers = get_playback_worker_count()
        except Exception:
            pass

        # 3. Async tasks count
        async_tasks = 0
        try:
            loop = asyncio.get_running_loop()
            if loop and loop.is_running():
                async_tasks = len([t for t in asyncio.all_tasks(loop) if not t.done()])
        except Exception:
            pass

        data = {
            "stage": stage,
            "rss_mb": round(rss, 2),
            "vosk_load_count": vosk_loads,
            "vosk_model_objects": vosk_models,
            "vosk_recognizers": vosk_recs,
            "audio_streams": audio_streams,
            "playback_workers": playback_workers,
            "async_tasks": async_tasks,
        }

        with self._lock:
            self.checkpoints.append(data)

        logger.info(
            f"[MEMORY_DIAG] Stage: '{stage}' | RSS: {rss:.1f} MB | "
            f"Vosk Loads: {vosk_loads} | Models: {vosk_models} | Recognizers: {vosk_recs} | "
            f"Audio Streams: {audio_streams} | Playback Workers: {playback_workers} | "
            f"Async Tasks: {async_tasks}"
        )
        return data

    def reset(self):
        """Clears recorded checkpoints."""
        with self._lock:
            self.checkpoints.clear()

    def get_summary(self) -> List[Dict[str, Any]]:
        """Returns all recorded checkpoints."""
        with self._lock:
            return list(self.checkpoints)


# Canonical singleton instance
memory_diagnostics = MemoryDiagnostics.get_instance()
