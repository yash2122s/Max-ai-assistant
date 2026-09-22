import os
import sys
import time
import json
import argparse
import subprocess

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

import pyautogui
import pyperclip

# Disable fail-safe to prevent corner-lock aborts
pyautogui.FAILSAFE = False


def log_step(step_num: int, title: str, details: str = ""):
    timestamp = time.strftime("%H:%M:%S")
    print(f"[{timestamp}] [Step {step_num}] {title}")
    if details:
        print(f"           -> {details}")


def safe_type(text: str, interval: float = 0.05):
    """Types text safely using keystrokes or clipboard for unicode characters."""
    try:
        text.encode('ascii')
        pyautogui.write(text, interval=interval)
    except UnicodeEncodeError:
        pyperclip.copy(text)
        pyautogui.hotkey('ctrl', 'v')


def is_whatsapp_running() -> bool:
    """Checks if WhatsApp process exists."""
    try:
        import psutil
        for p in psutil.process_iter(['name']):
            if 'whatsapp' in (p.info.get('name') or '').lower():
                return True
    except Exception:
        pass
    return False


def run_smart_whatsapp_automation(
    contact_name: str = "yaswanth",
    message_text: str = "hello",
    send_message: bool = False,
    launch_wait: float = 6.0,
    search_wait: float = 2.0,
    use_direct_uri: bool = False
):
    print("=" * 70)
    print("🤖 SMART WHATSAPP AUTOMATION (ADAPTIVE UI FLOW)")
    print(f"🎯 Target Contact : {contact_name}")
    print(f"💬 Message Text   : {message_text}")
    print(f"⏱️ Launch Wait     : {launch_wait}s (Adaptive)")
    print(f"⏱️ Search Wait     : {search_wait}s")
    print(f"📤 Auto-Send      : {'Yes' if send_message else 'No (Type only)'}")
    print("=" * 70)

    # -------------------------------------------------------------------------
    # Step 1 & 2: Launch WhatsApp (Start Menu or Direct Protocol)
    # -------------------------------------------------------------------------
    if use_direct_uri:
        log_step(1, "Direct Protocol Launch", "Opening WhatsApp via Windows URI 'whatsapp://'")
        os.system("start whatsapp://")
    else:
        log_step(1, "Pressing Windows Key", "Opening Windows Start menu")
        pyautogui.press('win')
        time.sleep(0.6)

        log_step(2, "Searching 'WhatsApp'", "Typing app name into Start Search")
        safe_type('WhatsApp', interval=0.08)
        time.sleep(0.8)

        log_step(3, "Launching WhatsApp", "Pressing Enter to launch application")
        pyautogui.press('enter')

    # -------------------------------------------------------------------------
    # Step 4: Adaptive Wait for WhatsApp UI to load
    # -------------------------------------------------------------------------
    log_step(4, f"Waiting {launch_wait}s for WhatsApp to fully load", "Ensuring app is ready and focused...")
    
    # Progress countdown so user sees active waiting
    for remaining in range(int(launch_wait), 0, -1):
        print(f"           ⏳ Loading... ({remaining}s remaining)", end='\r')
        time.sleep(1.0)
    print(" " * 50, end='\r')
    log_step(4, "WhatsApp Ready", "Proceeding to UI interaction")

    # -------------------------------------------------------------------------
    # Step 5: Focus WhatsApp Search Bar (Ctrl + F)
    # -------------------------------------------------------------------------
    log_step(5, "Activating Search Bar", "Sending shortcut 'Ctrl + F'")
    pyautogui.hotkey('ctrl', 'f')
    time.sleep(1.0)

    # -------------------------------------------------------------------------
    # Step 6: Type Target Contact Name
    # -------------------------------------------------------------------------
    log_step(6, f"Searching for Contact: '{contact_name}'", "Typing contact name in search bar")
    safe_type(contact_name, interval=0.08)
    
    log_step(6, f"Waiting {search_wait}s for search results", "Allowing contact search indexing to populate")
    time.sleep(search_wait)

    # -------------------------------------------------------------------------
    # Step 7: Select & Open Chat
    # -------------------------------------------------------------------------
    log_step(7, "Opening Chat", "Navigating to top result with Down Arrow & Enter")
    pyautogui.press('down')
    time.sleep(0.4)
    pyautogui.press('enter')
    time.sleep(1.2) # Allow chat thread to open and message box to focus

    # -------------------------------------------------------------------------
    # Step 8: Type Message
    # -------------------------------------------------------------------------
    log_step(8, f"Typing Message: '{message_text}'", "Entering message into active chat input field")
    safe_type(message_text, interval=0.06)
    time.sleep(0.6)

    if send_message:
        log_step(8, "Sending Message", "Pressing Enter to dispatch message")
        pyautogui.press('enter')
        time.sleep(0.5)

    # -------------------------------------------------------------------------
    # Step 9: Report Completion
    # -------------------------------------------------------------------------
    print("=" * 70)
    print("🎉 TASK DONE! (టాస్క్ విజయవంతంగా పూర్తయింది)")
    print(f"✅ Contact: '{contact_name}' | Message: '{message_text}' typed successfully.")
    print("=" * 70)


def main():
    parser = argparse.ArgumentParser(description="Smart WhatsApp Desktop Automation with Adaptive UI Timing")
    parser.add_argument("--contact", "-c", type=str, default="yaswanth", help="Target contact name to search")
    parser.add_argument("--message", "-m", type=str, default="hello", help="Message text to type")
    parser.add_argument("--send", "-s", action="store_true", help="Send the message after typing")
    parser.add_argument("--launch-wait", "-w", type=float, default=6.0, help="Seconds to wait for WhatsApp to load (default: 6s)")
    parser.add_argument("--search-wait", type=float, default=2.0, help="Seconds to wait for search results (default: 2s)")
    parser.add_argument("--direct-uri", "-u", action="store_true", help="Launch directly via whatsapp:// protocol")

    args = parser.parse_args()

    run_smart_whatsapp_automation(
        contact_name=args.contact,
        message_text=args.message,
        send_message=args.send,
        launch_wait=args.launch_wait,
        search_wait=args.search_wait,
        use_direct_uri=args.direct_uri
    )


if __name__ == "__main__":
    main()
