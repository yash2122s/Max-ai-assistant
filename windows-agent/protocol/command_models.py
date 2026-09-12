"""
MAX AI — Remote Command Protocol Models
Authoritative data models and status codes for P2.1 Remote Command Protocol.
"""

from dataclasses import dataclass, field, asdict
from typing import Dict, Any, Optional
from enum import Enum


class CommandStatus(str, Enum):
    SUCCESS = "success"
    FAILED = "failed"
    REJECTED = "rejected"
    TIMEOUT = "timeout"
    ACKNOWLEDGED = "acknowledged"
    INVALID_REQUEST = "invalid_request"
    UNAUTHORIZED = "unauthorized"
    UNSUPPORTED = "unsupported"


class CommandErrorCode(str, Enum):
    MALFORMED_REQUEST = "MALFORMED_REQUEST"
    UNAUTHORIZED = "UNAUTHORIZED"
    INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
    UNSUPPORTED_COMMAND = "UNSUPPORTED_COMMAND"
    EXECUTION_FAILED = "EXECUTION_FAILED"
    TIMEOUT = "TIMEOUT"
    DEVICE_MISMATCH = "DEVICE_MISMATCH"
    DANGEROUS_PAYLOAD = "DANGEROUS_PAYLOAD"
    INVALID_TIMEOUT = "INVALID_TIMEOUT"


@dataclass
class CommandError:
    code: str
    message: str
    details: Optional[Dict[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        d: Dict[str, Any] = {
            "code": self.code,
            "message": self.message
        }
        if self.details:
            d["details"] = self.details
        return d


@dataclass
class CommandRequestPayload:
    command_id: str
    tool: str
    action: str
    arguments: Dict[str, Any] = field(default_factory=dict)
    timeout_ms: int = 5000

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'CommandRequestPayload':
        filtered = {k: v for k, v in data.items() if k in cls.__dataclass_fields__}
        return cls(**filtered)


@dataclass
class CommandResultPayload:
    command_id: str
    status: str
    action: str
    data: Dict[str, Any] = field(default_factory=dict)
    error: Optional[Dict[str, Any]] = None
    execution_time_ms: int = 0

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'CommandResultPayload':
        filtered = {k: v for k, v in data.items() if k in cls.__dataclass_fields__}
        return cls(**filtered)
