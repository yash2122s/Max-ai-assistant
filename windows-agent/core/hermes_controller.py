"""
MAX AI — Hermes Controller & Runtime Intent Gateway
Elevates Hermes as the primary autonomous reasoning, planning, and execution authority.
Acts as the central gateway between perception (Gemini Live) and execution (UnifiedToolRegistry).
"""

import time
import json
import uuid
import logging
import asyncio
from dataclasses import dataclass, field
from typing import Dict, Any, List, Optional

from core.state_database import state_db
from core.skills_engine import skills_engine
from core.verifier import action_verifier
from core.device_registry import device_registry

logger = logging.getLogger("MAXWindowsAgent.HermesController")


@dataclass
class HermesRequest:
    """Explicit typed contract for requests submitted to Hermes."""
    user_text: str
    request_id: str = field(default_factory=lambda: f"req_{int(time.time()*1000)}_{uuid.uuid4().hex[:8]}")
    tool_name: Optional[str] = None
    arguments: Dict[str, Any] = field(default_factory=dict)
    conversation_id: Optional[str] = None
    session_id: Optional[str] = None
    source: str = "voice"
    current_screen_context: Optional[Dict[str, Any]] = None
    timestamp: float = field(default_factory=time.time)


@dataclass
class HermesResult:
    """Explicit typed contract for execution outcomes returned by Hermes."""
    request_id: str
    status: str  # "success", "error", "partial", "timeout"
    response: Dict[str, Any]
    user_message: str
    steps: List[Dict[str, Any]] = field(default_factory=list)
    tools_used: List[str] = field(default_factory=list)
    verification_status: str = "VERIFIED"  # "VERIFIED", "PARTIALLY_VERIFIED", "UNVERIFIED"
    recovery_attempts: int = 0
    memories_used: List[Dict[str, Any]] = field(default_factory=list)
    skills_used: List[str] = field(default_factory=list)
    learning_proposal: Optional[Dict[str, Any]] = None
    duration_ms: int = 0
    error: Optional[str] = None


