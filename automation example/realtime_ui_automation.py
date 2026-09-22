import os
import sys
import time
import pyautogui
import pyperclip

if sys.platform == 'win32':
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

pyautogui.FAILSAFE = False

def run_realtime():
    print("=" * 65, flush=True)
    print("🚀 REAL-TIME OBSERVED UI AUTOMATION STARTED", flush=True)
    print("🎯 Target Contact : yaswanth", flush=True)
    print("💬 Message        : hello", flush=True)
    print("=" * 65, flush=True)

    # -------------------------------------------------------------
    # Step 1: Open / Focus WhatsApp Desktop Window
    # -------------------------------------------------------------
    print("[1/5] 📱 Launching & focusing WhatsApp window...", flush=True)
    os.system("start whatsapp://")
    time.sleep(3.5)

    # -------------------------------------------------------------
    # Step 2: Focus Search Bar with Ctrl+F & clear previous search
    # -------------------------------------------------------------
    print("[2/5] 🔍 Triggering Search Bar (Ctrl+F)...", flush=True)
    pyautogui.hotkey('ctrl', 'f')
    time.sleep(0.8)
    
    # Select all and clear in case something was in search
    pyautogui.hotkey('ctrl', 'a')
    pyautogui.press('backspace')
    time.sleep(0.3)

    # -------------------------------------------------------------
    # Step 3: Type contact name 'yaswanth' & wait for search results
    # -------------------------------------------------------------
    print("[3/5] ⌨️ Typing contact 'yaswanth' into search...", flush=True)
    pyperclip.copy("yaswanth")
    pyautogui.hotkey('ctrl', 'v')
    print("      ⏳ Waiting 2.0s for contact search list to index...", flush=True)
    time.sleep(2.0)

    # -------------------------------------------------------------
    # Step 4: Select top search result and open chat
    # -------------------------------------------------------------
    print("[4/5] 📂 Selecting 'yaswanth' chat from search results (Down Arrow + Enter)...", flush=True)
    pyautogui.press('down')
    time.sleep(0.4)
    pyautogui.press('enter')
    print("      ⏳ Waiting 1.5s for chat thread & message input to load...", flush=True)
    time.sleep(1.5)

    # -------------------------------------------------------------
    # Step 5: Type 'hello' in active message box
    # -------------------------------------------------------------
    print("[5/5] ✍️ Typing 'hello' into active chat message box...", flush=True)
    pyperclip.copy("hello")
    pyautogui.hotkey('ctrl', 'v')
    time.sleep(0.5)

    print("=" * 65, flush=True)
    print("🎉 REAL-TIME AUTOMATION SUCCESSFUL!", flush=True)
    print("✅ WhatsApp లో 'yaswanth' చాట్ ఓపెన్ చేసి 'hello' మెసేజ్ టైప్ చేయడం జరిగింది!", flush=True)
    print("=" * 65, flush=True)

if __name__ == "__main__":
    run_realtime()
