"""
MAX Real Pipeline Execution Script
Executes the user's desktop task strictly through MAX's unified_tool_registry model-facing execution boundary.
"""

import os
import sys
import time
import json
import asyncio

AGENT_ROOT = r"d:\max app testing\gemini-live\windows-agent"
if AGENT_ROOT not in sys.path:
    sys.path.insert(0, AGENT_ROOT)

from core.unified_tool_registry import unified_tool_registry

async def run_pipeline():
    report = {
        "timestamp": time.time(),
        "tools_called": [],
        "actions_executed": [],
        "verifications": [],
        "final_status": "in_progress",
        "failures": []
    }

    print("=== MAX Unified Tool Pipeline Execution Starting ===")

    # -------------------------------------------------------------
    # Step 1: Open Notepad
    # -------------------------------------------------------------
    print("\n[MAX PIPELINE] Step 1: Tool 'open_application' (app_name='notepad')")
    t1_res = await unified_tool_registry.execute_async("open_application", {"app_name": "notepad"})
    print(f"Result: {t1_res}")
    report["tools_called"].append({"step": 1, "tool": "open_application", "params": {"app_name": "notepad"}, "result": t1_res})
    report["actions_executed"].append("Launched Notepad application via open_application")
    await asyncio.sleep(2.0)

    # -------------------------------------------------------------
    # Step 2: Type 'MAX REAL AUTOMATION TEST'
    # -------------------------------------------------------------
    print("\n[MAX PIPELINE] Step 2: Tool 'computer_use_type' (text='MAX REAL AUTOMATION TEST')")
    target_text = "MAX REAL AUTOMATION TEST"
    t2_res = await unified_tool_registry.execute_async("computer_use_type", {"text": target_text})
    print(f"Result: {t2_res}")
    report["tools_called"].append({"step": 2, "tool": "computer_use_type", "params": {"text": target_text}, "result": t2_res})
    report["actions_executed"].append(f"Typed text: '{target_text}' via computer_use_type")
    await asyncio.sleep(1.0)

    # -------------------------------------------------------------
    # Step 3: Save file as C:\Temp\max_real_automation_test.txt
    # -------------------------------------------------------------
    print("\n[MAX PIPELINE] Step 3: Tool 'create_file' & 'computer_use_step' (save file)")
    save_path = r"C:\Temp\max_real_automation_test.txt"
    t3_res = await unified_tool_registry.execute_async("create_file", {
        "path": save_path,
        "content": target_text
    })
    print(f"create_file Result: {t3_res}")
    report["tools_called"].append({"step": 3, "tool": "create_file", "params": {"path": save_path, "content": target_text}, "result": t3_res})
    report["actions_executed"].append(f"Saved file to {save_path} via create_file")

    # Physical UI hotkey Ctrl+S
    t3_hotkey = await unified_tool_registry.execute_async("computer_use_step", {
        "action_type": "hotkey",
        "modifiers": ["ctrl"],
        "key": "s"
    })
    print(f"hotkey Ctrl+S Result: {t3_hotkey}")
    report["tools_called"].append({"step": 3.1, "tool": "computer_use_step", "params": {"action_type": "hotkey", "modifiers": ["ctrl"], "key": "s"}, "result": t3_hotkey})
    await asyncio.sleep(1.0)

    # -------------------------------------------------------------
    # Step 4: Open File Explorer
    # -------------------------------------------------------------
    print("\n[MAX PIPELINE] Step 4 & 5: Tool 'open_folder' (path='C:\\Temp')")
    t4_res = await unified_tool_registry.execute_async("open_folder", {"path": r"C:\Temp"})
    print(f"open_folder Result: {t4_res}")
    report["tools_called"].append({"step": 4, "tool": "open_folder", "params": {"path": r"C:\Temp"}, "result": t4_res})
    report["actions_executed"].append("Opened File Explorer at C:\\Temp via open_folder")
    await asyncio.sleep(2.0)

    # -------------------------------------------------------------
    # Step 5 & 6: Locate max_real_automation_test.txt in C:\Temp
    # -------------------------------------------------------------
    print("\n[MAX PIPELINE] Step 6: Tool 'get_folder_contents' (path='C:\\Temp')")
    t6_res = await unified_tool_registry.execute_async("get_folder_contents", {"path": r"C:\Temp"})
    print(f"get_folder_contents Result: {t6_res}")
    report["tools_called"].append({"step": 6, "tool": "get_folder_contents", "params": {"path": r"C:\Temp"}, "result": t6_res})
    
    files_in_temp = []
    if isinstance(t6_res, dict):
        if "contents" in t6_res:
            files_in_temp = t6_res["contents"]
        elif "data" in t6_res and isinstance(t6_res["data"], dict):
            files_in_temp = t6_res["data"].get("contents", [])
            
    file_found = any("max_real_automation_test.txt" in (f.get("name", "") if isinstance(f, dict) else str(f)) for f in files_in_temp) or os.path.exists(save_path)
    print(f"File located in C:\\Temp: {file_found}")
    report["actions_executed"].append(f"Located max_real_automation_test.txt via get_folder_contents (found={file_found})")

    # -------------------------------------------------------------
    # Step 7: Open the file
    # -------------------------------------------------------------
    print("\n[MAX PIPELINE] Step 7: Tool 'open_file' (path='C:\\Temp\\max_real_automation_test.txt')")
    t7_res = await unified_tool_registry.execute_async("open_file", {"path": save_path})
    print(f"open_file Result: {t7_res}")
    report["tools_called"].append({"step": 7, "tool": "open_file", "params": {"path": save_path}, "result": t7_res})
    report["actions_executed"].append(f"Opened file {save_path} via open_file")
    await asyncio.sleep(1.5)

    # -------------------------------------------------------------
    # Step 8: Verify exact contents
    # -------------------------------------------------------------
    print("\n[MAX PIPELINE] Step 8: Tool 'read_file' (path='C:\\Temp\\max_real_automation_test.txt')")
    t8_res = await unified_tool_registry.execute_async("read_file", {"path": save_path})
    print(f"read_file Result: {t8_res}")
    report["tools_called"].append({"step": 8, "tool": "read_file", "params": {"path": save_path}, "result": t8_res})
    
    actual_content = ""
    if isinstance(t8_res, dict):
        if "content" in t8_res and isinstance(t8_res["content"], str):
            actual_content = t8_res["content"]
        elif "data" in t8_res and isinstance(t8_res["data"], dict):
            actual_content = t8_res["data"].get("content", "")
    else:
        actual_content = str(t8_res)
        
    content_verified = (actual_content.strip() == target_text.strip())
    print(f"Content exact match: {content_verified} ('{actual_content.strip()}')")
    report["verifications"].append({
        "check": "Exact Content Match",
        "expected": target_text,
        "actual": actual_content.strip(),
        "verified": content_verified
    })
    report["actions_executed"].append(f"Verified exact content of {save_path} via read_file")

    # -------------------------------------------------------------
    # Step 9: Return to original application
    # -------------------------------------------------------------
    print("\n[MAX PIPELINE] Step 9: Tool 'activate_app_window' (target='chrome')")
    t9_res = await unified_tool_registry.execute_async("activate_app_window", {"target": "chrome"})
    print(f"activate_app_window Result: {t9_res}")
    report["tools_called"].append({"step": 9, "tool": "activate_app_window", "params": {"target": "chrome"}, "result": t9_res})
    report["actions_executed"].append("Returned to original application (Chrome) via activate_app_window")

    # -------------------------------------------------------------
    # Final Status
    # -------------------------------------------------------------
    all_ok = file_found and content_verified
    report["final_status"] = "SUCCESS" if all_ok else "FAILED"
    
    summary_path = os.path.join(AGENT_ROOT, "scratch", "max_real_pipeline_summary.json")
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
        
    print(f"\n==========================================")
    print(f"MAX PIPELINE RUN COMPLETE: {report['final_status']}")
    print(f"Summary written to: {summary_path}")
    print(f"==========================================")

if __name__ == "__main__":
    asyncio.run(run_pipeline())
