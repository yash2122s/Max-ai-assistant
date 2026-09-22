"""
Unit tests for SkillInputBinder and SkillCompiler in MAX Windows Agent.
"""

import os
import pytest

from core.skills.skill_definition import SkillDefinition, SkillInput, SkillStep, PlatformOverrides, StepVerification
from core.skills.input_binder import SkillInputBinder
from core.skills.compiler import SkillCompiler, BoundSkillPlan
from core.skills.skill_parser import SkillParser

FIXTURES_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "shared", "skills", "fixtures"))


def test_input_binder_binds_valid_parameters():
    skill = SkillDefinition(
        skill_id="test.skill",
        name="Test",
        description="Test",
        inputs=[
            SkillInput(name="target_user", type="string", required=True),
            SkillInput(name="count", type="integer", required=False, default_value=5),
            SkillInput(name="is_admin", type="boolean", required=False, default_value=False)
        ]
    )

    # Valid binding
    res = SkillInputBinder.bind_inputs(skill, {"target_user": "yaswanth.1432", "count": 10})
    assert res.success is True
    assert res.bound_values["target_user"] == "yaswanth.1432"
    assert res.bound_values["count"] == 10
    assert res.bound_values["is_admin"] is False


def test_input_binder_missing_required_fails():
    skill = SkillDefinition(
        skill_id="test.skill",
        name="Test",
        description="Test",
        inputs=[
            SkillInput(name="target_user", type="string", required=True)
        ]
    )

    res = SkillInputBinder.bind_inputs(skill, {})
    assert res.success is False
    assert "Missing mandatory input parameter" in res.error_message


def test_input_binder_string_and_args_interpolation():
    bound = {"target_user": "yaswanth.1432", "msg": "Hello World"}

    template = "Searching for {{target_user}} with ${inputs.msg}"
    interpolated = SkillInputBinder.interpolate_string(template, bound)
    assert interpolated == "Searching for yaswanth.1432 with Hello World"

    args = {
        "text": "{{target_user}}",
        "nested": {"key": "Hello {{target_user}}"},
        "list": ["{{target_user}}", 123]
    }
    interpolated_args = SkillInputBinder.interpolate_arguments(args, bound)
    assert interpolated_args["text"] == "yaswanth.1432"
    assert interpolated_args["nested"]["key"] == "Hello yaswanth.1432"
    assert interpolated_args["list"][0] == "yaswanth.1432"
    assert interpolated_args["list"][1] == 123


def test_compiler_compiles_instagram_skill_for_windows():
    json_path = os.path.join(FIXTURES_DIR, "instagram_search_chat.json")
    with open(json_path, "r", encoding="utf-8") as f:
        raw_json = f.read()

    parse_res = SkillParser.parse(raw_json)
    assert parse_res.success is True
    skill = parse_res.skill

    bound_inputs = {"target_user": "yaswanth.1432"}
    plan: BoundSkillPlan = SkillCompiler.compile(skill, bound_inputs, target_platform="windows")

    assert plan.skill_id == skill.skill_id
    assert plan.target_platform == "windows"
    assert len(plan.steps) == 6
    assert plan.execution_order == [
        "step_open_app",
        "step_open_search",
        "step_search_user",
        "step_open_profile",
        "step_click_message",
        "step_verify_chat"
    ]

    # Step 1: app.open
    step0 = plan.steps[0]
    assert step0.canonical_action == "app.open"
    assert step0.native_action_name == "open_application"
    # Windows platform override check
    assert step0.arguments.get("process_name") == "Instagram.exe"

    # Step 3: ui.type_text
    step2 = plan.steps[2]
    assert step2.canonical_action == "ui.type_text"
    assert step2.arguments.get("text") == "yaswanth.1432"
    assert step2.depends_on == ["step_open_search"]

    # Step 5: ui.click message button
    step4 = plan.steps[4]
    assert step4.canonical_action == "ui.click"
    assert step4.semantic_target == "profile_message_button"
    assert step4.arguments.get("role") == "button"
    assert step4.verification is not None
    assert step4.verification.type == "ELEMENT_VISIBLE"
    assert step4.verification.expected_value == "conversation_composer"


def test_compiler_topological_order():
    skill = SkillDefinition(
        skill_id="test.dag",
        name="DAG Test",
        description="Test topological ordering",
        steps=[
            SkillStep(step_id="step_c", action="ui.click", target="C", depends_on=["step_b"]),
            SkillStep(step_id="step_a", action="app.open", target="A", depends_on=[]),
            SkillStep(step_id="step_b", action="ui.click", target="B", depends_on=["step_a"])
        ]
    )

    plan = SkillCompiler.compile(skill, {}, target_platform="windows")
    assert plan.execution_order == ["step_a", "step_b", "step_c"]
