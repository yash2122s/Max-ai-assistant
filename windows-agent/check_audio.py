import pyaudio
pa = pyaudio.PyAudio()
print("Devices:")
for i in range(pa.get_device_count()):
    info = pa.get_device_info_by_index(i)
    print(f"  [{i}] {info['name']}  (in={info['maxInputChannels']}, out={info['maxOutputChannels']})")
pa.terminate()
