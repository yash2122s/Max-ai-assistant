import asyncio
import json
import logging
import time
from typing import Optional, Dict, Any, List
import websockets
from config import LOG_PATH
from .pairing_manager import PairingManager
from tools.tool_registry import ToolRegistry
from protocol import PacketValidator, PacketFactory, PacketType, CloseCode, EventType

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.FileHandler(LOG_PATH, encoding="utf-8"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("MAXWindowsAgent")

import socket
from zeroconf import IPVersion, ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

class MDNSAdvertiser:
    def __init__(self, name: str, port: int, device_id: str):
        self.aio_zeroconf = None
        self.service_info = None
        self.name = name
        self.port = port
        self.device_id = device_id

    async def start(self, local_ip: str):
        self.aio_zeroconf = AsyncZeroconf(ip_version=IPVersion.V4Only)
        
        from core.cert_generator import get_cert_fingerprint
        # TXT record properties
        txt_records = {
            "device_id": self.device_id[:12],
            "device_name": self.name,
            "version": "3.0",
            "cert_fingerprint": get_cert_fingerprint()
        }
        
        clean_name = f"{self.name.replace(' ', '-').replace('.', '-')}-{self.device_id[:6]}"
        self.service_info = ServiceInfo(
            "_max-agent._tcp.local.",
            f"{clean_name}._max-agent._tcp.local.",
            addresses=[socket.inet_aton(local_ip)],
            port=self.port,
            properties=txt_records,
            server=f"{clean_name}.local."
        )
        
        try:
            await self.aio_zeroconf.async_register_service(self.service_info)
            logger.info(f"mDNS registered: _max-agent._tcp.local on {local_ip}:{self.port} (device_id: {self.device_id[:12]})")
        except Exception as e:
            logger.error(f"Failed to register mDNS service: {e}", exc_info=True)

    async def stop(self):
        if self.aio_zeroconf and self.service_info:
            try:
                await self.aio_zeroconf.async_unregister_service(self.service_info)
                await self.aio_zeroconf.async_close()
                # Allow Zeroconf background tasks to drain before event loop exits
                await asyncio.sleep(0.2)
                logger.info("mDNS advertising stopped.")
            except Exception as e:
                logger.error(f"Error stopping mDNS: {e}")
            finally:
                self.aio_zeroconf = None
                self.service_info = None

# Module-level server singleton
websocket_server: Optional['WebSocketServer'] = None

class WebSocketServer:
    def __init__(self, host: str, port: int):
        global websocket_server
        websocket_server = self
        self.host = host
        self.port = port
        self.pairing_manager = PairingManager()
        self.mdns_advertiser = None
        self._server = None
        self._closing = False
        self.active_session_tokens = {}
        # Track active websocket per device_id to prevent reconnection storms
        self.active_device_sockets = {}  # device_id -> websocket
        self.last_connect_time = {}      # device_id -> timestamp
        self.MIN_RECONNECT_INTERVAL = 5.0  # seconds
        logger.info(f"Initialized server. Current pairing code: {self.pairing_manager.get_pairing_code()}")

    async def start(self):
        import ssl
        import secrets
        from core.cert_generator import generate_self_signed_cert
        from core.settings_manager import settings_manager
        from server.api_router import get_local_ip

        cert_path, key_path = generate_self_signed_cert()
        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_context.load_cert_chain(certfile=cert_path, keyfile=key_path)

        agent_name = settings_manager.get("agent_name", "MAX Windows Agent")
        device_id = settings_manager.get("device_id", "unknown_device")
        local_ip = get_local_ip()

        self.mdns_advertiser = MDNSAdvertiser(agent_name, self.port, device_id)
        await self.mdns_advertiser.start(local_ip)

        try:
            self._server = await websockets.serve(
                self.handler,
                self.host,
                self.port,
                ssl=ssl_context,
                ping_interval=15,
                ping_timeout=25,
                close_timeout=10
            )
            logger.info(f"WebSocket Secure (WSS) server listening on wss://{self.host}:{self.port}")
            await self._server.wait_closed()
        except asyncio.CancelledError:
            pass
        finally:
            await self.stop()

    async def stop(self):
        if self._closing:
            return
        self._closing = True
        logger.info("🛑 Stopping WebSocket server...")
        if self._server:
            try:
                self._server.close()
                await self._server.wait_closed()
            except Exception as e:
                logger.debug(f"Error closing websocket server: {e}")
            self._server = None
        if self.mdns_advertiser:
            try:
                await self.mdns_advertiser.stop()
            except Exception as e:
                logger.debug(f"Error stopping mDNS: {e}")
            self.mdns_advertiser = None
        # Close open client sockets
        for ws in list(self.active_device_sockets.values()):
            try:
                await ws.close(CloseCode.NORMAL.value, "Server shutdown")
            except Exception:
                pass
        self.active_device_sockets.clear()
        logger.info("✅ WebSocket server stopped")



    async def handler(self, websocket):
        import secrets
        client_address = websocket.remote_address
        logger.info(f"New connection attempt from {client_address}")
        
        device_id = None
        authenticated = False
        session_token = None

        try:
            # 1. Handshake Phase
            handshake_msg = await websocket.recv()
            try:
                envelope = json.loads(handshake_msg)
            except Exception as e:
                logger.warning(f"Malformed JSON in handshake: {e}")
                await websocket.close(CloseCode.INVALID_PAYLOAD.value, "Malformed JSON")
                return

            # Envelope validation
            env_err = PacketValidator.validate_envelope(envelope)
            if env_err:
                logger.warning(f"Envelope validation failed during handshake: {env_err}")
                code = CloseCode.PROTOCOL_MISMATCH.value if "Protocol version mismatch" in env_err else CloseCode.INVALID_PAYLOAD.value
                await websocket.close(code, env_err)
                return

            msg_type = envelope.get("type")
            source = envelope.get("source", {})
            device_id = source.get("device_id")
            msg_id = envelope.get("id")

            if not device_id:
                await websocket.close(CloseCode.INVALID_PAYLOAD.value, "device_id is required")
                return

            # Stale connection handling: if device already has an active socket, close it to let new connection take over
            existing_ws = self.active_device_sockets.get(device_id)
            if existing_ws is not None:
                try:
                    logger.info(f"Device {device_id} reconnecting. Closing previous connection...")
                    await existing_ws.close(1000, "Reconnected from new socket")
                except Exception as e:
                    logger.debug(f"Error closing stale socket: {e}")
                self.active_device_sockets.pop(device_id, None)

            # Rate limit check (reduced to 1.0s to allow seamless reconnect)
            now = time.time()
            last_time = self.last_connect_time.get(device_id, 0)
            if (now - last_time) < 1.0:
                logger.warning(f"Rate limiting device {device_id}: reconnecting too fast. Rejecting.")
                await websocket.close(1000, "Reconnecting too fast, please wait")
                return
            self.last_connect_time[device_id] = now

            from core.session_manager import session_manager
            from server.event_bus import event_bus
            from core.connection_manager import connection_manager
            from core.metrics_manager import metrics_manager
            from core.cert_generator import get_cert_fingerprint

            if msg_type == PacketType.PAIR_REQUEST.value:
                payload = envelope.get("payload", {})
                code = payload.get("pairing_code")
                dev_name = payload.get("device_name", "Unknown Android Device")
                logger.info(f"[PAIR_REQUEST_RX] Received pair_request from {client_address} (device_id={device_id}, device_name={dev_name})")
                
                event_bus.publish(EventType.PAIR_STARTED, {
                    "device_id": device_id,
                    "device_name": dev_name
                })
                
                # Pair device
                token = session_manager.pair_device(device_id, dev_name, code)
                if token:
                    logger.info(f"[PAIR_SUCCESS] Pairing SUCCESS for device: {device_id} ({dev_name})")
                    session_token = secrets.token_hex(16)
                    self.active_session_tokens[device_id] = session_token
                    
                    resp_payload = {
                        "status": "success",
                        "token": token,
                        "session_token": session_token,
                        "cert_fingerprint": get_cert_fingerprint()
                    }
                    event_bus.publish(EventType.PAIR_SUCCESS, {
                        "device_id": device_id,
                        "device_name": dev_name
                    })
                    response_envelope = PacketFactory.create_envelope(
                        msg_id,
                        PacketType.PAIR_RESPONSE,
                        device_id,
                        resp_payload
                    )
                    await websocket.send(json.dumps(response_envelope))
                    authenticated = True
                else:
                    logger.warning(f"[PAIR_FAIL] Pairing FAILED for device: {device_id} - Incorrect pairing code")
                    logger.warning(
                        f"[WS_4001] reason='Incorrect pairing code' device_id='{device_id}' "
                        f"message_type='{msg_type}' session_state='HANDSHAKE' pairing_state='FAILED' remote_ip='{client_address}'"
                    )
                    resp_payload = {"status": "error", "message": "Incorrect pairing code"}
                    event_bus.publish(EventType.PAIR_FAILED, {
                        "device_id": device_id,
                        "reason": "Incorrect pairing code"
                    })
                    response_envelope = PacketFactory.create_envelope(
                        msg_id,
                        PacketType.PAIR_RESPONSE,
                        device_id,
                        resp_payload
                    )
                    await websocket.send(json.dumps(response_envelope))
                    await websocket.close(CloseCode.UNAUTHORIZED.value, "Pairing failed")
                    return

            elif msg_type == PacketType.HELLO.value:
                logger.info(f"[HELLO_RX] Received HELLO handshake from {client_address} (device_id={device_id})")
                is_paired = any(d.get("device_id") == device_id for d in session_manager.paired_devices)
                if not is_paired:
                    logger.warning(f"[HELLO_REJECT] Unpaired device {device_id} sent HELLO. Closing connection.")
                    logger.warning(
                        f"[WS_4001] reason='Device not paired' device_id='{device_id}' "
                        f"message_type='{msg_type}' session_state='HANDSHAKE' pairing_state='UNPAIRED' remote_ip='{client_address}'"
                    )
                    await websocket.close(CloseCode.UNAUTHORIZED.value, "Device not paired")
                    return

                # Send hello response
                caps = ToolRegistry.get_capabilities()
                hello_payload = {
                    "device_name": "Yaswanth Laptop",
                    "platform": "windows",
                    "version": "3.0",
                    "capabilities": caps
                }
                response_envelope = PacketFactory.create_envelope(
                    msg_id,
                    PacketType.HELLO,
                    device_id,
                    hello_payload
                )
                await websocket.send(json.dumps(response_envelope))

                # Generate secure challenge
                challenge = session_manager.generate_auth_challenge(device_id)
                challenge_envelope = {
                    "type": "auth_challenge",
                    "id": secrets.token_hex(8),
                    "source": {"device_id": "windows-main", "platform": "windows"},
                    "target": {"device_id": device_id, "platform": "android"},
                    "payload": {
                        "challenge": challenge
                    }
                }
                await websocket.send(json.dumps(challenge_envelope))

                # Await auth_response with 5.0 seconds TTL
                try:
                    auth_response_msg = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                    auth_envelope = json.loads(auth_response_msg)
                    if auth_envelope.get("type") != "auth_response":
                        logger.warning(f"Handshake auth failed: expected auth_response, got {auth_envelope.get('type')}")
                        logger.warning(
                            f"[WS_4001] reason='Expected auth_response' device_id='{device_id}' "
                            f"message_type='{auth_envelope.get('type')}' session_state='CHALLENGE' pairing_state='PAIRED' remote_ip='{client_address}'"
                        )
                        await websocket.close(CloseCode.UNAUTHORIZED.value, "Expected auth_response")
                        return

                    sig = auth_envelope.get("payload", {}).get("signature")
                    if session_manager.verify_auth_response(device_id, sig):
                        session_token = secrets.token_hex(16)
                        self.active_session_tokens[device_id] = session_token
                        
                        success_envelope = {
                            "type": "auth_success",
                            "id": secrets.token_hex(8),
                            "source": {"device_id": "windows-main", "platform": "windows"},
                            "target": {"device_id": device_id, "platform": "android"},
                            "payload": {
                                "session_token": session_token
                            }
                        }
                        await websocket.send(json.dumps(success_envelope))
                        authenticated = True
                        logger.info(f"[AUTH_SUCCESS] Challenge-Response Authentication SUCCESS: device {device_id} authenticated.")
                    else:
                        logger.warning(f"[AUTH_FAIL] Challenge-Response Authentication FAILED: invalid signature from device {device_id}.")
                        logger.warning(
                            f"[WS_4001] reason='Authentication signature invalid' device_id='{device_id}' "
                            f"message_type='auth_response' session_state='VERIFYING' pairing_state='PAIRED' remote_ip='{client_address}'"
                        )
                        await websocket.close(CloseCode.UNAUTHORIZED.value, "Authentication signature invalid")
                        return
                except asyncio.TimeoutError:
                    logger.warning(f"[AUTH_TIMEOUT] Authentication TIMEOUT: device {device_id} did not respond within 5 seconds.")
                    logger.warning(
                        f"[WS_4001] reason='Authentication timeout' device_id='{device_id}' "
                        f"message_type='auth_challenge' session_state='AWAITING_AUTH_RESPONSE' pairing_state='PAIRED' remote_ip='{client_address}'"
                    )
                    await websocket.close(CloseCode.UNAUTHORIZED.value, "Authentication timeout")
                    return
                except Exception as e:
                    logger.warning(f"Authentication error during handshake: {e}")
                    logger.warning(
                        f"[WS_4001] reason='Auth exception: {e}' device_id='{device_id}' "
                        f"message_type='auth_response' session_state='CHALLENGE' pairing_state='PAIRED' remote_ip='{client_address}'"
                    )
                    await websocket.close(CloseCode.UNAUTHORIZED.value, f"Auth exception: {e}")
                    return
            else:
                logger.warning(f"Closed connection: Unknown first packet type: {msg_type}")
                logger.warning(
                    f"[WS_4001] reason='Unknown first packet type: {msg_type}' device_id='{device_id}' "
                    f"message_type='{msg_type}' session_state='HANDSHAKE' pairing_state='UNKNOWN' remote_ip='{client_address}'"
                )
                await websocket.close(CloseCode.INVALID_PAYLOAD.value, "Hello or pair_request handshake expected")
                return

            if authenticated:
                # Track this as the active connection for this device
                self.active_device_sockets[device_id] = websocket

                from core.device_registry import device_registry, ConnectionState
                hello_payload = envelope.get("payload", {})
                dev_name = hello_payload.get("deviceName") or hello_payload.get("device_name") or "Unknown Device"
                dev_type = hello_payload.get("deviceType") or hello_payload.get("device_type") or "phone"
                dev_model = hello_payload.get("model") or dev_name
                os_ver = hello_payload.get("osVersion") or hello_payload.get("os_version") or ""
                app_ver = hello_payload.get("appVersion") or hello_payload.get("app_version") or ""
                caps = hello_payload.get("capabilities") or []
                ip_str = client_address[0] if isinstance(client_address, tuple) and len(client_address) > 0 else str(client_address)

                # Register/update in canonical DeviceRegistry
                device_registry.register_device(
                    device_id=device_id,
                    display_name=dev_name,
                    device_type=dev_type,
                    platform=source.get("platform", "android"),
                    capabilities=caps,
                    model=dev_model,
                    os_version=os_ver,
                    app_version=app_ver,
                    ip_address=ip_str,
                    session_id=session_token or "",
                    authority_state="STANDALONE"
                )

                connection_manager.register(
                    websocket,
                    device_id,
                    source.get("platform", "unknown"),
                    dev_name
                )
                metrics_manager.increment_connections()
                
                logger.info(f"[DEVICE_REGISTERED] Registered device [{device_id}] '{dev_name}' (type={dev_type}, state=CONNECTED)")
                
                event_bus.publish(EventType.DEVICE_REGISTERED, {
                    "device_id": device_id,
                    "display_name": dev_name,
                    "device_type": dev_type,
                    "platform": source.get("platform", "android"),
                    "capabilities": caps
                })

                event_bus.publish(EventType.CLIENT_CONNECTED, {
                    "device_id": device_id,
                    "platform": source.get("platform", "unknown"),
                    "device_name": dev_name
                })

                # 2. Main Transaction Loop
                async for message in websocket:
                    await self.process_packet(websocket, message, device_id)

        except websockets.ConnectionClosed as e:
            logger.info(f"[WS_CLOSE] Connection closed by {client_address} (code={e.code}, reason='{e.reason}')")

        except Exception as e:
            logger.error(f"Error handling session: {e}", exc_info=True)
        finally:
            from core.connection_manager import connection_manager
            from core.metrics_manager import metrics_manager
            from core.device_registry import device_registry, ConnectionState
            from server.event_bus import event_bus
            
            # Clean up active device socket tracking
            if device_id and self.active_device_sockets.get(device_id) == websocket:
                del self.active_device_sockets[device_id]

            connection_manager.unregister(websocket)
            if device_id:
                from orchestrator.device_router import device_router
                device_router.handle_device_disconnected(device_id)

                device_registry.set_connection_state(device_id, ConnectionState.DISCONNECTED)
                metrics_manager.decrement_connections()
                event_bus.publish(EventType.DEVICE_STATE_CHANGED, {
                    "device_id": device_id,
                    "state": ConnectionState.DISCONNECTED
                })
                event_bus.publish(EventType.CLIENT_DISCONNECTED, {
                    "device_id": device_id
                })



    async def process_packet(self, websocket, raw_message: str, client_device_id: str):
        try:
            from core.connection_manager import connection_manager
            connection_manager.update_activity(websocket)

            try:
                envelope = json.loads(raw_message)
            except Exception as e:
                logger.warning(f"Malformed JSON packet from client: {e}")
                return

            env_err = PacketValidator.validate_envelope(envelope)
            if env_err:
                logger.warning(f"Envelope validation error: {env_err}")
                return

            msg_type = envelope.get("type")
            msg_id = envelope.get("id")
            payload = envelope.get("payload", {})

            payload_err = PacketValidator.validate_payload(msg_type, payload)
            if payload_err:
                logger.warning(f"Payload validation error for {msg_type}: {payload_err}")
                return
            
            logger.info(f"Received packet type: {msg_type}, id: {msg_id}, payload: {payload}")

            if msg_type == PacketType.TOOL_REQUEST.value:
                # Handle tool executions
                token = payload.get("token")
                tool_name = payload.get("tool")
                action = payload.get("action")
                args = payload.get("arguments", {})
                
                args["request_id"] = msg_id
                
                from server.event_bus import event_bus
                from core.metrics_manager import metrics_manager

                metrics_manager.increment_requests()
                
                event_bus.publish(EventType.TOOL_REQUESTED, {
                    "device_id": client_device_id,
                    "tool": tool_name,
                    "action": action,
                    "arguments": args
                })

                session_token = self.active_session_tokens.get(client_device_id)
                is_authenticated_socket = self.active_device_sockets.get(client_device_id) == websocket
                if not is_authenticated_socket and (not session_token or token != session_token):
                    logger.warning(f"Unauthorized session token command request from device: {client_device_id}")
                    err_payload = {"status": "error", "output": "Unauthorized: session token is invalid or expired."}
                    await self.send_envelope(websocket, msg_id, PacketType.TOOL_RESPONSE, client_device_id, err_payload)
                    event_bus.publish(EventType.TOOL_FAILED, {
                        "device_id": client_device_id,
                        "tool": tool_name,
                        "action": action,
                        "status": "error",
                        "error": "Unauthorized"
                    })
                    return


                tool = ToolRegistry.get_tool(tool_name)
                if not tool:
                    logger.warning(f"Requested tool '{tool_name}' not found in registry")
                    err_payload = {"status": "error", "output": f"Tool '{tool_name}' is not registered on this Windows Agent."}
                    await self.send_envelope(websocket, msg_id, PacketType.TOOL_RESPONSE, client_device_id, err_payload)
                    event_bus.publish(EventType.TOOL_FAILED, {
                        "device_id": client_device_id,
                        "tool": tool_name,
                        "action": action,
                        "status": "error",
                        "error": f"Tool '{tool_name}' is not registered on this Windows Agent."
                    })
                    return

                # Async progress callback helper
                async def send_progress(progress_msg: str):
                    progress_payload = {"state": "RUNNING", "message": progress_msg}
                    await self.send_envelope(websocket, msg_id, PacketType.TOOL_PROGRESS, client_device_id, progress_payload)
                    event_bus.publish(EventType.TOOL_PROGRESS, {
                        "device_id": client_device_id,
                        "tool": tool_name,
                        "action": action,
                        "message": progress_msg
                    })

                # Execute tool
                logger.info(f"Executing tool: {tool_name}/{action} for {client_device_id}")
                start_time_exec = time.time()
                result = await tool.execute(action, args, send_progress)
                exec_duration = (time.time() - start_time_exec) * 1000.0
                
                # Record metrics average latency
                metrics_manager.update_latency(exec_duration)
                
                logger.info(f"Tool {tool_name}/{action} completed with status: {result.get('status')}")

                # JSON Lines Audit Logging with Size Rotation (5MB limit)
                try:
                    import os
                    from core.settings_manager import STORAGE_DIR
                    log_dir = os.path.join(STORAGE_DIR, "logs")
                    os.makedirs(log_dir, exist_ok=True)
                    log_path = os.path.join(log_dir, "audit.log")
                    
                    # Rotate log if size exceeds 5 MB
                    if os.path.exists(log_path) and os.path.getsize(log_path) > 5 * 1024 * 1024:
                        backup_path = os.path.join(log_dir, "audit.log.1")
                        try:
                            if os.path.exists(backup_path):
                                os.remove(backup_path)
                            os.rename(log_path, backup_path)
                        except Exception as e:
                            logger.warning(f"Could not rotate audit log: {e}")

                    log_entry = {
                        "timestamp": int(time.time()),
                        "request_id": msg_id,
                        "action": action,
                        "status": result.get("status", "unknown"),
                        "duration_ms": int(exec_duration)
                    }
                    with open(log_path, "a", encoding="utf-8") as f:
                        f.write(json.dumps(log_entry) + "\n")
                except Exception as ex:
                    logger.error(f"Failed writing audit log: {ex}")


                await self.send_envelope(websocket, msg_id, PacketType.TOOL_RESPONSE, client_device_id, result)
                
                if result.get("status") == "success":
                    event_bus.publish(EventType.TOOL_COMPLETED, {
                        "device_id": client_device_id,
                        "tool": tool_name,
                        "action": action,
                        "status": "success",
                        "output": result.get("output", "")
                    })
                else:
                    event_bus.publish(EventType.TOOL_FAILED, {
                        "device_id": client_device_id,
                        "tool": tool_name,
                        "action": action,
                        "status": "error",
                        "error": result.get("output", "Execution failed")
                    })

            elif msg_type == PacketType.CANCEL.value:
                target_id = payload.get("target_request_id")
                from tools.terminal_tool import terminal_helper
                cancelled = terminal_helper.cancel_task(target_id)
                logger.info(f"Task cancellation call received for ID '{target_id}'. Success: {cancelled}")
                resp_payload = {"status": "success" if cancelled else "failed", "output": f"Task cancellation status: {cancelled}"}
                await self.send_envelope(websocket, msg_id, PacketType.TOOL_RESPONSE, client_device_id, resp_payload)

            elif msg_type == PacketType.COMMAND_RESULT.value:
                # Handle incoming command result from Android worker
                from server.event_bus import event_bus
                from core.device_registry import device_registry
                from orchestrator.device_router import device_router
                
                device_registry.touch(client_device_id)
                cmd_id = payload.get("command_id")
                status = payload.get("status")
                action = payload.get("action")
                logger.info(f"Received command_result from [{client_device_id}] for command [{cmd_id}]: status={status}, action={action}")
                
                # Pass to DeviceRouter to resolve correlated future
                device_router.handle_command_result(payload)
                
                event_bus.publish(EventType.COMMAND_RESULT_RECEIVED, {
                    "device_id": client_device_id,
                    "command_id": cmd_id,
                    "status": status,
                    "action": action,
                    "data": payload.get("data", {}),
                    "error": payload.get("error"),
                    "execution_time_ms": payload.get("execution_time_ms", 0)
                })

            elif msg_type == PacketType.HEARTBEAT.value:


                # Heartbeat acknowledgement (log and echo)
                logger.debug(f"Heartbeat received from {client_device_id}")
                
                from server.event_bus import event_bus
                event_bus.publish(EventType.HEARTBEAT, {
                    "device_id": client_device_id
                })
                
                heartbeat_payload = {
                    "uptime": int(time.process_time()),
                    "agent_version": "3.0"
                }
                await self.send_envelope(websocket, msg_id, PacketType.HEARTBEAT, client_device_id, heartbeat_payload)

            elif msg_type == "event" or msg_type == PacketType.EVENT.value:
                event_name = payload.get("event_name") or payload.get("event")
                if event_name in ["core:ping", "ping"]:
                    from tools.system_tool import system_helper
                    telemetry = system_helper.get_system_metrics()
                    pong_payload = {
                        "event_name": "core:pong",
                        "data": telemetry
                    }
                    await self.send_envelope(websocket, msg_id, PacketType.EVENT, client_device_id, pong_payload)
                elif event_name == "heartbeat":
                    logger.debug(f"Event heartbeat received from {client_device_id}")
                    
                    from server.event_bus import event_bus
                    event_bus.publish(EventType.HEARTBEAT, {
                        "device_id": client_device_id
                    })
                    
                    heartbeat_payload = {
                        "uptime": int(time.process_time()),
                        "agent_version": "3.0"
                    }
                    await self.send_envelope(websocket, msg_id, PacketType.HEARTBEAT, client_device_id, heartbeat_payload)
                elif event_name == "notification:synced":
                    from core.security import security_guard
                    from server.event_bus import event_bus

                    pkg = payload.get("packageName", "")
                    title = payload.get("title", "")
                    text = payload.get("text", "")
                    ts = payload.get("timestamp", int(time.time() * 1000))

                    # Mask sensitive patterns before logging or passing along
                    safe_title = security_guard.mask_secrets(str(title))
                    safe_text = security_guard.mask_secrets(str(text))

                    logger.info(f"📱 Received synced notification from [{client_device_id}] [{pkg}]: {safe_title} - {safe_text}")

                    # Publish notification event to EventBus for context consumers
                    event_bus.publish(EventType.NOTIFICATION_RECEIVED, {
                        "device_id": client_device_id,
                        "package_name": pkg,
                        "title": safe_title,
                        "text": safe_text,
                        "timestamp": ts
                    })

                    # Insert notification into ContextStore as an ephemeral NOTIFICATIONS record
                    try:
                        from context.store import context_store
                        from context.models import ContextRecord, ContextCategory, ContextOrigin
                        notif_record = ContextRecord(
                            record_id=f"notif_{int(ts)}",
                            category=ContextCategory.NOTIFICATIONS,
                            origin=ContextOrigin.REMOTE_ANDROID,
                            payload={
                                "device_id": client_device_id,
                                "package_name": pkg,
                                "title": safe_title,
                                "text": safe_text,
                                "timestamp": ts
                            },
                            updated_at=time.time(),
                            ttl_seconds=300.0
                        )
                        context_store.put(notif_record)
                    except Exception as ex:
                        logger.debug(f"Could not store notification in context_store: {ex}")

                    # Acknowledge notification reception back to Android
                    ack_payload = {
                        "event_name": "notification:ack",
                        "status": "received",
                        "timestamp": ts
                    }

                    await self.send_envelope(websocket, msg_id, PacketType.EVENT, client_device_id, ack_payload)
                elif event_name in ["capabilities:update", "capabilities_update", "capabilities.update"]:
                    from core.device_registry import device_registry
                    caps = payload.get("capabilities", [])
                    device_registry.update_capabilities(client_device_id, caps)
                    device_registry.touch(client_device_id)
                    logger.info(f"📱 Dynamic capabilities updated from [{client_device_id}]: {len(caps)} actions")
                else:
                    logger.warning(f"Unknown event name: {event_name}")



            else:
                logger.warning(f"Unknown message type: {msg_type}")
                
        except Exception as e:
            logger.error(f"Error processing packet: {e}", exc_info=True)

    async def send_envelope(self, websocket, request_id: str, packet_type: PacketType, target_device_id: str, payload: dict):
        envelope = PacketFactory.create_envelope(request_id, packet_type, target_device_id, payload)
        await websocket.send(json.dumps(envelope))
