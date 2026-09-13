import pyaudio
import math
import struct
import time
import queue
import threading

pa = pyaudio.PyAudio()

print("Default output device info:")
def_dev = pa.get_default_output_device_info()
print(def_dev)

# Test 1: Open stream with channels=1, rate=24000
print("\nTest 1: Opening channels=1, rate=24000 on default output")
try:
    stream1 = pa.open(
        format=pyaudio.paInt16,
        channels=1,
        rate=24000,
        output=True,
        frames_per_buffer=1024,
    )
    print("Stream 1 opened successfully.")
    
    # Generate 1 second of audio at 24kHz mono
    samples = [int(32767 * 0.1 * math.sin(2 * math.pi * 440 * n / 24000)) for n in range(24000)]
    pcm = struct.pack(f"<{len(samples)}h", *samples)
    
    # Test different chunk sizes (including odd sizes, irregular sizes like Gemini sends)
    chunk_sizes = [512, 1024, 1200, 1920, 2048, 4096, 960, 333] # note 333 is odd bytes
    offset = 0
    for cs in chunk_sizes:
        if offset + cs > len(pcm):
            break
        chunk = pcm[offset:offset+cs]
        offset += cs
        try:
            # Check if chunk length is even (16-bit PCM = 2 bytes per sample)
            if len(chunk) % 2 != 0:
                print(f"Testing odd byte chunk ({len(chunk)} bytes) - should error in PortAudio:")
            stream1.write(chunk, exception_on_underflow=False)
            print(f"Wrote {len(chunk)} bytes. stream active={stream1.is_active()}, stopped={stream1.is_stopped()}")
        except Exception as e:
            print(f"Write failed on chunk {len(chunk)} bytes: {e} (active={stream1.is_active()}, stopped={stream1.is_stopped()})")
            
            # Try to write again to see if stream is closed
            try:
                stream1.write(pcm[0:1024], exception_on_underflow=False)
                print("Follow up write succeeded")
            except Exception as e2:
                print(f"Follow up write failed: {e2}")
            break
            
    stream1.stop_stream()
    stream1.close()
except Exception as e:
    print(f"Test 1 failed: {e}")

pa.terminate()
