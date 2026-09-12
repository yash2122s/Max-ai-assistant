"""
MAX AI + Hermes Unified Agent Integration Smoke Tests
Validates all Hermes capabilities running in the unified MAX environment:
1. Feature flags & configuration
2. SQLite WAL state database & FTS5 full-text search
3. Progressive Skills Engine (3-tier disclosure)
4. Unified Diff Patching Engine (AST validated)
5. Persistent Cron Scheduler
6. Programmatic Tool Calling (PTC) Sandbox
7. Subagent Task Delegation
8. MCP Client background loop
9. UnifiedToolRegistry auto-registration & alias resolution
"""

import os
import time
import pytest
from pathlib import Path

from config import (
    ENABLE_HERMES,
    ENABLE_HERMES_SKILLS,
    ENABLE_HERMES_MCP,
    ENABLE_HERMES_MEMORY,
    ENABLE_HERMES_SUBAGENTS,
    ENABLE_HERMES_SCHEDULER,
    ENABLE_HERMES_VOICE,
    PROJECT_ROOT
)


class TestHermesIntegrationSmoke:

    def test_01_feature_flags_and_voice_sovereignty(self):
        """Validates that Hermes is enabled and MAX Gemini Live remains exclusive voice interface."""
        assert ENABLE_HERMES is True
        assert ENABLE_HERMES_SKILLS is True
        assert ENABLE_HERMES_MCP is True
        assert ENABLE_HERMES_MEMORY is True
        assert ENABLE_HERMES_SUBAGENTS is True
        assert ENABLE_HERMES_SCHEDULER is True
        assert ENABLE_HERMES_VOICE is False  # Invariant: MAX Gemini Live is exclusive voice engine

    def test_02_sqlite_state_database_and_fts5_memory(self, tmp_path):
        """Tests SQLite WAL state database with FTS5 search, memory persistence, and audit logging."""
        from core.state_database import StateDatabase

        db_file = str(tmp_path / "test_state.db")
        db = StateDatabase(db_file)

        # 1. Save memories
        res1 = db.save_memory(key="creator_name", value="Yaswanth")
        assert res1["status"] == "success"

        res2 = db.save_memory(key="favorite_device", value="Redmi K20 Pro Phone 1")
        assert res2["status"] == "success"

        # 2. Recall with FTS5
        search_res = db.recall_memory("Yaswanth")
        assert search_res["status"] == "success"
        assert "creator_name" in search_res["results"]
        assert search_res["results"]["creator_name"] == "Yaswanth"

        search_res2 = db.recall_memory("Redmi")
        assert search_res2["status"] == "success"
        assert "favorite_device" in search_res2["results"]

        # 3. Summary text for prompt injection
        summary = db.get_summary_text()
        assert "creator_name: Yaswanth" in summary
        assert "favorite_device: Redmi K20 Pro Phone 1" in summary

        # 4. Audit logging
        db.log_audit(
            agent="hermes",
            tool="test_tool",
            arguments={"query": "test"},
            status="success",
            duration_ms=12
        )
        conn = db._get_connection()
        cur = conn.execute("SELECT COUNT(*) as count FROM audit_logs WHERE agent = 'hermes'")
        assert cur.fetchone()["count"] >= 1

    def test_03_progressive_skills_engine(self, tmp_path):
        """Tests 3-tier progressive disclosure skills engine."""
        from core.skills_engine import SkillsEngine

        skills_dir = str(tmp_path / "skills")
        engine = SkillsEngine(skills_dir)

        # 1. Tier 1: List skills metadata
        list_res = engine.list_skills()
        assert list_res["status"] == "success"
        assert list_res["count"] >= 3  # Default seeded skills

        skill_names = [s["name"] for s in list_res["skills"]]
        assert "windows-automation" in skill_names
        assert "android-ecosystem" in skill_names
        assert "code-repair" in skill_names

        # 2. Tier 2: View full SKILL.md
        view_res = engine.view_skill("windows-automation")
        assert view_res["status"] == "success"
        assert "Windows Workstation Automation Skill" in view_res["content"]

        # 3. Dynamic Skill Management (Create & Delete)
        create_res = engine.manage_skill(
            action="create",
            name="test-custom-skill",
            description="A test skill for unit testing",
            content="## Instructions\nDo custom task."
        )
        assert create_res["status"] == "success"

        view_custom = engine.view_skill("test-custom-skill")
        assert view_custom["status"] == "success"
        assert "Do custom task." in view_custom["content"]

        del_res = engine.manage_skill(action="delete", name="test-custom-skill")
        assert del_res["status"] == "success"

    def test_04_unified_diff_patching_engine(self, tmp_path):
        """Tests AST-validated unified diff patching with context matching."""
        from tools.patch_tool import PatchEngine
        from core.code_repair_agent import PathSandbox

        sandbox = PathSandbox([str(tmp_path)])
        patcher = PatchEngine(sandbox=sandbox)

        target_file = str(tmp_path / "calc.py")
        with open(target_file, "w", encoding="utf-8") as f:
            f.write("""def add(a, b):
    # Old comment
    return a - b
""")

        # Valid Diff to fix subtraction to addition
        diff = """--- calc.py
+++ calc.py
@@ -1,3 +1,3 @@
 def add(a, b):
-    # Old comment
-    return a - b
+    # Fixed addition
+    return a + b
"""
        # 1. Dry run
        dry_res = patcher.apply_patch(target_file=target_file, diff_content=diff, dry_run=True)
        assert dry_res["status"] == "success"
        assert dry_res["dry_run"] is True

        # Verify file unchanged after dry run
        with open(target_file, "r", encoding="utf-8") as f:
            assert "return a - b" in f.read()

        # 2. Real patch
        real_res = patcher.apply_patch(target_file=target_file, diff_content=diff, dry_run=False)
        assert real_res["status"] == "success"

        with open(target_file, "r", encoding="utf-8") as f:
            content = f.read()
            assert "return a + b" in content
            assert "Fixed addition" in content

        # 3. Syntax Error Rejection
        bad_diff = """--- calc.py
+++ calc.py
@@ -1,3 +1,3 @@
 def add(a, b):
-    # Fixed addition
-    return a + b
+    def broken_syntax(
"""
        bad_res = patcher.apply_patch(target_file=target_file, diff_content=bad_diff, dry_run=False)
        assert bad_res["status"] == "error"
        assert "syntax error" in bad_res["message"].lower()

    def test_05_persistent_cron_scheduler(self):
        """Tests cron scheduler job registration, cron parsing, and interval calculations."""
        from core.cron_scheduler import cron_scheduler, calculate_next_run

        # 1. Interval calculation
        t_now = 1000000.0
        next_t = calculate_next_run("every 10m", base_time=t_now)
        assert next_t == 1000600.0

        # 2. Standard 5-field cron calculation
        next_cron = calculate_next_run("0 9 * * *", base_time=t_now)
        assert next_cron is not None
        assert next_cron > t_now

        # 3. Add job
        res = cron_scheduler.add_job(
            name="Test Daily Greeting",
            schedule_expr="0 9 * * *",
            goal="Greet Yaswanth every morning"
        )
        assert res["status"] == "success"
        job_id = res["job_id"]

        # 4. List jobs
        jobs_list = cron_scheduler.list_jobs()
        assert jobs_list["status"] == "success"
        found = any(j["job_id"] == job_id for j in jobs_list["jobs"])
        assert found is True

        # 5. Pause & Resume
        p_res = cron_scheduler.pause_job(job_id)
        assert p_res["status"] == "success"

        r_res = cron_scheduler.resume_job(job_id)
        assert r_res["status"] == "success"

        # 6. Cleanup
        d_res = cron_scheduler.delete_job(job_id)
        assert d_res["status"] == "success"

    def test_06_programmatic_tool_calling_sandbox(self):
        """Tests PTC code sandbox executing Python code with loopback TCP RPC."""
        from tools.code_sandbox import code_sandbox

        script = """
import hermes_tools

# Remember a fact via hermes_tools RPC
res = hermes_tools.remember_fact(key="ptc_test", value="PTC Works Successfully")
print(f"PTC RESULT: {res.get('status')}")
"""
        exec_res = code_sandbox.execute_code(code=script, timeout_sec=15.0)
        assert exec_res["status"] == "success"
        assert "PTC RESULT: success" in exec_res["stdout"]

    def test_07_subagent_delegation(self):
        """Tests subagent delegation engine."""
        from orchestrator.delegation import subagent_delegator

        # 1. Single subagent research task
        res = subagent_delegator.delegate_single(task_goal="Search for python asyncio best practices", timeout_sec=15.0)
        assert res["status"] == "success"
        assert res["subagent_id"].startswith("sub_")
        assert "Research Result" in res["result"] or "Result" in res["result"]

        # 2. Batch delegation
        batch_res = subagent_delegator.delegate_batch(
            tasks=["Search for quantum computing", "Search for machine learning"],
            timeout_sec=20.0
        )
        assert batch_res["status"] == "success"
        assert batch_res["total_tasks"] == 2
        assert batch_res["completed"] == 2

    def test_08_mcp_client_manager(self):
        """Tests MCP client manager running on dedicated background loop."""
        from core.mcp_client import mcp_client

        assert mcp_client._mcp_thread is not None
        assert mcp_client._mcp_thread.is_alive()
        assert mcp_client._running is True

        mcp_client.register_server_config("test_server", {"command": "echo", "args": ["hello"]})
        call_res = mcp_client.call_tool("test_server", "sample_tool", {"param": 1})
        assert call_res["status"] == "success"
        assert call_res["server"] == "test_server"

    def test_09_unified_tool_registry_auto_registration(self):
        """Validates that all Hermes tools and aliases are registered into UnifiedToolRegistry."""
        from core.unified_tool_registry import unified_tool_registry

        decl_names = [d["name"] for d in unified_tool_registry.declarations]
        
        # Verify Hermes tools are present
        assert "skills_list" in decl_names
        assert "skill_view" in decl_names
        assert "skill_manage" in decl_names
        assert "apply_patch" in decl_names
        assert "execute_code" in decl_names
        assert "delegate_task" in decl_names
        assert "delegate_batch" in decl_names
        assert "cronjob" in decl_names
        assert "mcp_call" in decl_names

        # Verify MAX native tools remain present
        assert "execute_device_action" in decl_names
        assert "execute_multi_device_action" in decl_names
        assert "list_connected_devices" in decl_names
        assert "remember_fact" in decl_names
        assert "recall_fact" in decl_names

        # Test tool alias resolution
        res_alias = unified_tool_registry.execute("list_skills", {})
        assert res_alias["status"] == "success"

        res_alias2 = unified_tool_registry.execute("view_skill", {"skill_name": "windows-automation"})
        assert res_alias2["status"] == "success"
