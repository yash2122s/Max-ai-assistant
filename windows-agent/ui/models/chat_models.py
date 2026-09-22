"""
MAX UI — Chat Data Models
"""

from dataclasses import dataclass, field
from enum import Enum
import time
from typing import Optional, List, Dict, Any


class MessageRole(str, Enum):
    USER = "user"
    ASSISTANT = "assistant"
    SYSTEM = "system"
    TOOL = "tool"


@dataclass
class CodeSnippet:
    language: str
    code: str
    file_path: Optional[str] = None


@dataclass
class ChatMessage:
    id: str
    role: MessageRole
    content: str
    timestamp: float = field(default_factory=time.time)
    tool_name: Optional[str] = None
    tool_status: Optional[str] = None
    tool_input: Optional[Dict[str, Any]] = None
    tool_output: Optional[Dict[str, Any]] = None
    code_snippets: List[CodeSnippet] = field(default_factory=list)
    attachments: List[str] = field(default_factory=list)
    is_streaming: bool = False
    error: Optional[str] = None
