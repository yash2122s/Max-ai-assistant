"""
MAX AI — Lightweight Startup Profiler
Singleton bridge for stage-by-stage startup benchmarking.
"""
from time import perf_counter
from contextlib import contextmanager
import logging

_startup_t0 = perf_counter()


class StartupProfiler:
    _instance = None

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance.t0 = _startup_t0
            cls._instance.last = _startup_t0
            cls._instance.stages = []
        return cls._instance

    def mark(self, name: str):
        now = perf_counter()
        delta = now - self.last
        total = now - self.t0

        self.stages.append({
            "name": name,
            "delta_ms": round(delta * 1000, 2),
            "total_ms": round(total * 1000, 2),
        })

        logging.getLogger("MAX.StartupProfiler").info(
            "STARTUP_STAGE %-28s +%8.2f ms | total=%9.2f ms",
            name,
            delta * 1000,
            total * 1000,
        )

        self.last = now

    def report(self):
        logger = logging.getLogger("MAX.StartupProfiler")
        lines = [
            "=" * 78,
            "MAX STARTUP PROFILE",
            "=" * 78,
        ]
        for s in self.stages:
            lines.append(
                f"{s['name']:<28} {s['delta_ms']:8.2f} ms | cumulative {s['total_ms']:9.2f} ms"
            )
        lines.append("=" * 78)

        report_text = "\n" + "\n".join(lines) + "\n"
        logger.info(report_text)
        print(report_text, flush=True)


profiler = StartupProfiler()
