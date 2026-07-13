# Public API Specifications

This document declares the frozen public interfaces, schemas, and entry points of the MAX Agent codebase. Everything else not listed here is considered private implementation detail.

## 1. Exported Managers
Application code interfaces with global singleton systems imported from `core`:
- `settings_manager`: Access configurations via `get(key, default)` and `set(key, value)`.
- `session_manager`: Verify pairing credentials via `paired_devices`.
- `connection_manager`: Register/unregister sockets and check active device arrays.
- `metrics_manager`: Access counters and running latencies via `get_metrics()`.

## 2. Shared Protocol Enums
Exported from the `protocol` package:
- `EventType`: Domain event tags.
- `PacketType`: WebSocket payload framing types.
- `CloseCode`: WebSocket shutdown close code integers.
- `ErrorCode`: REST JSON payload response failure types.
- `SettingKey`: Configuration key string hashes.

## 3. Extensible Tool Interface
Command providers inherit from `BaseTool` and register through `ToolRegistry`:
```python
from tools.base_tool import BaseTool
from tools.tool_registry import ToolRegistry

class CustomTool(BaseTool):
    def get_actions(self) -> list:
        return ["action_name"]

    async def execute(self, action: str, args: dict, progress_callback) -> dict:
        return {"status": "success", "output": "Done"}

ToolRegistry.register("custom", CustomTool())
```

## 4. Frozen HTTP endpoints
Exposed on HTTP server port `9001`:
- `GET /api/v1/health`
- `GET /api/v1/metrics`
- `GET /api/v1/status`
- `GET /api/v1/events` (SSE Push stream)
