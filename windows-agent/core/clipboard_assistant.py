"""
MAX AI — Clipboard AI Floating Actions (P2-7)
Extracts contextual, passive action suggestions from copied clipboard text
without auto-executing actions, protecting credentials and preventing prompt injection.
"""

import re
import logging
from typing import Dict, Any, List, Optional

from core.security import security_guard

logger = logging.getLogger("MAXWindowsAgent.ClipboardAssistant")


class ClipboardAssistant:
    """
    Analyzes copied text to provide structured suggestions:
    - [Explain] for errors, code, or technical terms
    - [Fix] for stack traces or syntax errors
    - [Summarize] for long text passages
    - [Translate] for foreign language text
    - [Search] for questions or lookup terms
    """

    TRACEBACK_REGEX = re.compile(r'Traceback\s*\(most\s+recent\s+call\s+last\):|([A-Za-z0-9_]+Error:\s*.+)', re.IGNORECASE)
    CODE_REGEX = re.compile(r'(def\s+\w+|class\s+\w+|import\s+\w+|const\s+\w+|function\s+\w+|public\s+class|\{.*\})', re.DOTALL)
    URL_REGEX = re.compile(r'https?://[^\s]+', re.IGNORECASE)

    @classmethod
    def analyze_clipboard(cls, text: str) -> Dict[str, Any]:
        """
        Analyzes clipboard text and returns contextual recommended actions.
        Does NOT execute any action automatically.
        """
        if not text or not isinstance(text, str) or not text.strip():
            return {
                "status": "empty",
                "actions": [],
                "content_type": "empty",
                "preview": ""
            }

        clean_text = text.strip()
        safe_preview = security_guard.mask_secrets(clean_text[:120])
        safe_preview = safe_preview.replace("<PASSIVE_CONTEXT>", "").replace("</PASSIVE_CONTEXT>", "")

        suggested_actions: List[Dict[str, str]] = []
        content_type = "plain_text"

        # 1. Check for Python / System Tracebacks
        if cls.TRACEBACK_REGEX.search(clean_text):
            content_type = "traceback"
            suggested_actions.extend([
                {"action": "fix_code", "label": "Fix Error", "intent": "Diagnose and fix code error with CodeRepairAgent"},
                {"action": "explain_error", "label": "Explain Error", "intent": "Explain the root cause of this error"},
                {"action": "search_error", "label": "Search Solution", "intent": "Search online for solutions to this traceback"}
            ])

        # 2. Check for Source Code
        elif cls.CODE_REGEX.search(clean_text):
            content_type = "source_code"
            suggested_actions.extend([
                {"action": "explain_code", "label": "Explain Code", "intent": "Explain what this code does"},
                {"action": "optimize_code", "label": "Optimize", "intent": "Review code for performance and style improvements"},
                {"action": "generate_tests", "label": "Generate Tests", "intent": "Generate unit tests for this code"}
            ])

        # 3. Check for URLs
        elif cls.URL_REGEX.search(clean_text):
            content_type = "url"
            suggested_actions.extend([
                {"action": "open_url", "label": "Open Website", "intent": "Open URL in browser"},
                {"action": "summarize_webpage", "label": "Summarize Page", "intent": "Extract content and summarize webpage"}
            ])

        # 4. Check for Long Text (> 150 chars)
        elif len(clean_text) > 150:
            content_type = "long_text"
            suggested_actions.extend([
                {"action": "summarize", "label": "Summarize", "intent": "Provide a bulleted summary of this text"},
                {"action": "translate", "label": "Translate", "intent": "Translate this text"},
                {"action": "proofread", "label": "Proofread", "intent": "Check spelling, grammar, and clarity"}
            ])

        # 5. Short Search Queries / Questions
        else:
            content_type = "short_query"
            suggested_actions.extend([
                {"action": "search_fast", "label": "Fast Search", "intent": "Search web for this term via ParallelSearchRacer"},
                {"action": "explain", "label": "Explain", "intent": "Explain meaning or definition"}
            ])

        return {
            "status": "success",
            "content_type": content_type,
            "char_count": len(clean_text),
            "preview": safe_preview,
            "suggested_actions": suggested_actions,
            "requires_user_trigger": True
        }


# Global singleton instance
clipboard_assistant = ClipboardAssistant()
