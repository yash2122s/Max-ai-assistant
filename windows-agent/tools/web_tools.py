import os
import time
import logging
import webbrowser
import urllib.parse
import datetime
import requests
import pyautogui
import pyperclip

from tools.app_control import _find_windows_for_app, activate_app_window

logger = logging.getLogger("MAXWindowsAgent.WebTools")


def find_existing_youtube_context() -> dict:
    """
    Finds existing YouTube context across browser layers with strict priority:
    1. Active Playwright/CDP browser context (if active page is youtube.com)
    2. Open Desktop Browser Window containing 'YouTube' in active window title
    3. Deep UIA tab inspection for background tabs containing 'YouTube' across open browsers
    
    Returns structured context dict if found, else None.
    """
    try:
        # Tier 1: Check active Playwright/CDP browser context
        try:
            from core.browser_automation import browser_manager
            if browser_manager._active_page and hasattr(browser_manager._active_page, "url"):
                page_url = browser_manager._active_page.url or ""
                if "youtube.com" in page_url:
                    return {
                        "type": "playwright_cdp",
                        "url": page_url,
                        "is_active_tab": True,
                        "description": "Playwright/CDP active page"
                    }
        except Exception:
            pass

        # Tier 2: Check open desktop browser windows with YouTube in active window title
        browser_names = ["chrome", "msedge", "brave", "firefox", "opera", "vivaldi"]
        for b_name in browser_names:
            wins = _find_windows_for_app(b_name)
            for w in wins:
                title = w.get("title", "")
                if "youtube" in title.lower():
                    return {
                        "type": "browser_window",
                        "hwnd": w["hwnd"],
                        "pid": w.get("pid", 0),
                        "title": title,
                        "process_name": w.get("process_name", b_name),
                        "is_active_tab": True,
                        "description": f"Active window '{title}'"
                    }

        # Tier 3: Deep UIA Tab Inspection across open browser windows
        try:
            from core.ui_automation import uia_engine
            if uia_engine.is_available:
                for b_name in browser_names:
                    wins = _find_windows_for_app(b_name)
                    for w in wins:
                        hwnd = w["hwnd"]
                        if not hwnd:
                            continue
                        root = uia_engine._uia.ElementFromHandle(hwnd)
                        if root:
                            tab_matches = uia_engine.find_elements_by_name(root, "YouTube", partial=True, max_results=3)
                            for match in tab_matches:
                                ctrl_type = match.get("control_type", "").lower()
                                if "tab" in ctrl_type or "item" in ctrl_type or "button" in ctrl_type or "youtube" in (match.get("name") or "").lower():
                                    return {
                                        "type": "browser_tab_uia",
                                        "hwnd": hwnd,
                                        "pid": w.get("pid", 0),
                                        "title": match.get("name", "YouTube"),
                                        "process_name": w.get("process_name", b_name),
                                        "is_active_tab": False,
                                        "element": match,
                                        "description": f"Browser tab '{match.get('name')}' in {b_name}"
                                    }
        except Exception as e:
            logger.debug(f"UIA browser tab inspection error: {e}")

    except Exception as e:
        logger.error(f"Error checking existing YouTube context: {e}")

    return None


