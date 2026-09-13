"""
MAX Ecosystem — Task Classification Engine
Deterministic 5-Tier Task Classifier with Ambiguity Detection, Safety Fallbacks,
and Granular Permission Suggestions.
"""

import re
import logging
from dataclasses import dataclass, field
from typing import Dict, Any, List, Optional, Tuple
from enum import Enum

from config import MAX_CLASSIFIER_CONFIDENCE_THRESHOLD
from protocol.task_envelope import GranularPermission

logger = logging.getLogger("MAXWindowsAgent.TaskClassifier")


class TaskTier(int, Enum):
    TIER_1_DIRECT_ACTION = 1
    TIER_1_DIRECT = 1
    TIER_2_PLATFORM_EXECUTION = 2
    TIER_2_PLATFORM_EXEC = 2
    TIER_3_REASONING = 3
    TIER_4_DEVELOPER = 4
    TIER_5_HYBRID = 5


@dataclass
class ClassificationResult:
    tier: int
    task_type: str
    target: str  # "windows_agent", "android_agent", "max_agent", "antigravity", "hybrid"
    confidence: float
    reason: str
    requires_confirmation: bool = False
    requires_clarification: bool = False
    is_destructive: bool = False
    is_ambiguous: bool = False
    suggested_permissions: List[str] = field(default_factory=list)

    @property
    def target_worker(self) -> str:
        return self.target

    @property
    def target_platform(self) -> str:
        return "android" if self.target == "android_agent" else "windows"

    @property
    def reasoning(self) -> str:
        return self.reason

    def to_dict(self) -> Dict[str, Any]:
        return {
            "tier": self.tier,
            "task_type": self.task_type,
            "target": self.target,
            "confidence": self.confidence,
            "reason": self.reason,
            "requires_confirmation": self.requires_confirmation,
            "requires_clarification": self.requires_clarification,
            "is_destructive": self.is_destructive,
            "is_ambiguous": self.is_ambiguous,
            "suggested_permissions": self.suggested_permissions
        }


# Destructive patterns requiring explicit user confirmation
DESTRUCTIVE_PATTERNS = [
    r"\b(delete|remove|erase|format|wipe|destroy|kill|terminate|shutdown|restart|reboot)\b",
    r"\b(rmdir|del|drop\s+table|purge|uninstall)\b"
]

# Ambiguous keywords requiring clarification when lacking clear context
AMBIGUOUS_PATTERNS = [
    r"^fix\s+(paint|chrome|notepad|word|whatsapp|app)$",
    r"^clean\s+(up|that|system)$",
    r"^check\s+(it|this)$",
    r"^do\s+it$"
]


