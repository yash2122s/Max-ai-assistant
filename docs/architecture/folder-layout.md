# Folder Layouts, Naming Conventions, & Versioning

This document outlines the codebase directory layout, styles, naming policies, and versioning rules.

## Directory Tree Layout
```text
max-windows-agent/
├── core/                  # State Managers Layer
│   ├── connection_manager.py
│   ├── metrics_manager.py
│   ├── session_manager.py
│   └── settings_manager.py
├── protocol/              # Shared Protocol Models
│   ├── close_codes.py
│   ├── constants.py
│   ├── error_codes.py
│   ├── event_types.py
│   ├── packet_factory.py
│   ├── packet_types.py
│   ├── setting_keys.py
│   ├── validators.py
│   └── versions.py
├── server/                # Network Sockets & REST Handlers
│   ├── api_router.py
│   ├── event_bus.py
│   ├── http_server.py
│   └── websocket_server.py
├── tools/                 # Command Automation Dispatcher
│   ├── base_tool.py
│   ├── cmd_tool.py
│   └── tool_registry.py
├── plugins/               # Extensible Connectors Hook (Reserved)
├── storage/               # Saved state / logs (Runtime-Created)
│   ├── config.json
│   ├── paired_devices.json
│   └── logs/max-agent.log
└── tests/                 # Automated Tests Suite
    ├── test_architecture.py
    └── test_event_bus.py
```

---

## Coding Style & Casing Rules

1. **Python Files**: `snake_case.py` (e.g. `websocket_server.py`).
2. **Classes**: `PascalCase` (e.g. `WebSocketServer`, `SettingsManager`).
3. **Methods & Functions**: `snake_case` (e.g. `process_packet()`).
4. **JSON Fields**: `snake_case` (e.g. `pairing_code`, `device_name`).
5. **Event Names**: `snake_case` (e.g. `client_connected`).
6. **API Routes**: `/api/v1/...` (lowercase, slash-separated).
7. **Tool Names**: `lowercase` (e.g. `cmd`).
8. **Error Codes**: `UPPER_SNAKE_CASE` (e.g. `UNAUTHORIZED`, `NOT_FOUND`).

---

## Semantic Versioning Policy
To avoid cascade breaks when adding modules, version tags are tracked independently:

- **Agent Version** (e.g. `1.0.0`): Follows SemVer. Changes when we introduce new features, widgets, or internal optimizations.
- **Protocol Version** (e.g. `1`): Monotonically increasing integer. Changes only when WebSocket handshake formats or binary framing protocols are modified in a breaking way.
- **API Version** (e.g. `1`): Prefixed in routes (`/api/v1/`). Incremented when REST pathways or JSON response structures undergo breaking refactoring.
- **Event Schema Version** (e.g. `1`): Specified in domain event envelopes. Incremented when event schemas or metadata structures are restructured.
