"""
MAX AI — Closed Learning Loop & Skill Synthesis Engine
Implements autonomous learning from task execution:
1. TrajectoryEvaluator: Detects successful, compound multi-step workflows.
2. LearnPromptBuilder: Standards-guided /learn synthesis conforming to Agentskills.io.
3. SkillValidator & SecurityScanner: AST, character-limit, and threat pattern validation.
4. SkillUsageTracker: Records skill provenance, usage counts, and success metrics.
5. BackgroundCurator: Periodic maintenance, idle pruning, archiving, and lifecycle transitions.
"""

import os
import re
import yaml
import json
import time
import logging
from pathlib import Path
from typing import Dict, Any, List, Optional, Tuple

from config import SKILLS_DIR, PROJECT_ROOT
from core.state_database import state_db
from core.security import security_guard

logger = logging.getLogger("MAXWindowsAgent.LearningLoop")


class TrajectoryEvaluator:
    """
    Evaluates multi-turn goal execution trajectories to determine
    whether a workflow represents a reusable, high-value procedure.
    """

    MIN_STEPS = 2
    MIN_DISTINCT_TOOLS = 2

    @classmethod
    def is_worth_learning(
        cls,
        goal: str,
        steps: List[Dict[str, Any]],
        final_answer: str,
        status: str = "success"
    ) -> Tuple[bool, str]:
        """
        Determines if an execution trajectory should trigger autonomous skill generation.
        Returns (should_learn, reason).
        """
        if status != "success":
            return False, f"Execution status '{status}' did not succeed"

        if not steps or len(steps) < cls.MIN_STEPS:
            return False, f"Trajectory step count ({len(steps)}) is below threshold ({cls.MIN_STEPS})"

        distinct_tools = set(s.get("tool") for s in steps if s.get("tool"))
        if len(distinct_tools) < cls.MIN_DISTINCT_TOOLS:
            return False, f"Distinct tool count ({len(distinct_tools)}) is below threshold ({cls.MIN_DISTINCT_TOOLS})"

        # Check for unhandled fatal errors in steps
        for s in steps:
            res = s.get("result", {})
            if isinstance(res, dict) and res.get("status") == "error" and "unsupported" in str(res.get("message", "")).lower():
                return False, "Trajectory contains fatal unsupported errors"

        # Check goal text length and complexity
        if not goal or len(goal.strip()) < 10:
            return False, "Goal text is too trivial"

        return True, f"Compound successful workflow with {len(steps)} steps across {len(distinct_tools)} tools"


class LearnPromptBuilder:
    """
    Constructs strict, standards-guided prompts that instruct an LLM
    to generate valid Agentskills.io SKILL.md documents.
    """

    AUTHORING_RULES = """\
Follow the strict Agentskills.io skill-authoring standards:
1. Frontmatter (YAML enclosed in '---'):
   - name: lowercase-hyphenated, <= 64 chars, no spaces (e.g. 'deploy-fastapi-service').
   - description: ONE sentence, EXACTLY <= 60 characters, ending with a period. State the capability concisely without buzzwords (e.g. 'Deploy FastAPI services to local Windows port.').
   - version: 0.1.0
   - author: Hermes
   - platforms: [windows] or [android] or omit if portable.
2. Required Section Hierarchy:
   - # <Human Title>
   - ## When to Use (bullet list of trigger phrases)
   - ## Prerequisites (required paths, env vars, tools)
   - ## How to Run (canonical tool invocation)
   - ## Quick Reference (flat command summary)
   - ## Procedure (numbered steps with exact tool calls)
   - ## Pitfalls (known edge cases)
   - ## Verification (check proving success)
3. Framing:
   - Reference built-in MAX tools in backticks: `read_file`, `write_file`, `execute_device_action`, `execute_code`, `apply_patch`, `web_search`, `set_volume`.
"""

    @classmethod
    def build_synthesis_prompt(
        cls,
        instruction: str,
        trajectory: Optional[List[Dict[str, Any]]] = None,
        context_notes: Optional[str] = None
    ) -> str:
        """
        Builds the complete instruction prompt for skill synthesis.
        """
        traj_summary = ""
        if trajectory:
            traj_lines = []
            for idx, step in enumerate(trajectory, 1):
                t_name = step.get("tool", "unknown")
                t_args = json.dumps(step.get("arguments", {}), default=str)
                t_res = str(step.get("result", ""))[:200]
                traj_lines.append(f"Step {idx}: {t_name}({t_args}) -> {t_res}")
            traj_summary = "\n".join(traj_lines)

        traj_block = f"<EXECUTION_TRAJECTORY>\n{traj_summary}\n</EXECUTION_TRAJECTORY>\n" if traj_summary else ""
        context_block = f"<CONTEXT_NOTES>\n{context_notes}\n</CONTEXT_NOTES>\n" if context_notes else ""

        prompt = f"""You are Hermes, the primary autonomous AI skill curator.
Your task is to author a complete, production-grade SKILL.md document from the following task experience.

<TASK_INSTRUCTION>
{instruction}
</TASK_INSTRUCTION>

{traj_block}{context_block}
{cls.AUTHORING_RULES}

Output ONLY the complete valid SKILL.md markdown text enclosed in standard frontmatter. Do NOT include extraneous conversational filler.
"""
        return prompt


