"""
MAX AI — Skill Registry for Windows Agent
Manages installed persistent skills and in-memory ephemeral prompt-attached skills.
"""

import logging
from dataclasses import dataclass
from typing import Dict, Any, List, Optional, Set

from core.skills.skill_definition import SkillDefinition
from core.skills.skill_parser import SkillParser
from core.skills.skill_validator import SkillValidator
from core.skills.storage import SkillStorage

logger = logging.getLogger("MAX.Skills.Registry")


@dataclass
class SkillImportResult:
    success: bool = False
    skill: Optional[SkillDefinition] = None
    is_ephemeral: bool = False
    documentation_markdown: str = ""
    error_message: Optional[str] = None


class SkillRegistry:
    """Central registry managing installed and ephemeral skills."""

    def __init__(self, storage: Optional[SkillStorage] = None):
        self.storage = storage or SkillStorage()
        self._installed_skills: Dict[str, SkillDefinition] = {}
        self._disabled_skill_ids: Set[str] = set()
        self._ephemeral_skills: Dict[str, SkillDefinition] = {}
        self.refresh_from_storage()

    def refresh_from_storage(self):
        self._installed_skills.clear()
        for skill in self.storage.list_installed():
            self._installed_skills[skill.skill_id] = skill
        logger.info(f"Loaded {len(self._installed_skills)} skills into SkillRegistry.")

    def install(self, raw_content: str, target_platform: str = "windows") -> SkillImportResult:
        parse_result = SkillParser.parse(raw_content)
        if not parse_result.success or not parse_result.skill:
            return SkillImportResult(
                success=False,
                error_message=parse_result.error_message or "Failed to parse skill"
            )

        skill = parse_result.skill
        validation = SkillValidator.validate(skill, target_platform=target_platform)
        if not validation.is_valid:
            self.storage.quarantine_raw(skill.skill_id, raw_content, validation.error_summary())
            return SkillImportResult(
                success=False,
                error_message=f"Skill validation rejected: {validation.error_summary()}"
            )

        storage_res = self.storage.install(skill, raw_content, target_platform=target_platform)
        if not storage_res.get("success"):
            return SkillImportResult(
                success=False,
                error_message=storage_res.get("error", "Storage installation failed")
            )

        self._installed_skills[skill.skill_id] = skill
        self._disabled_skill_ids.discard(skill.skill_id)
        return SkillImportResult(
            success=True,
            skill=skill,
            is_ephemeral=False,
            documentation_markdown=parse_result.documentation_markdown
        )

    def register_ephemeral(self, raw_content: str, target_platform: str = "windows") -> SkillImportResult:
        parse_result = SkillParser.parse(raw_content)
        if not parse_result.success or not parse_result.skill:
            return SkillImportResult(
                success=False,
                error_message=parse_result.error_message or "Failed to parse ephemeral skill"
            )

        skill = parse_result.skill
        validation = SkillValidator.validate(skill, target_platform=target_platform)
        if not validation.is_valid:
            return SkillImportResult(
                success=False,
                error_message=f"Ephemeral validation rejected: {validation.error_summary()}"
            )

        self._ephemeral_skills[skill.skill_id] = skill
        logger.info(f"Registered ephemeral skill '{skill.skill_id}'.")
        return SkillImportResult(
            success=True,
            skill=skill,
            is_ephemeral=True,
            documentation_markdown=parse_result.documentation_markdown
        )

    def get(self, skill_id: str) -> Optional[SkillDefinition]:
        if skill_id in self._ephemeral_skills:
            return self._ephemeral_skills[skill_id]
        if skill_id in self._disabled_skill_ids:
            return None
        return self._installed_skills.get(skill_id)

    def list_installed(self, include_disabled: bool = False) -> List[SkillDefinition]:
        if include_disabled:
            return list(self._installed_skills.values())
        return [s for sid, s in self._installed_skills.items() if sid not in self._disabled_skill_ids]

    def list_ephemeral(self) -> List[SkillDefinition]:
        return list(self._ephemeral_skills.values())

    def contains(self, skill_id: str) -> bool:
        if skill_id in self._ephemeral_skills:
            return True
        return skill_id in self._installed_skills and skill_id not in self._disabled_skill_ids

    def disable(self, skill_id: str) -> bool:
        if skill_id in self._installed_skills:
            self._disabled_skill_ids.add(skill_id)
            return True
        return False

    def enable(self, skill_id: str) -> bool:
        if skill_id in self._disabled_skill_ids:
            self._disabled_skill_ids.remove(skill_id)
            return True
        return False

    def delete(self, skill_id: str) -> bool:
        self._ephemeral_skills.pop(skill_id, None)
        self._disabled_skill_ids.discard(skill_id)
        self._installed_skills.pop(skill_id, None)
        return self.storage.delete(skill_id)

    def clear_ephemeral(self):
        self._ephemeral_skills.clear()
