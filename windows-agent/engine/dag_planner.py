"""
MAX AI — DAG Multi-Agent Planner & Graph Orchestrator
Adapted from OpenJarvis workflow architecture.
Provides Directed Acyclic Graph (DAG) validation, cycle detection (DFS),
topological sorting (Kahn's algorithm), parallel execution staging,
and dynamic replanning upon execution failures.
100% compatible with existing Task and Step models in engine/models.py.
"""

from __future__ import annotations

import logging
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set, Tuple, Any

from .models import Task, Step, StepState, TaskState

logger = logging.getLogger("MAXWindowsAgent.DAGPlanner")


@dataclass
class DAGNode:
    """A node in the execution DAG wrapping a Task Step."""
    step_id: str
    action: str
    arguments: Dict[str, Any] = field(default_factory=dict)
    dependencies: List[str] = field(default_factory=list)
    risk_level: str = "SAFE"
    timeout_s: float = 60.0
    sequence: int = 0
    status: str = "PENDING"
    result: Optional[Dict[str, Any]] = None
    error: Optional[Dict[str, Any]] = None


@dataclass
class DAGEdge:
    """A directed dependency edge from prerequisite (source) to dependent (target)."""
    source: str
    target: str


class DAGWorkflowGraph:
    """
    Directed Acyclic Graph (DAG) engine for complex multi-step tasks.
    Supports cycle detection, topological dependency ordering, and parallel execution stages.
    """

    def __init__(self, name: str = ""):
        self.name = name
        self._nodes: Dict[str, DAGNode] = {}
        self._edges: List[DAGEdge] = []
        self._adjacency: Dict[str, List[str]] = defaultdict(list)
        self._reverse: Dict[str, List[str]] = defaultdict(list)

    def add_node(self, node: DAGNode) -> None:
        if node.step_id in self._nodes:
            raise ValueError(f"Duplicate node step_id: {node.step_id}")
        self._nodes[node.step_id] = node

    def add_edge(self, source: str, target: str) -> None:
        if source not in self._nodes:
            raise ValueError(f"Source node '{source}' not found in graph.")
        if target not in self._nodes:
            raise ValueError(f"Target node '{target}' not found in graph.")
        edge = DAGEdge(source=source, target=target)
        self._edges.append(edge)
        self._adjacency[source].append(target)
        self._reverse[target].append(source)
        if source not in self._nodes[target].dependencies:
            self._nodes[target].dependencies.append(source)

    def get_node(self, step_id: str) -> Optional[DAGNode]:
        return self._nodes.get(step_id)

    @property
    def nodes(self) -> List[DAGNode]:
        return list(self._nodes.values())

    @property
    def edges(self) -> List[DAGEdge]:
        return list(self._edges)

    def validate(self) -> Tuple[bool, str]:
        """Validates graph for circular dependencies using DFS cycle detection."""
        visited: Set[str] = set()
        in_stack: Set[str] = set()

        def _dfs(node_id: str) -> bool:
            visited.add(node_id)
            in_stack.add(node_id)
            for neighbor in self._adjacency.get(node_id, []):
                if neighbor in in_stack:
                    return True  # Cycle detected
                if neighbor not in visited and _dfs(neighbor):
                    return True
            in_stack.discard(node_id)
            return False

        for node_id in self._nodes:
            if node_id not in visited:
                if _dfs(node_id):
                    return False, f"Circular dependency cycle detected involving step '{node_id}'"

        return True, ""

    def topological_sort(self) -> List[str]:
        """Returns node IDs in topological dependency order using Kahn's algorithm."""
        valid, msg = self.validate()
        if not valid:
            raise ValueError(msg)

        in_degree: Dict[str, int] = {nid: 0 for nid in self._nodes}
        for edge in self._edges:
            in_degree[edge.target] = in_degree.get(edge.target, 0) + 1

        queue = deque([nid for nid, deg in in_degree.items() if deg == 0])
        order: List[str] = []

        while queue:
            node_id = queue.popleft()
            order.append(node_id)
            for neighbor in self._adjacency.get(node_id, []):
                in_degree[neighbor] -= 1
                if in_degree[neighbor] == 0:
                    queue.append(neighbor)

        if len(order) != len(self._nodes):
            raise ValueError("Graph contains an unresolved dependency cycle.")

        return order

    def execution_stages(self) -> List[List[str]]:
        """
        Groups nodes into execution stages where steps in each stage can run concurrently.
        """
        valid, msg = self.validate()
        if not valid:
            raise ValueError(msg)

        in_degree: Dict[str, int] = {nid: 0 for nid in self._nodes}
        for edge in self._edges:
            in_degree[edge.target] = in_degree.get(edge.target, 0) + 1

        current_stage = [nid for nid, deg in in_degree.items() if deg == 0]
        stages: List[List[str]] = []

        while current_stage:
            stages.append(current_stage)
            next_stage: List[str] = []
            for node_id in current_stage:
                for neighbor in self._adjacency.get(node_id, []):
                    in_degree[neighbor] -= 1
                    if in_degree[neighbor] == 0:
                        next_stage.append(neighbor)
            current_stage = next_stage

        return stages

    @classmethod
    def from_steps(cls, steps: List[Step], name: str = "TaskDAG") -> DAGWorkflowGraph:
        """Constructs a DAG from a list of Step models, reading dependencies from arguments."""
        graph = cls(name=name)
        for step in steps:
            deps = step.arguments.get("depends_on", [])
            if isinstance(deps, str):
                deps = [deps]
            elif not isinstance(deps, list):
                deps = []
            node = DAGNode(
                step_id=step.step_id,
                action=step.action,
                arguments=dict(step.arguments),
                dependencies=list(deps),
                risk_level=step.risk_level,
                timeout_s=step.timeout_s,
                sequence=step.sequence,
                status=step.status.value if hasattr(step.status, "value") else str(step.status)
            )
            graph.add_node(node)

        # Wire edges
        for node in graph.nodes:
            for dep_id in node.dependencies:
                # Resolve dep_id by step_id or sequence string
                resolved_id = dep_id
                if dep_id not in graph._nodes:
                    # Check sequence matching
                    for cand in graph.nodes:
                        if str(cand.sequence) == str(dep_id):
                            resolved_id = cand.step_id
                            break
                if resolved_id in graph._nodes:
                    graph.add_edge(resolved_id, node.step_id)
                else:
                    logger.warning(f"DAG: Dependency '{dep_id}' for step '{node.step_id}' not found. Ignoring.")

        return graph

    def compile_to_sequential_steps(self, original_steps: List[Step]) -> List[Step]:
        """
        Sorts the steps into an executable sequence respecting all DAG dependencies.
        """
        if not self._edges:
            # No dependencies declared — preserve original sequence
            return sorted(original_steps, key=lambda s: s.sequence)

        order = self.topological_sort()
        step_map = {s.step_id: s for s in original_steps}

        compiled_steps: List[Step] = []
        for i, step_id in enumerate(order, start=1):
            step = step_map[step_id]
            step.sequence = i
            compiled_steps.append(step)

        return compiled_steps

    def replan_on_failure(self, failed_step_id: str, error_message: str) -> List[DAGNode]:
        """
        Computes the blast radius of a failed step and identifies downstream steps
        that are blocked or require replanning.
        """
        blocked_nodes: List[DAGNode] = []
        queue = deque([failed_step_id])
        seen = {failed_step_id}

        while queue:
            current = queue.popleft()
            for downstream in self._adjacency.get(current, []):
                if downstream not in seen:
                    seen.add(downstream)
                    queue.append(downstream)
                    node = self._nodes.get(downstream)
                    if node:
                        node.status = "BLOCKED"
                        node.error = {"code": "DEPENDENCY_FAILED", "message": f"Prerequisite step '{current}' failed: {error_message}"}
                        blocked_nodes.append(node)

        return blocked_nodes
