import ctypes
import logging
import time
import subprocess
from ctypes import wintypes
from .base_tool import BaseTool

logger = logging.getLogger("MAXWindowsAgent.Input")

VK_MAP = {
    "enter": 0x0D, "return": 0x0D, "tab": 0x09, "escape": 0x1B,
    "backspace": 0x08, "delete": 0x2E, "space": 0x20,
    "up": 0x26, "down": 0x28, "left": 0x25, "right": 0x27,
    "home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22,
    "insert": 0x2D, "printscreen": 0x2C, "scrolllock": 0x91, "pause": 0x13,
    "capslock": 0x14, "numlock": 0x90,
    "f1": 0x70, "f2": 0x71, "f3": 0x72, "f4": 0x73,
    "f5": 0x74, "f6": 0x75, "f7": 0x76, "f8": 0x77,
    "f9": 0x78, "f10": 0x79, "f11": 0x7A, "f12": 0x7B,
    "f13": 0x7C, "f14": 0x7D, "f15": 0x7E, "f16": 0x7F,
    "f17": 0x80, "f18": 0x81, "f19": 0x82, "f20": 0x83,
    "f21": 0x84, "f22": 0x85, "f23": 0x86, "f24": 0x87,
    "0": 0x30, "1": 0x31, "2": 0x32, "3": 0x33, "4": 0x34,
    "5": 0x35, "6": 0x36, "7": 0x37, "8": 0x38, "9": 0x39,
    "a": 0x41, "b": 0x42, "c": 0x43, "d": 0x44, "e": 0x45,
    "f": 0x46, "g": 0x47, "h": 0x48, "i": 0x49, "j": 0x4A,
    "k": 0x4B, "l": 0x4C, "m": 0x4D, "n": 0x4E, "o": 0x4F,
    "p": 0x50, "q": 0x51, "r": 0x52, "s": 0x53, "t": 0x54,
    "u": 0x55, "v": 0x56, "w": 0x57, "x": 0x58, "y": 0x59, "z": 0x5A,
    "numpad0": 0x60, "numpad1": 0x61, "numpad2": 0x62, "numpad3": 0x63,
    "numpad4": 0x64, "numpad5": 0x65, "numpad6": 0x66, "numpad7": 0x67,
    "numpad8": 0x68, "numpad9": 0x69, "multiply": 0x6A, "add": 0x6B,
    "separator": 0x6C, "subtract": 0x6D, "decimal": 0x6E, "divide": 0x6F,
    "semicolon": 0xBA, "plus": 0xBB, "comma": 0xBC, "minus": 0xBD,
    "period": 0xBE, "slash": 0xBF, "backquote": 0xC0,
    "bracketleft": 0xDB, "backslash": 0xDC, "bracketright": 0xDD, "quote": 0xDE,
    "oem_8": 0xDF, "ico_help": 0xE1, "ico_00": 0xE4,
    "processkey": 0xE5, "attn": 0xF6, "crsel": 0xF7, "exsel": 0xF8,
    "lcontrol": 0xA2, "lctrl": 0xA2, "rcontrol": 0xA3, "rctrl": 0xA3,
    "lshift": 0xA0, "rshift": 0xA1, "lmenu": 0xA4, "lalt": 0xA4,
    "rmenu": 0xA5, "ralt": 0xA5, "lwin": 0x5B, "rwin": 0x5C,
    "apps": 0x5D, "sleep": 0x5F, "browser_back": 0xA6, "browser_forward": 0xA7,
    "browser_refresh": 0xA8, "browser_stop": 0xA9, "browser_search": 0xAA,
    "browser_favorites": 0xAB, "browser_home": 0xAC, "volume_mute": 0xAD,
    "volume_down": 0xAE, "volume_up": 0xAF,
    "media_next": 0xB0, "media_prev": 0xB1, "media_stop": 0xB2,
    "media_play_pause": 0xB3,
    "launch_mail": 0xB4, "launch_media": 0xB5, "launch_app1": 0xB6, "launch_app2": 0xB7,
}


