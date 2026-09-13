"""
Live End-to-End Test for MAXOverlay + Python UI Bridge
Starts the real UIBridge server on port 9002, lets the running MAXOverlay connect,
and plays a sequence of live state events to verify complete end-to-end functionality.
"""

import asyncio
import time
from server.event_bus import event_bus
from protocol.event_types import EventType
from server.ui_bridge import ui_bridge

async def run_live_simulation():
    print("🚀 Starting UI Bridge on ws://127.0.0.1:9002...")
    await ui_bridge.start()
    
    # Wait for MAXOverlay client to connect
    print("⏳ Waiting for MAXOverlay client connection...")
    for _ in range(20):
        if len(ui_bridge._clients) > 0:
            print(f"✅ Overlay connected! ({len(ui_bridge._clients)} client(s))")
            break
        await asyncio.sleep(0.5)
    
    # 1. Idle state
    print("📢 1. Publishing WAKE_WORD_LISTENING state...")
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "wake_word_listening",
        "audio_level": 0.0,
        "transcript": "",
        "session_active": False
    })
    await asyncio.sleep(2.0)

    # 2. Wake detected
    print("📢 2. Publishing WAKE_DETECTED state...")
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "wake_detected",
        "audio_level": 0.3,
        "transcript": "Hey MAX",
        "session_active": True
    })
    await asyncio.sleep(2.0)

    # 3. Live conversation + user speaking with audio levels
    print("📢 3. Publishing LIVE_CONVERSATION + user audio levels & transcript...")
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "live_conversation",
        "session_active": True
    })
    event_bus.publish(EventType.TRANSCRIPT_USER, {
        "text": "What is the current CPU usage and free disk space?"
    })
    for level in [1200, 3400, 5600, 4800, 2200, 800]:
        event_bus.publish(EventType.VOICE_AUDIO_LEVEL, {"rms": level})
        await asyncio.sleep(0.2)

    await asyncio.sleep(1.5)

    # 4. Processing / Thinking
    print("📢 4. Publishing PROCESSING state...")
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "processing",
        "session_active": True
    })
    await asyncio.sleep(2.0)

    # 5. Assistant speaking
    print("📢 5. Publishing ASSISTANT_SPEAKING state with transcript fragments...")
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "assistant_speaking",
        "session_active": True
    })
    for chunk in ["Your CPU usage is currently at 14%", ", and you have 185 GB of free space on drive D."]:
        event_bus.publish(EventType.TRANSCRIPT_ASSISTANT, {"text": chunk})
        for level in [2800, 4200, 3100, 1500]:
            event_bus.publish(EventType.VOICE_AUDIO_LEVEL, {"rms": level})
            await asyncio.sleep(0.2)

    await asyncio.sleep(2.0)

    # 6. Device control effect
    print("📢 6. Publishing DEVICE_CONTROL border effect...")
    event_bus.publish(EventType.DEVICE_CONTROL, {
        "state": "DEVICE_CONTROL_STARTED",
        "target": "Desktop",
        "action": "Window Management"
    })
    await asyncio.sleep(3.0)

    event_bus.publish(EventType.DEVICE_CONTROL, {
        "state": "DEVICE_CONTROL_FINISHED",
        "target": "Desktop",
        "action": "Window Management"
    })
    await asyncio.sleep(1.0)

    # 7. Task progress
    print("📢 7. Publishing OVERLAY_TASK_STATE...")
    event_bus.publish(EventType.TASK_STARTED, {
        "task_id": "task-001",
        "title": "Optimizing memory",
        "step": 1,
        "total_steps": 3,
        "progress": 0.33,
        "status": "running"
    })
    await asyncio.sleep(1.5)
    event_bus.publish(EventType.TASK_COMPLETED, {
        "task_id": "task-001"
    })
    await asyncio.sleep(1.0)

    # 8. Return to WakeWordListening
    print("📢 8. Returning to WAKE_WORD_LISTENING (compact island)...")
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "wake_word_listening",
        "session_active": False
    })
    await asyncio.sleep(2.0)

    print("🎉 Live simulation sequence finished successfully!")
    await ui_bridge.stop()

if __name__ == "__main__":
    asyncio.run(run_live_simulation())
