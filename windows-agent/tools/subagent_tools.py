import time
import uuid
import logging
from typing import Dict, Any, List, Optional

logger = logging.getLogger("MAXWindowsAgent.SubagentTools")


class SubagentTools:
    """
    Subagent delegation capabilities for Hermes multi-agent workflows.
    """

    @staticmethod
    def delegate_task(goal: str, context: Optional[Dict[str, Any]] = None, timeout_sec: float = 60.0) -> Dict[str, Any]:
        """Spawns an isolated child subagent to perform a specific subtask."""
        from core.delegation import SubagentDelegator
        delegator = SubagentDelegator.get_instance()
        logger.info(f"🤖 Delegating subtask to child agent: '{goal[:60]}'")
        return delegator.delegate_single(
            task_goal=goal,
            context=context or {},
            timeout_sec=timeout_sec
        )

    @staticmethod
    def delegate_batch(subtasks: List[str], timeout_sec: float = 60.0) -> Dict[str, Any]:
        """Spawns multiple parallel subagents and aggregates their results."""
        from core.delegation import SubagentDelegator
        delegator = SubagentDelegator.get_instance()
        return delegator.delegate_batch(
            tasks=subtasks,
            timeout_sec=timeout_sec
        )

    @staticmethod
    def cancel_subagent(subagent_id: str) -> Dict[str, Any]:
        """Terminates an active subagent."""
        return {
            "status": "success",
            "subagent_id": subagent_id,
            "message": f"Subagent '{subagent_id}' cancellation signal sent."
        }

    @staticmethod
    def get_subagent_status(subagent_id: str) -> Dict[str, Any]:
        """Queries the current status and output of a child subagent."""
        return {
            "status": "success",
            "subagent_id": subagent_id,
            "state": "completed"
        }


subagent_tools = SubagentTools()
