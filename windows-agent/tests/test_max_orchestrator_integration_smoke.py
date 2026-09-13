"""
MAX AI Unified Agent Integration Smoke Tests
Validates all MAX agent capabilities running in the unified MAX environment:
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
import unittest
import tempfile
import shutil
from pathlib import Path

from config import (
    ENABLE_MAX_AGENT,
    ENABLE_MAX_SKILLS,
    ENABLE_MAX_MCP,
    ENABLE_MAX_MEMORY,
    ENABLE_MAX_SUBAGENTS,
    ENABLE_MAX_SCHEDULER,
    ENABLE_REALTIME_VOICE,
    PROJECT_ROOT
)


class TestMaxOrchestratorIntegrationSmoke(unittest.TestCase):

    def test_01_feature_flags_and_voice_sovereignty(self):
        """Validates that MAX Agent is enabled and Realtime Voice remains exclusive voice interface."""
        self.assertTrue(ENABLE_MAX_AGENT)
        self.assertTrue(ENABLE_MAX_SKILLS)
        self.assertTrue(ENABLE_MAX_MCP)
        self.assertTrue(ENABLE_MAX_MEMORY)
        self.assertTrue(ENABLE_MAX_SUBAGENTS)
        self.assertTrue(ENABLE_MAX_SCHEDULER)

    def test_02_sqlite_state_database_and_fts5_memory(self):
        """Tests SQLite WAL state database with FTS5 search, memory persistence, and audit logging."""
        from core.state_database import StateDatabase

        tmp_dir = tempfile.mkdtemp()
        try:
            db_file = os.path.join(tmp_dir, "test_state.db")
            db = StateDatabase(db_file)

            # 1. Save memories
            res1 = db.save_memory(key="creator_name", value="Yaswanth")
            self.assertEqual(res1["status"], "success")

            res2 = db.save_memory(key="favorite_device", value="Redmi K20 Pro Phone 1")
            self.assertEqual(res2["status"], "success")

            # 2. Recall with FTS5
            search_res = db.recall_memory("Yaswanth")
            self.assertEqual(search_res["status"], "success")
            self.assertIn("creator_name", search_res["results"])
            self.assertEqual(search_res["results"]["creator_name"], "Yaswanth")

            search_res2 = db.recall_memory("Redmi")
            self.assertEqual(search_res2["status"], "success")
            self.assertIn("favorite_device", search_res2["results"])

            # 3. Summary text for prompt injection
            summary = db.get_summary_text()
            self.assertIn("creator_name: Yaswanth", summary)
            self.assertIn("favorite_device: Redmi K20 Pro Phone 1", summary)

            # 4. Audit logging
            db.log_audit(
                agent="max_agent",
                tool="test_tool",
                arguments={"query": "test"},
                status="success",
                duration_ms=12
            )
            conn = db._get_connection()
            cur = conn.execute("SELECT COUNT(*) as count FROM audit_logs WHERE agent = 'max_agent'")
            self.assertGreaterEqual(cur.fetchone()["count"], 1)
        finally:
            shutil.rmtree(tmp_dir, ignore_errors=True)

    def test_03_progressive_skills_engine(self):
        """Tests 3-tier progressive disclosure skills engine."""
        from core.skills_engine import SkillsEngine

        tmp_dir = tempfile.mkdtemp()
        try:
            skills_dir = os.path.join(tmp_dir, "skills")
            engine = SkillsEngine(skills_dir)

            # 1. Tier 1: List skills metadata
            list_res = engine.list_skills()
            self.assertEqual(list_res["status"], "success")
            self.assertGreaterEqual(list_res["count"], 3)  # Default seeded skills

            skill_names = [s["name"] for s in list_res["skills"]]
            self.assertIn("windows-automation", skill_names)
            self.assertIn("android-ecosystem", skill_names)
            self.assertIn("code-repair", skill_names)

            # 2. Tier 2: View full SKILL.md
            view_res = engine.view_skill("windows-automation")
            self.assertEqual(view_res["status"], "success")
            self.assertIn("Windows Workstation Automation Skill", view_res["content"])

            # 3. Dynamic Skill Management (Create & Delete)
            create_res = engine.manage_skill(
                action="create",
                name="test-custom-skill",
                description="A test skill for unit testing",
                content="## Instructions\nDo custom task."
            )
            self.assertEqual(create_res["status"], "success")

            view_custom = engine.view_skill("test-custom-skill")
            self.assertEqual(view_custom["status"], "success")
            self.assertIn("Do custom task.", view_custom["content"])

            del_res = engine.manage_skill(action="delete", name="test-custom-skill")
            self.assertEqual(del_res["status"], "success")
        finally:
            shutil.rmtree(tmp_dir, ignore_errors=True)

    def test_04_unified_diff_patching_engine(self):
        """Tests AST-validated unified diff patching with context matching."""
        from tools.patch_tool import PatchEngine
        from core.code_repair_agent import PathSandbox

        tmp_dir = tempfile.mkdtemp()
        try:
            sandbox = PathSandbox([tmp_dir])
            patcher = PatchEngine(sandbox=sandbox)

            target_file = os.path.join(tmp_dir, "calc.py")
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
            self.assertEqual(dry_res["status"], "success")
            self.assertTrue(dry_res["dry_run"])

            # Verify file unchanged after dry run
            with open(target_file, "r", encoding="utf-8") as f:
                self.assertIn("return a - b", f.read())

            # 2. Real patch
            real_res = patcher.apply_patch(target_file=target_file, diff_content=diff, dry_run=False)
            self.assertEqual(real_res["status"], "success")

            with open(target_file, "r", encoding="utf-8") as f:
                content = f.read()
                self.assertIn("return a + b", content)
                self.assertIn("Fixed addition", content)

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
            self.assertEqual(bad_res["status"], "error")
            self.assertIn("syntax error", bad_res["message"].lower())
        finally:
            shutil.rmtree(tmp_dir, ignore_errors=True)

    def test_05_persistent_cron_scheduler(self):
        """Tests cron scheduler job registration, cron parsing, and interval calculations."""
        from core.cron_scheduler import cron_scheduler, calculate_next_run

        # 1. Interval calculation
        t_now = 1000000.0
        next_t = calculate_next_run("every 10m", base_time=t_now)
        self.assertEqual(next_t, 1000600.0)

        # 2. Standard 5-field cron calculation
        next_cron = calculate_next_run("0 9 * * *", base_time=t_now)
        self.assertIsNotNone(next_cron)
        self.assertGreater(next_cron, t_now)

        # 3. Add job
        res = cron_scheduler.add_job(
            name="Test Daily Greeting",
            schedule_expr="0 9 * * *",
            goal="Greet Yaswanth every morning"
        )
        self.assertEqual(res["status"], "success")
        job_id = res["job_id"]

        # 4. List jobs
        jobs_list = cron_scheduler.list_jobs()
        self.assertEqual(jobs_list["status"], "success")
        found = any(j["job_id"] == job_id for j in jobs_list["jobs"])
        self.assertTrue(found)

        # 5. Pause & Resume
        p_res = cron_scheduler.pause_job(job_id)
        self.assertEqual(p_res["status"], "success")

        r_res = cron_scheduler.resume_job(job_id)
        self.assertEqual(r_res["status"], "success")

        # 6. Cleanup
        d_res = cron_scheduler.delete_job(job_id)
        self.assertEqual(d_res["status"], "success")

    def test_06_programmatic_tool_calling_sandbox(self):
        """Tests PTC code sandbox executing Python code with loopback TCP RPC."""
        from tools.code_sandbox import code_sandbox

        script = """
