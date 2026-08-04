# MAX Agent Plugin API Specification

This document details the interface contracts and lifecycle bounds for external pluggable connectors.

## Plugin Manifest (`plugin.json`)
Every plugin must reside in a subdirectory and supply a manifest:
```json
{
  "name": "vision_connector",
  "version": "1.0.0",
  "api_version": 1,
  "description": "OCR and screen analysis automation tool",
  "permissions": [
    "screenshot",
    "ocr_read"
  ],
  "entry_point": "connector.py"
}
```

## Plugin Lifecycle
Plugins are instantiated dynamically on startup and must implement three standard asynchronous methods:
```python
class BasePlugin:
    async def initialize(self, context) -> None:
        """Executed immediately after importing the module."""
        pass

    async def start(self) -> None:
        """Executed during the main agent server startup loop."""
        pass

    async def stop(self) -> None:
        """Executed during server shutdown to release file handles or network ports."""
        pass
```

## Registration Hooks
Plugins hook into the central agent runtime using registration callbacks provided in `context`:
- `context.register_tool(tool_name: str, action_handler: Callable[[str, dict], Awaitable[dict]])`
- `context.register_event_subscriber(event_type: EventType, callback: Callable[[dict], Awaitable[None]])`
