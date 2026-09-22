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

__all__ = ["main", "async_shutdown", "run_shutdown", "is_shutdown_requested", "trigger_shutdown"]

import asyncio
import logging
import os
import signal
import sys
import threading
from typing import Any, Callable, Optional

from core.startup_profiler import StartupProfiler, profiler
from core.readiness import readiness


def safe_print(*args, **kwargs):
    """Print helper that safely handles Windows console encoding errors."""
    try:
        print(*args, flush=True, **kwargs)
    except UnicodeEncodeError:
        safe_args = []
        for a in args:
            if isinstance(a, str):
                safe_args.append(a.encode("ascii", errors="replace").decode("ascii"))
            else:
                safe_args.append(a)
        print(*safe_args, flush=True, **kwargs)


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
    """Verify required dependencies at startup using fast spec lookup; exit with code 2 if missing."""
    from importlib.util import find_spec
    required = {
        "websockets": "websockets",
        "google-genai": "google.genai",
    }
    missing = []
    for package_name, module_name in required.items():
        if find_spec(module_name) is None:
            missing.append(package_name)
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
    """Asynchronous shutdown coordinator: stops services in deterministic order (producers before consumers)."""
    global _shutdown_called
    with _shutdown_lock:
        if _shutdown_called:
            return
        _shutdown_called = True

    try:
        from core.memory_diagnostics import memory_diagnostics
        memory_diagnostics.record_checkpoint("before shutdown")
    except Exception:
        pass

    # 1. Stop voice session loops & await Live/Gemini async client cleanup
    if _voice_session is not None:
        if hasattr(_voice_session, "aclose"):
            await _stop_quietly("voice session async", _voice_session.aclose)
        else:
            await _stop_quietly("voice session", lambda: asyncio.to_thread(_voice_session.stop))

    # 2. Stop MCP background workers asynchronously (never block main loop)
    try:
        from core.mcp_client import mcp_client
        await _stop_quietly("mcp client", mcp_client.stop_async)
    except Exception:
        pass

    # 3. Stop Desktop Overlay UI Bridge event forwarding
    try:
        from server.ui_bridge import ui_bridge
        await _stop_quietly("ui bridge", ui_bridge.stop)
    except Exception:
        pass

    # 4. Stop WebSocket companion server & mDNS advertiser
    if _ws_server is not None:
        await _stop_quietly("websocket server", _ws_server.stop)

    # 5. Stop HTTP companion server
    if _http_server is not None:
        await _stop_quietly("http server", _http_server.stop)

    # 6. Stop Desktop Overlay native process
    try:
        from core.overlay_process_manager import overlay_manager
        overlay_manager.stop_overlay()
    except Exception:
        pass

    # 7. Cleanly unload shared Vosk models and recognizers
    try:
        from core.vosk_manager import vosk_manager
        vosk_manager.release_models()
    except Exception:
        pass

    # 8. Terminate shared PyAudio singleton
    try:
        from core.audio_handler import terminate_shared_pyaudio
        terminate_shared_pyaudio()
    except Exception:
        pass


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
    try:
        from core.vosk_manager import vosk_manager
        vosk_manager.release_models()
    except Exception:
        pass
    try:
        from core.audio_handler import terminate_shared_pyaudio
        terminate_shared_pyaudio()
    except Exception:
        pass


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


def trigger_shutdown() -> None:
    """Threadsafe trigger for graceful application shutdown."""
    global _shutdown_event
    event = _shutdown_event
    if event is None:
        main_mod = sys.modules.get("__main__")
        if main_mod:
            event = getattr(main_mod, "_shutdown_event", None)
    if event is not None:
        try:
            loop = getattr(event, "_loop", None)
            if loop and loop.is_running():
                loop.call_soon_threadsafe(event.set)
            else:
                event.set()
        except Exception:
            event.set()
    else:
        logger = logging.getLogger("jarvis.main")
        logger.warning("trigger_shutdown called but _shutdown_event is None")



# ─── Main Composition Root ───────────────────────────────────────────────────

