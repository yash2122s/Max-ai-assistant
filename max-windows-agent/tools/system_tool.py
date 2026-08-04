import ctypes
import os
import time
import base64
import io
import logging
from PIL import ImageGrab, Image

logger = logging.getLogger("MAXWindowsAgent.System")

# Win32 constants
SW_MINIMIZE = 6
SW_MAXIMIZE = 3
SW_RESTORE = 9
WM_CLOSE = 0x0010
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
MAX_PATH = 260

class SystemHelper:
    def __init__(self):
        self.kernel32 = ctypes.windll.kernel32
        self.user32 = ctypes.windll.user32
        
        # Explicitly define argtypes and restypes for user32/kernel32 to prevent 64-bit handle truncation
        self.user32.IsWindowVisible.argtypes = [ctypes.c_void_p]
        self.user32.IsWindowVisible.restype = ctypes.c_bool

        self.user32.GetWindowTextLengthW.argtypes = [ctypes.c_void_p]
        self.user32.GetWindowTextLengthW.restype = ctypes.c_int

        self.user32.GetWindowTextW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_int]
        self.user32.GetWindowTextW.restype = ctypes.c_int

        self.user32.GetWindowThreadProcessId.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong)]
        self.user32.GetWindowThreadProcessId.restype = ctypes.c_ulong

        self.user32.GetForegroundWindow.argtypes = []
        self.user32.GetForegroundWindow.restype = ctypes.c_void_p

        self.user32.ShowWindow.argtypes = [ctypes.c_void_p, ctypes.c_int]
        self.user32.ShowWindow.restype = ctypes.c_bool

        self.user32.SetForegroundWindow.argtypes = [ctypes.c_void_p]
        self.user32.SetForegroundWindow.restype = ctypes.c_bool

        self.user32.PostMessageW.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_void_p, ctypes.c_void_p]
        self.user32.PostMessageW.restype = ctypes.c_bool

        self.user32.GetSystemMetrics.argtypes = [ctypes.c_int]
        self.user32.GetSystemMetrics.restype = ctypes.c_int

        EnumWindowsProc = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
        self.user32.EnumWindows.argtypes = [EnumWindowsProc, ctypes.c_void_p]
        self.user32.EnumWindows.restype = ctypes.c_bool


    def get_process_name(self, pid: int) -> str:
        h_process = self.kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if h_process:
            try:
                size = ctypes.c_ulong(MAX_PATH)
                buff = ctypes.create_unicode_buffer(MAX_PATH)
                if self.kernel32.QueryFullProcessImageNameW(h_process, 0, buff, ctypes.byref(size)):
                    return os.path.basename(buff.value)
            except Exception as e:
                logger.warning(f"Error querying process image name for PID {pid}: {e}")
            finally:
                self.kernel32.CloseHandle(h_process)
        return "unknown"

    def list_windows(self) -> list:
        windows = []
        
        # EnumWindows callback definition
        EnumWindowsProc = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
        
        def foreach_window(hwnd, lParam):
            if self.user32.IsWindowVisible(hwnd):
                length = self.user32.GetWindowTextLengthW(hwnd)
                if length > 0:
                    buff = ctypes.create_unicode_buffer(length + 1)
                    self.user32.GetWindowTextW(hwnd, buff, length + 1)
                    title = buff.value
                    
                    pid = ctypes.c_ulong()
                    self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
                    
                    process_name = self.get_process_name(pid.value)
                    
                    windows.append({
                        "hwnd": hwnd,
                        "title": title,
                        "process_name": process_name,
                        "pid": pid.value,
                        "is_visible": True
                    })
            return True

        cb = EnumWindowsProc(foreach_window)
        self.user32.EnumWindows(cb, 0)
        return windows


    def find_window(self, target_name: str) -> int:
        """
        Locates a window handle by fuzzy matching target_name against process name or window title.
        Returns 0 if not found.
        """
        windows = self.list_windows()
        target_lower = target_name.lower().strip()
        tokens = [t for t in target_lower.split() if t]
        
        # 1. Exact process name match
        for w in windows:
            p_name = w["process_name"].lower()
            if p_name == target_lower or p_name == f"{target_lower}.exe":
                return w["hwnd"]
                
        # 2. Window title contains search query
        for w in windows:
            if target_lower in w["title"].lower():
                return w["hwnd"]

        # 3. Process name contains search query
        for w in windows:
            if target_lower in w["process_name"].lower():
                return w["hwnd"]

        # 4. All token keywords match inside title or process name
        if len(tokens) > 1:
            for w in windows:
                combined = f"{w['title']} {w['process_name']}".lower()
                if all(t in combined for t in tokens):
                    return w["hwnd"]

        return 0

    def copy_image_to_clipboard(self, image) -> bool:
        """
        Copies a PIL Image object directly to the Windows Clipboard in DIB format.
        """
        try:
            output = io.BytesIO()
            image.convert("RGB").save(output, "BMP")
            data = output.getvalue()[14:]  # Strip 14-byte BMP header for CF_DIB
            output.close()

            CF_DIB = 8
            self.user32.OpenClipboard(None)
            self.user32.EmptyClipboard()
            
            # Allocate global memory (GMEM_MOVEABLE = 0x0002)
            h_mem = self.kernel32.GlobalAlloc(0x0002, len(data))
            p_mem = self.kernel32.GlobalLock(h_mem)
            ctypes.memmove(p_mem, data, len(data))
            self.kernel32.GlobalUnlock(h_mem)
            
            self.user32.SetClipboardData(CF_DIB, h_mem)
            self.user32.CloseClipboard()
            return True
        except Exception as e:
            logger.error(f"Failed to copy image to clipboard: {e}")
            try:
                self.user32.CloseClipboard()
            except Exception:
                pass
            return False

    def get_active_window(self) -> dict:
        hwnd = self.user32.GetForegroundWindow()
        if hwnd:
            length = self.user32.GetWindowTextLengthW(hwnd)
            buff = ctypes.create_unicode_buffer(length + 1)
            self.user32.GetWindowTextW(hwnd, buff, length + 1)
            title = buff.value
            
            pid = ctypes.c_ulong()
            self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            process_name = self.get_process_name(pid.value)
            
            return {
                "title": title,
                "process_name": process_name,
                "hwnd": hwnd
            }
        return {"title": "unknown", "process_name": "unknown", "hwnd": 0}

    def capture_screenshot(self, quality: int = 85, scale: float = 0.7, save_to_pc: bool = False, copy_to_clipboard: bool = False) -> dict:
        """
        Captures the primary monitor using Pillow, compresses, and returns base64 + metadata.
        Optionally saves to PC Pictures folder and/or copies to Windows Clipboard.
        """
        start_time = time.time()
        saved_pc_path = None
        copied_cb = False
        
        try:
            # Capture primary screen in-memory, fall back to mock image if headless
            try:
                orig_img = ImageGrab.grab()
            except OSError as oe:
                logger.warning(f"Headless session detected: {oe}. Using diagnostic mock screen image.")
                orig_img = Image.new("RGB", (1280, 720), color=(40, 44, 52))
                
            orig_width, orig_height = orig_img.width, orig_img.height
            
            # Save to PC Pictures directory if requested
            if save_to_pc:
                try:
                    pictures_dir = os.path.join(os.path.expanduser("~"), "Pictures", "MAX Screenshots")
                    os.makedirs(pictures_dir, exist_ok=True)
                    timestamp_str = time.strftime("%Y%m%d_%H%M%S")
                    saved_pc_path = os.path.join(pictures_dir, f"MAX_Screenshot_{timestamp_str}.png")
                    orig_img.save(saved_pc_path, format="PNG")
                    logger.info(f"Saved screenshot to PC: {saved_pc_path}")
                except Exception as e:
                    logger.error(f"Failed to save screenshot to PC: {e}")

            # Copy to Windows Clipboard if requested
            if copy_to_clipboard:
                copied_cb = self.copy_image_to_clipboard(orig_img)

            # Resize if scale < 1.0 for network transmission
            img = orig_img
            if scale < 1.0:
                width = int(orig_width * scale)
                height = int(orig_height * scale)
                img = orig_img.resize((width, height), Image.Resampling.LANCZOS)
                
            # Compress and encode to base64
            buffer = io.BytesIO()
            img.save(buffer, format="JPEG", quality=quality)
            encoded_string = base64.b64encode(buffer.getvalue()).decode("utf-8")
            
            # Fetch active window metadata
            active_win = self.get_active_window()
            capture_duration = int((time.time() - start_time) * 1000)
            
            # Count monitors (fallback to 1 if user32 EnumDisplayMonitors details is complex)
            screen_count = ctypes.windll.user32.GetSystemMetrics(80) # SM_CMONITORS = 80
            if screen_count <= 0:
                screen_count = 1

            return {
                "mime_type": "image/jpeg",
                "base64_data": encoded_string,
                "metadata": {
                    "resolution": {
                        "width": orig_width,
                        "height": orig_height
                    },
                    "capture_time_ms": capture_duration,
                    "screen_count": screen_count,
                    "active_window": active_win,
                    "saved_to_pc_path": saved_pc_path,
                    "copied_to_clipboard": copied_cb
                }
            }
        except Exception as e:
            logger.error(f"Failed to capture screen: {e}")
            raise e

    def get_system_metrics(self) -> dict:
        import subprocess
        cpu = 0.0
        ram = 0.0
        battery = 100
        charging = True
        try:
            # CPU load via PowerShell CIM instance query (returns percentage)
            res = subprocess.run(["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).LoadPercentage"], capture_output=True, text=True)
            if res.returncode == 0 and res.stdout.strip():
                cpu = float(res.stdout.strip().split()[0])
        except Exception as e:
            logger.debug(f"Failed to get CPU metrics: {e}")
        try:
            # RAM usage percentage calculation
            res = subprocess.run(["powershell", "-NoProfile", "-Command", "$m = Get-CimInstance Win32_OperatingSystem; [round](($m.TotalVisibleMemorySize - $m.FreePhysicalMemory) / $m.TotalVisibleMemorySize * 100, 1)"], capture_output=True, text=True)
            if res.returncode == 0 and res.stdout.strip():
                ram = float(res.stdout.strip().split()[0])
        except Exception as e:
            logger.debug(f"Failed to get RAM metrics: {e}")
        try:
            # Battery state metrics
            res_b = subprocess.run(["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Battery).EstimatedChargeRemaining"], capture_output=True, text=True)
            if res_b.returncode == 0 and res_b.stdout.strip():
                battery = int(res_b.stdout.strip().split()[0])
            res_c = subprocess.run(["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Battery).BatteryStatus"], capture_output=True, text=True)
            if res_c.returncode == 0 and res_c.stdout.strip():
                charging = int(res_c.stdout.strip().split()[0]) == 2
        except Exception as e:
            logger.debug(f"Failed to get Battery metrics: {e}")
            
        return {
            "cpu_percent": cpu,
            "ram_percent": ram,
            "battery_percent": battery,
            "is_charging": charging
        }

system_helper = SystemHelper()
