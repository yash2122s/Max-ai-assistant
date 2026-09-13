import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pyaudio
import math
import struct
import time
import queue
import threading

from core.audio_handler import get_shared_pyaudio, AudioCapture, AudioPlayer

print("Testing simultaneous AudioCapture and AudioPlayer...")
pa = get_shared_pyaudio()

cap = AudioCapture()
player = AudioPlayer()

import asyncio
loop = asyncio.new_event_loop()

# Start both
cap.start(loop)
player.start()

print("Both started. Generating 24kHz audio bursts...")
samples = [int(32767 * 0.1 * math.sin(2 * math.pi * 440 * n / 24000)) for n in range(24000 * 3)]
pcm = struct.pack(f"<{len(samples)}h", *samples)

# Send chunks in rapid bursts
for i in range(0, len(pcm), 1024):
    player.play_chunk(pcm[i:i+1024])
    time.sleep(0.01)

print("Waiting for playback...")
time.sleep(2.0)

print(f"Player running={player._running}, is_playing={player.is_playing}")
print(f"Stream is_active={player._stream.is_active() if player._stream else None}")

# Now test interrupt + resume + new chunks
print("Testing interrupt...")
player.interrupt()
time.sleep(0.1)

print("Testing resume + play new burst...")
player.resume()
for i in range(0, len(pcm), 1024):
    player.play_chunk(pcm[i:i+1024])
    time.sleep(0.01)

time.sleep(2.0)
print(f"Player running={player._running}, stream active={player._stream.is_active() if player._stream else None}")

cap.stop()
player.stop()
print("Test completed successfully.")
