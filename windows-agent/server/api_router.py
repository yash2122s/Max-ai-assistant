import json
import time
import asyncio
import os
import urllib.parse
from core.settings_manager import settings_manager
from core.session_manager import session_manager
from core.connection_manager import connection_manager
from core.metrics_manager import metrics_manager
from protocol import SettingKey, ErrorCode

DASHBOARD_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "dashboard")

def get_mime_type(filepath: str) -> str:
    ext = os.path.splitext(filepath)[1].lower()
    if ext == ".html":
        return "text/html; charset=utf-8"
    elif ext == ".css":
        return "text/css; charset=utf-8"
    elif ext == ".js":
        return "application/javascript; charset=utf-8"
    elif ext == ".svg":
        return "image/svg+xml; charset=utf-8"
    elif ext in (".png", ".jpg", ".jpeg", ".webp"):
        return f"image/{ext[1:]}"
    elif ext == ".ico":
        return "image/x-icon"
    return "application/octet-stream"

async def serve_static_file(path: str, writer):
    # Strip query params
    clean_path = urllib.parse.unquote(path.split("?", 1)[0])
    
    # Map root path to index.html
    if clean_path in ("/", "/index.html"):
        filepath = os.path.join(DASHBOARD_DIR, "index.html")
    else:
        filepath = os.path.join(DASHBOARD_DIR, clean_path.lstrip("/"))
        
    filepath = os.path.abspath(filepath)
    
    # Secure validation: prevent directory traversal
    try:
        common = os.path.commonpath([DASHBOARD_DIR, filepath])
    except ValueError:
        common = ""
        
    if common != DASHBOARD_DIR or not os.path.exists(filepath) or os.path.isdir(filepath):
        # Resource not found fallback (404)
        resp = {
            "success": False,
            "error": {
                "code": ErrorCode.NOT_FOUND.value,
                "message": f"Resource not found: {path}"
            }
        }
        body_bytes = json.dumps(resp).encode("utf-8")
        h_bytes = (
            f"HTTP/1.1 404 Not Found\r\n"
            f"Content-Type: application/json\r\n"
            f"Content-Length: {len(body_bytes)}\r\n"
            f"Access-Control-Allow-Origin: *\r\n"
            f"Connection: close\r\n\r\n"
        ).encode("utf-8")
        writer.write(h_bytes + body_bytes)
        return
        
    try:
        with open(filepath, "rb") as f:
            content = f.read()
        mime_type = get_mime_type(filepath)
        h_bytes = (
            f"HTTP/1.1 200 OK\r\n"
            f"Content-Type: {mime_type}\r\n"
            f"Content-Length: {len(content)}\r\n"
            f"Cache-Control: no-cache\r\n"
            f"Access-Control-Allow-Origin: *\r\n"
            f"Connection: close\r\n\r\n"
        ).encode("utf-8")
        writer.write(h_bytes + content)
    except Exception as e:
        resp = {
            "success": False,
            "error": {
                "code": ErrorCode.INTERNAL_ERROR.value,
                "message": str(e)
            }
        }
        body_bytes = json.dumps(resp).encode("utf-8")
        h_bytes = (
            f"HTTP/1.1 500 Internal Server Error\r\n"
            f"Content-Type: application/json\r\n"
            f"Content-Length: {len(body_bytes)}\r\n"
            f"Connection: close\r\n\r\n"
        ).encode("utf-8")
        writer.write(h_bytes + body_bytes)

import socket

def get_local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

async def handle_request(method: str, path: str, headers: dict, body: bytes, writer, client_ip: str = "127.0.0.1"):
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
        # Route parsing with support for versioned api endpoints
        if path in ("/api/pairing", "/api/v1/pairing"):
            if method != "GET":
                write_error(405, ErrorCode.METHOD_NOT_ALLOWED.value, "Only GET is allowed")
                await writer.drain()
                return
            
            # Security verification: block remote LAN fetches of pairing credentials
            if client_ip not in ("127.0.0.1", "::1", "localhost"):
                write_error(403, "ACCESS_DENIED", "Access restricted to localhost only")
                await writer.drain()
                return
                
            from core.cert_generator import get_cert_fingerprint
            pairing_data = {
                "protocol": "2.1",
                "device_id": settings_manager.get("device_id", "unknown_device"),
                "device_name": settings_manager.get("agent_name", "MAX Windows Agent"),
                "platform": "windows",
                "ip": get_local_ip(),
                "port": settings_manager.get(SettingKey.PORT.value, 9000),
                "pairing_code": session_manager.get_pairing_code(),
                "cert_fingerprint": get_cert_fingerprint()
            }
            write_success(pairing_data)

            await writer.drain()

        elif path in ("/health", "/api/v1/health"):
            if method != "GET":
                write_error(405, ErrorCode.METHOD_NOT_ALLOWED.value, "Only GET is allowed")
                await writer.drain()
                return
            write_success({"status": "ok"})
            await writer.drain()

            
        elif path in ("/metrics", "/api/v1/metrics"):
            if method != "GET":
                write_error(405, ErrorCode.METHOD_NOT_ALLOWED.value, "Only GET is allowed")
                await writer.drain()
                return
            # Update metrics connections count dynamically
            metrics_manager.set_connection_count(len(connection_manager.get_all_connections()))
            write_success(metrics_manager.get_metrics())
            await writer.drain()
            
        elif path in ("/api/status", "/api/v1/status"):
            if method != "GET":
                write_error(405, ErrorCode.METHOD_NOT_ALLOWED.value, "Only GET is allowed")
                await writer.drain()
                return
                
            uptime = time.time() - settings_manager.start_time
            status_data = {
                "agent_version": "3.0.0",
                "protocol_version": settings_manager.get(SettingKey.PROTOCOL_VERSION.value, 1),
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
                "http_port": settings_manager.get(SettingKey.HTTP_PORT.value, 9001),
                "ws_port": settings_manager.get(SettingKey.PORT.value, 9000)
            }
            write_success(status_data)
            await writer.drain()

        elif path.startswith("/api/events") or path.startswith("/api/v1/events"):
            if method != "GET":
                write_error(405, ErrorCode.METHOD_NOT_ALLOWED.value, "Only GET is allowed")
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
            await serve_static_file(path, writer)
            await writer.drain()
            
    except Exception as e:
        write_error(500, ErrorCode.INTERNAL_ERROR.value, str(e))
        await writer.drain()

