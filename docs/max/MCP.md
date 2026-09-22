# MAX Model Context Protocol (MCP) Ecosystem (v1)

## Overview
The Model Context Protocol (MCP) subsystem allows MAX to securely connect to external MCP servers (stdio, HTTP, SSE), discover external tools, and execute them under the single MAX Brain, Unified Tool Registry, and Safety Gate.

---

## Target Architecture

```
User / Voice / Chat
        │
       MAX
        │
  MaxOrchestrator
        │
     Planner
        │
    MCP Tool
        │
 Schema Validation
        │
Risk Classification (LOW / MEDIUM / HIGH)
        │
 MAX Safety Gate
        │
Confirmation (if HIGH)
        │
Execute over JSON-RPC 2.0 Transport
        │
   Audit Logging
        │
   MAX Context
```

---

## Core Modules (`windows-agent/core/mcp/`)

| Module | Responsibility |
|---|---|
| `models.py` | Typed contracts: `MCPServerConfig`, `MCPToolDeclaration`, `MCPToolResult`, `MCPRiskLevel`. |
| `mcp_transport.py` | Real JSON-RPC 2.0 transport over stdin/stdout pipes with `shell=False`. |
| `mcp_schema.py` | JSON Schema argument validation against tool definitions. |
| `mcp_permissions.py` | Risk classification mapping tools to LOW, MEDIUM, or HIGH risk. |
| `mcp_audit.py` | Structured audit logging with sensitive token/password redaction. |
| `mcp_registry.py` | Discovered MCP tools catalog indexed by canonical and server namespaces. |
| `mcp_executor.py` | Safe execution pipeline enforcing validation, timeouts, and safety gates. |
| `mcp_client.py` | Connection lifecycle management for a single MCP server. |
| `mcp_manager.py` | Multi-server manager loading configuration from `storage/config.json`. |

---

## Configuration Example (`storage/config.json`)
```json
{
  "mcp_servers": {
    "filesystem": {
      "transport": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "C:/Workspace"],
      "enabled": true,
      "auto_start": false
    }
  }
}
```
