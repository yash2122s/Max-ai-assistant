"""
MAX AI — Shared Vosk Acoustic Model Manager
Provides thread-safe singleton management for local Vosk/Kaldi models.
Guarantees:
  - 1 Vosk Model instance per unique model path across the process.
  - N KaldiRecognizer instances created from the shared model.
  - Reference counting per consumer with clean model unloading on ref_count == 0.
  - Explicit destroy_recognizer() to unbind recognizers before model release.
  - Test-resettable state for clean unit test isolation.
"""

import os
import json
import logging
import threading
from typing import Dict, Set, Tuple, List, Optional, Any

logger = logging.getLogger("MAXWindowsAgent.VoskManager")


class VoskModelManager:
    """
    Centralized owner and lifecycle coordinator for Vosk acoustic models and recognizers.
    """
    _instance: Optional['VoskModelManager'] = None
    _singleton_lock = threading.Lock()

    def __init__(self):
        self._lock = threading.RLock()
        # model_path (normalized) -> vosk.Model object
        self._models: Dict[str, Any] = {}
        # model_path (normalized) -> set of active consumer_id strings
        self._ref_counts: Dict[str, Set[str]] = {}
        # rec_id -> (KaldiRecognizer, model_path, consumer_id)
        self._recognizers: Dict[str, Tuple[Any, str, str]] = {}
        # Total number of physical model loads performed from disk
        self._load_count: int = 0

    @classmethod
    def get_instance(cls) -> 'VoskModelManager':
        with cls._singleton_lock:
            if cls._instance is None:
                cls._instance = cls()
            return cls._instance

    @classmethod
    def reset_instance(cls):
        """Resets the singleton for unit test isolation."""
        with cls._singleton_lock:
            if cls._instance is not None:
                cls._instance.reset()
                cls._instance = None

    def reset(self):
        """Clears all recognizers, models, and counters."""
        with self._lock:
            self._recognizers.clear()
            self._models.clear()
            self._ref_counts.clear()
            self._load_count = 0

    def acquire_model(self, model_path: str, consumer_id: str = "default") -> Optional[Any]:
        """
        Acquires the shared Vosk Model instance for model_path, incrementing its ref count.
        Loads from disk if not already cached.
        """
        if not model_path or not os.path.exists(model_path):
            logger.warning(f"Vosk model path not found: {model_path}")
            return None

        norm_path = os.path.abspath(model_path)
        with self._lock:
            if norm_path not in self._models:
                try:
                    from vosk import Model
                    self._load_count += 1
                    logger.info(
                        f"Loading local acoustic model from: {norm_path} "
                        f"(Vosk model load count = {self._load_count})"
                    )
                    self._models[norm_path] = Model(norm_path)
                    self._ref_counts[norm_path] = set()
                except Exception as e:
                    logger.error(f"Failed to load Vosk Model from '{norm_path}': {e}")
                    return None

            self._ref_counts[norm_path].add(consumer_id)
            logger.debug(
                f"Vosk model '{os.path.basename(norm_path)}' acquired by '{consumer_id}' "
                f"(ref_count={len(self._ref_counts[norm_path])})"
            )
            return self._models[norm_path]

    def release_model(self, model_path: str, consumer_id: str = "default"):
        """
        Decrements the ref count for model_path by consumer_id.
        Unloads and releases the model if ref count drops to 0.
        """
        if not model_path:
            return

        norm_path = os.path.abspath(model_path)
        with self._lock:
            if norm_path in self._ref_counts:
                self._ref_counts[norm_path].discard(consumer_id)
                logger.debug(
                    f"Vosk model '{os.path.basename(norm_path)}' released by '{consumer_id}' "
                    f"(ref_count={len(self._ref_counts[norm_path])})"
                )
                if len(self._ref_counts[norm_path]) == 0:
                    del self._models[norm_path]
                    del self._ref_counts[norm_path]
                    logger.info(
                        f"Vosk model '{os.path.basename(norm_path)}' unloaded from memory "
                        f"(ref_count reached 0)"
                    )

    def create_recognizer(
        self,
        model_path: str,
        consumer_id: str,
        sample_rate: int = 16000,
        grammar: Optional[List[str]] = None
    ) -> Optional[Any]:
        """
        Creates a new KaldiRecognizer bound to the shared Vosk Model.
        Acquires model reference automatically.
        """
        model = self.acquire_model(model_path, consumer_id)
        if model is None:
            return None

        try:
            from vosk import KaldiRecognizer
            if grammar:
                grammar_json = json.dumps(grammar)
                rec = KaldiRecognizer(model, sample_rate, grammar_json)
                rec.SetWords(False)
            else:
                rec = KaldiRecognizer(model, sample_rate)

            norm_path = os.path.abspath(model_path)
            with self._lock:
                rec_id = f"{consumer_id}_{id(rec)}"
                self._recognizers[rec_id] = (rec, norm_path, consumer_id)

            return rec
        except Exception as e:
            logger.error(f"Failed to create KaldiRecognizer for consumer '{consumer_id}': {e}")
            self.release_model(model_path, consumer_id)
            return None

    def destroy_recognizer(self, rec: Any, consumer_id: Optional[str] = None):
        """
        Explicitly destroys a recognizer and releases its associated model reference.
        """
        if rec is None:
            return

        with self._lock:
            matched_id = None
            for rec_id, (rec_obj, model_path, c_id) in list(self._recognizers.items()):
                if rec_obj is rec or id(rec_obj) == id(rec):
                    matched_id = rec_id
                    break

            if matched_id:
                rec_obj, model_path, c_id = self._recognizers.pop(matched_id)
                self.release_model(model_path, c_id)

    def release_models(self):
        """Shutdown helper: destroys all active recognizers and unloads all models."""
        with self._lock:
            # First unbind recognizers
            self._recognizers.clear()
            # Then unload all models
            model_count = len(self._models)
            self._models.clear()
            self._ref_counts.clear()
            if model_count > 0:
                logger.info(f"🛑 Released {model_count} shared Vosk acoustic model(s)")

    def get_load_count(self) -> int:
        """Returns total number of physical model disk loads since start/reset."""
        with self._lock:
            return self._load_count

    def get_model_object_count(self) -> int:
        """Returns number of unique vosk.Model objects currently residing in RAM."""
        with self._lock:
            return len(self._models)

    def get_recognizer_count(self) -> int:
        """Returns number of active KaldiRecognizer objects currently registered."""
        with self._lock:
            return len(self._recognizers)


# Canonical singleton instance
vosk_manager = VoskModelManager.get_instance()
