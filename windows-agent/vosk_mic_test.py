"""
Standalone Vosk + Microphone Diagnostic Script
Tests MME device with Vosk at 16000Hz 16-bit Mono.
"""

import json
import math
import os
import struct
import sys
import time
import pyaudio
from vosk import Model, KaldiRecognizer

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def calculate_rms(audio_bytes: bytes) -> float:
    count = len(audio_bytes) // 2
    if count == 0:
        return 0.0
    try:
        shorts = struct.unpack(f"<{count}h", audio_bytes[: count * 2])
        sum_squares = sum(s * s for s in shorts)
        return math.sqrt(sum_squares / count)
    except Exception:
        return 0.0


def find_best_input_device(pa):
    """Find the best MME microphone device for 16kHz speech recognition."""
    mme_mic = None
    mme_any = None
    dsound_dev = None
    fallback = None
    
    for i in range(pa.get_device_count()):
        try:
            info = pa.get_device_info_by_index(i)
            if info.get("maxInputChannels", 0) <= 0:
                continue
            api = pa.get_host_api_info_by_index(info.get("hostApi"))
            api_name = api.get("name", "")
            dev_name = info.get("name", "").lower()
            
            if "MME" in api_name:
                if "microphone" in dev_name and mme_mic is None:
                    mme_mic = i
                elif mme_any is None:
                    mme_any = i
            elif "DirectSound" in api_name:
                if dsound_dev is None:
                    dsound_dev = i
            elif fallback is None:
                fallback = i
        except Exception:
            pass
    
    return next((x for x in (mme_mic, mme_any, dsound_dev, fallback) if x is not None), None)


def main():
    model_path = os.path.join(os.path.dirname(__file__), "storage", "models", "vosk-model-small-en-us-0.15")
    if not os.path.exists(model_path):
        print(f"Model path not found: {model_path}")
        return

    print("Loading Vosk model...", flush=True)
    model = Model(model_path)
    rec = KaldiRecognizer(model, 16000)
    print("Vosk model loaded.", flush=True)

    pa = pyaudio.PyAudio()
    input_device_index = find_best_input_device(pa)
    
    if input_device_index is None:
        print("No input device found!", flush=True)
        return
    
    dev_info = pa.get_device_info_by_index(input_device_index)
    api_info = pa.get_host_api_info_by_index(dev_info.get("hostApi"))
    print(f"\nOpening Device [{input_device_index}]: {dev_info.get('name')}", flush=True)
    print(f"Host API: {api_info.get('name')}", flush=True)
    print(f"Format: 16000Hz, 1 Channel, 16-bit PCM, chunk=1024", flush=True)

    stream = pa.open(
        format=pyaudio.paInt16,
        channels=1,
        rate=16000,
        input=True,
        input_device_index=input_device_index,
        frames_per_buffer=1024
    )

    print("\n=======================================================", flush=True)
    print("[VOSK TEST] LISTENING... Speak 'Hey MAX' into mic!", flush=True)
    print("Press Ctrl+C to stop.", flush=True)
    print("=======================================================\n", flush=True)

    chunks_total = 0
    partials_total = 0
    finals_total = 0
    last_log_time = time.time()

    try:
        while True:
            data = stream.read(1024, exception_on_overflow=False)
            chunks_total += 1
            rms = calculate_rms(data)

            if rec.AcceptWaveform(data):
                finals_total += 1
                res = json.loads(rec.Result())
                text = res.get("text", "")
                if text:
                    print(f"\n[VOSK FINAL]: \"{text}\" (RMS={rms:.1f})\n", flush=True)
            else:
                pres = json.loads(rec.PartialResult())
                ptext = pres.get("partial", "")
                if ptext:
                    partials_total += 1
                    print(f"[VOSK PARTIAL]: \"{ptext}\" (RMS={rms:.1f})", flush=True)

            now = time.time()
            if now - last_log_time >= 2.0:
                print(f"[Telemetry] chunks={chunks_total}, rms={rms:.1f}, partials={partials_total}, finals={finals_total}", flush=True)
                last_log_time = now

    except KeyboardInterrupt:
        print("\n\nStopping Vosk test...", flush=True)
    finally:
        try:
            stream.stop_stream()
            stream.close()
        except Exception:
            pass
        pa.terminate()
        print("Clean exit.", flush=True)

if __name__ == "__main__":
    main()
