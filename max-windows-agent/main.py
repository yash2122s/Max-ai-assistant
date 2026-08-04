import asyncio
import sys
from config import load_config
from server.websocket_server import WebSocketServer
from server.http_server import HTTPServer
from server.api_router import handle_request as http_handler
from server.event_bus import event_bus

from protocol import SettingKey, EventType

# Import tools package to trigger auto-registration of CmdTool
import tools

async def run_servers(ws_server, http_server):
    # Publish agent startup domain event
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
        # Publish agent shutdown domain event
        event_bus.publish(EventType.AGENT_SHUTDOWN, {})
        await http_server.stop()

def main():
    print("=========================================")
    print("        MAX Windows Agent v3.0 (Full GA)   ")
    print("=========================================")

    # Load server configuration
    config = load_config()
    host = config.get(SettingKey.HOST.value, "0.0.0.0")
    ws_port = config.get(SettingKey.PORT.value, 9000)
    http_port = config.get(SettingKey.HTTP_PORT.value, 9001)

    from server.api_router import get_local_ip
    local_ip = get_local_ip()

    # Initialize the servers
    ws_server = WebSocketServer(host, ws_port)
    http_server = HTTPServer(host, http_port, http_handler)
 
    print(f"Server Host  : {host} (Listening on LAN)")
    print(f"WS Port      : {ws_port}")
    print(f"HTTP Port    : {http_port}")
    print(f"Dashboard URL: \033[94mhttp://localhost:{http_port}/\033[0m")
    print(f"Pairing Code : \033[92m{ws_server.pairing_manager.get_pairing_code()}\033[0m")
    print("-----------------------------------------")
    print("Scan the QR code on the Dashboard page or enter manually on phone.")
    print("Press Ctrl+C to terminate the server.")
    print("=========================================")


    try:
        asyncio.run(run_servers(ws_server, http_server))
    except KeyboardInterrupt:
        print("\nStopping MAX Windows Agent server...")
        sys.exit(0)
    except Exception as e:
        print(f"\nServer error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    main()
