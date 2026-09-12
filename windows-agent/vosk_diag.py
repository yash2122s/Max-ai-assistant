"""Diagnostic: Test Vosk with MME device and proper resampling."""
import json, math, os, struct, sys, time, pyaudio, audioop
from vosk import Model, KaldiRecognizer

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.stderr.reconfigure(encoding='utf-8', errors='replace')

model = Model(os.path.join('storage', 'models', 'vosk-model-small-en-us-0.15'))
rec = KaldiRecognizer(model, 16000)

pa = pyaudio.PyAudio()

# Try MME device [1] (Microphone Array)
for dev_idx in [1, 0, 4]:
    info = pa.get_device_info_by_index(dev_idx)
    api = pa.get_host_api_info_by_index(info.get("hostApi"))
    print(f'\nTesting Device [{dev_idx}]: {info["name"]}', flush=True)
    print(f'Host API: {api["name"]}, Native: {info["defaultSampleRate"]}Hz/{info["maxInputChannels"]}ch', flush=True)
    
    try:
        stream = pa.open(format=pyaudio.paInt16, channels=1, rate=16000, input=True,
                         input_device_index=dev_idx, frames_per_buffer=1024)
        
        print('Listening for 5 seconds...', flush=True)
        rec2 = KaldiRecognizer(model, 16000)
        start = time.time()
        chunks = 0
        found_speech = False
        while time.time() - start < 5.0:
            data = stream.read(1024, exception_on_overflow=False)
            chunks += 1
            
            count = len(data) // 2
            shorts = struct.unpack(f'<{count}h', data[:count*2])
            rms = math.sqrt(sum(s*s for s in shorts) / count) if count else 0
            
            if chunks == 1:
                print(f'  First 10 samples: {list(shorts[:10])}', flush=True)
                print(f'  Bytes: {len(data)}, RMS: {rms:.0f}', flush=True)
            
            if rec2.AcceptWaveform(data):
                res = json.loads(rec2.Result())
                text = res.get('text', '')
                if text:
                    print(f'  FINAL: "{text}" (rms={rms:.0f})', flush=True)
                    found_speech = True
            else:
                partial = json.loads(rec2.PartialResult())
                ptext = partial.get('partial', '')
                if ptext:
                    print(f'  PARTIAL: "{ptext}" (rms={rms:.0f})', flush=True)
                    found_speech = True
            
            if chunks % 200 == 0:
                print(f'  [chunks={chunks} rms={rms:.0f}]', flush=True)
        
        stream.stop_stream()
        stream.close()
        
        result = 'SPEECH DETECTED' if found_speech else 'NO SPEECH DETECTED'
        print(f'  Result: {result} (chunks={chunks})', flush=True)
        
        if found_speech:
            print(f'\n*** DEVICE [{dev_idx}] WORKS FOR VOSK! ***', flush=True)
            break
            
    except Exception as e:
        print(f'  ERROR: {e}', flush=True)

pa.terminate()
