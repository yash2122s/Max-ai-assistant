"""
MAX AI — Planner Package
Strict Task Planner foundation, schemas, validation, and dynamic prompt generator.
"""

from .schemas import Plan, PlanStep, PlannerError, PlanValidationResult
from .validator import PlanValidator, plan_validator, MAX_PLAN_STEPS
from .prompt import get_planner_system_prompt, build_tool_catalog, PLANNER_SYSTEM_INSTRUCTIONS
from .client import GeminiPlanningClient, planning_client, DEFAULT_PLANNING_MODEL, PLAN_RESPONSE_SCHEMA
from .normalizer import PlanNormalizer, plan_normalizer
from .task_factory import TaskFactory, task_factory
from .fast_path import FastPathResolver, fast_path_resolver

__all__ = [
    "Plan",
    "PlanStep",
    "PlannerError",
    "PlanValidationResult",
    "PlanValidator",
    "plan_validator",
    "MAX_PLAN_STEPS",
    "get_planner_system_prompt",
    "build_tool_catalog",
    "PLANNER_SYSTEM_INSTRUCTIONS",
    "GeminiPlanningClient",
    "planning_client",
    "DEFAULT_PLANNING_MODEL",
    "PLAN_RESPONSE_SCHEMA",
    "PlanNormalizer",
    "plan_normalizer",
    "TaskFactory",
    "task_factory",
    "FastPathResolver",
    "fast_path_resolver",
]


