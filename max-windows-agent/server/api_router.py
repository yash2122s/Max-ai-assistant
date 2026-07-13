import json

async def handle_request(method: str, path: str, headers: dict, body: bytes, writer):
    if path == "/health" and method == "GET":
        resp_payload = {
            "success": True,
            "data": {
                "status": "ok"
            }
        }
        resp_bytes = json.dumps(resp_payload).encode("utf-8")
        headers_bytes = (
            f"HTTP/1.1 200 OK\r\n"
            f"Content-Type: application/json\r\n"
            f"Content-Length: {len(resp_bytes)}\r\n"
            f"Access-Control-Allow-Origin: *\r\n"
            f"Connection: close\r\n\r\n"
        ).encode("utf-8")
        writer.write(headers_bytes + resp_bytes)
        await writer.drain()
    else:
        # Route not found fallback
        resp_payload = {
            "success": False,
            "error": {
                "code": "NOT_FOUND",
                "message": f"Route not found: {method} {path}"
            }
        }
        resp_bytes = json.dumps(resp_payload).encode("utf-8")
        headers_bytes = (
            f"HTTP/1.1 404 Not Found\r\n"
            f"Content-Type: application/json\r\n"
            f"Content-Length: {len(resp_bytes)}\r\n"
            f"Access-Control-Allow-Origin: *\r\n"
            f"Connection: close\r\n\r\n"
        ).encode("utf-8")
        writer.write(headers_bytes + resp_bytes)
        await writer.drain()
