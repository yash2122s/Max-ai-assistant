"""
JARVIS Productivity Tools
Reminders, clipboard, keyboard simulation, allowlisted shell commands.
"""

import os
import subprocess
import asyncio
import threading
import datetime
import pyautogui
import pyperclip

from config import ALLOWED_COMMANDS


# ─── Active Reminders (in-memory) ───────────────────────────────────────────
_active_reminders = []


def set_reminder(message: str, minutes: int) -> dict:
    """
    Set a reminder that will trigger after a specified number of minutes.
    Uses a background thread to wait, then shows a Windows notification.
    
    Args:
        message: The reminder message.
        minutes: Number of minutes from now.
    """
    try:
        minutes = max(1, int(minutes))
        trigger_time = datetime.datetime.now() + datetime.timedelta(minutes=minutes)
        
        def _remind():
            import time
            time.sleep(minutes * 60)
            # Show Windows toast notification
            try:
                ps_cmd = f"""
                [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
                $template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
                $text = $template.GetElementsByTagName('text')
                $text[0].AppendChild($template.CreateTextNode('JARVIS Reminder')) | Out-Null
                $text[1].AppendChild($template.CreateTextNode('{message}')) | Out-Null
                $notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('JARVIS')
                $notifier.Show([Windows.UI.Notifications.ToastNotification]::new($template))
                """
                subprocess.run(
                    ["powershell", "-Command", ps_cmd],
                    capture_output=True, timeout=10,
                )
            except Exception:
                # Fallback: simple message box
                subprocess.Popen(
                    ["powershell", "-Command", f'[System.Windows.MessageBox]::Show("{message}", "JARVIS Reminder")'],
                )
        
        thread = threading.Thread(target=_remind, daemon=True)
        thread.start()
        
        reminder_info = {
            "message": message,
            "trigger_at": trigger_time.strftime("%I:%M %p"),
            "minutes": minutes,
        }
        _active_reminders.append(reminder_info)
        
        return {
            "status": "success",
            "message": f"Reminder set for {minutes} minute(s) from now ({trigger_time.strftime('%I:%M %p')})",
            "reminder": message,
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def clipboard_copy(text: str) -> dict:
    """
    Copy text to the clipboard.
    
    Args:
        text: The text to copy.
    """
    try:
        pyperclip.copy(text)
        return {"status": "success", "message": "Text copied to clipboard"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def clipboard_paste() -> dict:
    """Get the current clipboard content."""
    try:
        content = pyperclip.paste()
        return {
            "status": "success",
            "content": content[:500],  # Limit output length
            "truncated": len(content) > 500,
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def type_text(text: str) -> dict:
    """
    Type text into the currently active window using keyboard simulation.
    Uses clipboard paste for fast, 100% reliable typing of all characters.
    
    Args:
        text: The text to type.
    """
    try:
        import time
        pyautogui.FAILSAFE = False
        time.sleep(0.8)  # Wait for window focus
        pyperclip.copy(text)
        time.sleep(0.2)
        pyautogui.hotkey('ctrl', 'v')
        return {"status": "success", "message": f"Typed '{text}' into focused window"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def send_whatsapp_message(contact_name: str = None, contact_or_number: str = None, message: str = "", **kwargs) -> dict:
    """
    Open WhatsApp Web to send a message. Accepts contact name (e.g. 'daddy', 'mom', 'bannu') or phone number.
    
    Args:
        contact_name: Name of contact or phone number.
        message: The message text to send.
    """
    try:
        import urllib.parse
        import json
        import webbrowser
        
        target_raw = contact_name or contact_or_number or kwargs.get("contact") or kwargs.get("name") or ""
        target = str(target_raw).lower().strip()
        number = None
        
        # 1. Check config.json contacts
        config_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "config.json")
        if os.path.exists(config_path):
            try:
                with open(config_path, "r", encoding="utf-8") as f:
                    cfg = json.load(f)
                    number = cfg.get("contacts", {}).get(target)
            except Exception:
                pass
        
        # 2. Check memory.json if not in config.json
        if not number:
            try:
                from core.memory import _load_memory
                memories = _load_memory()
                for k, item in memories.items():
                    if target in k or target in item.get("original_key", "").lower():
                        val = str(item.get("value", ""))
                        if any(char.isdigit() for char in val):
                            number = "".join(ch for ch in val if ch.isdigit() or ch == "+")
                            break
            except Exception:
                pass
        
        # 3. Check if input itself is a phone number
        if not number and any(char.isdigit() for char in target):
            number = "".join(ch for ch in target if ch.isdigit() or ch == "+")
        
        # If phone number resolved, use WA web phone link
        if number:
            encoded_msg = urllib.parse.quote(message)
            url = f"https://web.whatsapp.com/send?phone={number}&text={encoded_msg}"
            webbrowser.open(url)
            
            def _auto_press_enter():
                import time
                time.sleep(12)  # Wait for WA Web chat to load
                pyautogui.FAILSAFE = False
                pyautogui.press('enter')
            
            threading.Thread(target=_auto_press_enter, daemon=True).start()
            return {
                "status": "success",
                "message": f"Opened WhatsApp Web for {target_raw} ({number}) and sending message: '{message}'",
            }
        
        # Fallback to UI search if no phone number was found
        webbrowser.open("https://web.whatsapp.com/")
        def _automate_search():
            import time
            time.sleep(4.0)
            pyautogui.FAILSAFE = False
            pyautogui.hotkey('ctrl', 'alt', '/')
            time.sleep(0.5)
            pyautogui.hotkey('ctrl', 'f')
            time.sleep(0.5)
            pyperclip.copy(target_raw)
            pyautogui.hotkey('ctrl', 'v')
            time.sleep(1.0)
            pyautogui.press('down')
            time.sleep(0.3)
            pyautogui.press('enter')
            time.sleep(1.0)
            pyperclip.copy(message)
            pyautogui.hotkey('ctrl', 'v')
            time.sleep(0.5)
            pyautogui.press('enter')

        threading.Thread(target=_automate_search, daemon=True).start()
        return {
            "status": "success",
            "message": f"Opened WhatsApp Web, searching for '{target_raw}' to send: '{message}'",
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def press_key(key_combo: str) -> dict:
    """
    Simulate keyboard key press or hotkey combination.
    
    Args:
        key_combo: Key or combination (e.g., 'enter', 'ctrl+c', 'alt+tab', 'win+d', 'ctrl+shift+esc').
    """
    try:
        key_combo = key_combo.lower().strip()
        
        # Map common names
        key_map = {
            "win": "win",
            "windows": "win",
            "ctrl": "ctrl",
            "control": "ctrl",
            "alt": "alt",
            "shift": "shift",
            "tab": "tab",
            "enter": "enter",
            "return": "enter",
            "esc": "escape",
            "escape": "escape",
            "space": "space",
            "delete": "delete",
            "del": "delete",
            "backspace": "backspace",
            "up": "up",
            "down": "down",
            "left": "left",
            "right": "right",
            "home": "home",
            "end": "end",
            "pageup": "pageup",
            "pagedown": "pagedown",
            "f1": "f1", "f2": "f2", "f3": "f3", "f4": "f4",
            "f5": "f5", "f6": "f6", "f7": "f7", "f8": "f8",
            "f9": "f9", "f10": "f10", "f11": "f11", "f12": "f12",
            "printscreen": "printscreen",
            "prtsc": "printscreen",
        }
        
        # Parse combo (e.g., "ctrl+c" → ["ctrl", "c"])
        keys = [k.strip() for k in key_combo.split("+")]
        mapped_keys = [key_map.get(k, k) for k in keys]
        
        if len(mapped_keys) == 1:
            pyautogui.press(mapped_keys[0])
        else:
            pyautogui.hotkey(*mapped_keys)
        
        return {"status": "success", "message": f"Pressed {key_combo}"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def run_command(command: str) -> dict:
    """
    Execute an ALLOWLISTED shell command and return its output.
    ⚠️ DANGEROUS: Only allowlisted commands are permitted. Others are rejected.
    
    Args:
        command: The shell command to run (must be in the allowlist).
    """
    try:
        # Security: check against allowlist
        command_parts = command.strip().split()
        if not command_parts:
            return {"status": "error", "message": "Empty command"}
        
        base_command = command_parts[0].lower()
        
        # Check if the base command (first word) is in the allowlist
        if base_command not in ALLOWED_COMMANDS:
            return {
                "status": "error",
                "message": f"Command '{base_command}' is not in the allowlist. Allowed: {', '.join(sorted(ALLOWED_COMMANDS))}",
            }
        
        # Execute with timeout and output capture
        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
        
        output = result.stdout.strip() or result.stderr.strip()
        
        # Limit output size for voice response
        if len(output) > 2000:
            output = output[:2000] + "\n... (output truncated)"
        
        return {
            "status": "success" if result.returncode == 0 else "error",
            "output": output,
            "return_code": result.returncode,
        }
    except subprocess.TimeoutExpired:
        return {"status": "error", "message": "Command timed out after 30 seconds"}
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
        "description": "Simulate a keyboard key press or hotkey combination. Examples: 'enter', 'ctrl+c', 'alt+tab', 'win+d', 'ctrl+shift+esc'.",
        "parameters": {
            "type": "object",
            "properties": {
                "key_combo": {
                    "type": "string",
                    "description": "Key or combination (e.g., 'enter', 'ctrl+c', 'alt+tab', 'win+d')",
                }
            },
            "required": ["key_combo"],
        },
    },
    {
        "name": "run_command",
        "description": f"Execute a shell command (RESTRICTED to allowlisted commands only: {', '.join(sorted(ALLOWED_COMMANDS))}). DANGEROUS: requires confirmation.",
        "parameters": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "The shell command to run (must be allowlisted)",
                }
            },
            "required": ["command"],
        },
    },
    {
        "name": "send_whatsapp_message",
        "description": "Open WhatsApp, search for a contact name, and send a message text automatically.",
        "parameters": {
            "type": "object",
            "properties": {
                "contact_name": {
                    "type": "string",
                    "description": "Name of the contact to search for (e.g., 'bannu', 'Mom', 'John')",
                },
                "message": {
                    "type": "string",
                    "description": "The message text to send",
                },
            },
            "required": ["contact_name", "message"],
        },
    },
]

TOOL_FUNCTIONS = {
    "set_reminder": set_reminder,
    "clipboard_copy": clipboard_copy,
    "clipboard_paste": clipboard_paste,
    "type_text": type_text,
    "press_key": press_key,
    "run_command": run_command,
    "send_whatsapp_message": send_whatsapp_message,
}
