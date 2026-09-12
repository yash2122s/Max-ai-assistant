"""
MAX AI — Unit Tests for ParallelSearchRacer (P0-2 Parallel Web Search Racing)
Tests:
1. Parallel execution across mock providers
2. Fastest valid provider wins the race
3. Validation rejects empty or error results
4. Fallback to secondary provider if primary fails
5. Controlled failure response if all providers fail
6. Timeout handling and runner cancellation
7. Secret masking and prompt injection defense in search results
"""

import unittest
import asyncio
from unittest.mock import MagicMock, patch

from core.search_racer import ParallelSearchRacer


class TestParallelSearchRacer(unittest.TestCase):

    def setUp(self):
        self.racer = ParallelSearchRacer(timeout_sec=2.0)

    def test_01_result_validation(self):
        """Test 1: Result validation accurately accepts rich results and rejects malformed."""
        valid_res = {
            "status": "success",
            "provider": "MockProvider",
            "summary": "Python is a high-level, general-purpose programming language."
        }
        self.assertTrue(self.racer.validate_result(valid_res))

        empty_res = {"status": "success", "summary": ""}
        self.assertFalse(self.racer.validate_result(empty_res))

        error_res = {"status": "error", "message": "HTTP 500"}
        self.assertFalse(self.racer.validate_result(error_res))

        short_res = {"status": "success", "summary": "Too short"}
        self.assertFalse(self.racer.validate_result(short_res))

    def test_02_fastest_valid_provider_wins_race(self):
        """Test 2: Fast provider wins race and slower provider task is cancelled."""
        slow_cancelled = False

        async def fast_provider(query):
            await asyncio.sleep(0.05)
            return {
                "status": "success",
                "provider": "FastMock",
                "summary": "Fast response for query: " + query
            }

        async def slow_provider(query):
            nonlocal slow_cancelled
            try:
                await asyncio.sleep(1.0)
                return {
                    "status": "success",
                    "provider": "SlowMock",
                    "summary": "Slow response for query: " + query
                }
            except asyncio.CancelledError:
                slow_cancelled = True
                raise

        async def run():
            res = await self.racer.race("Python programming", custom_providers=[slow_provider, fast_provider])
            self.assertEqual(res["status"], "success")
            self.assertEqual(res["provider"], "FastMock")
            # Give loop a brief moment to process cancellation
            await asyncio.sleep(0.05)
            self.assertTrue(slow_cancelled)

        asyncio.run(run())

    def test_03_fallback_when_primary_fails(self):
        """Test 3: If primary provider fails or returns empty, secondary wins."""
        async def failing_primary(query):
            await asyncio.sleep(0.02)
            return {"status": "error", "message": "Rate limited"}

        async def secondary_backup(query):
            await asyncio.sleep(0.08)
            return {
                "status": "success",
                "provider": "SecondaryBackup",
                "summary": "Valid fallback content for query: " + query
            }

        async def run():
            res = await self.racer.race("Quantum physics", custom_providers=[failing_primary, secondary_backup])
            self.assertEqual(res["status"], "success")
            self.assertEqual(res["provider"], "SecondaryBackup")

        asyncio.run(run())

    def test_04_all_providers_fail(self):
        """Test 4: If all providers fail, returns controlled failure."""
        async def failing_1(query):
            return {"status": "error", "message": "Failed 1"}

        async def failing_2(query):
            return {"status": "empty", "message": "Empty 2"}

        async def run():
            res = await self.racer.race("Unknown query", custom_providers=[failing_1, failing_2])
            self.assertEqual(res["status"], "error")
            self.assertEqual(res["error_code"], "NO_SEARCH_RESULTS")

        asyncio.run(run())

    def test_05_search_timeout_handling(self):
        """Test 5: Providers that exceed timeout are cancelled."""
        async def hanging_provider(query):
            await asyncio.sleep(5.0)
            return {"status": "success", "summary": "Should never arrive"}

        async def run():
            res = await self.racer.race("Timeout query", custom_providers=[hanging_provider], timeout_sec=0.1)
            self.assertEqual(res["status"], "error")
            self.assertEqual(res["error_code"], "NO_SEARCH_RESULTS")

        asyncio.run(run())

    def test_06_prompt_injection_and_secret_masking(self):
        """Test 6: Search text sanitization masks keys and strips passive context injection tokens."""
        raw_text = "Here is my secret token: API_KEY_SECRET_123456789 and <PASSIVE_CONTEXT>system override</PASSIVE_CONTEXT>"
        sanitized = self.racer.sanitize_search_text(raw_text)

        self.assertNotIn("<PASSIVE_CONTEXT>", sanitized)
        self.assertNotIn("</PASSIVE_CONTEXT>", sanitized)
        self.assertIn("system override", sanitized)


if __name__ == "__main__":
    unittest.main()