class TaskClassifier:
    """
    Deterministic first-pass task classification engine.
    Categorizes intents into Tiers 1-5 without guessing and enforces safety boundaries.
    """
    def __init__(self, confidence_threshold: float = MAX_CLASSIFIER_CONFIDENCE_THRESHOLD):
        self.confidence_threshold = confidence_threshold

    def classify(
        self,
        user_intent: str,
        metadata: Optional[Dict[str, Any]] = None,
        context: Optional[Dict[str, Any]] = None
    ) -> ClassificationResult:
        combined_meta = {**(metadata or {}), **(context or {})}
        if not user_intent or not user_intent.strip():
            return ClassificationResult(
                tier=TaskTier.TIER_1_DIRECT_ACTION.value,
                task_type="empty_query",
                target="windows_agent",
                confidence=1.0,
                reason="Empty user intent",
                requires_clarification=True,
                is_ambiguous=True
            )

        intent = user_intent.strip()
        low = intent.lower()

        # 1. Check Destructive Risk
        is_destructive = any(re.search(pat, low) for pat in DESTRUCTIVE_PATTERNS)

        # 2. Check Explicit Ambiguity
        is_ambiguous = any(re.match(pat, low) for pat in AMBIGUOUS_PATTERNS)
        if is_ambiguous:
            return ClassificationResult(
                tier=TaskTier.TIER_1_DIRECT_ACTION.value,
                task_type="ambiguous_request",
                target="windows_agent",
                confidence=0.50,
                reason=f"Ambiguous intent '{intent}' requires clarification before execution.",
                requires_confirmation=is_destructive,
                requires_clarification=True,
                is_destructive=is_destructive,
                is_ambiguous=True,
                suggested_permissions=[GranularPermission.WORKSPACE_READ.value]
            )

        # 3. Check TIER 5 (Hybrid: Code Change + OS/GUI Runtime Verification)
        if ("fix" in low or "bug" in low or "refactor" in low or "implement" in low or "modify" in low) and \
           ("verify by" in low or "verify in" in low or "test by drawing" in low or "verify by drawing" in low or "actually drawing" in low or "actually using" in low or "actually draw" in low):
            return ClassificationResult(
                tier=TaskTier.TIER_5_HYBRID.value,
                task_type="hybrid",
                target="hybrid",
                confidence=0.96,
                reason="Task requires code modifications in isolated worktree followed by OS/GUI runtime verification.",
                requires_confirmation=is_destructive,
                requires_clarification=False,
                is_destructive=is_destructive,
                suggested_permissions=[
                    GranularPermission.WORKSPACE_READ.value,
                    GranularPermission.WORKSPACE_WRITE.value,
                    GranularPermission.WORKTREE_CREATE.value,
                    GranularPermission.GIT_READ.value,
                    GranularPermission.GIT_WRITE.value,
                    GranularPermission.TEST_EXECUTION.value,
                    GranularPermission.GUI_READ.value,
                    GranularPermission.GUI_CONTROL.value,
                    GranularPermission.PROCESS_READ.value
                ]
            )

        # 3.5 Check Normal Filesystem Operations (Direct Windows Agent - NEVER Antigravity)
        filesystem_patterns = [
            r"\b(create|make|new|write|add)\b.*\b(folder|directory|text\s+file|file)\b",
            r"\b(rename|move|copy|delete|list)\b.*\b(folder|directory|file)\b",
            r"\b(desktop|downloads|documents|pictures)\b.*\b(folder|file)\b",
            r"\b(file\s+create|folder\s+create|create\s+cheyyi|file\s+create\s+cheyyi)\b"
        ]
        # Only treat as developer task if explicit coding/scripting keywords appear
        has_developer_override = any(k in low for k in ("python script", "script that", "write code", "fix code", "git", "pytest", "worktree", "antigravity", "repo", "repository"))
        if any(re.search(pat, low) for pat in filesystem_patterns) and not has_developer_override:
            return ClassificationResult(
                tier=TaskTier.TIER_2_PLATFORM_EXECUTION.value,
                task_type="file_operation",
                target="windows_agent",
                confidence=0.98,
                reason="Normal filesystem operation executed directly via Windows Agent without worktree isolation.",
                requires_confirmation=is_destructive,
                requires_clarification=False,
                is_destructive=is_destructive,
                suggested_permissions=[
                    GranularPermission.WORKSPACE_READ.value,
                    GranularPermission.WORKSPACE_WRITE.value,
                    GranularPermission.PROCESS_READ.value
                ]
            )

        # 3.6 Check Window & Tab Management (Direct Keyboard/Win32 - NEVER Antigravity)
        window_tab_patterns = [
            r"\b(switch|change|go\s+to|next|previous)\b.*\b(tab|chrome\s+tab|browser\s+tab)\b",
            r"\b(third\s+tab|first\s+tab|second\s+tab|4th\s+tab|tab\s+\d+)\b",
            r"\b(minimize|maximize|restore|snap|split\s+screen|put\s+.*\s+on\s+the\s+left|put\s+.*\s+on\s+the\s+right)\b",
            r"\b(snap\s+left|snap\s+right|snap\s+top|snap\s+bottom)\b"
        ]
        if any(re.search(pat, low) for pat in window_tab_patterns):
            return ClassificationResult(
                tier=TaskTier.TIER_1_DIRECT_ACTION.value,
                task_type="direct_action",
                target="windows_agent",
                confidence=0.99,
                reason="Direct desktop window/tab control operation resolved via keyboard shortcut or Win32 API.",
                requires_confirmation=is_destructive,
                requires_clarification=False,
                is_destructive=is_destructive,
                suggested_permissions=[
                    GranularPermission.GUI_READ.value,
                    GranularPermission.GUI_CONTROL.value,
                    GranularPermission.PROCESS_READ.value
                ]
            )

        # 4. Check TIER 4 (Developer Task -> Antigravity Worker)
        developer_patterns = [
            r"\b(inspect|examine|audit|review|analyze|check|read)\b.*\b(code|bug|function|class|test|script|repo|repository|codebase|worktree|automation|agy)\b",
            r"\b(fix|debug|refactor|patch|edit|modify|rewrite|write|implement|update|generate|build)\b.*\b(code|bug|function|class|test|module|script|repo|repository|codebase|pytest|git|worktree|antigravity)\b",
            r"\b(run\s+tests|pytest|npm\s+test|git\s+status|git\s+diff|git\s+branch|compile|build|worktree|antigravity)\b",
            r"\b(paint\s+brush\s+click\s+bug|paint\s+automation\s+code|paint\s+automation)\b",
            r"\b(isolated\s+worktree|in\s+an\s+isolated\s+worktree)\b"
        ]
        # Ensure pure GUI actions without code context (e.g. 'draw in paint') don't match Tier 4
        is_pure_gui_drawing = ("draw" in low or "paint" in low) and not any(k in low for k in ("code", "bug", "inspect", "refactor", "automation", "worktree", "antigravity", "pytest", "git", "repo"))
        if any(re.search(pat, low) for pat in developer_patterns) and not is_pure_gui_drawing:
            is_inspection = any(w in low for w in ("inspect", "examine", "audit", "review", "tell me", "check", "read only", "do not modify", "without modifying")) and not any(w in low for w in ("fix ", "patch ", "refactor ", "create ", "write ", "build "))
            task_type = "code_inspection" if is_inspection else ("code_change" if any(w in low for w in ("create", "write", "modify", "edit", "fix", "patch", "refactor")) else "code_inspection")
            
            perms = [
                GranularPermission.WORKSPACE_READ.value,
                GranularPermission.GIT_READ.value,
                GranularPermission.TEST_EXECUTION.value
            ]
            if task_type == "code_change":
                perms.extend([
                    GranularPermission.WORKSPACE_WRITE.value,
                    GranularPermission.WORKTREE_CREATE.value,
                    GranularPermission.GIT_WRITE.value
                ])

            return ClassificationResult(
                tier=TaskTier.TIER_4_DEVELOPER.value,
                task_type=task_type,
                target="antigravity",
                confidence=0.95,
                reason="Task requires repository inspection, coding, debugging, or test execution in isolated worktree.",
                requires_confirmation=is_destructive,
                requires_clarification=False,
                is_destructive=is_destructive,
                suggested_permissions=perms
            )

        # 5. Check TIER 1 (Direct Simple Action -> Windows Agent / Fast-Path)
        direct_patterns = [
            r"^(open|launch|start|close|quit|kill)\s+([a-zA-Z0-9_\-\s]+)$",
            r"\b(volume|mute|unmute|battery|time|date|status|specs|ipconfig|whoami)\b"
        ]
        if any(re.search(pat, low) for pat in direct_patterns):
            return ClassificationResult(
                tier=TaskTier.TIER_1_DIRECT_ACTION.value,
                task_type="direct_action",
                target="windows_agent",
                confidence=0.97,
                reason="Direct simple system command or application lifecycle operation.",
                requires_confirmation=is_destructive,
                requires_clarification=False,
                is_destructive=is_destructive,
                suggested_permissions=[
                    GranularPermission.PROCESS_READ.value,
                    GranularPermission.PROCESS_CONTROL.value
                ]
            )

        # 6. Check TIER 2 (Platform Execution -> Windows Agent or Android Agent)
        # Android triggers
        android_patterns = [
            r"\b(phone|android|mobile|device|flashlight|torch|whatsapp\s+message|sms|call|battery\s+on\s+phone)\b"
        ]
        if any(re.search(pat, low) for pat in android_patterns) and not ("windows" in low or "desktop" in low):
            return ClassificationResult(
                tier=TaskTier.TIER_2_PLATFORM_EXECUTION.value,
                task_type="android_action",
                target="android_agent",
                confidence=0.92,
                reason="Intent targets remote Android companion device.",
                requires_confirmation=is_destructive,
                requires_clarification=False,
                is_destructive=is_destructive,
                suggested_permissions=[GranularPermission.DEVICE_CONTROL.value, GranularPermission.NETWORK_READ.value]
            )

        # Windows GUI / Paint / App execution triggers
        gui_patterns = [
            r"\b(draw|paint|canvas|brush|shape|triangle|circle|rectangle|oval|diamond|heart)\b",
            r"\b(click|type|press|scroll|focus|inspect|window|desktop|explorer)\b",
            r"\b(take\s+screenshot|capture\s+screen)\b"
        ]
        if any(re.search(pat, low) for pat in gui_patterns):
            return ClassificationResult(
                tier=TaskTier.TIER_2_PLATFORM_EXECUTION.value,
                task_type="gui_action",
                target="windows_agent",
                confidence=0.95,
                reason="Intent requires Windows OS GUI or Paint canvas automation.",
                requires_confirmation=is_destructive,
                requires_clarification=False,
                is_destructive=is_destructive,
                suggested_permissions=[
                    GranularPermission.GUI_READ.value,
                    GranularPermission.GUI_CONTROL.value,
                    GranularPermission.PROCESS_READ.value
                ]
            )

        # 7. Check TIER 3 (Internal Autonomous Reasoning -> MAX Agent Runtime Worker)
        reasoning_patterns = [
            r"\b(plan|analyze|reason|summarize|evaluate|compare|think|breakdown|strategy)\b",
            r"\b(multi-step|workflow|autonomous|schedule|cron)\b"
        ]
        if any(re.search(pat, low) for pat in reasoning_patterns):
            return ClassificationResult(
                tier=TaskTier.TIER_3_REASONING.value,
                task_type="reasoning",
                target="max_agent",
                confidence=0.88,
                reason="Intent requires multi-turn reasoning, planning, or autonomous skill evaluation.",
                requires_confirmation=is_destructive,
                requires_clarification=False,
                is_destructive=is_destructive,
                suggested_permissions=[
                    GranularPermission.WORKSPACE_READ.value,
                    GranularPermission.PROCESS_READ.value
                ]
            )

        # Default fallback
        return ClassificationResult(
            tier=TaskTier.TIER_1_DIRECT_ACTION.value,
            task_type="general_query",
            target="windows_agent",
            confidence=0.75,  # Below threshold -> triggers safe fallback
            reason="General query without explicit platform or developer keywords.",
            requires_confirmation=is_destructive,
            requires_clarification=False,
            is_destructive=is_destructive,
            is_ambiguous=False,
            suggested_permissions=[GranularPermission.WORKSPACE_READ.value]
        )


TaskClassificationResult = ClassificationResult

# Canonical singleton instance
task_classifier = TaskClassifier()

