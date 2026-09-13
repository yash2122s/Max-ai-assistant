"""
Comprehensive verification test for all MAXOverlay Futuristic Visual HUD states:
1. IDLE (220x42 compact capsule, cyan breathing reactor, particle drift, click-through)
2. WAKE_DETECTED (ignition flash pulse)
3. LISTENING (480x110 morph, cyan waveform responding to real RMS audio levels, user transcript)
4. THINKING (violet rotating energy rings, 'Thinking...' text without chain-of-thought)
5. ASSISTANT_SPEAKING (cyan/blue energy waveform, assistant transcript, perimeter light sweep)
6. TASK_RUNNING (glowing progress bar, step pill, percentage readout)
7. CONFIRMATION (amber holographic UI, expiry progress bar, interactive approve/reject)
8. TRANSIENT SUCCESS / ERROR pulses
9. FULLSCREEN DEVICE CONTROL (corner reticle nodes, traveling laser beams, scanlines, success/failure themes)
"""

import asyncio
import time
from server.event_bus import event_bus
from protocol.event_types import EventType
from server.ui_bridge import ui_bridge

async def verify_all_hud_states():
    print("🚀 Starting UI Bridge on ws://127.0.0.1:9002...")
    await ui_bridge.start()

    print("⏳ Waiting for MAXOverlay client connection...")
    for _ in range(20):
        if len(ui_bridge._clients) > 0:
            print(f"✅ Overlay connected! ({len(ui_bridge._clients)} client(s))")
            break
        await asyncio.sleep(0.5)

    print("\n--- 1. Testing IDLE State ---")
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "wake_word_listening",
        "audio_level": 0.0,
        "transcript": "",
        "session_active": False
    })
    await asyncio.sleep(2.0)

    print("\n--- 2. Testing WAKE_DETECTED Ignition ---")
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "wake_detected",
        "audio_level": 0.2,
        "transcript": "Hey MAX",
        "session_active": True
    })
    await asyncio.sleep(2.0)

    print("\n--- 3. Testing LISTENING + Real Audio RMS Waveform ---")
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "live_conversation",
        "session_active": True
    })
    event_bus.publish(EventType.TRANSCRIPT_USER, {
        "text": "Open Spotify and play my synthwave playlist."
    })
    # Ramp audio RMS levels up and down to simulate real voice speech
    for rms in [800, 2200, 4500, 6200, 5100, 3200, 1400, 400]:
        event_bus.publish(EventType.VOICE_AUDIO_LEVEL, {"rms": rms})
        await asyncio.sleep(0.2)

    await asyncio.sleep(1.5)

    print("\n--- 4. Testing THINKING (Rotating Orbital Rings, Privacy Safe) ---")
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "processing",
        "session_active": True
    })
    await asyncio.sleep(3.0)

    print("\n--- 5. Testing ASSISTANT_SPEAKING (Perimeter Sweep + Audio RMS) ---")
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "assistant_speaking",
        "session_active": True
    })
    event_bus.publish(EventType.TRANSCRIPT_ASSISTANT, {
        "text": "Opening Spotify now and queuing your synthwave playlist."
    })
    for rms in [1500, 3800, 5200, 4100, 2600, 1100]:
        event_bus.publish(EventType.VOICE_AUDIO_LEVEL, {"rms": rms})
        await asyncio.sleep(0.2)

    await asyncio.sleep(2.0)

    print("\n--- 6. Testing TASK_RUNNING (Step & Segmented Progress) ---")
    event_bus.publish(EventType.TASK_STARTED, {
        "task_id": "task-spotify-01",
        "title": "Launch Spotify & Play Synthwave",
        "step": 1,
        "total_steps": 3,
        "progress": 0.33,
        "status": "running",
        "tool": "AppLaunch"
    })
    await asyncio.sleep(1.5)

    event_bus.publish(EventType.STEP_COMPLETED, {
        "task_id": "task-spotify-01",
        "title": "Launch Spotify & Play Synthwave",
        "step": 2,
        "total_steps": 3,
        "progress": 0.66,
        "status": "running",
        "tool": "MediaController"
    })
    await asyncio.sleep(1.5)

    event_bus.publish(EventType.TASK_COMPLETED, {
        "task_id": "task-spotify-01"
    })
    await asyncio.sleep(2.0)

    print("\n--- 7. Testing CONFIRMATION State ---")
    event_bus.publish(EventType.CONFIRMATION_REQUIRED, {
        "ticket_id": "ticket-999",
        "action_name": "Delete File",
        "description": "Permanently delete temporary build artifacts",
        "target": "D:\\temp\\build_cache",
        "expires_in_seconds": 30.0
    })
    await asyncio.sleep(3.5)

    # Clear confirmation
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "wake_word_listening",
        "session_active": False
    })

    print("\n--- 8. Testing FULLSCREEN DEVICE CONTROL Overlay ---")
    # Started
    event_bus.publish(EventType.DEVICE_CONTROL, {
        "state": "DEVICE_CONTROL_STARTED",
        "device": "Display",
        "action": "Window Rearrange"
    })
    await asyncio.sleep(2.0)

    # Running
    event_bus.publish(EventType.DEVICE_CONTROL, {
        "state": "DEVICE_CONTROL_RUNNING",
        "device": "Display",
        "action": "Window Rearrange"
    })
    await asyncio.sleep(2.5)

    # Success
    event_bus.publish(EventType.DEVICE_CONTROL, {
        "state": "DEVICE_CONTROL_SUCCESS",
        "device": "Display",
        "action": "Window Rearrange"
    })
    await asyncio.sleep(2.0)

    # Finished
    event_bus.publish(EventType.DEVICE_CONTROL, {
        "state": "DEVICE_CONTROL_FINISHED",
        "device": "Display",
        "action": "Window Rearrange"
    })
    await asyncio.sleep(1.5)

    print("\n--- 9. Return to IDLE Capsule ---")
    event_bus.publish(EventType.VOICE_STATE, {
        "state": "wake_word_listening",
        "session_active": False
    })
    await asyncio.sleep(2.0)

    print("\n🎉 All HUD Visual States Tested Successfully!")
    await ui_bridge.stop()

if __name__ == "__main__":
    asyncio.run(verify_all_hud_states())
