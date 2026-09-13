"""
JARVIS Permanent Memory System
Stores user facts, preferences, contact info, and notes persistently in memory.json.
Provides tools to remember, recall, list, and forget facts.
"""

import os
import json
import logging
from typing import Dict, Any
from core.settings_manager import STORAGE_DIR
from core.security import security_guard

logger = logging.getLogger("jarvis.memory")

MEMORY_FILE = os.path.join(STORAGE_DIR, "memory.json")


def _load_memory() -> Dict[str, Any]:
    """Load memories from secure storage memory.json file."""
    if not os.path.exists(MEMORY_FILE):
        return {}
    try:
        with open(MEMORY_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        logger.error(f"Error loading memory.json: {e}")
        return {}


def _save_memory(memory: Dict[str, Any]) -> bool:
    """Save memories to secure storage memory.json file."""
    try:
        os.makedirs(STORAGE_DIR, exist_ok=True)
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
        from core.state_database import state_db
        res = state_db.save_memory(key=key, value=value)
        # Asynchronously/safely persist dense vector in memory_vectors table
        try:
            from core.hybrid_search import hybrid_searcher
            hybrid_searcher.embed_and_store(key, f"{key}: {value}")
        except Exception as ve:
            logger.debug(f"Vector embedding update error: {ve}")
        return res
    except Exception as e:
        logger.error(f"Save memory error: {e}")
        return {"status": "error", "message": str(e)}


def recall_fact(query: str) -> dict:
    """
    Search and retrieve facts from permanent memory by key or keyword.
    
    Args:
        query: Search term or key to look up (e.g., 'bannu', 'name', 'food').
    """
    try:
        from core.state_database import state_db
        return state_db.recall_memory(query=query)
    except Exception as e:
        logger.error(f"Recall memory error: {e}")
        return {"status": "error", "message": str(e)}


def hybrid_recall_fact(query: str, limit: int = 5) -> dict:
    """
    Search permanent memory using OpenJarvis-adapted Hybrid Search
    (combining FTS5 BM25 keyword matching with dense semantic cosine similarity via RRF).
    
    Args:
        query: Search term or semantic description to look up.
        limit: Maximum number of ranked results to return (default 5).
    """
    try:
        from core.hybrid_search import hybrid_searcher
        results = hybrid_searcher.search(query=query, limit=limit)
        return {
            "status": "success",
            "query": query,
            "count": len(results),
            "results": results
        }
    except Exception as e:
        logger.error(f"Hybrid recall error: {e}")
        return recall_fact(query)


def list_memories() -> dict:
    """
    List all stored permanent memories and facts.
    """
    try:
        from core.state_database import state_db
        return state_db.list_all_memories()
    except Exception as e:
        logger.error(f"List memories error: {e}")
        return {"status": "error", "message": str(e)}


def forget_fact(key: str) -> dict:
    """
    Delete a fact from permanent memory.
    
    Args:
        key: The key or fact name to forget/delete.
    """
    try:
        from core.state_database import state_db
        return state_db.delete_memory(key=key)
    except Exception as e:
        logger.error(f"Forget memory error: {e}")
        return {"status": "error", "message": str(e)}


def get_all_memories_summary() -> str:
    """Return a formatted string of all memories for auto-injection into System Prompt."""
    try:
        from core.state_database import state_db
        return state_db.get_summary_text()
    except Exception as e:
        logger.error(f"Memory summary error: {e}")
        return "No saved memories yet."


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
        "name": "hybrid_recall_fact",
        "description": "Advanced hybrid search (BM25 + Semantic Vector RRF) over permanent long-term memory.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search query or natural language description to find in memory",
                },
                "limit": {
                    "type": "integer",
                    "description": "Maximum number of results to return (default 5)",
                },
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
    "hybrid_recall_fact": hybrid_recall_fact,
    "list_memories": list_memories,
    "forget_fact": forget_fact,
}
