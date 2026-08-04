import asyncio
import json
import logging
import time
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
        
        # TXT record properties
        txt_records = {
            "device_id": self.device_id[:12],
            "device_name": self.name,
            "version": "3.0"
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
                logger.info("mDNS advertising stopped.")
            except Exception as e:
                logger.error(f"Error stopping mDNS: {e}")

class WebSocketServer:
    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        self.pairing_manager = PairingManager()
        self.mdns_advertiser = None
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
            async with websockets.serve(
                self.handler,
                self.host,
                self.port,
                ssl=ssl_context
            ):
                logger.info(f"WebSocket Secure (WSS) server listening on wss://{self.host}:{self.port}")
                await asyncio.Future()  # run forever
        finally:
            if self.mdns_advertiser:
                await self.mdns_advertiser.stop()


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

            if msg_type == PacketType.PAIR_REQUEST.value:
                payload = envelope.get("payload", {})
                code = payload.get("pairing_code")
                dev_name = payload.get("device_name", "Unknown Android Device")
                
                event_bus.publish(EventType.PAIR_STARTED, {
                    "device_id": device_id,
                    "device_name": dev_name
                })
                
                # Pair device
                token = session_manager.pair_device(device_id, dev_name, code)
                if token:
                    logger.info(f"Pairing SUCCESS for device: {device_id} ({dev_name})")
                    session_token = secrets.token_hex(16)
                    self.active_session_tokens[device_id] = session_token
                    
                    resp_payload = {
                        "status": "success",
                        "token": token,
                        "session_token": session_token
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
                    logger.warning(f"Pairing FAILED for device: {device_id}")
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
                is_paired = any(d.get("device_id") == device_id for d in session_manager.paired_devices)
                if not is_paired:
                    logger.warning(f"Unpaired device {device_id} sent HELLO. Closing connection.")
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
                        logger.info(f"Challenge-Response Authentication SUCCESS: device {device_id} is authenticated.")
                    else:
                        logger.warning(f"Challenge-Response Authentication FAILED: invalid signature from device {device_id}.")
                        await websocket.close(CloseCode.UNAUTHORIZED.value, "Authentication signature invalid")
                        return
                except asyncio.TimeoutError:
                    logger.warning(f"Authentication TIMEOUT: device {device_id} did not respond within 5 seconds.")
                    await websocket.close(CloseCode.UNAUTHORIZED.value, "Authentication timeout")
                    return
                except Exception as e:
                    logger.warning(f"Authentication error during handshake: {e}")
                    await websocket.close(CloseCode.UNAUTHORIZED.value, f"Auth exception: {e}")
                    return
            else:
                logger.warning(f"Closed connection: Unknown first packet type: {msg_type}")
                await websocket.close(CloseCode.INVALID_PAYLOAD.value, "Hello handshake expected")
                return

            if authenticated:
                # Track this as the active connection for this device
                self.active_device_sockets[device_id] = websocket

                connection_manager.register(
                    websocket,
                    device_id,
                    source.get("platform", "unknown"),
                    envelope.get("payload", {}).get("device_name", "")
                )
                metrics_manager.increment_connections()
                
                event_bus.publish(EventType.CLIENT_CONNECTED, {
                    "device_id": device_id,
                    "platform": source.get("platform", "unknown"),
                    "device_name": envelope.get("payload", {}).get("device_name", "")
                })

                # 2. Main Transaction Loop
                async for message in websocket:
                    await self.process_packet(websocket, message, device_id)

        except websockets.exceptions.ConnectionClosed as e:
            logger.info(f"Connection closed by {client_address} (code={e.code}, reason='{e.reason}')")
        except Exception as e:
            logger.error(f"Error handling session: {e}", exc_info=True)
        finally:
            from core.connection_manager import connection_manager
            from core.metrics_manager import metrics_manager
            from server.event_bus import event_bus
            
            # Clean up active device socket tracking
            if device_id and self.active_device_sockets.get(device_id) == websocket:
                del self.active_device_sockets[device_id]

            connection_manager.unregister(websocket)
            if device_id:
                metrics_manager.decrement_connections()
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
                else:
                    logger.warning(f"Unknown event name: {event_name}")


            else:
                logger.warning(f"Unknown message type: {msg_type}")
                
        except Exception as e:
            logger.error(f"Error processing packet: {e}", exc_info=True)

    async def send_envelope(self, websocket, request_id: str, packet_type: PacketType, target_device_id: str, payload: dict):
        envelope = PacketFactory.create_envelope(request_id, packet_type, target_device_id, payload)
        await websocket.send(json.dumps(envelope))
