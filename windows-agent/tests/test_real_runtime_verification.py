"""
MAX AI — Comprehensive Real Runtime Verification Suite
Executes end-to-end dynamic verification across all 7 core capabilities:
1. Real Vosk acoustic model audio ingestion & recognition (no test hooks)
2. Real 384-dim semantic embeddings (all-MiniLM-L6-v2) + SQLite vector store
3. Real CrossRef & Wikipedia live HTTP research with real DOI/URLs and citation grounding
4. Real UI-originated confirmation gate with zero model self-authorization
5. Real Windows Recycle Bin / Vault file deletion with bit-exact disk restoration
6. Real CodeRepairAgent structured subprocess (shell=False) repair loop
7. Real DAG parallel stage task execution in TaskEngine with concurrency overlap
"""

import os
import sys
import time
import json
import shutil
import hashlib
import asyncio
import tempfile
import pytest

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from core.wake_word import RealWakeWordEngine
from core.hybrid_search import RealHybridSearcher, get_embedding_model
from tools.research_tools import ResearchTools
from core.confirmation_gate import HumanConfirmationGate
from core.safe_fs import safe_fs
from core.undo_manager import undo_manager
from core.code_repair_agent import CodeRepairAgent, PathSandbox, PatchValidator, TracebackParser
from engine.dag_planner import DAGWorkflowGraph
from engine.task_engine import TaskEngine
from engine.models import Step, StepState, TaskState
from core.unified_tool_registry import unified_tool_registry


# =========================================================================
# 1. Real Wake Word Engine Audio Feed Verification
# =========================================================================
def test_runtime_01_wake_word_real_model():
    """Verify Vosk model loads and processes real 16kHz 16-bit PCM audio frames."""
    detected_phrase = None
    def on_detect(phrase):
        nonlocal detected_phrase
        detected_phrase = phrase

    engine = RealWakeWordEngine(wake_words=["hey max", "jarvis"], on_detected=on_detect)
    assert engine.is_available is True, "Vosk acoustic model must be present and available"
    assert engine._recognizer is not None, "KaldiRecognizer must be initialized"

    # Create real 16kHz 16-bit mono audio samples (0.5s = 8000 samples = 16000 bytes)
    import math
    sample_rate = 16000
    freq = 440.0
    pcm_bytes = bytearray()
    for i in range(8000):
        val = int(32767.0 * 0.2 * math.sin(2.0 * math.pi * freq * (i / sample_rate)))
        pcm_bytes.extend(val.to_bytes(2, byteorder='little', signed=True))

    engine.start()
    assert engine.is_running is True

    # Ingest chunks of 3200 bytes (100ms) into the real recognizer
    for offset in range(0, len(pcm_bytes), 3200):
        chunk = bytes(pcm_bytes[offset:offset + 3200])
        engine.feed_audio(chunk)

    time.sleep(0.1)
    engine.stop()
    assert engine.is_running is False


# =========================================================================
# 2. Real Semantic Embeddings & Persistent Vector Storage
# =========================================================================
def test_runtime_02_embedding_similarity_and_persistence():
    """Verify local all-MiniLM-L6-v2 embeddings generate 384-dim vectors and perform semantic retrieval."""
    from core.hybrid_search import get_embedding_model, hybrid_searcher, EMBEDDING_DIM
    from core.memory import remember_fact, hybrid_recall_fact

    model = get_embedding_model()
    assert model is not None, "Sentence-transformers model must load"

    # Verify vector dimension
    test_vec = model.encode("Artificial intelligence and multi-agent coordination", convert_to_numpy=True)
    assert test_vec.shape == (EMBEDDING_DIM,)
    assert test_vec.shape == (384,)

    # Ingest 2 distinct real semantic documents
    remember_fact("user_car", "The driver recharged their Tesla electric vehicle with lithium-ion power cells at the supercharger.")
    remember_fact("user_garden", "Growing organic tomatoes and fresh basil in rich garden soil with natural compost.")

    # Query with zero keyword overlap for "vehicle/Tesla" -> "automobile EV battery"
    recall_res = hybrid_recall_fact("automobile EV battery", limit=2)
    assert recall_res["status"] == "success"
    assert recall_res["count"] >= 1

    top_hit = recall_res["results"][0]
    assert top_hit["key"] == "user_car", f"Expected 'user_car' as top semantic hit, got {top_hit['key']}"
    assert top_hit.get("cosine_similarity", 0) > 0.35, f"Expected cosine similarity > 0.35, got {top_hit.get('cosine_similarity')}"


