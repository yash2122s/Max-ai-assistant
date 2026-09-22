"""
MAX AI — Natural Language Skill Resolver for Windows Agent
Resolves user prompts to installed/ephemeral skills and extracts dynamic slot arguments.
"""

import re
import logging
from dataclasses import dataclass, field
from typing import Dict, Any, Optional, List

from core.skills.skill_definition import SkillDefinition
from core.skills.registry import SkillRegistry

logger = logging.getLogger("MAX.Skills.Resolver")


@dataclass
class SkillResolutionMatch:
    matched: bool
    skill: Optional[SkillDefinition] = None
    extracted_inputs: Dict[str, Any] = field(default_factory=dict)
    confidence: float = 0.0
    match_type: str = "NONE"
    reason: str = ""


class SkillResolver:
    """Matches user prompts to available skills and extracts slot arguments."""

    def __init__(self, registry: Optional[SkillRegistry] = None):
        self.registry = registry or SkillRegistry()

    def resolve_prompt(self, prompt: str) -> SkillResolutionMatch:
        clean_prompt = prompt.strip()
        if not clean_prompt:
            return SkillResolutionMatch(False, reason="Prompt is empty")

        # 1. Explicit Embedded / Attached Skill Check
        yaml_idx = clean_prompt.find("---")
        if yaml_idx != -1 and ("skill_id:" in clean_prompt or "schema_version:" in clean_prompt):
            skill_block = clean_prompt[yaml_idx:].strip()
            import_res = self.registry.register_ephemeral(skill_block, target_platform="windows")
            if import_res.success and import_res.skill:
                extracted = self.extract_inputs_from_prompt(clean_prompt, import_res.skill)
                return SkillResolutionMatch(
                    matched=True,
                    skill=import_res.skill,
                    extracted_inputs=extracted,
                    confidence=1.0,
                    match_type="EXPLICIT_EMBEDDED",
                    reason="Parsed explicit ephemeral skill from prompt"
                )

        available = self.registry.list_installed() + self.registry.list_ephemeral()
        norm_prompt = clean_prompt.lower()

        best_match: Optional[SkillDefinition] = None
        best_score = 0.0
        best_match_type = "NONE"
        best_extracted: Dict[str, Any] = {}

        for skill in available:
            # Tier 1: Exact Phrase Match
            for phrase in skill.intents.exact_phrases:
                c_phrase = phrase.strip().lower()
                if norm_prompt == c_phrase or norm_prompt.startswith(f"{c_phrase} ") or c_phrase in norm_prompt:
                    score = 0.95
                    if score > best_score:
                        best_score = score
                        best_match = skill
                        best_match_type = "EXACT_PHRASE"
                        best_extracted = self.extract_inputs_from_prompt(clean_prompt, skill)

            # Tier 2: Trigger Keywords
            keywords = [k.strip().lower() for k in skill.intents.trigger_keywords if k]
            if keywords:
                matched_cnt = sum(1 for k in keywords if k in norm_prompt)
                if matched_cnt >= 2 or (len(keywords) == 1 and matched_cnt == 1):
                    score = 0.80 + (matched_cnt / len(keywords) * 0.10)
                    if score > best_score:
                        best_score = score
                        best_match = skill
                        best_match_type = "TRIGGER_KEYWORDS"
                        best_extracted = self.extract_inputs_from_prompt(clean_prompt, skill)

        if best_match and best_score >= 0.75:
            logger.info(f"Resolved prompt '{prompt}' -> skill '{best_match.skill_id}' ({best_match_type})")
            return SkillResolutionMatch(
                matched=True,
                skill=best_match,
                extracted_inputs=best_extracted,
                confidence=best_score,
                match_type=best_match_type,
                reason=f"Matched skill '{best_match.name}' via {best_match_type}"
            )

        return SkillResolutionMatch(False, reason="No matching skill found for prompt")

    def extract_inputs_from_prompt(self, prompt: str, skill: SkillDefinition) -> Dict[str, Any]:
        extracted = {}
        for inp in skill.inputs:
            name_lower = inp.name.lower()
            if any(term in name_lower for term in ("user", "contact", "account", "name")):
                username = self._extract_target_user(prompt)
                if username:
                    extracted[inp.name] = username
            elif any(term in name_lower for term in ("message", "text")):
                msg = self._extract_message(prompt)
                if msg:
                    extracted[inp.name] = msg
        return extracted

    @staticmethod
    def _extract_target_user(prompt: str) -> str:
        regexes = [
            re.compile(r"(?:for\s+user|user|contact|for|with|to)\s+([a-zA-Z0-9_.]+)", re.IGNORECASE),
            re.compile(r"(?:lo|ki|ku)\s+([a-zA-Z0-9_.]+)\s+(?:chat|message|dm)", re.IGNORECASE),
            re.compile(r"(?:instagram|insta)\s+(?:lo\s+)?([a-zA-Z0-9_.]+)\s+(?:chat|dm|profile)", re.IGNORECASE)
        ]
        for rgx in regexes:
            m = rgx.search(prompt)
            if m:
                cand = m.group(1).strip()
                if cand.lower() not in ("chat", "message", "dm"):
                    return cand

        tokens = prompt.split()
        for tok in tokens:
            c = tok.strip(",.!?\"':")
            if "." in c or (any(ch.isdigit() for ch in c) and any(ch.isalpha() for ch in c)):
                return c
        return ""

    @staticmethod
    def _extract_message(prompt: str) -> str:
        m = re.search(r'(?:saying|says|text|message)\s+["\']?([^"\']+)["\']?', prompt, re.IGNORECASE)
        return m.group(1).strip() if m else ""
