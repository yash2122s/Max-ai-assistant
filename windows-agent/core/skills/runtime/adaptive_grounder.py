"""
MAX AI — Adaptive UI Grounder for Windows Agent
Bridges abstract semantic targets to concrete interactive UIElements on Windows.
"""

import logging
from dataclasses import dataclass
from typing import Dict, Any, List, Optional

from core.computer_use.models import UIElement, UIMap
from core.computer_use.target_resolver import TargetResolver
from core.computer_use.ui_ref_cache import ui_ref_cache

logger = logging.getLogger("MAX.Skills.AdaptiveGrounder")


@dataclass
class GroundedTarget:
    success: bool
    element: Optional[UIElement] = None
    center_x: int = 0
    center_y: int = 0
    matched_text: str = ""
    resolution_method: str = "SEMANTIC"
    reason: str = ""


class AdaptiveUiGrounder:
    """Grounds semantic skill targets against active Windows UIMap."""

    def __init__(self, resolver: Optional[TargetResolver] = None):
        self.resolver = resolver or TargetResolver()

    def ground_target(
        self,
        semantic_target: str,
        arguments: Optional[Dict[str, Any]] = None,
        ui_map: Optional[UIMap] = None
    ) -> GroundedTarget:
        args = arguments or {}
        clean_target = semantic_target.strip()
        active_map = ui_map or ui_ref_cache.current_map

        if not active_map or not active_map.elements:
            return GroundedTarget(
                success=False,
                reason="No active Windows UIMap available in observation."
            )

        avoid_list = [str(a).lower() for a in args.get("avoid", []) if a]
        role = str(args.get("role", "")).lower()

        logger.info(f"Grounding Windows target '{clean_target}' (role='{role}', avoid={avoid_list})")

        # 1. Resolve via TargetResolver with multi-tier matching
        res = self.resolver.resolve(query=clean_target, ui_map=active_map)

        if res.status == "FOUND" and res.matched and res.matched.element:
            el = res.matched.element
            if self._is_avoided(el, avoid_list):
                logger.warning(f"Matched element '{el.name}' rejected by avoid filter: {avoid_list}")
            else:
                cx, cy = el.center.to_tuple() if el.center else (0, 0)
                return GroundedTarget(
                    success=True,
                    element=el,
                    center_x=int(cx),
                    center_y=int(cy),
                    matched_text=el.name or clean_target,
                    resolution_method=res.matched.strategy or "TARGET_RESOLVER",
                    reason=res.message
                )

        # 2. Direct filtered scan across active map
        for el in active_map.elements:
            if self._is_avoided(el, avoid_list):
                continue

            name_lower = (el.name or "").lower()
            auto_id_lower = (el.automation_id or "").lower()
            query_lower = clean_target.lower()

            if query_lower in name_lower or query_lower in auto_id_lower:
                if role and el.role.lower() != role and role not in ("row", "container"):
                    continue

                cx, cy = el.center.to_tuple() if el.center else (0, 0)
                return GroundedTarget(
                    success=True,
                    element=el,
                    center_x=int(cx),
                    center_y=int(cy),
                    matched_text=el.name or clean_target,
                    resolution_method="FILTERED_SCAN",
                    reason=f"Matched element via filtered scan: [{el.role}] '{el.name}'"
                )

        return GroundedTarget(
            success=False,
            reason=f"Failed to ground target '{clean_target}' on Windows UI."
        )

    def _is_avoided(self, element: UIElement, avoid_list: List[str]) -> bool:
        if not avoid_list:
            return False
        name = (element.name or "").lower()
        auto_id = (element.automation_id or "").lower()
        for avoid in avoid_list:
            if avoid in name or avoid in auto_id:
                return True
        return False
