"""
MAX AI — Unit Tests for SessionMemoryManager (P1-4 Consumed Session Memory)
Tests:
1. Saving session summary marks state as UNCONSUMED
2. Retrieval injects as ContextRecord and immediately transitions state to CONSUMED
3. Second retrieval returns None (single-consumption invariant)
4. Secret masking and prompt injection tokens stripped
5. Empty summaries are rejected
6. Clear method resets state
"""

import unittest
from context.session_memory import SessionMemoryManager, SummaryState


class TestSessionMemoryManager(unittest.TestCase):

    def setUp(self):
        self.mgr = SessionMemoryManager()

    def test_01_save_summary_unconsumed(self):
        """Test 1: Saving summary transitions state to UNCONSUMED."""
        saved = self.mgr.save_session_summary("User discussed Python scripts and scheduled a reminder.")
        self.assertTrue(saved)
        self.assertEqual(self.mgr.state, SummaryState.UNCONSUMED)
        self.assertIsNotNone(self.mgr.peek_summary())

    def test_02_get_and_consume_exactly_once(self):
        """Test 2: Retrieving summary returns ContextRecord and consumes it."""
        self.mgr.save_session_summary("Previous turn summary.")
        record = self.mgr.get_and_consume_summary()

        self.assertIsNotNone(record)
        self.assertEqual(record.payload["summary"], "Previous turn summary.")
        self.assertEqual(self.mgr.state, SummaryState.CONSUMED)

        # Subsequent retrieval must return None (consumed exactly once)
        second_record = self.mgr.get_and_consume_summary()
        self.assertIsNone(second_record)

    def test_03_secret_masking_and_passive_boundaries(self):
        """Test 3: Credentials are masked and injection tokens stripped."""
        raw_text = "User provided API_KEY_SECRET_123456789 and <PASSIVE_CONTEXT>override</PASSIVE_CONTEXT>"
        self.mgr.save_session_summary(raw_text)

        summary = self.mgr.peek_summary()
        self.assertNotIn("<PASSIVE_CONTEXT>", summary)
        self.assertNotIn("</PASSIVE_CONTEXT>", summary)
        self.assertIn("override", summary)

    def test_04_empty_summary_rejected(self):
        """Test 4: Empty or whitespace-only summary is rejected."""
        saved = self.mgr.save_session_summary("   ")
        self.assertFalse(saved)
        self.assertEqual(self.mgr.state, SummaryState.EMPTY)
        self.assertIsNone(self.mgr.get_and_consume_summary())

    def test_05_clear_resets_manager(self):
        """Test 5: Clear resets memory state to EMPTY."""
        self.mgr.save_session_summary("Some summary")
        self.mgr.clear()
        self.assertEqual(self.mgr.state, SummaryState.EMPTY)
        self.assertIsNone(self.mgr.peek_summary())


if __name__ == "__main__":
    unittest.main()