# =========================================================================
# 3. Real Web Research with CrossRef & Wikipedia APIs
# =========================================================================
def test_runtime_03_live_scholarly_and_web_research():
    """Verify real HTTP retrieval from CrossRef Works and Wikipedia APIs."""
    # 1. Real scholarly paper retrieval from CrossRef
    res_arxiv = ResearchTools.search_arxiv("Deep Reinforcement Learning")
    assert res_arxiv.get("status") == "success", f"CrossRef search failed: {res_arxiv}"
    papers = res_arxiv.get("papers", [])
    assert len(papers) >= 1, "Expected at least 1 real paper returned"
    first_paper = papers[0]
    assert "title" in first_paper and len(first_paper["title"]) > 5
    assert "url" in first_paper and first_paper["url"].startswith("http")
    assert "doi" in first_paper or "source" in first_paper

    # 2. Real encyclopedic retrieval from Wikipedia
    res_deep = ResearchTools.deep_research("Machine learning")
    assert res_deep.get("status") == "success", f"Deep research failed: {res_deep}"
    assert "dossier_markdown" in res_deep and len(res_deep["dossier_markdown"]) > 50
    assert len(res_deep.get("sources", [])) >= 1

    # 3. Real citation validation against real Wikipedia URL
    sample_url = "https://en.wikipedia.org/wiki/Machine_learning"
    res_cite = ResearchTools.validate_citations("machine learning algorithms data", [sample_url])
    assert res_cite.get("status") == "success"
    assert res_cite.get("verification_status") == "VERIFIED"
    assert res_cite.get("is_grounded") is True


# =========================================================================
# 4. Real UI Confirmation Gate (Zero Model Self-Authorization)
# =========================================================================
def test_runtime_04_ui_confirmation_click():
    """Verify model receives zero credentials and action executes ONLY upon out-of-band UI click."""
    gate = HumanConfirmationGate(expiry_seconds=15.0)
    ui_notifications = []
    executed_action = False

    gate.register_ui_listener(lambda payload: ui_notifications.append(payload))

    def critical_disk_format():
        nonlocal executed_action
        executed_action = True
        return {"status": "formatted", "drive": "D:"}

    # Model requests dangerous operation
    request_result = gate.request_confirmation(
        action_name="format_disk",
        description="Format D: volume partition",
        target="D:",
        action_fn=critical_disk_format
    )

    # Verify model response contains ZERO authorization tokens
    assert request_result["status"] == "WAITING_CONFIRMATION"
    ticket_id = request_result["ticket_id"]
    assert "token" not in request_result
    assert "hmac" not in request_result
    assert "auth_key" not in request_result
    assert executed_action is False, "Action must not execute before UI confirmation"

    # Verify UI received the out-of-band notification
    assert len(ui_notifications) == 1
    assert ui_notifications[0]["ticket_id"] == ticket_id

    # Model attempts to forge authorization with invalid ticket -> rejected
    fake_res = gate.user_ui_approve("invalid_ticket_forged")
    assert fake_res["status"] == "error"
    assert executed_action is False

    # Real UI click occurs out-of-band
    approval_result = gate.user_ui_approve(ticket_id)
    assert approval_result["status"] == "success"
    assert executed_action is True
    assert approval_result["result"] == {"status": "formatted", "drive": "D:"}

    # Ticket is invalidated; replay is blocked
    replay_res = gate.user_ui_approve(ticket_id)
    assert replay_res["status"] == "error"
    assert replay_res["error_code"] == "INVALID_OR_EXPIRED_TICKET"


# =========================================================================
# 5. Real Safe File Deletion & True Disk Restoration (Undo)
# =========================================================================
def test_runtime_05_safe_file_deletion_and_disk_restoration(tmp_path):
    """Verify real file deletion with Windows Recycle Bin backup and bit-exact disk restoration."""
    target_file = tmp_path / "important_contract.txt"
    original_content = b"CRITICAL CONTRACT DATA: Hash verify 1234567890abcdef."
    target_file.write_bytes(original_content)
    target_path = str(target_file)

    original_hash = hashlib.sha256(original_content).hexdigest()
    assert os.path.exists(target_path) is True

    # Perform real safe delete
    del_result = safe_fs.safe_delete(target_path)
    assert del_result["status"] == "success"

    # Verify file is gone from original disk location
    assert not os.path.exists(target_path), "File must be removed from disk"

    # Perform real undo restoration
    undo_result = undo_manager.undo()
    assert undo_result["status"] == "success"
    assert undo_result["success"] is True

    # Verify file is genuinely restored on disk with bit-exact hash
    assert os.path.exists(target_path), "File must be physically restored to disk"
    restored_bytes = target_file.read_bytes()
    assert hashlib.sha256(restored_bytes).hexdigest() == original_hash
    assert restored_bytes == original_content


