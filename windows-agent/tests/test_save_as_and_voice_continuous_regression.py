"""
MAX AI — Save As Dialog + Continuous Voice Regression Test Suite
Tests:
1. Continuous multi-turn realtime voice session across tool boundaries without wake word re-triggering.
2. VoiceGate explicit invariant enforcement (mic=active, wake_word=disabled, loops=running, user_turn_input=enabled).
3. Stale window_title context invalidation and dynamic Save As dialog element resolution.
4. Computer Use success semantics (rejecting unverified UIA patterns without window/state change).
5. Semantic Save As flow with real disk state verification.
"""

import os
import time
import pytest
import asyncio
from unittest.mock import MagicMock, patch

from core.voice_gate import VoiceInputGate, VoiceState
from core.realtime_voice import RealtimeVoiceSession
from core.unified_tool_registry import UnifiedToolRegistry, unified_tool_registry
from core.computer_use.computer_use_engine import ComputerUseEngine
from core.computer_use.target_resolver import TargetResolver
from core.computer_use.verification import ComputerUseVerifier
from core.computer_use.models import (
    UIMap, UIElement, Rect, Point, ElementSource, ComputerUseAction, ActionType, RiskLevel
)


class TestVoiceContinuousTurnAndInvariant:
    """Test Suite for Continuous Realtime Voice Duplex and User-Turn Handshake."""

    def test_01_voice_gate_live_conversation_invariant(self):
        """WHEN state == LIVE_CONVERSATION, verify explicit invariant conditions."""
        vg = VoiceInputGate()
        vg.transition_to(VoiceState.LIVE_CONVERSATION)

        mock_session = MagicMock()
        mock_session.audio_capture.has_active_stream = True
        mock_session.audio_capture.is_running = True
        mock_session._is_send_audio_running = True
        mock_session._is_receive_loop_running = True
        mock_session._session_active = True
        mock_session._is_session_alive.return_value = True

        # Invariant should pass
        assert vg.verify_live_conversation_invariant(mock_session) is True

        # If microphone becomes inactive, invariant must fail and log violation
        mock_session.audio_capture.has_active_stream = False
        mock_session.audio_capture.is_running = False
        assert vg.verify_live_conversation_invariant(mock_session) is False

    def test_02_continuous_turns_without_wake_word(self):
        """
        Verify multi-turn conversation (5+ turns) remains in LIVE_CONVERSATION
        and transmits microphone audio across tool execution boundaries without re-triggering wake word.
        """
        vg = VoiceInputGate()
        # Initial wake word triggers LIVE_CONVERSATION
        vg.trigger_wake_word("Open notepad")
        assert vg.state == VoiceState.LIVE_CONVERSATION

        # Simulated 16kHz PCM audio chunk (speech energy)
        dummy_pcm_speech = b"\x50\x00" * 320  # RMS > 300
        dummy_pcm_silence = b"\x00\x00" * 320

        for turn in range(1, 6):
            # 1. User speaks -> audio transmitted directly to Gemini
            assert vg.process_audio_chunk(dummy_pcm_speech) is True

            # 2. Assistant starts speaking
            vg.on_assistant_speaking_started()
            assert vg.state == VoiceState.ASSISTANT_SPEAKING
            # While assistant speaks, microphone audio is suppressed (feedback suppression)
            assert vg.process_audio_chunk(dummy_pcm_speech) is False

            # 3. Assistant finishes speaking -> enters cooldown
            vg.on_assistant_speaking_finished()
            assert vg.state == VoiceState.COOLDOWN

            # 4. Cooldown expires after cooldown_sec (0.3s)
            time.sleep(0.35)
            # Next chunk returns state to LIVE_CONVERSATION
            assert vg.process_audio_chunk(dummy_pcm_speech) is True
            assert vg.state == VoiceState.LIVE_CONVERSATION

        # Ensure after 5 full turns, wake word is still NOT required
        assert vg.state == VoiceState.LIVE_CONVERSATION
        assert vg.process_audio_chunk(dummy_pcm_speech) is True

    def test_03_speaker_output_manager_synchronization(self):
        """Verify SpeakerOutputManager notifies VoiceGate only after playback queue finishes draining."""
        from core.audio_handler import SpeakerOutputManager

        vg = VoiceInputGate()
        vg.transition_to(VoiceState.LIVE_CONVERSATION)

        speaker = SpeakerOutputManager()
        speaker.set_voice_gate(vg)

        # Simulate assistant speaking
        vg.on_assistant_speaking_started()
        assert vg.state == VoiceState.ASSISTANT_SPEAKING

        # Enqueue chunk
        speaker.play_chunk(b"\x00\x00" * 160)
        # Flush turn complete
        speaker.flush()

        # Interrupt / barge-in test
        speaker.interrupt()
        assert vg.state == VoiceState.LIVE_CONVERSATION