class HermesController:
    """
    Central Controller for Hermes Primary Execution Authority.
    Determines execution strategy, pre-loads memory and skills,
    orchestrates multi-step plans, coordinates verification, and executes bounded recovery.
    """
    _instance: Optional['HermesController'] = None

    # Strict classification of fast-path vs Hermes-required operations
    FAST_PATH_ALLOWED = frozenset({
        "get_connected_devices",
        "get_device_status",
        "list_connected_devices",
        "get_battery_status",
        "get_volume",
        "get_brightness",
        "get_system_info",
        "get_cursor_position",
        "get_weather",
        "get_active_window"
    })

    MAX_RECOVERY_ATTEMPTS = 3

    def __init__(self):
        self._is_active = True

    @classmethod
    def get_instance(cls) -> 'HermesController':
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def is_fast_path(self, tool_name: Optional[str], arguments: Dict[str, Any]) -> bool:
        """Determines if an action is a read-only fast-path query."""
        if not tool_name:
            return False
        return tool_name.lower().strip() in self.FAST_PATH_ALLOWED

    async def handle_request(
        self,
        request: HermesRequest,
        tool_registry = None
    ) -> HermesResult:
        """
        Primary entry point for Hermes execution authority.
        """
        start_time = time.time()
        req_id = request.request_id
        tool_name = request.tool_name or "execute_goal"
        args = request.arguments or {}
        goal_text = request.user_text or str(args.get("goal") or tool_name)

        if tool_registry is None:
            from core.unified_tool_registry import unified_tool_registry
            tool_registry = unified_tool_registry

        # 1. Structured Logging: [HERMES_REQUEST]
        logger.info(f"[HERMES_REQUEST] id={req_id} tool={tool_name} goal='{goal_text[:80]}'")

        # 2. Fast-Path Bypass for read-only instant queries
        if self.is_fast_path(tool_name, args):
            logger.info(f"[HERMES_FAST_PATH] Executing read-only query '{tool_name}' directly.")
            try:
                raw_res = await tool_registry.execute_async(tool_name, args)
                dur_ms = int((time.time() - start_time) * 1000)
                msg = raw_res.get("message") or f"Executed {tool_name} successfully."
                return HermesResult(
                    request_id=req_id,
                    status="success" if raw_res.get("status") != "error" else "error",
                    response=raw_res,
                    user_message=msg,
                    tools_used=[tool_name],
                    verification_status="VERIFIED",
                    duration_ms=dur_ms
                )
            except Exception as e:
                dur_ms = int((time.time() - start_time) * 1000)
                return HermesResult(
                    request_id=req_id,
                    status="error",
                    response={"status": "error", "message": str(e)},
                    user_message=f"Failed to query {tool_name}: {str(e)}",
                    tools_used=[tool_name],
                    verification_status="UNVERIFIED",
                    duration_ms=dur_ms,
                    error=str(e)
                )

        # 3. Hermes Autonomous Authority: Memory Retrieval
        memories = self._retrieve_memories(goal_text)
        if memories:
            logger.info(f"[HERMES_MEMORY] Retrieved {len(memories)} relevant facts: {[m['key'] for m in memories]}")

        # 4. Hermes Autonomous Authority: Skill Retrieval
        skills = self._retrieve_skills(goal_text, tool_name)
        if skills:
            logger.info(f"[HERMES_SKILL] Loaded {len(skills)} matching skill suites: {skills}")

        # Check if the goal/tool targets Developer (Tier 4) or Hybrid (Tier 5) or explicit Task Authority tools
        from core.task_classifier import task_classifier, TaskTier
        classification = task_classifier.classify(goal_text)
        tier_val = getattr(classification.tier, "value", classification.tier)
        is_tier_4_5 = tier_val in (TaskTier.TIER_4_DEVELOPER.value, TaskTier.TIER_5_HYBRID.value, TaskTier.TIER_4_DEVELOPER, TaskTier.TIER_5_HYBRID)
        if tool_name in ("developer_task", "execute_task", "execute_goal") or is_tier_4_5:
            logger.info(f"[HERMES_TASK_AUTHORITY] Routing Tier {tier_val} goal '{goal_text[:60]}' to MAXTaskAuthority.")
            from orchestrator.max_task_authority import max_task_authority
            auth_res = await max_task_authority.submit_user_intent(
                user_intent=goal_text,
                context={"source": request.source, "request_id": req_id, "tool_name": tool_name},
                source=request.source,
                async_execution=False
            )
            total_dur_ms = int((time.time() - start_time) * 1000)
            err_msg = None
            if auth_res.get("error"):
                err_msg = auth_res["error"].get("message") if isinstance(auth_res["error"], dict) else str(auth_res["error"])

            return HermesResult(
                request_id=req_id,
                status=auth_res.get("status", "success"),
                response=auth_res,
                user_message=auth_res.get("message") or auth_res.get("response", ""),
                tools_used=[f"task_authority:{auth_res.get('worker', 'antigravity')}"],
                verification_status="VERIFIED" if auth_res.get("verified_success") else ("UNVERIFIED" if auth_res.get("status") == "error" else "PARTIALLY_VERIFIED"),
                duration_ms=total_dur_ms,
                error=err_msg
            )

        # 5. Planning & Execution Strategy Selection
        is_compound = (
            tool_name == "execute_goal" or
            " and " in goal_text.lower() or
            " then " in goal_text.lower() or
            " lo " in goal_text.lower() and (" message " in goal_text.lower() or " search " in goal_text.lower())
        )

        steps_executed = []
        tools_used = []
        recovery_attempts = 0
        final_response = {}
        final_message = ""
        verification_status = "VERIFIED"

        if is_compound or tool_name == "execute_goal":
            # Multi-turn reasoning via HermesAgentLoop
            logger.info(f"[HERMES_PLAN] Initiating multi-turn reasoning for compound goal.")
            from core.hermes_loop import hermes_loop
            loop_res = await hermes_loop.execute_goal_loop(
                goal=goal_text,
                context={
                    "request_id": req_id,
                    "memories": memories,
                    "skills": skills,
                    "screen_context": request.current_screen_context
                },
                tool_registry=tool_registry
            )
            final_response = loop_res
            final_message = loop_res.get("final_answer", "")
            steps_executed = loop_res.get("steps", [])
            tools_used = [s.get("tool") for s in steps_executed if s.get("tool")]
            learning_proposal = loop_res.get("learning_proposal")
        else:
            # Single action with verification & bounded recovery
            logger.info(f"[HERMES_TOOL] Executing action '{tool_name}' with verification.")
            tools_used.append(tool_name)
            current_args = dict(args)
            success = False
            last_err = None

            for attempt in range(1, self.MAX_RECOVERY_ATTEMPTS + 1):
                try:
                    tool_res = await tool_registry.execute_async(tool_name, current_args)
                    step_entry = {
                        "attempt": attempt,
                        "tool": tool_name,
                        "arguments": current_args,
                        "result": tool_res
                    }
                    steps_executed.append(step_entry)
                    logger.info(f"[HERMES_RESULT] attempt={attempt} status={tool_res.get('status')}")

                    # Action Verification
                    is_verified, v_status = self._verify_action(tool_name, current_args, tool_res)
                    verification_status = v_status
                    logger.info(f"[HERMES_VERIFY] status={v_status} verified={is_verified}")

                    if tool_res.get("status") != "error" and is_verified:
                        success = True
                        final_response = tool_res
                        final_message = tool_res.get("message") or f"Executed {tool_name} successfully."
                        break
                    else:
                        recovery_attempts += 1
                        last_err = tool_res.get("message") or "Action failed verification"
                        logger.warning(f"[HERMES_RECOVERY] Attempt #{attempt} failed ({last_err}). Adapting strategy...")
                        current_args = self._adapt_strategy(tool_name, current_args, attempt)

                except Exception as ex:
                    recovery_attempts += 1
                    last_err = str(ex)
                    logger.error(f"[HERMES_RECOVERY] Attempt #{attempt} raised exception: {ex}")
                    current_args = self._adapt_strategy(tool_name, current_args, attempt)

            if not success:
                final_response = {"status": "error", "message": last_err or "Action failed after recovery attempts"}
                final_message = f"I was unable to complete {tool_name}: {last_err}"
                verification_status = "UNVERIFIED"

            learning_proposal = None

        total_dur_ms = int((time.time() - start_time) * 1000)
        logger.info(f"[HERMES_COMPLETE] id={req_id} status={final_response.get('status', 'success')} duration={total_dur_ms}ms")

        return HermesResult(
            request_id=req_id,
            status=final_response.get("status", "success"),
            response=final_response,
            user_message=final_message,
            steps=steps_executed,
            tools_used=tools_used,
            verification_status=verification_status,
            recovery_attempts=recovery_attempts,
            memories_used=memories,
            skills_used=skills,
            learning_proposal=learning_proposal,
            duration_ms=total_dur_ms,
            error=final_response.get("error")
        )

    def _retrieve_memories(self, query: str) -> List[Dict[str, Any]]:
        """Searches persistent SQLite FTS5 database for relevant facts."""
        try:
            res = state_db.recall_memory(query, limit=5)
            results_dict = res.get("results", {})
            return [{"key": k, "value": v} for k, v in results_dict.items()]
        except Exception as e:
            logger.debug(f"Memory retrieval notice: {e}")
            return []

    def _retrieve_skills(self, query: str, tool_name: str) -> List[str]:
        """Discovers matching skills from SkillsEngine."""
        matching = []
        try:
            res = skills_engine.list_skills()
            skills = res.get("skills", [])
            q_lower = (query + " " + tool_name).lower()
            for s in skills:
                name = s.get("name", "").lower()
                desc = s.get("description", "").lower()
                if any(word in name or word in desc for word in q_lower.split() if len(word) > 3):
                    matching.append(s.get("name"))
        except Exception as e:
            logger.debug(f"Skill retrieval notice: {e}")
        return matching[:3]

    def _verify_action(
        self,
        tool_name: str,
        args: Dict[str, Any],
        result: Dict[str, Any]
    ) -> (bool, str):
        """Verifies action execution against real system state to prevent hallucination."""
        if result.get("status") == "error":
            return False, "UNVERIFIED"

        name = tool_name.lower().strip()

        # Remote Android Actions
        if name in ("execute_device_action", "execute_multi_device_action"):
            if result.get("status") == "success" or result.get("success") is True:
                return True, "VERIFIED"
            return False, "UNVERIFIED"

        # App Control
        if name == "open_application":
            app_name = args.get("app_name", "")
            verified = action_verifier.verify_app_running_sync(app_name) if app_name else True
            return verified, "VERIFIED" if verified else "PARTIALLY_VERIFIED"

        if name == "close_application":
            app_name = args.get("app_name", "")
            verified = action_verifier.verify_app_closed_sync(app_name) if app_name else True
            return verified, "VERIFIED" if verified else "PARTIALLY_VERIFIED"

        # Filesystem Operations
        if name in ("create_file", "create_text_file", "write_file"):
            p = result.get("path") or args.get("path") or args.get("file_path")
            content = args.get("content") or args.get("text", "")
            verified = action_verifier.verify_file_created_sync(p, content)
            return verified, "VERIFIED" if verified else "UNVERIFIED"

        if name in ("create_folder", "create_directory"):
            p = result.get("path") or args.get("path") or args.get("folder_path")
            verified = action_verifier.verify_folder_created_sync(p)
            return verified, "VERIFIED" if verified else "UNVERIFIED"

        if name in ("delete_file", "delete_folder", "delete_directory"):
            p = args.get("path") or args.get("file_path") or args.get("folder_path")
            verified = action_verifier.verify_path_deleted_sync(p)
            return verified, "VERIFIED" if verified else "UNVERIFIED"

        if name in ("move_file", "rename_file"):
            src = args.get("source")
            dst = result.get("path") or args.get("destination") or args.get("new_name")
            verified = action_verifier.verify_file_moved_sync(src, dst)
            return verified, "VERIFIED" if verified else "UNVERIFIED"

        if name == "copy_file":
            src = args.get("source")
            dst = args.get("destination")
            verified = action_verifier.verify_file_copied_sync(src, dst)
            return verified, "VERIFIED" if verified else "UNVERIFIED"

        if name in ("read_file", "read_text_file"):
            verified = result.get("status") == "success" and "content" in result
            return verified, "VERIFIED" if verified else "UNVERIFIED"

        # Clipboard
        if name in ("clipboard_copy", "paste_text"):
            return True, "VERIFIED"

        # Default standard verification
        return True, "VERIFIED"

    def _adapt_strategy(self, tool_name: str, args: Dict[str, Any], attempt: int) -> Dict[str, Any]:
        """Adaptive strategy modification upon failure."""
        adapted = dict(args)
        name = tool_name.lower().strip()

        if name == "click_ui_element":
            target = args.get("target")
            # If target is non-string or int, coerce to str or search in name/element/label
            if not isinstance(target, str):
                if "name" in args and isinstance(args["name"], str):
                    adapted["target"] = args["name"]
                elif "element" in args and isinstance(args["element"], str):
                    adapted["target"] = args["element"]
                else:
                    adapted["target"] = str(target) if target is not None else ""

            # Attempt 1: Simplify target by stripping redundant suffixes ('button', 'menu', 'icon', 'tab')
            if attempt == 1 and isinstance(adapted.get("target"), str):
                t = adapted["target"].strip()
                for suffix in (" button", " menu", " icon", " tab", " link"):
                    if t.lower().endswith(suffix):
                        adapted["target"] = t[:-len(suffix)].strip()
                        break

            # Attempt 2: Scope to active foreground window title if not specified
            if attempt == 2 and not adapted.get("window_title"):
                try:
                    from core.ui_automation import uia_engine
                    state = uia_engine.get_desktop_state(max_elements=5)
                    act_title = state.get("active_window", {}).get("title")
                    if act_title:
                        adapted["window_title"] = act_title
                except Exception:
                    pass

        elif name == "open_application":
            app = str(args.get("app_name", ""))
            if attempt == 1 and not app.endswith(".exe"):
                adapted["app_name"] = f"{app}.exe"
            elif attempt == 2:
                adapted["app_name"] = app.split("\\")[-1].lower()

        elif name == "create_file":
            path = str(args.get("path", ""))
            if attempt == 1 and not (path.startswith("/") or path.startswith("C:") or path.startswith("Desktop")):
                adapted["path"] = f"Desktop/{path}"

        elif name == "execute_device_action":
            # Increase timeout on retry
            adapted["timeout_ms"] = min(15000, int(args.get("timeout_ms", 5000)) + 3000)

        return adapted


# Singleton instance
hermes_controller = HermesController.get_instance()
