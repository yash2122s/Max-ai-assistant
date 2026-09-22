"""
MAX AI — Skill Parser for .json and .md (YAML Frontmatter) Skills (Python)
Enforces size limits, safe parsing, and strict separation of structured declarations from Markdown docs.
"""

import json
import logging
from dataclasses import dataclass
from typing import Optional, Dict, Any, List

try:
    import yaml
except ImportError:
    yaml = None

from .skill_definition import (
    SkillDefinition,
    SkillStep,
    SkillRequirements,
    PlatformRequirementBlock,
    SkillInput,
    SkillPrecondition,
    SkillIntents,
    StepRetryPolicy,
    StepVerification,
    PlatformOverrides,
    TrustLevel,
    FailurePolicy,
)

logger = logging.getLogger("MAX.SkillParser")


@dataclass
class SkillParseResult:
    success: bool
    skill: Optional[SkillDefinition] = None
    documentation_markdown: str = ""
    error_message: Optional[str] = None


class SkillParser:
    MAX_FILE_SIZE_BYTES = 512 * 1024  # 512 KB

    @classmethod
    def parse(cls, raw_content: str) -> SkillParseResult:
        """Parses a skill string from either JSON or Markdown with YAML frontmatter."""
        if not raw_content or not raw_content.strip():
            return SkillParseResult(success=False, error_message="Skill content is empty.")

        encoded_size = len(raw_content.encode("utf-8"))
        if encoded_size > cls.MAX_FILE_SIZE_BYTES:
            return SkillParseResult(success=False, error_message="Skill file exceeds maximum allowed size (512KB).")

        trimmed = raw_content.lstrip('\ufeff').strip()

        if trimmed.startswith("---"):
            return cls._parse_markdown(trimmed)
        elif trimmed.startswith("{"):
            return cls._parse_json(trimmed)
        else:
            return SkillParseResult(
                success=False,
                error_message="Unsupported skill format. File must start with '---' (YAML frontmatter) or '{' (JSON)."
            )

    @classmethod
    def _parse_json(cls, json_str: str) -> SkillParseResult:
        try:
            data = json.loads(json_str)
            if not isinstance(data, dict):
                return SkillParseResult(success=False, error_message="Root of JSON skill must be a JSON object.")
            skill = cls._dict_to_skill(data)
            return SkillParseResult(success=True, skill=skill, documentation_markdown="")
        except json.JSONDecodeError as jde:
            return SkillParseResult(success=False, error_message=f"JSON decoding error: {jde}")
        except Exception as e:
            return SkillParseResult(success=False, error_message=f"Error parsing JSON skill: {e}")

    @classmethod
    def _parse_markdown(cls, md_str: str) -> SkillParseResult:
        parts = md_str.split("---", 2)
        if len(parts) < 3:
            return SkillParseResult(
                success=False,
                error_message="Invalid Markdown frontmatter. Expected closing '---' delimiter."
            )

        fm_text = parts[1].strip()
        body_text = parts[2].strip()

        if not fm_text:
            return SkillParseResult(success=False, error_message="YAML frontmatter block is empty.")

        try:
            if yaml is not None:
                data = yaml.safe_load(fm_text) or {}
            else:
                # Basic fallback if pyyaml is missing
                data = {}
                for line in fm_text.splitlines():
                    if ":" in line and not line.strip().startswith("#"):
                        k, v = line.split(":", 1)
                        data[k.strip()] = v.strip().strip("\"'")

            if not isinstance(data, dict):
                return SkillParseResult(success=False, error_message="YAML frontmatter must evaluate to an object/dict.")

            skill = cls._dict_to_skill(data)
            return SkillParseResult(success=True, skill=skill, documentation_markdown=body_text)
        except Exception as e:
            return SkillParseResult(success=False, error_message=f"YAML frontmatter parse error: {e}")

    @classmethod
    def _dict_to_skill(cls, d: Dict[str, Any]) -> SkillDefinition:
        skill_id = d.get("skill_id", "")
        name = d.get("name", "")
        description = d.get("description", "")
        version = d.get("version", "1.0.0")
        schema_version = d.get("schema_version", "1.0.0")
        author = d.get("author", "user")
        
        trust_str = d.get("trust_level", "LOCAL_USER")
        try:
            trust_level = TrustLevel(trust_str)
        except ValueError:
            trust_level = TrustLevel.LOCAL_USER

        checksum = d.get("checksum", "")
        signature = d.get("signature")
        supported_platforms = d.get("supported_platforms", ["android", "windows"])

        # Requirements
        req_dict = d.get("requirements", {})
        req_common = PlatformRequirementBlock(
            capabilities=req_dict.get("common", {}).get("capabilities", []),
            permissions=req_dict.get("common", {}).get("permissions", [])
        )
        req_android = PlatformRequirementBlock(
            capabilities=req_dict.get("android", {}).get("capabilities", []),
            permissions=req_dict.get("android", {}).get("permissions", [])
        )
        req_windows = PlatformRequirementBlock(
            capabilities=req_dict.get("windows", {}).get("capabilities", []),
            permissions=req_dict.get("windows", {}).get("permissions", [])
        )
        requirements = SkillRequirements(common=req_common, android=req_android, windows=req_windows)

        allowed_capabilities = d.get("allowed_capabilities", [])
        
        # Intents
        intents_dict = d.get("intents", {})
        intents = SkillIntents(
            exact_phrases=intents_dict.get("exact_phrases", []),
            trigger_keywords=intents_dict.get("trigger_keywords", []),
            category=intents_dict.get("category", "general")
        )

        # Inputs
        inputs = [
            SkillInput(
                name=inp.get("name", ""),
                type=inp.get("type", "string"),
                required=inp.get("required", False),
                default_value=inp.get("default_value"),
                description=inp.get("description", "")
            )
            for inp in d.get("inputs", [])
        ]

        # Preconditions
        preconditions = [
            SkillPrecondition(
                app_identifier=prec.get("app_identifier"),
                window_state=prec.get("window_state", "any")
            )
            for prec in d.get("preconditions", [])
        ]

        # Steps
        steps = []
        for s in d.get("steps", []):
            rp_dict = s.get("retry_policy", {})
            retry_policy = StepRetryPolicy(
                max_retries=rp_dict.get("max_retries", 0),
                delay_ms=rp_dict.get("delay_ms", 500)
            )

            vf_dict = s.get("verification", {})
            verification = StepVerification(
                type=vf_dict.get("type", "NONE"),
                expected_value=vf_dict.get("expected_value"),
                timeout_ms=vf_dict.get("timeout_ms", 3000)
            )

            po_dict = s.get("platform_overrides", {})
            platform_overrides = PlatformOverrides(
                android=po_dict.get("android"),
                windows=po_dict.get("windows")
            )

            step = SkillStep(
                step_id=s.get("step_id", ""),
                action=s.get("action", ""),
                target=s.get("target", ""),
                depends_on=s.get("depends_on", []),
                arguments=s.get("arguments", {}),
                platform_overrides=platform_overrides,
                timeout_ms=s.get("timeout_ms", 10000),
                retry_policy=retry_policy,
                verification=verification
            )
            steps.append(step)

        fail_str = d.get("failure_policy", "ABORT")
        try:
            failure_policy = FailurePolicy(fail_str)
        except ValueError:
            failure_policy = FailurePolicy.ABORT

        return SkillDefinition(
            skill_id=skill_id,
            name=name,
            description=description,
            version=version,
            schema_version=schema_version,
            author=author,
            trust_level=trust_level,
            checksum=checksum,
            signature=signature,
            supported_platforms=supported_platforms,
            requirements=requirements,
            allowed_capabilities=allowed_capabilities,
            intents=intents,
            inputs=inputs,
            preconditions=preconditions,
            steps=steps,
            failure_policy=failure_policy
        )
