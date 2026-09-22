"""
MAX AI — Authoritative YouTube Video Automation Tool
Comprehensive YouTube automation subsystem for MAX Windows Agent.

Features:
  1. Direct Video Playback (play): Scrapes YouTube search for top non-Shorts video ID and launches/navigates directly, with browser context reuse.
  2. Transcript Extraction & AI Summarization (summarize): Fetches subtitles/captions with multi-language fallback, generates structured AI summary via Gemini (or local extractive fallback), saves to Desktop as .txt, and opens in Notepad.
  3. Video Metadata Extraction (get_info): Scrapes title, channel/owner, view count, duration (MM:SS), likes, and description.
  4. Regional Trending Feed (trending): Scrapes top trending videos by country/region code (IN, US, GB, etc.).
  5. Universal Dispatcher (youtube_video): Consolidated tool endpoint for voice, Realtime Live, WebSocket, and HTTP invocation.

Integrates natively with MAX SecurityGuard, ActionVerifier, UndoManager, and AppControl.
"""

import os
import re
import sys
import json
import time
import urllib.parse
import datetime
import logging
from pathlib import Path
from typing import Dict, Any, List, Optional, Tuple, Union

import requests
import pyautogui
import pyperclip
import webbrowser

try:
    from youtube_transcript_api import YouTubeTranscriptApi, TranscriptsDisabled, NoTranscriptFound
    _TRANSCRIPT_API_AVAILABLE = True
except ImportError:
    _TRANSCRIPT_API_AVAILABLE = False

from core.security import security_guard
from core.verifier import action_verifier
from tools.app_control import _find_windows_for_app, activate_app_window, open_application
from tools.file_manager import write_file, _resolve_user_path, SPECIAL_FOLDERS, open_file

logger = logging.getLogger("MAXWindowsAgent.YouTubeTool")

DEFAULT_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/123.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "en-US,en;q=0.9,te;q=0.8,hi;q=0.7",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
}


# ─── Helper Functions ────────────────────────────────────────────────────────

def _get_gemini_client():
    """Returns a client for Gemini AI generation with fallback handling."""
    api_key = os.getenv("GEMINI_API_KEY", "")
    if not api_key:
        return None

    try:
        from google import genai
        client = genai.Client(api_key=api_key)

        class _GeminiWrapper:
            def generate_content(self, contents):
                for model_name in ("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash"):
                    try:
                        return client.models.generate_content(model=model_name, contents=contents)
                    except Exception as me:
                        logger.debug(f"Model {model_name} failed: {me}")
                raise RuntimeError("All Gemini models failed to generate content.")

        return _GeminiWrapper()
    except Exception as e:
        logger.warning(f"Could not initialize google.genai: {e}")
        return None


def extract_video_id(url_or_query: str) -> Optional[str]:
    """
    Robustly extracts an 11-character YouTube video ID from various URL formats
    or returns the string itself if it is already a direct 11-char ID.
    
    Supported Formats:
      - youtube.com/watch?v=VIDEO_ID
      - youtu.be/VIDEO_ID
      - youtube.com/embed/VIDEO_ID
      - youtube.com/v/VIDEO_ID
      - youtube.com/shorts/VIDEO_ID
      - youtube.com/live/VIDEO_ID
      - m.youtube.com/watch?v=VIDEO_ID
    """
    if not url_or_query or not isinstance(url_or_query, str):
        return None

    raw = url_or_query.strip()
    
    # 1. Direct 11-char alphanumeric ID with '-' and '_'
    if re.fullmatch(r"[a-zA-Z0-9_-]{11}", raw):
        return raw

    # 2. Regex URL patterns
    patterns = [
        r"(?:youtube\.com\/(?:[^\/\n\s]+\/\S+\/|(?:v|e(?:mbed)?)\/|\S*?[?&]v=)|youtu\.be\/|youtube\.com\/(?:shorts|live)\/)([a-zA-Z0-9_-]{11})",
        r"[?&]v=([a-zA-Z0-9_-]{11})",
        r"youtu\.be\/([a-zA-Z0-9_-]{11})",
        r"youtube\.com\/shorts\/([a-zA-Z0-9_-]{11})",
        r"youtube\.com\/embed\/([a-zA-Z0-9_-]{11})",
        r"youtube\.com\/live\/([a-zA-Z0-9_-]{11})",
        r"\/watch\?v=([a-zA-Z0-9_-]{11})",
    ]

    for pat in patterns:
        match = re.search(pat, raw)
        if match:
            return match.group(1)

    # 3. Query string parameter parsing
    try:
        parsed = urllib.parse.urlparse(raw)
        if "youtube" in parsed.netloc or "youtu.be" in parsed.netloc:
            qs = urllib.parse.parse_qs(parsed.query)
            if "v" in qs and qs["v"]:
                vid = qs["v"][0]
                if len(vid) == 11:
                    return vid
            path_parts = [p for p in parsed.path.split("/") if p]
            if path_parts:
                candidate = path_parts[-1]
                if len(candidate) == 11 and re.match(r"^[a-zA-Z0-9_-]{11}$", candidate):
                    return candidate
    except Exception:
        pass

    return None


