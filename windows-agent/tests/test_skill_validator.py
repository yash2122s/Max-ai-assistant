"""
Unit tests for SkillValidator with Kahn's DAG Algorithm (Python).
"""

import os
import pytest
from core.skills.skill_parser import SkillParser
from core.skills.skill_validator import SkillValidator
from core.skills.skill_definition import SkillDefinition, SkillStep


FIXTURES_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "shared", "skills", "fixtures"))


def test_valid_instagram_skill_passes_windows():
    json_path = os.path.join(FIXTURES_DIR, "valid_instagram_skill.json")
    with open(json_path, "r", encoding="utf-8") as f:
        content = f.read()

    parse_res = SkillParser.parse(content)
    assert parse_res.success is True

    val_res = SkillValidator.validate(parse_res.skill, target_platform="windows")
    assert val_res.is_valid is True, f"Validation failed: {val_res.error_summary()}"
    assert len(val_res.errors) == 0


def test_valid_instagram_skill_passes_android():
    json_path = os.path.join(FIXTURES_DIR, "valid_instagram_skill.json")
    with open(json_path, "r", encoding="utf-8") as f:
        content = f.read()

    parse_res = SkillParser.parse(content)
    assert parse_res.success is True

    val_res = SkillValidator.validate(parse_res.skill, target_platform="android")
    assert val_res.is_valid is True, f"Validation failed: {val_res.error_summary()}"


def test_cyclic_skill_is_rejected_by_kahn():
    json_path = os.path.join(FIXTURES_DIR, "invalid_cyclic_skill.json")
    with open(json_path, "r", encoding="utf-8") as f:
        content = f.read()

    parse_res = SkillParser.parse(content)
    assert parse_res.success is True

    val_res = SkillValidator.validate(parse_res.skill, target_platform="android")
    assert val_res.is_valid is False
    assert any("Circular dependency detected" in err for err in val_res.errors)


def test_duplicate_step_id_rejected():
    skill = SkillDefinition(
        skill_id="com.max.test.duplicate",
        name="Duplicate Test",
        description="Test duplicate step IDs",
        allowed_capabilities=["ui.click"],
        steps=[
            SkillStep(step_id="step_1", action="ui.click", target="Button 1", depends_on=[]),
            SkillStep(step_id="step_1", action="ui.click", target="Button 2", depends_on=[]),
        ]
    )

    val_res = SkillValidator.validate(skill, target_platform="android")
    assert val_res.is_valid is False
    assert any("Duplicate step_id" in err for err in val_res.errors)


def test_self_dependency_rejected():
    skill = SkillDefinition(
        skill_id="com.max.test.self_dep",
        name="Self Dep Test",
        description="Test self dependency",
        allowed_capabilities=["ui.click"],
        steps=[
            SkillStep(step_id="step_1", action="ui.click", target="Button 1", depends_on=["step_1"])
        ]
    )

    val_res = SkillValidator.validate(skill, target_platform="android")
    assert val_res.is_valid is False
    assert any("self-dependency" in err for err in val_res.errors)


def test_unknown_dependency_rejected():
    skill = SkillDefinition(
        skill_id="com.max.test.unknown_dep",
        name="Unknown Dep Test",
        description="Test unknown dependency",
        allowed_capabilities=["ui.click"],
        steps=[
            SkillStep(step_id="step_1", action="ui.click", target="Button 1", depends_on=["non_existent"])
        ]
    )

    val_res = SkillValidator.validate(skill, target_platform="android")
    assert val_res.is_valid is False
    assert any("unknown dependency" in err for err in val_res.errors)


def test_unregistered_action_rejected():
    json_path = os.path.join(FIXTURES_DIR, "invalid_unregistered_action.json")
    with open(json_path, "r", encoding="utf-8") as f:
        content = f.read()

    parse_res = SkillParser.parse(content)
    assert parse_res.success is True

    val_res = SkillValidator.validate(parse_res.skill, target_platform="android")
    assert val_res.is_valid is False
    assert any("unrecognized" in err.lower() for err in val_res.errors)


def test_tampered_checksum_rejected():
    json_path = os.path.join(FIXTURES_DIR, "invalid_checksum_tampered.json")
    with open(json_path, "r", encoding="utf-8") as f:
        content = f.read()

    parse_res = SkillParser.parse(content)
    assert parse_res.success is True

    val_res = SkillValidator.validate(parse_res.skill, target_platform="android")
    assert val_res.is_valid is False
    assert any("Checksum mismatch" in err for err in val_res.errors)
