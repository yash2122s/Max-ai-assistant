"""
JARVIS Permanent Memory System
Stores user facts, preferences, contact info, and notes persistently in memory.json.
Provides tools to remember, recall, list, and forget facts.
"""

import os
import json
import logging
from typing import Dict, Any

logger = logging.getLogger("jarvis.memory")

MEMORY_FILE = os.path.join(os.path.dirname(os.path.dirname(__file__)), "memory.json")


def _load_memory() -> Dict[str, Any]:
    """Load memories from memory.json file."""
    if not os.path.exists(MEMORY_FILE):
        return {}
    try:
        with open(MEMORY_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        logger.error(f"Error loading memory.json: {e}")
        return {}


def _save_memory(memory: Dict[str, Any]) -> bool:
    """Save memories to memory.json file."""
    try:
        with open(MEMORY_FILE, "w", encoding="utf-8") as f:
            json.dump(memory, f, indent=2, ensure_ascii=False)
        return True
    except Exception as e:
        logger.error(f"Error saving memory.json: {e}")
        return False


def remember_fact(key: str, value: str) -> dict:
    """
    Save a fact, preference, contact detail, or note to permanent memory.
    
    Args:
        key: The key/name of the fact (e.g., 'user_name', 'bannu_phone', 'favorite_food').
        value: The detail/fact value to remember (e.g., 'Yaswanth', '9876543210', 'Biryani').
    """
    try:
        key_clean = key.lower().strip().replace(" ", "_")
        memories = _load_memory()
        memories[key_clean] = {
            "value": value,
            "original_key": key.strip(),
        }
        if _save_memory(memories):
            return {
                "status": "success",
                "message": f"Remembered '{key}': '{value}' in permanent memory.",
                "key": key,
                "value": value,
            }
        return {"status": "error", "message": "Failed to save memory file."}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def recall_fact(query: str) -> dict:
    """
    Search and retrieve facts from permanent memory by key or keyword.
    
    Args:
        query: Search term or key to look up (e.g., 'bannu', 'name', 'food').
    """
    try:
        memories = _load_memory()
        if not memories:
            return {"status": "info", "message": "Permanent memory is currently empty.", "results": {}}
        
        query_clean = query.lower().strip()
        results = {}
        
        for k, item in memories.items():
            val = item.get("value", "")
            orig_k = item.get("original_key", k)
            if query_clean in k or query_clean in str(val).lower() or query_clean in orig_k.lower():
                results[orig_k] = val
        
        if results:
            return {
                "status": "success",
                "message": f"Found {len(results)} memory entry(ies) for '{query}'",
                "results": results,
            }
        return {
            "status": "info",
            "message": f"No memory entries found matching '{query}'",
            "all_keys": [item.get("original_key", k) for k, item in memories.items()],
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def list_memories() -> dict:
    """
    List all stored permanent memories and facts.
    """
    try:
        memories = _load_memory()
        if not memories:
            return {"status": "info", "message": "Permanent memory is currently empty.", "memories": {}}
        
        formatted = {item.get("original_key", k): item.get("value") for k, item in memories.items()}
        return {
            "status": "success",
            "total_memories": len(formatted),
            "memories": formatted,
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def forget_fact(key: str) -> dict:
    """
    Delete a fact from permanent memory.
    
    Args:
        key: The key or fact name to forget/delete.
    """
    try:
        key_clean = key.lower().strip().replace(" ", "_")
        memories = _load_memory()
        if key_clean in memories:
            del memories[key_clean]
            _save_memory(memories)
            return {"status": "success", "message": f"Forgot '{key}' from permanent memory."}
        return {"status": "error", "message": f"Fact '{key}' not found in permanent memory."}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def get_all_memories_summary() -> str:
    """Return a formatted string of all memories for auto-injection into System Prompt."""
    memories = _load_memory()
    if not memories:
        return "No saved memories yet."
    lines = [f"- {item.get('original_key', k)}: {item.get('value')}" for k, item in memories.items()]
    return "\n".join(lines)


# ─── Tool Declarations ──────────────────────────────────────────────────────
TOOL_DECLARATIONS = [
    {
        "name": "remember_fact",
        "description": "Save a fact, user preference, contact detail, or note to permanent long-term memory.",
        "parameters": {
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "description": "Name/label of the fact to remember (e.g., 'user_name', 'bannu_phone', 'favorite_music')",
                },
                "value": {
                    "type": "string",
                    "description": "The value or information to store (e.g., 'Yaswanth', '9876543210')",
                },
            },
            "required": ["key", "value"],
        },
    },
    {
        "name": "recall_fact",
        "description": "Search and retrieve facts from permanent long-term memory by key or search query.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search keyword or key to look up (e.g., 'bannu', 'name', 'wifi')",
                }
            },
            "required": ["query"],
        },
    },
    {
        "name": "list_memories",
        "description": "List all stored facts, preferences, and notes in permanent long-term memory.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "forget_fact",
        "description": "Delete a stored fact or entry from permanent long-term memory.",
        "parameters": {
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "description": "Key or fact name to remove from memory",
                }
            },
            "required": ["key"],
        },
    },
]

TOOL_FUNCTIONS = {
    "remember_fact": remember_fact,
    "recall_fact": recall_fact,
    "list_memories": list_memories,
    "forget_fact": forget_fact,
}
