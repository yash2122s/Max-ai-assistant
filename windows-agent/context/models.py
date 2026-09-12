"""
MAX AI — Context Models
Typed, schema-versioned models for ephemeral runtime observation records and snapshots.
"""

import time
import uuid
from enum import Enum
from dataclasses import dataclass, field, asdict
from typing import Dict, Any, List, Optional


class ContextOrigin(str, Enum):
    LOCAL_WINDOWS = "local_windows"
    REMOTE_ANDROID = "remote_android"
    SYSTEM = "system"


class ContextCategory(str, Enum):
    ACTIVE_WINDOW = "active_window"
    ACTIVE_APP = "active_app"
    FOCUSED_UI = "focused_ui"
    DEVICE_STATE = "device_state"
    ACTIVE_TASK = "active_task"
    NOTIFICATIONS = "notifications"
    ECOSYSTEM_DEVICES = "ecosystem_devices"


# Category-specific default TTLs (in seconds)
DEFAULT_CATEGORY_TTLS: Dict[ContextCategory, float] = {
    ContextCategory.ACTIVE_WINDOW: 60.0,    # 1 minute
    ContextCategory.ACTIVE_APP: 60.0,       # 1 minute
    ContextCategory.FOCUSED_UI: 30.0,       # 30 seconds
    ContextCategory.DEVICE_STATE: 120.0,    # 2 minutes
    ContextCategory.ACTIVE_TASK: 300.0,     # 5 minutes
    ContextCategory.NOTIFICATIONS: 300.0,   # 5 minutes
    ContextCategory.ECOSYSTEM_DEVICES: 120.0, # 2 minutes
}


@dataclass
class ContextRecord:
    """
    Represents a single ephemeral runtime observation.
    """
    record_id: str
    category: ContextCategory
    origin: ContextOrigin
    payload: Dict[str, Any]
    updated_at: float = field(default_factory=time.time)
    ttl_seconds: float = 60.0
    schema_version: str = "1.0"

    def __post_init__(self):
        if not self.record_id:
            self.record_id = f"ctx_{uuid.uuid4().hex[:8]}"
        if isinstance(self.category, str) and not isinstance(self.category, ContextCategory):
            self.category = ContextCategory(self.category)
        if isinstance(self.origin, str) and not isinstance(self.origin, ContextOrigin):
            self.origin = ContextOrigin(self.origin)
        if self.ttl_seconds <= 0:
            self.ttl_seconds = DEFAULT_CATEGORY_TTLS.get(self.category, 60.0)

    @property
    def is_expired(self) -> bool:
        """Returns True if the current time exceeds updated_at + ttl_seconds."""
        return (time.time() - self.updated_at) > self.ttl_seconds

    def to_dict(self) -> Dict[str, Any]:
        return {
            "record_id": self.record_id,
            "category": self.category.value if isinstance(self.category, ContextCategory) else str(self.category),
            "origin": self.origin.value if isinstance(self.origin, ContextOrigin) else str(self.origin),
            "payload": self.payload,
            "updated_at": self.updated_at,
            "ttl_seconds": self.ttl_seconds,
            "schema_version": self.schema_version,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'ContextRecord':
        return cls(
            record_id=data.get("record_id", ""),
            category=ContextCategory(data["category"]),
            origin=ContextOrigin(data.get("origin", ContextOrigin.LOCAL_WINDOWS.value)),
            payload=data.get("payload", {}),
            updated_at=float(data.get("updated_at", time.time())),
            ttl_seconds=float(data.get("ttl_seconds", 60.0)),
            schema_version=data.get("schema_version", "1.0"),
        )


@dataclass
class ContextSnapshot:
    """
    Point-in-time snapshot of runtime context records across categories.
    Preserves multi-record cardinality (e.g. notifications list).
    """
    snapshot_id: str
    timestamp: float
    records: Dict[str, List[ContextRecord]] = field(default_factory=dict)
    schema_version: str = "1.0"

    def __post_init__(self):
        if not self.snapshot_id:
            self.snapshot_id = f"snap_{uuid.uuid4().hex[:8]}"

    def get_category_records(self, category: ContextCategory) -> List[ContextRecord]:
        """Returns all active records for a given category."""
        cat_key = category.value if isinstance(category, ContextCategory) else str(category)
        return self.records.get(cat_key, [])

    def to_dict(self) -> Dict[str, Any]:
        return {
            "snapshot_id": self.snapshot_id,
            "timestamp": self.timestamp,
            "records": {
                cat: [r.to_dict() for r in r_list]
                for cat, r_list in self.records.items()
            },
            "schema_version": self.schema_version,
        }
