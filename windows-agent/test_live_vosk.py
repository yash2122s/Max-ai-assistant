import os
import json
import pyaudio
from vosk import Model, KaldiRecognizer

if __name__ == "__main__":
    model_path = r'storage\models\vosk-model-small-en-us-0.15'
    model = Model(model_path)
    rec = KaldiRecognizer(model, 16000)

    pa = pyaudio.PyAudio()
    # device 4 or 5
    stream = pa.open(format=pyaudio.paInt16, channels=1, rate=16000, input=True, input_device_index=4, frames_per_buffer=1024)

    print('=== Listening to mic for 4 seconds... ===')
    for i in range(60):
        data = stream.read(1024, exception_on_overflow=False)
        if rec.AcceptWaveform(data):
            res = json.loads(rec.Result())
            text = res.get('text', '')
            if text:
                print(f'FINAL [{i}]: "{text}"')
        else:
            pres = json.loads(rec.PartialResult())
            ptext = pres.get('partial', '')
            if ptext:
                print(f'PARTIAL [{i}]: "{ptext}"')

    stream.stop_stream()
    stream.close()
    pa.terminate()
    print('=== Done ===')
