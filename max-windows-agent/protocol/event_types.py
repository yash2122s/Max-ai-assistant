from enum import Enum

class EventType(Enum):
    AGENT_STARTED = "agent_started"
    AGENT_SHUTDOWN = "agent_shutdown"
    CLIENT_CONNECTED = "client_connected"
    CLIENT_DISCONNECTED = "client_disconnected"
    PAIR_STARTED = "pair_started"
    PAIR_SUCCESS = "pair_success"
    PAIR_FAILED = "pair_failed"
    TOOL_REQUESTED = "tool_requested"
    TOOL_PROGRESS = "tool_progress"
    TOOL_COMPLETED = "tool_completed"
    TOOL_FAILED = "tool_failed"
    HEARTBEAT = "heartbeat"
