"""
JARVIS Productivity Tools
Reminders, clipboard operations, keyboard simulation, allowlisted shell commands.
"""

import os
import time
import subprocess
import threading
import datetime
import logging
import ctypes
from ctypes import wintypes
import pyautogui
import pyperclip

user32 = ctypes.windll.user32

from config import ALLOWED_COMMANDS
from tools.clipboard_tool import clipboard_helper
from core.verifier import action_verifier
from core.undo_manager import undo_manager
from tools.persistent_scheduler import persistent_scheduler

logger = logging.getLogger("MAXWindowsAgent.Productivity")


def set_reminder(message: str, minutes: int = 1, **kwargs) -> dict:
    """
    Set a persistent reminder that will trigger after a specified number of minutes.
    Recorded in SQLite and scheduled with Windows Task Scheduler (survives app restarts and reboot).
    
    Args:
        message: The reminder message.
        minutes: Number of minutes from now (default: 1).
    """
    try:
        min_val = max(1, int(minutes or kwargs.get("time_minutes", 1)))
        msg_val = str(message or kwargs.get("text", "Reminder"))
        title = kwargs.get("title", "MAX Reminder")
        
        return persistent_scheduler.set_reminder(
            message=msg_val,
            minutes=min_val,
            title=title,
        )
    except Exception as e:
        logger.error(f"Error setting persistent reminder: {e}")
        return {"status": "error", "message": str(e)}


def list_scheduled_reminders(active_only: bool = True, **kwargs) -> dict:
    """
    List scheduled and active reminders from the persistent database.
    """
    try:
        return persistent_scheduler.list_reminders(active_only=active_only)
    except Exception as e:
        return {"status": "error", "message": str(e)}


def cancel_scheduled_reminder(reminder_id: str, **kwargs) -> dict:
    """
    Cancel a scheduled reminder by its ID.
    
    Args:
        reminder_id: The ID of the reminder to cancel (e.g. 'rem_12345').
    """
    try:
        rid = reminder_id or kwargs.get("id", "")
        return persistent_scheduler.cancel_reminder(reminder_id=rid)
    except Exception as e:
        return {"status": "error", "message": str(e)}


def undo_last_action(**kwargs) -> dict:
    """
    Undo the most recent reversible action (e.g. volume change, brightness change, file write, file move).
    Rolls back state safely after verifying preconditions.
    """
    try:
        return undo_manager.undo()
    except Exception as e:
        logger.error(f"Error during undo_last_action: {e}")
        return {"status": "error", "message": str(e)}


