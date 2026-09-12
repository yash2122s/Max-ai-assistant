"""
JARVIS — Windows AI Agent & Companion Ecosystem Server
Main entry point. Initializes both the Companion WebSocket Server (for Android phone control)
and the Gemini Live voice agent session.

Usage:
    python main.py
"""

import asyncio
import logging
import signal
import sys
import threading

# ─── Logging Setup ───────────────────────────────────────────────────────────

def setup_logging():
    """Configure colored console logging."""
    class ColorFormatter(logging.Formatter):
        COLORS = {
            logging.DEBUG: "\033[36m",     # Cyan
            logging.INFO: "\033[32m",      # Green
            logging.WARNING: "\033[33m",   # Yellow
            logging.ERROR: "\033[31m",     # Red
            logging.CRITICAL: "\033[41m",  # Red background
        }
        RESET = "\033[0m"
        
        def format(self, record):
            color = self.COLORS.get(record.levelno, self.RESET)
            record.msg = f"{color}{record.msg}{self.RESET}"
            return super().format(record)
    
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(ColorFormatter("%(asctime)s [%(name)s] %(message)s", datefmt="%H:%M:%S"))
    
    logging.root.setLevel(logging.INFO)
    logging.root.addHandler(handler)
    
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    logging.getLogger("google").setLevel(logging.WARNING)


# ─── Banner ──────────────────────────────────────────────────────────────────

BANNER = r"""
     ██╗ █████╗ ██████╗ ██╗   ██╗██╗███████╗
     ██║██╔══██╗██╔══██╗██║   ██║██║██╔════╝
     ██║███████║██████╔╝██║   ██║██║███████╗
██   ██║██╔══██║██╔══██╗╚██╗ ██╔╝██║╚════██║
╚█████╔╝██║  ██║██║  ██║ ╚████╔╝ ██║███████╗
 ╚════╝ ╚═╝  ╚═╝╚═╝  ╚═╝  ╚═══╝  ╚═╝╚══════╝
    Windows AI Agent — Unified Ecosystem Server & Voice
"""


# ─── Centralized Shutdown Coordinator ─────────────────────────────────────────

_shutdown_lock = threading.Lock()
_shutdown_called = False
_voice_session = None
_ws_server = None
_http_server = None
_tasks: list = []


def is_shutdown_requested() -> bool:
    global _shutdown_called
    with _shutdown_lock:
        return _shutdown_called


async def async_shutdown():
    """Asynchronous shutdown coordinator: stops services in deterministic order."""
    global _shutdown_called
    with _shutdown_lock:
        if _shutdown_called:
            return
        _shutdown_called = True

    logger = logging.getLogger("jarvis.main")
    print("\n\n🛑 Shutting down MAX...")

    # 1. Stop voice session (joins audio capture/playback threads before loop closure)
    if _voice_session is not None:
        try:
            await asyncio.to_thread(_voice_session.stop)
        except Exception:
            logger.exception("Failed to stop voice session cleanly")

    # 2. Stop WebSocket companion server & mDNS advertiser
    if _ws_server is not None:
        try:
            await _ws_server.stop()
        except Exception:
            logger.exception("Failed to stop WebSocket server cleanly")

    # 3. Stop HTTP companion server
    if _http_server is not None:
        try:
            await _http_server.stop()
        except Exception:
            logger.exception("Failed to stop HTTP server cleanly")

    # 4. Cancel & await remaining tasks
    pending = [t for t in _tasks if not t.done() and t is not asyncio.current_task()]
    for t in pending:
        t.cancel()
    if pending:
        try:
            await asyncio.gather(*pending, return_exceptions=True)
        except Exception:
            logger.exception("Error awaiting cancelled tasks")


def run_shutdown():
    """Synchronous fallback shutdown (for signal handlers / interpreter exit)."""
    global _shutdown_called
    with _shutdown_lock:
        if _shutdown_called:
            return
        _shutdown_called = True

    logger = logging.getLogger("jarvis.main")
    print("\n\n🛑 Shutting down MAX...")
    if _voice_session is not None:
        try:
            _voice_session.stop()
        except Exception:
            logger.exception("Voice session sync shutdown failed")
    for t in _tasks:
        try:
            if not t.done():
                t.cancel()
        except Exception:
            logger.exception("Task cancellation failed")


