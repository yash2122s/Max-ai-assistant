import threading
from typing import Dict, Any

class MetricsManager:
    def __init__(self):
        self._lock = threading.Lock()
        self.events_sent = 0
        self.connections = 0
        self.tool_requests = 0
        self.avg_latency_ms = 0.0
        self._latency_count = 0

    def increment_events(self, count: int = 1):
        with self._lock:
            self.events_sent += count

    def increment_requests(self, count: int = 1):
        with self._lock:
            self.tool_requests += count

    def set_connection_count(self, count: int):
        with self._lock:
            self.connections = count

    def increment_connections(self, count: int = 1):
        with self._lock:
            self.connections += count

    def decrement_connections(self, count: int = 1):
        with self._lock:
            self.connections = max(0, self.connections - count)

    def update_latency(self, latency_ms: float):
        with self._lock:
            self._latency_count += 1
            # Running average formula:
            # avg = avg + (new_value - avg) / count
            self.avg_latency_ms += (latency_ms - self.avg_latency_ms) / self._latency_count

    def get_metrics(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "events_sent": self.events_sent,
                "connections": self.connections,
                "tool_requests": self.tool_requests,
                "avg_latency_ms": round(self.avg_latency_ms, 2)
            }

# Global singleton
metrics_manager = MetricsManager()