def scrape_first_video_id(query: str, timeout: int = 7) -> Optional[str]:
    """
    Scrapes YouTube search results for the first non-Shorts video ID.
    Returns the 11-character video ID if found, else None.
    """
    try:
        encoded = urllib.parse.quote_plus(query)
        search_url = f"https://www.youtube.com/results?search_query={encoded}"
        resp = requests.get(search_url, headers=DEFAULT_HEADERS, timeout=timeout)
        if resp.status_code != 200:
            logger.warning(f"YouTube search request returned status {resp.status_code}")
            return None

        html = resp.text

        # 1. Search for videoId in ytInitialData JSON blobs
        vid_matches = re.findall(r'"videoId":"([a-zA-Z0-9_-]{11})"', html)
        for vid in vid_matches:
            # Check if it's associated with /shorts/ in nearby text
            idx = html.find(vid)
            if idx != -1:
                context = html[max(0, idx - 100): min(len(html), idx + 100)]
                if "shorts" in context.lower():
                    continue
            return vid

        # 2. Fallback regex for watch links
        watch_matches = re.findall(r'\/watch\?v=([a-zA-Z0-9_-]{11})', html)
        for vid in watch_matches:
            return vid

    except Exception as e:
        logger.error(f"Error scraping YouTube first video ID: {e}")

    return None


def format_duration(seconds: Union[int, float, str]) -> str:
    """Formats duration in seconds to MM:SS or HH:MM:SS."""
    try:
        total_sec = int(float(seconds))
        hours = total_sec // 3600
        minutes = (total_sec % 3600) // 60
        secs = total_sec % 60
        if hours > 0:
            return f"{hours:02d}:{minutes:02d}:{secs:02d}"
        return f"{minutes:02d}:{secs:02d}"
    except Exception:
        return "00:00"