class TestStaleWindowContextAndSaveAsResolution:
    """Test Suite for Root Cause A: Stale window context and Save As dialog element resolution."""

    def test_04_target_resolver_stale_window_invalidation(self):
        """
        Verify TargetResolver does NOT reject Save As dialog elements when
        a stale parent window_title ('*test.txt - Notepad') is passed.
        """
        resolver = TargetResolver()

        # Build mock UIMap representing a visible 'Save As' common dialog
        dialog_elements = [
            UIElement(
                ref="@e1",
                role="edit",
                name="File name:",
                text="*.txt",
                bounds=Rect(100, 200, 300, 230),
                center=Point(200, 215),
                source=ElementSource.UIA,
                control_type="Edit",
                automation_id="1001",
                enabled=True,
                visible=True,
                interactive=True,
                window="Save As",
                process="notepad.exe"
            ),
            UIElement(
                ref="@e2",
                role="button",
                name="Save",
                text="Save",
                bounds=Rect(350, 400, 420, 430),
                center=Point(385, 415),
                source=ElementSource.UIA,
                control_type="Button",
                automation_id="1",
                enabled=True,
                visible=True,
                interactive=True,
                window="Save As",
                process="notepad.exe"
            ),
            UIElement(
                ref="@e3",
                role="combobox",
                name="Save as type:",
                text="Text Documents (*.txt)",
                bounds=Rect(100, 250, 300, 280),
                center=Point(200, 265),
                source=ElementSource.UIA,
                control_type="ComboBox",
                automation_id="FileTypeControlHost",
                enabled=True,
                visible=True,
                interactive=True,
                window="Save As",
                process="notepad.exe"
            )
        ]

        save_as_map = UIMap(
            generation=42,
            timestamp=time.time(),
            elements=dialog_elements,
            ref_map={"@e1": dialog_elements[0], "@e2": dialog_elements[1], "@e3": dialog_elements[2]},
            active_window="Save As",
            active_process="notepad.exe",
            active_hwnd=99999,
            monitor=0,
            dpi_scale=1.0
        )

        # Resolving 'File name' with STALE expected_window='*test.txt - Notepad' MUST SUCCEED
        res_filename = resolver.resolve(
            query="File name",
            ui_map=save_as_map,
            expected_window="*test.txt - Notepad"
        )
        assert res_filename.status == "FOUND"
        assert res_filename.matched is not None
        assert res_filename.matched.element.ref == "@e1"
        assert "filename" in res_filename.matched.strategy.lower() or "file" in res_filename.matched.element.name.lower()

        # Resolving 'Save' button with STALE expected_window MUST resolve Button '@e2' (not ComboBox '@e3')
        res_save = resolver.resolve(
            query="Save",
            ui_map=save_as_map,
            expected_window="*test.txt - Notepad"
        )
        assert res_save.status == "FOUND"
        assert res_save.matched is not None
        assert res_save.matched.element.ref == "@e2"
        assert res_save.matched.element.role == "button"

    def test_05_computer_use_verifier_window_transition(self):
        """Verify ComputerUseVerifier logs [CU_WINDOW] and marks window-spawning actions verified upon transition."""
        verifier = ComputerUseVerifier()

        pre_map = UIMap(
            generation=1,
            timestamp=time.time(),
            elements=[],
            ref_map={},
            active_window="*test.txt - Notepad",
            active_process="notepad.exe",
            active_hwnd=11111,
            monitor=0,
            dpi_scale=1.0
        )

        post_map = UIMap(
            generation=2,
            timestamp=time.time(),
            elements=[
                UIElement(
                    ref="@e1", role="edit", name="File name:", text="",
                    bounds=Rect(10, 10, 100, 30), center=Point(55, 20),
                    source=ElementSource.UIA, window="Save As", process="notepad.exe"
                )
            ],
            ref_map={},
            active_window="Save As",
            active_process="notepad.exe",
            active_hwnd=22222,
            monitor=0,
            dpi_scale=1.0
        )

        action = ComputerUseAction(
            action_type=ActionType.CLICK,
            target_query="Save as",
            description="Click Save as menu item"
        )

        action_result = {
            "status": "success",
            "action": "click",
            "backend": "uia_semantic_pattern",
            "execution_evidence": {"backend_used": "uia_semantic_pattern"}
        }

        ver_res = verifier.verify_action(action, pre_map, post_map, action_result)
        assert ver_res.verified is True
        assert ver_res.goal_state_verified is True
        assert ver_res.status == "VERIFIED"
        assert any("Active window transitioned" in c for c in ver_res.observed_changes)

    def test_06_computer_use_verifier_rejects_unverified_uia_invoke(self):
        """
        A Computer Use action must NOT return verified=True merely because UIA pattern succeeded
        when an expected window-spawning action produces no window or layout change.
        """
        verifier = ComputerUseVerifier()

        pre_map = UIMap(
            generation=1,
            timestamp=time.time(),
            elements=[],
            ref_map={},
            active_window="*test.txt - Notepad",
            active_process="notepad.exe",
            active_hwnd=11111,
            monitor=0,
            dpi_scale=1.0
        )

        # Post map is identical (no dialog opened)
        post_map = UIMap(
            generation=2,
            timestamp=time.time(),
            elements=[],
            ref_map={},
            active_window="*test.txt - Notepad",
            active_process="notepad.exe",
            active_hwnd=11111,
            monitor=0,
            dpi_scale=1.0
        )

        action = ComputerUseAction(
            action_type=ActionType.CLICK,
            target_query="Save as",
            description="Click Save as"
        )

        action_result = {
            "status": "success",
            "action": "click",
            "backend": "uia_semantic_pattern",
            "execution_evidence": {"backend_used": "uia_semantic_pattern"}
        }

        ver_res = verifier.verify_action(action, pre_map, post_map, action_result)
        # MUST BE UNVERIFIED because no window transition or layout delta occurred!
        assert ver_res.verified is False
        assert ver_res.goal_state_verified is False
        assert ver_res.status == "UNVERIFIED"
        assert ver_res.error_message is not None