class SkillValidator:
    """
    Validates synthesized SKILL.md documents against strict formatting,
    character bounds, and security patterns.
    """

    THREAT_PATTERNS = [
        re.compile(r'(?i)\bformat\s+[a-z]:'),
        re.compile(r'(?i)\bdiskpart\b'),
        re.compile(r'(?i)\brmdir\s+/[sq]\s+[a-z]:\\'),
        re.compile(r'(?i)\bdel\s+/[fsq]\s+[a-z]:\\'),
        re.compile(r'(?i)<PASSIVE_CONTEXT>'),
    ]

    @classmethod
    def validate(cls, raw_content: str) -> Tuple[bool, str, Dict[str, Any]]:
        """
        Validates a SKILL.md text.
        Returns (is_valid, message, metadata).
        """
        if not raw_content or not raw_content.strip():
            return False, "Skill content is empty", {}

        clean_text = raw_content.strip()

        # 1. Check Threat Patterns
        for pattern in cls.THREAT_PATTERNS:
            if pattern.search(clean_text):
                return False, f"Skill content contains prohibited dangerous pattern: {pattern.pattern}", {}

        # 2. Frontmatter Extraction
        if not clean_text.startswith("---"):
            return False, "Skill content is missing initial '---' YAML frontmatter header", {}

        parts = clean_text.split("---", 2)
        if len(parts) < 3:
            return False, "Malformed YAML frontmatter delimiters", {}

        try:
            metadata = yaml.safe_load(parts[1]) or {}
        except Exception as e:
            return False, f"Invalid YAML frontmatter: {e}", {}

        # 3. Validate Frontmatter Fields
        name = str(metadata.get("name", "")).strip()
        if not name or not re.match(r"^[a-z0-9-]+$", name) or len(name) > 64:
            return False, f"Invalid skill name '{name}'. Must be lowercase-hyphenated and <= 64 chars.", metadata

        description = str(metadata.get("description", "")).strip()
        if not description:
            return False, "Skill description is required", metadata
        if len(description) > 60:
            return False, f"Skill description length ({len(description)} chars) exceeds maximum allowed 60 characters: '{description}'", metadata

        # 4. Check Section Hierarchy
        body = parts[2]
        required_sections = ["When to Use", "Procedure", "Verification"]
        for sec in required_sections:
            if sec.lower() not in body.lower():
                return False, f"Missing required skill section: '## {sec}'", metadata

        return True, "Skill content successfully validated", metadata


