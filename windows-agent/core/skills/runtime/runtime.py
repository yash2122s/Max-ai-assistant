"""
MAX AI — Main Skill Runtime Coordinator for Windows Agent
Orchestrates input binding, step compilation, execution, bounded verification, and recovery.
"""

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Dict, Any, List, Optional

from core.skills.skill_definition import SkillDefinition, FailurePolicy
from core.skills.input_binder import SkillInputBinder
from core.skills.compiler import SkillCompiler, BoundSkillPlan
from core.skills.runtime.step_executor import SkillStepExecutor
from core.skills.runtime.state_verifier import SkillStateVerifier
from core.skills.runtime.recovery import SkillRecovery

logger = logging.getLogger("MAX.Skills.Runtime")


@dataclass
class SkillExecutionResult:
    success: bool
    skill_id: str
    total_steps: int
    completed_steps: List[str] = field(default_factory=list)
    failed_step_id: Optional[str] = None
    message: str = ""


class SkillRuntime:
    """End-to-end adaptive runtime orchestrator for Windows automation skills."""

    def __init__(
        self,
        step_executor: Optional[SkillStepExecutor] = None,
        verifier: Optional[SkillStateVerifier] = None,
        recovery: Optional[SkillRecovery] = None
    ):
        self.step_executor = step_executor or SkillStepExecutor()
        self.verifier = verifier or SkillStateVerifier()
        self.recovery = recovery or SkillRecovery()

    async def execute_skill(
        self,
        skill: SkillDefinition,
        provided_inputs: Optional[Dict[str, Any]] = None
    ) -> SkillExecutionResult:
        inputs = provided_inputs or {}
        logger.info(f"=== Starting Windows Skill Execution: '{skill.name}' ({skill.skill_id}) ===")

        # 1. Bind Inputs
        bind_res = SkillInputBinder.bind_inputs(skill, inputs)
        if not bind_res.success:
            err = f"Input binding failed: {bind_res.error_message}"
            logger.error(err)
            return SkillExecutionResult(
                success=False,
                skill_id=skill.skill_id,
                total_steps=len(skill.steps),
                message=err
            )

        # 2. Compile Bound Execution Plan
        plan: BoundSkillPlan = SkillCompiler.compile(
            skill=skill,
            bound_inputs=bind_res.bound_values,
            target_platform="windows"
        )
        logger.info(f"Compiled {len(plan.steps)} steps in order: {plan.execution_order}")

        completed_step_ids: List[str] = []
        step_map = {s.step_id: s for s in plan.steps}

        # 3. Execute Steps
        for step_id in plan.execution_order:
            step = step_map.get(step_id)
            if not step:
                continue

            unsatisfied = [d for d in step.depends_on if d not in completed_step_ids]
            if unsatisfied:
                err = f"Step '{step_id}' cannot run: unsatisfied dependencies {unsatisfied}"
                logger.error(err)
                return SkillExecutionResult(
                    success=False,
                    skill_id=skill.skill_id,
                    total_steps=len(plan.steps),
                    completed_steps=completed_step_ids,
                    failed_step_id=step_id,
                    message=err
                )

            max_retries = max(0, step.retry_policy.max_retries)
            attempt = 0
            step_passed = False
            last_err = ""

            while attempt <= max_retries and not step_passed:
                attempt += 1
                logger.info(f"Executing step '{step_id}' (attempt {attempt}/{max_retries + 1})")

                # A. Execute Physical Action
                exec_res = await self.step_executor.execute_step(step)
                if not exec_res.success:
                    last_err = f"Execution failed: {exec_res.message}"
                    logger.warning(f"Step '{step_id}' {last_err}")
                    if attempt <= max_retries:
                        await self.recovery.attempt_recovery(step, attempt)
                        await asyncio.sleep(max(0.3, step.retry_policy.delay_ms / 1000.0))
                    continue

                # B. Verify Post-Condition State
                ver_res = await self.verifier.verify_step(step)
                if ver_res.verified:
                    step_passed = True
                    logger.info(f"✓ Step '{step_id}' verified.")
                else:
                    last_err = f"Verification failed: {ver_res.message}"
                    logger.warning(f"Step '{step_id}' {last_err}")
                    if attempt <= max_retries:
                        await self.recovery.attempt_recovery(step, attempt)
                        await asyncio.sleep(max(0.3, step.retry_policy.delay_ms / 1000.0))

            if step_passed:
                completed_step_ids.append(step_id)
            else:
                logger.error(f"✗ Step '{step_id}' failed permanently after {attempt} attempts: {last_err}")
                if skill.failure_policy == FailurePolicy.ABORT:
                    return SkillExecutionResult(
                        success=False,
                        skill_id=skill.skill_id,
                        total_steps=len(plan.steps),
                        completed_steps=completed_step_ids,
                        failed_step_id=step_id,
                        message=f"Skill aborted at step '{step_id}': {last_err}"
                    )

        all_completed = (len(completed_step_ids) == len(plan.steps))
        msg = f"✓ Skill '{skill.name}' completed ({len(completed_step_ids)}/{len(plan.steps)} steps verified)." if all_completed else f"Skill finished with partial completion ({len(completed_step_ids)}/{len(plan.steps)})."
        return SkillExecutionResult(
            success=all_completed,
            skill_id=skill.skill_id,
            total_steps=len(plan.steps),
            completed_steps=completed_step_ids,
            message=msg
        )
