import json
import time
import asyncio
import os
import urllib.parse
import logging

logger = logging.getLogger("MAX.APIRouter")
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

        elif path in ("/health", "/healthz", "/api/v1/health"):
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
            metrics = metrics_manager.get_metrics()
            status_data = {
                "agent_version": "3.0.0",
                "protocol_version": settings_manager.get(SettingKey.PROTOCOL_VERSION.value, 1),
                "uptime": round(uptime, 2),
                "connected_devices": len(connection_manager.get_all_connections()),
                "paired_devices": len(session_manager.paired_devices),
                "metrics": metrics,
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

        elif path in ("/api/chat", "/api/v1/chat"):
            if method == "OPTIONS":
                h_bytes = (
                    "HTTP/1.1 204 No Content\r\n"
                    "Access-Control-Allow-Origin: *\r\n"
                    "Access-Control-Allow-Methods: POST, OPTIONS\r\n"
                    "Access-Control-Allow-Headers: Content-Type\r\n"
                    "Connection: close\r\n\r\n"
                ).encode("utf-8")
                writer.write(h_bytes)
                await writer.drain()
                return

            if method != "POST":
                write_error(405, ErrorCode.METHOD_NOT_ALLOWED.value, "Only POST is allowed")
                await writer.drain()
                return

            try:
                payload = json.loads(body.decode("utf-8")) if body else {}
                user_msg = payload.get("message", "").strip()
                if not user_msg:
                    write_error(400, "BAD_REQUEST", "Message cannot be empty")
                    await writer.drain()
                    return

                from .event_bus import event_bus
                from protocol.event_types import EventType
                
                # 1. Publish User Message Event
                event_bus.publish(EventType.CHAT_MESSAGE, {
                    "role": "user",
                    "text": user_msg,
                    "timestamp": time.time()
                })

                # 2. Process chat message via IntentRouter and MAX autonomous loop
                from core.intent_router import intent_router, IntentCategory
                route_decision = intent_router.classify(user_msg)
                logger.info(
                    f"[MAX_ROUTE] input=\"{user_msg}\" intent={route_decision.intent.value} "
                    f"tool={route_decision.tool or 'NONE'} reason=\"{route_decision.reason}\""
                )

                from config import GEMINI_API_KEY
                if route_decision.intent == IntentCategory.CALCULATION and route_decision.direct_answer:
                    response_text = route_decision.direct_answer
                elif route_decision.intent in (IntentCategory.GENERAL_KNOWLEDGE, IntentCategory.CONVERSATION):
                    if GEMINI_API_KEY and GEMINI_API_KEY != "your_api_key_here":
                        try:
                            from core.provider_manager import provider_manager
                            from config import SYSTEM_PROMPT
                            resp = provider_manager.chat_completion(
                                messages=[
                                    {"role": "system", "content": SYSTEM_PROMPT},
                                    {"role": "user", "content": user_msg}
                                ]
                            )
                            response_text = resp.get("content", "") or f"Processed: {user_msg}"
                        except Exception as ai_err:
                            logger.warning(f"Direct AI generation notice: {ai_err}")
                            response_text = f"MAX: {user_msg}"
                    else:
                        response_text = f"MAX Standalone: Received '{user_msg}'."
                else:
                    if GEMINI_API_KEY and GEMINI_API_KEY != "your_api_key_here":
                        try:
                            from core.agent_execution_loop import agent_execution_loop
                            h_res = await agent_execution_loop.execute_goal_loop(user_msg)
                            response_text = h_res.get("final_answer") or "Task completed successfully."
                        except Exception as h_err:
                            logger.warning(f"MAX execution loop fallback in chat: {h_err}")
                            response_text = f"Processed request: {user_msg}"
                    else:
                        response_text = f"MAX Standalone: Received '{user_msg}'."

                # Publish MAX Response Event
                event_bus.publish(EventType.CHAT_MESSAGE, {
                    "role": "max",
                    "text": response_text,
                    "timestamp": time.time()
                })

                write_success({
                    "user_message": user_msg,
                    "max_response": response_text
                })
                await writer.drain()
            except Exception as chat_err:
                write_error(500, "CHAT_ERROR", str(chat_err))
                await writer.drain()

        elif path in ("/api/tool", "/api/v1/tool"):
            if method == "OPTIONS":
                h_bytes = (
                    "HTTP/1.1 204 No Content\r\n"
                    "Access-Control-Allow-Origin: *\r\n"
                    "Access-Control-Allow-Methods: POST, OPTIONS\r\n"
                    "Access-Control-Allow-Headers: Content-Type\r\n"
                    "Connection: close\r\n\r\n"
                ).encode("utf-8")
                writer.write(h_bytes)
                await writer.drain()
                return

            if method != "POST":
                write_error(405, ErrorCode.METHOD_NOT_ALLOWED.value, "Only POST is allowed")
                await writer.drain()
                return

            try:
                payload = json.loads(body.decode("utf-8")) if body else {}
                token = headers.get("authorization", "").replace("Bearer ", "").strip() or payload.get("token", "")
                origin = headers.get("origin", "").lower()
                
                # Check Origin against CSRF / DNS-rebinding
                is_local = client_ip in ("127.0.0.1", "::1", "localhost")
                http_port = settings_manager.get(SettingKey.HTTP_PORT.value, 9001)
                allowed_local_origins = (
                    f"http://localhost:{http_port}",
                    f"http://127.0.0.1:{http_port}",
                    "http://localhost:5173",
                    "http://127.0.0.1:5173",
                    "",
                )
                is_valid_token = any(d.get("token") == token for d in session_manager.paired_devices)


                if is_local:
                    if origin and not any(origin.startswith(ao) for ao in allowed_local_origins if ao):
                        if not is_valid_token:
                            write_error(403, "FORBIDDEN", f"Cross-Origin request blocked from unauthorized origin: {origin}")
                            await writer.drain()
                            return
                else:
                    if not is_valid_token:
                        write_error(401, "UNAUTHORIZED", "Authentication token is invalid or missing.")
                        await writer.drain()
                        return


                tool_name = payload.get("tool", "").strip().lower()
                action_name = payload.get("action", "").strip().lower()
                args = payload.get("args", {})

                # Normalize tool names from dashboard
                name_map = {
                    "system_info": "systeminfo",
                    "file_ops": "fileops",
                    "environment": "env",
                    "windows_agent": "windows_agent"
                }
                tool_name = name_map.get(tool_name, tool_name)

                import tools
                from tools.tool_registry import ToolRegistry
                from server.event_bus import event_bus
                from protocol.event_types import EventType

                tool_obj = ToolRegistry.get_tool(tool_name)
                if not tool_obj:
                    write_error(404, "TOOL_NOT_FOUND", f"Tool '{tool_name}' is not registered.")
                    await writer.drain()
                    return


                event_bus.publish(EventType.TOOL_REQUESTED, {
                    "tool": tool_name,
                    "action": action_name,
                    "args": args
                })

                # Execute action with optional send_progress callback
                async def _send_progress(msg: str):
                    try:
                        event_bus.publish(EventType.TOOL_PROGRESS, {
                            "tool": tool_name,
                            "action": action_name,
                            "message": msg
                        })
                    except Exception:
                        pass

                import inspect
                sig = inspect.signature(tool_obj.execute)
                if len(sig.parameters) >= 3 or "send_progress" in sig.parameters:
                    res = await tool_obj.execute(action_name, args, _send_progress)
                else:
                    res = await tool_obj.execute(action_name, args)
                
                event_bus.publish(EventType.TOOL_COMPLETED, {
                    "tool": tool_name,
                    "action": action_name,
                    "result": res
                })

                metrics_manager.increment_requests()
                write_success(res)
                await writer.drain()
            except Exception as tool_err:
                write_error(500, "EXECUTION_ERROR", str(tool_err))
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

