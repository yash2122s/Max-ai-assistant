"""
MAX UI — Central Observable UI State Model
Thread-safe Qt signal-backed state container.
"""

from enum import Enum
import time
from typing import Dict, Any, List, Optional, Tuple

from PyQt6.QtCore import QObject, pyqtSignal

from .chat_models import ChatMessage, MessageRole
from .task_models import TaskItem, TaskStatus, ConfirmationRequest
from .computer_use_models import ComputerUseState, ActionPhase


class PresentationMode(str, Enum):
    FULL_SCREEN = "full_screen"
    DYNAMIC_ISLAND_MINI = "dynamic_island_mini"
    DYNAMIC_ISLAND_EXPANDED = "dynamic_island_expanded"


class WorkspaceType(str, Enum):
    HOME = "home"
    CHAT = "chat"
    COMPUTER_USE = "computer_use"
    BROWSER = "browser"
    CODING = "coding"
    FILES = "files"
    TASKS = "tasks"
    MEMORY = "memory"
    DEVICES = "devices"
    SETTINGS = "settings"


class VoiceState(str, Enum):
    IDLE = "idle"
    WAKE_DETECTED = "wake_detected"
    LISTENING = "listening"
    THINKING = "thinking"
    PROCESSING = "processing"
    SPEAKING = "speaking"
    COOLDOWN = "cooldown"
    ERROR = "error"


class AgentState(str, Enum):
    IDLE = "idle"
    BUSY = "busy"
    EXECUTING_TASK = "executing_task"
    WAITING_CONFIRMATION = "waiting_confirmation"
    ERROR = "error"


