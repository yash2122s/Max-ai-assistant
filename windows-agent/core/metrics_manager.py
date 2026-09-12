import threading
import time
import os
import platform
import psutil
from typing import Dict, Any

class MetricsManager:
    def __init__(self):
        self._lock = threading.Lock()
        self.events_sent = 0
        self.connections = 0
        self.tool_requests = 0
        self.avg_latency_ms = 0.0
        self._latency_count = 0
        self.start_time = time.time()

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
            self.avg_latency_ms += (latency_ms - self.avg_latency_ms) / self._latency_count

    def get_metrics(self) -> Dict[str, Any]:
        with self._lock:
            # Real-time system hardware telemetry
            cpu_pct = psutil.cpu_percent(interval=None)
            cpu_cores = psutil.cpu_count(logical=True)
            mem = psutil.virtual_memory()
            
            try:
                disk = psutil.disk_usage('C:\\' if os.name == 'nt' else '/')
                disk_pct = disk.percent
                disk_used_gb = round(disk.used / (1024 ** 3), 1)
                disk_total_gb = round(disk.total / (1024 ** 3), 1)
            except Exception:
                disk_pct = 0.0
                disk_used_gb = 0.0
                disk_total_gb = 0.0

            boot_time = psutil.boot_time()
            sys_uptime = round(time.time() - boot_time, 1)
            agent_uptime = round(time.time() - self.start_time, 1)

            return {
                "events_sent": self.events_sent,
                "connections": self.connections,
                "tool_requests": self.tool_requests,
                "avg_latency_ms": round(self.avg_latency_ms, 2),
                "cpu_percent": round(cpu_pct, 1),
                "cpu_cores": cpu_cores,
                "ram_percent": round(mem.percent, 1),
                "ram_used_gb": round((mem.total - mem.available) / (1024 ** 3), 1),
                "ram_total_gb": round(mem.total / (1024 ** 3), 1),
                "disk_percent": disk_pct,
                "disk_used_gb": disk_used_gb,
                "disk_total_gb": disk_total_gb,
                "system_uptime": sys_uptime,
                "agent_uptime": agent_uptime,
                "os_info": f"{platform.system()} {platform.release()}",
                "hostname": platform.node()
            }

# Global singleton
metrics_manager = MetricsManager()

