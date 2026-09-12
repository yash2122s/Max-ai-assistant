"""
MAX AI — Action Verification & Controlled Retry Tests
Validates asynchronous retry execution and verification routines.
"""

import os
import sys
import unittest
import asyncio

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from core.verifier import action_verifier


class TestActionVerifier(unittest.TestCase):

    def test_01_execute_with_retry_success(self):
        """Verify successful action returns True immediately without retrying."""
        call_count = 0

        async def action():
            nonlocal call_count
            call_count += 1
            return "ok"

        async def verify():
            return True

        async def run():
            return await action_verifier.execute_with_retry(action, verify, max_attempts=3)

        success, res = asyncio.run(run())
        self.assertTrue(success)
        self.assertEqual(res, "ok")
        self.assertEqual(call_count, 1)

    def test_02_execute_with_retry_recovery(self):
        """Verify retry logic recovers when initial attempt fails."""
        call_count = 0

        async def flaky_action():
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return "initial_failure"
            return "recovered"

        async def verify():
            return call_count > 1

        async def run():
            return await action_verifier.execute_with_retry(flaky_action, verify, max_attempts=3, delay_seconds=0.05)

        success, res = asyncio.run(run())
        self.assertTrue(success)
        self.assertEqual(res, "recovered")
        self.assertEqual(call_count, 2)


if __name__ == "__main__":
    unittest.main()
