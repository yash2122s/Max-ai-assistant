"""
MAX AI — Windows PC Webcam & Vision Capture Subsystem
Native Windows PC webcam capture, hardware probing, and real-time visual frame compression.

Key Capabilities:
  1. Windows DirectShow (cv2.CAP_DSHOW) Backend: Fast initialization (<100ms), eliminating MSMF delays and resource locks.
  2. Smart Multi-Index Camera Probing: Auto-scans indices 0..5, runs auto-exposure warmup (5-10 frames), and tests luminance (mean > 8) to bypass disabled/blank feeds.
  3. Caching & Config Persistence: Automatically caches verified camera index in configuration.
  4. Real-Time Gemini Live Frame Compression: Downscales to 720p (1280x720) JPEG @ 82% quality for ultra-low latency vision streaming.
  5. Verified File Storage: Saves captured photos to Pictures/Webcam with ActionVerifier verification.
  6. In-Memory Streaming: Exposes get_webcam_frame_bytes() for zero-copy memory injection into Gemini Live WebSocket and Vision Coordinator.

Integrates natively with MAX SecurityGuard, ActionVerifier, and UnifiedToolRegistry.
"""

import os
import io
import json
import time
import datetime
import logging
import platform
from pathlib import Path
from typing import Dict, Any, Optional, Tuple, Union
try:
    import numpy as np
except ImportError:
    np = None

try:
    import cv2
    _CV2_AVAILABLE = True
except ImportError:
    _CV2_AVAILABLE = False

try:
    import PIL.Image
    _PIL_AVAILABLE = True
except ImportError:
    _PIL_AVAILABLE = False

from core.security import security_guard
from core.verifier import action_verifier
from tools.file_manager import SPECIAL_FOLDERS, _resolve_user_path

logger = logging.getLogger("MAXWindowsAgent.Webcam")

_IMG_MAX_W = 1280
_IMG_MAX_H = 720
_DEFAULT_JPEG_QUALITY = 82
_CONFIG_FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "config.json")


# ─── Configuration & Backend Resolution ─────────────────────────────────────

def _load_cached_camera_index() -> Optional[int]:
    """Loads previously detected working camera index from config.json."""
    if os.path.exists(_CONFIG_FILE):
        try:
            with open(_CONFIG_FILE, "r", encoding="utf-8") as f:
                cfg = json.load(f)
                if "camera_index" in cfg:
                    return int(cfg["camera_index"])
        except Exception as e:
            logger.debug(f"Could not load camera_index from config: {e}")
    return None


def _save_cached_camera_index(index: int) -> None:
    """Saves detected camera index to config.json."""
    try:
        cfg = {}
        if os.path.exists(_CONFIG_FILE):
            try:
                with open(_CONFIG_FILE, "r", encoding="utf-8") as f:
                    cfg = json.load(f)
            except Exception:
                cfg = {}
        cfg["camera_index"] = int(index)
        with open(_CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2, ensure_ascii=False)
    except Exception as e:
        logger.debug(f"Could not save camera_index to config: {e}")


def _cv2_backend() -> int:
    """
    Returns the optimal OpenCV camera capture backend for the host OS.
    On Windows, uses cv2.CAP_DSHOW (DirectShow) for fast initialization and reliable resource teardown.
    """
    if not _CV2_AVAILABLE:
        return 0
    sys_name = platform.system().lower()
    if sys_name == "windows":
        return getattr(cv2, "CAP_DSHOW", 700)
    elif sys_name == "darwin":
        return getattr(cv2, "CAP_AVFOUNDATION", 1200)
    return getattr(cv2, "CAP_ANY", 0)


# ─── Camera Probing & Hardware Auto-Detection ────────────────────────────────

def _probe_camera(index: int, backend: int, warmup_frames: int = 5) -> bool:
    """
    Probes a specific camera index, performs warmup reads for auto-exposure stabilization,
    and validates frame luminance (mean pixel brightness > 8) to filter out blank/disabled devices.
    """
    if not _CV2_AVAILABLE:
        return False

    cap = None
    try:
        cap = cv2.VideoCapture(index, backend)
        if not cap.isOpened():
            return False

        # Read warmup frames for auto-exposure / white-balance stabilization
        for _ in range(max(1, warmup_frames)):
            cap.read()

        ret, frame = cap.read()
        if not ret or frame is None:
            return False

        # Check luminance: ensure frame is not completely dark/blank (OBS placeholder / disabled cover)
        mean_val = float(np.mean(frame))
        return mean_val > 8.0

    except Exception as e:
        logger.debug(f"Error probing camera index {index}: {e}")
        return False
    finally:
        if cap is not None:
            try:
                cap.release()
            except Exception:
                pass