async def main() -> None:
    """Initialize and start MAX Companion Server & Voice Agent."""
    global _voice_session, _ws_server, _http_server, _shutdown_event
    _shutdown_event = asyncio.Event()

    setup_logging()
    profiler.mark("MAIN_ENTER")
    logger = logging.getLogger("jarvis.main")
    _check_dependencies()
    profiler.mark("CHECK_DEPS_DONE")

    try:
        from core.memory_diagnostics import memory_diagnostics
        memory_diagnostics.record_checkpoint("MAX startup")
    except Exception:
        pass
    profiler.mark("MEM_DIAGNOSTICS_DONE")
    
    from config import GEMINI_API_KEY, GEMINI_MODEL, JARVIS_NAME, USER_NAME
    profiler.mark("CONFIG_LOADED")

    from server.websocket_server import WebSocketServer
    from server.http_server import HTTPServer
    from server.api_router import handle_request as http_handler, get_local_ip
    from core.task_group import TaskGroup
    profiler.mark("SERVERS_MODULES_LOADED")
    
    # Ensure tools package is imported to trigger auto-registration
    profiler.mark("PRE_TOOLS_IMPORT")
    import tools
    profiler.mark("POST_TOOLS_IMPORT")

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

    # Core and companion servers are fully ready
    profiler.mark("MAX_ONLINE")
    readiness.set("core", "READY")
    readiness.set("servers", "READY")

    safe_print("""
╔══════════════════════════════╗
║          MAX CORE            ║
║          ONLINE              ║
╚══════════════════════════════╝

Core       ✓ READY
Servers    ✓ READY
Voice      ◌ INITIALIZING
Gemini     ◌ CONNECTING
Overlay    ◌ STARTING
""")

    # Realtime Voice session setup if API key is provided
    _voice_session = None
    if GEMINI_API_KEY and GEMINI_API_KEY != PLACEHOLDER_KEY:
        try:
            profiler.mark("PRE_VOICE_SESSION_INIT")
            from core.tool_executor import ToolRegistry as VoiceToolRegistry
            from core.realtime_voice import RealtimeVoiceSession
            voice_tool_registry = VoiceToolRegistry()
            _voice_session = RealtimeVoiceSession(voice_tool_registry)
            profiler.mark("VOICE_SESSION_INIT_DONE")
            logger.info("🎤 Realtime Voice Session configured (model: %s)", GEMINI_MODEL)
        except Exception as e:
            logger.exception("Voice session initialization failed: %s", e)
            readiness.set("voice", "ERROR", error=str(e))
            readiness.set("gemini", "ERROR", error=str(e))
    else:
        logger.info("Realtime Voice API key not configured — running Companion Server in standalone mode.")
        safe_print("  ℹ️  Realtime Voice API key not configured — running Companion Server in standalone mode.")
        readiness.set("voice", "DISABLED")
        readiness.set("gemini", "DISABLED")

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
            logger.debug("ConfirmationGate linkage skipped: %s", cg_err)
        logger.info("🖥️  Desktop Overlay UI Bridge configured (ws://127.0.0.1:9002)")
    except Exception as ub_err:
        logger.warning("UI Bridge initialization skipped: %s", ub_err)
        readiness.set("overlay", "DISABLED")

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
            logger.exception("Voice session ended with error: %s", ve)
            readiness.set("gemini", "ERROR", error=str(ve))
        safe_print("  ℹ️  Voice session stopped. Companion Server remaining active.")

    async def _init_vosk_managed():
        if _voice_session is None:
            return
        try:
            await _voice_session.initialize_vosk_background()
        except asyncio.CancelledError:
            pass
        except Exception as ve:
            logger.exception("Vosk background initialization failed: %s", ve)
            readiness.set("voice", "ERROR", error=str(ve))

    async def _run_ui_bridge():
        if _ui_bridge is None:
            return
        try:
            await _ui_bridge.start()
            # Automatically launch and manage MAXOverlay.exe in background thread
            try:
                from core.overlay_process_manager import overlay_manager
                overlay_manager.set_shutdown_callback(trigger_shutdown)
                await asyncio.to_thread(overlay_manager.start_overlay)
                readiness.set("overlay", "READY")
                profiler.mark("OVERLAY_READY")
                safe_print("  🖥️  Desktop Overlay: READY")
            except Exception as op_err:
                logger.warning("Failed to start overlay process: %s", op_err)
                readiness.set("overlay", "ERROR", error=str(op_err))

            # Keep alive until cancelled
            while True:
                await asyncio.sleep(1.0)
        except asyncio.CancelledError:
            pass
        except Exception as ube:
            logger.warning("UI Bridge error: %s", ube)
            readiness.set("overlay", "ERROR", error=str(ube))

    managed_tasks: list[asyncio.Task] = []

    async def _wait_for_shutdown():
        await _shutdown_event.wait()
        logger.info("Shutdown event received — stopping active services...")
        if _voice_session is not None:
            try:
                _voice_session.stop()
            except Exception as e:
                logger.debug(f"Voice session stop error: {e}")
        try:
            from core.overlay_process_manager import overlay_manager
            overlay_manager.stop_overlay()
        except Exception:
            pass
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

            # Background Task 1: Vosk initial model load
            if _voice_session is not None:
                vosk_task = tg.create_task(_init_vosk_managed(), name="vosk_init")
                managed_tasks.append(vosk_task)

                # Background Task 2: Realtime Voice Session (Gemini connection)
                voice_task = tg.create_task(_run_voice_managed(), name="voice_session")
                managed_tasks.append(voice_task)

            # Background Task 3: Desktop Overlay UI Bridge & Process Manager
            if _ui_bridge is not None:
                ui_task = tg.create_task(_run_ui_bridge(), name="ui_bridge")
                managed_tasks.append(ui_task)

            # Background Task 4: Readiness Monitor
            async def _monitor_readiness():
                try:
                    await asyncio.gather(
                        readiness.wait_for("voice"),
                        readiness.wait_for("gemini"),
                        readiness.wait_for("overlay"),
                    )
                    health = readiness.get_system_health()
                    profiler.mark(f"SYSTEM_{health}")
                    snap = readiness.snapshot()

                    def _fmt(comp: str) -> str:
                        val = snap.get(comp, "NOT_STARTED")
                        if val == "READY":
                            return f"✓ {val}"
                        elif val == "DISABLED":
                            return f"— {val}"
                        elif "ERROR" in val:
                            return f"✗ {val}"
                        return f"◌ {val}"

                    safe_print("\n" + "=" * 55)
                    if health == "FULLY_OPERATIONAL":
                        safe_print("  ✓ MAX SYSTEM FULLY OPERATIONAL")
                    elif health == "DEGRADED":
                        safe_print("  ⚠ MAX SYSTEM DEGRADED")
                    else:
                        safe_print("  MAX SYSTEM READY")

                    safe_print(f"     Core:    {_fmt('core')}")
                    safe_print(f"     Servers: {_fmt('servers')}")
                    safe_print(f"     Voice:   {_fmt('voice')}")
                    safe_print(f"     Gemini:  {_fmt('gemini')}")
                    safe_print(f"     Overlay: {_fmt('overlay')}")
                    safe_print("=" * 55 + "\n")
                    logger.info("System health %s: %s", health, snap)
                    profiler.report()
                    if os.environ.get("MAX_EXIT_AFTER_READY"):
                        logger.info("MAX_EXIT_AFTER_READY requested — triggering shutdown")
                        trigger_shutdown()
                except asyncio.CancelledError:
                    pass
                except Exception as err:
                    logger.exception("Readiness monitor error: %s", err)

            monitor_task = tg.create_task(_monitor_readiness(), name="readiness_monitor")
            managed_tasks.append(monitor_task)

            # Await shutdown signal event
            await _wait_for_shutdown()
    except asyncio.CancelledError:
        pass
    except Exception as eg:
        logger.exception(f"Service task error: {eg}")
    finally:
        await async_shutdown()
        print(f"\n👋 MAX offline. Bye {USER_NAME}!\n", flush=True)
        os._exit(0)


if __name__ == "__main__":
    import sys
    sys.modules["main"] = sys.modules["__main__"]
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        run_shutdown()
    finally:
        import os
        os._exit(0)


