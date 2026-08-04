# Max AI Assistant

Max AI Assistant is a powerful, cross-device AI orchestration assistant that seamlessly bridges your **Android mobile device** with a **Windows desktop agent**. Powered by Google Gemini, Max allows you to control, monitor, and automate tasks on both your phone and your computer through voice, text, and automated routines.

---

## 📱 Project Components

### 1. Android Application (`/app`)
A native Android app built with **Kotlin** and **Jetpack Compose** that serves as the primary interface for the user.
* **Gemini Live Integration**: Implements a real-time WebSocket client connection for low-latency voice, audio, and vision processing.
* **Device Automation**: Executes local system actions like adjusting brightness, volume, DND settings, and managing Wi-Fi or Bluetooth.
* **Advanced Integrations**: Incorporates Shizuku shell service integration for elevated permission actions and system automation.
* **Built-in Utilities**: Includes structured tools for alarms, reminders, calendar syncing, period tracking, and saving memories.

### 2. Windows Agent (`/windows-agent` & `/max-windows-agent`)
A lightweight **Python** background agent running on your PC that executes tasks requested by the Android client or assistant engine.
* **Control Tools**: Terminal execution, process management, file operations, system info retrieval, and power commands (sleep, lock, shutdown).
* **Media & Productivity**: Screen capture capabilities, clipboard syncing, application launching, and system volume controls.
* **Web Dashboard**: Features a premium HTML/CSS/JS dashboard serving real-time logs, active device pairing, terminal sessions, and service settings.
* **Secure Transport**: Features a secure cert-based communication system using WebSockets for encrypted local networking.

---

## ⚡ Key Features

* **Cross-Device Coordination**: Request actions on your phone that seamlessly control or query your PC (e.g., *"Open VS Code on my PC"* or *"Check my PC's CPU usage"*).
* **Gemini-Powered Engine**: Direct tool call routing where Gemini decides whether to execute actions locally on the phone or forward them to the Windows Agent.
* **Voice & Vision Feedback**: Supports real-time audio streams, voice wave visualization, and screen reasoners to "see" your current workspace.

---

## 🚀 Setup & Installation

### Android App
1. Open the `/app` folder in **Android Studio**.
2. Create a `.env` file in the root directory and add your Gemini API Key:
   ```env
   GEMINI_API_KEY=your_api_key_here
   ```
3. Sync Gradle and build the application onto an emulator or physical device.

### Windows Agent
1. Navigate to the agent directory:
   ```bash
   cd windows-agent
   ```
2. Install the Python dependencies:
   ```bash
   pip install -r requirements.txt
   ```
3. Run the agent startup script:
   ```bash
   startup.bat
   ```
4. Access the web dashboard locally at `https://localhost:8000` (or the configured port) to pair with your Android device.

---

## 🤝 How It Works (Protocol)
The Android client and Windows agent communicate over a custom local WebSocket protocol. When Gemini classifies an intent that targets the computer (like looking up a file or taking a screenshot), the Android orchestrator routes the packet to the Windows agent connection, which processes it and responds with the execution results.
