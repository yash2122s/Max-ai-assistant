"""
MAX UI Workspaces Package
"""

from .home import HomeWorkspace
from .chat import ChatWorkspace
from .computer_use import ComputerUseWorkspace
from .browser import BrowserWorkspace
from .coding import CodingWorkspace
from .files import FilesWorkspace
from .tasks import TasksWorkspace
from .memory import MemoryWorkspace
from .devices import DevicesWorkspace
from .settings import SettingsWorkspace

__all__ = [
    "HomeWorkspace",
    "ChatWorkspace",
    "ComputerUseWorkspace",
    "BrowserWorkspace",
    "CodingWorkspace",
    "FilesWorkspace",
    "TasksWorkspace",
    "MemoryWorkspace",
    "DevicesWorkspace",
    "SettingsWorkspace",
]
