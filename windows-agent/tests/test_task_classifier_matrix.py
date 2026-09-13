"""
Unit tests for TaskClassifier 5-Tier Matrix, Confidence Scoring, and Safety Guardrails.
"""

import pytest
from core.task_classifier import TaskClassifier, TaskTier


@pytest.fixture
def classifier():
    return TaskClassifier()


class TestTaskClassifierMatrix:

    def test_tier_1_direct_action_open_paint(self, classifier):
        res = classifier.classify("Open Paint")
        assert res.tier == TaskTier.TIER_1_DIRECT_ACTION.value
        assert res.target == "windows_agent"
        assert res.confidence >= 0.80
        assert res.requires_clarification is False

    def test_tier_1_direct_action_volume_and_battery(self, classifier):
        res_vol = classifier.classify("Set volume to 50%")
        assert res_vol.tier == TaskTier.TIER_1_DIRECT_ACTION.value
        assert res_vol.target == "windows_agent"

        res_bat = classifier.classify("What is my battery level?")
        assert res_bat.tier == TaskTier.TIER_1_DIRECT_ACTION.value

    def test_tier_2_windows_paint_drawing(self, classifier):
        res = classifier.classify("Draw a blue triangle in Paint")
        assert res.tier == TaskTier.TIER_2_PLATFORM_EXECUTION.value
        assert res.target == "windows_agent"
        assert res.task_type == "gui_action"
        assert res.confidence >= 0.90
        # Invariant: No Antigravity routing for GUI operations
        assert res.target != "antigravity"

    def test_tier_2_android_device_control(self, classifier):
        res = classifier.classify("Turn on flashlight on my phone")
        assert res.tier == TaskTier.TIER_2_PLATFORM_EXECUTION.value
        assert res.target == "android_agent"
        assert res.task_type == "android_action"

    def test_tier_3_max_autonomous_reasoning(self, classifier):
        res = classifier.classify("Analyze this information and plan a multi-step workflow")
        assert res.tier == TaskTier.TIER_3_REASONING.value
        assert res.target == "max_agent"
        assert res.task_type == "reasoning"

    def test_tier_4_antigravity_developer_task(self, classifier):
        res = classifier.classify("Fix the Paint brush click bug and run tests")
        assert res.tier == TaskTier.TIER_4_DEVELOPER.value
        assert res.target == "antigravity"
        assert res.task_type == "code_change"
        assert "WORKSPACE_WRITE" in res.suggested_permissions
        assert "TEST_EXECUTION" in res.suggested_permissions

    def test_tier_5_hybrid_developer_and_runtime(self, classifier):
        res = classifier.classify("Fix Paint automation and verify by actually drawing")
        assert res.tier == TaskTier.TIER_5_HYBRID.value
        assert res.target == "hybrid"
        assert res.task_type == "hybrid"
        assert "WORKTREE_CREATE" in res.suggested_permissions
        assert "GUI_CONTROL" in res.suggested_permissions

    def test_ambiguity_safe_fallback(self, classifier):
        res = classifier.classify("Fix Paint")
        assert res.is_ambiguous is True
        assert res.requires_clarification is True
        assert res.confidence < 0.80
        # Must not silently default to code destruction or unmanaged modification
        assert res.target != "antigravity"

    def test_destructive_operation_flagging(self, classifier):
        res = classifier.classify("Delete all temporary files in storage")
        assert res.is_destructive is True
        assert res.requires_confirmation is True

        res_sd = classifier.classify("Shutdown the system now")
        assert res_sd.is_destructive is True
        assert res_sd.requires_confirmation is True

    def test_false_positive_guard(self, classifier):
        # "Draw a circle" contains no code keywords, must NOT route to developer worker
        res = classifier.classify("Draw a red circle on the canvas")
        assert res.target == "windows_agent"
        assert res.target != "antigravity"
