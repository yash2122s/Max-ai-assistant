import json
import time
import asyncio
from core.settings_manager import settings_manager
from core.session_manager import session_manager
from core.connection_manager import connection_manager
from core.metrics_manager import metrics_manager

async def handle_request(method: str, path: str, headers: dict, body: bytes, writer):
    def write_success(data):
        resp = {
            "success": True,
            "data": data
        }
        body_bytes = json.dumps(resp).encode("utf-8")
        h_bytes = (
            f"HTTP/1.1 200 OK\r\n"
            f"Content-Type: application/json\r\n"
            f"Content-Length: {len(body_bytes)}\r\n"
            f"Access-Control-Allow-Origin: *\r\n"
            f"Connection: close\r\n\r\n"
        ).encode("utf-8")
        writer.write(h_bytes + body_bytes)

    def write_error(status_code: int, code: str, message: str):
        resp = {
            "success": False,
            "error": {
                "code": code,
                "message": message
            }
        }
        body_bytes = json.dumps(resp).encode("utf-8")
        status_line = f"HTTP/1.1 {status_code} "
        if status_code == 404:
            status_line += "Not Found"
        elif status_code == 405:
            status_line += "Method Not Allowed"
        else:
            status_line += "Internal Server Error"
            
        h_bytes = (
            f"{status_line}\r\n"
            f"Content-Type: application/json\r\n"
            f"Content-Length: {len(body_bytes)}\r\n"
            f"Access-Control-Allow-Origin: *\r\n"
            f"Connection: close\r\n\r\n"
        ).encode("utf-8")
        writer.write(h_bytes + body_bytes)

    try:
        if path == "/health":
            if method != "GET":
                write_error(405, "METHOD_NOT_ALLOWED", "Only GET is allowed")
                await writer.drain()
                return
            write_success({"status": "ok"})
            await writer.drain()
            
        elif path == "/metrics":
            if method != "GET":
                write_error(405, "METHOD_NOT_ALLOWED", "Only GET is allowed")
                await writer.drain()
                return
            # Update metrics connections count dynamically
            metrics_manager.set_connection_count(len(connection_manager.get_all_connections()))
            write_success(metrics_manager.get_metrics())
            await writer.drain()
            
        elif path == "/api/status":
            if method != "GET":
                write_error(405, "METHOD_NOT_ALLOWED", "Only GET is allowed")
                await writer.drain()
                return
                
            uptime = time.time() - settings_manager.start_time
            status_data = {
                "agent_version": "1.0.0",
                "protocol_version": settings_manager.get("protocol_version", 1),
                "uptime": round(uptime, 2),
                "connected_devices": len(connection_manager.get_all_connections()),
                "paired_devices": len(session_manager.paired_devices),
                "connections": [
                    {
                        "device_id": conn.device_id,
                        "platform": conn.platform,
                        "device_name": conn.device_name,
                        "connected_at": conn.connected_at,
                        "last_seen": conn.last_seen,
                        "latency_ms": conn.latency_ms
                    }
                    for conn in connection_manager.get_all_connections()
                ],
                "http_port": settings_manager.get("http_port", 9001),
                "ws_port": settings_manager.get("port", 9000)
            }
            write_success(status_data)
            await writer.drain()

        elif path.startswith("/api/events"):
            if method != "GET":
                write_error(405, "METHOD_NOT_ALLOWED", "Only GET is allowed")
                await writer.drain()
                return

            # Parse Last-Event-ID from headers or query parameters
            last_event_id = headers.get("last-event-id")
            if not last_event_id and "?" in path:
                _, query = path.split("?", 1)
                for param in query.split("&"):
                    if "=" in param:
                        k, v = param.split("=", 1)
                        if k == "last_event_id":
                            last_event_id = v
                            break

            # Send SSE Headers
            sse_headers = (
                f"HTTP/1.1 200 OK\r\n"
                f"Content-Type: text/event-stream\r\n"
                f"Cache-Control: no-cache\r\n"
                f"Connection: keep-alive\r\n"
                f"Access-Control-Allow-Origin: *\r\n"
                f"\r\n"
            ).encode("utf-8")
            writer.write(sse_headers)
            await writer.drain()

            # Subscribe client queue to EventBus
            queue = asyncio.Queue()
            from .event_bus import event_bus
            event_bus.subscribe(queue)

            try:
                # 1. Replay missed events if requested
                if last_event_id:
                    replayed = event_bus.get_events_after(last_event_id)
                    for event in replayed:
                        sse_msg = f"id: {event['id']}\nevent: {event['type']}\ndata: {json.dumps(event)}\n\n"
                        writer.write(sse_msg.encode("utf-8"))
                    await writer.drain()

                # 2. Keepalive & event stream loop
                while True:
                    try:
                        event = await asyncio.wait_for(queue.get(), timeout=20.0)
                        # Record metrics events sent
                        metrics_manager.increment_events()
                        sse_msg = f"id: {event['id']}\nevent: {event['type']}\ndata: {json.dumps(event)}\n\n"
                        writer.write(sse_msg.encode("utf-8"))
                        await writer.drain()
                    except asyncio.TimeoutError:
                        # Periodically push keepalive comments to prevent proxy dropouts
                        writer.write(b": keepalive\n\n")
                        await writer.drain()

            except (asyncio.CancelledError, ConnectionResetError, BrokenPipeError):
                pass
            finally:
                event_bus.unsubscribe(queue)

        else:
            write_error(404, "NOT_FOUND", f"Route not found: {method} {path}")
            await writer.drain()
            
    except Exception as e:
        write_error(500, "INTERNAL_ERROR", str(e))
        await writer.drain()
