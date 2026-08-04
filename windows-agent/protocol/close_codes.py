from enum import Enum

class CloseCode(Enum):
    NORMAL = 1000
    PROTOCOL_MISMATCH = 1003
    UNAUTHORIZED = 4001
    INVALID_PAYLOAD = 4002
