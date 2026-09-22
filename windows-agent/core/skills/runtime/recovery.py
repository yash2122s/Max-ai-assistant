"""
MAX AI — Skill Recovery Handler for Windows Agent
Provides non-destructive recovery, re-grounding, and modal dismissal.
"""

import asyncio
import logging
from dataclasses import dataclass
from typing import Optional

from core.skills.compiler import BoundSkillStep
from core.computer_use.screen_perception import ScreenPerceptionCoordinator
from core.skills.runtime.adaptive_grounder import AdaptiveUiGrounder
from core.computer_use.models import ComputerUseAction, ActionType
from core.computer_use.action_executor import ActionExecutor

logger = logging.getLogger("MAX.Skills.Recovery")


@dataclass
class RecoveryResult:
    success: bool
    action_taken: str = ""
    message: str = ""


class SkillRecovery:
    """Handles recovery from step execution or verification failures."""

    def __init__(
        self,
        perception: Optional[ScreenPerceptionCoordinator] = None,
        grounder: Optional[AdaptiveUiGrounder] = None,
        action_executor: Optional[ActionExecutor] = None
    ):
        self.perception = perception or ScreenPerceptionCoordinator()
        self.grounder = grounder or AdaptiveUiGrounder()
        self.action_executor = action_executor or ActionExecutor()

    async def attempt_recovery(self, step: BoundSkillStep, attempt_number: int) -> RecoveryResult:
        logger.warning(f"Attempting recovery on Windows for step '{step.step_id}' (attempt #{attempt_number})")

        # 1. Check for dismissible modal buttons
        dismissed = await self._dismiss_modal()
        if dismissed:
            await asyncio.sleep(0.5)
            return RecoveryResult(True, "DISMISSED_MODAL", "Dismissed blocking modal dialog")

        # 2. Re-observation & re-grounding
        await asyncio.sleep(0.3)
        ui_map = self.perception.observe(include_ocr=False) if self.perception else None
        fresh_ground = self.grounder.ground_target(step.semantic_target, step.arguments, ui_map)
        if fresh_ground.success:
            return RecoveryResult(True, "RE_GROUNDED", "Re-grounded target on refreshed Windows UIMap")

        # 3. Escape key if stuck
        if attempt_number >= 2:
            action = ComputerUseAction(action_type=ActionType.PRESS_KEY, key="escape")
            self.action_executor.execute_action(action, None)
            await asyncio.sleep(0.5)
            return RecoveryResult(True, "SENT_ESCAPE", "Sent Escape key to clear transient state")

        return RecoveryResult(False, "NONE", "No safe recovery resolved")

    async def _dismiss_modal(self) -> bool:
        ui_map = self.perception.observe(include_ocr=False) if self.perception else None
        if not ui_map:
            return False
        common_labels = ["cancel", "close", "dismiss", "ok", "got it", "not now"]

        for label in common_labels:
            grounded = self.grounder.ground_target(label, ui_map=ui_map)
            if grounded.success and grounded.element:
                action = ComputerUseAction(action_type=ActionType.CLICK, target_ref=grounded.element.ref)
                self.action_executor.execute_action(action, grounded.element)
                return True
        return False
