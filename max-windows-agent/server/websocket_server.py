import asyncio
import json
import logging
import time
import websockets
from config import LOG_PATH, PROTOCOL_VERSION
from .pairing_manager import PairingManager
from tools.tool_registry import ToolRegistry

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
            envelope = json.loads(handshake_msg)
            
            if envelope.get("type") != "hello":
                logger.warning("Closed connection: First packet was not hello")
                await websocket.close(1008, "Hello handshake expected")
                return

            if envelope.get("protocol_version") != PROTOCOL_VERSION:
                logger.warning(f"Protocol version mismatch: client={envelope.get('protocol_version')}, server={PROTOCOL_VERSION}")
                await websocket.close(1003, "Protocol version mismatch")
                return

            source = envelope.get("source", {})
            device_id = source.get("device_id")
            
            # Send server hello response
            response_envelope = {
                "protocol_version": PROTOCOL_VERSION,
                "id": envelope.get("id", "handshake_response"),
                "type": "hello",
                "timestamp": int(time.time()),
                "source": {
                    "device_id": "windows-main",
                    "platform": "windows"
                },
                "target": {
                    "device_id": device_id
                },
                "payload": {
                    "device_name": "MAX Windows Agent",
                    "capabilities": ToolRegistry.get_capabilities()
                }
            }
            await websocket.send(json.dumps(response_envelope))
            handshake_done = True
            logger.info(f"Handshake successful with device: {device_id} ({source.get('platform')})")

            # Register active connection
            from core.connection_manager import connection_manager
            connection_manager.register(
                websocket,
                device_id,
                source.get("platform", "unknown"),
                envelope.get("payload", {}).get("device_name", "")
            )

            # 2. Main Transaction Loop
            async for message in websocket:
                await self.process_packet(websocket, message, device_id)

        except websockets.exceptions.ConnectionClosed as e:
            logger.info(f"Connection closed by {client_address} (code={e.code}, reason='{e.reason}')")
        except Exception as e:
            logger.error(f"Error handling session: {e}", exc_info=True)
        finally:
            from core.connection_manager import connection_manager
            connection_manager.unregister(websocket)

    async def process_packet(self, websocket, raw_message: str, client_device_id: str):
        try:
            # Update connection activity
            from core.connection_manager import connection_manager
            connection_manager.update_activity(websocket)

            envelope = json.loads(raw_message)
            msg_type = envelope.get("type")
            msg_id = envelope.get("id")
            payload = envelope.get("payload", {})
            
            logger.info(f"Received packet type: {msg_type}, id: {msg_id}, payload: {payload}")

            if msg_type == "pair_request":
                # Handle pairing request
                code = payload.get("pairing_code")
                dev_name = payload.get("device_name", "Unknown Android Device")
                
                token = self.pairing_manager.pair_device(client_device_id, dev_name, code)
                
                resp_payload = {}
                if token:
                    logger.info(f"Pairing SUCCESS for device: {client_device_id} ({dev_name})")
                    resp_payload = {"status": "success", "token": token}
                else:
                    logger.warning(f"Pairing FAILED (incorrect code) for device: {client_device_id}")
                    resp_payload = {"status": "error", "message": "Incorrect pairing code"}

                await self.send_envelope(websocket, msg_id, "pair_response", client_device_id, resp_payload)

            elif msg_type == "tool_request":
                # Handle tool executions
                token = payload.get("token")
                tool_name = payload.get("tool")
                action = payload.get("action")
                args = payload.get("arguments", {})

                # Validate pairing token
                if not self.pairing_manager.verify_token(client_device_id, token):
                    logger.warning(f"Unauthorized command request from device: {client_device_id}")
                    err_payload = {"status": "error", "output": "Unauthorized: pairing token is invalid or expired."}
                    await self.send_envelope(websocket, msg_id, "tool_response", client_device_id, err_payload)
                    return

                tool = ToolRegistry.get_tool(tool_name)
                if not tool:
                    logger.warning(f"Requested tool '{tool_name}' not found in registry")
                    err_payload = {"status": "error", "output": f"Tool '{tool_name}' is not registered on this Windows Agent."}
                    await self.send_envelope(websocket, msg_id, "tool_response", client_device_id, err_payload)
                    return

                # Async progress callback helper
                async def send_progress(progress_msg: str):
                    progress_payload = {"state": "running", "message": progress_msg}
                    await self.send_envelope(websocket, msg_id, "tool_progress", client_device_id, progress_payload)

                # Execute tool
                logger.info(f"Executing tool: {tool_name}/{action} for {client_device_id}")
                result = await tool.execute(action, args, send_progress)
                logger.info(f"Tool {tool_name}/{action} completed with status: {result.get('status')}")

                await self.send_envelope(websocket, msg_id, "tool_response", client_device_id, result)

            elif msg_type == "heartbeat":
                # Heartbeat acknowledgement (log and echo)
                logger.debug(f"Heartbeat received from {client_device_id}")
                heartbeat_payload = {
                    "uptime": int(time.process_time()),
                    "agent_version": "1.0.0"
                }
                await self.send_envelope(websocket, msg_id, "heartbeat", client_device_id, heartbeat_payload)

            elif msg_type == "event":
                event_name = payload.get("event")
                if event_name == "heartbeat":
                    logger.debug(f"Event heartbeat received from {client_device_id}")
                    heartbeat_payload = {
                        "uptime": int(time.process_time()),
                        "agent_version": "1.0.0"
                    }
                    await self.send_envelope(websocket, msg_id, "heartbeat", client_device_id, heartbeat_payload)
                else:
                    logger.warning(f"Unknown event name: {event_name}")

            else:
                logger.warning(f"Unknown message type: {msg_type}")
                
        except Exception as e:
            logger.error(f"Error processing packet: {e}", exc_info=True)

    async def send_envelope(self, websocket, request_id: str, msg_type: str, target_device_id: str, payload: dict):
        envelope = {
            "protocol_version": PROTOCOL_VERSION,
            "id": request_id,
            "type": msg_type,
            "timestamp": int(time.time()),
            "source": {
                "device_id": "windows-main",
                "platform": "windows"
            },
            "target": {
                "device_id": target_device_id
            },
            "payload": payload
        }
        await websocket.send(json.dumps(envelope))