async def run_companion_servers(ws_server, http_server):
    """Run WebSocket and HTTP Companion Servers."""
    from server.event_bus import event_bus
    from protocol import EventType

    event_bus.publish(EventType.AGENT_STARTED, {
        "ws_port": ws_server.port,
        "http_port": http_server.port
    })
    
    try:
        await asyncio.gather(
            ws_server.start(),
            http_server.start()
        )
    except asyncio.CancelledError:
        pass
    finally:
        event_bus.publish(EventType.AGENT_SHUTDOWN, {})
        try:
            await ws_server.stop()
        except Exception:
            pass
        try:
            await http_server.stop()
        except Exception:
            pass


async def main():
    """Initialize and start JARVIS Companion Server & Voice Agent."""
    global _voice_session, _ws_server, _http_server, _tasks
    setup_logging()
    logger = logging.getLogger("jarvis.main")
    
    print(BANNER)
    print("  Initializing systems...\n")
    
    from config import GEMINI_API_KEY, GEMINI_MODEL, JARVIS_NAME
    from server.websocket_server import WebSocketServer
    from server.http_server import HTTPServer
    from server.api_router import handle_request as http_handler, get_local_ip
    
    # Ensure tools package is imported to trigger auto-registration
    import tools

    host = "0.0.0.0"
    ws_port = 9000
    http_port = 9001
    local_ip = get_local_ip()

    _ws_server = WebSocketServer(host, ws_port)
    _http_server = HTTPServer(host, http_port, http_handler)
    
    from orchestrator.device_router import device_router
    device_router._ws_server = _ws_server

    print("  =======================================================")
    print("          MAX Windows Agent & Companion Ecosystem        ")
    print("  =======================================================")
    print(f"  Server Host  : {host} (LAN IP: {local_ip})")
    print(f"  WS Port      : {ws_port} (Android App Endpoint: wss://{local_ip}:{ws_port})")
    print(f"  HTTP Port    : {http_port}")
    print(f"  Dashboard URL: \033[94mhttp://localhost:{http_port}/\033[0m")
    print(f"  Pairing Code : \033[92m{_ws_server.pairing_manager.get_pairing_code()}\033[0m")
    print("  -------------------------------------------------------")
    print("  Scan/enter pairing code in Android App to connect.")
    print("  =======================================================\n")

    _tasks = []

    # 1. Start companion WebSocket & HTTP servers
    server_task = asyncio.create_task(run_companion_servers(_ws_server, _http_server))
    _tasks.append(server_task)

    # 2. Start Gemini Live Voice session if API key is provided
    _voice_session = None
    if GEMINI_API_KEY and GEMINI_API_KEY != "your_api_key_here":
        try:
            from core.tool_executor import ToolRegistry as VoiceToolRegistry
            from core.gemini_live import GeminiLiveSession
            voice_tool_registry = VoiceToolRegistry()
            _voice_session = GeminiLiveSession(voice_tool_registry)
            voice_session = _voice_session
            print(f"  🎤 Gemini Live Voice Session Enabled ({GEMINI_MODEL})")
            
            async def run_voice():
                try:
                    await voice_session.start()
                except Exception as ve:
                    logger.warning(f"Voice session ended: {ve}")
                print("  ℹ️  Voice session stopped. Companion Server remaining active.")
            
            voice_task = asyncio.create_task(run_voice())
            _tasks.append(voice_task)
        except Exception as e:
            logger.warning(f"Voice session initialization notice: {e}")
    else:
        print("  ℹ️  Gemini API key not configured — running Companion Server in standalone mode.")

    loop = asyncio.get_running_loop()
    
    # Schedule async_shutdown on signal
    def handle_signal():
        asyncio.create_task(async_shutdown())

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, handle_signal)
        except NotImplementedError:
            pass

    try:
        await server_task
    except asyncio.CancelledError:
        pass
    except KeyboardInterrupt:
        pass
    finally:
        await async_shutdown()

    print("\n👋 MAX offline. Bye Yaswanth!\n")


if __name__ == "__main__":
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        run_shutdown()
        sys.exit(0)
    finally:
        run_shutdown()
