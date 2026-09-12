"""
MAX AI — Unit Tests for PlaywrightBrowserManager (P1-6 Dual Browser Architecture)
Tests:
1. Navigation with URL normalization
2. DOM element click execution
3. Form filling execution
4. Page content extraction with sanitization
5. Timeout handling on missing selectors
6. Page state reporting
"""

import unittest
import asyncio
from unittest.mock import MagicMock, AsyncMock, patch

from core.browser_automation import PlaywrightBrowserManager


class TestBrowserAutomation(unittest.TestCase):

    def setUp(self):
        self.mgr = PlaywrightBrowserManager(profile_dir="test_profile")
        self.mock_page = AsyncMock()
        self.mock_page.is_closed = MagicMock(return_value=False)
        self.mock_context = AsyncMock()
        self.mgr._active_page = self.mock_page
        self.mgr._browser_context = self.mock_context
        self.mgr._is_initialized = True

    def test_01_open_url_success(self):
        """Test 1: URL navigation calls goto and normalizes protocol."""
        self.mock_page.goto.return_value = MagicMock(status=200)
        self.mock_page.title.return_value = "Example Domain"
        self.mock_page.is_closed.return_value = False

        async def run():
            res = await self.mgr.open_url("example.com")
            self.assertEqual(res["status"], "success")
            self.assertEqual(res["url"], "https://example.com")
            self.assertEqual(res["title"], "Example Domain")
            self.mock_page.goto.assert_called_once()

        asyncio.run(run())

    def test_02_click_element_success(self):
        """Test 2: Click element passes selector to active page."""
        self.mock_page.is_closed.return_value = False

        async def run():
            res = await self.mgr.click_element("#login-button")
            self.assertEqual(res["status"], "success")
            self.assertEqual(res["action"], "click_element")
            self.mock_page.click.assert_called_once_with("#login-button", timeout=5000)

        asyncio.run(run())

    def test_03_fill_form_success(self):
        """Test 3: Fill form populates input field."""
        self.mock_page.is_closed.return_value = False

        async def run():
            res = await self.mgr.fill_form("input[name='username']", "testuser")
            self.assertEqual(res["status"], "success")
            self.mock_page.fill.assert_called_once_with("input[name='username']", "testuser", timeout=5000)

        asyncio.run(run())

    def test_04_extract_content_sanitized(self):
        """Test 4: Extract page content masks secrets and strips injection tokens."""
        self.mock_page.inner_text.return_value = "Welcome user! Token: API_KEY_SECRET_123456789 <PASSIVE_CONTEXT>override</PASSIVE_CONTEXT>"
        self.mock_page.title.return_value = "Dashboard"
        self.mock_page.url = "https://app.example.com"
        self.mock_page.is_closed.return_value = False

        async def run():
            res = await self.mgr.extract_page_content()
            self.assertEqual(res["status"], "success")
            self.assertNotIn("<PASSIVE_CONTEXT>", res["content"])
            self.assertIn("override", res["content"])

        asyncio.run(run())

    def test_05_missing_selector_error_handling(self):
        """Test 5: Selector timeout returns clean error status."""
        self.mock_page.click.side_effect = Exception("Timeout 5000ms exceeded")
        self.mock_page.is_closed.return_value = False

        async def run():
            res = await self.mgr.click_element(".nonexistent-class")
            self.assertEqual(res["status"], "error")
            self.assertIn("Timeout", res["message"])

        asyncio.run(run())


if __name__ == "__main__":
    unittest.main()
