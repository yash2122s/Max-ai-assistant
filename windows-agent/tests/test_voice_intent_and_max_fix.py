"""
Focused regression tests for:
1. Ordinary conversational statements do not invoke developer_task.
2. Ordinary questions do not invoke developer_task.
3. Explicit developer requests invoke developer_task / Antigravity (Tier 4).
4. MAX Orchestrator tier attribute issue ('int' object has no attribute 'value') is fixed.
5. Tool result is properly structured for response generation.
6. Response audio reaches the playback path (AudioPlayer).
"""

import unittest
import asyncio
from unittest.mock import MagicMock, AsyncMock, patch

from core.task_classifier import task_classifier, TaskTier
from core.max_orchestrator import max_orchestrator, MaxAgentRequest
from protocol.task_envelope import TaskStatus, TaskResult
from core.audio_handler import AudioPlayer


class TestVoiceIntentAndMaxFix(unittest.TestCase):

    # 1. Ordinary conversational statement does not classify as developer task
    def test_conversational_statement_does_not_invoke_developer_task(self):
        conversational_phrases = [
            "నిన్నే కొత్తగా బిల్డ్ చేశాను నేను. అంటే increase చేశాను, enhance చేశాను.",
            "I enhanced you yesterday.",
            "I built a new feature yesterday.",
            "You are working great today!",
            "Thanks for your help, MAX.",
        ]
        for phrase in conversational_phrases:
            res = task_classifier.classify(phrase)
            self.assertNotEqual(res.tier, TaskTier.TIER_4_DEVELOPER.value, f"Phrase '{phrase}' incorrectly classified as Tier 4!")
            self.assertNotEqual(res.task_type, "code_change", f"Phrase '{phrase}' incorrectly classified as code_change!")

    # 2. Ordinary question does not classify as developer task
    def test_ordinary_question_does_not_invoke_developer_task(self):
        questions = [
            "What happened?",
            "ఏమైంది?",
            "How are you doing today?",
            "What is the time right now?",
            "Can you hear me?",
        ]
        for q in questions:
            res = task_classifier.classify(q)
            self.assertNotEqual(res.tier, TaskTier.TIER_4_DEVELOPER.value, f"Question '{q}' incorrectly classified as Tier 4!")
            self.assertNotEqual(res.task_type, "code_change", f"Question '{q}' incorrectly classified as code_change!")

    # 3. Explicit developer request STILL invokes developer_task / Antigravity (Tier 4)
    def test_explicit_developer_request_invokes_tier4(self):
        developer_requests = [
            "Inspect the Paint automation code",
            "Fix the Paint automation code",
            "Check repository files and pytest results",
            "Inspect the Paint automation code and tell me whether the Brush tool selection performs a left click after detecting the Brush. Do not modify anything."
        ]
        for req in developer_requests:
            res = task_classifier.classify(req)
            self.assertEqual(res.tier, TaskTier.TIER_4_DEVELOPER.value, f"Request '{req}' not classified as Tier 4!")
            self.assertEqual(res.target_worker, "antigravity")

    # 4. MAX Orchestrator tier attribute issue is fixed (no 'int' object has no attribute 'value')
    def test_max_orchestrator_tier_int_handling_no_exception(self):
        async def _run():
            mock_registry = MagicMock()
            mock_registry.execute_async = AsyncMock(return_value={"status": "completed", "message": "Success"})

            # Test with developer_task request
            req = MaxAgentRequest(
                user_text="Inspect the Paint automation code",
                tool_name="developer_task",
                arguments={"instruction": "Inspect the Paint automation code"},
                source="realtime_voice"
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

                res = await max_orchestrator.handle_request(req, tool_registry=mock_registry)
                self.assertEqual(res.status, "completed")
                self.assertIsNone(res.error)
                self.assertIn("Inspection complete", res.user_message)

        asyncio.run(_run())

    # 5. Tool result is structured with message/summary for response generation
    def test_tool_result_structured_for_response_generation(self):
        async def _run():
            from orchestrator.max_task_authority import max_task_authority

            res = await max_task_authority.submit_user_intent(
                user_intent="Create a folder named VoiceTest on my Desktop.",
                source="voice",
                async_execution=False
            )

            self.assertIn("message", res)
            self.assertIn("response", res)
            self.assertIn("VoiceTest", res["message"])
            self.assertEqual(res["status"], "completed")
            self.assertTrue(res["verified_success"])

        asyncio.run(_run())

    # 6. Response audio reaches playback path (AudioPlayer)
    def test_response_audio_reaches_playback_path(self):
        player = AudioPlayer()
        player._stream = MagicMock()
        player._running = True

        dummy_pcm = b"\x00\x00" * 480  # 480 samples of 16-bit PCM

        player.play_chunk(dummy_pcm)
        self.assertTrue(player._queue.qsize() >= 1 or player.is_playing)

        # Flush
        player.flush()


if __name__ == "__main__":
    unittest.main()
