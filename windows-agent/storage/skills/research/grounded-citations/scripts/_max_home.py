"""Resolve MAX_HOME for standalone skill scripts."""

from __future__ import annotations

import os
from pathlib import Path


def get_max_home() -> Path:
    """Return the MAX home directory (default: ``~/.max``)."""
    val = os.environ.get("MAX_HOME", "").strip() or os.environ.get("HERMES_HOME", "").strip()
    return Path(val) if val else Path.home() / ".max"


# Compatibility alias
get_hermes_home = get_max_home

