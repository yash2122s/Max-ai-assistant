"""
JARVIS Web Tools
Browser actions, web search, weather, news.
"""

import os
import webbrowser
import urllib.parse
import datetime
import requests


def open_website(url: str) -> dict:
    """
    Open a URL or configured website shortcut in the default web browser.
    
    Args:
        url: The URL or shortcut name (e.g., 'google', 'youtube', 'github', 'https://example.com').
    """
    try:
        url_clean = url.lower().strip()
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
                
        webbrowser.open(target_url)
        return {"status": "success", "message": f"Opened {url} ({target_url})"}
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


def search_youtube(query: str) -> dict:
    """
    Search YouTube in the default browser.
    
    Args:
        query: The search query for YouTube.
    """
    try:
        encoded = urllib.parse.quote_plus(query)
        url = f"https://www.youtube.com/results?search_query={encoded}"
        webbrowser.open(url)
        return {"status": "success", "message": f"Searching YouTube for: {query}"}
    except Exception as e:
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
]

TOOL_FUNCTIONS = {
    "open_website": open_website,
    "search_google": search_google,
    "search_youtube": search_youtube,
    "get_weather": get_weather,
    "get_news": get_news,
}
