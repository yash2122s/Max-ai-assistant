"""
MAX AI — Progressive Skills Engine (Agentskills.io Standard)
Implements 3-tier progressive disclosure:
- Tier 1: Metadata index (name, description, category) via skills_list
- Tier 2: Full SKILL.md instructions via skill_view
- Tier 3: Supplementary references/templates/scripts via skill_view
"""

import os
import re
try:
    import yaml
except ImportError:
    yaml = None
import json
import logging
from pathlib import Path
from typing import Dict, Any, List, Optional, Tuple

from config import SKILLS_DIR, PROJECT_ROOT

logger = logging.getLogger("MAXWindowsAgent.SkillsEngine")


def parse_skill_frontmatter(content: str) -> Tuple[Dict[str, Any], str]:
    """
    Parses YAML frontmatter enclosed in '---' from markdown content.
    Returns (metadata_dict, body_markdown).
    """
    if not content or not content.startswith("---"):
        return {}, content

    parts = content.split("---", 2)
    if len(parts) < 3:
        return {}, content

    try:
        if yaml is not None:
            metadata = yaml.safe_load(parts[1]) or {}
        else:
            # Fallback key-value parser for frontmatter
            metadata = {}
            for line in parts[1].splitlines():
                if ":" in line:
                    k, v = line.split(":", 1)
                    metadata[k.strip()] = v.strip().strip("\"'")
        body = parts[2].strip()
        return metadata, body
    except Exception as e:
        logger.debug(f"YAML frontmatter parse error: {e}")
        return {}, content


