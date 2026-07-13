# Transport Layer & Network Protocol Specifications

This document defines the framing schemas, REST api structures, and SSE push stream behaviors.

## 1. WebSocket Protocol (Port `9000`)

### Handshake Sequence
Clients initiate communication by transmitting a `hello` packet:
```json
{
  "protocol_version": 1,
  "id": "handshake_1",
  "type": "hello",
  "timestamp": 1783939681,
  "source": {
    "device_id": "client-device-id",
    "platform": "android"
  },
  "target": {
    "device_id": "windows-main"
  },
  "payload": {
    "device_name": "Redmi K20 Pro"
  }
}
```

The Windows Agent replies with a server `hello` confirming the API version and registration capabilities:
```json
{
  "protocol_version": 1,
  "api_version": 1,
  "id": "handshake_1",
  "type": "hello",
  "timestamp": 1783939682,
  "source": {
    "device_id": "windows-main",
    "platform": "windows"
  },
  "target": {
    "device_id": "client-device-id"
  },
  "payload": {
    "device_name": "MAX Windows Agent",
    "capabilities": {
      "cmd": 1
    }
  }
}
```

---

## 2. Versioned HTTP REST API (Port `9001`)

Every HTTP route returns JSON conforming to standard envelopes.

### Route Registry

#### `GET /api/v1/health` (Alias: `/health`)
- **Description**: Verifies HTTP server availability.
- **Success Response (`200 OK`)**:
  ```json
  {"success": true, "data": {"status": "ok"}}
  ```

#### `GET /api/v1/metrics` (Alias: `/metrics`)
- **Description**: Exposes telemetry counters and command runtimes.
- **Success Response (`200 OK`)**:
  ```json
  {
    "success": true,
    "data": {
      "events_sent": 45,
      "connections": 1,
      "tool_requests": 204,
      "avg_latency_ms": 0.54
    }
  }
  ```

#### `GET /api/v1/status` (Alias: `/api/status`)
- **Description**: Returns live connection detail vectors safely scrubbed of authorization codes/tokens.
- **Success Response (`200 OK`)**:
  ```json
  {
    "success": true,
    "data": {
      "agent_version": "1.0.0",
      "protocol_version": 1,
      "uptime": 3600.0,
      "connected_devices": 1,
      "paired_devices": 1,
      "connections": [
        {
          "device_id": "client-device-id",
          "platform": "android",
          "device_name": "Redmi K20 Pro",
          "connected_at": 1783939000,
          "last_seen": 1783939600,
          "latency_ms": 12.5
        }
      ],
      "http_port": 9001,
      "ws_port": 9000
    }
  }
  ```

---

## 3. Server-Sent Events (SSE) (Port `9001`)

### Endpoint: `GET /api/v1/events` (Alias: `/api/events`)
- **MIME Type**: `text/event-stream`
- **Replay Handshake**: Supported using standard `Last-Event-ID` header or `last_event_id` URL query parameters.
- **Keepalive Comments**: The server pushes empty comments (`: keepalive\n\n`) every 20 seconds to prevent network dropouts.
- **Message Serialization Format**:
  ```text
  id: evt_ab12cd34ef567890
  event: tool_completed
  data: {"schema_version": 1, "id": "evt_ab12cd34ef567890", ...}
  ```