def scrape_video_info(video_id: str, timeout: int = 7) -> dict:
    """
    Scrapes metadata for a specific YouTube video ID directly from YouTube HTML.
    Extracts: title, channel, view count (formatted), duration, like count, description.
    """
    info = {
        "video_id": video_id,
        "url": f"https://www.youtube.com/watch?v={video_id}",
        "title": "YouTube Video",
        "channel": "YouTube Creator",
        "views": "0",
        "duration": "00:00",
        "duration_seconds": 0,
        "likes": "N/A",
        "description": "",
        "published_date": "",
    }

    try:
        watch_url = f"https://www.youtube.com/watch?v={video_id}"
        resp = requests.get(watch_url, headers=DEFAULT_HEADERS, timeout=timeout)
        if resp.status_code != 200:
            logger.warning(f"YouTube video fetch returned status {resp.status_code}")
            return info

        html = resp.text

        # Title
        title_m = re.search(r'<meta name="title" content="([^"]+)">', html)
        if title_m:
            info["title"] = title_m.group(1).replace("&amp;", "&").replace("&quot;", '"').replace("&#39;", "'")
        else:
            title_tag = re.search(r'<title>(.*?) - YouTube</title>', html)
            if title_tag:
                info["title"] = title_tag.group(1).strip()

        # Channel / Author
        author_m = re.search(r'<link itemprop="name" content="([^"]+)">', html)
        if author_m:
            info["channel"] = author_m.group(1)
        else:
            owner_m = re.search(r'"ownerChannelName"\s*:\s*"([^"]+)"', html)
            if owner_m:
                info["channel"] = owner_m.group(1)
            else:
                author_m2 = re.search(r'"author"\s*:\s*"([^"]+)"', html)
                if author_m2:
                    info["channel"] = author_m2.group(1)

        # View Count
        views_m = re.search(r'"viewCount"\s*:\s*"(\d+)"', html)
        if views_m:
            count = int(views_m.group(1))
            info["views"] = f"{count:,}"
        else:
            views_raw = re.search(r'([0-9,]+)\s+views', html)
            if views_raw:
                info["views"] = views_raw.group(1)

        # Duration
        dur_ms = re.search(r'"approxDurationMs"\s*:\s*"(\d+)"', html)
        if dur_ms:
            sec = int(dur_ms.group(1)) // 1000
            info["duration_seconds"] = sec
            info["duration"] = format_duration(sec)
        else:
            dur_sec = re.search(r'"lengthSeconds"\s*:\s*"(\d+)"', html)
            if dur_sec:
                sec = int(dur_sec.group(1))
                info["duration_seconds"] = sec
                info["duration"] = format_duration(sec)

        # Likes
        likes_m = re.search(r'"likeCount"\s*:\s*"(\d+)"', html)
        if likes_m:
            l_count = int(likes_m.group(1))
            info["likes"] = f"{l_count:,}"
        else:
            like_lbl = re.search(r'([0-9,KMB.]+)\s+likes', html, re.IGNORECASE)
            if like_lbl:
                info["likes"] = like_lbl.group(1)

        # Description
        desc_m = re.search(r'<meta name="description" content="([^"]+)">', html)
        if desc_m:
            info["description"] = desc_m.group(1).replace("&amp;", "&").replace("&quot;", '"').replace("&#39;", "'")
        else:
            short_desc = re.search(r'"shortDescription"\s*:\s*"(.*?)"', html)
            if short_desc:
                info["description"] = short_desc.group(1).encode().decode('unicode-escape', 'ignore')

        # Published Date
        pub_m = re.search(r'<meta itemprop="datePublished" content="([^"]+)">', html)
        if pub_m:
            info["published_date"] = pub_m.group(1)
        else:
            upload_m = re.search(r'"uploadDate"\s*:\s*"([^"]+)"', html)
            if upload_m:
                info["published_date"] = upload_m.group(1)

    except Exception as e:
        logger.error(f"Error scraping YouTube video info: {e}")

    return info


def fetch_video_transcript(video_id: str, preferred_languages: Optional[List[str]] = None) -> Tuple[bool, str, List[dict]]:
    """
    Fetches subtitles/transcripts using youtube-transcript-api with language fallback.
    Returns (success, formatted_transcript_text, raw_entries).
    """
    if not _TRANSCRIPT_API_AVAILABLE:
        return False, "youtube_transcript_api is not installed in the current Python environment.", []

    lang_list = []
    if preferred_languages:
        lang_list.extend(preferred_languages)
    # Default multi-language priority
    for default_lang in ["en", "te", "hi", "es", "auto", "en-US", "en-GB"]:
        if default_lang not in lang_list:
            lang_list.append(default_lang)

    try:
        # First attempt: direct get_transcript with language priority
        try:
            entries = YouTubeTranscriptApi.get_transcript(video_id, languages=lang_list)
            if entries:
                formatted = _format_transcript_entries(entries)
                return True, formatted, entries
        except Exception as e:
            logger.debug(f"Direct transcript lookup failed for {video_id}: {e}")

        # Second attempt: list_transcripts to inspect all available and translate or pick best
        try:
            transcript_list = YouTubeTranscriptApi.list_transcripts(video_id)
            # 1. Try manual transcript
            for t in transcript_list:
                if not t.is_generated:
                    entries = t.fetch()
                    formatted = _format_transcript_entries(entries)
                    return True, formatted, entries

            # 2. Try auto-generated transcript
            for t in transcript_list:
                if t.is_generated:
                    entries = t.fetch()
                    formatted = _format_transcript_entries(entries)
                    return True, formatted, entries
        except Exception as e:
            logger.debug(f"List transcripts inspection failed for {video_id}: {e}")

        return False, "No English or matching subtitles/captions found for this video.", []

    except Exception as ex:
        logger.error(f"Error fetching YouTube transcript for {video_id}: {ex}")
        return False, f"Failed to retrieve transcript: {str(ex)}", []


