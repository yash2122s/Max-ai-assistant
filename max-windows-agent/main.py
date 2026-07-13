import asyncio
import sys
from config import load_config
from server.websocket_server import WebSocketServer

# Import tools package to trigger auto-registration of CmdTool
import tools

def main():
    print("=========================================")
    print("        MAX Windows Agent (Phase 1A)      ")
    print("=========================================")

    # Load server configuration
    config = load_config()
    host = config.get("host", "0.0.0.0")
    port = config.get("port", 9000)

    # Initialize the websocket server
    server = WebSocketServer(host, port)

    print(f"Server Host  : {host}")
    print(f"Server Port  : {port}")
    print(f"Pairing Code : \033[92m{server.pairing_manager.get_pairing_code()}\033[0m")
    print("-----------------------------------------")
    print("Start your Android client, pair, and send commands.")
    print("Press Ctrl+C to terminate the server.")
    print("=========================================")

    try:
        asyncio.run(server.start())
    except KeyboardInterrupt:
        print("\nStopping MAX Windows Agent server...")
        sys.exit(0)
    except Exception as e:
        print(f"\nServer error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