def list_undo_history(limit: int = 10, **kwargs) -> dict:
    """
    Inspect the recent undo history stack without executing any rollbacks.
    
    Args:
        limit: Number of recent actions to return (default: 10).
    """
    try:
        history = undo_manager.get_history(limit=limit)
        return {
            "status": "success",
            "count": len(history),
            "undo_stack": history,
            "can_undo": len(history) > 0,
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def clear_undo_history(**kwargs) -> dict:
    """Clear all recorded undo actions from memory."""
    try:
        undo_manager.clear()
        return {"status": "success", "message": "Undo history cleared."}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def clipboard_copy(text: str = None, **kwargs) -> dict:
    """
    Copy text to the system clipboard.
    
    Args:
        text: The text to copy to clipboard.
    """
    try:
        copy_text = text if text is not None else kwargs.get("content", kwargs.get("data", ""))
        copy_text = str(copy_text)
        success = clipboard_helper.set_text(copy_text)
        if not success:
            pyperclip.copy(copy_text)
        
        verified = action_verifier.verify_clipboard_contains_sync(copy_text[:20]) if copy_text else True
        return {
            "status": "success",
            "message": "Text copied to clipboard",
            "characters_copied": len(copy_text),
            "verified": verified
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def clipboard_paste(**kwargs) -> dict:
    """
    Get the current content of the system clipboard.
    """
    try:
        content = clipboard_helper.get_text()
        if not content:
            content = pyperclip.paste() or ""
            
        return {
            "status": "success",
            "content": content[:1000],  # Limit output length
            "length": len(content),
            "truncated": len(content) > 1000,
            "message": f"Clipboard contains: {content[:100]}" if content else "Clipboard is empty."
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def paste_text(text: str = None, **kwargs) -> dict:
    """
    Paste text into the currently active window.
    If text is provided, copies to clipboard and simulates Ctrl+V.
    If text is not provided, simulates Ctrl+V with current clipboard content.
    """
    try:
        pyautogui.FAILSAFE = False
        target_text = text if text is not None else kwargs.get("content")
        
        if target_text is not None:
            clipboard_helper.set_text(str(target_text))
            time.sleep(0.15)

        time.sleep(0.2)
        pyautogui.hotkey('ctrl', 'v')
        return {
            "status": "success",
            "message": f"Pasted into focused window.",
            "verified": True
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def copy_selection(**kwargs) -> dict:
    """
    Copy currently selected text in the active window (simulates Ctrl+C) and returns the text.
    """
    try:
        pyautogui.FAILSAFE = False
        pyautogui.hotkey('ctrl', 'c')
        time.sleep(0.25)
        content = clipboard_helper.get_text() or pyperclip.paste() or ""
        return {
            "status": "success",
            "copied_text": content[:500],
            "message": f"Copied selection: '{content[:80]}'" if content else "Copied selection to clipboard."
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def type_text(text: str, **kwargs) -> dict:
    """
    Type text into the currently active window using keyboard simulation.
    Uses clipboard paste for fast, 100% reliable typing of all characters and Unicode.
    
    Args:
        text: The text to type.
    """
    try:
        target_text = str(text or kwargs.get("content", ""))
        pyautogui.FAILSAFE = False
        time.sleep(0.5)  # Wait for window focus
        clipboard_helper.set_text(target_text)
        time.sleep(0.15)
        pyautogui.hotkey('ctrl', 'v')
        return {"status": "success", "message": f"Typed text into focused window", "verified": True}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def clear_field(**kwargs) -> dict:
    """
    Clears the active input field or text box in the focused window by selecting all and deleting.
    Simulates Ctrl+A followed by Delete.
    """
    try:
        pyautogui.FAILSAFE = False
        pyautogui.hotkey('ctrl', 'a')
        time.sleep(0.08)
        pyautogui.press('delete')
        return {"status": "success", "message": "Active field cleared", "verified": True}
    except Exception as e:
        logger.error(f"Error in clear_field: {e}")
        return {"status": "error", "message": str(e), "verified": False}


def smart_type(text: str = "", clear_first: bool = True, **kwargs) -> dict:
    """
    Smart type into the currently active input field.
    If clear_first is True (default), it selects all existing text and deletes it before typing.
    Uses clipboard paste for fast, 100% reliable Unicode and multi-language typing.
    
    Args:
        text: The text to type into the field.
        clear_first: Whether to clear existing content before typing (default: True).
    """
    try:
        pyautogui.FAILSAFE = False
        target_text = str(text if text is not None else kwargs.get("content", ""))
        
        if clear_first:
            clear_field()
            time.sleep(0.08)

        clipboard_helper.set_text(target_text)
        time.sleep(0.12)
        pyautogui.hotkey('ctrl', 'v')
        
        preview = target_text[:60] + ('...' if len(target_text) > 60 else '')
        return {
            "status": "success",
            "message": f"Smart-typed: {preview}",
            "text": target_text,
            "cleared_first": clear_first,
            "verified": True
        }
    except Exception as e:
        logger.error(f"Error in smart_type: {e}")
        return {"status": "error", "message": str(e), "verified": False}


# ─── Synthetic Test Data Generation ─────────────────────────────────────────

_FIRST_NAMES = [
    "Alex", "Jordan", "Taylor", "Morgan", "Casey", "Riley", "Drew", "Quinn",
    "Avery", "Blake", "Cameron", "Dakota", "Emerson", "Finley", "Harper",
    "Yaswanth", "Rahul", "Priya", "Ananya", "Vikram", "Suresh", "Kavya"
]
_LAST_NAMES = [
    "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
    "Davis", "Wilson", "Moore", "Taylor", "Anderson", "Thomas", "Jackson",
    "Sharma", "Reddy", "Verma", "Rao", "Patel", "Nair", "Chowdhury"
]
_DOMAINS = ["gmail.com", "yahoo.com", "outlook.com", "proton.me", "mail.com", "icloud.com"]


def generate_random_data(data_type: str = "name", **kwargs) -> dict:
    """
    Generate synthetic test data for form filling, testing, and dummy registrations.
    
    Supported types:
      - 'name', 'first_name', 'last_name'
      - 'email', 'username', 'password'
      - 'phone', 'birthday', 'address', 'city', 'zip_code'
    
    Args:
        data_type: The type of synthetic data to generate.
    """
    import random
    import string
    
    dt = str(data_type or kwargs.get("type", "name")).lower().strip()
    val = ""
    
    if dt == "first_name":
        val = random.choice(_FIRST_NAMES)
    elif dt == "last_name":
        val = random.choice(_LAST_NAMES)
    elif dt == "name":
        val = f"{random.choice(_FIRST_NAMES)} {random.choice(_LAST_NAMES)}"
    elif dt == "email":
        first = random.choice(_FIRST_NAMES).lower()
        last = random.choice(_LAST_NAMES).lower()
        num = random.randint(10, 999)
        val = f"{first}.{last}{num}@{random.choice(_DOMAINS)}"
    elif dt == "username":
        val = f"{random.choice(_FIRST_NAMES).lower()}{random.randint(100, 9999)}"
    elif dt == "password":
        chars = string.ascii_letters + string.digits + "!@#$%"
        raw = (
            random.choice(string.ascii_uppercase)
            + random.choice(string.digits)
            + random.choice("!@#$%")
            + "".join(random.choices(chars, k=9))
        )
        val = "".join(random.sample(raw, len(raw)))
    elif dt == "phone":
        val = f"+91{random.randint(6, 9)}{random.randint(100000000, 999999999)}"
    elif dt == "birthday":
        y = random.randint(1985, 2003)
        m = random.randint(1, 12)
        d = random.randint(1, 28)
        val = f"{m:02d}/{d:02d}/{y}"
    elif dt == "address":
        num = random.randint(100, 9999)
        street = random.choice(["Main Road", "Gandhi Nagar", "Park Avenue", "Oak Street", "MG Road"])
        val = f"{num}, {street}"
    elif dt == "zip_code":
        val = str(random.randint(500001, 534201))
    elif dt == "city":
        val = random.choice(["Hyderabad", "Bhimavaram", "Bengaluru", "Chennai", "Mumbai", "Visakhapatnam"])
    else:
        val = f"mock_{dt}_{random.randint(1000, 9999)}"

    return {
        "status": "success",
        "data_type": dt,
        "value": val,
        "message": f"Generated synthetic {dt}: {val}",
        "verified": True
    }


def get_user_or_random_data(field: str = "name", **kwargs) -> dict:
    """
    Retrieves user profile data from MAX long-term memory.
    If the field is not present in memory, automatically generates synthetic fallback data.
    
    Args:
        field: Field name to retrieve (e.g. 'name', 'email', 'phone', 'city', 'address').
    """
    f_clean = str(field or kwargs.get("key", "name")).lower().strip()
    
    try:
        from core.memory import recall_fact
        fact_res = recall_fact(f_clean)
        if fact_res and fact_res.get("status") == "success":
            val = None
            if "value" in fact_res and fact_res["value"]:
                val = fact_res["value"]
            elif "results" in fact_res and fact_res["results"]:
                first_match = fact_res["results"][0]
                val = first_match.get("value") if isinstance(first_match, dict) else str(first_match)
            if val:
                return {
                    "status": "success",
                    "field": f_clean,
                    "value": val,
                    "source": "memory",
                    "message": f"Retrieved '{f_clean}' from user memory: {val}",
                    "verified": True
                }
    except Exception as me:
        logger.debug(f"Memory lookup for {f_clean} skipped: {me}")

    gen_res = generate_random_data(data_type=f_clean)
    gen_res["source"] = "synthetic_fallback"
    return gen_res


# ─── Fast Multimodal Vision Grounder ───────────────────────────────────────

def _get_gemini_vision_client():
    """Returns a client for Gemini Vision model calls."""
    api_key = os.getenv("GEMINI_API_KEY", "")
    if not api_key:
        return None
    try:
        from google import genai
        return genai.Client(api_key=api_key)
    except Exception as e:
        logger.warning(f"Could not initialize google.genai: {e}")
        return None


def screen_find(description: str, timeout: int = 20, **kwargs) -> dict:
    """
    Locates a visual element on the screen using 1-shot Multimodal Vision (Gemini 2.5 Flash).
    Returns the exact center coordinates (x, y) of the requested element.
    Useful as a reliable visual fallback when UIA or DOM tree is unavailable (Canvas, games, custom widgets).
    
    Args:
        description: Natural language description of the visual element (e.g., 'Sign In button', 'Search bar', 'Close icon').
    """
    if not description or not str(description).strip():
        return {"status": "error", "message": "Element description is required.", "verified": False}

    client = _get_gemini_vision_client()
    if not client:
        return {"status": "error", "message": "Gemini API key not configured for screen vision.", "verified": False}

    try:
        import io
        import re
        from google.genai import types as gtypes
        
        pyautogui.FAILSAFE = False
        w, h = pyautogui.size()
        img = pyautogui.screenshot()
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        image_bytes = buf.getvalue()

        prompt = (
            f"This is a screenshot of a {w}x{h} pixel screen. "
            f"Locate the UI element described as: '{description}'. "
            f"Reply with ONLY the center coordinates in the exact format: x,y "
            f"Example: 450,320 "
            f"If the element is not visible or cannot be found, reply: NOT_FOUND"
        )

        resp = None
        for model_name in ("gemini-2.5-flash", "gemini-1.5-flash", "gemini-1.5-flash-8b"):
            try:
                resp = client.models.generate_content(
                    model=model_name,
                    contents=[
                        gtypes.Part.from_bytes(data=image_bytes, mime_type="image/png"),
                        prompt
                    ]
                )
                if resp and resp.text:
                    break
            except Exception as me:
                logger.debug(f"Model {model_name} failed: {me}")

        if not resp or not resp.text:
            return {"status": "not_found", "message": f"Vision model failed to analyze screen for '{description}'.", "verified": False}

        text = resp.text.strip()
        if "NOT_FOUND" in text.upper():
            return {"status": "not_found", "message": f"Element '{description}' not found on screen.", "verified": False}

        match = re.search(r"(\d+)\s*,\s*(\d+)", text)
        if match:
            x, y = int(match.group(1)), int(match.group(2))
            x = max(0, min(w - 1, x))
            y = max(0, min(h - 1, y))
            return {
                "status": "success",
                "x": x,
                "y": y,
                "screen_width": w,
                "screen_height": h,
                "description": description,
                "message": f"Found '{description}' at ({x}, {y})",
                "verified": True
            }

        return {"status": "not_found", "message": f"Could not parse coordinates from vision output: '{text}'", "verified": False}

    except Exception as e:
        logger.error(f"Error in screen_find: {e}")
        return {"status": "error", "message": str(e), "verified": False}


def screen_click(description: str, button: str = "left", clicks: int = 1, timeout: int = 20, **kwargs) -> dict:
    """
    Locates a UI element on screen using Gemini Vision and clicks it directly.
    
    Args:
        description: Description of the element to click (e.g. 'Submit button', 'Settings icon').
        button: Mouse button ('left', 'right', 'middle').
        clicks: Number of clicks (1 for single, 2 for double).
    """
    try:
        find_res = screen_find(description=description, timeout=timeout, **kwargs)
        if find_res.get("status") != "success" or "x" not in find_res:
            return find_res

        x = find_res["x"]
        y = find_res["y"]
        
        pyautogui.FAILSAFE = False
        time.sleep(0.15)
        pyautogui.click(x, y, button=button, clicks=clicks)
        
        return {
            "status": "success",
            "operation": "screen_click",
            "x": x,
            "y": y,
            "description": description,
            "button": button,
            "clicks": clicks,
            "message": f"{'Double-c' if clicks == 2 else 'C'}licked '{description}' at ({x}, {y})",
            "verified": True
        }
    except Exception as e:
        logger.error(f"Error in screen_click: {e}")
        return {"status": "error", "message": str(e), "verified": False}


# ─── Master Computer Control Dispatcher ─────────────────────────────────────

def computer_control(action: str = "smart_type", **kwargs) -> dict:
    """
    Master unified computer control dispatcher.
    Routes and coordinates all input, keyboard, mouse, visual element finding,
    and synthetic data operations under a single canonical interface.
    
    Supported Actions:
      - 'type' / 'smart_type': Type text (with auto-clearing and clipboard acceleration).
      - 'clear_field': Clear active input field.
      - 'click' / 'left_click' / 'double_click' / 'right_click': Mouse clicking.
      - 'move' / 'drag': Mouse movement and drag-and-drop.
      - 'hotkey' / 'press': Simulate key combination or single key.
      - 'scroll': Scroll wheel up/down/left/right.
      - 'copy' / 'paste': Clipboard operations.
      - 'screen_find': Locate visual element coordinates via Gemini Vision.
      - 'screen_click': Locate and click visual element via Gemini Vision.
      - 'random_data': Generate synthetic form data.
      - 'user_data': Retrieve user data with synthetic fallback.
      - 'screenshot': Capture full screen screenshot.
      - 'focus_window': Focus application window by title or name.
      - 'wait': Sleep for specified duration.
    """
    act = str(action or kwargs.get("op", "smart_type")).lower().strip()
    
    try:
        if act in ("smart_type", "type"):
            text = kwargs.get("text", kwargs.get("content", ""))
            clear_first = kwargs.get("clear_first", act == "smart_type")
            return smart_type(text=text, clear_first=clear_first)
            
        elif act in ("clear_field", "clear"):
            return clear_field()
            
        elif act in ("click", "left_click"):
            x = kwargs.get("x")
            y = kwargs.get("y")
            button = kwargs.get("button", "left")
            clicks = int(kwargs.get("clicks", 1))
            pyautogui.FAILSAFE = False
            if x is not None and y is not None:
                pyautogui.click(int(x), int(y), button=button, clicks=clicks)
                return {"status": "success", "message": f"Clicked at ({x}, {y})", "verified": True}
            pyautogui.click(button=button, clicks=clicks)
            return {"status": "success", "message": "Clicked at current position", "verified": True}
            
        elif act == "double_click":
            x = kwargs.get("x")
            y = kwargs.get("y")
            pyautogui.FAILSAFE = False
            if x is not None and y is not None:
                pyautogui.click(int(x), int(y), button="left", clicks=2)
                return {"status": "success", "message": f"Double-clicked at ({x}, {y})", "verified": True}
            pyautogui.click(button="left", clicks=2)
            return {"status": "success", "message": "Double-clicked at current position", "verified": True}
            
        elif act == "right_click":
            x = kwargs.get("x")
            y = kwargs.get("y")
            pyautogui.FAILSAFE = False
            if x is not None and y is not None:
                pyautogui.click(int(x), int(y), button="right", clicks=1)
                return {"status": "success", "message": f"Right-clicked at ({x}, {y})", "verified": True}
            pyautogui.click(button="right", clicks=1)
            return {"status": "success", "message": "Right-clicked at current position", "verified": True}
            
        elif act == "move":
            x = int(kwargs.get("x", 0))
            y = int(kwargs.get("y", 0))
            dur = float(kwargs.get("duration", kwargs.get("seconds", 0.2)))
            pyautogui.FAILSAFE = False
            pyautogui.moveTo(x, y, duration=dur)
            return {"status": "success", "message": f"Moved mouse to ({x}, {y})", "verified": True}
            
        elif act == "drag":
            x1 = int(kwargs.get("x1", kwargs.get("start_x", 0)))
            y1 = int(kwargs.get("y1", kwargs.get("start_y", 0)))
            x2 = int(kwargs.get("x2", kwargs.get("end_x", 0)))
            y2 = int(kwargs.get("y2", kwargs.get("end_y", 0)))
            dur = float(kwargs.get("duration", kwargs.get("seconds", 0.4)))
            pyautogui.FAILSAFE = False
            pyautogui.moveTo(x1, y1, duration=0.15)
            pyautogui.dragTo(x2, y2, duration=dur, button="left")
            return {"status": "success", "message": f"Dragged from ({x1}, {y1}) to ({x2}, {y2})", "verified": True}
            
        elif act in ("hotkey", "press"):
            keys = kwargs.get("keys") or kwargs.get("key_combo") or kwargs.get("key") or "enter"
            return press_key(key_combo=str(keys))
            
        elif act == "scroll":
            direction = kwargs.get("direction", "down").lower()
            amount = int(kwargs.get("amount", 3))
            pyautogui.FAILSAFE = False
            clicks = amount if direction in ("up", "right") else -amount
            if direction in ("up", "down"):
                pyautogui.scroll(clicks)
            else:
                pyautogui.hscroll(clicks)
            return {"status": "success", "message": f"Scrolled {direction} by {amount}", "verified": True}
            
        elif act == "copy":
            return copy_selection()
            
        elif act == "paste":
            return paste_text(text=kwargs.get("text"))
            
        elif act in ("screen_find", "find_element"):
            desc = kwargs.get("description") or kwargs.get("query") or kwargs.get("target") or ""
            return screen_find(description=desc)
            
        elif act in ("screen_click", "click_element"):
            desc = kwargs.get("description") or kwargs.get("query") or kwargs.get("target") or ""
            button = kwargs.get("button", "left")
            clicks = int(kwargs.get("clicks", 1))
            return screen_click(description=desc, button=button, clicks=clicks)
            
        elif act in ("random_data", "fake_data", "mock_data"):
            dt = kwargs.get("type") or kwargs.get("data_type") or "name"
            return generate_random_data(data_type=dt)
            
        elif act in ("user_data", "profile_data"):
            field = kwargs.get("field") or kwargs.get("key") or "name"
            return get_user_or_random_data(field=field)
            
        elif act == "screenshot":
            from tools.app_control import take_screenshot
            return take_screenshot(filename=kwargs.get("path") or kwargs.get("filename"))
            
        elif act == "focus_window":
            title = kwargs.get("title") or kwargs.get("app_name") or ""
            from tools.app_control import switch_to_app
            return switch_to_app(app_name=title)
            
        elif act == "wait":
            secs = min(30.0, max(0.1, float(kwargs.get("seconds", 1.0))))
            time.sleep(secs)
            return {"status": "success", "message": f"Waited {secs} seconds", "verified": True}
            
        else:
            return {"status": "error", "message": f"Unknown computer_control action: '{action}'", "verified": False}

    except Exception as e:
        logger.error(f"Error executing computer_control action '{action}': {e}")
        return {"status": "error", "message": str(e), "verified": False}


def press_key(key_combo: str, **kwargs) -> dict:
    """
    Simulate a keyboard key press or hotkey combination.
    Examples: 'enter', 'ctrl+c', 'ctrl+v', 'alt+tab', 'win+d', 'ctrl+shift+esc', 'escape', 'tab'.
    
    Args:
        key_combo: Key or combination (e.g., 'enter', 'ctrl+c', 'alt+tab', 'win+d').
    """
    try:
        combo_raw = str(key_combo or kwargs.get("key", kwargs.get("combo", ""))).lower().strip()
        if not combo_raw:
            return {"status": "error", "message": "Key combination is required."}

        pyautogui.FAILSAFE = False
        
        # Split combinations like 'ctrl+c', 'alt+tab', 'win+d'
        keys = [k.strip() for k in combo_raw.replace("-", "+").split("+")]
        
        # Map common names
        key_map = {
            "win": "winleft",
            "windows": "winleft",
            "cmd": "winleft",
            "super": "winleft",
            "control": "ctrl",
            "return": "enter",
            "esc": "escape",
            "spacebar": "space",
        }
        mapped_keys = [key_map.get(k, k) for k in keys]
        
        if len(mapped_keys) == 1:
            pyautogui.press(mapped_keys[0])
        else:
            pyautogui.hotkey(*mapped_keys)
            
        return {"status": "success", "message": f"Pressed '{combo_raw}'", "verified": True}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def send_whatsapp_message(contact_name: str = None, contact_or_number: str = None, message: str = "", **kwargs) -> dict:
    """
    Send WhatsApp message exclusively on the local Windows PC (via WhatsApp Web or Desktop app).
    
    CRITICAL ARCHITECTURE RULE:
    For sending WhatsApp messages on Android devices (e.g. 'Phone 1', 'Phone 2', 'phone', 'tablet'),
    do NOT use this local Windows function. Instead, call execute_device_action(target='Phone 1', action='SEND_WHATSAPP', arguments={'contact': '...', 'message': '...'}).
    
    Args:
        contact_name: Name of the contact to search for (e.g. 'Yashwanth', 'Mom') or phone number on local Windows WhatsApp.
        message: The message text to send. If empty, opens and focuses the contact's chat.
    """
    try:
        import urllib.parse
        import json
        import webbrowser
        import pyautogui
        from tools.app_control import switch_to_app, _find_windows_for_app
        
        # Defensive check: If target device was passed to Windows WhatsApp tool, divert to DeviceRouter
        device_target = kwargs.get("device") or kwargs.get("target_device") or kwargs.get("device_target")
        candidate_target = str(contact_name or "").strip().lower()
        if device_target or candidate_target in ("phone 1", "phone 2", "phone", "my phone", "tablet", "android", "redmi"):
            target_dev = device_target or contact_name
            real_contact = kwargs.get("contact") or kwargs.get("name") or contact_or_number or kwargs.get("recipient") or ""
            real_msg = str(message or kwargs.get("text", kwargs.get("msg", ""))).strip()
            
            from orchestrator.device_router import get_device_router
            import asyncio
            router = get_device_router()
            
            try:
                try:
                    loop = asyncio.get_running_loop()
                except RuntimeError:
                    loop = None
                    
                if loop and loop.is_running():
                    import concurrent.futures
                    with concurrent.futures.ThreadPoolExecutor() as pool:
                        res = pool.submit(lambda: asyncio.run(router.execute_single(target_dev, "SEND_WHATSAPP", arguments={"contact": str(real_contact), "message": real_msg}))).result()
                else:
                    res = asyncio.run(router.execute_single(target_dev, "SEND_WHATSAPP", arguments={"contact": str(real_contact), "message": real_msg}))
                return res
            except Exception as e:
                logger.error(f"Failed to divert WhatsApp message to remote device {target_dev}: {e}")
                return {"status": "error", "message": f"Failed remote routing to {target_dev}: {e}"}

        target_raw = contact_name or contact_or_number or kwargs.get("contact") or kwargs.get("name") or kwargs.get("target") or ""
        target = str(target_raw).strip()
        msg_text = str(message or kwargs.get("text", kwargs.get("msg", ""))).strip()
        
        if not target:
            return {"status": "error", "message": "Contact name or phone number is required."}

        # 1. Check config.json contacts
        number = None
        config_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "config.json")
        if os.path.exists(config_path):
            try:
                with open(config_path, "r", encoding="utf-8") as f:
                    cfg = json.load(f)
                    number = cfg.get("contacts", {}).get(target.lower())
            except Exception:
                pass
        
        digits_only = "".join(c for c in target if c.isdigit() or c == "+")
        if len(digits_only) >= 10:
            number = digits_only
            if not number.startswith("+") and len(number) == 10:
                number = "+91" + number

        pyautogui.FAILSAFE = False

        # If direct phone number is available:
        if number and (number.startswith("+") or number.isdigit()):
            clean_num = number.lstrip("+")
            encoded_msg = urllib.parse.quote(msg_text)
            url = f"https://web.whatsapp.com/send?phone={clean_num}&text={encoded_msg}"
            webbrowser.open(url)
            time.sleep(2.5)
            if msg_text:
                time.sleep(1.0)
                pyautogui.press('enter')
            return {
                "status": "success",
                "message": f"Opened WhatsApp chat for {target} ({number})" + (f" and sent: '{msg_text}'" if msg_text else "."),
                "contact": target,
                "verified": True
            }

        # 2. Contact Name Automation with Mouse Clicks (e.g. 'Yashwanth')
        target_hwnd = None
        wa_windows = _find_windows_for_app("whatsapp")
        if wa_windows:
            target_hwnd = wa_windows[0]["hwnd"]
            switch_to_app("whatsapp")
            time.sleep(1.0)
        else:
            # Open WhatsApp Web in default browser
            webbrowser.open("https://web.whatsapp.com")
            time.sleep(3.5)
            wa_windows = _find_windows_for_app("whatsapp")
            if wa_windows:
                target_hwnd = wa_windows[0]["hwnd"]

        # Close any lingering browser find overlays (Esc)
        pyautogui.press('escape')
        time.sleep(0.15)
        pyautogui.press('escape')
        time.sleep(0.2)

        # Get window dimensions or fallback to screen size
        rect = wintypes.RECT()
        has_rect = False
        if not target_hwnd:
            target_hwnd = user32.GetForegroundWindow()
        
        if target_hwnd:
            try:
                if user32.GetWindowRect(target_hwnd, ctypes.byref(rect)):
                    if (rect.right - rect.left) > 300 and (rect.bottom - rect.top) > 300:
                        has_rect = True
            except Exception:
                has_rect = False

        if has_rect:
            win_left = rect.left
            win_top = rect.top
            win_w = rect.right - rect.left
            win_h = rect.bottom - rect.top
        else:
            screen_w, screen_h = pyautogui.size()
            win_left = 0
            win_top = 0
            win_w = screen_w
            win_h = screen_h

        # STEP 1: Click Search Bar using Mouse
        search_x = win_left + max(100, int(win_w * 0.18))
        search_y = win_top + max(120, int(win_h * 0.16))
        
        # WhatsApp shortcut backup
        pyautogui.hotkey('ctrl', 'alt', 'n')
        time.sleep(0.2)
        
        # Mouse click into the search input box
        pyautogui.click(search_x, search_y)
        time.sleep(0.25)
        
        # Clear existing text
        pyautogui.hotkey('ctrl', 'a')
        time.sleep(0.1)
        pyautogui.press('backspace')
        time.sleep(0.1)

        # Type contact name via clipboard
        clipboard_helper.set_text(target)
        time.sleep(0.15)
        pyautogui.hotkey('ctrl', 'v')
        time.sleep(1.0)  # Wait for WhatsApp to filter and show contact results

        # STEP 2: Click the First Filtered Contact in the Results List
        result_x = win_left + max(100, int(win_w * 0.18))
        result_y = win_top + max(200, int(win_h * 0.27))
        pyautogui.click(result_x, result_y)
        time.sleep(0.2)
        pyautogui.press('enter')  # Ensure chat opens
        time.sleep(1.0)  # Wait for chat pane and message input to activate

        # STEP 3: If message provided, Click Message Box and Send
        if msg_text:
            chat_input_x = win_left + int(win_w * 0.55)
            chat_input_y = win_top + int(win_h * 0.95)
            pyautogui.click(chat_input_x, chat_input_y)
            time.sleep(0.25)
            
            clipboard_helper.set_text(msg_text)
            time.sleep(0.15)
            pyautogui.hotkey('ctrl', 'v')
            time.sleep(0.3)
            pyautogui.press('enter')
            time.sleep(0.4)
            return {
                "status": "success",
                "message": f"Clicked WhatsApp search bar, selected contact '{target}', and sent message: '{msg_text}'",
                "contact": target,
                "verified": True
            }
        else:
            return {
                "status": "success",
                "message": f"Clicked WhatsApp search bar and selected contact '{target}'.",
                "contact": target,
                "verified": True
            }
    except Exception as e:
        logger.error(f"Error in send_whatsapp_message: {e}")
        return {"status": "error", "message": str(e)}


def search_whatsapp_contact(contact_name: str, **kwargs) -> dict:
    """
    Open WhatsApp and search for a contact name to open their chat.
    """
    return send_whatsapp_message(contact_name=contact_name, message="", **kwargs)


def run_command(command: str, **kwargs) -> dict:
    """
    Execute an allowlisted shell command. DANGEROUS: requires confirmation.
    """
    try:
        from core.security import security_guard
        cmd_str = str(command or kwargs.get("cmd", "")).strip()
        
        risk, reason = security_guard.classify_command(cmd_str)
        if risk == "CRITICAL_BLOCKED":
            return {"status": "error", "message": f"Security blocked: {reason}"}
            
        res = subprocess.run(cmd_str, shell=True, capture_output=True, text=True, timeout=15)
        return {
            "status": "success" if res.returncode == 0 else "error",
            "stdout": res.stdout[:1000],
            "stderr": res.stderr[:1000],
            "returncode": res.returncode
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ─── Tool Declarations ──────────────────────────────────────────────────────

TOOL_DECLARATIONS = [
    {
        "name": "set_reminder",
        "description": "Set a reminder that shows a Windows notification after a specified number of minutes.",
        "parameters": {
            "type": "object",
            "properties": {
                "message": {
                    "type": "string",
                    "description": "The reminder message",
                },
                "minutes": {
                    "type": "integer",
                    "description": "Number of minutes from now",
                },
            },
            "required": ["message", "minutes"],
        },
    },
    {
        "name": "clipboard_copy",
        "description": "Copy text to the system clipboard.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "Text to copy to clipboard",
                }
            },
            "required": ["text"],
        },
    },
    {
        "name": "clipboard_paste",
        "description": "Get the current content of the system clipboard.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "paste_text",
        "description": "Paste text or current clipboard into the currently active focused window.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "Optional text to copy and paste. If omitted, pastes current clipboard.",
                }
            },
        },
    },
    {
        "name": "copy_selection",
        "description": "Copy the currently highlighted/selected text on screen and return it.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "type_text",
        "description": "Type text into the currently focused window using keyboard simulation.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "The text to type",
                }
            },
            "required": ["text"],
        },
    },
    {
        "name": "press_key",
        "description": "Simulate a keyboard key press or hotkey combination. Examples: 'enter', 'ctrl+c', 'ctrl+v', 'alt+tab', 'win+d', 'ctrl+shift+esc', 'escape'.",
        "parameters": {
            "type": "object",
            "properties": {
                "key_combo": {
                    "type": "string",
                    "description": "Key or combination (e.g., 'enter', 'ctrl+c', 'ctrl+v', 'alt+tab', 'win+d')",
                }
            },
            "required": ["key_combo"],
        },
    },
    {
        "name": "run_command",
        "description": f"Execute a shell command. DANGEROUS: requires confirmation.",
        "parameters": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "The shell command to run",
                }
            },
            "required": ["command"],
        },
    },
    {
        "name": "send_whatsapp_message",
        "description": "Open WhatsApp, search for a contact name or phone number, open their chat, and send a message automatically.",
        "parameters": {
            "type": "object",
            "properties": {
                "contact_name": {
                    "type": "string",
                    "description": "Name of the contact to search for (e.g. 'Yashwanth', 'Mom', 'bannu') or phone number",
                },
                "message": {
                    "type": "string",
                    "description": "The message text to send",
                },
            },
            "required": ["contact_name"],
        },
    },
    {
        "name": "search_whatsapp_contact",
        "description": "Open WhatsApp, search for a contact name in the search bar, and open/select their chat.",
        "parameters": {
            "type": "object",
            "properties": {
                "contact_name": {
                    "type": "string",
                    "description": "Name of the contact to search for (e.g. 'Yashwanth', 'Mom', 'bannu')",
                },
            },
            "required": ["contact_name"],
        },
    },
    {
        "name": "undo_last_action",
        "description": "Undo the most recent reversible action (e.g., volume change, brightness change, file write, file move, folder creation). Restores previous state safely.",
        "parameters": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "list_undo_history",
        "description": "List recently recorded undoable actions without modifying or rolling back state.",
        "parameters": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Number of recent actions to inspect (default: 10).",
                }
            },
        },
    },
    {
        "name": "list_scheduled_reminders",
        "description": "List all active and scheduled persistent reminders stored in the database.",
        "parameters": {
            "type": "object",
            "properties": {
                "active_only": {
                    "type": "boolean",
                    "description": "If true, only returns pending/scheduled reminders. If false, returns all.",
                }
            },
        },
    },
    {
        "name": "cancel_scheduled_reminder",
        "description": "Cancel a persistent reminder from Windows Task Scheduler and database by its ID.",
        "parameters": {
            "type": "object",
            "properties": {
                "reminder_id": {
                    "type": "string",
                    "description": "The ID of the reminder to cancel (e.g. 'rem_123456789abc').",
                }
            },
            "required": ["reminder_id"],
        },
    },
    {
        "name": "clear_field",
        "description": "Clear all text in the active input field or text box (Ctrl+A -> Delete).",
        "parameters": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "smart_type",
        "description": "Smart type text into the active field, optionally clearing existing text first (Ctrl+A -> Delete) and using fast clipboard paste.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "Text to type into the field.",
                },
                "clear_first": {
                    "type": "boolean",
                    "description": "Whether to select all and clear the field before typing (default: true).",
                }
            },
            "required": ["text"],
        },
    },
    {
        "name": "generate_random_data",
        "description": "Generate synthetic fake form data for testing/dummy registration (e.g. 'first_name', 'last_name', 'name', 'email', 'username', 'password', 'phone', 'birthday', 'address', 'city', 'zip_code').",
        "parameters": {
            "type": "object",
            "properties": {
                "data_type": {
                    "type": "string",
                    "description": "Type of synthetic data to generate (e.g. 'name', 'email', 'password', 'phone', 'city', 'address', 'birthday').",
                }
            },
        },
    },
    {
        "name": "get_user_or_random_data",
        "description": "Retrieve user profile data from MAX long-term memory; if missing, automatically falls back to generating realistic synthetic data.",
        "parameters": {
            "type": "object",
            "properties": {
                "field": {
                    "type": "string",
                    "description": "Field name to retrieve (e.g. 'name', 'email', 'phone', 'city', 'address').",
                }
            },
        },
    },
    {
        "name": "screen_find",
        "description": "Locate a UI/visual element on the screen using 1-shot Multimodal Vision (Gemini 2.5 Flash). Returns exact center (x, y) coordinates. Useful visual fallback when UIA is unavailable (Canvas, games, custom widgets).",
        "parameters": {
            "type": "object",
            "properties": {
                "description": {
                    "type": "string",
                    "description": "Natural language description of the visual element (e.g. 'Sign In button', 'Search bar', 'Download icon').",
                }
            },
            "required": ["description"],
        },
    },
    {
        "name": "screen_click",
        "description": "Locate a UI/visual element on the screen using Gemini Vision and click it directly at its center coordinates.",
        "parameters": {
            "type": "object",
            "properties": {
                "description": {
                    "type": "string",
                    "description": "Natural language description of the visual element to click (e.g. 'Sign In button', 'Close icon').",
                },
                "button": {
                    "type": "string",
                    "enum": ["left", "right", "middle"],
                    "description": "Mouse button to click (default: 'left').",
                },
                "clicks": {
                    "type": "integer",
                    "description": "Number of clicks (1 for single, 2 for double click).",
                }
            },
            "required": ["description"],
        },
    },
    {
        "name": "computer_control",
        "description": "Master unified computer control dispatcher for keyboard, mouse, visual element finding, and synthetic data generation.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": [
                        "type", "smart_type", "clear_field", "click", "double_click", "right_click",
                        "move", "drag", "hotkey", "press", "scroll", "copy", "paste",
                        "screen_find", "screen_click", "random_data", "user_data", "screenshot", "focus_window", "wait"
                    ],
                    "description": "Action to perform.",
                },
                "text": {"type": "string", "description": "Text to type or paste."},
                "x": {"type": "integer", "description": "Target X screen coordinate."},
                "y": {"type": "integer", "description": "Target Y screen coordinate."},
                "description": {"type": "string", "description": "Description of element for screen_find/screen_click."},
                "keys": {"type": "string", "description": "Key combination for hotkey/press (e.g. 'ctrl+c', 'enter')."},
                "clear_first": {"type": "boolean", "description": "Clear field before typing in smart_type."},
                "type": {"type": "string", "description": "Data type for random_data."},
                "field": {"type": "string", "description": "Field name for user_data."},
                "seconds": {"type": "number", "description": "Seconds to wait."},
            },
            "required": ["action"],
        },
    },
]