class UiState(QObject):
    """
    Central observable state model for the entire MAX PyQt6 application.
    All properties emit Qt signals upon modification, allowing widgets
    to react reactively without tight coupling.
    """

    # Core State Signals
    presentation_mode_changed = pyqtSignal(str)
    workspace_changed = pyqtSignal(str)
    voice_state_changed = pyqtSignal(str)
    agent_state_changed = pyqtSignal(str)
    audio_level_changed = pyqtSignal(float)
    
    # Transcripts & Chat
    user_transcript_updated = pyqtSignal(str)
    assistant_transcript_updated = pyqtSignal(str)
    chat_message_added = pyqtSignal(object)  # ChatMessage
    chat_message_updated = pyqtSignal(object)  # ChatMessage
    chat_cleared = pyqtSignal()
    
    # Tasks & Confirmation
    active_task_updated = pyqtSignal(object)  # Optional[TaskItem]
    task_list_updated = pyqtSignal(list)
    confirmation_requested = pyqtSignal(object)  # ConfirmationRequest
    confirmation_cleared = pyqtSignal()
    
    # Computer Use & Perception
    computer_use_updated = pyqtSignal(object)  # ComputerUseState
    
    # System Telemetry & Devices
    telemetry_updated = pyqtSignal(dict)
    devices_updated = pyqtSignal(list)
    
    # Connection
    connection_state_changed = pyqtSignal(bool)
    
    # Toast Notifications
    notification_pushed = pyqtSignal(str, str, str)  # (title, message, level: "info"|"success"|"warning"|"error")

    def __init__(self):
        super().__init__()
        self._presentation_mode: PresentationMode = PresentationMode.FULL_SCREEN
        self._workspace: WorkspaceType = WorkspaceType.HOME
        self._voice_state: VoiceState = VoiceState.IDLE
        self._agent_state: AgentState = AgentState.IDLE
        self._audio_level: float = 0.0
        
        self._user_transcript: str = ""
        self._assistant_transcript: str = ""
        self._chat_messages: List[ChatMessage] = []
        
        self._active_task: Optional[TaskItem] = None
        self._tasks: List[TaskItem] = []
        self._pending_confirmation: Optional[ConfirmationRequest] = None
        
        self._computer_use: ComputerUseState = ComputerUseState()
        
        self._telemetry: Dict[str, Any] = {
            "cpu_percent": 0.0,
            "ram_percent": 0.0,
            "gpu_percent": 0.0,
            "vram_percent": 0.0,
            "network_kbps": 0.0,
            "current_application": "Desktop",
            "current_tool": "",
            "active_device": "Windows PC",
        }
        
        self._devices: List[Dict[str, Any]] = [
            {"id": "pc_local", "name": "Windows PC", "type": "windows", "status": "online", "is_local": True},
            {"id": "phone_android", "name": "Android Phone", "type": "android", "status": "online", "is_local": False},
        ]
        
        self._is_connected: bool = False
        self._theme: str = "dark"

    # Properties & Setters
    @property
    def presentation_mode(self) -> PresentationMode:
        return self._presentation_mode

    def set_presentation_mode(self, mode: PresentationMode | str) -> None:
        if isinstance(mode, str):
            mode = PresentationMode(mode)
        if self._presentation_mode != mode:
            self._presentation_mode = mode
            self.presentation_mode_changed.emit(mode.value)

    @property
    def workspace(self) -> WorkspaceType:
        return self._workspace

    def set_workspace(self, ws: WorkspaceType | str) -> None:
        if isinstance(ws, str):
            ws = WorkspaceType(ws)
        if self._workspace != ws:
            self._workspace = ws
            self.workspace_changed.emit(ws.value)

    @property
    def voice_state(self) -> VoiceState:
        return self._voice_state

    def set_voice_state(self, state: VoiceState | str) -> None:
        if isinstance(state, str):
            state_map = {
                "WAKE_WORD_LISTENING": VoiceState.IDLE,
                "WAKE_DETECTED": VoiceState.WAKE_DETECTED,
                "LIVE_CONVERSATION": VoiceState.LISTENING,
                "LISTENING": VoiceState.LISTENING,
                "PROCESSING": VoiceState.PROCESSING,
                "THINKING": VoiceState.THINKING,
                "ASSISTANT_SPEAKING": VoiceState.SPEAKING,
                "SPEAKING": VoiceState.SPEAKING,
                "COOLDOWN": VoiceState.COOLDOWN,
                "ERROR": VoiceState.ERROR,
            }
            state = state_map.get(state.upper(), VoiceState.IDLE)
        
        if self._voice_state != state:
            self._voice_state = state
            self.voice_state_changed.emit(state.value)

    @property
    def agent_state(self) -> AgentState:
        return self._agent_state

    def set_agent_state(self, state: AgentState | str) -> None:
        if isinstance(state, str):
            state = AgentState(state)
        if self._agent_state != state:
            self._agent_state = state
            self.agent_state_changed.emit(state.value)

    @property
    def audio_level(self) -> float:
        return self._audio_level

    def set_audio_level(self, level: float) -> None:
        clamped = max(0.0, min(1.0, float(level)))
        if abs(self._audio_level - clamped) > 0.005:
            self._audio_level = clamped
            self.audio_level_changed.emit(clamped)

    @property
    def user_transcript(self) -> str:
        return self._user_transcript

    def set_user_transcript(self, text: str) -> None:
        if self._user_transcript != text:
            self._user_transcript = text
            self.user_transcript_updated.emit(text)

    @property
    def assistant_transcript(self) -> str:
        return self._assistant_transcript

    def set_assistant_transcript(self, text: str) -> None:
        if self._assistant_transcript != text:
            self._assistant_transcript = text
            self.assistant_transcript_updated.emit(text)

    def append_assistant_transcript(self, fragment: str) -> None:
        self._assistant_transcript += fragment
        self.assistant_transcript_updated.emit(self._assistant_transcript)

    @property
    def chat_messages(self) -> List[ChatMessage]:
        return self._chat_messages

    def add_chat_message(self, message: ChatMessage) -> None:
        self._chat_messages.append(message)
        self.chat_message_added.emit(message)

    def update_chat_message(self, message: ChatMessage) -> None:
        for idx, m in enumerate(self._chat_messages):
            if m.id == message.id:
                self._chat_messages[idx] = message
                self.chat_message_updated.emit(message)
                return

    def clear_chat(self) -> None:
        self._chat_messages.clear()
        self.chat_cleared.emit()

    @property
    def active_task(self) -> Optional[TaskItem]:
        return self._active_task

    def set_active_task(self, task: Optional[TaskItem]) -> None:
        self._active_task = task
        if task is not None:
            # Update or insert into tasks list
            found = False
            for idx, t in enumerate(self._tasks):
                if t.task_id == task.task_id:
                    self._tasks[idx] = task
                    found = True
                    break
            if not found:
                self._tasks.append(task)
            self.task_list_updated.emit(self._tasks)
        self.active_task_updated.emit(task)

    @property
    def tasks(self) -> List[TaskItem]:
        return self._tasks

    @property
    def pending_confirmation(self) -> Optional[ConfirmationRequest]:
        return self._pending_confirmation

    def set_pending_confirmation(self, req: Optional[ConfirmationRequest]) -> None:
        self._pending_confirmation = req
        if req is not None:
            self.confirmation_requested.emit(req)
        else:
            self.confirmation_cleared.emit()

    def clear_confirmation(self) -> None:
        self.set_pending_confirmation(None)

    @property
    def computer_use(self) -> ComputerUseState:
        return self._computer_use

    def update_computer_use(self, state: ComputerUseState) -> None:
        self._computer_use = state
        self.computer_use_updated.emit(state)

    @property
    def telemetry(self) -> Dict[str, Any]:
        return self._telemetry

    def update_telemetry(self, data: Dict[str, Any]) -> None:
        self._telemetry.update(data)
        self.telemetry_updated.emit(self._telemetry)

    @property
    def devices(self) -> List[Dict[str, Any]]:
        return self._devices

    def update_devices(self, devices: List[Dict[str, Any]]) -> None:
        self._devices = devices
        self.devices_updated.emit(self._devices)

    @property
    def is_connected(self) -> bool:
        return self._is_connected

    @property
    def connected(self) -> bool:
        return self._is_connected

    def set_connected(self, connected: bool) -> None:
        if self._is_connected != connected:
            self._is_connected = connected
            self.connection_state_changed.emit(connected)

    def push_notification(self, title: str, message: str, level: str = "info") -> None:
        self.notification_pushed.emit(title, message, level)
