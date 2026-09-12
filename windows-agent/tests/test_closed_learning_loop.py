"""
Unit and Integration Tests for MAX AI Closed Learning Loop & Memory Synthesis
Tests:
1. TrajectoryEvaluator qualification criteria
2. LearnPromptBuilder formatting and rules
3. SkillValidator frontmatter, description character cap, and threat scanner
4. StateDatabase L4 memory partitioning (User Profile + Environment Facts)
5. SkillUsageTracker and provenance recording
6. BackgroundCurator lifecycle transitions (active -> stale -> archived, pinned preservation)
7. SkillsEngine skill_learn and skill_manage tool execution
"""

import os
import shutil
import tempfile
import pytest
from typing import Dict, Any, List

from core.learning_loop import (
    TrajectoryEvaluator,
    LearnPromptBuilder,
    SkillValidator,
    BackgroundCurator,
    learning_loop,
)
from core.state_database import StateDatabase
from core.skills_engine import SkillsEngine, skill_learn, skill_manage, skills_list, skill_view


class TestClosedLearningLoop:

    @pytest.fixture(autouse=True)
    def setup_test_env(self):
        self.test_dir = tempfile.mkdtemp(prefix="max_learning_test_")
        self.db_path = os.path.join(self.test_dir, "test_state.db")
        self.skills_dir = os.path.join(self.test_dir, "skills")
        os.makedirs(self.skills_dir, exist_ok=True)

        self.db = StateDatabase(db_path=self.db_path)
        self.skills_eng = SkillsEngine(skills_dir=self.skills_dir, db=self.db)
        learning_loop.state_db = self.db

        yield

        shutil.rmtree(self.test_dir, ignore_errors=True)

    # ── 1. TrajectoryEvaluator Tests ──────────────────────────────────────────

    def test_01_trajectory_evaluator_qualifies_compound_workflow(self):
        steps = [
            {"tool": "read_file", "arguments": {"file_path": "test.txt"}, "result": "content"},
            {"tool": "web_search", "arguments": {"query": "python"}, "result": ["link1", "link2"]},
            {"tool": "execute_device_action", "arguments": {"target": "Phone 1", "action": "SET_VOLUME"}, "result": {"success": True}}
        ]
        should_learn, reason = TrajectoryEvaluator.is_worth_learning(
            goal="Analyze data and notify Phone 1 with volume adjustment",
            steps=steps,
            final_answer="Task completed successfully.",
            status="success"
        )
        assert should_learn is True
        assert "Compound successful workflow" in reason

    def test_02_trajectory_evaluator_rejects_single_step_or_failure(self):
        steps = [
            {"tool": "read_file", "arguments": {"file_path": "test.txt"}, "result": "content"}
        ]
        should_learn, reason = TrajectoryEvaluator.is_worth_learning(
            goal="Read a single file",
            steps=steps,
            final_answer="Done",
            status="success"
        )
        assert should_learn is False
        assert "below threshold" in reason

        # Test failure rejection
        should_learn_fail, _ = TrajectoryEvaluator.is_worth_learning(
            goal="Failed task",
            steps=steps * 3,
            final_answer="Error",
            status="failed"
        )
        assert should_learn_fail is False

    # ── 2. LearnPromptBuilder Tests ───────────────────────────────────────────

    def test_03_learn_prompt_builder_includes_agentskills_rules(self):
        prompt = LearnPromptBuilder.build_synthesis_prompt(
            instruction="Create a fast audio transcriber workflow",
            trajectory=[{"tool": "web_search", "result": "ok"}, {"tool": "read_file", "result": "ok"}]
        )
        assert "Agentskills.io" in prompt
        assert "<= 60 characters" in prompt
        assert "## When to Use" in prompt
        assert "## Procedure" in prompt
        assert "## Verification" in prompt

    # ── 3. SkillValidator & SecurityScanner Tests ─────────────────────────────

    def test_04_skill_validator_accepts_valid_skill(self):
        valid_content = """---
name: deploy-fastapi-service
description: Deploy FastAPI services to local Windows port.
version: 0.1.0
author: Hermes
platforms: [windows]
---

# Deploy FastAPI Service Skill

## When to Use
- When deploying FastAPI servers locally.

## Prerequisites
- Python 3.10+ installed.

## How to Run
Invoke via `execute_code`.

## Quick Reference
- Start server: uvicorn main:app --port 8000

## Procedure
1. Create virtualenv.
2. Run uvicorn server.

## Pitfalls
- Port 8000 might be in use.

## Verification
`curl http://127.0.0.1:8000/health`
"""
        is_valid, msg, meta = SkillValidator.validate(valid_content)
        assert is_valid is True
        assert meta.get("name") == "deploy-fastapi-service"
        assert len(meta.get("description", "")) <= 60

    def test_05_skill_validator_rejects_overlong_description(self):
        invalid_content = """---
name: overlong-desc-skill
description: This is an unnecessarily long description that exceeds sixty characters in length and violates the rule.
version: 0.1.0
---

# Title
## When to Use
- test
## Procedure
- step 1
## Verification
- verify
"""
        is_valid, msg, _ = SkillValidator.validate(invalid_content)
        assert is_valid is False
        assert "exceeds maximum allowed 60 characters" in msg

    def test_06_skill_validator_rejects_dangerous_threat_patterns(self):
        threat_content = """---
name: dangerous-skill
description: Format hard drive safely.
version: 0.1.0
---

# Title
## When to Use
- test
## Procedure
1. Run format c: /y
## Verification
- done
"""
        is_valid, msg, _ = SkillValidator.validate(threat_content)
        assert is_valid is False
        assert "prohibited dangerous pattern" in msg

    # ── 4. StateDatabase L4 Memory Partitioning Tests ────────────────────────

    def test_07_honcho_memory_partitioning(self):
        self.db.save_memory(key="user_name", value="Yaswanth", category="identity")
        self.db.save_memory(key="favorite_food", value="Biryani", category="user_profile")
        self.db.save_memory(key="device_phone", value="Redmi K20 Pro", category="device")
        self.db.save_memory(key="workstation_os", value="Windows 11 Pro", category="environment")

        user_summary = self.db.get_user_profile_summary()
        env_summary = self.db.get_environment_summary()
        all_summary = self.db.get_summary_text()

        assert "Yaswanth" in user_summary
        assert "Biryani" in user_summary
        assert "Redmi K20 Pro" in env_summary
        assert "Windows 11 Pro" in env_summary

        assert "<USER_PROFILE>" in all_summary
        assert "<ENVIRONMENT_FACTS>" in all_summary

    # ── 5. Skill Usage & Provenance Tracking Tests ────────────────────────────

    def test_08_skill_usage_and_provenance_recording(self):
        self.db.record_skill_usage("android-ecosystem", success=True)
        self.db.record_skill_usage("android-ecosystem", success=True)
        self.db.record_skill_usage("android-ecosystem", success=False)

        stats = self.db.get_skill_stats("android-ecosystem")
        assert stats is not None
        assert stats["usage_count"] == 3
        assert stats["failure_count"] == 1
        assert stats["lifecycle_state"] == "active"

        self.db.record_skill_provenance(
            skill_name="android-ecosystem",
            source_goal="Orchestrate companion devices",
            creator_agent="hermes",
            version="1.0.0"
        )
        ledger = self.db.list_skill_ledger()
        assert len(ledger) >= 1

    # ── 6. BackgroundCurator Lifecycle Tests ──────────────────────────────────

    def test_09_curator_lifecycle_transitions(self):
        # 1. Active skill
        self.db.record_skill_usage("fresh-skill", success=True)

        # 2. Pinned skill
        self.db.record_skill_usage("pinned-skill", success=True)
        self.db.set_skill_lifecycle_state("pinned-skill", "pinned")

        # 3. Simulate stale skill by manual update
        conn = self.db._get_connection()
        old_time = 100000.0  # ancient timestamp
        with conn:
            conn.execute("""
                INSERT INTO skill_ledger (name, usage_count, failure_count, last_used_at, lifecycle_state, is_agent_created, created_at, updated_at)
                VALUES ('abandoned-skill', 1, 0, ?, 'active', 1, ?, ?)
            """, (old_time, old_time, old_time))

        report = BackgroundCurator.run_curation_pass(db=self.db, skills_dir=self.skills_dir, stale_days=30, archive_days=90)
        assert report["pinned_skills"] >= 1
        assert report["archive_transitions"] >= 1

        abandoned_stat = self.db.get_skill_stats("abandoned-skill")
        assert abandoned_stat["lifecycle_state"] == "archived"

        pinned_stat = self.db.get_skill_stats("pinned-skill")
        assert pinned_stat["lifecycle_state"] == "pinned"

    # ── 7. SkillsEngine Tool Execution Tests ──────────────────────────────────

    def test_10_skills_engine_learn_and_manage_tools(self):
        content = """---
name: custom-media-sorter
description: Sort media files into dated folders automatically.
version: 0.1.0
author: Hermes
---

# Custom Media Sorter

## When to Use
- When organizing downloads.

## Prerequisites
- Python installed.

## How to Run
Invoke `execute_code`.

## Quick Reference
- python sort.py

## Procedure
1. Scan files.
2. Move to dated folders.

## Pitfalls
- Read-only files fail.

## Verification
`ls sorted/`
"""
        res = self.skills_eng.learn_skill(instruction="custom-media-sorter", content=content)
        assert res["status"] == "success"
        assert res["name"] == "custom-media-sorter"

        # Verify it appears in skills_list
        lst = self.skills_eng.list_skills()
        assert any(s["name"] == "custom-media-sorter" for s in lst["skills"])

        # Test pin via manage_skill
        pin_res = self.skills_eng.manage_skill(action="pin", name="custom-media-sorter")
        assert pin_res["status"] == "success"

        stat = self.db.get_skill_stats("custom-media-sorter")
        assert stat["lifecycle_state"] == "pinned"
