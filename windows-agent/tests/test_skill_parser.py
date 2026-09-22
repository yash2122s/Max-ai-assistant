"""
Unit tests for MAX SkillParser in Python.
"""

import os
import pytest
from core.skills.skill_parser import SkillParser


FIXTURES_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "shared", "skills", "fixtures"))


def test_parse_valid_json_skill():
    json_path = os.path.join(FIXTURES_DIR, "valid_instagram_skill.json")
    with open(json_path, "r", encoding="utf-8") as f:
        content = f.read()

    res = SkillParser.parse(content)
    assert res.success is True, f"Failed parsing: {res.error_message}"
    assert res.skill is not None
    assert res.skill.skill_id == "com.max.automation.instagram.search_chat"
    assert res.skill.name == "Instagram Direct Message Search"
    assert len(res.skill.steps) == 3
    assert res.skill.steps[0].action == "app.open"
    assert res.skill.steps[1].depends_on == ["step_open_app"]


def test_parse_valid_markdown_frontmatter_skill():
    md_path = os.path.join(FIXTURES_DIR, "valid_instagram_skill.md")
    with open(md_path, "r", encoding="utf-8") as f:
        content = f.read()

    res = SkillParser.parse(content)
    assert res.success is True, f"Failed parsing: {res.error_message}"
    assert res.skill is not None
    assert res.skill.skill_id == "com.max.automation.instagram.search_chat"
    assert len(res.skill.steps) == 3
    assert "Instagram Direct Message Search Skill" in res.documentation_markdown


def test_parse_empty_fails():
    res = SkillParser.parse("")
    assert res.success is False
    assert "empty" in res.error_message.lower()


def test_parse_unsupported_format_fails():
    res = SkillParser.parse("This is just arbitrary text.")
    assert res.success is False
    assert "unsupported skill format" in res.error_message.lower()


def test_parse_oversized_content_fails():
    huge_text = "{" + "a" * (600 * 1024) + "}"
    res = SkillParser.parse(huge_text)
    assert res.success is False
    assert "exceeds maximum allowed size" in res.error_message.lower()
