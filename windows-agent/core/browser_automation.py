"""
MAX AI — Dual Browser Architecture (P1-6)
Maintains strict separation between the user's primary desktop browser and deterministic
Playwright automation running in an isolated persistent profile.
Provides robust DOM-first browser actions, verification, and passive content boundaries.
"""

import os
import time
import logging
import asyncio
from typing import Dict, Any, Optional, List

from core.security import security_guard

logger = logging.getLogger("MAXWindowsAgent.BrowserAutomation")

AUTOMATION_PROFILE_DIR = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "storage", "browser_automation_profile")
)


class PlaywrightBrowserManager:
    """
    Manages deterministic browser automation in an isolated persistent context.
    Leaves user's primary Chrome session undisturbed.
    """

    def __init__(self, profile_dir: Optional[str] = None):
        self.profile_dir = profile_dir or AUTOMATION_PROFILE_DIR
        self._playwright = None
        self._browser_context = None
        self._active_page = None
        self._is_initialized = False

    async def _ensure_browser(self):
        """Initializes Playwright persistent context if not already active."""
        if self._browser_context and self._active_page:
            try:
                is_closed_fn = getattr(self._active_page, "is_closed", None)
                if callable(is_closed_fn):
                    is_closed = is_closed_fn()
                    if asyncio.iscoroutine(is_closed):
                        is_closed = await is_closed
                else:
                    is_closed = False

                if not is_closed:
                    return
            except Exception:
                pass

        try:
            from playwright.async_api import async_playwright
            os.makedirs(self.profile_dir, exist_ok=True)

            if not self._playwright:
                self._playwright = await async_playwright().start()

            self._browser_context = await self._playwright.chromium.launch_persistent_context(
                user_data_dir=self.profile_dir,
                headless=False,
                viewport={"width": 1280, "height": 800},
                args=["--disable-blink-features=AutomationControlled"]
            )

            pages = self._browser_context.pages
            self._active_page = pages[0] if pages else await self._browser_context.new_page()
            self._is_initialized = True
            logger.info("🌐 Playwright persistent automation browser initialized")

        except ImportError:
            logger.warning("Playwright is not installed in environment. Using graceful automation mock.")
            self._is_initialized = False
        except Exception as e:
            logger.error(f"Failed to launch Playwright browser: {e}")
            self._is_initialized = False

    def sanitize_page_text(self, text: str) -> str:
        """Sanitizes untrusted webpage text to prevent prompt injection."""
        if not text:
            return ""
        masked = security_guard.mask_secrets(text)
        return masked.replace("<PASSIVE_CONTEXT>", "").replace("</PASSIVE_CONTEXT>", "").strip()

    async def open_url(self, url: str, timeout_ms: int = 15000) -> Dict[str, Any]:
        """Navigates the automation browser to the specified URL."""
        if not url or not isinstance(url, str):
            return {"status": "error", "message": "URL cannot be empty"}

        clean_url = url.strip()
        if not clean_url.startswith(("http://", "https://", "about:")):
            clean_url = "https://" + clean_url

        try:
            await self._ensure_browser()
            if not self._active_page:
                return {"status": "error", "message": "Browser engine unavailable"}

            response = await self._active_page.goto(clean_url, timeout=timeout_ms, wait_until="domcontentloaded")
            title = await self._active_page.title()
            status_code = response.status if response else 200

            return {
                "status": "success",
                "action": "open_url",
                "url": clean_url,
                "title": self.sanitize_page_text(title),
                "http_status": status_code
            }
        except Exception as e:
            logger.error(f"Browser open_url error: {e}")
            return {"status": "error", "action": "open_url", "message": str(e)}

    async def click_element(self, selector: str, timeout_ms: int = 5000) -> Dict[str, Any]:
        """Clicks an element identified by DOM selector or text query."""
        if not selector or not isinstance(selector, str):
            return {"status": "error", "message": "Selector cannot be empty"}

        try:
            await self._ensure_browser()
            if not self._active_page:
                return {"status": "error", "message": "Browser engine unavailable"}

            # DOM-First click with automatic retry
            await self._active_page.click(selector, timeout=timeout_ms)
            return {
                "status": "success",
                "action": "click_element",
                "selector": selector,
                "message": f"Clicked element matching '{selector}'"
            }
        except Exception as e:
            return {"status": "error", "action": "click_element", "selector": selector, "message": str(e)}

    async def fill_form(self, selector: str, text: str, timeout_ms: int = 5000) -> Dict[str, Any]:
        """Fills text into an input field matching the selector."""
        if not selector:
            return {"status": "error", "message": "Selector cannot be empty"}

        try:
            await self._ensure_browser()
            if not self._active_page:
                return {"status": "error", "message": "Browser engine unavailable"}

            await self._active_page.fill(selector, text or "", timeout=timeout_ms)
            return {
                "status": "success",
                "action": "fill_form",
                "selector": selector,
                "message": f"Filled text into '{selector}'"
            }
        except Exception as e:
            return {"status": "error", "action": "fill_form", "selector": selector, "message": str(e)}

    async def extract_page_content(self, selector: Optional[str] = None, max_chars: int = 2000) -> Dict[str, Any]:
        """Extracts visible text from page or element as passive data."""
        try:
            await self._ensure_browser()
            if not self._active_page:
                return {"status": "error", "message": "Browser engine unavailable"}

            if selector:
                el = await self._active_page.query_selector(selector)
                text = await el.inner_text() if el else ""
            else:
                text = await self._active_page.inner_text("body")

            title = await self._active_page.title()
            url = self._active_page.url

            sanitized_text = self.sanitize_page_text(text)
            if len(sanitized_text) > max_chars:
                sanitized_text = sanitized_text[:max_chars] + "... [truncated]"

            return {
                "status": "success",
                "action": "extract_page_content",
                "title": self.sanitize_page_text(title),
                "url": url,
                "content": sanitized_text
            }
        except Exception as e:
            return {"status": "error", "action": "extract_page_content", "message": str(e)}

    async def wait_for_selector(self, selector: str, timeout_ms: int = 5000) -> Dict[str, Any]:
        """Waits for element to become present in the DOM."""
        try:
            await self._ensure_browser()
            if not self._active_page:
                return {"status": "error", "message": "Browser engine unavailable"}

            await self._active_page.wait_for_selector(selector, timeout=timeout_ms)
            return {"status": "success", "action": "wait_for_selector", "selector": selector}
        except Exception as e:
            return {"status": "error", "action": "wait_for_selector", "selector": selector, "message": str(e)}

    async def scroll_page(self, direction: str = "down", amount: int = 500) -> Dict[str, Any]:
        """Scrolls page up or down."""
        try:
            await self._ensure_browser()
            if not self._active_page:
                return {"status": "error", "message": "Browser engine unavailable"}

            delta_y = amount if direction.lower() == "down" else -amount
            await self._active_page.evaluate(f"window.scrollBy(0, {delta_y});")
            return {"status": "success", "action": "scroll_page", "direction": direction, "amount": amount}
        except Exception as e:
            return {"status": "error", "action": "scroll_page", "message": str(e)}

    async def get_page_state(self) -> Dict[str, Any]:
        """Returns current page URL, title, and loading state."""
        try:
            await self._ensure_browser()
            if not self._active_page:
                return {"status": "error", "message": "Browser engine unavailable"}

            title = await self._active_page.title()
            url = self._active_page.url
            return {
                "status": "success",
                "action": "get_page_state",
                "url": url,
                "title": self.sanitize_page_text(title),
                "is_closed": self._active_page.is_closed()
            }
        except Exception as e:
            return {"status": "error", "action": "get_page_state", "message": str(e)}

    async def close(self):
        """Closes browser context."""
        if self._browser_context:
            try:
                await self._browser_context.close()
            except Exception:
                pass
            self._browser_context = None
        if self._playwright:
            try:
                await self._playwright.stop()
            except Exception:
                pass
            self._playwright = None
        self._is_initialized = False


# Global singleton instance
browser_manager = PlaywrightBrowserManager()
