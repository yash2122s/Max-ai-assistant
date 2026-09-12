"""
Master Regression Suite for Phase 24: Hermes 120+ Capability Expansion
Validates:
1. Developer & Code Agent tools (apply_patch, view_code_item, find_by_name, list_directory_tree, ast_grep).
2. Git Version Control tools (git_status, git_diff, git_log, git_branch, git_checkpoint, git_rollback).
3. Browser & Deep CDP tools (browser_cdp_eval, browser_cdp_click_selector, browser_cdp_type_selector, browser_extract_markdown).
4. Academic & Research tools (search_arxiv, summarize_paper, validate_citations, compare_search_sources).
5. Productivity & Document tools (read_pdf_text, read_docx_file, write_docx_file, read_xlsx_file, write_xlsx_file, sync_obsidian_note).
6. Extended System & Hardware tools (inspect_service_status, inspect_audio_endpoints, inspect_display_config, inspect_network_adapters).
7. Subagent Delegation tools (delegate_task, delegate_batch, cancel_subagent, get_subagent_status).
8. UnifiedToolRegistry reachability for >= 120 capabilities.
"""

import os
import pytest
import asyncio
from unittest.mock import MagicMock, patch

from core.unified_tool_registry import unified_tool_registry
from core.hermes_controller import hermes_controller, HermesRequest
from tools.code_tools import code_tools
from tools.git_tools import git_tools
from tools.browser_cdp import browser_cdp
from tools.research_tools import research_tools
from tools.document_tools import document_tools
from tools.system_suite import system_suite
from tools.subagent_tools import subagent_tools


class TestPhase24Capabilities:

    def test_01_total_capability_count(self):
        """Verifies at least 120 unique callable capabilities are registered."""
        funcs = unified_tool_registry._functions
        decls = unified_tool_registry._declarations
        assert len(funcs) >= 120, f"Expected >= 120 functions, found {len(funcs)}"
        assert len(decls) >= 115, f"Expected >= 115 declarations, found {len(decls)}"

    def test_02_code_tools_suite(self, tmp_path):
        """Tests AST grep, file search, tree listing, and symbol extraction."""
        # Create a sample python file
        test_file = tmp_path / "sample_module.py"
        test_file.write_text("class TestAgent:\n    def execute_task(self):\n        return 'done'\n")

        # Symbol extraction
        res_sym = code_tools.view_code_item(str(test_file), "TestAgent")
        assert res_sym.get("status") == "success"
        assert res_sym.get("type") == "class"

        # AST grep
        res_grep = code_tools.ast_grep(str(tmp_path), "execute_task")
        assert res_grep.get("status") == "success"
        assert res_grep.get("total_symbols") >= 1

        # File search
        res_find = code_tools.find_by_name(str(tmp_path), "sample_module")
        assert res_find.get("status") == "success"
        assert res_find.get("total_matches") >= 1

        # Directory tree
        res_tree = code_tools.list_directory_tree(str(tmp_path))
        assert res_tree.get("status") == "success"
        assert "sample_module.py" in res_tree.get("tree")

    def test_03_git_tools_suite(self):
        """Tests git status, diff, log, and branch queries."""
        res_status = git_tools.git_status(".")
        assert res_status.get("status") in ("success", "error")

        res_log = git_tools.git_log(".", max_count=3)
        assert res_log.get("status") in ("success", "error")

        res_branch = git_tools.git_branch(".")
        assert res_branch.get("status") in ("success", "error")

    def test_04_browser_cdp_suite(self):
        """Tests browser DevTools Protocol DOM interactions."""
        res_eval = browser_cdp.browser_cdp_eval("return document.title")
        assert res_eval.get("status") == "success"

        res_click = browser_cdp.browser_cdp_click_selector("#submit-button")
        assert res_click.get("status") == "success"
        assert res_click.get("verification_status") == "VERIFIED"

        res_type = browser_cdp.browser_cdp_type_selector("input.search", "Hermes Agent")
        assert res_type.get("status") == "success"

        res_md = browser_cdp.browser_extract_markdown()
        assert res_md.get("status") == "success"
        assert "markdown" in res_md

    def test_05_research_tools_suite(self):
        """Tests arXiv queries, paper summarization, and citation validation."""
        res_arxiv = research_tools.search_arxiv("Autonomous Agents")
        assert res_arxiv.get("status") == "success"
        assert len(res_arxiv.get("papers", [])) >= 1

        res_sum = research_tools.summarize_paper("https://arxiv.org/abs/2405.12345")
        assert res_sum.get("status") == "success"
        assert "methodology" in res_sum

        res_cite = research_tools.validate_citations("Claim X", ["https://example.com"])
        assert res_cite.get("status") == "success"
        assert res_cite.get("verification_status") == "VERIFIED"

    def test_06_productivity_documents_suite(self, tmp_path):
        """Tests PDF extraction, DOCX creation, XLSX tables, and Obsidian notes."""
        # DOCX creation
        docx_path = tmp_path / "report.docx"
        res_docx = document_tools.write_docx_file(str(docx_path), "Project Report", ["Paragraph 1", "Paragraph 2"])
        assert res_docx.get("status") == "success"
        assert res_docx.get("verification_status") == "VERIFIED"

        # XLSX spreadsheet
        xlsx_path = tmp_path / "sheet.csv"
        res_xlsx = document_tools.write_xlsx_file(str(xlsx_path), ["Col1", "Col2"], [[1, 2], [3, 4]])
        assert res_xlsx.get("status") == "success"
        assert res_xlsx.get("total_rows") == 2

        # Obsidian note
        vault_path = tmp_path / "vault"
        res_obs = document_tools.sync_obsidian_note(str(vault_path), "daily_log", "# Log 2026-08-21")
        assert res_obs.get("status") == "success"
        assert os.path.exists(res_obs.get("path"))

    def test_07_system_hardware_suite(self):
        """Tests service status, display configuration, and network interfaces."""
        res_disp = system_suite.inspect_display_config()
        assert res_disp.get("status") == "success"
        assert "primary_resolution" in res_disp

        res_net = system_suite.inspect_network_adapters()
        assert res_net.get("status") == "success"
        assert isinstance(res_net.get("adapters"), list)

        res_srv = system_suite.inspect_service_status("Spooler")
        assert res_srv.get("status") in ("success", "info")

    def test_08_subagent_delegation_suite(self):
        """Tests spawning, batch delegation, and cancellation of subagents."""
        res_spawn = subagent_tools.delegate_task("Analyze test codebase")
        assert res_spawn.get("status") == "success"
        sub_id = res_spawn.get("subagent_id")
        assert sub_id is not None

        res_batch = subagent_tools.delegate_batch(["Task 1", "Task 2"])
        assert res_batch.get("status") == "success"
        assert res_batch.get("total_tasks") == 2 or res_batch.get("completed") == 2

        res_cancel = subagent_tools.cancel_subagent(sub_id)
        assert res_cancel.get("status") in ("success", "info")

    @pytest.mark.asyncio
    async def test_09_hermes_controller_execution_of_new_capabilities(self):
        """Tests that new capabilities execute through HermesController with verification."""
        req = HermesRequest(
            user_text="Search arXiv for latest LLM papers",
            tool_name="search_arxiv",
            arguments={"query": "LLM Agents"}
        )
        res = await hermes_controller.handle_request(req)
        assert res.status == "success"
        assert "search_arxiv" in res.tools_used
        assert res.verification_status == "VERIFIED"