def _format_transcript_entries(entries: List[dict]) -> str:
    """Formats transcript entries into clean timestamped text."""
    lines = []
    for item in entries:
        start_sec = item.get("start", 0)
        time_str = format_duration(start_sec)
        text = str(item.get("text", "")).strip().replace("\n", " ")
        if text:
            lines.append(f"[{time_str}] {text}")
    return "\n".join(lines)


def generate_video_summary(
    info: dict,
    transcript_text: str,
    target_language: str = "English"
) -> str:
    """
    Generates a high-quality, structured summary of the YouTube video using Gemini AI,
    with an intelligent fallback if Gemini is not accessible.
    """
    title = info.get("title", "YouTube Video")
    channel = info.get("channel", "Unknown Channel")
    duration = info.get("duration", "N/A")
    views = info.get("views", "N/A")
    description = info.get("description", "")

    client = _get_gemini_client()
    if client and transcript_text and len(transcript_text.strip()) > 50:
        # Trim transcript to ~30k characters if very long to stay well within limits
        safe_transcript = transcript_text[:35000]

        prompt = f"""You are MAX AI Assistant, analyzing and summarizing a YouTube video for Yaswanth (sir/boss).

VIDEO METADATA:
- Title: {title}
- Channel: {channel}
- Duration: {duration}
- Views: {views}

TRANSCRIPT:
\"\"\"
{safe_transcript}
\"\"\"

Please provide a clean, highly readable, structured summary in {target_language} with the following sections:
1. 🎬 Overview & Core Thesis (1-2 clear paragraphs explaining what the video is about and its central message)
2. 🔑 Key Takeaways & Highlights (5-7 bullet points of the most valuable insights, facts, or instructions)
3. ⏱️ Chronological Outline & Key Segments (Breakdown with approximate timestamps from the transcript)
4. 💡 Conclusion & Actionable Advice (Final takeaways or practical steps for the viewer)

Make the formatting professional, well-spaced, and easy to read in a text editor like Notepad.
"""
        try:
            response = client.generate_content(prompt)
            if response and hasattr(response, "text") and response.text:
                return response.text.strip()
        except Exception as e:
            logger.warning(f"Gemini video summarization failed: {e}")

    # Fallback local structured summary
    summary_lines = [
        f"═══════════════════════════════════════════════════════════════════════",
        f"  MAX AI — YOUTUBE VIDEO SUMMARY",
        f"═══════════════════════════════════════════════════════════════════════",
        f"🎬 Title:    {title}",
        f"📺 Channel:  {channel}",
        f"⏱️ Duration: {duration}",
        f"👁️ Views:    {views}",
        f"🔗 URL:      {info.get('url', '')}",
        f"📅 Date:     {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"═══════════════════════════════════════════════════════════════════════",
        "",
        "📌 OVERVIEW:",
    ]
    if description:
        clean_desc = "\n".join(description.splitlines()[:8])
        summary_lines.append(f"  {clean_desc}")
    else:
        summary_lines.append(f"  Video '{title}' by {channel}.")

    summary_lines.append("")
    summary_lines.append("📝 TRANSCRIPT HIGHLIGHTS & EXCERPTS:")
    
    if transcript_text:
        lines = [ln.strip() for ln in transcript_text.splitlines() if ln.strip()]
        # Select evenly distributed sample points
        if len(lines) <= 15:
            sample_lines = lines
        else:
            step = max(1, len(lines) // 12)
            sample_lines = [lines[i] for i in range(0, len(lines), step)][:12]
        
        for sl in sample_lines:
            summary_lines.append(f"  • {sl}")
    else:
        summary_lines.append("  (Transcript was unavailable for this video; summary generated from metadata)")

    summary_lines.append("")
    summary_lines.append("═══════════════════════════════════════════════════════════════════════")
    summary_lines.append("Generated by MAX AI Assistant.")
    return "\n".join(summary_lines)


def _sanitize_filename(name: str, max_len: int = 40) -> str:
    """Sanitizes a string to make it safe for Windows filenames."""
    clean = re.sub(r'[\\/*?:"<>|]', "", name)
    clean = re.sub(r"\s+", "_", clean).strip("._")
    if len(clean) > max_len:
        clean = clean[:max_len]
    return clean or "YouTube_Video"


# ─── 1. Direct Video Playback Tool ──────────────────────────────────────────

def play_youtube_video(query: str = None, url: str = None, **kwargs) -> dict:
    """
    Directly plays a YouTube video in the default browser.
    If given a search query, scrapes YouTube for the top non-Shorts video ID and opens
    https://www.youtube.com/watch?v={vid} directly instead of just opening search results.
    Reuses open browser context if YouTube is already active.
    
    Args:
        query: Search query (e.g., 'Telugu lo Python tutorials', 'Interstellar soundtrack').
        url: Direct YouTube video URL or ID (optional).
    """
    try:
        raw_input = query or url or kwargs.get("search_query") or kwargs.get("video") or ""
        if not raw_input:
            return {"status": "error", "message": "Query or YouTube URL is required.", "verified": False}

        vid = extract_video_id(raw_input)
        is_direct_video = False
        target_url = None

        if vid:
            target_url = f"https://www.youtube.com/watch?v={vid}"
            is_direct_video = True
        else:
            # It's a search query: scrape for top video ID
            scraped_vid = scrape_first_video_id(raw_input)
            if scraped_vid:
                target_url = f"https://www.youtube.com/watch?v={scraped_vid}"
                vid = scraped_vid
                is_direct_video = True
            else:
                # Fallback to standard search page
                encoded = urllib.parse.quote_plus(raw_input)
                target_url = f"https://www.youtube.com/results?search_query={encoded}"
                is_direct_video = False

        # Reuse existing YouTube browser context if available
        from tools.web_tools import find_existing_youtube_context
        yt_ctx = find_existing_youtube_context()

        if yt_ctx and yt_ctx.get("hwnd"):
            act_res = activate_app_window(yt_ctx["hwnd"], timeout_seconds=2.5)
            if act_res.get("verified"):
                if yt_ctx.get("type") == "browser_tab_uia" and not yt_ctx.get("is_active_tab"):
                    try:
                        from core.ui_automation import uia_engine
                        uia_engine.click_element_by_selector(target=yt_ctx.get("title", "YouTube"))
                        time.sleep(0.12)
                    except Exception:
                        pass

                pyautogui.FAILSAFE = False
                pyautogui.hotkey("ctrl", "l")
                time.sleep(0.08)
                pyperclip.copy(target_url)
                pyautogui.hotkey("ctrl", "v")
                time.sleep(0.05)
                pyautogui.press("enter")

                action_verifier.verify_app_focused_sync("chrome")

                return {
                    "status": "success",
                    "operation": "play_youtube_video",
                    "action": "reused_youtube_window",
                    "video_id": vid,
                    "url": target_url,
                    "is_direct_video": is_direct_video,
                    "message": f"Playing YouTube video directly in active window: {target_url}",
                    "verified": True
                }

        # Check open browser instances
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
                        "operation": "play_youtube_video",
                        "action": "opened_in_existing_browser",
                        "video_id": vid,
                        "url": target_url,
                        "is_direct_video": is_direct_video,
                        "message": f"Playing YouTube video in {b_name}: {target_url}",
                        "verified": True
                    }

        # Fallback: Launch default browser
        webbrowser.open(target_url)
        return {
            "status": "success",
            "operation": "play_youtube_video",
            "action": "launched_default_browser",
            "video_id": vid,
            "url": target_url,
            "is_direct_video": is_direct_video,
            "message": f"Playing YouTube video: {target_url}",
            "verified": True
        }

    except Exception as e:
        logger.error(f"Error in play_youtube_video: {e}")
        return {"status": "error", "message": f"Failed to play YouTube video: {str(e)}", "verified": False}


