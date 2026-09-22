"""
MAX AI — Canonical Skill Definition Dataclass Models (Python)
Implements the MAX Cross-Platform Automation Specification v1.0.0.
"""

from dataclasses import dataclass, field, asdict
from enum import Enum
from typing import Dict, Any, List, Optional


class TrustLevel(str, Enum):
    BUILTIN = "BUILTIN"
    LOCAL_USER = "LOCAL_USER"
    VERIFIED_COMMUNITY = "VERIFIED_COMMUNITY"
    UNTRUSTED = "UNTRUSTED"


class FailurePolicy(str, Enum):
    ABORT = "ABORT"
    CONTINUE = "CONTINUE"
    ASK_USER = "ASK_USER"


@dataclass
class PlatformRequirementBlock:
    capabilities: List[str] = field(default_factory=list)
    permissions: List[str] = field(default_factory=list)


@dataclass
class SkillRequirements:
    common: PlatformRequirementBlock = field(default_factory=PlatformRequirementBlock)
    android: PlatformRequirementBlock = field(default_factory=PlatformRequirementBlock)
    windows: PlatformRequirementBlock = field(default_factory=PlatformRequirementBlock)


@dataclass
class SkillIntents:
    exact_phrases: List[str] = field(default_factory=list)
    trigger_keywords: List[str] = field(default_factory=list)
    category: str = "general"


@dataclass
class SkillInput:
    name: str
    type: str  # "string", "number", "boolean"
    required: bool = False
    default_value: Any = None
    description: str = ""


@dataclass
class SkillPrecondition:
    app_identifier: Optional[str] = None
    window_state: str = "any"


@dataclass
class StepRetryPolicy:
    max_retries: int = 0
    delay_ms: int = 500


@dataclass
class StepVerification:
    type: str = "NONE"  # "ELEMENT_VISIBLE", "APP_FOREGROUND", "TEXT_MATCH", "NONE"
    expected_value: Optional[str] = None
    timeout_ms: int = 3000


@dataclass
class PlatformOverrides:
    android: Optional[Dict[str, Any]] = None
    windows: Optional[Dict[str, Any]] = None


@dataclass
class SkillStep:
    step_id: str
    action: str  # Canonical verb (e.g., "app.open", "ui.click")
    target: str = ""
    depends_on: List[str] = field(default_factory=list)
    arguments: Dict[str, Any] = field(default_factory=dict)
    platform_overrides: PlatformOverrides = field(default_factory=PlatformOverrides)
    timeout_ms: int = 10000
    retry_policy: StepRetryPolicy = field(default_factory=StepRetryPolicy)
    verification: StepVerification = field(default_factory=StepVerification)


@dataclass
class SkillDefinition:
    skill_id: str
    name: str
    description: str
    version: str = "1.0.0"
    schema_version: str = "1.0.0"
    author: str = "user"
    trust_level: TrustLevel = TrustLevel.LOCAL_USER
    checksum: str = ""
    signature: Optional[str] = None
    supported_platforms: List[str] = field(default_factory=lambda: ["android", "windows"])
    requirements: SkillRequirements = field(default_factory=SkillRequirements)
    allowed_capabilities: List[str] = field(default_factory=list)
    intents: SkillIntents = field(default_factory=SkillIntents)
    inputs: List[SkillInput] = field(default_factory=list)
    preconditions: List[SkillPrecondition] = field(default_factory=list)
    steps: List[SkillStep] = field(default_factory=list)
    failure_policy: FailurePolicy = FailurePolicy.ABORT

    def to_dict(self) -> Dict[str, Any]:
        """Converts dataclass hierarchy to plain dictionary."""
        return asdict(self)
