import pyaudio
import math
import struct
import time

pa = pyaudio.PyAudio()

print("--- HOST APIS ---")
for i in range(pa.get_host_api_count()):
    info = pa.get_host_api_info_by_index(i)
    print(f"[{i}] {info.get('name')} (defaultIn={info.get('defaultInputDevice')}, defaultOut={info.get('defaultOutputDevice')})")

print("\n--- DEFAULT OUTPUT DEVICE ---")
try:
    def_out = pa.get_default_output_device_info()
    print(def_out)
except Exception as e:
    print(f"Error getting default output: {e}")

print("\n--- ALL OUTPUT DEVICES ---")
for i in range(pa.get_device_count()):
    dev = pa.get_device_info_by_index(i)
    if dev.get("maxOutputChannels", 0) > 0:
        api = pa.get_host_api_info_by_index(dev.get("hostApi"))
        print(f"[{i}] {dev.get('name')} | API: {api.get('name')} | OutCh: {dev.get('maxOutputChannels')} | Rate: {dev.get('defaultSampleRate')}")

# Test playing 24kHz tone on default output
print("\n--- TESTING 24000Hz PCM WRITE ON DEFAULT OUTPUT ---")
try:
    stream = pa.open(
        format=pyaudio.paInt16,
        channels=1,
        rate=24000,
        output=True,
        frames_per_buffer=1024,
    )
    print(f"Stream opened successfully: active={stream.is_active()}, stopped={stream.is_stopped()}")
    
    # Generate 0.5s of 440Hz sine wave at 24kHz
    sr = 24000
    num_samples = int(sr * 0.5)
    pcm = bytearray()
    for n in range(num_samples):
        sample = int(32767 * 0.2 * math.sin(2 * math.pi * 440 * n / sr))
        pcm.extend(struct.pack("<h", sample))
    
    # Write in chunks of 1024 frames = 2048 bytes
    chunk_size = 2048
    for idx in range(0, len(pcm), chunk_size):
        chunk = bytes(pcm[idx:idx+chunk_size])
        stream.write(chunk, exception_on_underflow=False)
        print(f"Wrote chunk {len(chunk)} bytes. Active: {stream.is_active()}")
    
    time.sleep(0.3)
    stream.stop_stream()
    stream.close()
    print("Test finished successfully!")
except Exception as e:
    import traceback
    print(f"Test FAILED with exception: {e}")
    traceback.print_exc()

pa.terminate()
