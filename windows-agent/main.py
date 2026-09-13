"""
MAX — Windows AI Agent & Companion Ecosystem Server.

Responsibilities:
  1. Bring up the WebSocket + HTTP companion servers.
  2. Optionally start a Realtime Voice agent session.
  3. Coordinate shutdown across all services cleanly via structured TaskGroup lifecycle.

This file is a composition root; it holds no tool business logic.
"""

import sys as _sys

# Reconfigure stdout and stderr for UTF-8 unconditionally
for _stream in (_sys.stdout, _sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

__all__ = ["main", "async_shutdown", "run_shutdown", "is_shutdown_requested"]

import asyncio
import logging
import signal
import sys
import threading
from typing import Any, Callable, Optional


# ─── Configuration Constants ──────────────────────────────────────────────────
HOST = "0.0.0.0"
WS_PORT = 9000
HTTP_PORT = 9001
PLACEHOLDER_KEY = "your_api_key_here"


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


def _print_startup_banner(local_ip: str, ws_port: int, http_port: int, pairing_code: str) -> None:
    """Print clean formatted startup banner."""
    print(BANNER)
    print("  =======================================================")
    print("          MAX Windows Agent & Companion Ecosystem        ")
    print("  =======================================================")
    print(f"  Server Host  : {HOST} (LAN IP: {local_ip})")
    print(f"  WS Port      : {ws_port} (Android App Endpoint: wss://{local_ip}:{ws_port})")
    print(f"  HTTP Port    : {http_port}")
    print(f"  Dashboard URL: \033[94mhttp://localhost:{http_port}/\033[0m")
    print(f"  Health Check : \033[94mhttp://localhost:{http_port}/healthz\033[0m")
    print(f"  Pairing Code : \033[92m{pairing_code}\033[0m")
    print("  -------------------------------------------------------")
    print("  Scan/enter pairing code in Android App to connect.")
    print("  =======================================================\n")


def _check_dependencies() -> None:
    """Verify required dependencies at startup; exit with code 2 if missing."""
    missing = []
    try:
        import websockets
    except ImportError:
        missing.append("websockets")
    try:
        from google import genai
    except ImportError:
        missing.append("google-genai")
    if missing:
        _sys.stderr.write(
            f"❌ Missing required dependencies: {', '.join(missing)}\n"
            f"   Please install them via: pip install {' '.join(missing)}\n"
        )
        _sys.stderr.flush()
        _sys.exit(2)


# ─── Centralized Shutdown Coordinator ─────────────────────────────────────────

_shutdown_lock = threading.Lock()
_shutdown_called = False
_shutdown_event: Optional[asyncio.Event] = None
_voice_session: Any = None
_ws_server: Any = None
_http_server: Any = None


def is_shutdown_requested() -> bool:
    """Check if shutdown has been initiated."""
    global _shutdown_called
    with _shutdown_lock:
        return _shutdown_called


async def _stop_quietly(name: str, stop_callable: Any) -> None:
    """Safely invoke a synchronous or asynchronous stop callable without unhandled exceptions."""
    try:
        if stop_callable is None:
            return
        if asyncio.iscoroutinefunction(stop_callable):
            await stop_callable()
        elif callable(stop_callable):
            res = stop_callable()
            if asyncio.iscoroutine(res):
                await res
    except Exception:
        logger = logging.getLogger("jarvis.main")
        logger.exception("%s failed to stop cleanly", name)


async def async_shutdown() -> None:
    """Asynchronous shutdown coordinator: stops services in deterministic order."""
    global _shutdown_called
    with _shutdown_lock:
        if _shutdown_called:
            return
        _shutdown_called = True

    # 0. Stop Desktop Overlay process & UI Bridge
    try:
        from core.overlay_process_manager import overlay_manager
        overlay_manager.stop_overlay()
    except Exception:
        pass

    try:
        from server.ui_bridge import ui_bridge
        await _stop_quietly("ui bridge", ui_bridge.stop)
    except Exception:
        pass

    # 1. Stop voice session
    if _voice_session is not None:
        await _stop_quietly("voice session", lambda: asyncio.to_thread(_voice_session.stop))

    # 2. Stop WebSocket companion server & mDNS advertiser
    if _ws_server is not None:
        await _stop_quietly("websocket server", _ws_server.stop)

    # 3. Stop HTTP companion server
    if _http_server is not None:
        await _stop_quietly("http server", _http_server.stop)


def run_shutdown() -> None:
    """Synchronous fallback shutdown (for early KeyboardInterrupt before event loop started)."""
    global _shutdown_called
    with _shutdown_lock:
        if _shutdown_called:
            return
        _shutdown_called = True

    logger = logging.getLogger("jarvis.main")
    print("\n\n🛑 Shutting down MAX...")
    try:
        from core.overlay_process_manager import overlay_manager
        overlay_manager.cleanup()
    except Exception:
        pass
    if _voice_session is not None:
        try:
            _voice_session.stop()
        except Exception:
            logger.exception("Voice session sync shutdown failed")


# ─── Service Runners ─────────────────────────────────────────────────────────

async def run_companion_servers(ws_server: Any, http_server: Any) -> None:
    """Run WebSocket and HTTP Companion Servers under event-bus lifecycle."""
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
        await _stop_quietly("websocket server", ws_server.stop)
        await _stop_quietly("http server", http_server.stop)


# ─── Main Composition Root ───────────────────────────────────────────────────

async def main() -> None:
    """Initialize and start MAX Companion Server & Voice Agent."""
    global _voice_session, _ws_server, _http_server, _shutdown_event
    _shutdown_event = asyncio.Event()

    setup_logging()
    logger = logging.getLogger("jarvis.main")
    _check_dependencies()
    
    from config import GEMINI_API_KEY, GEMINI_MODEL, JARVIS_NAME, USER_NAME
    from server.websocket_server import WebSocketServer
    from server.http_server import HTTPServer
    from server.api_router import handle_request as http_handler, get_local_ip
    from core.task_group import TaskGroup
    
    # Ensure tools package is imported to trigger auto-registration
    import tools

    # Startup reconciliation for persistent reminders
    try:
        from tools.persistent_scheduler import persistent_scheduler
        reconcile_res = persistent_scheduler.reconcile()
        if reconcile_res.get("repaired", 0) > 0 or reconcile_res.get("missed", 0) > 0:
            logger.info(f"⏱️ Reminder reconciliation: {reconcile_res.get('message')}")
    except Exception as re_err:
        logger.warning(f"Reminder reconciliation notice: {re_err}")

    local_ip = get_local_ip()

    _ws_server = WebSocketServer(HOST, WS_PORT)
    _http_server = HTTPServer(HOST, HTTP_PORT, http_handler)
    
    from orchestrator.device_router import device_router
    device_router._ws_server = _ws_server

    pairing_code = _ws_server.pairing_manager.get_pairing_code()
    _print_startup_banner(local_ip, WS_PORT, HTTP_PORT, pairing_code)
    logger.info("Companion servers configured on %s (WS: %d, HTTP: %d)", HOST, WS_PORT, HTTP_PORT)

    # Realtime Voice session setup if API key is provided
    _voice_session = None
    if GEMINI_API_KEY and GEMINI_API_KEY != PLACEHOLDER_KEY:
        try:
            from core.tool_executor import ToolRegistry as VoiceToolRegistry
            from core.realtime_voice import RealtimeVoiceSession
            voice_tool_registry = VoiceToolRegistry()
            _voice_session = RealtimeVoiceSession(voice_tool_registry)
            logger.info("🎤 Realtime Voice Session Enabled (model: %s)", GEMINI_MODEL)
            print(f"  🎤 Realtime Voice Session Enabled ({GEMINI_MODEL})")
        except Exception as e:
            logger.exception(f"Voice session initialization failed: {e}")
    else:
        logger.info("Realtime Voice API key not configured — running Companion Server in standalone mode.")
        print("  ℹ️  Realtime Voice API key not configured — running Companion Server in standalone mode.")

    # Desktop Overlay UI Bridge setup
    _ui_bridge = None
    try:
        from server.ui_bridge import ui_bridge as _ui_bridge
        # Register confirmation gate UI listener to push events via EventBus
        try:
            from core.confirmation_gate import HumanConfirmationGate
            # Find the confirmation gate instance used by the tool executor
            if _voice_session and hasattr(_voice_session, 'tools'):
                gate = getattr(_voice_session.tools, '_confirmation_gate', None)
                if gate:
                    _ui_bridge.set_confirmation_gate(gate)
                    # Register EventBus-based UI callback on the gate
                    def _push_confirmation_to_eventbus(payload):
                        try:
                            from server.event_bus import event_bus
                            from protocol.event_types import EventType
                            event_bus.publish(EventType.CONFIRMATION_REQUIRED, payload)
                        except Exception:
                            pass
                    gate.register_ui_listener(_push_confirmation_to_eventbus)
                    logger.info("🛡️  ConfirmationGate linked to UI Bridge via EventBus")
        except Exception as cg_err:
            logger.debug(f"ConfirmationGate linkage skipped: {cg_err}")
        logger.info("🖥️  Desktop Overlay UI Bridge configured (ws://127.0.0.1:9002)")
        print("  🖥️  Desktop Overlay UI Bridge Enabled (ws://127.0.0.1:9002)")
    except Exception as ub_err:
        logger.warning(f"UI Bridge initialization skipped: {ub_err}")

    # Register OS signal handlers for clean event-driven shutdown
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _shutdown_event.set)
        except NotImplementedError:
            pass

    async def _run_voice_managed():
        if _voice_session is None:
            return
        try:
            await _voice_session.start()
        except asyncio.CancelledError:
            pass
        except Exception as ve:
            logger.warning(f"Voice session ended: {ve}")
        print("  ℹ️  Voice session stopped. Companion Server remaining active.")

    managed_tasks: list[asyncio.Task] = []

    async def _wait_for_shutdown():
        await _shutdown_event.wait()
        logger.info("Shutdown event received — stopping active services...")
        for task in managed_tasks:
            if not task.done():
                task.cancel()

    try:
        async with TaskGroup() as tg:
            server_task = tg.create_task(
                run_companion_servers(_ws_server, _http_server),
                name="companion_servers"
            )
            managed_tasks.append(server_task)

            if _voice_session is not None:
                voice_task = tg.create_task(_run_voice_managed(), name="voice_session")
                managed_tasks.append(voice_task)

            # Start Desktop Overlay UI Bridge & Process Manager
            if _ui_bridge is not None:
                async def _run_ui_bridge():
                    try:
                        await _ui_bridge.start()
                        # Automatically launch and manage MAXOverlay.exe after UIBridge is ready
                        try:
                            from core.overlay_process_manager import overlay_manager
                            overlay_manager.start_overlay()
                        except Exception as op_err:
                            logger.warning(f"Failed to start overlay process: {op_err}")

                        # Keep alive until cancelled
                        while True:
                            await asyncio.sleep(1.0)
                    except asyncio.CancelledError:
                        pass
                    except Exception as ube:
                        logger.warning(f"UI Bridge error: {ube}")
                ui_task = tg.create_task(_run_ui_bridge(), name="ui_bridge")
                managed_tasks.append(ui_task)

            # Await shutdown signal event
            await _wait_for_shutdown()
    except asyncio.CancelledError:
        pass
    except Exception as eg:
        logger.exception(f"Service task error: {eg}")
    finally:
        await async_shutdown()

    print(f"\n👋 MAX offline. Bye {USER_NAME}!\n")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        run_shutdown()
        sys.exit(0)
