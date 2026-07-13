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

class WebSocketServer:
    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        self.pairing_manager = PairingManager()
        logger.info(f"Initialized server. Current pairing code: {self.pairing_manager.get_pairing_code()}")

    async def start(self):
        async with websockets.serve(self.handler, self.host, self.port):
            logger.info(f"WebSocket server listening on ws://{self.host}:{self.port}")
            await asyncio.Future()  # run forever

    async def handler(self, websocket):
        client_address = websocket.remote_address
        logger.info(f"New connection attempt from {client_address}")
        
        device_id = None
        handshake_done = False

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

            if envelope.get("type") != PacketType.HELLO.value:
                logger.warning("Closed connection: First packet was not hello")
                await websocket.close(CloseCode.INVALID_PAYLOAD.value, "Hello handshake expected")
                return

            source = envelope.get("source", {})
            device_id = source.get("device_id")
            
            # Send server hello response using PacketFactory
            hello_payload = {
                "device_name": "MAX Windows Agent",
                "capabilities": ToolRegistry.get_capabilities()
            }
            response_envelope = PacketFactory.create_envelope(
                envelope.get("id", "handshake_response"),
                PacketType.HELLO,
                device_id,
                hello_payload
            )
            await websocket.send(json.dumps(response_envelope))
            handshake_done = True
            logger.info(f"Handshake successful with device: {device_id} ({source.get('platform')})")

            # Register active connection
            from core.connection_manager import connection_manager
            from core.metrics_manager import metrics_manager
            from server.event_bus import event_bus

            connection_manager.register(
                websocket,
                device_id,
                source.get("platform", "unknown"),
                envelope.get("payload", {}).get("device_name", "")
            )
            
            # Increment connections metrics
            metrics_manager.increment_connections()
            
            # Publish client connected domain event
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
            
            connection_manager.unregister(websocket)
            if device_id:
                metrics_manager.decrement_connections()
                event_bus.publish(EventType.CLIENT_DISCONNECTED, {
                    "device_id": device_id
                })

    async def process_packet(self, websocket, raw_message: str, client_device_id: str):
        try:
            # Update connection activity
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

            if msg_type == PacketType.PAIR_REQUEST.value:
                # Handle pairing request
                code = payload.get("pairing_code")
                dev_name = payload.get("device_name", "Unknown Android Device")
                
                from server.event_bus import event_bus
                event_bus.publish(EventType.PAIR_STARTED, {
                    "device_id": client_device_id,
                    "device_name": dev_name
                })
                
                token = self.pairing_manager.pair_device(client_device_id, dev_name, code)
                
                resp_payload = {}
                if token:
                    logger.info(f"Pairing SUCCESS for device: {client_device_id} ({dev_name})")
                    resp_payload = {"status": "success", "token": token}
                    event_bus.publish(EventType.PAIR_SUCCESS, {
                        "device_id": client_device_id,
                        "device_name": dev_name
                    })
                else:
                    logger.warning(f"Pairing FAILED (incorrect code) for device: {client_device_id}")
                    resp_payload = {"status": "error", "message": "Incorrect pairing code"}
                    event_bus.publish(EventType.PAIR_FAILED, {
                        "device_id": client_device_id,
                        "reason": "Incorrect pairing code"
                    })

                await self.send_envelope(websocket, msg_id, PacketType.PAIR_RESPONSE, client_device_id, resp_payload)

            elif msg_type == PacketType.TOOL_REQUEST.value:
                # Handle tool executions
                token = payload.get("token")
                tool_name = payload.get("tool")
                action = payload.get("action")
                args = payload.get("arguments", {})
                
                from server.event_bus import event_bus
                from core.metrics_manager import metrics_manager

                # Record request count metrics
                metrics_manager.increment_requests()
                
                event_bus.publish(EventType.TOOL_REQUESTED, {
                    "device_id": client_device_id,
                    "tool": tool_name,
                    "action": action,
                    "arguments": args
                })

                # Validate pairing token
                if not self.pairing_manager.verify_token(client_device_id, token):
                    logger.warning(f"Unauthorized command request from device: {client_device_id}")
                    err_payload = {"status": "error", "output": "Unauthorized: pairing token is invalid or expired."}
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
                    progress_payload = {"state": "running", "message": progress_msg}
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

            elif msg_type == PacketType.HEARTBEAT.value:
                # Heartbeat acknowledgement (log and echo)
                logger.debug(f"Heartbeat received from {client_device_id}")
                
                from server.event_bus import event_bus
                event_bus.publish(EventType.HEARTBEAT, {
                    "device_id": client_device_id
                })
                
                heartbeat_payload = {
                    "uptime": int(time.process_time()),
                    "agent_version": "1.0.0"
                }
                await self.send_envelope(websocket, msg_id, PacketType.HEARTBEAT, client_device_id, heartbeat_payload)

            elif msg_type == "event":
                event_name = payload.get("event")
                if event_name == "heartbeat":
                    logger.debug(f"Event heartbeat received from {client_device_id}")
                    
                    from server.event_bus import event_bus
                    event_bus.publish(EventType.HEARTBEAT, {
                        "device_id": client_device_id
                    })
                    
                    heartbeat_payload = {
                        "uptime": int(time.process_time()),
                        "agent_version": "1.0.0"
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
