from enum import Enum

class PacketType(Enum):
    HELLO = "hello"
    PAIR_REQUEST = "pair_request"
    PAIR_RESPONSE = "pair_response"
    TOOL_REQUEST = "tool_request"
    TOOL_PROGRESS = "tool_progress"
    TOOL_RESPONSE = "tool_response"
    HEARTBEAT = "heartbeat"
