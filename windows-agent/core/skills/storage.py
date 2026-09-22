"""
MAX AI — Skill Storage Manager for Windows Agent
Provides persistent filesystem storage under storage/skills/
with atomic staging promotions, SHA-256 verification, and quarantine management.
"""

import os
import re
import json
import shutil
import logging
import time
from pathlib import Path
from typing import Dict, Any, List, Optional

from core.skills.skill_definition import SkillDefinition
from core.skills.skill_parser import SkillParser
from core.skills.skill_validator import SkillValidator
from core.skills.canonical_serializer import CanonicalJsonSerializer

logger = logging.getLogger("MAX.Skills.Storage")


class SkillStorage:
    """Manages skill persistence, atomic installation, and quarantine."""

    def __init__(self, root_dir: Optional[Path] = None):
        if root_dir is None:
            self.root_dir = Path(os.path.dirname(os.path.abspath(__file__))).parent.parent / "storage" / "skills"
        else:
            self.root_dir = Path(root_dir)

        self.installed_dir = self.root_dir / "installed"
        self.staging_dir = self.root_dir / "staging"
        self.quarantine_dir = self.root_dir / "quarantine"
        self.backups_dir = self.root_dir / "backups"

        self._ensure_directories()

    def _ensure_directories(self):
        self.installed_dir.mkdir(parents=True, exist_ok=True)
        self.staging_dir.mkdir(parents=True, exist_ok=True)
        self.quarantine_dir.mkdir(parents=True, exist_ok=True)
        self.backups_dir.mkdir(parents=True, exist_ok=True)

    @staticmethod
    def sanitize_skill_id(skill_id: str) -> str:
        """Sanitizes skill ID to prevent path traversal."""
        return re.sub(r"[^a-zA-Z0-9._-]", "_", skill_id.strip())

    def install(
        self,
        skill: SkillDefinition,
        raw_content: str,
        target_platform: str = "windows"
    ) -> Dict[str, Any]:
        """Atomically installs a validated skill."""
        self._ensure_directories()
        sanitized_id = self.sanitize_skill_id(skill.skill_id)
        if not sanitized_id:
            return {"success": False, "error": "Invalid or blank skill_id"}

        if len(raw_content.encode("utf-8")) > SkillParser.MAX_FILE_SIZE_BYTES:
            self.quarantine_raw(sanitized_id, raw_content, "Exceeds maximum file size (512KB)")
            return {"success": False, "error": "File exceeds 512KB size limit"}

        validation = SkillValidator.validate(skill, target_platform=target_platform)
        if not validation.is_valid:
            self.quarantine_raw(sanitized_id, raw_content, f"Validation error: {validation.error_summary()}")
            return {"success": False, "error": f"Validation rejected: {validation.error_summary()}"}

        target_skill_dir = self.installed_dir / sanitized_id
        staging_skill_dir = self.staging_dir / f"{sanitized_id}_{int(time.time() * 1000)}"

        try:
            if staging_skill_dir.exists():
                shutil.rmtree(staging_skill_dir)
            staging_skill_dir.mkdir(parents=True, exist_ok=True)

            # 1. Write source file
            is_md = raw_content.lstrip().startswith("---")
            source_file = staging_skill_dir / ("source.md" if is_md else "source.json")
            source_file.write_text(raw_content, encoding="utf-8")

            # 2. Write canonical.json
            canonical_json = CanonicalJsonSerializer.to_canonical_json(skill)
            (staging_skill_dir / "canonical.json").write_text(canonical_json, encoding="utf-8")

            # 3. Write manifest.json
            computed_checksum = CanonicalJsonSerializer.compute_checksum(skill)
            manifest = {
                "skill_id": skill.skill_id,
                "name": skill.name,
                "version": skill.version,
                "author": skill.author,
                "checksum": computed_checksum,
                "installed_at": time.time(),
                "enabled": True
            }
            (staging_skill_dir / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

            # 4. Backup existing if present
            if target_skill_dir.exists():
                backup_dest = self.backups_dir / f"{sanitized_id}_{int(time.time() * 1000)}"
                shutil.move(str(target_skill_dir), str(backup_dest))

            # 5. Atomic Promotion
            shutil.move(str(staging_skill_dir), str(target_skill_dir))
            logger.info(f"Successfully installed skill '{skill.skill_id}' to {target_skill_dir}")
            return {
                "success": True,
                "skill_id": skill.skill_id,
                "installed_path": str(target_skill_dir)
            }
        except Exception as e:
            logger.error(f"Failed to install skill '{sanitized_id}': {e}", exc_info=True)
            if staging_skill_dir.exists():
                shutil.rmtree(staging_skill_dir)
            return {"success": False, "error": str(e)}

    def load_skill(self, skill_id: str) -> Optional[SkillDefinition]:
        """Loads an installed skill from canonical.json."""
        sanitized_id = self.sanitize_skill_id(skill_id)
        canonical_file = self.installed_dir / sanitized_id / "canonical.json"
        if not canonical_file.exists():
            return None

        try:
            content = canonical_file.read_text(encoding="utf-8")
            res = SkillParser.parse(content)
            return res.skill
        except Exception as e:
            logger.error(f"Failed to load skill '{skill_id}': {e}")
            return None

    def load_raw_source(self, skill_id: str) -> Optional[str]:
        """Loads the raw source file for audit or export."""
        sanitized_id = self.sanitize_skill_id(skill_id)
        skill_dir = self.installed_dir / sanitized_id
        for fname in ("source.md", "source.json", "canonical.json"):
            fpath = skill_dir / fname
            if fpath.exists():
                return fpath.read_text(encoding="utf-8")
        return None

    def list_installed(self) -> List[SkillDefinition]:
        """Lists all installed SkillDefinitions."""
        self._ensure_directories()
        skills = []
        for item in self.installed_dir.iterdir():
            if item.is_dir():
                canonical_file = item / "canonical.json"
                if canonical_file.exists():
                    try:
                        res = SkillParser.parse(canonical_file.read_text(encoding="utf-8"))
                        if res.skill:
                            skills.append(res.skill)
                    except Exception as e:
                        logger.warning(f"Error loading skill in {item.name}: {e}")
        return skills

    def contains(self, skill_id: str) -> bool:
        sanitized_id = self.sanitize_skill_id(skill_id)
        return (self.installed_dir / sanitized_id).exists()

    def delete(self, skill_id: str) -> bool:
        sanitized_id = self.sanitize_skill_id(skill_id)
        target_dir = self.installed_dir / sanitized_id
        if target_dir.exists():
            shutil.rmtree(target_dir)
            return True
        return False

    def quarantine_raw(self, skill_id: str, content: str, reason: str):
        self._ensure_directories()
        sanitized_id = self.sanitize_skill_id(skill_id)
        q_dir = self.quarantine_dir / f"{sanitized_id}_{int(time.time() * 1000)}"
        q_dir.mkdir(parents=True, exist_ok=True)
        (q_dir / "quarantine_reason.txt").write_text(reason, encoding="utf-8")
        (q_dir / "payload.txt").write_text(content, encoding="utf-8")
        logger.warning(f"Quarantined skill '{skill_id}': {reason}")
