"""
Unit Tests for MAX AI YouTube Video Automation Subsystem.
Tests video ID extraction, duration formatting, search scraping, metadata extraction,
transcript processing, summarization, regional trending feed, and UnifiedToolRegistry integration.
"""

import os
import sys
import json
import pytest
from unittest.mock import MagicMock, patch

# Ensure windows-agent root is in sys.path
AGENT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_ROOT not in sys.path:
    sys.path.insert(0, AGENT_ROOT)

from tools.youtube_tool import (
    extract_video_id,
    format_duration,
    _sanitize_filename,
    scrape_first_video_id,
    scrape_video_info,
    fetch_video_transcript,
    _format_transcript_entries,
    generate_video_summary,
    play_youtube_video,
    get_youtube_video_info,
    summarize_youtube_video,
    get_youtube_trending,
    youtube_video,
    TOOL_DECLARATIONS,
    TOOL_FUNCTIONS,
)
from core.unified_tool_registry import UnifiedToolRegistry


# ─── 1. Video ID & Formatting Tests ──────────────────────────────────────────

def test_extract_video_id():
    # Direct 11-char ID
    assert extract_video_id("dQw4w9WgXcQ") == "dQw4w9WgXcQ"
    assert extract_video_id("a1B2c3D4e5F") == "a1B2c3D4e5F"

    # Standard watch URLs
    assert extract_video_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ") == "dQw4w9WgXcQ"
    assert extract_video_id("https://youtube.com/watch?v=dQw4w9WgXcQ&t=10s") == "dQw4w9WgXcQ"
    assert extract_video_id("http://m.youtube.com/watch?v=dQw4w9WgXcQ") == "dQw4w9WgXcQ"

    # Shortened youtu.be URLs
    assert extract_video_id("https://youtu.be/dQw4w9WgXcQ") == "dQw4w9WgXcQ"
    assert extract_video_id("https://youtu.be/dQw4w9WgXcQ?si=abcdef") == "dQw4w9WgXcQ"

    # Embed, Shorts, and Live URLs
    assert extract_video_id("https://www.youtube.com/embed/dQw4w9WgXcQ") == "dQw4w9WgXcQ"
    assert extract_video_id("https://www.youtube.com/shorts/dQw4w9WgXcQ") == "dQw4w9WgXcQ"
    assert extract_video_id("https://www.youtube.com/live/dQw4w9WgXcQ") == "dQw4w9WgXcQ"

    # Invalid cases
    assert extract_video_id("not a url or id") is None
    assert extract_video_id("") is None
    assert extract_video_id(None) is None


def test_format_duration():
    assert format_duration(0) == "00:00"
    assert format_duration(45) == "00:45"
    assert format_duration(125) == "02:05"
    assert format_duration(3600) == "01:00:00"
    assert format_duration(3665) == "01:01:05"
    assert format_duration("75") == "01:15"
    assert format_duration("invalid") == "00:00"


def test_sanitize_filename():
    assert _sanitize_filename("Learn Python: The Complete 2026 Guide / Tutorial", max_len=36) == "Learn_Python_The_Complete_2026_Guide"
    assert _sanitize_filename("test?*<>|file") == "testfile"
    assert _sanitize_filename("") == "YouTube_Video"


# ─── 2. Search & Info Scraping Tests (Mocked) ───────────────────────────────

def test_scrape_first_video_id_mock():
    mock_html = """
    <html>
        <script>var ytInitialData = {"videoId":"dQw4w9WgXcQ","title":{"runs":[{"text":"Sample"}]}};</script>
        <a href="/watch?v=dQw4w9WgXcQ">Watch</a>
    </html>
    """
    with patch("requests.get") as mock_get:
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.text = mock_html
        mock_get.return_value = mock_resp

        vid = scrape_first_video_id("test search")
        assert vid == "dQw4w9WgXcQ"


def test_scrape_video_info_mock():
    mock_html = """
    <html>
        <meta name="title" content="Python Tutorial for Beginners">
        <link itemprop="name" content="Tech Academy">
        <script>
            var info = {
                "viewCount": "1250000",
                "approxDurationMs": "645000",
                "likeCount": "85000"
            };
        </script>
        <meta name="description" content="Learn Python from scratch with practical exercises.">
        <meta itemprop="datePublished" content="2026-01-15">
    </html>
    """
    with patch("requests.get") as mock_get:
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.text = mock_html
        mock_get.return_value = mock_resp

        info = scrape_video_info("dQw4w9WgXcQ")
        assert info["title"] == "Python Tutorial for Beginners"
        assert info["channel"] == "Tech Academy"
        assert info["views"] == "1,250,000"
        assert info["duration"] == "10:45"
        assert info["duration_seconds"] == 645
        assert info["likes"] == "85,000"
        assert "Learn Python from scratch" in info["description"]
        assert info["published_date"] == "2026-01-15"


