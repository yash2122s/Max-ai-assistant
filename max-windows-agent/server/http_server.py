import asyncio
import logging
from typing import Callable, Awaitable

logger = logging.getLogger("HTTPServer")

class HTTPServer:
    def __init__(self, host: str, port: int, handler: Callable[[str, str, dict, bytes, asyncio.StreamWriter], Awaitable[None]]):
        self.host = host
        self.port = port
        self.handler = handler
        self.server = None

    async def start(self):
        self.server = await asyncio.start_server(self.handle_connection, self.host, self.port)
        logger.info(f"HTTP server listening on http://{self.host}:{self.port}")

    async def stop(self):
        if self.server:
            self.server.close()
            await self.server.wait_closed()
            logger.info("HTTP server stopped")

    async def handle_connection(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        try:
            # Read HTTP request line (e.g. GET /health HTTP/1.1)
            request_line = await reader.readline()
            if not request_line:
                writer.close()
                return
                
            req_str = request_line.decode("utf-8").strip()
            parts = req_str.split(" ")
            if len(parts) < 2:
                writer.close()
                return
                
            method, path = parts[0], parts[1]
            
            # Read HTTP headers
            headers = {}
            while True:
                line = await reader.readline()
                if line == b"\r\n" or line == b"\n" or not line:
                    break
                header_str = line.decode("utf-8").strip()
                if ":" in header_str:
                    k, v = header_str.split(":", 1)
                    headers[k.strip().lower()] = v.strip()

            # Read request body if Content-Length header is specified
            body = b""
            content_length = int(headers.get("content-length", 0))
            if content_length > 0:
                body = await reader.readexact(content_length)

            # Delegate to router handler callback
            await self.handler(method, path, headers, body, writer)
            
        except Exception as e:
            logger.error(f"Error handling HTTP request: {e}", exc_info=True)
            try:
                writer.write(
                    b"HTTP/1.1 500 Internal Server Error\r\n"
                    b"Content-Type: application/json\r\n"
                    b"Connection: close\r\n\r\n"
                    b'{"success":false,"error":{"code":"INTERNAL_ERROR","message":"Internal Server Error"}}'
                )
                await writer.drain()
            except Exception:
                pass
        finally:
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass
