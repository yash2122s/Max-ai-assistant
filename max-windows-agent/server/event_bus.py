import asyncio
import time
import secrets
import copy
from collections import deque
from typing import Set, Dict, Any, List

class EventBus:
    def __init__(self):
        # Set of active async queues (subscribers)
        self.subscribers: Set[asyncio.Queue] = set()
        # Ring buffer for event replay (maxlen=100)
        self.buffer = deque(maxlen=100)

    def subscribe(self, queue: asyncio.Queue):
        self.subscribers.add(queue)

    def unsubscribe(self, queue: asyncio.Queue):
        self.subscribers.discard(queue)

    def publish(self, event_type: str, payload: Dict[str, Any]) -> str:
        # Construct the event envelope
        event = {
            "schema_version": 1,
            "id": f"evt_{secrets.token_hex(8)}",
            "type": event_type,
            "timestamp": time.time(),
            "payload": copy.deepcopy(payload)
        }
        
        # Store in replay buffer
        self.buffer.append(event)
        
        # Publish to active subscribers
        for queue in list(self.subscribers):
            try:
                # Put a copy so subscribers don't mutate each other's events
                queue.put_nowait(copy.deepcopy(event))
            except Exception:
                pass
        return event["id"]

    def get_events_after(self, last_event_id: str) -> List[Dict[str, Any]]:
        # Find if last_event_id is in the buffer
        found_index = -1
        for idx, event in enumerate(self.buffer):
            if event["id"] == last_event_id:
                found_index = idx
                break
                
        # If found, return all events after that index.
        # If not found (or buffer has wrapped and the ID is gone),
        # return all events currently in the buffer.
        if found_index != -1:
            return list(self.buffer)[found_index + 1:]
        else:
            return list(self.buffer)

# Global singleton
event_bus = EventBus()
