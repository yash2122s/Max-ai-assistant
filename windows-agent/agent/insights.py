"""
MAX AI — Background Insights & Knowledge Distillation Engine
Analyzes completed conversation turns and extracts implicit user facts,
device preferences, and habits, automatically persisting them into SQLite state DB memories.
"""

import re
import logging
from typing import Dict, Any, List

from core.state_database import state_db

logger = logging.getLogger("MAXWindowsAgent.Insights")


class InsightsEngine:
    """
    Extracts implicit user facts from session turns and persists them to memory.
    """

    FACT_PATTERNS = [
        r"(?:my name is|i am|call me)\s+([A-Za-z0-9_]+)",
        r"(?:my favorite|i prefer|i like)\s+([A-Za-z0-9_\s]+)",
        r"(?:my phone is|phone 1 is|phone 2 is)\s+([A-Za-z0-9_\s]+)",
    ]

    @classmethod
    def distill_insights_from_text(cls, conversation_text: str) -> List[Dict[str, str]]:
        """Scans conversation text for memorable user facts."""
        extracted = []
        if not conversation_text:
            return extracted

        # Check name
        m_name = re.search(r"(?:my name is|i am|nenu)\s+([A-Za-z]+)", conversation_text, re.IGNORECASE)
        if m_name and len(m_name.group(1)) > 2:
            extracted.append({"key": "user_name", "value": m_name.group(1).capitalize()})

        # Check project name
        m_proj = re.search(r"(?:project name is|project is called|project is)\s+([A-Za-z0-9_-]+)", conversation_text, re.IGNORECASE)
        if m_proj:
            extracted.append({"key": "project_name", "value": m_proj.group(1)})

        for item in extracted:
            state_db.save_memory(key=item["key"], value=item["value"], category="auto_insight", source="insights_engine")
            logger.info(f"💡 Background insight extracted & saved: '{item['key']}' -> '{item['value']}'")

        return extracted


# ─── Tool Declarations ────────────────────────────────────────────────────────

def extract_insights(text: str) -> Dict[str, Any]:
    """Manually trigger background insight extraction from conversation text."""
    results = InsightsEngine.distill_insights_from_text(text)
    return {
        "status": "success",
        "extracted_count": len(results),
        "insights": results
    }


INSIGHTS_TOOL_DECLARATIONS = [
    {
        "name": "extract_insights",
        "description": "Analyze text to extract implicit user facts and store them in permanent memory.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "Conversation transcript or text to analyze"
                }
            },
            "required": ["text"]
        }
    }
]

INSIGHTS_TOOL_FUNCTIONS = {
    "extract_insights": extract_insights,
}