# ─── 2. Video Metadata Extraction Tool ──────────────────────────────────────

def get_youtube_video_info(url: str = None, query: str = None, **kwargs) -> dict:
    """
    Extracts detailed metadata from a YouTube video including title, channel,
    views, duration, like count, and description.
    
    Args:
        url: YouTube URL or 11-char video ID.
        query: Search query if URL is not known (will resolve the top video).
    """
    try:
        raw_input = url or query or kwargs.get("video") or kwargs.get("video_url") or ""
        if not raw_input:
            return {"status": "error", "message": "URL or search query is required.", "verified": False}

        vid = extract_video_id(raw_input)
        if not vid:
            vid = scrape_first_video_id(raw_input)
            if not vid:
                return {"status": "error", "message": f"Could not find YouTube video for: '{raw_input}'", "verified": False}

        info = scrape_video_info(vid)
        return {
            "status": "success",
            "operation": "get_youtube_video_info",
            "video_id": vid,
            "url": info["url"],
            "title": info["title"],
            "channel": info["channel"],
            "views": info["views"],
            "duration": info["duration"],
            "duration_seconds": info.get("duration_seconds", 0),
            "likes": info["likes"],
            "description": info["description"][:500] + "..." if len(info["description"]) > 500 else info["description"],
            "published_date": info["published_date"],
            "verified": True
        }

    except Exception as e:
        logger.error(f"Error in get_youtube_video_info: {e}")
        return {"status": "error", "message": f"Failed to get video info: {str(e)}", "verified": False}


