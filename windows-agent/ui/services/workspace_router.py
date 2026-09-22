"""
MAX UI — Workspace Router
Manages workspace switching, history navigation, and deep-linking.
"""

from typing import List, Optional
from PyQt6.QtCore import QObject, pyqtSignal

from ..models.ui_state import WorkspaceType
from .state_store import state_store


class WorkspaceRouter(QObject):
    workspace_navigated = pyqtSignal(str)  # emits workspace name

    def __init__(self, max_history: int = 50):
        super().__init__()
        self._history: List[WorkspaceType] = [WorkspaceType.HOME]
        self._history_index: int = 0
        self._max_history = max_history

    @property
    def current_workspace(self) -> WorkspaceType:
        return state_store.state.workspace

    def navigate_to(self, workspace: WorkspaceType | str) -> None:
        if isinstance(workspace, str):
            workspace = WorkspaceType(workspace)

        if self.current_workspace != workspace:
            # Truncate forward history if navigating from a past state
            if self._history_index < len(self._history) - 1:
                self._history = self._history[:self._history_index + 1]

            self._history.append(workspace)
            if len(self._history) > self._max_history:
                self._history.pop(0)
            self._history_index = len(self._history) - 1

            state_store.set_workspace(workspace)
            self.workspace_navigated.emit(workspace.value)

    def can_go_back(self) -> bool:
        return self._history_index > 0

    def can_go_forward(self) -> bool:
        return self._history_index < len(self._history) - 1

    def go_back(self) -> Optional[WorkspaceType]:
        if self.can_go_back():
            self._history_index -= 1
            target = self._history[self._history_index]
            state_store.set_workspace(target)
            self.workspace_navigated.emit(target.value)
            return target
        return None

    def go_forward(self) -> Optional[WorkspaceType]:
        if self.can_go_forward():
            self._history_index += 1
            target = self._history[self._history_index]
            state_store.set_workspace(target)
            self.workspace_navigated.emit(target.value)
            return target
        return None


# Global singleton
workspace_router = WorkspaceRouter()
