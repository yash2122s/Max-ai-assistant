import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pyaudio
import time
import math
import struct
import threading
import queue

pa = pyaudio.PyAudio()

def get_best_output_device(pa: pyaudio.PyAudio):
    try:
        def_info = pa.get_default_output_device_info()
        print(f"Default output device: {def_info['name']} (index {def_info['index']})")
        return def_info['index']
    except Exception as e:
        print(f"No default output device: {e}")
    
    # Fallback search
    for i in range(pa.get_device_count()):
        try:
            info = pa.get_device_info_by_index(i)
            if info.get("maxOutputChannels", 0) > 0:
                print(f"Found output device [{i}]: {info.get('name')}")
                return i
        except Exception:
            pass
    return None

dev_idx = get_best_output_device(pa)
print(f"Selected output device: {dev_idx}")

# Test opening stream
stream = pa.open(
    format=pyaudio.paInt16,
    channels=1,
    rate=24000,
    output=True,
    output_device_index=dev_idx,
    frames_per_buffer=1024,
)
print(f"Stream opened: id={id(stream)}, active={stream.is_active()}, stopped={stream.is_stopped()}")

# Test writing 100 chunks with simulated pause / underrun
samples = [int(32767 * 0.1 * math.sin(2 * math.pi * 440 * n / 24000)) for n in range(24000)]
pcm = struct.pack(f"<{len(samples)}h", *samples)

for i in range(0, len(pcm), 1024):
    stream.write(pcm[i:i+1024], exception_on_underflow=False)

print(f"Stream state after write: active={stream.is_active()}, stopped={stream.is_stopped()}")
stream.stop_stream()
stream.close()
pa.terminate()
print("PyAudio test passed.")