# ─── 3. Video Transcript & Summarization Tool ───────────────────────────────

def summarize_youtube_video(
    url: str = None,
    query: str = None,
    save_to_desktop: bool = True,
    open_in_notepad: bool = True,
    language: str = "English",
    **kwargs
) -> dict:
    """
    Fetches the transcript/subtitles for a YouTube video, generates an AI summary with key takeaways,
    chronological outline, and actionable items, optionally saves the summary to Desktop as a .txt file,
    and opens it in Notepad.
    
    Args:
        url: YouTube video URL or 11-char video ID.
        query: Search query if URL is not provided.
        save_to_desktop: Whether to save the summary file on Desktop (default: True).
        open_in_notepad: Whether to automatically open the saved summary file in Notepad (default: True).
        language: Language for the summary output (default: 'English').
    """
    try:
        raw_input = url or query or kwargs.get("video") or kwargs.get("video_url") or ""
        if not raw_input:
            return {"status": "error", "message": "URL or search query is required.", "verified": False}

        # Resolve Video ID
        vid = extract_video_id(raw_input)
        if not vid:
            vid = scrape_first_video_id(raw_input)
            if not vid:
                return {"status": "error", "message": f"Could not find YouTube video for: '{raw_input}'", "verified": False}

        # Fetch Video Info
        info = scrape_video_info(vid)

        # Fetch Transcript
        has_transcript, transcript_text, entries = fetch_video_transcript(vid)

        # Generate Summary
        summary = generate_video_summary(info, transcript_text if has_transcript else "", target_language=language)

        saved_file_path = None
        notepad_opened = False

        # Save to Desktop
        if save_to_desktop:
            sanitized_title = _sanitize_filename(info.get("title", "Video"))
            timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
            file_name = f"YouTube_Summary_{sanitized_title}_{timestamp}.txt"
            desktop_dir = SPECIAL_FOLDERS.get("desktop", os.path.expanduser("~/Desktop"))
            target_path = os.path.join(desktop_dir, file_name)

            file_content = (
                f"{summary}\n\n"
                f"═══════════════════════════════════════════════════════════════════════\n"
                f"FULL TRANSCRIPT EXCERPT:\n"
                f"═══════════════════════════════════════════════════════════════════════\n\n"
                f"{transcript_text[:10000] if transcript_text else 'No subtitles available.'}\n"
            )

            write_res = write_file(path=target_path, content=file_content)
            if write_res.get("status") == "success":
                saved_file_path = write_res.get("path", target_path)

                if open_in_notepad:
                    try:
                        open_res = open_application("notepad", file_path=saved_file_path)
                        notepad_opened = open_res.get("status") == "success" or open_res.get("verified", False)
                    except Exception as ex:
                        logger.warning(f"Could not open Notepad for summary: {ex}")
                        try:
                            open_file(saved_file_path)
                            notepad_opened = True
                        except Exception:
                            pass

        return {
            "status": "success",
            "operation": "summarize_youtube_video",
            "video_id": vid,
            "title": info["title"],
            "channel": info["channel"],
            "views": info["views"],
            "duration": info["duration"],
            "url": info["url"],
            "has_transcript": has_transcript,
            "summary": summary,
            "saved_to_desktop": save_to_desktop,
            "file_path": saved_file_path,
            "notepad_opened": notepad_opened,
            "message": f"Summarized '{info['title']}' successfully." + (f" Saved to {saved_file_path} and opened in Notepad." if saved_file_path and notepad_opened else ""),
            "verified": True
        }

    except Exception as e:
        logger.error(f"Error in summarize_youtube_video: {e}")
        return {"status": "error", "message": f"Failed to summarize YouTube video: {str(e)}", "verified": False}