class SkillsEngine:
    """
    Progressive Skills Engine conforming to agentskills.io standard.
    """
    _instance: Optional['SkillsEngine'] = None

    def __init__(self, skills_dir: Optional[str] = None, db=None):
        self.skills_dir = skills_dir or SKILLS_DIR
        from core.state_database import state_db
        self.state_db = db or state_db
        os.makedirs(self.skills_dir, exist_ok=True)
        self._seed_default_skills()

    @classmethod
    def get_instance(cls) -> 'SkillsEngine':
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def _seed_default_skills(self):
        """Creates default starter skills if skills directory is empty."""
        try:
            # 1. Windows Automation Skill
            win_dir = os.path.join(self.skills_dir, "windows-automation")
            if not os.path.exists(win_dir):
                os.makedirs(win_dir, exist_ok=True)
                with open(os.path.join(win_dir, "SKILL.md"), "w", encoding="utf-8") as f:
                    f.write("""---
name: windows-automation
description: Expert guidelines for automating Windows applications, system settings, clipboard, and window management.
version: 1.0.0
platforms: [windows]
---

# Windows Workstation Automation Skill

## Guidelines
- Always verify application state before typing text or closing windows.
- Use `switch_to_app` before sending keyboard shortcuts or typing text.
- For volume/brightness adjustments, report current levels to user after setting.
- Destructive operations (shutdown, restart, permanent delete) MUST request confirmation first.
""")

            # 2. Android Multi-Device Control Skill
            droid_dir = os.path.join(self.skills_dir, "android-ecosystem")
            if not os.path.exists(droid_dir):
                os.makedirs(droid_dir, exist_ok=True)
                with open(os.path.join(droid_dir, "SKILL.md"), "w", encoding="utf-8") as f:
                    f.write("""---
name: android-ecosystem
description: Instructions for orchestrating paired Android companion devices (Phone 1, Phone 2, Tablet) via DeviceRouter.
version: 1.0.0
platforms: [windows, android]
---

# Android Multi-Device Ecosystem Skill

## Device Resolution
- Targets can be identified by display name ('Phone 1', 'Phone 2', 'Tablet') or model name.
- Single device commands: use `execute_device_action(target='Phone 1', action='FLASHLIGHT_ON')`.
- Broadcast / multi-device commands: use `execute_multi_device_action(targets='all_phones', action='FLASHLIGHT_OFF')`.
- Always check device connectivity via `list_connected_devices` if a device is unresponsive.
""")

            # 3. Code Patching & Self-Repair Skill
            code_dir = os.path.join(self.skills_dir, "code-repair")
            if not os.path.exists(code_dir):
                os.makedirs(code_dir, exist_ok=True)
                with open(os.path.join(code_dir, "SKILL.md"), "w", encoding="utf-8") as f:
                    f.write("""---
name: code-repair
description: Unified diff code patching, traceback diagnosis, and sandbox testing workflows.
version: 1.0.0
platforms: [windows, linux, macos]
---

# Code Repair & Patching Skill

## Patching Protocol
1. Read the target file content and note surrounding context lines.
2. Formulate a clean unified diff patch with @@ line headers and 3 context lines.
3. Apply patch via `apply_patch(target_file=..., diff_content=...)`.
4. Run unit tests (`pytest`) to verify the repair before concluding.
""")

        except Exception as e:
            logger.warning(f"Error seeding default skills: {e}")

    def list_skills(self, category: Optional[str] = None) -> Dict[str, Any]:
        """
        Lists all available skills with Tier 1 metadata (token efficient).
        """
        skills = []
        try:
            for root, dirs, files in os.walk(self.skills_dir):
                if "SKILL.md" in files:
                    skill_file = os.path.join(root, "SKILL.md")
                    try:
                        with open(skill_file, "r", encoding="utf-8") as f:
                            content = f.read()
                        meta, _ = parse_skill_frontmatter(content)
                        rel_dir = os.path.relpath(root, self.skills_dir)
                        cat = rel_dir.split(os.sep)[0] if rel_dir != "." else "general"

                        if category and cat.lower() != category.lower():
                            continue

                        skill_name = meta.get("name") or os.path.basename(root)
                        skills.append({
                            "name": skill_name,
                            "description": meta.get("description", "No description provided.")[:1024],
                            "category": cat,
                            "version": meta.get("version", "1.0.0"),
                            "platforms": meta.get("platforms", ["windows"]),
                            "path": rel_dir
                        })
                    except Exception as fe:
                        logger.debug(f"Error reading skill file {skill_file}: {fe}")

            return {
                "status": "success",
                "count": len(skills),
                "skills": skills
            }
        except Exception as e:
            logger.error(f"Error listing skills: {e}")
            return {"status": "error", "message": str(e), "skills": []}

    def view_skill(self, skill_name: str, subpath: Optional[str] = None) -> Dict[str, Any]:
        """
        Loads full skill content (Tier 2/3 progressive disclosure).
        """
        if not skill_name or not skill_name.strip():
            return {"status": "error", "message": "Skill name cannot be empty."}

        clean_name = skill_name.strip().lower()

        # Find directory matching skill_name
        target_dir = None
        for root, dirs, files in os.walk(self.skills_dir):
            if "SKILL.md" in files:
                skill_file = os.path.join(root, "SKILL.md")
                try:
                    with open(skill_file, "r", encoding="utf-8") as f:
                        meta, _ = parse_skill_frontmatter(f.read())
                    if meta.get("name", "").lower() == clean_name or os.path.basename(root).lower() == clean_name:
                        target_dir = root
                        break
                except Exception:
                    pass

        if not target_dir:
            return {"status": "error", "message": f"Skill '{skill_name}' not found."}

        if subpath:
            # Tier 3: Supplementary file loading (references, templates, scripts)
            clean_sub = os.path.normpath(subpath.lstrip("/\\"))
            target_file = os.path.abspath(os.path.join(target_dir, clean_sub))
            # Security boundary check: prevent path traversal out of target_dir
            if not target_file.startswith(os.path.abspath(target_dir)) or not os.path.exists(target_file):
                return {"status": "error", "message": f"File '{subpath}' not found in skill '{skill_name}'."}
        else:
            # Tier 2: Main SKILL.md
            target_file = os.path.join(target_dir, "SKILL.md")

        try:
            with open(target_file, "r", encoding="utf-8") as f:
                body = f.read()

            meta, main_md = parse_skill_frontmatter(body) if not subpath else ({}, body)
            
            # Record skill usage in StateDB ledger
            try:
                from core.state_database import state_db
                state_db.record_skill_usage(skill_name, success=True)
            except Exception:
                pass

            return {
                "status": "success",
                "skill_name": skill_name,
                "subpath": subpath or "SKILL.md",
                "metadata": meta,
                "content": main_md
            }
        except Exception as e:
            return {"status": "error", "message": f"Failed to read skill content: {e}"}

    def learn_skill(
        self,
        instruction: str,
        content: Optional[str] = None,
        source_goal: Optional[str] = None,
        trajectory: Optional[List[Dict[str, Any]]] = None
    ) -> Dict[str, Any]:
        """
        Synthesizes a new Agentskills.io SKILL.md from task instructions or user guidance.
        """
        from core.learning_loop import learning_loop, LearnPromptBuilder
        if content:
            # Direct content provided — validate and save
            clean_name = re.sub(r'[^a-zA-Z0-9_-]', '-', instruction.strip().lower()[:32])
            return learning_loop.learn_and_save_skill(
                skill_name=clean_name,
                skill_content=content,
                source_goal=source_goal or instruction,
                trajectory=trajectory,
                skills_dir=self.skills_dir
            )
        else:
            # Build synthesis prompt for LLM execution
            prompt = LearnPromptBuilder.build_synthesis_prompt(instruction=instruction, trajectory=trajectory)
            return {
                "status": "prompt_ready",
                "instruction": instruction,
                "synthesis_prompt": prompt
            }

    def manage_skill(
        self,
        action: str,
        name: str,
        description: str = "",
        content: str = "",
        category: str = "custom"
    ) -> Dict[str, Any]:
        """
        Allows MAX to dynamically create, update, pin, archive, or delete skills at runtime.
        """
        clean_action = action.strip().lower()
        clean_name = re.sub(r'[^a-zA-Z0-9_-]', '-', name.strip().lower())

        if not clean_name:
            return {"status": "error", "message": "Invalid skill name."}

        skill_dir = os.path.join(self.skills_dir, category, clean_name)

        if clean_action in ("pin", "archive", "restore"):
            state_map = {"pin": "pinned", "archive": "archived", "restore": "active"}
            target_state = state_map.get(clean_action, "active")
            updated = self.state_db.set_skill_lifecycle_state(clean_name, target_state)
            return {
                "status": "success" if updated else "info",
                "message": f"Skill '{clean_name}' state set to '{target_state}'."
            }

        if clean_action in ("create", "update"):
            os.makedirs(skill_dir, exist_ok=True)
            skill_file = os.path.join(skill_dir, "SKILL.md")
            
            clean_desc = (description or f"Custom skill for {clean_name}")[:60]
            if not clean_desc.endswith("."):
                clean_desc += "."

            frontmatter = {
                "name": clean_name,
                "description": clean_desc,
                "version": "0.1.0",
                "author": "MAX Agent",
                "platforms": ["windows"]
            }
            if yaml:
                fm_str = yaml.dump(frontmatter)
            else:
                fm_lines = [f"{k}: {v}" if not isinstance(v, list) else f"{k}: {json.dumps(v)}" for k, v in frontmatter.items()]
                fm_str = "\n".join(fm_lines) + "\n"
            full_content = f"---\n{fm_str}---\n\n{content.strip()}\n"
            with open(skill_file, "w", encoding="utf-8") as f:
                f.write(full_content)

            from core.state_database import state_db
            state_db.record_skill_usage(clean_name, success=True, is_agent_created=True)

            return {
                "status": "success",
                "message": f"Skill '{clean_name}' successfully {clean_action}d in category '{category}'.",
                "name": clean_name,
                "path": os.path.relpath(skill_file, self.skills_dir)
            }

        elif clean_action == "delete":
            import shutil
            if os.path.exists(skill_dir):
                shutil.rmtree(skill_dir)
                return {"status": "success", "message": f"Skill '{clean_name}' deleted."}
            return {"status": "error", "message": f"Skill '{clean_name}' not found."}

        return {"status": "error", "message": f"Unknown action '{action}'."}


