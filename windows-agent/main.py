"""
JARVIS — Windows AI Agent & Companion Ecosystem Server
Main entry point. Initializes both the Companion WebSocket Server (for Android phone control)
and the Gemini Live voice agent session.

Usage:
    python main.py
"""

import asyncio
import logging
import sys
import signal

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
        await http_server.stop()


async def main():
    """Initialize and start JARVIS Companion Server & Voice Agent."""
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

    ws_server = WebSocketServer(host, ws_port)
    http_server = HTTPServer(host, http_port, http_handler)

    print("  =======================================================")
    print("        JARVIS Windows Agent & Companion Ecosystem        ")
    print("  =======================================================")
    print(f"  Server Host  : {host} (LAN IP: {local_ip})")
    print(f"  WS Port      : {ws_port} (Android App Endpoint: wss://{local_ip}:{ws_port})")
    print(f"  HTTP Port    : {http_port}")
    print(f"  Dashboard URL: \033[94mhttp://localhost:{http_port}/\033[0m")
    print(f"  Pairing Code : \033[92m{ws_server.pairing_manager.get_pairing_code()}\033[0m")
    print("  -------------------------------------------------------")
    print("  Scan/enter pairing code in Android App to connect.")
    print("  =======================================================\n")

    tasks = []

    # 1. Start companion WebSocket & HTTP servers
    server_task = asyncio.create_task(run_companion_servers(ws_server, http_server))
    tasks.append(server_task)

    # 2. Start Gemini Live Voice session if API key is provided
    voice_session = None
    if GEMINI_API_KEY and GEMINI_API_KEY != "your_api_key_here":
        try:
            from core.tool_executor import ToolRegistry as VoiceToolRegistry
            from core.gemini_live import GeminiLiveSession
            voice_tool_registry = VoiceToolRegistry()
            voice_session = GeminiLiveSession(voice_tool_registry)
            print(f"  🎤 Gemini Live Voice Session Enabled ({GEMINI_MODEL})")
            voice_task = asyncio.create_task(voice_session.start())
            tasks.append(voice_task)
        except Exception as e:
            logger.warning(f"Voice session initialization notice: {e}")
    else:
        print("  ℹ️  Gemini API key not configured — running Companion Server in standalone mode.")

    # Handle graceful shutdown
    def shutdown():
        print("\n\n🛑 Shutting down JARVIS...")
        if voice_session:
            voice_session.stop()
        for t in tasks:
            t.cancel()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, shutdown)
        except NotImplementedError:
            pass

    try:
        await asyncio.gather(*tasks, return_exceptions=True)
    except KeyboardInterrupt:
        shutdown()

    print("\n👋 JARVIS offline. Goodbye, sir.\n")


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
        sys.exit(0)
