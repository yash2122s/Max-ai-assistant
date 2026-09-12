"""
Focused regression tests for:
1. Ordinary conversational statements do not invoke developer_task.
2. Ordinary questions do not invoke developer_task.
3. Explicit developer requests invoke developer_task / Antigravity (Tier 4).
4. Hermes tier attribute issue ('int' object has no attribute 'value') is fixed.
5. Tool result is properly structured for Gemini response generation.
6. Response audio reaches the playback path (AudioPlayer).
"""

import pytest
import asyncio
from unittest.mock import MagicMock, AsyncMock, patch

from core.task_classifier import task_classifier, TaskTier
from core.hermes_controller import hermes_controller, HermesRequest
from protocol.task_envelope import TaskStatus, TaskResult
from core.audio_handler import AudioPlayer


# 1. Ordinary conversational statement does not classify as developer task
def test_conversational_statement_does_not_invoke_developer_task():
    conversational_phrases = [
        "నిన్నే కొత్తగా బిల్డ్ చేశాను నేను. అంటే increase చేశాను, enhance చేశాను.",
        "I enhanced you yesterday.",
        "I built a new feature yesterday.",
        "You are working great today!",
        "Thanks for your help, MAX.",
    ]
    for phrase in conversational_phrases:
        res = task_classifier.classify(phrase)
        assert res.tier != TaskTier.TIER_4_DEVELOPER.value, f"Phrase '{phrase}' incorrectly classified as Tier 4!"
        assert res.task_type != "code_change", f"Phrase '{phrase}' incorrectly classified as code_change!"


# 2. Ordinary question does not classify as developer task
def test_ordinary_question_does_not_invoke_developer_task():
    questions = [
        "What happened?",
        "ఏమైంది?",
        "How are you doing today?",
        "What is the time right now?",
        "Can you hear me?",
    ]
    for q in questions:
        res = task_classifier.classify(q)
        assert res.tier != TaskTier.TIER_4_DEVELOPER.value, f"Question '{q}' incorrectly classified as Tier 4!"
        assert res.task_type != "code_change", f"Question '{q}' incorrectly classified as code_change!"


# 3. Explicit developer request STILL invokes developer_task / Antigravity (Tier 4)
def test_explicit_developer_request_invokes_tier4():
    developer_requests = [
        "Inspect the Paint automation code",
        "Fix the Paint automation code",
        "Check repository files and pytest results",
        "Inspect the Paint automation code and tell me whether the Brush tool selection performs a left click after detecting the Brush. Do not modify anything."
    ]
    for req in developer_requests:
        res = task_classifier.classify(req)
        assert res.tier == TaskTier.TIER_4_DEVELOPER.value, f"Request '{req}' not classified as Tier 4!"
        assert res.target_worker == "antigravity"


# 4. Hermes tier attribute issue is fixed (no 'int' object has no attribute 'value')
@pytest.mark.asyncio
async def test_hermes_controller_tier_int_handling_no_exception():
    mock_registry = MagicMock()
    mock_registry.execute_async = AsyncMock(return_value={"status": "completed", "message": "Success"})

    # Test with developer_task request
    h_req = HermesRequest(
        user_text="Inspect the Paint automation code",
        tool_name="developer_task",
        arguments={"instruction": "Inspect the Paint automation code"},
        source="gemini_live_voice"
    )

    with patch("orchestrator.max_task_authority.max_task_authority.submit_user_intent", new_callable=AsyncMock) as mock_submit:
        mock_submit.return_value = {
            "status": "completed",
            "task_id": "test-id-123",
            "worker": "antigravity",
            "tier": 4,
            "verified_success": True,
            "message": "Inspection complete.",
            "response": "Inspection complete."
        }

        res = await hermes_controller.handle_request(h_req, tool_registry=mock_registry)
        assert res.status == "completed"
        assert res.error is None
        assert "Inspection complete" in res.user_message


# 5. Tool result is structured with message/summary for Gemini response generation
@pytest.mark.asyncio
async def test_tool_result_structured_for_gemini():
    from orchestrator.max_task_authority import max_task_authority

    res = await max_task_authority.submit_user_intent(
        user_intent="Create a folder named VoiceTest on my Desktop.",
        source="voice",
        async_execution=False
    )

    assert "message" in res
    assert "response" in res
    assert "VoiceTest" in res["message"]
    assert res["status"] == "completed"
    assert res["verified_success"] is True


# 6. Response audio reaches playback path (AudioPlayer)
def test_response_audio_reaches_playback_path():
    player = AudioPlayer()
    player._stream = MagicMock()
    player._running = True

    dummy_pcm = b"\x00\x00" * 480  # 480 samples of 16-bit PCM

    player.play_chunk(dummy_pcm)
    assert player._queue.qsize() >= 1 or player.is_playing

    # Flush
    player.flush()