# Singleton instance
skills_engine = SkillsEngine.get_instance()


# ─── Tool Wrappers & Declarations for UnifiedToolRegistry ─────────────────────

def skills_list(category: Optional[str] = None) -> Dict[str, Any]:
    """List available capability skills with metadata summary (Tier 1)."""
    return skills_engine.list_skills(category=category)


def skill_view(skill_name: str, subpath: Optional[str] = None) -> Dict[str, Any]:
    """Load full instructions or reference files for a specific skill (Tier 2/3)."""
    return skills_engine.view_skill(skill_name=skill_name, subpath=subpath)


def skill_learn(instruction: str, content: Optional[str] = None) -> Dict[str, Any]:
    """Synthesize a new skill from natural language instruction or workflow notes (/learn)."""
    return skills_engine.learn_skill(instruction=instruction, content=content)


def skill_manage(action: str, name: str, description: str = "", content: str = "", category: str = "custom") -> Dict[str, Any]:
    """Create, update, pin, archive, or delete a skill definition in the skills repository."""
    return skills_engine.manage_skill(action=action, name=name, description=description, content=content, category=category)


SKILL_TOOL_DECLARATIONS = [
    {
        "name": "skills_list",
        "description": "List available specialized capability skills with name and metadata summary (Tier 1 progressive disclosure).",
        "parameters": {
            "type": "object",
            "properties": {
                "category": {
                    "type": "string",
                    "description": "Optional category filter (e.g. 'windows', 'android', 'coding', 'browser')"
                }
            }
        }
    },
    {
        "name": "skill_view",
        "description": "Load the complete detailed instructions (SKILL.md) or supplementary reference file for a skill.",
        "parameters": {
            "type": "object",
            "properties": {
                "skill_name": {
                    "type": "string",
                    "description": "The exact name of the skill to view (e.g. 'windows-automation', 'android-ecosystem', 'code-repair')"
                },
                "subpath": {
                    "type": "string",
                    "description": "Optional relative subpath to a reference, template, or script file inside the skill directory"
                }
            },
            "required": ["skill_name"]
        }
    },
    {
        "name": "skill_learn",
        "description": "Synthesize a new reusable Agentskills.io capability skill from an instruction, successful workflow, or documentation (/learn).",
        "parameters": {
            "type": "object",
            "properties": {
                "instruction": {
                    "type": "string",
                    "description": "Description of the skill capability to learn or the workflow that was performed."
                },
                "content": {
                    "type": "string",
                    "description": "Optional complete SKILL.md markdown text adhering to Agentskills.io format."
                }
            },
            "required": ["instruction"]
        }
    },
    {
        "name": "skill_manage",
        "description": "Create, update, pin, archive, or delete a skill definition in the skills repository.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["create", "update", "pin", "archive", "restore", "delete"],
                    "description": "Action to perform ('create', 'update', 'pin', 'archive', 'restore', 'delete')"
                },
                "name": {
                    "type": "string",
                    "description": "Name of the skill"
                },
                "description": {
                    "type": "string",
                    "description": "Short description of the skill (max 60 chars)"
                },
                "content": {
                    "type": "string",
                    "description": "Markdown instructions body of the skill"
                },
                "category": {
                    "type": "string",
                    "description": "Category for the skill (e.g. 'windows', 'coding', 'custom')"
                }
            },
            "required": ["action", "name"]
        }
    }
]

SKILL_TOOL_FUNCTIONS = {
    "skills_list": skills_list,
    "skill_view": skill_view,
    "skill_learn": skill_learn,
    "skill_manage": skill_manage,
}