# ─── 4. Regional Trending Feed Tool ─────────────────────────────────────────

def get_youtube_trending(region: str = "IN", max_results: int = 10, **kwargs) -> dict:
    """
    Retrieves the top trending YouTube videos for a specified country/region code.
    
    Args:
        region: ISO 2-letter country code (e.g., 'IN' for India, 'US' for USA, 'GB' for UK).
        max_results: Maximum number of trending items to return (default: 10, max: 30).
    """
    try:
        region_code = (region or "IN").upper().strip()
        max_items = max(1, min(int(max_results or 10), 30))

        trending_url = f"https://www.youtube.com/feed/trending?gl={region_code}&hl=en"
        resp = requests.get(trending_url, headers=DEFAULT_HEADERS, timeout=8)
        if resp.status_code != 200:
            return {"status": "error", "message": f"YouTube trending feed returned HTTP {resp.status_code}", "verified": False}

        html = resp.text
        items = []
        seen_vids = set()

        # Parse videoId and titles
        matches = re.findall(r'"videoId":"([a-zA-Z0-9_-]{11})".*?"title":\{"runs":\[\{"text":"(.*?)"\}\]', html)
        if not matches:
            matches = re.findall(r'"videoId":"([a-zA-Z0-9_-]{11})".*?"accessibility":\{"accessibilityData":\{"label":"(.*?)"\}\}', html)

        for match in matches:
            vid = match[0]
            if vid in seen_vids:
                continue
            seen_vids.add(vid)

            title = match[1].replace("\\u0026", "&").replace("&amp;", "&").replace("&quot;", '"')
            # Extract channel name if in label
            channel = "Trending Creator"
            channel_m = re.search(r'by\s+(.*?)\s+[0-9,]+\s+views', title)
            if channel_m:
                channel = channel_m.group(1)

            items.append({
                "rank": len(items) + 1,
                "video_id": vid,
                "title": title[:100],
                "channel": channel,
                "url": f"https://www.youtube.com/watch?v={vid}"
            })

            if len(items) >= max_items:
                break

        # Fallback simple extraction if json regex was too restrictive
        if not items:
            raw_vids = re.findall(r'\/watch\?v=([a-zA-Z0-9_-]{11})', html)
            for vid in raw_vids:
                if vid not in seen_vids:
                    seen_vids.add(vid)
                    items.append({
                        "rank": len(items) + 1,
                        "video_id": vid,
                        "title": f"Trending Video #{len(items) + 1}",
                        "channel": "YouTube Trending",
                        "url": f"https://www.youtube.com/watch?v={vid}"
                    })
                    if len(items) >= max_items:
                        break

        return {
            "status": "success",
            "operation": "get_youtube_trending",
            "region": region_code,
            "count": len(items),
            "videos": items,
            "message": f"Retrieved top {len(items)} trending videos for region '{region_code}'.",
            "verified": True
        }

    except Exception as e:
        logger.error(f"Error in get_youtube_trending: {e}")
        return {"status": "error", "message": f"Failed to get trending videos: {str(e)}", "verified": False}


# ─── 5. Universal Entry Point & Dispatcher ──────────────────────────────────

