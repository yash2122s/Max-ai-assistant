"""
Unit and integration tests for OpenJarvis-adapted DAG Planner in MAX.
Verifies cycle detection, Kahn's algorithm, execution stages, and TaskEngine integration.
"""

import time
import asyncio
import pytest
from engine.dag_planner import DAGWorkflowGraph, DAGNode
from engine.models import Step, StepState
from engine.task_engine import TaskEngine


def test_dag_validation_clean():
    graph = DAGWorkflowGraph(name="test_clean")
    graph.add_node(DAGNode(step_id="step_1", action="read_file"))
    graph.add_node(DAGNode(step_id="step_2", action="patch_code"))
    graph.add_node(DAGNode(step_id="step_3", action="run_tests"))

    graph.add_edge("step_1", "step_2")
    graph.add_edge("step_2", "step_3")

    valid, msg = graph.validate()
    assert valid is True
    assert msg == ""

    order = graph.topological_sort()
    assert order == ["step_1", "step_2", "step_3"]


def test_dag_cycle_detection():
    graph = DAGWorkflowGraph(name="test_cycle")
    graph.add_node(DAGNode(step_id="a", action="tool_a"))
    graph.add_node(DAGNode(step_id="b", action="tool_b"))
    graph.add_node(DAGNode(step_id="c", action="tool_c"))

    graph.add_edge("a", "b")
    graph.add_edge("b", "c")
    graph.add_edge("c", "a")  # Cycle!

    valid, msg = graph.validate()
    assert valid is False
    assert "Circular dependency" in msg

    with pytest.raises(ValueError, match="Circular dependency"):
        graph.topological_sort()


def test_dag_execution_stages():
    graph = DAGWorkflowGraph(name="test_stages")
    # Parallel roots: a, b
    # c depends on a, d depends on b
    # e depends on c and d
    for nid in ["a", "b", "c", "d", "e"]:
        graph.add_node(DAGNode(step_id=nid, action=f"action_{nid}"))

    graph.add_edge("a", "c")
    graph.add_edge("b", "d")
    graph.add_edge("c", "e")
    graph.add_edge("d", "e")

    stages = graph.execution_stages()
    assert len(stages) == 3
    assert set(stages[0]) == {"a", "b"}
    assert set(stages[1]) == {"c", "d"}
    assert stages[2] == ["e"]


def test_task_engine_dag_integration():
    engine = TaskEngine.get_instance()

    # Steps created out-of-order with depends_on
    step_compile = Step(
        step_id="step_compile",
        task_id="",
        sequence=1,
        action="terminal",
        arguments={"command": "build", "depends_on": ["step_generate"]}
    )
    step_generate = Step(
        step_id="step_generate",
        task_id="",
        sequence=2,
        action="code",
        arguments={"code": "print(1)"}
    )

    task = engine.create_task(
        goal="Test DAG build",
        steps=[step_compile, step_generate]
    )

    # Generated step should be sorted BEFORE compile step
    assert task.steps[0].step_id == "step_generate"
    assert task.steps[0].sequence == 1
    assert task.steps[1].step_id == "step_compile"
    assert task.steps[1].sequence == 2


@pytest.mark.asyncio
async def test_task_engine_parallel_dag_execution():
    import time
    from core.unified_tool_registry import unified_tool_registry
    from engine.models import TaskState, StepState

    engine = TaskEngine.get_instance()
    timestamps = {}

    async def async_worker_a(**kwargs):
        timestamps["a_start"] = time.time()
        await asyncio.sleep(0.1)
        timestamps["a_end"] = time.time()
        return {"status": "success", "data": "result_a"}

    async def async_worker_b(**kwargs):
        timestamps["b_start"] = time.time()
        await asyncio.sleep(0.1)
        timestamps["b_end"] = time.time()
        return {"status": "success", "data": "result_b"}

    async def async_combiner(**kwargs):
        timestamps["c_start"] = time.time()
        await asyncio.sleep(0.05)
        timestamps["c_end"] = time.time()
        return {"status": "success", "data": "combined"}

    unified_tool_registry._functions["async_worker_a"] = async_worker_a
    unified_tool_registry._functions["async_worker_b"] = async_worker_b
    unified_tool_registry._functions["async_combiner"] = async_combiner

    step_a = Step(
        step_id="step_a",
        task_id="",
        sequence=1,
        action="async_worker_a",
        risk_level="SAFE"
    )
    step_b = Step(
        step_id="step_b",
        task_id="",
        sequence=2,
        action="async_worker_b",
        risk_level="SAFE"
    )
    step_c = Step(
        step_id="step_c",
        task_id="",
        sequence=3,
        action="async_combiner",
        risk_level="SAFE",
        arguments={"depends_on": ["step_a", "step_b"]}
    )

    task = engine.create_task(
        goal="Parallel DAG execution verification",
        steps=[step_a, step_b, step_c]
    )

    t0 = time.time()
    completed_task = await engine.execute_task(task)
    total_duration = time.time() - t0

    assert completed_task.status == TaskState.COMPLETED
    assert completed_task.steps[0].status == StepState.SUCCEEDED
    assert completed_task.steps[1].status == StepState.SUCCEEDED
    assert completed_task.steps[2].status == StepState.SUCCEEDED

    # Verify parallel overlap between step_a and step_b
    overlap = max(timestamps["a_start"], timestamps["b_start"]) < min(timestamps["a_end"], timestamps["b_end"])
    assert overlap is True, "Step A and Step B must execute concurrently in parallel!"

    # Verify step_c only started AFTER both step_a and step_b finished
    assert timestamps["c_start"] >= timestamps["a_end"] - 0.01
    assert timestamps["c_start"] >= timestamps["b_end"] - 0.01

    # Verify stage 0 span is ~0.1s (parallel) instead of ~0.2s (sequential)
    parallel_stage_span = max(timestamps["a_end"], timestamps["b_end"]) - min(timestamps["a_start"], timestamps["b_start"])
    assert parallel_stage_span < 0.16, f"Expected parallel span < 0.16s, got {parallel_stage_span}s"


@pytest.mark.asyncio
async def test_dag_failure_downstream_blocking():
    from core.unified_tool_registry import unified_tool_registry
    from engine.models import TaskState, StepState

    engine = TaskEngine.get_instance()

    def failing_step(**kwargs):
        return {"status": "error", "message": "Failed critical component"}

    def blocked_step(**kwargs):
        return {"status": "success"}

    unified_tool_registry._functions["failing_action"] = failing_step
    unified_tool_registry._functions["blocked_action"] = blocked_step

    s1 = Step(step_id="s1_fail", task_id="", sequence=1, action="failing_action", max_attempts=1, risk_level="SAFE")
    s2 = Step(step_id="s2_downstream", task_id="", sequence=2, action="blocked_action", arguments={"depends_on": ["s1_fail"]})

    task = engine.create_task(goal="Failure propagation test", steps=[s1, s2])
    res_task = await engine.execute_task(task)

    assert res_task.status == TaskState.FAILED
    assert res_task.steps[0].status == StepState.FAILED
    assert res_task.steps[1].status == StepState.BLOCKED
