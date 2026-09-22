"""
MAX UI — Central State Store Singleton
Provides high-level actions and mutations over UiState.
"""

from typing import Optional, Dict, Any, List
import time
import uuid

from ..models.ui_state import UiState, WorkspaceType, PresentationMode, VoiceState, AgentState
from ..models.chat_models import ChatMessage, MessageRole
from ..models.task_models import TaskItem, TaskStatus, TaskStepItem, ConfirmationRequest
from ..models.computer_use_models import ComputerUseState, ActionPhase, PerceptionBox


class StateStore:
    """
    Central store for UI state management. Encapsulates business mutations
    and ensures all modifications are dispatched cleanly to UiState Qt signals.
    """

    def __init__(self, state: Optional[UiState] = None):
        self.state = state or UiState()

    def set_workspace(self, ws: WorkspaceType | str) -> None:
        self.state.set_workspace(ws)

    def set_presentation_mode(self, mode: PresentationMode | str) -> None:
        self.state.set_presentation_mode(mode)

    def toggle_presentation_mode(self) -> PresentationMode:
        current = self.state.presentation_mode
        if current == PresentationMode.FULL_SCREEN:
            new_mode = PresentationMode.DYNAMIC_ISLAND_MINI
        else:
            new_mode = PresentationMode.FULL_SCREEN
        self.state.set_presentation_mode(new_mode)
        return new_mode

    def add_user_message(self, text: str) -> ChatMessage:
        msg = ChatMessage(
            id=str(uuid.uuid4()),
            role=MessageRole.USER,
            content=text,
            timestamp=time.time(),
        )
        self.state.add_chat_message(msg)
        return msg

    def add_assistant_message(self, text: str, is_streaming: bool = False) -> ChatMessage:
        msg = ChatMessage(
            id=str(uuid.uuid4()),
            role=MessageRole.ASSISTANT,
            content=text,
            timestamp=time.time(),
            is_streaming=is_streaming,
        )
        self.state.add_chat_message(msg)
        return msg

    def start_or_update_task(self, task_id: str, title: str, tool: str = "",
                             step: int = 1, total_steps: int = 1,
                             progress: float = 0.0) -> TaskItem:
        task = TaskItem(
            task_id=task_id,
            title=title,
            tool=tool,
            status=TaskStatus.RUNNING,
            step=step,
            total_steps=total_steps,
            progress=progress,
            started_at=time.time(),
        )
        self.state.set_active_task(task)
        self.state.set_agent_state(AgentState.EXECUTING_TASK)
        return task

    def complete_task(self, task_id: str, result_summary: str = "") -> None:
        if self.state.active_task and self.state.active_task.task_id == task_id:
            task = self.state.active_task
            task.status = TaskStatus.COMPLETED
            task.progress = 1.0
            task.completed_at = time.time()
            self.state.set_active_task(None)
            self.state.set_agent_state(AgentState.IDLE)
            self.state.push_notification("Task Completed", f"{task.title} finished successfully.", "success")

    def fail_task(self, task_id: str, error: str = "") -> None:
        if self.state.active_task and self.state.active_task.task_id == task_id:
            task = self.state.active_task
            task.status = TaskStatus.FAILED
            task.error = error
            task.completed_at = time.time()
            self.state.set_active_task(None)
            self.state.set_agent_state(AgentState.ERROR)
            self.state.push_notification("Task Failed", f"{task.title}: {error}", "error")

    def request_confirmation(self, ticket_id: str, action_name: str,
                             description: str, target: str = "",
                             expires_in: float = 90.0) -> ConfirmationRequest:
        req = ConfirmationRequest(
            ticket_id=ticket_id,
            action_name=action_name,
            description=description,
            target=target,
            expires_in_seconds=expires_in,
            created_at=time.time(),
        )
        self.state.set_pending_confirmation(req)
        self.state.set_agent_state(AgentState.WAITING_CONFIRMATION)
        self.state.push_notification("Confirmation Required", f"{action_name}: {description}", "warning")
        return req

    def clear_confirmation(self) -> None:
        self.state.clear_confirmation()
        if self.state.agent_state == AgentState.WAITING_CONFIRMATION:
            self.state.set_agent_state(AgentState.IDLE)

    def handle_interrupt(self, reason: str = "User pressed Escape") -> None:
        """Clear active confirmation, cancel active task, and reset to IDLE."""
        self.clear_confirmation()
        if self.state.active_task:
            task = self.state.active_task
            task.status = TaskStatus.CANCELLED
            self.state.set_active_task(None)
        self.state.set_agent_state(AgentState.IDLE)
        self.state.set_voice_state(VoiceState.IDLE)


# Global singleton
state_store = StateStore()
