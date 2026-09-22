#!/usr/bin/env python3
"""
JSON Workflow Engine for WhatsApp Desktop Automation
====================================================
Reads `workflow.json`, substitutes dynamic variables (e.g. {{TARGET_CONTACT}}, {{MESSAGE_TEXT}}),
and executes each step sequentially.
"""

import os
import sys
import time
import json
import argparse

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

import pyautogui
import pyperclip

pyautogui.FAILSAFE = False


def resolve_params(params: dict, variables: dict) -> dict:
    """Substitutes {{VARIABLE}} template placeholders with runtime dynamic values."""
    resolved = {}
    for k, v in params.items():
        if isinstance(v, str):
            for var_key, var_val in variables.items():
                pattern = f"{{{{{var_key}}}}}"
                if pattern in v:
                    v = v.replace(pattern, str(var_val))
            resolved[k] = v
        elif isinstance(v, list):
            new_list = []
            for item in v:
                if isinstance(item, str):
                    for var_key, var_val in variables.items():
                        pattern = f"{{{{{var_key}}}}}"
                        if pattern in item:
                            item = item.replace(pattern, str(var_val))
                new_list.append(item)
            resolved[k] = new_list
        else:
            resolved[k] = v
    return resolved


def execute_action(action_type: str, params: dict):
    """Executes a single step action based on action_type."""
    if action_type == "key_press":
        key = params.get("key", "")
        pyautogui.press(key)
        delay = float(params.get("post_delay_sec", 0.5))
        time.sleep(delay)

    elif action_type == "type_text":
        text = str(params.get("text", ""))
        interval = float(params.get("interval_sec", 0.05))
        try:
            text.encode("ascii")
            pyautogui.write(text, interval=interval)
        except UnicodeEncodeError:
            pyperclip.copy(text)
            pyautogui.hotkey("ctrl", "v")
        delay = float(params.get("post_delay_sec", 0.5))
        time.sleep(delay)

    elif action_type == "hotkey":
        keys = params.get("keys", [])
        pyautogui.hotkey(*keys)
        delay = float(params.get("post_delay_sec", 0.5))
        time.sleep(delay)

    elif action_type == "key_sequence":
        seq = params.get("sequence", [])
        interval = float(params.get("interval_sec", 0.3))
        for key in seq:
            pyautogui.press(key)
            time.sleep(interval)
        delay = float(params.get("post_delay_sec", 0.5))
        time.sleep(delay)

    elif action_type == "notify":
        msg = params.get("message", "Task Done!")
        print(f"\n📢 [NOTIFICATION] {msg}")


def run_json_workflow(workflow_path: str, variables: dict):
    if not os.path.exists(workflow_path):
        print(f"[ERROR] Workflow file not found: {workflow_path}")
        sys.exit(1)

    with open(workflow_path, "r", encoding="utf-8") as f:
        wf = json.load(f)

    print("=" * 65)
    print(f"⚙️ EXECUTING WORKFLOW: {wf.get('name')}")
    print(f"📖 Description: {wf.get('description')}")
    print(f"🔀 Active Dynamic Variables: {variables}")
    print("=" * 65)

    steps = wf.get("steps", [])
    for step in steps:
        step_num = step.get("step_number")
        title = step.get("title")
        desc = step.get("description_en")
        action_type = step.get("action_type")
        raw_params = step.get("params", {})
        resolved_params = resolve_params(raw_params, variables)

        ts = time.strftime("%H:%M:%S")
        print(f"[{ts}] [Step {step_num}] {title} ({action_type})")
        execute_action(action_type, resolved_params)

    print("=" * 65)
    print("🎉 TASK DONE! (వర్క్‌ఫ్లో విజయవంతంగా రన్ అయింది)")
    print("=" * 65)


def main():
    parser = argparse.ArgumentParser(description="Run WhatsApp JSON Workflow with Dynamic Parameters")
    parser.add_argument("--workflow", "-w", default="workflow.json", help="Path to workflow.json")
    parser.add_argument("--contact", "-c", default="yaswanth", help="Target contact name to search")
    parser.add_argument("--message", "-m", default="hello", help="Message text to type")
    parser.add_argument("--app", "-a", default="WhatsApp", help="Application name")

    args = parser.parse_args()

    vars_dict = {
        "TARGET_CONTACT": args.contact,
        "MESSAGE_TEXT": args.message,
        "APP_NAME": args.app,
        "LAUNCH_WAIT_SECONDS": 2.5,
        "SEARCH_WAIT_SECONDS": 1.2
    }

    run_json_workflow(args.workflow, vars_dict)


if __name__ == "__main__":
    main()