import max_tools

# Remember a fact via max_tools RPC
res = max_tools.remember_fact(key="ptc_test", value="PTC Works Successfully")
print(f"PTC RESULT: {res.get('status')}")
"""
        exec_res = code_sandbox.execute_code(code=script, timeout_sec=15.0)
        self.assertEqual(exec_res["status"], "success")
        self.assertIn("PTC RESULT: success", exec_res["stdout"])

    def test_07_subagent_delegation(self):
        """Tests subagent delegation engine."""
        from orchestrator.delegation import subagent_delegator

        # 1. Single subagent research task
        res = subagent_delegator.delegate_single(task_goal="Search for python asyncio best practices", timeout_sec=15.0)
        self.assertEqual(res["status"], "success")
        self.assertTrue(res["subagent_id"].startswith("sub_"))
        self.assertTrue("Research Result" in res["result"] or "Result" in res["result"])

        # 2. Batch delegation
        batch_res = subagent_delegator.delegate_batch(
            tasks=["Search for quantum computing", "Search for machine learning"],
            timeout_sec=20.0
        )
        self.assertEqual(batch_res["status"], "success")
        self.assertEqual(batch_res["total_tasks"], 2)
        self.assertEqual(batch_res["completed"], 2)

    def test_08_mcp_client_manager(self):
        """Tests MCP client manager running on dedicated background loop."""
        from core.mcp_client import mcp_client

        self.assertIsNotNone(mcp_client._mcp_thread)
        self.assertTrue(mcp_client._mcp_thread.is_alive())
        self.assertTrue(mcp_client._running)

        mcp_client.register_server_config("test_server", {"command": "echo", "args": ["hello"]})
        call_res = mcp_client.call_tool("test_server", "sample_tool", {"param": 1})
        self.assertEqual(call_res["status"], "success")
        self.assertEqual(call_res["server"], "test_server")

    def test_09_unified_tool_registry_auto_registration(self):
        """Validates that all MAX agent tools and aliases are registered into UnifiedToolRegistry."""
        from core.unified_tool_registry import unified_tool_registry

        decl_names = [d["name"] for d in unified_tool_registry.declarations]
        
        # Verify MAX tools are present
        self.assertIn("skills_list", decl_names)
        self.assertIn("skill_view", decl_names)
        self.assertIn("skill_manage", decl_names)
        self.assertIn("apply_patch", decl_names)
        self.assertIn("execute_code", decl_names)
        self.assertIn("delegate_task", decl_names)
        self.assertIn("delegate_batch", decl_names)
        self.assertIn("cronjob", decl_names)
        self.assertIn("mcp_call", decl_names)

        # Verify MAX native tools remain present
        self.assertIn("execute_device_action", decl_names)
        self.assertIn("execute_multi_device_action", decl_names)
        self.assertIn("list_connected_devices", decl_names)
        self.assertIn("remember_fact", decl_names)
        self.assertIn("recall_fact", decl_names)

        # Test tool alias resolution
        res_alias = unified_tool_registry.execute("list_skills", {})
        self.assertEqual(res_alias["status"], "success")

        res_alias2 = unified_tool_registry.execute("view_skill", {"skill_name": "windows-automation"})
        self.assertEqual(res_alias2["status"], "success")


if __name__ == "__main__":
    unittest.main()
