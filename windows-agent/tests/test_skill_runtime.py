"""
Unit tests for SkillResolver and SkillRuntime in MAX Windows Agent.
"""

import os
import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock

from core.skills.skill_definition import (
    SkillDefinition, SkillInput, SkillStep, SkillIntents,
    StepRetryPolicy, StepVerification, FailurePolicy
)
from core.skills.registry import SkillRegistry
from core.skills.storage import SkillStorage
from core.skills.runtime.resolver import SkillResolver, SkillResolutionMatch
from core.skills.runtime.runtime import SkillRuntime, SkillExecutionResult
from core.skills.runtime.step_executor import SkillStepExecutor, StepExecutionResult
from core.skills.runtime.state_verifier import SkillStateVerifier, StepVerificationResult
from core.skills.runtime.recovery import SkillRecovery, RecoveryResult
from core.skills.runtime.adaptive_grounder import GroundedTarget
from core.skills.skill_parser import SkillParser

FIXTURES_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "shared", "skills", "fixtures"))


@pytest.fixture
def instagram_skill():
    json_path = os.path.join(FIXTURES_DIR, "instagram_search_chat.json")
    with open(json_path, "r", encoding="utf-8") as f:
        raw_json = f.read()
    res = SkillParser.parse(raw_json)
    return res.skill


def test_skill_resolver_exact_phrase_and_keywords(instagram_skill, tmp_path):
    storage = SkillStorage(root_dir=tmp_path)
    registry = SkillRegistry(storage=storage)
    # Explicitly add skill into registry
    registry._installed_skills[instagram_skill.skill_id] = instagram_skill

    resolver = SkillResolver(registry=registry)

    # 1. Exact phrase resolution
    match1 = resolver.resolve_prompt("open instagram chat")
    assert match1.matched is True
    assert match1.skill.skill_id == instagram_skill.skill_id
    assert match1.match_type == "EXACT_PHRASE"

    # 2. Trigger keywords + slot extraction (Telugu prompt)
    match2 = resolver.resolve_prompt("Instagram lo yaswanth.1432 chat open cheyyi")
    assert match2.matched is True
    assert match2.skill.skill_id == instagram_skill.skill_id
    assert match2.extracted_inputs.get("target_user") == "yaswanth.1432"

    # 3. Unrelated prompt
    match3 = resolver.resolve_prompt("Play some jazz music on spotify")
    assert match3.matched is False


def test_skill_resolver_embedded_frontmatter(tmp_path):
    storage = SkillStorage(root_dir=tmp_path)
    registry = SkillRegistry(storage=storage)
    resolver = SkillResolver(registry=registry)

    prompt = """Please execute this custom skill for user alex.dev:
---
skill_id: "com.custom.automation"
name: "Custom Search"
description: "Custom user lookup"
version: "1.0.0"
schema_version: "1.0.0"
supported_platforms: ["windows"]
allowed_capabilities: ["ui.type_text"]
intents:
  exact_phrases: ["find user"]
inputs:
  - name: "target_user"
    type: "string"
    required: true
steps:
  - step_id: "step_search"
    action: "ui.type_text"
    target: "Search box"
    arguments:
      text: "{{target_user}}"
---
"""
    match = resolver.resolve_prompt(prompt)
    assert match.matched is True
    assert match.skill is not None
    assert match.skill.skill_id == "com.custom.automation"
    assert match.match_type == "EXPLICIT_EMBEDDED"
    assert match.extracted_inputs.get("target_user") == "alex.dev"


@pytest.mark.asyncio
async def test_skill_runtime_end_to_end_success(instagram_skill):
    # Mock StepExecutor
    mock_executor = MagicMock(spec=SkillStepExecutor)
    mock_executor.execute_step = AsyncMock(side_effect=lambda step: StepExecutionResult(
        success=True,
        step_id=step.step_id,
        message=f"Executed {step.canonical_action}",
        grounded_target=GroundedTarget(success=True)
    ))

    # Mock Verifier
    mock_verifier = MagicMock(spec=SkillStateVerifier)
    mock_verifier.verify_step = AsyncMock(return_value=StepVerificationResult(
        verified=True,
        message="Verified state",
        elapsed_ms=50
    ))

    # Mock Recovery
    mock_recovery = MagicMock(spec=SkillRecovery)

    runtime = SkillRuntime(
        step_executor=mock_executor,
        verifier=mock_verifier,
        recovery=mock_recovery
    )

    result: SkillExecutionResult = await runtime.execute_skill(
        skill=instagram_skill,
        provided_inputs={"target_user": "yaswanth.1432"}
    )

    assert result.success is True
    assert result.skill_id == instagram_skill.skill_id
    assert result.total_steps == len(instagram_skill.steps)
    assert len(result.completed_steps) == len(instagram_skill.steps)
    assert result.completed_steps == [s.step_id for s in instagram_skill.steps]
    assert mock_executor.execute_step.call_count == len(instagram_skill.steps)
    assert mock_verifier.verify_step.call_count == len(instagram_skill.steps)


@pytest.mark.asyncio
async def test_skill_runtime_step_failure_and_abort(instagram_skill):
    # Mock StepExecutor that fails at step 3
    mock_executor = MagicMock(spec=SkillStepExecutor)

    async def _execute_step(step):
        if step.step_id == "step_search_user":
            return StepExecutionResult(success=False, step_id=step.step_id, message="Target search box not found")
        return StepExecutionResult(success=True, step_id=step.step_id, message="OK")

    mock_executor.execute_step = AsyncMock(side_effect=_execute_step)

    mock_verifier = MagicMock(spec=SkillStateVerifier)
    mock_verifier.verify_step = AsyncMock(return_value=StepVerificationResult(verified=True, message="OK"))

    mock_recovery = MagicMock(spec=SkillRecovery)
    mock_recovery.attempt_recovery = AsyncMock(return_value=RecoveryResult(success=False, action_taken="NONE"))

    runtime = SkillRuntime(
        step_executor=mock_executor,
        verifier=mock_verifier,
        recovery=mock_recovery
    )

    result: SkillExecutionResult = await runtime.execute_skill(
        skill=instagram_skill,
        provided_inputs={"target_user": "yaswanth.1432"}
    )

    assert result.success is False
    assert result.failed_step_id == "step_search_user"
    assert "step_open_app" in result.completed_steps
    assert "Skill aborted at step 'step_search_user'" in result.message