def detect_webcam(force_rescan: bool = False, max_indices: int = 6, **kwargs) -> dict:
    """
    Auto-detects available Windows PC webcams by probing indices 0..max_indices with DirectShow.
    Validates frame brightness and caches the verified camera index.
    
    Args:
        force_rescan: If True, ignores cached camera index and rescans hardware (default: False).
        max_indices: Maximum camera indices to scan (default: 6).
    """
    if not _CV2_AVAILABLE:
        return {
            "status": "error",
            "message": "OpenCV (cv2) is not installed in the Python environment.",
            "verified": False
        }

    if not force_rescan:
        cached = _load_cached_camera_index()
        if cached is not None:
            backend = _cv2_backend()
            if _probe_camera(cached, backend, warmup_frames=3):
                return {
                    "status": "success",
                    "operation": "detect_webcam",
                    "camera_index": cached,
                    "source": "cached_verified",
                    "message": f"Webcam verified and ready at camera index {cached}.",
                    "verified": True
                }

    backend = _cv2_backend()
    logger.info("Scanning for connected Windows webcams via DirectShow...")
    
    for idx in range(max_indices):
        if _probe_camera(idx, backend, warmup_frames=5):
            _save_cached_camera_index(idx)
            logger.info(f"Working webcam detected at index {idx}.")
            return {
                "status": "success",
                "operation": "detect_webcam",
                "camera_index": idx,
                "source": "hardware_scan",
                "message": f"Working webcam found and cached at camera index {idx}.",
                "verified": True
            }

    # Fallback to index 0 if no probe succeeded
    _save_cached_camera_index(0)
    return {
        "status": "warning",
        "operation": "detect_webcam",
        "camera_index": 0,
        "source": "fallback_default",
        "message": "No active webcam detected with positive luminance; defaulting to camera index 0.",
        "verified": False
    }


def _get_active_camera_index() -> int:
    """Returns the verified active camera index."""
    cached = _load_cached_camera_index()
    if cached is not None:
        return cached
    res = detect_webcam()
    return res.get("camera_index", 0)


# ─── Frame Capture & Image Compression ──────────────────────────────────────

def _compress_frame_to_jpeg(frame: np.ndarray, quality: int = _DEFAULT_JPEG_QUALITY) -> Tuple[bytes, int, int]:
    """
    Compresses an OpenCV BGR frame into a 720p JPEG buffer optimized for Gemini Live vision.
    Returns (jpeg_bytes, width, height).
    """
    if _PIL_AVAILABLE:
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        img = PIL.Image.fromarray(rgb)
        img.thumbnail((_IMG_MAX_W, _IMG_MAX_H), PIL.Image.BILINEAR)
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=quality, optimize=False)
        w, h = img.size
        return buf.getvalue(), w, h
    else:
        # OpenCV resize fallback
        h_orig, w_orig = frame.shape[:2]
        scale = min(_IMG_MAX_W / w_orig, _IMG_MAX_H / h_orig, 1.0)
        target_w, target_h = int(w_orig * scale), int(h_orig * scale)
        resized = cv2.resize(frame, (target_w, target_h), interpolation=cv2.INTER_AREA)
        _, enc = cv2.imencode(".jpg", resized, [cv2.IMWRITE_JPEG_QUALITY, quality])
        return enc.tobytes(), target_w, target_h


def get_webcam_frame_bytes(quality: int = _DEFAULT_JPEG_QUALITY, warmup_frames: int = 8) -> Tuple[bytes, str]:
    """
    Direct in-memory webcam frame capture for Gemini Live video stream injection and Vision Coordinator.
    Returns (jpeg_bytes, 'image/jpeg').
    """
    if not _CV2_AVAILABLE:
        raise RuntimeError("OpenCV (cv2) is not installed.")

    index = _get_active_camera_index()
    backend = _cv2_backend()
    cap = cv2.VideoCapture(index, backend)

    if not cap.isOpened():
        raise RuntimeError(f"Webcam at index {index} could not be opened.")

    try:
        # Warmup reads to allow sensor gain and auto-exposure to stabilize
        for _ in range(max(1, warmup_frames)):
            cap.read()

        ret, frame = cap.read()
        if not ret or frame is None:
            raise RuntimeError("Webcam returned no valid image frame.")

        jpeg_bytes, _, _ = _compress_frame_to_jpeg(frame, quality=quality)
        return jpeg_bytes, "image/jpeg"
    finally:
        cap.release()


# ─── Public Tool Endpoints ──────────────────────────────────────────────────