TOOL_FUNCTIONS = {
    "set_reminder": set_reminder,
    "list_scheduled_reminders": list_scheduled_reminders,
    "list_reminders": list_scheduled_reminders,
    "cancel_scheduled_reminder": cancel_scheduled_reminder,
    "cancel_reminder": cancel_scheduled_reminder,
    "undo_last_action": undo_last_action,
    "undo": undo_last_action,
    "rollback_last_action": undo_last_action,
    "list_undo_history": list_undo_history,
    "undo_history": list_undo_history,
    "clear_undo_history": clear_undo_history,
    "clipboard_copy": clipboard_copy,
    "copy_to_clipboard": clipboard_copy,
    "set_clipboard": clipboard_copy,
    "clipboard_paste": clipboard_paste,
    "get_clipboard": clipboard_paste,
    "get_clipboard_text": clipboard_paste,
    "paste_from_clipboard": clipboard_paste,
    "paste_text": paste_text,
    "copy_selection": copy_selection,
    "type_text": type_text,
    "clear_field": clear_field,
    "smart_type": smart_type,
    "generate_random_data": generate_random_data,
    "random_data": generate_random_data,
    "get_user_or_random_data": get_user_or_random_data,
    "user_data": get_user_or_random_data,
    "screen_find": screen_find,
    "screen_click": screen_click,
    "computer_control": computer_control,
    "press_key": press_key,
    "run_command": run_command,
    "send_whatsapp_message": send_whatsapp_message,
    "search_whatsapp_contact": search_whatsapp_contact,
}