class BackgroundCurator:
    """
    Periodic background maintenance orchestrator for agent-created skills:
    - Prunes unused stale skills (> 30 days) to 'stale' state.
    - Archives abandoned skills (> 90 days unused) into 'archived' state.
    - Preserves 'pinned' skills unconditionally.
    - Persists state in .curator_state.
    """

    DEFAULT_STALE_DAYS = 30
    DEFAULT_ARCHIVE_DAYS = 90

    @classmethod
    def run_curation_pass(
        cls,
        db=None,
        skills_dir: Optional[str] = None,
        stale_days: int = DEFAULT_STALE_DAYS,
        archive_days: int = DEFAULT_ARCHIVE_DAYS
    ) -> Dict[str, Any]:
        """
        Executes a deterministic curation pass over skill_ledger and storage/skills/.
        """
        active_db = db or state_db
        active_skills_dir = skills_dir or SKILLS_DIR
        now = time.time()
        stale_cutoff = now - (stale_days * 86400)
        archive_cutoff = now - (archive_days * 86400)

        ledger = active_db.list_skill_ledger()
        updated_stale = 0
        updated_archived = 0
        active_count = 0
        pinned_count = 0

        for entry in ledger:
            s_name = entry["name"]
            state = entry.get("lifecycle_state", "active")
            last_used = entry.get("last_used_at") or entry.get("created_at") or now

            if state == "pinned":
                pinned_count += 1
                continue

            if last_used < archive_cutoff:
                active_db.set_skill_lifecycle_state(s_name, "archived")
                updated_archived += 1
                logger.info(f"📦 Curator: transitioned skill '{s_name}' to 'archived' (unused for {archive_days}+ days)")
            elif state == "active" and last_used < stale_cutoff:
                active_db.set_skill_lifecycle_state(s_name, "stale")
                updated_stale += 1
                logger.info(f"📚 Curator: transitioned skill '{s_name}' to 'stale' (unused for {stale_days}+ days)")
            else:
                active_count += 1

        # Write .curator_state file
        curator_state_file = os.path.join(active_skills_dir, ".curator_state")
        report = {
            "last_run_at": now,
            "active_skills": active_count,
            "pinned_skills": pinned_count,
            "stale_transitions": updated_stale,
            "archive_transitions": updated_archived,
            "total_tracked": len(ledger)
        }
        try:
            os.makedirs(active_skills_dir, exist_ok=True)
            with open(curator_state_file, "w", encoding="utf-8") as f:
                json.dump(report, f, indent=2)
        except Exception as e:
            logger.debug(f"Curator state write notice: {e}")

        return report


class LearningLoopManager:
    """
    Unified manager orchestrating trajectory detection, /learn skill synthesis,
    and background curation.
    """
    _instance: Optional['LearningLoopManager'] = None

    def __init__(self):
        self.state_db = state_db

    @classmethod
    def get_instance(cls) -> 'LearningLoopManager':
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def learn_and_save_skill(
        self,
        skill_name: str,
        skill_content: str,
        source_goal: Optional[str] = None,
        trajectory: Optional[List[Dict[str, Any]]] = None,
        skills_dir: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Validates, saves, and registers a newly synthesized skill into storage/skills/.
        """
        is_valid, msg, metadata = SkillValidator.validate(skill_content)
        if not is_valid:
            return {"status": "error", "message": f"Skill validation failed: {msg}"}

        target_name = metadata.get("name") or skill_name.strip().lower()
        active_skills_dir = skills_dir or SKILLS_DIR
        target_dir = os.path.join(active_skills_dir, target_name)
        os.makedirs(target_dir, exist_ok=True)
        skill_file = os.path.join(target_dir, "SKILL.md")

        with open(skill_file, "w", encoding="utf-8") as f:
            f.write(skill_content.strip() + "\n")

        # Record in StateDB ledger & provenance
        self.state_db.record_skill_usage(target_name, success=True, is_agent_created=True)
        self.state_db.record_skill_provenance(
            skill_name=target_name,
            source_goal=source_goal or "Synthesized via /learn",
            creator_agent="hermes_learning_loop",
            version=metadata.get("version", "0.1.0"),
            trajectory=trajectory
        )

        logger.info(f"✨ Successfully saved synthesized skill '{target_name}' at {skill_file}")
        return {
            "status": "success",
            "message": f"Skill '{target_name}' synthesized and saved successfully.",
            "name": target_name,
            "path": skill_file,
            "metadata": metadata
        }


learning_loop = LearningLoopManager.get_instance()
