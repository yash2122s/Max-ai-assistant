"""
MAX AI — Skill Step Executor for Windows Agent
Dispatches compiled skill steps to native Windows UIAutomation and input tools.
"""

import logging
import asyncio
from dataclasses import dataclass
from typing import Dict, Any, Optional

from core.skills.compiler import BoundSkillStep
from core.skills.runtime.adaptive_grounder import AdaptiveUiGrounder, GroundedTarget
from core.computer_use.models import ComputerUseAction, ActionType
from core.computer_use.action_executor import ActionExecutor
from core.computer_use.screen_perception import ScreenPerceptionCoordinator

logger = logging.getLogger("MAX.Skills.StepExecutor")


@dataclass
class StepExecutionResult:
    success: bool
    step_id: str
    message: str = ""
    grounded_target: Optional[GroundedTarget] = None


class SkillStepExecutor:
    """Executes bound steps on Windows using ActionExecutor or UIAutomation."""

    def __init__(
        self,
        grounder: Optional[AdaptiveUiGrounder] = None,
        action_executor: Optional[ActionExecutor] = None,
        perception: Optional[ScreenPerceptionCoordinator] = None
    ):
        self.grounder = grounder or AdaptiveUiGrounder()
        self.action_executor = action_executor or ActionExecutor()
        self.perception = perception or ScreenPerceptionCoordinator()

    async def execute_step(self, step: BoundSkillStep) -> StepExecutionResult:
        logger.info(f"Executing Windows step '{step.step_id}': action='{step.canonical_action}', target='{step.semantic_target}'")
        action = step.canonical_action.lower()

        if action == "app.open":
            return await self._execute_app_open(step)
        elif action == "ui.click":
            return await self._execute_click(step)
        elif action == "ui.type_text":
            return await self._execute_type_text(step)
        elif action == "ui.press_key":
            return await self._execute_press_key(step)
        elif action == "ui.wait":
            return await self._execute_wait(step)
        else:
            return await self._execute_click(step)

    async def _execute_app_open(self, step: BoundSkillStep) -> StepExecutionResult:
        process_name = step.arguments.get("process_name") or step.semantic_target
        try:
            import subprocess
            subprocess.Popen(process_name, shell=True)
            await asyncio.sleep(1.0)
            return StepExecutionResult(True, step.step_id, f"Launched application '{process_name}'")
        except Exception as e:
            return StepExecutionResult(False, step.step_id, f"Failed to open '{process_name}': {e}")

    async def _execute_click(self, step: BoundSkillStep) -> StepExecutionResult:
        ui_map = self.perception.observe(include_ocr=False) if self.perception else None
        grounded = self.grounder.ground_target(step.semantic_target, step.arguments, ui_map)

        if not grounded.success or not grounded.element:
            return StepExecutionResult(False, step.step_id, f"Grounding failed: {grounded.reason}", grounded)

        action = ComputerUseAction(
            action_type=ActionType.CLICK,
            target_query=step.semantic_target,
            target_ref=grounded.element.ref,
            x=grounded.center_x,
            y=grounded.center_y
        )
        res = self.action_executor.execute_action(action, grounded.element)
        success = (res.get("status") == "success")
        return StepExecutionResult(success, step.step_id, res.get("message", "Clicked target"), grounded)

    async def _execute_type_text(self, step: BoundSkillStep) -> StepExecutionResult:
        text = str(step.arguments.get("text", ""))
        press_enter = bool(step.arguments.get("press_enter", False))

        ui_map = self.perception.observe(include_ocr=False) if self.perception else None
        grounded = self.grounder.ground_target(step.semantic_target, step.arguments, ui_map)

        action = ComputerUseAction(
            action_type=ActionType.TYPE_TEXT,
            target_query=step.semantic_target,
            text=text,
            press_enter=press_enter,
            x=grounded.center_x if grounded.success else None,
            y=grounded.center_y if grounded.success else None
        )
        target_el = grounded.element if grounded.success else None
        res = self.action_executor.execute_action(action, target_el)
        success = (res.get("status") == "success")
        return StepExecutionResult(success, step.step_id, res.get("message", "Typed text"), grounded)

    async def _execute_press_key(self, step: BoundSkillStep) -> StepExecutionResult:
        key = str(step.arguments.get("key", "enter")).lower()
        action = ComputerUseAction(action_type=ActionType.PRESS_KEY, key=key)
        res = self.action_executor.execute_action(action, None)
        return StepExecutionResult(res.get("status") == "success", step.step_id, f"Pressed key '{key}'")

    async def _execute_wait(self, step: BoundSkillStep) -> StepExecutionResult:
        duration = float(step.arguments.get("duration_ms", 1000)) / 1000.0
        await asyncio.sleep(duration)
        return StepExecutionResult(True, step.step_id, f"Waited {duration}s")
