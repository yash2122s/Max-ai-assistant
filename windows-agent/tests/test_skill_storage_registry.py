"""
Unit tests for SkillStorage and SkillRegistry in MAX Windows Agent.
"""

import os
import shutil
import tempfile
from pathlib import Path
import pytest

from core.skills.skill_definition import SkillDefinition, SkillStep
from core.skills.skill_parser import SkillParser
from core.skills.storage import SkillStorage
from core.skills.registry import SkillRegistry

FIXTURES_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "shared", "skills", "fixtures"))


@pytest.fixture
def temp_skills_dir():
    temp_dir = tempfile.mkdtemp(prefix="max_skills_test_")
    yield Path(temp_dir)
    shutil.rmtree(temp_dir, ignore_errors=True)


def test_storage_install_json_and_load(temp_skills_dir):
    storage = SkillStorage(root_dir=temp_skills_dir)
    json_path = os.path.join(FIXTURES_DIR, "instagram_search_chat.json")
    with open(json_path, "r", encoding="utf-8") as f:
        raw_json = f.read()

    parse_res = SkillParser.parse(raw_json)
    assert parse_res.success is True
    skill = parse_res.skill
    assert skill is not None

    # Install
    res = storage.install(skill, raw_json, target_platform="windows")
    assert res["success"] is True

    # Verify directory structure
    installed_skill_dir = temp_skills_dir / "installed" / storage.sanitize_skill_id(skill.skill_id)
    assert installed_skill_dir.exists()
    assert (installed_skill_dir / "canonical.json").exists()
    assert (installed_skill_dir / "manifest.json").exists()
    assert (installed_skill_dir / "source.json").exists()

    # Verify loading
    loaded = storage.load_skill(skill.skill_id)
    assert loaded is not None
    assert loaded.skill_id == skill.skill_id
    assert loaded.name == skill.name
    assert len(loaded.steps) == len(skill.steps)

    # Verify list and contains
    assert storage.contains(skill.skill_id)
    all_skills = storage.list_installed()
    assert len(all_skills) == 1
    assert all_skills[0].skill_id == skill.skill_id

    # Verify delete
    assert storage.delete(skill.skill_id) is True
    assert not storage.contains(skill.skill_id)
    assert len(storage.list_installed()) == 0


def test_storage_install_markdown(temp_skills_dir):
    storage = SkillStorage(root_dir=temp_skills_dir)
    md_path = os.path.join(FIXTURES_DIR, "instagram_search_chat.md")
    with open(md_path, "r", encoding="utf-8") as f:
        raw_md = f.read()

    parse_res = SkillParser.parse(raw_md)
    assert parse_res.success is True
    skill = parse_res.skill

    res = storage.install(skill, raw_md, target_platform="windows")
    assert res["success"] is True

    installed_skill_dir = temp_skills_dir / "installed" / storage.sanitize_skill_id(skill.skill_id)
    assert (installed_skill_dir / "source.md").exists()
    assert (installed_skill_dir / "canonical.json").exists()


def test_storage_quarantine_invalid_skill(temp_skills_dir):
    storage = SkillStorage(root_dir=temp_skills_dir)
    invalid_skill = SkillDefinition(
        skill_id="bad.skill",
        name="Bad Skill",
        description="Missing steps",
        steps=[]  # Empty steps fails validation
    )
    raw_content = '{"skill_id": "bad.skill", "name": "Bad Skill", "steps": []}'

    res = storage.install(invalid_skill, raw_content, target_platform="windows")
    assert res["success"] is False
    assert "Validation rejected" in res["error"]

    # Verify quarantined
    q_dirs = list((temp_skills_dir / "quarantine").iterdir())
    assert len(q_dirs) == 1
    assert "bad.skill" in q_dirs[0].name


def test_registry_installed_and_ephemeral(temp_skills_dir):
    storage = SkillStorage(root_dir=temp_skills_dir)
    registry = SkillRegistry(storage=storage)

    json_path = os.path.join(FIXTURES_DIR, "instagram_search_chat.json")
    with open(json_path, "r", encoding="utf-8") as f:
        raw_json = f.read()

    # 1. Install permanent
    import_res = registry.install(raw_json, target_platform="windows")
    assert import_res.success is True
    assert import_res.is_ephemeral is False
    skill_id = import_res.skill.skill_id

    assert registry.contains(skill_id) is True
    assert len(registry.list_installed()) == 1
    assert len(registry.list_ephemeral()) == 0

    # 2. Disable / Enable
    registry.disable(skill_id)
    assert registry.contains(skill_id) is False
    assert registry.get(skill_id) is None
    assert len(registry.list_installed(include_disabled=False)) == 0
    assert len(registry.list_installed(include_disabled=True)) == 1

    registry.enable(skill_id)
    assert registry.contains(skill_id) is True
    assert registry.get(skill_id) is not None

    # 3. Register ephemeral with distinct skill_id
    raw_ephemeral = """---
skill_id: "com.ephemeral.skill"
name: "Ephemeral Skill"
description: "Temporary prompt attached skill"
version: "1.0.0"
schema_version: "1.0.0"
supported_platforms: ["windows"]
allowed_capabilities: ["ui.click"]
steps:
  - step_id: "step_temp"
    action: "ui.click"
    target: "button"
---
# Ephemeral docs
"""

    eph_res = registry.register_ephemeral(raw_ephemeral, target_platform="windows")
    assert eph_res.success is True
    assert eph_res.is_ephemeral is True
    eph_id = eph_res.skill.skill_id

    assert registry.contains(eph_id) is True
    assert len(registry.list_ephemeral()) == 1

    # Ephemeral does NOT touch disk installed directory
    assert not (temp_skills_dir / "installed" / storage.sanitize_skill_id(eph_id)).exists()

    # Clear ephemeral
    registry.clear_ephemeral()
    assert len(registry.list_ephemeral()) == 0
    # Permanent skill still remains
    assert registry.contains(skill_id) is True
