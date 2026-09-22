from enum import Enum

class EventType(Enum):
    AGENT_STARTED = "agent_started"
    AGENT_SHUTDOWN = "agent_shutdown"
    CLIENT_CONNECTED = "client_connected"
    CLIENT_DISCONNECTED = "client_disconnected"
    PAIR_STARTED = "pair_started"
    PAIR_SUCCESS = "pair_success"
    PAIR_FAILED = "pair_failed"
    TOOL_REQUESTED = "tool_requested"
    TOOL_PROGRESS = "tool_progress"
    TOOL_COMPLETED = "tool_completed"
    TOOL_FAILED = "tool_failed"
    HEARTBEAT = "heartbeat"
    CHAT_MESSAGE = "chat_message"
    CORE_STATE = "core_state"
    NOTIFICATION_RECEIVED = "notification_received"
    # Task Engine Events
    TASK_CREATED = "task_created"
    TASK_STARTED = "task_started"
    STEP_STARTED = "step_started"
    STEP_COMPLETED = "step_completed"
    STEP_FAILED = "step_failed"
    STEP_RETRYING = "step_retrying"
    TASK_COMPLETED = "task_completed"
    TASK_FAILED = "task_failed"
    TASK_CANCELLED = "task_cancelled"
    TASK_INTERRUPTED = "task_interrupted"

    # Multi-Device Ecosystem Events
    DEVICE_REGISTERED = "device_registered"
    DEVICE_UPDATED = "device_updated"
    DEVICE_STATE_CHANGED = "device_state_changed"
    DEVICE_CAPABILITIES_UPDATED = "device_capabilities_updated"
    COMMAND_REQUEST_SENT = "command_request_sent"
    COMMAND_RESULT_RECEIVED = "command_result_received"

    # Desktop Overlay UI Events
    VOICE_STATE = "voice_state"
    VOICE_AUDIO_LEVEL = "voice_audio_level"
    TRANSCRIPT_USER = "transcript_user"
    TRANSCRIPT_ASSISTANT = "transcript_assistant"
    OVERLAY_TASK_STATE = "overlay_task_state"
    DEVICE_CONTROL = "device_control"
    CONFIRMATION_REQUIRED = "confirmation_required"
    CONFIRMATION_RESPONSE = "confirmation_response"
    TOOL_SELECTING = "tool_selecting"
    TOOL_EXECUTING = "tool_executing"
    COMPUTER_USE = "computer_use"
    MCP_EXECUTING = "mcp_executing"
    VERIFYING = "verifying"
    RECOVERING = "recovering"
    SUCCESS = "success"
    ERROR = "error"


try:
    from core.voice_gate import VoiceState
except Exception:
    class VoiceState(str, Enum):  # type: ignore
        IDLE = "idle"
        WAKE_WORD_LISTENING = "wake_word_listening"
        WAKE_DETECTED = "wake_detected"
        LIVE_CONVERSATION = "live_conversation"
        COMMAND_LISTENING = "live_conversation"
        PROCESSING = "processing"
        ASSISTANT_SPEAKING = "assistant_speaking"
        COOLDOWN = "cooldown"