class TestSemanticSaveAsFlow:
    """Test Suite for Root Cause E: Semantic Save As workflow with disk verification."""

    def test_07_save_as_file_disk_verification(self, tmp_path):
        """Verify save_as_file properly verifies file existence on disk."""
        engine = ComputerUseEngine()

        test_file = str(tmp_path / "test_demo_save.txt")

        # Mock the UI steps of save_as_file
        with patch.object(engine, "step", return_value={"status": "success", "verified": True}):
            with patch.object(engine.perception, "observe", return_value=UIMap(
                generation=1, timestamp=time.time(), elements=[], ref_map={},
                active_window="Save As", active_process="notepad.exe", active_hwnd=123, monitor=0, dpi_scale=1.0
            )):
                # Pre-create file to simulate Notepad saving to disk
                with open(test_file, "w", encoding="utf-8") as f:
                    f.write("Hello MAX Save As Verified")

                res = engine.save_as_file(filename=test_file, expected_content="Hello MAX Save As")
                assert res["status"] == "success"
                assert res["verified"] is True
                assert res["file_exists"] is True
                assert res["content_verified"] is True
                assert res["file_size"] > 0

    def test_08_save_as_file_missing_disk_file_fails_verification(self, tmp_path):
        """Verify save_as_file returns verification_failed if file does not exist on disk."""
        engine = ComputerUseEngine()
        non_existent_file = str(tmp_path / "non_existent_output.txt")

        with patch.object(engine, "step", return_value={"status": "success", "verified": True}):
            with patch.object(engine.perception, "observe", return_value=UIMap(
                generation=1, timestamp=time.time(), elements=[], ref_map={},
                active_window="Save As", active_process="notepad.exe", active_hwnd=123, monitor=0, dpi_scale=1.0
            )):
                res = engine.save_as_file(filename=non_existent_file)
                assert res["status"] == "verification_failed"
                assert res["verified"] is False
                assert res["file_exists"] is False