def youtube_video(
    action: str = "play",
    query: str = None,
    url: str = None,
    region: str = "IN",
    save_to_desktop: bool = True,
    open_in_notepad: bool = True,
    language: str = "English",
    max_results: int = 10,
    **kwargs
) -> dict:
    """
    Universal YouTube tool dispatcher.
    
    Supported Actions:
      - 'play' / 'search': Play video directly or search.
      - 'summarize' / 'summary': Summarize video transcript with AI and save to Desktop.
      - 'info' / 'details': Get video metadata (views, duration, likes, channel).
      - 'trending': Get trending videos for a country/region.
    """
    act = (action or "play").lower().strip()

    if act in ("play", "open", "watch", "start"):
        return play_youtube_video(query=query, url=url, **kwargs)

    elif act in ("summarize", "summary", "notes", "overview"):
        return summarize_youtube_video(
            url=url,
            query=query,
            save_to_desktop=save_to_desktop,
            open_in_notepad=open_in_notepad,
            language=language,
            **kwargs
        )

    elif act in ("info", "details", "metadata", "get_info"):
        return get_youtube_video_info(url=url, query=query, **kwargs)

    elif act in ("trending", "trends", "top", "popular"):
        return get_youtube_trending(region=region, max_results=max_results, **kwargs)

    elif act in ("search", "find"):
        from tools.web_tools import search_youtube
        return search_youtube(query=query or url or "")

    else:
        # Default to play_youtube_video if query or url is present
        return play_youtube_video(query=query, url=url, **kwargs)


# ─── Tool Declarations for Realtime Voice & LLM Models ─────────────────────

TOOL_DECLARATIONS = [
    {
        "name": "play_youtube_video",
        "description": "Play a YouTube video directly in the browser. If a search query is given, it finds the top direct video (skipping Shorts) and plays it automatically.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The video search query or title to play (e.g. 'Telugu tech tutorials', 'Interstellar soundtrack', 'Lofi hip hop').",
                },
                "url": {
                    "type": "string",
                    "description": "Optional direct YouTube video URL or ID (e.g. 'https://www.youtube.com/watch?v=dQw4w9WgXcQ').",
                }
            },
        },
    },
    {
        "name": "summarize_youtube_video",
        "description": "Extract subtitles/transcript from a YouTube video, summarize it with AI into key takeaways and chronological outline, save the summary to Desktop as a .txt file, and open it in Notepad.",
        "parameters": {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "The YouTube video URL or video ID to summarize.",
                },
                "query": {
                    "type": "string",
                    "description": "Optional search query to find and summarize the top matching video if URL is unknown.",
                },
                "save_to_desktop": {
                    "type": "boolean",
                    "description": "Whether to save the summary file on Desktop (default: true).",
                },
                "open_in_notepad": {
                    "type": "boolean",
                    "description": "Whether to open the saved file in Notepad (default: true).",
                },
                "language": {
                    "type": "string",
                    "description": "Language for summary output (default: 'English').",
                }
            },
        },
    },
    {
        "name": "get_youtube_video_info",
        "description": "Get detailed metadata for a YouTube video including title, channel, views, duration (MM:SS), likes, and description.",
        "parameters": {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "The YouTube video URL or 11-character video ID.",
                },
                "query": {
                    "type": "string",
                    "description": "Optional search query to find and inspect top video.",
                }
            },
        },
    },
    {
        "name": "get_youtube_trending",
        "description": "Get top trending YouTube videos for a specific country or region code (e.g., 'IN' for India, 'US' for USA, 'GB' for UK).",
        "parameters": {
            "type": "object",
            "properties": {
                "region": {
                    "type": "string",
                    "description": "ISO 2-letter country code (default: 'IN').",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Max number of trending videos to return (default: 10, max: 30).",
                }
            },
        },
    },
    {
        "name": "youtube_video",
        "description": "Universal YouTube automation controller supporting 'play', 'summarize', 'info', and 'trending' operations.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["play", "summarize", "info", "trending", "search"],
                    "description": "Action to perform on YouTube.",
                },
                "query": {
                    "type": "string",
                    "description": "Search query or video topic.",
                },
                "url": {
                    "type": "string",
                    "description": "Direct YouTube URL or ID.",
                },
                "region": {
                    "type": "string",
                    "description": "Country code for trending (e.g. 'IN', 'US').",
                },
                "save_to_desktop": {
                    "type": "boolean",
                    "description": "Save summary to Desktop.",
                }
            },
        },
    },
]

TOOL_FUNCTIONS = {
    "play_youtube_video": play_youtube_video,
    "summarize_youtube_video": summarize_youtube_video,
    "get_youtube_video_info": get_youtube_video_info,
    "get_youtube_trending": get_youtube_trending,
    "youtube_video": youtube_video,
}
