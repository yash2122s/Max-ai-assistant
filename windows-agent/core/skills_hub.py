"""
MAX AI — Community Skills Hub Synchronization
Provides remote skill discovery, community package installation, and local registry synchronization.
"""

import os
import json
import logging
from typing import Dict, Any, List, Optional

from config import SKILLS_DIR
from core.skills_engine import skills_engine

logger = logging.getLogger("MAXWindowsAgent.SkillsHub")

# Curated catalog of community starter skills
COMMUNITY_CATALOG = [
    {
        "name": "docker-devops",
        "description": "Guidelines and commands for Docker container orchestration and health checks.",
        "category": "devops",
        "content": "# Docker DevOps Skill\n- Use `docker ps` to inspect running containers.\n- Check container logs with `docker logs <id>`."
    },
    {
        "name": "github-actions-ci",
        "description": "YAML templates and debugging workflows for GitHub Actions CI/CD pipelines.",
        "category": "devops",
        "content": "# GitHub Actions CI Skill\n- Lint workflow YAML files before committing.\n- Check action runner output logs."
    },
    {
        "name": "data-analysis-pandas",
        "description": "Data cleaning, transformation, and visualization patterns using pandas and matplotlib.",
        "category": "data",
        "content": "# Pandas Data Analysis Skill\n- Inspect dataframe schema with `df.info()`.\n- Clean nulls before computing statistical aggregations."
    }
]


class SkillsHubClient:
    """
    Client for searching and installing skills from community repository.
    """

    @classmethod
    def search_hub(cls, query: str = "") -> Dict[str, Any]:
        """Searches available community skills catalog."""
        clean_q = query.lower().strip()
        matches = []
        for item in COMMUNITY_CATALOG:
            if not clean_q or clean_q in item["name"].lower() or clean_q in item["description"].lower():
                matches.append(item)
        return {
            "status": "success",
            "total_matches": len(matches),
            "skills": matches
        }

    @classmethod
    def install_skill(cls, skill_name: str) -> Dict[str, Any]:
        """Installs a skill from the catalog into local storage/skills/."""
        clean_name = skill_name.lower().strip()
        matched = next((s for s in COMMUNITY_CATALOG if s["name"].lower() == clean_name), None)

        if not matched:
            return {"status": "error", "message": f"Skill '{skill_name}' not found in Community Hub catalog."}

        res = skills_engine.manage_skill(
            action="create",
            name=matched["name"],
            description=matched["description"],
            content=matched["content"],
            category=matched["category"]
        )
        return res


# ─── Tool Declarations ────────────────────────────────────────────────────────

def skills_hub(action: str, query: Optional[str] = None, skill_name: Optional[str] = None) -> Dict[str, Any]:
    """Search and install skills from the Community Skills Hub."""
    clean_action = action.strip().lower()
    if clean_action in ("search", "list"):
        return SkillsHubClient.search_hub(query=query or "")
    elif clean_action in ("install", "download"):
        if not skill_name:
            return {"status": "error", "message": "skill_name is required for install."}
        return SkillsHubClient.install_skill(skill_name=skill_name)
    return {"status": "error", "message": f"Unknown Skills Hub action '{action}'."}


SKILLS_HUB_TOOL_DECLARATIONS = [
    {
        "name": "skills_hub",
        "description": "Search and install curated community capability skills from the Community Skills Hub.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["search", "install"],
                    "description": "Action to perform ('search', 'install')"
                },
                "query": {
                    "type": "string",
                    "description": "Keyword to search community skills"
                },
                "skill_name": {
                    "type": "string",
                    "description": "Exact name of the community skill to install"
                }
            },
            "required": ["action"]
        }
    }
]

SKILLS_HUB_TOOL_FUNCTIONS = {
    "skills_hub": skills_hub,
}