# ─── 3. Transcript & Summarization Tests ───────────────────────────────────

def test_format_transcript_entries():
    entries = [
        {"start": 0.0, "duration": 3.5, "text": "Welcome to the video."},
        {"start": 4.2, "duration": 4.0, "text": "Today we will learn Python.\nIt is very easy."},
        {"start": 75.0, "duration": 5.0, "text": "Let us look at loops."},
    ]
    formatted = _format_transcript_entries(entries)
    assert "[00:00] Welcome to the video." in formatted
    assert "[00:04] Today we will learn Python. It is very easy." in formatted
    assert "[01:15] Let us look at loops." in formatted


def test_generate_video_summary_fallback():
    info = {
        "title": "Python Tutorial",
        "channel": "Tech Academy",
        "duration": "10:45",
        "views": "1,000,000",
        "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "description": "Complete beginner tutorial.",
    }
    transcript = "[00:00] Intro\n[02:00] Variables\n[05:00] Functions\n[08:00] Classes\n[10:00] Conclusion"
    
    with patch("tools.youtube_tool._get_gemini_client", return_value=None):
        summary = generate_video_summary(info, transcript)
        assert "MAX AI — YOUTUBE VIDEO SUMMARY" in summary
        assert "Python Tutorial" in summary
        assert "Tech Academy" in summary
        assert "10:45" in summary
        assert "TRANSCRIPT HIGHLIGHTS" in summary


# ─── 4. High-Level Tool Functions Tests ─────────────────────────────────────

def test_play_youtube_video():
    with patch("tools.youtube_tool.scrape_first_video_id", return_value="dQw4w9WgXcQ"), \
         patch("tools.web_tools.find_existing_youtube_context", return_value=None), \
         patch("tools.youtube_tool._find_windows_for_app", return_value=[]), \
         patch("webbrowser.open") as mock_wb:

        # Test with search query
        res = play_youtube_video(query="Interstellar main theme")
        assert res["status"] == "success"
        assert res["video_id"] == "dQw4w9WgXcQ"
        assert "https://www.youtube.com/watch?v=dQw4w9WgXcQ" in res["url"]
        assert res["is_direct_video"] is True
        mock_wb.assert_called_once()

    # Test with direct URL
    with patch("tools.web_tools.find_existing_youtube_context", return_value=None), \
         patch("tools.youtube_tool._find_windows_for_app", return_value=[]), \
         patch("webbrowser.open") as mock_wb2:
        res2 = play_youtube_video(url="https://youtu.be/dQw4w9WgXcQ")
        assert res2["status"] == "success"
        assert res2["video_id"] == "dQw4w9WgXcQ"
        mock_wb2.assert_called_once()


def test_get_youtube_video_info():
    mock_info = {
        "video_id": "dQw4w9WgXcQ",
        "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "title": "Never Gonna Give You Up",
        "channel": "Rick Astley",
        "views": "1,500,000,000",
        "duration": "03:33",
        "duration_seconds": 213,
        "likes": "16,000,000",
        "description": "The official music video.",
        "published_date": "2009-10-25"
    }
    with patch("tools.youtube_tool.scrape_video_info", return_value=mock_info):
        res = get_youtube_video_info("dQw4w9WgXcQ")
        assert res["status"] == "success"
        assert res["title"] == "Never Gonna Give You Up"
        assert res["channel"] == "Rick Astley"
        assert res["views"] == "1,500,000,000"
        assert res["duration"] == "03:33"


