"""
MAX AI — Context Package
Exports ContextRecord, ContextSnapshot, ContextCategory, ContextOrigin,
ContextStore, ContextPolicy, build_context_block, and canonical singletons.
"""

from .models import ContextRecord, ContextSnapshot, ContextCategory, ContextOrigin
from .policy import ContextPolicy, context_policy
from .store import ContextStore, context_store
from .providers import ActiveWindowProvider, DeviceStateProvider, TaskContextProvider, FocusedUiProvider
from .builder import build_context_block, refresh_local_context

__all__ = [
    "ContextRecord",
    "ContextSnapshot",
    "ContextCategory",
    "ContextOrigin",
    "ContextPolicy",
    "context_policy",
    "ContextStore",
    "context_store",
    "ActiveWindowProvider",
    "DeviceStateProvider",
    "TaskContextProvider",
    "FocusedUiProvider",
    "build_context_block",
    "refresh_local_context",
]
