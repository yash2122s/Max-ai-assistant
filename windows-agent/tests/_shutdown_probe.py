"""
MAX AI — Audio shutdown probe helper (NOT a unittest).

Spawns the real AudioCapture (real PyAudio) and exits through a chosen path so
the parent test can assert the interpreter finalizes WITHOUT the
PyEval_RestoreThread fatal error.

Modes:
  normal      -> return normally, rely on the atexit/threading safety net
  stop        -> explicit capture.stop() + terminate_shared_pyaudio()
  sys_exit    -> sys.exit(0) mid-capture
  keyboard    -> raise KeyboardInterrupt mid-capture
  unhandled   -> raise RuntimeError mid-capture
  os_exit     -> os._exit(0) mid-capture
"""

import asyncio
import os
import sys
import time

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from core.audio_handler import AudioCapture, terminate_shared_pyaudio

MODE = sys.argv[1] if len(sys.argv) > 1 else "normal"


def main():
    cap = AudioCapture()
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    cap.start(loop)

    stop_at = time.time() + 0.3

    async def tick():
        while time.time() < stop_at:
            await asyncio.sleep(0.05)
        loop.stop()

    loop.run_until_complete(tick())
    print("PROBE-CAPTURE-STARTED", flush=True)

    if MODE == "normal" or MODE == "stop":
        cap.stop()
        terminate_shared_pyaudio()
        return
    if MODE == "sys_exit":
        cap.stop()
        terminate_shared_pyaudio()
        sys.exit(0)
    if MODE == "keyboard":
        cap.stop()
        terminate_shared_pyaudio()
        raise KeyboardInterrupt
    if MODE == "unhandled":
        cap.stop()
        terminate_shared_pyaudio()
        raise RuntimeError("probe unhandled error")
    if MODE == "os_exit":
        os._exit(0)


if __name__ == "__main__":
    main()