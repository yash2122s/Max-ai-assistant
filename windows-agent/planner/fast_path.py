"""
MAX AI — Fast-Path Command Resolver
Performs deterministic, pattern-based resolution for atomic, single-shot user commands,
bypassing LLM planning latency while strictly enforcing Plan validation.
"""

import re
import logging
from typing import Optional, Dict, Any, List, Tuple

from core.unified_tool_registry import UnifiedToolRegistry
from .schemas import Plan, PlanStep

logger = logging.getLogger("MAXWindowsAgent.FastPath")

# Words indicating multi-step / compound tasks that MUST be handled by Gemini REST Planner
MULTI_STEP_CONJUNCTIONS = [
    " and ", " then ", " after ", " also ", " later ", ",", ";", "&"
]


class FastPathResolver:
    """
    Resolves simple deterministic commands into a validated Plan without LLM latency.
    """

    def __init__(self, registry: Optional[UnifiedToolRegistry] = None):
        self.registry = registry or UnifiedToolRegistry.get_instance()

    def resolve(self, text: str) -> Optional[Plan]:
        """
        Attempts to resolve a single-step atomic command into a Plan.
        Returns None if complex, ambiguous, or unmatched.
        """
        if not text or not isinstance(text, str):
            return None

        clean_text = text.strip()
        lower_text = clean_text.lower()

        # 1. Multi-step Check: If command contains conjunctions, reject to normal planner
        for conj in MULTI_STEP_CONJUNCTIONS:
            if conj in lower_text:
                return None

        # 2. Pattern Matching for Canonical Windows Tools
        
        # Open / Launch Application
        m = re.match(r"^(?:open|launch|start)\s+([a-zA-Z0-9_\-\.\s]+)$", lower_text)
        if m:
            app_raw = m.group(1).strip()
            # Avoid matching compound phrases like "open my files"
            if app_raw and len(app_raw) <= 30 and not app_raw.startswith("my "):
                return Plan(
                    goal=f"Open {app_raw}",
                    steps=[
                        PlanStep(
                            id="step_1",
                            tool="open_application",
                            args={"app_name": app_raw},
                            description=f"Launch application {app_raw}",
                            requires_confirmation=False
                        )
                    ]
                )

        # Close / Quit Application
        m = re.match(r"^(?:close|quit|exit)\s+([a-zA-Z0-9_\-\.\s]+)$", lower_text)
        if m:
            app_raw = m.group(1).strip()
            if app_raw and len(app_raw) <= 30:
                return Plan(
                    goal=f"Close {app_raw}",
                    steps=[
                        PlanStep(
                            id="step_1",
                            tool="close_application",
                            args={"app_name": app_raw},
                            description=f"Close application {app_raw}",
                            requires_confirmation=False
                        )
                    ]
                )

        # Take Screenshot
        if lower_text in ("take screenshot", "take a screenshot", "capture screen", "screenshot"):
            return Plan(
                goal="Take screenshot",
                steps=[
                    PlanStep(
                        id="step_1",
                        tool="take_screenshot",
                        args={},
                        description="Capture full screen screenshot",
                        requires_confirmation=False
                    )
                ]
            )

        # Volume Controls
        if lower_text in ("mute", "unmute", "mute volume", "unmute volume", "toggle mute"):
            return Plan(
                goal="Toggle volume mute",
                steps=[
                    PlanStep(
                        id="step_1",
                        tool="mute_unmute",
                        args={},
                        description="Toggle audio mute state",
                        requires_confirmation=False
                    )
                ]
            )

        m = re.match(r"^(?:set|change)\s+volume\s+(?:to\s+)?(\d{1,3})%?$", lower_text)
        if m:
            level = max(0, min(100, int(m.group(1))))
            return Plan(
                goal=f"Set volume to {level}%",
                steps=[
                    PlanStep(
                        id="step_1",
                        tool="set_volume",
                        args={"level": level},
                        description=f"Set audio volume to {level}%",
                        requires_confirmation=False
                    )
                ]
            )

        if lower_text in ("get volume", "what is the volume", "check volume"):
            return Plan(
                goal="Get current volume level",
                steps=[
                    PlanStep(
                        id="step_1",
                        tool="get_volume",
                        args={},
                        description="Query current volume level",
                        requires_confirmation=False
                    )
                ]
            )

        # Battery Status
        if lower_text in ("check battery", "battery status", "get battery", "what is the battery"):
            return Plan(
                goal="Check battery status",
                steps=[
                    PlanStep(
                        id="step_1",
                        tool="get_battery_status",
                        args={},
                        description="Query battery level and power state",
                        requires_confirmation=False
                    )
                ]
            )

        # System Info
        if lower_text in ("system info", "get system info", "system specifications"):
            return Plan(
                goal="Get system specifications",
                steps=[
                    PlanStep(
                        id="step_1",
                        tool="get_system_info",
                        args={},
                        description="Query OS and hardware specs",
                        requires_confirmation=False
                    )
                ]
            )

        # Active Window
        if lower_text in ("get active window", "current window", "focused window"):
            return Plan(
                goal="Get active window details",
                steps=[
                    PlanStep(
                        id="step_1",
                        tool="get_active_window",
                        args={},
                        description="Query currently focused window",
                        requires_confirmation=False
                    )
                ]
            )

        # Atomic Filesystem Commands
        # 1. Create Folder
        m = re.match(r"^(?:create|make|new)\s+(?:folder|directory)\s+['\"]?([a-zA-Z0-9_\-\.\/\\:]+)['\"]?$", clean_text, re.IGNORECASE)
        if m:
            folder_path = m.group(1).strip()
            return Plan(
                goal=f"Create folder {folder_path}",
                steps=[
                    PlanStep(
                        id="step_1",
                        tool="create_folder",
                        args={"path": folder_path},
                        description=f"Create folder at {folder_path}",
                        requires_confirmation=False
                    )
                ]
            )

        # 2. Create File (optional content)
        m = re.match(r"^(?:create|make|new)\s+(?:file|text file)\s+['\"]?([a-zA-Z0-9_\-\.\/\\:]+)['\"]?(?:\s+(?:with\s+content|with\s+text)\s+['\"](.*)['\"])?$", clean_text, re.IGNORECASE)
        if m:
            file_path = m.group(1).strip()
            content = m.group(2) if m.group(2) is not None else ""
            args = {"path": file_path}
            if content:
                args["content"] = content
            return Plan(
                goal=f"Create file {file_path}",
                steps=[
                    PlanStep(
                        id="step_1",
                        tool="create_file",
                        args=args,
                        description=f"Create file at {file_path}",
                        requires_confirmation=False
                    )
                ]
            )

        # 3. Write File
        m = re.match(r"^(?:write\s+file|write\s+to)\s+['\"]?([a-zA-Z0-9_\-\.\/\\:]+)['\"]?\s+(?:with\s+content|content)?\s*['\"](.*)['\"]$", clean_text, re.IGNORECASE)
        if m:
            file_path = m.group(1).strip()
            content = m.group(2)
            return Plan(
                goal=f"Write to {file_path}",
                steps=[
                    PlanStep(
                        id="step_1",
                        tool="write_file",
                        args={"path": file_path, "content": content},
                        description=f"Write content to {file_path}",
                        requires_confirmation=False
                    )
                ]
            )

        # 4. Read File
        m = re.match(r"^(?:read\s+file|read|view\s+file|get\s+content\s+of)\s+['\"]?([a-zA-Z0-9_\-\.\/\\:]+\.[a-zA-Z0-9]+)['\"]?$", clean_text, re.IGNORECASE)
        if m:
            file_path = m.group(1).strip()
            return Plan(
                goal=f"Read file {file_path}",
                steps=[
                    PlanStep(
                        id="step_1",
                        tool="read_file",
                        args={"path": file_path},
                        description=f"Read contents of {file_path}",
                        requires_confirmation=False
                    )
                ]
            )

        # 5. Open File
        m = re.match(r"^(?:open\s+file|open)\s+['\"]?([a-zA-Z0-9_\-\.\/\\:]+\.[a-zA-Z0-9]+)['\"]?$", clean_text, re.IGNORECASE)
        if m:
            file_path = m.group(1).strip()
            return Plan(
                goal=f"Open file {file_path}",
                steps=[
                    PlanStep(
                        id="step_1",
                        tool="open_file",
                        args={"path": file_path},
                        description=f"Open file {file_path}",
                        requires_confirmation=False
                    )
                ]
            )

        return None


# Canonical singleton fast path resolver instance
fast_path_resolver = FastPathResolver()