def capture_webcam(
    save_to_disk: bool = True,
    path: Optional[str] = None,
    quality: int = _DEFAULT_JPEG_QUALITY,
    warmup_frames: int = 8,
    **kwargs
) -> dict:
    """
    Captures a photo from the local Windows PC webcam using DirectShow hardware acceleration.
    Automatically applies 720p JPEG compression, saves to Pictures/Webcam directory, and verifies storage.
    
    Args:
        save_to_disk: Whether to save the captured photo to disk (default: True).
        path: Optional custom file path or filename to save the photo.
        quality: JPEG compression quality (default: 82).
        warmup_frames: Number of auto-exposure warmup frames (default: 8).
    """
    if not _CV2_AVAILABLE:
        return {
            "status": "error",
            "message": "OpenCV (cv2) is required for webcam capture. Run: pip install opencv-python",
            "verified": False
        }

    cap = None
    try:
        index = _get_active_camera_index()
        backend = _cv2_backend()
        cap = cv2.VideoCapture(index, backend)

        if not cap.isOpened():
            # If default index failed, attempt immediate re-scan
            detect_res = detect_webcam(force_rescan=True)
            index = detect_res.get("camera_index", 0)
            cap = cv2.VideoCapture(index, backend)
            if not cap.isOpened():
                return {
                    "status": "error",
                    "message": f"Could not access Windows webcam at camera index {index}.",
                    "verified": False
                }

        # Auto-exposure stabilization
        for _ in range(max(1, warmup_frames)):
            cap.read()

        ret, frame = cap.read()
        if not ret or frame is None:
            return {
                "status": "error",
                "message": "Webcam returned an empty or invalid frame.",
                "verified": False
            }

        jpeg_bytes, width, height = _compress_frame_to_jpeg(frame, quality=quality)
        out_filepath = None
        verified = True

        if save_to_disk:
            # Determine destination path
            if path:
                out_filepath = _resolve_user_path(path)
            else:
                timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
                pictures_dir = SPECIAL_FOLDERS.get("pictures", os.path.expanduser("~/Pictures"))
                webcam_dir = os.path.join(pictures_dir, "Webcam")
                os.makedirs(webcam_dir, exist_ok=True)
                out_filepath = os.path.join(webcam_dir, f"webcam_{timestamp}.jpg")

            if not security_guard.is_safe_path(out_filepath):
                return {
                    "status": "error",
                    "message": f"Access denied: Target path '{out_filepath}' is outside sandbox.",
                    "verified": False
                }

            os.makedirs(os.path.dirname(out_filepath), exist_ok=True)
            with open(out_filepath, "wb") as f:
                f.write(jpeg_bytes)

            verified = action_verifier.verify_file_created_sync(out_filepath)

        return {
            "status": "success",
            "operation": "capture_webcam",
            "camera_index": index,
            "width": width,
            "height": height,
            "format": "JPEG",
            "bytes_size": len(jpeg_bytes),
            "file_path": out_filepath,
            "saved_to_disk": save_to_disk,
            "message": f"Webcam photo captured ({width}x{height})" + (f" and saved to: {out_filepath}" if out_filepath else "."),
            "verified": verified
        }

    except Exception as e:
        logger.error(f"Error in capture_webcam: {e}")
        return {
            "status": "error",
            "message": f"Webcam capture failed: {str(e)}",
            "verified": False
        }
    finally:
        if cap is not None:
            try:
                cap.release()
            except Exception:
                pass


def webcam_photo(path: Optional[str] = None, **kwargs) -> dict:
    """Tool alias for capture_webcam."""
    return capture_webcam(save_to_disk=True, path=path, **kwargs)


# ─── Tool Declarations for Unified Registry ─────────────────────────────────

TOOL_DECLARATIONS = [
    {
        "name": "capture_webcam",
        "description": "Capture a photo from the local Windows PC webcam using DirectShow hardware acceleration. Saves to Pictures/Webcam directory.",
        "parameters": {
            "type": "object",
            "properties": {
                "save_to_disk": {
                    "type": "boolean",
                    "description": "Whether to save photo to disk (default: true).",
                },
                "path": {
                    "type": "string",
                    "description": "Optional custom file path or name to save the captured photo.",
                },
                "quality": {
                    "type": "integer",
                    "description": "JPEG compression quality (1-100, default: 82).",
                }
            },
        },
    },
    {
        "name": "detect_webcam",
        "description": "Auto-detect and probe connected Windows webcams (indices 0..5), verify auto-exposure luminance, and cache the active camera index.",
        "parameters": {
            "type": "object",
            "properties": {
                "force_rescan": {
                    "type": "boolean",
                    "description": "Force a full hardware rescan instead of using cached index.",
                }
            },
        },
    },
]

TOOL_FUNCTIONS = {
    "capture_webcam": capture_webcam,
    "webcam_photo": webcam_photo,
    "detect_webcam": detect_webcam,
}
