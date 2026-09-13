"""
MAX AI — AgentExecutionLoop & Autonomous Multi-Turn Reasoning
Executes multi-step autonomous goals with intermediate tool execution,
repetition loop guards, SQLite turn checkpointing, and error classification.
"""

import time
import json
import logging
import asyncio
from typing import Dict, Any, List, Optional, Callable

from core.provider_manager import provider_manager
from context.max_compressor import max_compressor
from core.state_database import state_db

logger = logging.getLogger("MAX.ExecutionLoop")


class AgentExecutionLoop:
    """
    Autonomous multi-turn reasoning engine.
    """

    def __init__(self, max_iterations: int = 10, timeout_sec: float = 90.0):
        self.max_iterations = max_iterations
        self.timeout_sec = timeout_sec

    async def execute_goal_loop(
        self,
        goal: str,
        system_instruction: Optional[str] = None,
        context: Optional[Dict[str, Any]] = None,
        tool_registry = None
    ) -> Dict[str, Any]:
        """
        Executes multi-turn reasoning until completion or max iterations.
        """
        start_time = time.time()
        logger.info(f"🚀 Starting MAX Multi-Turn Reasoning for: '{goal}'")

        # 0. Pre-Routing Classification
        from core.intent_router import intent_router, IntentCategory
        route_decision = intent_router.classify(goal)
        logger.info(
            f"[MAX_ROUTE] input=\"{goal}\" intent={route_decision.intent.value} "
            f"tool={route_decision.tool or 'NONE'} reason=\"{route_decision.reason}\""
        )
        if route_decision.intent == IntentCategory.CALCULATION and route_decision.direct_answer:
            dur_ms = int((time.time() - start_time) * 1000)
            return {
                "status": "success",
                "final_answer": route_decision.direct_answer,
                "steps": [],
                "tools_used": [],
                "duration_ms": dur_ms
            }

        if tool_registry is None:
            from core.unified_tool_registry import unified_tool_registry
            tool_registry = unified_tool_registry

        # 1. Initialize Messages and Tool Catalog
        memories_summary = state_db.get_summary_text()
        if hasattr(tool_registry, "get_declarations"):
            declarations = tool_registry.get_declarations()
        elif hasattr(tool_registry, "_declarations"):
            declarations = tool_registry._declarations
        else:
            declarations = []

        tools_summary = []
        for decl in declarations:
            name = decl.get("name", "")
            desc = decl.get("description", "")
            props = list(decl.get("parameters", {}).get("properties", {}).keys())
            tools_summary.append(f"- {name}({', '.join(props)}): {desc}")

        tools_catalog_text = "\n".join(tools_summary)

        sys_prompt = system_instruction or (
            "You are MAX Agent Engine, the primary autonomous execution engine of MAX AI.\n"
            "You have full access to Windows automation, browser, coding, skills, MCP, and Android multi-device ecosystem.\n"
            "When given a user goal, you MUST actively execute each action step-by-step using available tools.\n\n"
            "RULES:\n"
            "1. For each step of the goal, invoke the appropriate tool (e.g. open_website, search_whatsapp_contact, create_text_file, click_ui_element, type_ui_element, send_whatsapp_message).\n"
            "2. Do NOT respond with conversational text describing what to do when an action tool can be executed.\n"
            "3. When all steps are completely executed and verified, summarize what was completed in plain text.\n\n"
            f"<AVAILABLE_TOOLS>\n{tools_catalog_text}\n</AVAILABLE_TOOLS>\n\n"
            f"<PERMANENT_MEMORY>\n{memories_summary}\n</PERMANENT_MEMORY>"
        )

        messages = [
            {"role": "system", "content": sys_prompt},
            {"role": "user", "content": goal}
        ]

        iteration = 0
        call_history: List[str] = []
        final_answer = ""
        executed_steps: List[Dict[str, Any]] = []

        while iteration < self.max_iterations:
            iteration += 1
            elapsed = time.time() - start_time
            if elapsed > self.timeout_sec:
                logger.warning(f"⚠️ Multi-turn reasoning reached timeout ({self.timeout_sec}s)")
                break

            # 2. Compress context if needed
            messages, _ = max_compressor.compress_if_needed(messages)

            # 3. Request LLM turn completion with full tool declarations
            resp = provider_manager.chat_completion(messages=messages, tools=declarations)
            llm_text = resp.get("content", "")

            # 4. Check if LLM emitted native tool calls or tool calls in text
            tool_calls = resp.get("tool_calls", [])
            if not tool_calls:
                tool_calls = self._extract_tool_calls_from_text(llm_text)

            if not tool_calls:
                action_keywords = ["create", "open", "write", "delete", "remove", "move", "copy", "rename", "click", "send", "type", "set", "save", "run", "close", "search", "change", "toggle"]
                is_action_goal = any(kw in goal.lower() for kw in action_keywords)
                
                # If it's an action goal and NO tools have been executed yet, enforce the Execution Gate!
                if is_action_goal and len(executed_steps) == 0 and iteration < self.max_iterations:
                    logger.warning(f"⚠️ Execution Gate: LLM emitted text response without executing action tools for goal '{goal}'. Enforcing tool call.")
                    messages.append({"role": "assistant", "content": llm_text})
                    messages.append({
                        "role": "user",
                        "content": (
                            f"[Execution Gate Notice]: The user requested an action ('{goal}'). "
                            "You must NOT merely explain or claim you completed it. "
                            "You MUST execute the action using the appropriate tool from <AVAILABLE_TOOLS> (e.g. create_text_file, create_file, open_application, click_ui_element, etc.)."
                        )
                    })
                    continue

                # Agent concluded with final response
                final_answer = llm_text
                messages.append({"role": "assistant", "content": final_answer})
                break

            for tc in tool_calls:
                tool_name = tc.get("name")
                tool_args = tc.get("arguments", {})
                if not tool_name:
                    continue

                # 5. Repetition / Infinite Loop Guard
                call_sig = f"{tool_name}:{json.dumps(tool_args, sort_keys=True)}"
                if call_history.count(call_sig) >= 2:
                    logger.warning(f"🛑 Infinite loop detected for tool call '{call_sig}'. Breaking loop.")
                    messages.append({
                        "role": "system",
                        "content": f"[System Warning]: You have called '{tool_name}' with identical arguments multiple times. Please provide your final answer or try an alternative approach."
                    })
                    continue

                call_history.append(call_sig)

                # 6. Execute tool turn via UnifiedToolRegistry
                logger.info(f"⚙️ MAX Turn #{iteration} -> Executing '{tool_name}' with {tool_args}")
                from server.event_bus import event_bus
                from protocol.event_types import EventType
                event_bus.publish(EventType.TOOL_REQUESTED, {
                    "tool": tool_name,
                    "action": tool_name,
                    "args": tool_args
                })

                tool_start = time.time()
                try:
                    tool_result = await tool_registry.execute_async(tool_name, tool_args)
                except Exception as e:
                    tool_result = {"status": "error", "message": f"Tool execution failed: {str(e)}"}

                tool_dur_ms = int((time.time() - tool_start) * 1000)

                event_bus.publish(EventType.TOOL_COMPLETED, {
                    "tool": tool_name,
                    "action": tool_name,
                    "result": tool_result
                })

                # 7. Record turn step
                step_record = {
                    "iteration": iteration,
                    "tool": tool_name,
                    "arguments": tool_args,
                    "result": tool_result,
                    "duration_ms": tool_dur_ms
                }
                executed_steps.append(step_record)

                # 8. Feed result back into conversation context
                messages.append({
                    "role": "assistant",
                    "content": f"Calling tool {tool_name}({json.dumps(tool_args)})"
                })
                messages.append({
                    "role": "user",
                    "content": f"[Tool Result for {tool_name}]: {json.dumps(tool_result, default=str)}"
                })

                # Checkpoint turn to SQLite state DB
                state_db.log_audit(
                    agent="max_execution_loop",
                    tool=tool_name,
                    arguments=tool_args,
                    status=tool_result.get("status", "success"),
                    result_summary=str(tool_result)[:300],
                    duration_ms=tool_dur_ms
                )

        if not final_answer:
            final_answer = llm_text or f"Task processed in {iteration} steps."

        # Ground-truth status determination: Action goals must execute at least one tool
        action_keywords = ["create", "open", "write", "delete", "remove", "move", "copy", "rename", "click", "send", "type", "set", "save", "run", "close", "search"]
        is_action_goal = any(kw in goal.lower() for kw in action_keywords)
        
        has_errors = any(s.get("result", {}).get("status") == "error" for s in executed_steps)
        if is_action_goal and len(executed_steps) == 0:
            final_status = "error"
            final_answer = "Failed: No action tools were executed for the requested task."
        elif has_errors and not any(s.get("result", {}).get("status") == "success" for s in executed_steps):
            final_status = "error"
        else:
            final_status = "success"

        total_time = time.time() - start_time
        logger.info(f"✅ MAX goal completed in {iteration} turns ({total_time:.2f}s) status={final_status}")

        # 9. Closed Learning Loop: Evaluate trajectory for autonomous skill synthesis
        learning_proposal = None
        try:
            from core.learning_loop import TrajectoryEvaluator
            should_learn, reason = TrajectoryEvaluator.is_worth_learning(
                goal=goal,
                steps=executed_steps,
                final_answer=final_answer,
                status="success"
            )
            if should_learn:
                logger.info(f"🧠 Learning Loop: Trajectory qualified for skill synthesis ({reason})")
                learning_proposal = {
                    "should_learn": True,
                    "reason": reason,
                    "goal": goal,
                    "step_count": len(executed_steps)
                }
        except Exception as le:
            logger.debug(f"Learning evaluation notice: {le}")

        return {
            "status": final_status,
            "goal": goal,
            "iterations": iteration,
            "final_answer": final_answer,
            "steps": executed_steps,
            "learning_proposal": learning_proposal,
            "duration_sec": total_time
        }

    def _extract_tool_calls_from_text(self, text: str) -> List[Dict[str, Any]]:
        """Extracts one or more tool calls from text (markdown JSON or Calling tool pattern)."""
        if not text:
            return []

        import re
        results = []

        # 1. Check for 'Calling tool <name>(<args>)' patterns
        pattern = r"Calling tool ([a-zA-Z0-9_]+)\((\{.*?\})\)"
        for match in re.finditer(pattern, text, re.DOTALL):
            name, args_str = match.group(1), match.group(2)
            try:
                args = json.loads(args_str)
                results.append({"name": name, "arguments": args})
            except Exception:
                pass

        if results:
            return results

        # 2. Check for ```json ... ``` blocks or raw json
        m = re.search(r"```json\s*(\{.*?\})\s*```", text, re.DOTALL)
        candidate = m.group(1) if m else text.strip()

        if candidate.startswith("{") and candidate.endswith("}"):
            try:
                data = json.loads(candidate)
                if "name" in data and ("arguments" in data or "parameters" in data):
                    results.append({
                        "name": data["name"],
                        "arguments": data.get("arguments") or data.get("parameters") or {}
                    })
                elif "tool" in data:
                    results.append({
                        "name": data["tool"],
                        "arguments": data.get("args") or data.get("arguments") or {}
                    })
            except Exception:
                pass

        return results


# Singleton instance
agent_execution_loop = AgentExecutionLoop()

# Backward-compatibility aliases
HermesAgentLoop = AgentExecutionLoop
hermes_loop = agent_execution_loop