class InputHelper:
    def __init__(self):
        self.user32 = ctypes.windll.user32

    def type_text(self, text: str, interval: float = 0.01) -> dict:
        try:
            for char in text:
                vk = VK_MAP.get(char.lower())
                if vk:
                    self.user32.keybd_event(vk, 0, 0, 0)
                    self.user32.keybd_event(vk, 0, 2, 0)
                else:
                    shift_state = char.isupper() or char in "~!@#$%^&*()_+{}|:\"<>?"
                    if shift_state:
                        self.user32.keybd_event(0xA0, 0, 0, 0)
                    ctypes.windll.user32.keybd_event(ord(char.upper()), 0, 0, 0)
                    ctypes.windll.user32.keybd_event(ord(char.upper()), 0, 2, 0)
                    if shift_state:
                        self.user32.keybd_event(0xA0, 0, 2, 0)
                if interval > 0:
                    time.sleep(interval)
            return {"status": "success", "message": f"Typed {len(text)} characters"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def press_key(self, key: str) -> dict:
        try:
            vk = VK_MAP.get(key.lower())
            if not vk:
                return {"status": "failed", "error": f"Unknown key: {key}"}
            self.user32.keybd_event(vk, 0, 0, 0)
            self.user32.keybd_event(vk, 0, 2, 0)
            return {"status": "success", "message": f"Key '{key}' pressed"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def key_combo(self, keys: list) -> dict:
        try:
            vk_codes = []
            for key in keys:
                vk = VK_MAP.get(key.lower())
                if not vk:
                    return {"status": "failed", "error": f"Unknown key in combo: {key}"}
                vk_codes.append(vk)

            for vk in vk_codes:
                self.user32.keybd_event(vk, 0, 0, 0)
                time.sleep(0.02)
            for vk in reversed(vk_codes):
                self.user32.keybd_event(vk, 0, 2, 0)
                time.sleep(0.02)

            return {"status": "success", "message": f"Key combo {keys} executed"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def move_mouse(self, x: int, y: int, absolute: bool = True) -> dict:
        try:
            if absolute:
                self.user32.SetCursorPos(x, y)
            else:
                current_x = wintypes.c_int()
                current_y = wintypes.c_int()
                self.user32.GetCursorPos(ctypes.byref(current_x), ctypes.byref(current_y))
                self.user32.SetCursorPos(current_x.value + x, current_y.value + y)
            return {"status": "success", "message": f"Mouse moved to ({x}, {y})"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def click_mouse(self, button: str = "left", double: bool = False) -> dict:
        try:
            MOUSEEVENTF_LEFTDOWN = 0x0002
            MOUSEEVENTF_LEFTUP = 0x0004
            MOUSEEVENTF_RIGHTDOWN = 0x0008
            MOUSEEVENTF_RIGHTUP = 0x0010
            MOUSEEVENTF_MIDDLEDOWN = 0x0020
            MOUSEEVENTF_MIDDLEUP = 0x0040

            if button.lower() in ("left", "l"):
                down, up = MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP
            elif button.lower() in ("right", "r"):
                down, up = MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP
            elif button.lower() in ("middle", "m", "mid"):
                down, up = MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP
            else:
                return {"status": "failed", "error": f"Unknown button: {button}"}

            for _ in range(2 if double else 1):
                self.user32.mouse_event(down, 0, 0, 0, 0)
                time.sleep(0.05)
                self.user32.mouse_event(up, 0, 0, 0, 0)
                time.sleep(0.05)

            action = "double-clicked" if double else "clicked"
            return {"status": "success", "message": f"Mouse {action} ({button} button)"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def scroll_mouse(self, amount: int = 1) -> dict:
        try:
            MOUSEEVENTF_WHEEL = 0x0800
            self.user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, amount * 120, 0)
            return {"status": "success", "message": f"Scrolled by {amount} clicks"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def get_cursor_pos(self) -> dict:
        try:
            x = wintypes.c_int()
            y = wintypes.c_int()
            self.user32.GetCursorPos(ctypes.byref(x), ctypes.byref(y))
            return {"x": x.value, "y": y.value}
        except Exception as e:
            return {"error": str(e)}

    def get_screen_size(self) -> dict:
        try:
            width = self.user32.GetSystemMetrics(0)
            height = self.user32.GetSystemMetrics(1)
            return {"width": width, "height": height}
        except Exception as e:
            return {"error": str(e)}

    def send_keys_via_powershell(self, text: str) -> dict:
        try:
            escaped = text.replace('"', '`"').replace("'", "''")
            ps_script = f'''
            Add-Type -AssemblyName System.Windows.Forms
            [System.Windows.Forms.SendKeys]::SendWait("{escaped}")
            '''
            subprocess.run(["powershell", "-NoProfile", "-Command", ps_script], capture_output=True, timeout=30)
            return {"status": "success", "message": f"Sent keys via SendKeys: {text[:50]}..."}
        except Exception as e:
            return {"status": "failed", "error": str(e)}


input_helper = InputHelper()


class InputTool(BaseTool):
    @property
    def name(self) -> str:
        return "input"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running input/{action}...")
        try:
            if action == "type":
                text = arguments.get("text", arguments.get("message", ""))
                interval = float(arguments.get("interval", 0.01))
                if not text:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'text' parameter"}}
                return input_helper.type_text(text, interval)
            elif action == "sendkeys":
                text = arguments.get("text", arguments.get("message", ""))
                if not text:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'text' parameter"}}
                return input_helper.send_keys_via_powershell(text)
            elif action == "press":
                key = arguments.get("key", arguments.get("name", ""))
                if not key:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'key' parameter"}}
                return input_helper.press_key(key)
            elif action == "combo":
                keys = arguments.get("keys", arguments.get("combo", []))
                if isinstance(keys, str):
                    keys = keys.split("+")
                if not keys:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'keys' parameter"}}
                return input_helper.key_combo(keys)
            elif action in ("hotkey", "shortcut"):
                keys = arguments.get("keys", arguments.get("combo", []))
                if isinstance(keys, str):
                    keys = keys.split("+")
                if not keys:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'keys' parameter"}}
                return input_helper.key_combo(keys)
            elif action == "mouse_move":
                x = int(arguments.get("x", 0))
                y = int(arguments.get("y", 0))
                absolute = arguments.get("absolute", True)
                if isinstance(absolute, str):
                    absolute = absolute.lower() == "true"
                return input_helper.move_mouse(x, y, absolute)
            elif action == "click":
                button = arguments.get("button", "left")
                double = arguments.get("double", False)
                if isinstance(double, str):
                    double = double.lower() == "true"
                return input_helper.click_mouse(button, double)
            elif action == "double_click":
                return input_helper.click_mouse("left", True)
            elif action == "right_click":
                return input_helper.click_mouse("right", False)
            elif action == "scroll":
                amount = int(arguments.get("amount", 1))
                return input_helper.scroll_mouse(amount)
            elif action == "cursor_pos":
                return {"status": "success", "output": input_helper.get_cursor_pos()}
            elif action == "screen_size":
                return {"status": "success", "output": input_helper.get_screen_size()}
            elif action == "drag":
                x1 = int(arguments.get("x1", arguments.get("from_x", 0)))
                y1 = int(arguments.get("y1", arguments.get("from_y", 0)))
                x2 = int(arguments.get("x2", arguments.get("to_x", 0)))
                y2 = int(arguments.get("y2", arguments.get("to_y", 0)))
                input_helper.move_mouse(x1, y1, True)
                time.sleep(0.1)
                MOUSEEVENTF_LEFTDOWN = 0x0002
                ctypes.windll.user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
                time.sleep(0.05)
                steps = 20
                for i in range(1, steps + 1):
                    cx = x1 + (x2 - x1) * i // steps
                    cy = y1 + (y2 - y1) * i // steps
                    ctypes.windll.user32.SetCursorPos(cx, cy)
                    time.sleep(0.01)
                MOUSEEVENTF_LEFTUP = 0x0004
                ctypes.windll.user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
                return {"status": "success", "message": f"Dragged from ({x1},{y1}) to ({x2},{y2})"}
            else:
                return {
                    "status": "failed",
                    "error": {"code": "UNSUPPORTED_ACTION", "message": f"Unknown input action: {action}"}
                }
        except Exception as e:
            logger.error(f"InputTool error: {e}", exc_info=True)
            return {"status": "failed", "error": {"code": "EXECUTION_EXCEPTION", "message": str(e)}}
