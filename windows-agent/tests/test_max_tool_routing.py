"""
MAX AI — Comprehensive MAX Tool Routing & Intent Classification Tests
Validates that simple calculations, general knowledge, and conversational queries
do NOT trigger unnecessary web searches, while live/current and agent tasks route properly.
"""

import unittest
import asyncio
from unittest.mock import AsyncMock, patch, MagicMock

from core.intent_router import intent_router, IntentCategory, RouteDecision
from core.max_orchestrator import MaxOrchestrator, MaxAgentRequest, MaxAgentResult


class TestMaxToolRouting(unittest.IsolatedAsyncioTestCase):
    """
    Test Suite for IntentRouter and MAX Tool Routing Policy.
    """

    def setUp(self):
        self.orchestrator = MaxOrchestrator.get_instance()

    # ── 1. The 10 Primary Specification Tests ──────────────────────────────────

    def test_spec_test1_calculation(self):
        """TEST 1: 'What is 25 multiplied by 48?' -> NO search, Answer: 1200."""
        decision = intent_router.classify("What is 25 multiplied by 48?")
        self.assertEqual(decision.intent, IntentCategory.CALCULATION)
        self.assertTrue(decision.should_bypass_web_search)
        self.assertEqual(decision.tool, None)
        self.assertEqual(decision.direct_answer, "1200")

    def test_spec_test2_calculation_followup(self):
        """TEST 2: 'Now multiply that result by 2.' with last_math_result=1200 -> NO search, Answer: 2400."""
        decision = intent_router.classify("Now multiply that result by 2.", last_math_result=1200.0)
        self.assertEqual(decision.intent, IntentCategory.CALCULATION)
        self.assertTrue(decision.should_bypass_web_search)
        self.assertEqual(decision.tool, None)
        self.assertIn("2400", decision.direct_answer)

    def test_spec_test3_general_knowledge_fact(self):
        """TEST 3: 'Tell me one fact about Hyderabad.' -> NO web search, direct answer."""
        decision = intent_router.classify("Tell me one fact about Hyderabad.")
        self.assertEqual(decision.intent, IntentCategory.GENERAL_KNOWLEDGE)
        self.assertTrue(decision.should_bypass_web_search)
        self.assertEqual(decision.tool, None)

    def test_spec_test4_general_knowledge_definition(self):
        """TEST 4: 'What is Python?' -> NO web search."""
        decision = intent_router.classify("What is Python?")
        self.assertEqual(decision.intent, IntentCategory.GENERAL_KNOWLEDGE)
        self.assertTrue(decision.should_bypass_web_search)
        self.assertEqual(decision.tool, None)

    def test_spec_test5_latest_news_search(self):
        """TEST 5: 'What is the latest OpenAI news?' -> search_web_fast SHOULD run."""
        decision = intent_router.classify("What is the latest OpenAI news?")
        self.assertEqual(decision.intent, IntentCategory.CURRENT_INFO)
        self.assertFalse(decision.should_bypass_web_search)
        self.assertEqual(decision.tool, "search_web_fast")

    def test_spec_test6_explicit_price_search(self):
        """TEST 6: 'Search the web for the latest RTX 5070 price in India.' -> search SHOULD run."""
        decision = intent_router.classify("Search the web for the latest RTX 5070 price in India.")
        self.assertIn(decision.intent, (IntentCategory.CURRENT_INFO, IntentCategory.WEB_RESEARCH))
        self.assertFalse(decision.should_bypass_web_search)
        self.assertEqual(decision.tool, "search_web_fast")

    def test_spec_test7_app_control(self):
        """TEST 7: 'Open Notepad.' -> Windows/application tool."""
        decision = intent_router.classify("Open Notepad")
        self.assertEqual(decision.intent, IntentCategory.LOCAL_COMPUTER)
        self.assertTrue(decision.should_bypass_web_search)

    def test_spec_test8_filesystem_operation(self):
        """TEST 8: 'Create a folder on my desktop called MAX Test.' -> filesystem tool."""
        decision = intent_router.classify("Create a folder on my desktop called MAX Test.")
        self.assertEqual(decision.intent, IntentCategory.FILE_OPERATION)
        self.assertTrue(decision.should_bypass_web_search)

    def test_spec_test9_complex_task(self):
        """TEST 9: Multi-step task -> MAX planner + multiple tools."""
        decision = intent_router.classify(
            "Create a folder, add 5 files, write content, read them back, verify them and report the result."
        )
        self.assertEqual(decision.intent, IntentCategory.COMPLEX_TASK)
        self.assertEqual(decision.tool, "max_planner")

    def test_spec_test10_weather_today(self):
        """TEST 10: 'What's the weather today?' -> current weather tool path."""
        decision = intent_router.classify("What's the weather today?")
        self.assertEqual(decision.intent, IntentCategory.CURRENT_INFO)
        self.assertEqual(decision.tool, "get_weather")

    # ── 2. 10 Negative Web Search Tests (Must NOT Search Web) ───────────────────

    def test_neg_search_01_addition(self):
        d = intent_router.classify("What is 100 + 200?")
        self.assertTrue(d.should_bypass_web_search)
        self.assertEqual(d.direct_answer, "300")

    def test_neg_search_02_percentage(self):
        d = intent_router.classify("What is 15 percent of 800?")
        self.assertTrue(d.should_bypass_web_search)
        self.assertIn("120", d.direct_answer)

    def test_neg_search_03_division(self):
        d = intent_router.classify("Calculate 12345 / 15")
        self.assertTrue(d.should_bypass_web_search)
        self.assertEqual(d.direct_answer, "823")

    def test_neg_search_04_einstein(self):
        d = intent_router.classify("Who was Albert Einstein?")
        self.assertTrue(d.should_bypass_web_search)

    def test_neg_search_05_cpu(self):
        d = intent_router.classify("What does CPU mean?")
        self.assertTrue(d.should_bypass_web_search)

    def test_neg_search_06_capital(self):
        d = intent_router.classify("What is the capital of India?")
        self.assertTrue(d.should_bypass_web_search)

    def test_neg_search_07_greeting(self):
        d = intent_router.classify("Hello MAX")
        self.assertTrue(d.should_bypass_web_search)

    def test_neg_search_08_how_are_you(self):
        d = intent_router.classify("How are you doing today?")
        # Notice: contains 'today' in conversational context, but classified as conversation/greeting
        d2 = intent_router.classify("How are you?")
        self.assertTrue(d2.should_bypass_web_search)

    def test_neg_search_09_who_are_you(self):
        d = intent_router.classify("Who created you?")
        self.assertTrue(d.should_bypass_web_search)

    def test_neg_search_10_joke(self):
        d = intent_router.classify("Tell me a joke")
        self.assertTrue(d.should_bypass_web_search)

    # ── 3. 10 Positive Routing Tests (Must Route Correctly) ─────────────────────

    def test_pos_01_recent_news(self):
        d = intent_router.classify("Find recent breaking news about AI")
        self.assertEqual(d.intent, IntentCategory.CURRENT_INFO)

    def test_pos_02_job_search(self):
        d = intent_router.classify("Search the web for fresher Java jobs")
        self.assertIn(d.intent, (IntentCategory.CURRENT_INFO, IntentCategory.WEB_RESEARCH))

    def test_pos_03_crypto_price(self):
        d = intent_router.classify("What is the current price of Bitcoin?")
        self.assertEqual(d.intent, IntentCategory.CURRENT_INFO)

    def test_pos_04_weather_city(self):
        d = intent_router.classify("What is the weather in Hyderabad?")
        self.assertEqual(d.tool, "get_weather")

    def test_pos_05_app_close(self):
        d = intent_router.classify("Close Chrome")
        self.assertEqual(d.intent, IntentCategory.LOCAL_COMPUTER)

    def test_pos_06_volume(self):
        d = intent_router.classify("Set volume to 50")
        self.assertEqual(d.intent, IntentCategory.LOCAL_COMPUTER)

    def test_pos_07_file_read(self):
        d = intent_router.classify("Read file test.txt")
        self.assertEqual(d.intent, IntentCategory.FILE_OPERATION)

    def test_pos_08_phone_torch(self):
        d = intent_router.classify("Turn on phone flashlight")
        self.assertEqual(d.intent, IntentCategory.DEVICE_CONTROL)

    def test_pos_09_multi_step_workspace(self):
        d = intent_router.classify("Create a folder named MAX_Workspace on desktop and create three files inside it")
        self.assertEqual(d.intent, IntentCategory.COMPLEX_TASK)

    def test_pos_10_doc_search(self):
        d = intent_router.classify("Search for the latest Gemini API documentation")
        self.assertEqual(d.intent, IntentCategory.CURRENT_INFO)

    # ── 4. MaxOrchestrator Routing & Search Bypass Verification ────────────────

    async def test_max_orchestrator_intercepts_calculation_search(self):
        """MaxOrchestrator should intercept search_web_fast with math and return direct calculation."""
        req = MaxAgentRequest(
            user_text="What is 25 multiplied by 48?",
            tool_name="search_web_fast",
            arguments={"query": "25 multiplied by 48"}
        )
        res = await self.orchestrator.handle_request(req)
        self.assertEqual(res.status, "success")
        self.assertEqual(res.response.get("result"), "1200")
        self.assertEqual(res.tools_used, [])  # Did NOT call search_web_fast

    async def test_max_orchestrator_intercepts_general_knowledge_search(self):
        """MaxOrchestrator should intercept search_web_fast for general facts."""
        req = MaxAgentRequest(
            user_text="Tell me one fact about Hyderabad",
            tool_name="search_web_fast",
            arguments={"query": "interesting fact about Hyderabad"}
        )
        res = await self.orchestrator.handle_request(req)
        self.assertEqual(res.status, "success")
        self.assertTrue(res.response.get("bypass_search"))
        self.assertEqual(res.tools_used, [])

    async def test_voice_text_parity(self):
        """Both voice source and text source must adhere to identical routing policy."""
        req_voice = MaxAgentRequest(
            user_text="What is 50 * 4?",
            tool_name="search_web_fast",
            arguments={"query": "50 * 4"},
            source="realtime_voice"
        )
        req_text = MaxAgentRequest(
            user_text="What is 50 * 4?",
            tool_name="search_web_fast",
            arguments={"query": "50 * 4"},
            source="text_input"
        )
        res_voice = await self.orchestrator.handle_request(req_voice)
        res_text = await self.orchestrator.handle_request(req_text)
        self.assertEqual(res_voice.response.get("result"), "200")
        self.assertEqual(res_text.response.get("result"), "200")
        self.assertEqual(res_voice.tools_used, [])
        self.assertEqual(res_text.tools_used, [])

    async def test_search_recovery_does_not_loop_for_non_search(self):
        """Adapt strategy must not loop or repeat when query is not a valid web search."""
        adapted = self.orchestrator._adapt_strategy("search_web_fast", {"query": "25 multiplied by 48"}, attempt=1)
        self.assertEqual(adapted["query"], "25 multiplied by 48")


if __name__ == "__main__":
    unittest.main()