def test_summarize_youtube_video(tmp_path):
    mock_info = {
        "video_id": "dQw4w9WgXcQ",
        "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "title": "Machine Learning in 10 Minutes",
        "channel": "AI World",
        "views": "500,000",
        "duration": "10:00",
        "duration_seconds": 600,
        "likes": "25,000",
        "description": "An introduction to ML algorithms.",
        "published_date": "2026-01-01"
    }
    mock_transcript = "[00:00] Welcome to machine learning.\n[05:00] Neural networks overview."

    with patch("tools.youtube_tool.scrape_video_info", return_value=mock_info), \
         patch("tools.youtube_tool.fetch_video_transcript", return_value=(True, mock_transcript, [])), \
         patch("tools.youtube_tool.open_application", return_value={"status": "success", "verified": True}), \
         patch("tools.youtube_tool.SPECIAL_FOLDERS", {"desktop": str(tmp_path)}):

        res = summarize_youtube_video(
            url="https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            save_to_desktop=True,
            open_in_notepad=True
        )

        assert res["status"] == "success"
        assert res["title"] == "Machine Learning in 10 Minutes"
        assert res["has_transcript"] is True
        assert res["saved_to_desktop"] is True
        assert res["file_path"] is not None
        assert os.path.exists(res["file_path"])
        assert res["notepad_opened"] is True

        with open(res["file_path"], "r", encoding="utf-8") as f:
            content = f.read()
            assert "Machine Learning in 10 Minutes" in content
            assert "FULL TRANSCRIPT EXCERPT:" in content


def test_get_youtube_trending():
    mock_html = """
    <html>
        <script>
            var data = [
                {"videoId":"vid11111111","title":{"runs":[{"text":"India vs Australia Cricket Highlights"}]}},
                {"videoId":"vid22222222","title":{"runs":[{"text":"New Movie Trailer 2026"}]}}
            ];
        </script>
    </html>
    """
    with patch("requests.get") as mock_get:
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.text = mock_html
        mock_get.return_value = mock_resp

        res = get_youtube_trending(region="IN", max_results=5)
        assert res["status"] == "success"
        assert res["region"] == "IN"
        assert len(res["videos"]) == 2
        assert res["videos"][0]["video_id"] == "vid11111111"
        assert "Cricket Highlights" in res["videos"][0]["title"]


def test_youtube_video_dispatcher():
    with patch("tools.youtube_tool.play_youtube_video", return_value={"status": "success", "action": "played"}) as mock_play, \
         patch("tools.youtube_tool.summarize_youtube_video", return_value={"status": "success", "action": "summarized"}) as mock_sum, \
         patch("tools.youtube_tool.get_youtube_video_info", return_value={"status": "success", "action": "info"}) as mock_info, \
         patch("tools.youtube_tool.get_youtube_trending", return_value={"status": "success", "action": "trending"}) as mock_trend:

        # Test play action
        r1 = youtube_video(action="play", query="Telugu songs")
        assert r1["action"] == "played"
        mock_play.assert_called_once()

        # Test summarize action
        r2 = youtube_video(action="summarize", url="https://youtu.be/dQw4w9WgXcQ")
        assert r2["action"] == "summarized"
        mock_sum.assert_called_once()

        # Test info action
        r3 = youtube_video(action="info", url="https://youtu.be/dQw4w9WgXcQ")
        assert r3["action"] == "info"
        mock_info.assert_called_once()

        # Test trending action
        r4 = youtube_video(action="trending", region="US")
        assert r4["action"] == "trending"
        mock_trend.assert_called_once()


# ─── 5. UnifiedToolRegistry Registration & Routing Tests ────────────────────

def test_unified_tool_registry_registration():
    registry = UnifiedToolRegistry.get_instance()
    declarations = {d["name"]: d for d in registry.get_declarations()}

    # Check that tool declarations exist in the unified registry
    assert "play_youtube_video" in declarations
    assert "summarize_youtube_video" in declarations
    assert "get_youtube_video_info" in declarations
    assert "get_youtube_trending" in declarations
    assert "youtube_video" in declarations

    # Check tool functions registered
    assert "play_youtube_video" in registry._functions
    assert "summarize_youtube_video" in registry._functions
    assert "get_youtube_video_info" in registry._functions
    assert "get_youtube_trending" in registry._functions
    assert "youtube_video" in registry._functions

    # Check aliases resolution
    from core.unified_tool_registry import TOOL_ALIASES
    assert TOOL_ALIASES.get("play_youtube") == "play_youtube_video"
    assert TOOL_ALIASES.get("summarize_youtube") == "summarize_youtube_video"
    assert TOOL_ALIASES.get("youtube_info") == "get_youtube_video_info"
    assert TOOL_ALIASES.get("youtube_trending") == "get_youtube_trending"
    assert TOOL_ALIASES.get("trending_videos") == "get_youtube_trending"
    assert TOOL_ALIASES.get("youtube") == "youtube_video"
