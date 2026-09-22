"""
Unit Tests for MAX Windows PC Webcam & Vision Capture Subsystem.
Tests hardware probing, DirectShow backend selection, luminance verification,
720p JPEG compression, in-memory stream injection, file saving, and UnifiedToolRegistry integration.
"""

import os
import sys
import json
import tempfile
import pytest
import numpy as np
from unittest.mock import MagicMock, patch

# Ensure windows-agent root is in sys.path
AGENT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_ROOT not in sys.path:
    sys.path.insert(0, AGENT_ROOT)

import cv2
from tools.webcam_tool import (
    _cv2_backend,
    _probe_camera,
    _load_cached_camera_index,
    _save_cached_camera_index,
    detect_webcam,
    _compress_frame_to_jpeg,
    get_webcam_frame_bytes,
    capture_webcam,
    webcam_photo,
)
from core.vision_coordinator import vision_coordinator
from core.unified_tool_registry import UnifiedToolRegistry


# ─── 1. Backend Resolution & Cache Tests ────────────────────────────────────

def test_cv2_backend():
    with patch("platform.system", return_value="Windows"):
        backend = _cv2_backend()
        expected = getattr(cv2, "CAP_DSHOW", 700)
        assert backend == expected

    with patch("platform.system", return_value="Darwin"):
        backend = _cv2_backend()
        expected = getattr(cv2, "CAP_AVFOUNDATION", 1200)
        assert backend == expected


def test_camera_index_cache(tmp_path):
    temp_config = tmp_path / "config.json"
    with patch("tools.webcam_tool._CONFIG_FILE", str(temp_config)):
        # Initially empty
        assert _load_cached_camera_index() is None
        
        # Save and reload
        _save_cached_camera_index(2)
        assert _load_cached_camera_index() == 2


# ─── 2. Camera Probing & Luminance Verification ─────────────────────────────

def test_probe_camera_valid_frame():
    mock_cap = MagicMock()
    mock_cap.isOpened.return_value = True
    # Create test frame with bright colors (mean > 8)
    valid_frame = np.full((480, 640, 3), 120, dtype=np.uint8)
    mock_cap.read.return_value = (True, valid_frame)

    with patch("cv2.VideoCapture", return_value=mock_cap):
        result = _probe_camera(0, cv2.CAP_DSHOW, warmup_frames=2)
        assert result is True
        mock_cap.release.assert_called_once()


def test_probe_camera_dark_or_blank_frame():
    mock_cap = MagicMock()
    mock_cap.isOpened.return_value = True
    # Completely black frame (mean <= 8)
    black_frame = np.zeros((480, 640, 3), dtype=np.uint8)
    mock_cap.read.return_value = (True, black_frame)

    with patch("cv2.VideoCapture", return_value=mock_cap):
        result = _probe_camera(0, cv2.CAP_DSHOW, warmup_frames=2)
        assert result is False
        mock_cap.release.assert_called_once()


def test_probe_camera_cannot_open():
    mock_cap = MagicMock()
    mock_cap.isOpened.return_value = False

    with patch("cv2.VideoCapture", return_value=mock_cap):
        result = _probe_camera(99, cv2.CAP_DSHOW)
        assert result is False


# ─── 3. Hardware Auto-Detection & Scanning ──────────────────────────────────

def test_detect_webcam_scans_hardware(tmp_path):
    temp_config = tmp_path / "config.json"
    with patch("tools.webcam_tool._CONFIG_FILE", str(temp_config)), \
         patch("tools.webcam_tool._probe_camera") as mock_probe:
        
        # Index 0 fails, Index 1 succeeds
        def probe_side_effect(idx, backend, warmup_frames=5):
            return idx == 1
        mock_probe.side_effect = probe_side_effect

        res = detect_webcam(force_rescan=True, max_indices=4)
        assert res["status"] == "success"
        assert res["camera_index"] == 1
        assert res["source"] == "hardware_scan"
        assert res["verified"] is True
        assert _load_cached_camera_index() == 1


def test_detect_webcam_uses_valid_cache(tmp_path):
    temp_config = tmp_path / "config.json"
    with patch("tools.webcam_tool._CONFIG_FILE", str(temp_config)), \
         patch("tools.webcam_tool._probe_camera", return_value=True):
        
        _save_cached_camera_index(0)
        res = detect_webcam(force_rescan=False)
        assert res["status"] == "success"
        assert res["camera_index"] == 0
        assert res["source"] == "cached_verified"
        assert res["verified"] is True


# ─── 4. Image Compression & In-Memory Capture ───────────────────────────────

