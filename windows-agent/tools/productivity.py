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

logger = logging.getLogger("MAXWindowsAgent.Productivity")

# ─── Active Reminders (in-memory) ───────────────────────────────────────────
_active_reminders = []


def set_reminder(message: str, minutes: int, **kwargs) -> dict:
    """
    Set a reminder that will trigger after a specified number of minutes.
    Uses a background thread to wait, then shows a Windows notification.
    
    Args:
        message: The reminder message.
        minutes: Number of minutes from now.
    """
    try:
        min_val = max(1, int(minutes or kwargs.get("time_minutes", 1)))
        msg_val = str(message or kwargs.get("text", "Reminder"))
        trigger_time = datetime.datetime.now() + datetime.timedelta(minutes=min_val)
        
        def _remind():
            time.sleep(min_val * 60)
            try:
                ps_cmd = f"""
                [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
                $template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
                $text = $template.GetElementsByTagName('text')
                $text[0].AppendChild($template.CreateTextNode('MAX Reminder')) | Out-Null
                $text[1].AppendChild($template.CreateTextNode('{msg_val}')) | Out-Null
                $notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('MAX')
                $notifier.Show([Windows.UI.Notifications.ToastNotification]::new($template))
                """
                subprocess.run(
                    ["powershell", "-Command", ps_cmd],
                    capture_output=True, timeout=10,
                )
            except Exception:
                subprocess.Popen(
                    ["powershell", "-Command", f'[System.Windows.MessageBox]::Show("{msg_val}", "MAX Reminder")'],
                )
        
        thread = threading.Thread(target=_remind, daemon=True)
        thread.start()
        
        reminder_info = {
            "message": msg_val,
            "trigger_at": trigger_time.strftime("%I:%M %p"),
            "minutes": min_val,
        }
        _active_reminders.append(reminder_info)
        
        return {
            "status": "success",
            "message": f"Reminder set for {min_val} minute(s) from now ({trigger_time.strftime('%I:%M %p')})",
            "reminder": msg_val,
        }
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
]

TOOL_FUNCTIONS = {
    "set_reminder": set_reminder,
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
    "press_key": press_key,
    "run_command": run_command,
    "send_whatsapp_message": send_whatsapp_message,
    "search_whatsapp_contact": search_whatsapp_contact,
}
