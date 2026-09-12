"""
MAX AI — Semantic Context Compression Engine
Provides sliding window history management and semantic micro-summarization
to maintain bounded token context during long autonomous agent executions.
"""

import time
import logging
from typing import Dict, Any, List, Optional, Tuple

logger = logging.getLogger("MAXWindowsAgent.ContextCompressor")


class HermesContextCompressor:
    """
    Manages sliding window context compression and turn summarization.
    """

    def __init__(self, max_tokens: int = 8000, keep_recent_turns: int = 4):
        self.max_tokens = max_tokens
        self.keep_recent_turns = keep_recent_turns

    def estimate_tokens(self, text: str) -> int:
        """Fast heuristic token estimation (~4 chars per token)."""
        if not text:
            return 0
        return max(1, len(text) // 4)

    def estimate_messages_tokens(self, messages: List[Dict[str, Any]]) -> int:
        """Estimates total tokens across message history."""
        total = 0
        for m in messages:
            content = str(m.get("content", ""))
            total += self.estimate_tokens(content) + 4
        return total

    def compress_if_needed(self, messages: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], bool]:
        """
        Compresses conversation history if total tokens exceed max_tokens.
        Returns (compressed_messages, was_compressed).
        """
        if not messages or len(messages) <= (self.keep_recent_turns + 2):
            return messages, False

        current_tokens = self.estimate_messages_tokens(messages)
        if current_tokens <= self.max_tokens:
            return messages, False

        logger.info(f"🗜️ Compressing context: {current_tokens} tokens > {self.max_tokens} token threshold")

        # 1. Preserve System Message
        system_msg = None
        user_start_idx = 0
        if messages and messages[0].get("role") == "system":
            system_msg = messages[0]
            user_start_idx = 1

        # 2. Split into older messages (to summarize) and recent messages (to preserve)
        non_system = messages[user_start_idx:]
        cutoff = max(1, len(non_system) - self.keep_recent_turns)
        older_messages = non_system[:cutoff]
        recent_messages = non_system[cutoff:]

        # 3. Create semantic micro-summary of older messages
        summary_lines = []
        for m in older_messages:
            role = m.get("role", "user").capitalize()
            content = str(m.get("content", ""))[:200].replace("\n", " ")
            summary_lines.append(f"- {role}: {content}")

        summary_text = "[Summary of earlier conversation turns]:\n" + "\n".join(summary_lines)

        # 4. Construct compressed message list
        compressed = []
        if system_msg:
            compressed.append(system_msg)

        compressed.append({
            "role": "system",
            "content": summary_text
        })
        compressed.extend(recent_messages)

        new_tokens = self.estimate_messages_tokens(compressed)
        logger.info(f"✨ Context compressed from {current_tokens} -> {new_tokens} tokens")
        return compressed, True


# Singleton instance
hermes_compressor = HermesContextCompressor()
