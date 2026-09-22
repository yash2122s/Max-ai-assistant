"""
MAX AI — MaxAgentEngine & Unified Ecosystem Bridge
Connects MAX autonomous reasoning, skills, MCP, PTC, and subagents
directly into MAX's authoritative UnifiedToolRegistry, Windows drivers, and Android DeviceRouter.
"""

import time
import json
import logging
import asyncio
from typing import Dict, Any, List, Optional

from config import (
    ENABLE_HERMES as ENABLE_MAX_AGENT,
    ENABLE_HERMES_SKILLS as ENABLE_MAX_SKILLS,
    ENABLE_HERMES_MCP as ENABLE_MAX_MCP,
    ENABLE_HERMES_MEMORY as ENABLE_MAX_MEMORY,
    ENABLE_HERMES_SUBAGENTS as ENABLE_MAX_SUBAGENTS,
    ENABLE_HERMES_SCHEDULER as ENABLE_MAX_SCHEDULER,
)

logger = logging.getLogger("MAX.AgentEngine")


class MaxAgentEngine:
    """
    Primary Autonomous Agent Runtime for MAX AI.
    """
    _instance: Optional['MaxAgentEngine'] = None

    def __init__(self):
        self._is_initialized = False
        self._init_subsystems()

    @classmethod
    def get_instance(cls) -> 'MaxAgentEngine':
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def _init_subsystems(self):
        """Initializes MAX Agent modules and registers their capabilities."""
        if not ENABLE_MAX_AGENT:
            logger.info("ℹ️ MAX Agent Engine is disabled via feature flag.")
            return

        logger.info("🚀 Initializing MAX Primary Agent Engine...")

        # 1. Start SQLite State Database
        if ENABLE_MAX_MEMORY:
            try:
                from core.state_database import state_db
                logger.info("💾 MAX State Database & FTS5 Memory: ACTIVE")
            except Exception as e:
                logger.error(f"Failed to initialize MAX State DB: {e}")

        # 2. Start Persistent Cron Scheduler
        if ENABLE_MAX_SCHEDULER:
            try:
                from core.cron_scheduler import cron_scheduler
                cron_scheduler.start()
                logger.info("⏱️ MAX Persistent Cron Scheduler: ACTIVE")
            except Exception as e:
                logger.error(f"Failed to start Cron Scheduler: {e}")

        # 3. Load MCP Client Servers
        if ENABLE_MAX_MCP:
            try:
                from core.mcp_client import mcp_client
                mcp_client.load_servers_from_config()
                logger.info("🔌 MAX MCP Client System: ACTIVE")
            except Exception as e:
                logger.error(f"Failed to initialize MCP Client: {e}")

        # 4. Progressive Skills Engine
        if ENABLE_MAX_SKILLS:
            try:
                from core.skills_engine import skills_engine
                logger.info(f"📚 MAX Progressive Skills Engine: ACTIVE ({skills_engine.list_skills().get('count', 0)} skills loaded)")
                try:
                    from core.startup_profiler import profiler
                    profiler.mark("SKILLS_READY")
                except Exception:
                    pass
            except Exception as e:
                logger.error(f"Failed to initialize Skills Engine: {e}")

        self._is_initialized = True
        logger.info("✨ MAX Primary Agent Engine initialized successfully.")
        try:
            from core.startup_profiler import profiler
            profiler.mark("AGENT_ENGINE_READY")
        except Exception:
            pass

    def register_all_max_agent_tools(self, registry):
        """
        Registers all MAX Agent tools directly into MAX's UnifiedToolRegistry.
        """
        if not ENABLE_MAX_AGENT:
            return

        # 1. Skills Tools
        if ENABLE_MAX_SKILLS:
            try:
                from core.skills_engine import SKILL_TOOL_DECLARATIONS, SKILL_TOOL_FUNCTIONS
                for decl in SKILL_TOOL_DECLARATIONS:
                    if decl["name"] not in [d["name"] for d in registry._declarations]:
                        registry._declarations.append(decl)
                for name, func in SKILL_TOOL_FUNCTIONS.items():
                    registry._functions[name] = func
                logger.info("Registered MAX Skills tools into UnifiedToolRegistry")
            except Exception as e:
                logger.error(f"Error registering skills tools: {e}")

        # 2. Patch Tool
        try:
            from tools.patch_tool import PATCH_TOOL_DECLARATIONS, PATCH_TOOL_FUNCTIONS
            for decl in PATCH_TOOL_DECLARATIONS:
                if decl["name"] not in [d["name"] for d in registry._declarations]:
                    registry._declarations.append(decl)
            for name, func in PATCH_TOOL_FUNCTIONS.items():
                registry._functions[name] = func
            logger.info("Registered MAX Patch tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering patch tools: {e}")

        # 3. Programmatic Tool Calling (PTC) Sandbox
        try:
            from tools.code_sandbox import SANDBOX_TOOL_DECLARATIONS, SANDBOX_TOOL_FUNCTIONS
            for decl in SANDBOX_TOOL_DECLARATIONS:
                if decl["name"] not in [d["name"] for d in registry._declarations]:
                    registry._declarations.append(decl)
            for name, func in SANDBOX_TOOL_FUNCTIONS.items():
                registry._functions[name] = func
            logger.info("Registered MAX Code Sandbox (PTC) tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering sandbox tools: {e}")

        # 4. Subagent Delegation Tools
        if ENABLE_MAX_SUBAGENTS:
            try:
                from core.delegation import DELEGATION_TOOL_DECLARATIONS, DELEGATION_TOOL_FUNCTIONS
                for decl in DELEGATION_TOOL_DECLARATIONS:
                    if decl["name"] not in [d["name"] for d in registry._declarations]:
                        registry._declarations.append(decl)
                for name, func in DELEGATION_TOOL_FUNCTIONS.items():
                    registry._functions[name] = func
                logger.info("Registered MAX Subagent Delegation tools into UnifiedToolRegistry")
            except Exception as e:
                logger.error(f"Error registering delegation tools: {e}")

        # 5. Persistent Cronjob Tools
        if ENABLE_MAX_SCHEDULER:
            try:
                from core.cron_scheduler import CRON_TOOL_DECLARATIONS, CRON_TOOL_FUNCTIONS
                for decl in CRON_TOOL_DECLARATIONS:
                    if decl["name"] not in [d["name"] for d in registry._declarations]:
                        registry._declarations.append(decl)
                for name, func in CRON_TOOL_FUNCTIONS.items():
                    registry._functions[name] = func
                logger.info("Registered MAX Cronjob tools into UnifiedToolRegistry")
            except Exception as e:
                logger.error(f"Error registering cronjob tools: {e}")

        # 6. MCP Tools
        if ENABLE_MAX_MCP:
            try:
                from core.mcp_client import MCP_TOOL_DECLARATIONS, MCP_TOOL_FUNCTIONS
                for decl in MCP_TOOL_DECLARATIONS:
                    if decl["name"] not in [d["name"] for d in registry._declarations]:
                        registry._declarations.append(decl)
                for name, func in MCP_TOOL_FUNCTIONS.items():
                    registry._functions[name] = func
                logger.info("Registered MAX MCP tools into UnifiedToolRegistry")
            except Exception as e:
                logger.error(f"Error registering MCP tools: {e}")

        # 7. Interactive Windows PTY Tools
        try:
            from core.pty_manager import PTY_TOOL_DECLARATIONS, PTY_TOOL_FUNCTIONS
            for decl in PTY_TOOL_DECLARATIONS:
                if decl["name"] not in [d["name"] for d in registry._declarations]:
                    registry._declarations.append(decl)
            for name, func in PTY_TOOL_FUNCTIONS.items():
                registry._functions[name] = func
            logger.info("Registered MAX PTY tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering PTY tools: {e}")

        # 8. Skills Hub Tools
        try:
            from core.skills_hub import SKILLS_HUB_TOOL_DECLARATIONS, SKILLS_HUB_TOOL_FUNCTIONS
            for decl in SKILLS_HUB_TOOL_DECLARATIONS:
                if decl["name"] not in [d["name"] for d in registry._declarations]:
                    registry._declarations.append(decl)
            for name, func in SKILLS_HUB_TOOL_FUNCTIONS.items():
                registry._functions[name] = func
            logger.info("Registered MAX Skills Hub tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering Skills Hub tools: {e}")

        # 9. MCP Server Hosting Tools
        try:
            from core.mcp_server_host import MCP_SERVER_TOOL_DECLARATIONS, MCP_SERVER_TOOL_FUNCTIONS
            for decl in MCP_SERVER_TOOL_DECLARATIONS:
                if decl["name"] not in [d["name"] for d in registry._declarations]:
                    registry._declarations.append(decl)
            for name, func in MCP_SERVER_TOOL_FUNCTIONS.items():
                registry._functions[name] = func
            logger.info("Registered MAX MCP Server Host tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering MCP Server Host tools: {e}")

        # 10. Multi-Platform Gateway Tools
        try:
            from gateway.gateway_server import GATEWAY_TOOL_DECLARATIONS, GATEWAY_TOOL_FUNCTIONS
            for decl in GATEWAY_TOOL_DECLARATIONS:
                if decl["name"] not in [d["name"] for d in registry._declarations]:
                    registry._declarations.append(decl)
            for name, func in GATEWAY_TOOL_FUNCTIONS.items():
                registry._functions[name] = func
            logger.info("Registered MAX Gateway tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering Gateway tools: {e}")

        # 11. Self-Diagnostics Doctor Tools
        try:
            from core.doctor import DOCTOR_TOOL_DECLARATIONS, DOCTOR_TOOL_FUNCTIONS
            for decl in DOCTOR_TOOL_DECLARATIONS:
                if decl["name"] not in [d["name"] for d in registry._declarations]:
                    registry._declarations.append(decl)
            for name, func in DOCTOR_TOOL_FUNCTIONS.items():
                registry._functions[name] = func
            logger.info("Registered MAX Doctor tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering Doctor tools: {e}")

        # 12. Media Generation Tools
        try:
            from tools.media_generation import MEDIA_TOOL_DECLARATIONS, MEDIA_TOOL_FUNCTIONS
            for decl in MEDIA_TOOL_DECLARATIONS:
                if decl["name"] not in [d["name"] for d in registry._declarations]:
                    registry._declarations.append(decl)
            for name, func in MEDIA_TOOL_FUNCTIONS.items():
                registry._functions[name] = func
            logger.info("Registered MAX Media Generation tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering Media Generation tools: {e}")

        # 13. Kanban Task Board Tools
        try:
            from tools.kanban import KANBAN_TOOL_DECLARATIONS, KANBAN_TOOL_FUNCTIONS
            for decl in KANBAN_TOOL_DECLARATIONS:
                if decl["name"] not in [d["name"] for d in registry._declarations]:
                    registry._declarations.append(decl)
            for name, func in KANBAN_TOOL_FUNCTIONS.items():
                registry._functions[name] = func
            logger.info("Registered MAX Kanban tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering Kanban tools: {e}")

        # 14. Insights Extraction Tools
        try:
            from agent.insights import INSIGHTS_TOOL_DECLARATIONS, INSIGHTS_TOOL_FUNCTIONS
            for decl in INSIGHTS_TOOL_DECLARATIONS:
                if decl["name"] not in [d["name"] for d in registry._declarations]:
                    registry._declarations.append(decl)
            for name, func in INSIGHTS_TOOL_FUNCTIONS.items():
                registry._functions[name] = func
            logger.info("Registered MAX Insights tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering Insights tools: {e}")

        # 15. Developer & Code Agent Suite
        try:
            from tools.code_tools import code_tools
            code_decls = [
                {
                    "name": "apply_patch",
                    "description": "Applies a unified diff patch to a target file with dry-run verification.",
                    "parameters": {"type": "object", "properties": {"file_path": {"type": "string"}, "diff_content": {"type": "string"}, "dry_run": {"type": "boolean"}}, "required": ["file_path", "diff_content"]}
                },
                {
                    "name": "view_code_item",
                    "description": "Extracts AST-level function or class definition from a Python file.",
                    "parameters": {"type": "object", "properties": {"file_path": {"type": "string"}, "item_name": {"type": "string"}}, "required": ["file_path", "item_name"]}
                },
                {
                    "name": "find_by_name",
                    "description": "Finds files by filename pattern or glob across directory tree.",
                    "parameters": {"type": "object", "properties": {"search_path": {"type": "string"}, "pattern": {"type": "string"}, "max_results": {"type": "integer"}}, "required": ["search_path", "pattern"]}
                },
                {
                    "name": "list_directory_tree",
                    "description": "Generates a structured directory tree with depth control.",
                    "parameters": {"type": "object", "properties": {"directory_path": {"type": "string"}, "max_depth": {"type": "integer"}}, "required": ["directory_path"]}
                },
                {
                    "name": "ast_grep",
                    "description": "Performs syntax-aware symbol searches across Python codebases.",
                    "parameters": {"type": "object", "properties": {"search_path": {"type": "string"}, "symbol_query": {"type": "string"}}, "required": ["search_path", "symbol_query"]}
                }
            ]
            for d in code_decls:
                if d["name"] not in [x["name"] for x in registry._declarations]:
                    registry._declarations.append(d)
            registry._functions["apply_patch"] = code_tools.apply_patch
            registry._functions["view_code_item"] = code_tools.view_code_item
            registry._functions["find_by_name"] = code_tools.find_by_name
            registry._functions["list_directory_tree"] = code_tools.list_directory_tree
            registry._functions["ast_grep"] = code_tools.ast_grep
            logger.info("Registered Developer & Code Agent tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering Code tools: {e}")

        # 16. Git Version Control Suite
        try:
            from tools.git_tools import git_tools
            git_decls = [
                {"name": "git_status", "description": "Returns structured git status for repository.", "parameters": {"type": "object", "properties": {"repo_path": {"type": "string"}}}},
                {"name": "git_diff", "description": "Returns unified git diff against HEAD or target.", "parameters": {"type": "object", "properties": {"repo_path": {"type": "string"}, "target": {"type": "string"}}}},
                {"name": "git_log", "description": "Returns structured recent commit history.", "parameters": {"type": "object", "properties": {"repo_path": {"type": "string"}, "max_count": {"type": "integer"}}}},
                {"name": "git_branch", "description": "Lists repository branches and active branch.", "parameters": {"type": "object", "properties": {"repo_path": {"type": "string"}}}},
                {"name": "git_checkpoint", "description": "Creates a temporary stash checkpoint.", "parameters": {"type": "object", "properties": {"repo_path": {"type": "string"}, "message": {"type": "string"}}}},
                {"name": "git_rollback", "description": "Rolls back uncommitted changes safely.", "parameters": {"type": "object", "properties": {"repo_path": {"type": "string"}}}}
            ]
            for d in git_decls:
                if d["name"] not in [x["name"] for x in registry._declarations]:
                    registry._declarations.append(d)
            registry._functions["git_status"] = git_tools.git_status
            registry._functions["git_diff"] = git_tools.git_diff
            registry._functions["git_log"] = git_tools.git_log
            registry._functions["git_branch"] = git_tools.git_branch
            registry._functions["git_checkpoint"] = git_tools.git_checkpoint
            registry._functions["git_rollback"] = git_tools.git_rollback
            logger.info("Registered Git Version Control tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering Git tools: {e}")

        # 17. Deep Browser CDP Suite
        try:
            from tools.browser_cdp import browser_cdp
            cdp_decls = [
                {"name": "browser_cdp_eval", "description": "Evaluates JavaScript in active browser page.", "parameters": {"type": "object", "properties": {"script": {"type": "string"}}, "required": ["script"]}},
                {"name": "browser_cdp_click_selector", "description": "Clicks element matching CSS selector or XPath.", "parameters": {"type": "object", "properties": {"selector": {"type": "string"}}, "required": ["selector"]}},
                {"name": "browser_cdp_type_selector", "description": "Types text into input matching selector.", "parameters": {"type": "object", "properties": {"selector": {"type": "string"}, "text": {"type": "string"}}, "required": ["selector", "text"]}},
                {"name": "browser_cdp_extract_dom", "description": "Extracts cleaned DOM text or HTML subtree.", "parameters": {"type": "object", "properties": {"selector": {"type": "string"}}}},
                {"name": "browser_cdp_screenshot_element", "description": "Captures visual frame of specific element.", "parameters": {"type": "object", "properties": {"selector": {"type": "string"}}, "required": ["selector"]}},
                {"name": "browser_extract_markdown", "description": "Converts live web page into structured Markdown.", "parameters": {"type": "object", "properties": {"url": {"type": "string"}}}},
                {"name": "browser_extract_links", "description": "Extracts all outbound URLs on current page.", "parameters": {"type": "object", "properties": {}}}
            ]
            for d in cdp_decls:
                if d["name"] not in [x["name"] for x in registry._declarations]:
                    registry._declarations.append(d)
            registry._functions["browser_cdp_eval"] = browser_cdp.browser_cdp_eval
            registry._functions["browser_cdp_click_selector"] = browser_cdp.browser_cdp_click_selector
            registry._functions["browser_cdp_type_selector"] = browser_cdp.browser_cdp_type_selector
            registry._functions["browser_cdp_extract_dom"] = browser_cdp.browser_cdp_extract_dom
            registry._functions["browser_cdp_screenshot_element"] = browser_cdp.browser_cdp_screenshot_element
            registry._functions["browser_extract_markdown"] = browser_cdp.browser_extract_markdown
            registry._functions["browser_extract_links"] = browser_cdp.browser_extract_links
            logger.info("Registered Deep Browser CDP tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering CDP tools: {e}")

        # 18. Research & Academic Suite
        try:
            from tools.research_tools import research_tools
            res_decls = [
                {"name": "search_arxiv", "description": "Searches arXiv for scientific research papers.", "parameters": {"type": "object", "properties": {"query": {"type": "string"}, "max_results": {"type": "integer"}}, "required": ["query"]}},
                {"name": "summarize_paper", "description": "Extracts methodology, findings, and conclusions from academic text.", "parameters": {"type": "object", "properties": {"paper_text_or_url": {"type": "string"}}, "required": ["paper_text_or_url"]}},
                {"name": "validate_citations", "description": "Cross-validates factual claims against source URLs.", "parameters": {"type": "object", "properties": {"claim": {"type": "string"}, "source_urls": {"type": "array", "items": {"type": "string"}}}, "required": ["claim", "source_urls"]}},
                {"name": "compare_search_sources", "description": "Ranks and consolidates results across web indices.", "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}
            ]
            for d in res_decls:
                if d["name"] not in [x["name"] for x in registry._declarations]:
                    registry._declarations.append(d)
            registry._functions["search_arxiv"] = research_tools.search_arxiv
            registry._functions["summarize_paper"] = research_tools.summarize_paper
            registry._functions["validate_citations"] = research_tools.validate_citations
            registry._functions["compare_search_sources"] = research_tools.compare_search_sources
            logger.info("Registered Research & Academic tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering Research tools: {e}")

        # 19. Productivity & Office Document Suite
        try:
            from tools.document_tools import document_tools
            doc_decls = [
                {"name": "read_pdf_text", "description": "Extracts text content from a PDF file.", "parameters": {"type": "object", "properties": {"file_path": {"type": "string"}, "max_pages": {"type": "integer"}}, "required": ["file_path"]}},
                {"name": "read_docx_file", "description": "Extracts text from a Microsoft Word .docx file.", "parameters": {"type": "object", "properties": {"file_path": {"type": "string"}}, "required": ["file_path"]}},
                {"name": "write_docx_file", "description": "Creates formatted Microsoft Word .docx file.", "parameters": {"type": "object", "properties": {"file_path": {"type": "string"}, "title": {"type": "string"}, "paragraphs": {"type": "array", "items": {"type": "string"}}}, "required": ["file_path", "title", "paragraphs"]}},
                {"name": "read_xlsx_file", "description": "Reads rows and headers from an Excel workbook.", "parameters": {"type": "object", "properties": {"file_path": {"type": "string"}}, "required": ["file_path"]}},
                {"name": "write_xlsx_file", "description": "Creates Excel spreadsheet with headers and rows.", "parameters": {"type": "object", "properties": {"file_path": {"type": "string"}, "headers": {"type": "array", "items": {"type": "string"}}, "rows": {"type": "array", "items": {"type": "array", "items": {"type": "string"}}}}, "required": ["file_path", "headers", "rows"]}},
                {"name": "sync_obsidian_note", "description": "Syncs or creates a Markdown note in an Obsidian vault.", "parameters": {"type": "object", "properties": {"vault_path": {"type": "string"}, "note_title": {"type": "string"}, "content": {"type": "string"}}, "required": ["vault_path", "note_title", "content"]}}
            ]
            for d in doc_decls:
                if d["name"] not in [x["name"] for x in registry._declarations]:
                    registry._declarations.append(d)
            registry._functions["read_pdf_text"] = document_tools.read_pdf_text
            registry._functions["read_docx_file"] = document_tools.read_docx_file
            registry._functions["write_docx_file"] = document_tools.write_docx_file
            registry._functions["read_xlsx_file"] = document_tools.read_xlsx_file
            registry._functions["write_xlsx_file"] = document_tools.write_xlsx_file
            registry._functions["sync_obsidian_note"] = document_tools.sync_obsidian_note
            logger.info("Registered Productivity & Document tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering Document tools: {e}")

        # 20. Extended System & Hardware Suite
        try:
            from tools.system_suite import system_suite
            sys_decls = [
                {"name": "inspect_service_status", "description": "Queries Windows service status by service name.", "parameters": {"type": "object", "properties": {"service_name": {"type": "string"}}, "required": ["service_name"]}},
                {"name": "inspect_audio_endpoints", "description": "Lists audio playback and capture endpoint details.", "parameters": {"type": "object", "properties": {}}},
                {"name": "inspect_display_config", "description": "Queries screen resolutions and multi-monitor bounds.", "parameters": {"type": "object", "properties": {}}},
                {"name": "inspect_network_adapters", "description": "Lists network interfaces, IP addresses, and status.", "parameters": {"type": "object", "properties": {}}}
            ]
            for d in sys_decls:
                if d["name"] not in [x["name"] for x in registry._declarations]:
                    registry._declarations.append(d)
            registry._functions["inspect_service_status"] = system_suite.inspect_service_status
            registry._functions["inspect_audio_endpoints"] = system_suite.inspect_audio_endpoints
            registry._functions["inspect_display_config"] = system_suite.inspect_display_config
            registry._functions["inspect_network_adapters"] = system_suite.inspect_network_adapters
            logger.info("Registered Extended System & Hardware tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering System suite tools: {e}")

        # 21. Subagent Delegation Suite
        try:
            from tools.subagent_tools import subagent_tools
            sub_decls = [
                {"name": "delegate_task", "description": "Spawns isolated child subagent for bounded subtask.", "parameters": {"type": "object", "properties": {"goal": {"type": "string"}, "timeout_sec": {"type": "number"}}, "required": ["goal"]}},
                {"name": "delegate_batch", "description": "Spawns multiple parallel subagents with task IDs.", "parameters": {"type": "object", "properties": {"subtasks": {"type": "array", "items": {"type": "string"}}, "timeout_sec": {"type": "number"}}, "required": ["subtasks"]}},
                {"name": "cancel_subagent", "description": "Terminates an active subagent by ID.", "parameters": {"type": "object", "properties": {"subagent_id": {"type": "string"}}, "required": ["subagent_id"]}},
                {"name": "get_subagent_status", "description": "Queries current status and output of child subagent.", "parameters": {"type": "object", "properties": {"subagent_id": {"type": "string"}}, "required": ["subagent_id"]}}
            ]
            for d in sub_decls:
                if d["name"] not in [x["name"] for x in registry._declarations]:
                    registry._declarations.append(d)
            registry._functions["delegate_task"] = subagent_tools.delegate_task
            registry._functions["delegate_batch"] = subagent_tools.delegate_batch
            registry._functions["cancel_subagent"] = subagent_tools.cancel_subagent
            registry._functions["get_subagent_status"] = subagent_tools.get_subagent_status
            logger.info("Registered Subagent Delegation tools into UnifiedToolRegistry")
        except Exception as e:
            logger.error(f"Error registering Subagent tools: {e}")

    register_all_hermes_tools = register_all_max_agent_tools

    async def execute_autonomous_goal(
        self,
        goal: str,
        context: Optional[Dict[str, Any]] = None,
        timeout_sec: float = 45.0
    ) -> Dict[str, Any]:
        """
        Executes a complex autonomous goal using the MAX multi-turn reasoning loop.
        """
        start_t = time.time()
        logger.info(f"🧠 MAX Agent Engine reasoning on goal: '{goal}'")
        try:
            from core.agent_execution_loop import agent_execution_loop
            return await agent_execution_loop.execute_goal_loop(goal=goal, context=context)
        except Exception as e:
            logger.error(f"❌ MAX goal execution error: {e}")
            return {"status": "error", "message": str(e)}


# Singleton instance
max_agent_engine = MaxAgentEngine.get_instance()

# Backward-compatibility aliases
HermesEngine = MaxAgentEngine
hermes_engine = max_agent_engine
