"""
Contract tests for normalize_tool_result.
Validates the 7 required normalization contracts:
1. Plain dictionary without data
2. Envelope with nested dictionary
3. Envelope with nested list
4. Ordinary domain object containing a data field (without envelope keys)
5. data=None
6. Error result with both error and data
7. Already-normalized ToolResult
"""

import pytest
from core.tool_result import ToolResult, normalize_tool_result


def test_01_plain_dictionary_without_data():
    raw = {"status": "success", "file_path": "C:\\test.txt", "message": "File created."}
    res = normalize_tool_result("create_file", raw)
    assert res.success is True
    assert res.source == "native"
    assert res.tool_name == "create_file"
    assert res["file_path"] == "C:\\test.txt"
    assert res.data == raw


def test_02_envelope_with_nested_dictionary():
    raw = {
        "status": "success",
        "command_id": "cmd_12345",
        "message": "Executed successfully",
        "data": {"state": True, "brightness": 80}
    }
    res = normalize_tool_result("execute_device_action", raw)
    assert res.success is True
    assert res.data == {"state": True, "brightness": 80}
    assert res.get("data", {}).get("state") is True
    # Sibling metadata is preserved
    assert res["command_id"] == "cmd_12345"
    assert res.get("command_id") == "cmd_12345"


def test_03_envelope_with_nested_list():
    raw = {
        "status": "success",
        "data": ["item1", "item2", "item3"],
        "message": "Listed items"
    }
    res = normalize_tool_result("list_items", raw)
    assert res.success is True
    assert res.data == ["item1", "item2", "item3"]
    assert res["data"] == ["item1", "item2", "item3"]


def test_04_ordinary_domain_object_containing_data_field():
    # Ordinary domain dict without any envelope keys ("status", "success", "message", etc.)
    raw = {
        "data": {"state": True},
        "source": "remote_host",
        "latency_ms": 20
    }
    res = normalize_tool_result("custom_metric", raw)
    # Since it is not an envelope, raw is preserved as data and no sibling keys are dropped
    assert res.data == raw
    assert res["source"] == "remote_host"
    assert res["latency_ms"] == 20


def test_05_data_is_none():
    raw = {
        "status": "success",
        "data": None,
        "message": "No data returned"
    }
    res = normalize_tool_result("ping", raw)
    assert res.success is True
    assert res.data == raw
    assert res.message == "No data returned"


def test_06_error_result_with_both_error_and_data():
    raw = {
        "status": "error",
        "error": "Failed to connect",
        "error_code": "NETWORK_ERROR",
        "data": {"retry_after": 5}
    }
    res = normalize_tool_result("sync_data", raw)
    assert res.success is False
    assert res.error == "Failed to connect"
    assert res.error_code == "NETWORK_ERROR"
    assert res.data == {"retry_after": 5}
    assert res.get("data", {}).get("retry_after") == 5


def test_07_already_normalized_tool_result():
    orig = ToolResult(
        success=True,
        status="success",
        tool_name="test_tool",
        data={"verified": True}
    )
    res = normalize_tool_result("test_tool", orig)
    assert res is orig
    assert res.success is True
    assert res.data == {"verified": True}