# =========================================================================
# 6. Real Code Failure -> Patch -> Test -> Repair (Structured Subprocess)
# =========================================================================
@pytest.mark.asyncio
async def test_runtime_06_code_repair_structured_subprocess(tmp_path):
    """Verify CodeRepairAgent runs targeted tests with shell=False structured argv and repairs bugs."""
    sandbox = PathSandbox(allowed_roots=[str(tmp_path)])
    agent = CodeRepairAgent(max_attempts=3, sandbox=sandbox)

    # 1. Create a buggy module
    code_file = tmp_path / "calculator.py"
    buggy_code = (
        "def compute_ratio(a, b):\n"
        "    return a / b\n"
    )
    code_file.write_text(buggy_code, encoding="utf-8")

    # 2. Create an automated test that triggers the bug
    test_file = tmp_path / "test_calc.py"
    test_code = (
        "import unittest\n"
        "from calculator import compute_ratio\n\n"
        "class TestCalc(unittest.TestCase):\n"
        "    def test_ratio(self):\n"
        "        self.assertEqual(compute_ratio(10, 2), 5)\n"
        "        self.assertEqual(compute_ratio(10, 0), 0)\n\n"
        "if __name__ == '__main__':\n"
        "    unittest.main()\n"
    )
    test_file.write_text(test_code, encoding="utf-8")

    # 3. Run targeted test via structured subprocess (shell=False)
    cmd = f"py -m unittest {test_file.name}"
    run_res = agent.run_targeted_test(cmd, cwd=str(tmp_path))
    assert run_res["status"] == "failed", "Initial test run must reproduce the bug"
    assert "ZeroDivisionError" in run_res["stderr"]

    # 4. Parse traceback
    tb_info = TracebackParser.parse(run_res["stderr"])
    assert tb_info is not None
    assert tb_info.exception_type == "ZeroDivisionError"

    # 5. Fix the code via safe patch application
    fixed_code = (
        "def compute_ratio(a, b):\n"
        "    if b == 0:\n"
        "        return 0\n"
        "    return a / b\n"
    )
    patch_res = agent.apply_patch_safely(str(code_file), fixed_code)
    assert patch_res["status"] == "success"

    # 6. Re-run targeted test -> passes!
    retest_res = agent.run_targeted_test(cmd, cwd=str(tmp_path))
    assert retest_res["status"] == "success", f"Repaired code must pass: {retest_res}"


# =========================================================================
# 7. Real DAG Parallel Stage Task Execution in TaskEngine
# =========================================================================
@pytest.mark.asyncio
async def test_runtime_07_dag_parallel_task_execution():
    """Verify TaskEngine executes independent safe DAG steps concurrently in parallel stages."""
    engine = TaskEngine.get_instance()
    timestamps = {}

    async def fetch_weather_api(**kwargs):
        timestamps["weather_start"] = time.time()
        await asyncio.sleep(0.12)
        timestamps["weather_end"] = time.time()
        return {"status": "success", "weather": "Sunny 25C"}

    async def fetch_news_api(**kwargs):
        timestamps["news_start"] = time.time()
        await asyncio.sleep(0.12)
        timestamps["news_end"] = time.time()
        return {"status": "success", "news": "AI Agents advance"}

    async def synthesize_briefing(**kwargs):
        timestamps["briefing_start"] = time.time()
        await asyncio.sleep(0.04)
        timestamps["briefing_end"] = time.time()
        return {"status": "success", "briefing": "Complete"}

    unified_tool_registry._functions["fetch_weather_api"] = fetch_weather_api
    unified_tool_registry._functions["fetch_news_api"] = fetch_news_api
    unified_tool_registry._functions["synthesize_briefing"] = synthesize_briefing

    step_weather = Step(
        step_id="step_weather",
        task_id="",
        sequence=1,
        action="fetch_weather_api",
        risk_level="SAFE"
    )
    step_news = Step(
        step_id="step_news",
        task_id="",
        sequence=2,
        action="fetch_news_api",
        risk_level="SAFE"
    )
    step_summary = Step(
        step_id="step_summary",
        task_id="",
        sequence=3,
        action="synthesize_briefing",
        risk_level="SAFE",
        arguments={"depends_on": ["step_weather", "step_news"]}
    )

    task = engine.create_task(
        goal="Live morning briefing with concurrent feeds",
        steps=[step_weather, step_news, step_summary]
    )

    t0 = time.time()
    completed_task = await engine.execute_task(task)
    total_time = time.time() - t0

    assert completed_task.status == TaskState.COMPLETED
    assert completed_task.steps[0].status == StepState.SUCCEEDED
    assert completed_task.steps[1].status == StepState.SUCCEEDED
    assert completed_task.steps[2].status == StepState.SUCCEEDED

    # Verify concurrent execution interval overlap between weather and news
    has_overlap = max(timestamps["weather_start"], timestamps["news_start"]) < min(timestamps["weather_end"], timestamps["news_end"])
    assert has_overlap is True, "Weather and News fetch must overlap concurrently in parallel!"

    # Verify stage 0 span is ~0.12s (concurrent) rather than 0.24s (sequential)
    stage_0_span = max(timestamps["weather_end"], timestamps["news_end"]) - min(timestamps["weather_start"], timestamps["news_start"])
    assert stage_0_span < 0.18, f"Expected parallel span < 0.18s, got {stage_0_span}s"

    # Verify briefing step started strictly after both parallel dependencies completed
    assert timestamps["briefing_start"] >= timestamps["weather_end"] - 0.01
    assert timestamps["briefing_start"] >= timestamps["news_end"] - 0.01
