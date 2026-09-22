"""
MAX AI — State Verifier for Windows Skills
Validates post-condition states using ScreenPerceptionCoordinator and AdaptiveUiGrounder.
"""

import time
import asyncio
import logging
from dataclasses import dataclass
from typing import Optional

from core.skills.compiler import BoundSkillStep
from core.computer_use.screen_perception import ScreenPerceptionCoordinator
from core.skills.runtime.adaptive_grounder import AdaptiveUiGrounder

logger = logging.getLogger("MAX.Skills.StateVerifier")


@dataclass
class StepVerificationResult:
    verified: bool
    message: str = ""
    elapsed_ms: int = 0


class SkillStateVerifier:
    """Performs bounded state verification on Windows."""

    def __init__(
        self,
        perception: Optional[ScreenPerceptionCoordinator] = None,
        grounder: Optional[AdaptiveUiGrounder] = None
    ):
        self.perception = perception or ScreenPerceptionCoordinator()
        self.grounder = grounder or AdaptiveUiGrounder()

    async def verify_step(self, step: BoundSkillStep) -> StepVerificationResult:
        verification = step.verification
        if not verification or verification.type.upper() == "NONE":
            return StepVerificationResult(True, "No explicit verification declared")

        start_time = time.time()
        v_type = verification.type.upper()
        expected = verification.expected_value or ""
        timeout_s = (verification.timeout_ms or 3000) / 1000.0

        logger.info(f"Verifying Windows state '{v_type}' for '{step.step_id}' (expected='{expected}', timeout={timeout_s}s)")
        verified = False

        while (time.time() - start_time) < timeout_s:
            active_map = self.perception.observe(include_ocr=False) if self.perception else None
            if not active_map:
                await asyncio.sleep(0.15)
                continue

            if v_type == "APP_FOREGROUND":
                proc_name = (active_map.active_process or "").lower()
                win_name = (active_map.active_window or "").lower()
                if expected.lower() in proc_name or expected.lower() in win_name:
                    verified = True
                    break
            elif v_type in ("ELEMENT_VISIBLE", "TARGET_VISIBLE"):
                target = expected or step.semantic_target
                grounded = self.grounder.ground_target(target, ui_map=active_map)
                if grounded.success:
                    verified = True
                    break
            elif v_type in ("TEXT_MATCH", "TEXT_ENTERED"):
                found_text = any(expected.lower() in (e.name or "").lower() for e in active_map.elements)
                if found_text:
                    verified = True
                    break
            elif v_type == "ELEMENT_GONE":
                target = expected or step.semantic_target
                grounded = self.grounder.ground_target(target, ui_map=active_map)
                if not grounded.success:
                    verified = True
                    break

            await asyncio.sleep(0.15)

        elapsed_ms = int((time.time() - start_time) * 1000)
        msg = f"✓ Verified state '{v_type}' in {elapsed_ms}ms" if verified else f"✗ Verification failed for '{v_type}' (expected: '{expected}') after {elapsed_ms}ms"
        return StepVerificationResult(verified=verified, message=msg, elapsed_ms=elapsed_ms)