def open_website(url: str) -> dict:
    """
    Open a URL or configured website shortcut in the default web browser.
    Reuses existing browser context if the target (e.g. YouTube) is already open.
    
    Args:
        url: The URL or shortcut name (e.g., 'google', 'youtube', 'github', 'https://example.com').
    """
    try:
        url_clean = str(url or "").lower().strip()
        target_url = None
        
        # Check config.json urls section
        config_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "config.json")
        if os.path.exists(config_path):
            try:
                import json
                with open(config_path, "r", encoding="utf-8") as f:
                    cfg = json.load(f)
                    target_url = cfg.get("urls", {}).get(url_clean)
            except Exception:
                pass
        
        if not target_url:
            target_url = url
            if not target_url.startswith(("http://", "https://")):
                target_url = "https://" + target_url

        # Check for YouTube context reuse
        if "youtube.com" in target_url or url_clean in ("youtube", "yt"):
            yt_ctx = find_existing_youtube_context()
            if yt_ctx and yt_ctx.get("hwnd"):
                act_res = activate_app_window(yt_ctx["hwnd"])
                if act_res.get("verified"):
                    if yt_ctx.get("type") == "browser_tab_uia" and not yt_ctx.get("is_active_tab"):
                        try:
                            from core.ui_automation import uia_engine
                            uia_engine.click_element_by_selector(target=yt_ctx["title"])
                        except Exception:
                            pass
                    return {
                        "status": "success",
                        "message": f"Reused existing YouTube window ('{yt_ctx.get('title', 'YouTube')}').",
                        "reused_context": True,
                        "verified": True,
                        "hwnd": yt_ctx.get("hwnd")
                    }

        webbrowser.open(target_url)
        return {"status": "success", "message": f"Opened {url} ({target_url})", "reused_context": False, "verified": True}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def search_google(query: str) -> dict:
    """
    Search Google in the default browser.
    
    Args:
        query: The search query.
    """
    try:
        encoded = urllib.parse.quote_plus(query)
        url = f"https://www.google.com/search?q={encoded}"
        webbrowser.open(url)
        return {"status": "success", "message": f"Searching Google for: {query}"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


async def search_web_fast(query: str) -> dict:
    """
    Rapidly search the web using parallel provider racing. Returns direct summarized answers
    without requiring manual browser interaction.
    
    Args:
        query: The factual search query or question to research.
    """
    try:
        from core.search_racer import search_racer
        return await search_racer.race(query)
    except Exception as e:
        return {"status": "error", "message": str(e)}


def search_youtube(query: str) -> dict:
    """
    Search YouTube in the browser. Reuses existing YouTube context if already open.
    
    Args:
        query: The search query for YouTube.
    """
    try:
        encoded = urllib.parse.quote_plus(query)
        target_url = f"https://www.youtube.com/results?search_query={encoded}"
        
        # 1. Inspect existing browser instances for YouTube context
        yt_ctx = find_existing_youtube_context()
        
        if yt_ctx and yt_ctx.get("hwnd"):
            logger.info(f"🎯 Reusing existing YouTube context: {yt_ctx.get('description')}")
            act_res = activate_app_window(yt_ctx["hwnd"], timeout_seconds=2.5)
            
            if act_res.get("verified"):
                # If it's a background tab, click tab to bring it to foreground
                if yt_ctx.get("type") == "browser_tab_uia" and not yt_ctx.get("is_active_tab"):
                    try:
                        from core.ui_automation import uia_engine
                        uia_engine.click_element_by_selector(target=yt_ctx.get("title", "YouTube"))
                        time.sleep(0.15)
                    except Exception:
                        pass
                
                # Navigate existing browser tab directly via address bar (Ctrl+L)
                pyautogui.FAILSAFE = False
                pyautogui.hotkey("ctrl", "l")
                time.sleep(0.08)
                pyperclip.copy(target_url)
                pyautogui.hotkey("ctrl", "v")
                time.sleep(0.05)
                pyautogui.press("enter")
                
                return {
                    "status": "success",
                    "message": f"Searching YouTube for: {query} (reusing existing window)",
                    "reused_context": True,
                    "verified": True,
                    "window_title": act_res.get("window_title", ""),
                    "hwnd": act_res.get("hwnd")
                }

        # 2. If no existing YouTube context, check if any browser window is already open
        browser_names = ["chrome", "msedge", "brave", "firefox"]
        for b_name in browser_names:
            wins = _find_windows_for_app(b_name)
            if wins:
                act_res = activate_app_window(wins[0]["hwnd"], timeout_seconds=2.0)
                if act_res.get("verified"):
                    pyautogui.FAILSAFE = False
                    pyautogui.hotkey("ctrl", "t")
                    time.sleep(0.1)
                    pyperclip.copy(target_url)
                    pyautogui.hotkey("ctrl", "v")
                    time.sleep(0.05)
                    pyautogui.press("enter")
                    return {
                        "status": "success",
                        "message": f"Searching YouTube for: {query}",
                        "reused_context": False,
                        "verified": True,
                        "window_title": act_res.get("window_title", "")
                    }

        # 3. Fallback: Launch default browser
        webbrowser.open(target_url)
        return {"status": "success", "message": f"Searching YouTube for: {query}", "reused_context": False, "verified": True}
    except Exception as e:
        logger.error(f"Error in search_youtube: {e}")
        return {"status": "error", "message": str(e)}


def get_weather(city: str) -> dict:
    """
    Get current weather for a city using wttr.in (no API key needed).
    
    Args:
        city: Name of the city (e.g., 'London', 'New York', 'Hyderabad').
    """
    try:
        encoded_city = urllib.parse.quote(city)
        response = requests.get(
            f"https://wttr.in/{encoded_city}?format=j1",
            timeout=10,
            headers={"User-Agent": "JARVIS-Agent"},
        )
        response.raise_for_status()
        data = response.json()
        
        current = data.get("current_condition", [{}])[0]
        
        return {
            "status": "success",
            "city": city,
            "temperature_c": current.get("temp_C", "N/A"),
            "temperature_f": current.get("temp_F", "N/A"),
            "feels_like_c": current.get("FeelsLikeC", "N/A"),
            "condition": current.get("weatherDesc", [{"value": "N/A"}])[0]["value"],
            "humidity": current.get("humidity", "N/A") + "%",
            "wind_speed_kmh": current.get("windspeedKmph", "N/A"),
            "wind_direction": current.get("winddir16Point", "N/A"),
        }
    except requests.Timeout:
        return {"status": "error", "message": "Weather service timed out"}
    except requests.RequestException as e:
        return {"status": "error", "message": f"Failed to fetch weather: {str(e)}"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def get_news(category: str = "general") -> dict:
    """
    Get top headlines. Opens Google News in the browser.
    
    Args:
        category: News category (general, technology, science, sports, health, business, entertainment).
    """
    try:
        # Use Google News (no API key required)
        category_urls = {
            "general": "https://news.google.com/topstories",
            "technology": "https://news.google.com/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRGRqTVhZU0FtVnVHZ0pWVXlnQVAB",
            "science": "https://news.google.com/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRGRqTVhZU0FtVnVHZ0pWVXlnQVAB",
            "sports": "https://news.google.com/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRFp1ZEdvU0FtVnVHZ0pWVXlnQVAB",
            "health": "https://news.google.com/topics/CAAqIQgKIhtDQkFTRGdvSUwyMHZNR3QwTlRFU0FtVnVLQUFQAQ",
            "business": "https://news.google.com/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRGx6TVdZU0FtVnVHZ0pWVXlnQVAB",
            "entertainment": "https://news.google.com/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNREpxYW5RU0FtVnVHZ0pWVXlnQVAB",
        }
        
        url = category_urls.get(category.lower(), category_urls["general"])
        webbrowser.open(url)
        return {"status": "success", "message": f"Opened {category} news in browser"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ─── Tool Declarations ──────────────────────────────────────────────────────

TOOL_DECLARATIONS = [
    {
        "name": "open_website",
        "description": "Open a URL in the default web browser.",
        "parameters": {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "The URL to open (e.g., 'google.com', 'https://github.com')",
                }
            },
            "required": ["url"],
        },
    },
    {
        "name": "search_google",
        "description": "Search Google for a query. Opens results in the default browser.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query",
                }
            },
            "required": ["query"],
        },
    },
    {
        "name": "search_youtube",
        "description": "Search YouTube for videos. Opens results in the default browser.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The YouTube search query",
                }
            },
            "required": ["query"],
        },
    },
    {
        "name": "get_weather",
        "description": "Get the current weather for a city including temperature, humidity, wind, and conditions.",
        "parameters": {
            "type": "object",
            "properties": {
                "city": {
                    "type": "string",
                    "description": "Name of the city (e.g., 'London', 'Hyderabad', 'New York')",
                }
            },
            "required": ["city"],
        },
    },
    {
        "name": "get_news",
        "description": "Open Google News in the browser, optionally filtered by category.",
        "parameters": {
            "type": "object",
            "properties": {
                "category": {
                    "type": "string",
                    "enum": ["general", "technology", "science", "sports", "health", "business", "entertainment"],
                    "description": "News category to browse",
                }
            },
        },
    },
    {
        "name": "search_web_fast",
        "description": "Search the live web for real-time, current, breaking news, or live external information. Use this ONLY when current/latest web information is required (e.g. today's news, current prices, live scores, recent documentation). Do NOT use this for arithmetic, math calculations, basic reasoning, stable general knowledge, definition of terms, conversation, or facts already available to the model.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The specific live web search query for current or external information",
                }
            },
            "required": ["query"],
        },
    },
]

TOOL_FUNCTIONS = {
    "open_website": open_website,
    "search_google": search_google,
    "search_web_fast": search_web_fast,
    "search_youtube": search_youtube,
    "get_weather": get_weather,
    "get_news": get_news,
}