def test_compress_frame_to_jpeg():
    # 1920x1080 high-res test frame
    raw_frame = np.full((1080, 1920, 3), 180, dtype=np.uint8)
    jpeg_bytes, w, h = _compress_frame_to_jpeg(raw_frame, quality=82)
    
    assert isinstance(jpeg_bytes, bytes)
    assert len(jpeg_bytes) > 0
    # Must be downscaled to within 1280x720
    assert w <= 1280
    assert h <= 720
    # Check JPEG header SOI marker (\xff\xd8)
    assert jpeg_bytes[:2] == b"\xff\xd8"


def test_get_webcam_frame_bytes():
    mock_cap = MagicMock()
    mock_cap.isOpened.return_value = True
    test_frame = np.full((720, 1280, 3), 150, dtype=np.uint8)
    mock_cap.read.return_value = (True, test_frame)

    with patch("cv2.VideoCapture", return_value=mock_cap), \
         patch("tools.webcam_tool._get_active_camera_index", return_value=0):
        jpeg_bytes, mime = get_webcam_frame_bytes(quality=80, warmup_frames=2)
        assert isinstance(jpeg_bytes, bytes)
        assert mime == "image/jpeg"
        assert jpeg_bytes[:2] == b"\xff\xd8"
        mock_cap.release.assert_called_once()


# ─── 5. Capture Photo Tool & File Storage ───────────────────────────────────

def test_capture_webcam_saves_to_disk(tmp_path):
    mock_cap = MagicMock()
    mock_cap.isOpened.return_value = True
    test_frame = np.full((720, 1280, 3), 200, dtype=np.uint8)
    mock_cap.read.return_value = (True, test_frame)

    target_file = str(tmp_path / "custom_photo.jpg")

    with patch("cv2.VideoCapture", return_value=mock_cap), \
         patch("tools.webcam_tool._get_active_camera_index", return_value=0), \
         patch("core.security.security_guard.is_safe_path", return_value=True), \
         patch("core.verifier.action_verifier.verify_file_created_sync", return_value=True):
        
        res = capture_webcam(save_to_disk=True, path=target_file)
        assert res["status"] == "success"
        assert res["operation"] == "capture_webcam"
        assert res["verified"] is True
        assert res["saved_to_disk"] is True
        assert os.path.exists(target_file)
        assert os.path.getsize(target_file) > 0


def test_webcam_photo_alias(tmp_path):
    mock_cap = MagicMock()
    mock_cap.isOpened.return_value = True
    test_frame = np.full((720, 1280, 3), 200, dtype=np.uint8)
    mock_cap.read.return_value = (True, test_frame)

    target_file = str(tmp_path / "alias_photo.jpg")

    with patch("cv2.VideoCapture", return_value=mock_cap), \
         patch("tools.webcam_tool._get_active_camera_index", return_value=0), \
         patch("core.security.security_guard.is_safe_path", return_value=True), \
         patch("core.verifier.action_verifier.verify_file_created_sync", return_value=True):
        
        res = webcam_photo(path=target_file)
        assert res["status"] == "success"
        assert res["verified"] is True
        assert os.path.exists(target_file)


# ─── 6. Vision Coordinator & Registry Integration ───────────────────────────

def test_vision_coordinator_webcam_triggers():
    # Direct tool detection
    assert vision_coordinator.detect_vision_requirement("capture_webcam") is True
    assert vision_coordinator.detect_vision_requirement("webcam_photo") is True
    assert vision_coordinator.detect_vision_requirement("detect_webcam") is True

    # Prompt hint detection
    assert vision_coordinator.detect_vision_requirement(prompt_hint="take a webcam photo please") is True
    assert vision_coordinator.detect_vision_requirement(prompt_hint="check my pc camera") is True
    assert vision_coordinator.detect_vision_requirement(prompt_hint="open webcam and take photo") is True


def test_unified_tool_registry_webcam_integration():
    registry = UnifiedToolRegistry.get_instance()

    # Tool functions registered
    assert "capture_webcam" in registry._functions
    assert "webcam_photo" in registry._functions
    assert "detect_webcam" in registry._functions

    # Tool declarations registered
    decl_names = [d["name"] for d in registry.get_declarations()]
    assert "capture_webcam" in decl_names
    assert "detect_webcam" in decl_names

    # Check aliases map correctly
    from core.unified_tool_registry import TOOL_ALIASES
    assert TOOL_ALIASES.get("take_webcam_photo") == "capture_webcam"
    assert TOOL_ALIASES.get("pc_webcam") == "capture_webcam"
    assert TOOL_ALIASES.get("check_webcam") == "detect_webcam"
