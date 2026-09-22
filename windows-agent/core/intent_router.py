"""
MAX AI — Intent Router & Pre-Routing Gateway
Provides deterministic pre-routing classification and routing guards to prevent
unnecessary web search calls for arithmetic, basic reasoning, conversation, and general knowledge.
"""

import re
import math
import logging
from enum import Enum
from typing import Dict, Any, Optional, Tuple, NamedTuple

logger = logging.getLogger("MAX.IntentRouter")


class IntentCategory(str, Enum):
    FORM_FILL = "FORM_FILL"
    FORM_ANALYZE = "FORM_ANALYZE"
    CALCULATION = "CALCULATION"
    GENERAL_KNOWLEDGE = "GENERAL_KNOWLEDGE"
    CONVERSATION = "CONVERSATION"
    CURRENT_INFO = "CURRENT_INFO"
    WEB_RESEARCH = "WEB_RESEARCH"
    LOCAL_COMPUTER = "LOCAL_COMPUTER"
    COMPUTER_USE = "COMPUTER_USE"
    MCP = "MCP"
    FILE_OPERATION = "FILE_OPERATION"
    DEVICE_CONTROL = "DEVICE_CONTROL"
    CODE_TASK = "CODE_TASK"
    COMPLEX_TASK = "COMPLEX_TASK"
    AMBIGUOUS = "AMBIGUOUS"


class RouteDecision(NamedTuple):
    intent: IntentCategory
    tool: Optional[str]
    should_bypass_web_search: bool
    direct_answer: Optional[str]
    reason: str
    confidence: float


# Explicit current / live search indicator patterns
LIVE_SEARCH_PATTERNS = [
    r"\b(latest|current|today|today's|todays|recent|breaking\s+news|right\s+now|live)\b",
    r"\b(current\s+price|market\s+price|stock\s+price|crypto\s+price|price\s+of|cost\s+of)\b",
    r"\b(weather|forecast|temperature\s+in|rain\s+in)\b",
    r"\b(job\s+postings|hiring|vacancies|openings|fresher\s+jobs)\b",
    r"\b(search\s+the\s+web|search\s+online|search\s+google|look\s+up\s+online|look\s+this\s+up|google\s+it)\b",
    r"\b(news\s+about|headlines|score\s+of|match\s+score|live\s+score)\b",
    r"\b(documentation\s+for\s+latest|release\s+notes\s+for|changelog)\b",
]

# Math calculation patterns
MATH_EXPR_PATTERNS = [
    r"^\s*what\s+is\s+([0-9\.\s\+\-\*\/\^\(\)\%xX]|multiplied\s+by|divided\s+by|plus|minus|times|\*|\/|\+|\-)+\s*\??\s*$",
    r"^\s*calculate\s+([0-9\.\s\+\-\*\/\^\(\)\%xX]|multiplied\s+by|divided\s+by|plus|minus|times|\*|\/|\+|\-)+\s*\??\s*$",
    r"^\s*multiply\s+([0-9\.]+)\s+(by|with|\*)\s+([0-9\.]+)\s*\??\s*$",
    r"^\s*divide\s+([0-9\.]+)\s+(by|\/)\s+([0-9\.]+)\s*\??\s*$",
    r"^\s*add\s+([0-9\.]+)\s+(and|to|\+)\s+([0-9\.]+)\s*\??\s*$",
    r"^\s*subtract\s+([0-9\.]+)\s+from\s+([0-9\.]+)\s*\??\s*$",
    r"^\s*([0-9\.]+)\s*(multiplied\s+by|times|\*|x)\s*([0-9\.]+)\s*\??\s*$",
    r"^\s*([0-9\.]+)\s*(divided\s+by|\/)\s*([0-9\.]+)\s*\??\s*$",
    r"^\s*([0-9\.]+)\s*(\+|\-)\s*([0-9\.]+)\s*\??\s*$",
    r"^\s*(what\s+is\s+)?([0-9\.]+)\s*(percent|\%)\s+of\s+([0-9\.]+)\s*\??\s*$",
    r"^\s*now\s+multiply\s+(that|the\s+result|it)\s+by\s+([0-9\.]+)\s*\??\s*$",
]

# General knowledge patterns (stable facts, definitions, encyclopedic queries)
GENERAL_KNOWLEDGE_PATTERNS = [
    r"^\s*(tell\s+me\s+(a|one|some)?\s*(basic|interesting|fun)?\s*fact\s+about)\s+([a-zA-Z0-9_\-\s]+)\s*\??\s*$",
    r"^\s*(what\s+is|what\s+are|who\s+is|who\s+was|who\s+were|where\s+is|where\s+was)\s+([a-zA-Z0-9_\-\s]+)\s*\??\s*$",
    r"^\s*(what\s+does\s+([a-zA-Z0-9_\-]+)\s+(mean|stand\s+for))\s*\??\s*$",
    r"^\s*(define|explain|meaning\s+of)\s+([a-zA-Z0-9_\-\s]+)\s*\??\s*$",
    r"^\s*(what\s+is\s+the\s+capital\s+of)\s+([a-zA-Z0-9_\-\s]+)\s*\??\s*$",
    r"^\s*(who\s+wrote|who\s+discovered|who\s+invented|who\s+created)\s+([a-zA-Z0-9_\-\s]+)\s*\??\s*$",
]

# Conversational phrases
CONVERSATIONAL_PATTERNS = [
    r"^\s*(hi|hello|hey|hey\s+max|hello\s+max|good\s+morning|good\s+afternoon|good\s+evening|good\s+night)\s*[\!\.]*\s*$",
    r"^\s*(how\s+are\s+you|how\s+are\s+you\s+doing|how's\s+it\s+going|hows\s+it\s+going)\s*\??\s*$",
    r"^\s*(who\s+are\s+you|what\s+is\s+your\s+name|who\s+made\s+you|who\s+created\s+you)\s*\??\s*$",
    r"^\s*(thank\s+you|thanks|thanks\s+max|thank\s+you\s+max|bye|goodbye|see\s+you)\s*[\!\.]*\s*$",
    r"^\s*(tell\s+me\s+a\s+joke|make\s+me\s+laugh|say\s+something\s+funny)\s*[\!\.]*\s*$",
]


class IntentRouter:
    """
    Deterministic pre-routing classifier and tool policy enforcer.
    """

    @staticmethod
    def try_evaluate_math(text: str, last_math_result: Optional[float] = None) -> Optional[Tuple[float, str]]:
        """
        Safely and deterministically evaluates standard mathematical queries.
        Returns (numeric_result, formatted_string_answer) or None if not an arithmetic query.
        """
        clean = text.lower().strip()
        # Remove punctuation
        clean_norm = re.sub(r"[\?\,]", "", clean).strip()

        # Handle 'now multiply that result by X'
        followup_match = re.match(r"(?:now\s+)?multiply\s+(?:that|that\s+result|the\s+result|it)\s+by\s+([0-9\.]+)", clean_norm)
        if followup_match and last_math_result is not None:
            multiplier = float(followup_match.group(1))
            res = last_math_result * multiplier
            res_str = f"{int(res)}" if res.is_integer() else f"{res:.4f}".rstrip("0").rstrip(".")
            return res, f"{last_math_result:g} multiplied by {multiplier:g} is {res_str}."

        # Handle 'X percent of Y'
        pct_match = re.search(r"([0-9\.]+)\s*(?:percent|\%)\s+of\s+([0-9\.]+)", clean_norm)
        if pct_match:
            pct = float(pct_match.group(1))
            val = float(pct_match.group(2))
            res = (pct / 100.0) * val
            res_str = f"{int(res)}" if res.is_integer() else f"{res:.4f}".rstrip("0").rstrip(".")
            return res, f"{pct:g}% of {val:g} is {res_str}."

        # Normalize word operators to standard arithmetic tokens
        expr = clean_norm
        expr = re.sub(r"^(what\s+is|calculate|evaluate|tell\s+me)\s+", "", expr).strip()
        expr = re.sub(r"\bmultiplied\s+by\b|\btimes\b|\bx\b", "*", expr)
        expr = re.sub(r"\bdivided\s+by\b", "/", expr)
        expr = re.sub(r"\bplus\b", "+", expr)
        expr = re.sub(r"\bminus\b", "-", expr)

        # Check if expression consists only of valid arithmetic characters
        if re.match(r"^[0-9\.\s\+\-\*\/\(\)\%]+$", expr):
            tokens = re.findall(r"[0-9\.]+|[\+\-\*\/\(\)\%]", expr)
            if tokens and any(op in tokens for op in ("+", "-", "*", "/", "%")):
                try:
                    # Safe arithmetic evaluation via compiled AST / restricted eval
                    code = compile(expr, "<math>", "eval")
                    # Enforce no names or function calls
                    if not code.co_names:
                        res = float(eval(code, {"__builtins__": {}}, {}))
                        res_str = f"{int(res)}" if res.is_integer() else f"{res:.4f}".rstrip("0").rstrip(".")
                        return res, f"{res_str}"
                except Exception:
                    pass

        return None

    @classmethod
    def classify(
        cls,
        text: str,
        last_math_result: Optional[float] = None
    ) -> RouteDecision:
        """
        Classifies incoming user query and determines the appropriate tool routing policy.
        """
        if not text or not text.strip():
            return RouteDecision(
                intent=IntentCategory.CONVERSATION,
                tool=None,
                should_bypass_web_search=True,
                direct_answer="",
                reason="empty input",
                confidence=1.0
            )

        raw_text = text.strip()
        low = raw_text.lower()
        low_norm = re.sub(r"[\?\.\!\,\;]+$", "", low).strip()

        # 1. Check for Explicit Multi-Step / Compound Tasks
        is_multi_step = (
            (" and " in low or " then " in low or " inside it " in low or " after that " in low)
            and any(w in low for w in ("create", "folder", "file", "write", "read", "verify", "open", "delete", "move"))
        ) or any(phrase in low for phrase in ("create three", "create 3", "create five", "create 5", "read all", "verify them"))
        if is_multi_step:
            return RouteDecision(
                intent=IntentCategory.COMPLEX_TASK,
                tool="max_planner",
                should_bypass_web_search=False,
                direct_answer=None,
                reason="multi-step autonomous workflow",
                confidence=0.98
            )

        # 2. Check for Explicit Live Web / Current Information Intent
        is_live_request = any(re.search(pat, low) for pat in LIVE_SEARCH_PATTERNS)
        if is_live_request:
            # Weather special case
            if "weather" in low:
                city_match = re.search(r"weather\s+(?:in|for|at)?\s*([a-zA-Z\s]+)", low)
                city = city_match.group(1).strip() if city_match else "current location"
                return RouteDecision(
                    intent=IntentCategory.CURRENT_INFO,
                    tool="get_weather",
                    should_bypass_web_search=False,
                    direct_answer=None,
                    reason="current weather query",
                    confidence=0.95
                )

            return RouteDecision(
                intent=IntentCategory.CURRENT_INFO,
                tool="search_web_fast",
                should_bypass_web_search=False,
                direct_answer=None,
                reason="explicit current-information request",
                confidence=0.95
            )

        # 3. Check for Deterministic Arithmetic / Calculation
        math_eval = cls.try_evaluate_math(raw_text, last_math_result)
        if math_eval is not None:
            _, ans_str = math_eval
            return RouteDecision(
                intent=IntentCategory.CALCULATION,
                tool=None,
                should_bypass_web_search=True,
                direct_answer=ans_str,
                reason="deterministic arithmetic",
                confidence=0.99
            )

        # 4. Check for Conversational / Greeting / Identity Queries
        if any(re.match(pat, low) or re.match(pat, low_norm) for pat in CONVERSATIONAL_PATTERNS):
            return RouteDecision(
                intent=IntentCategory.CONVERSATION,
                tool=None,
                should_bypass_web_search=True,
                direct_answer=None,
                reason="conversational interaction",
                confidence=0.95
            )

        # 5. Check for MCP (Model Context Protocol) External Tools
        mcp_patterns = [
            r"\b(list|show|get)\s+(?:all\s+)?(?:available\s+)?(?:external|mcp)\s+tools\b",
            r"\b(mcp\s+list|list\s+mcp|mcp\s+discover|discover\s+mcp)\b",
            r"\b(read|get|list|fetch)\s+(?:my\s+)?github\s+(?:issues|prs|pull\s+requests|repositories|repos)\b",
            r"\b(mcp\s+call|call\s+mcp|execute\s+mcp)\b",
            r"\b(query|search)\s+(?:external|postgres|mysql|sqlite)\s+(?:database|db)\b"
        ]
        if any(re.search(pat, low) or re.search(pat, low_norm) for pat in mcp_patterns):
            tool = "mcp_list_tools" if any(w in low for w in ("list", "show", "discover")) else "mcp_call_tool"
            return RouteDecision(
                intent=IntentCategory.MCP,
                tool=tool,
                should_bypass_web_search=True,
                direct_answer=None,
                reason="external MCP tool request",
                confidence=0.96
            )

        # 6. Check for Filesystem Action (Native)
        file_patterns = [
            r"\b(create|make|write|read|delete|remove|move|copy|rename)\b.*\b(folder|directory|file|text\s+file)\b",
            r"\b(desktop|downloads|documents)\b.*\b(folder|file)\b"
        ]
        if any(re.search(pat, low) or re.search(pat, low_norm) for pat in file_patterns):
            return RouteDecision(
                intent=IntentCategory.FILE_OPERATION,
                tool="filesystem_tool",
                should_bypass_web_search=True,
                direct_answer=None,
                reason="filesystem operation",
                confidence=0.95
            )

        # 7. Check for Local Computer Action (Apps, Volume, Windows control - Native)
        app_patterns = [
            r"^\s*(open|launch|start|close|quit|kill)\s+([a-zA-Z0-9_\-\s]+)$",
            r"\b(volume|mute|unmute|brightness|screenshot|lock\s+screen|battery)\b",
            r"\b(maximize|minimize|restore|snap|switch\s+to)\b"
        ]
        if any(re.search(pat, low) or re.search(pat, low_norm) for pat in app_patterns) and not any(w in low for w in ("website", "url", "youtube", "google", "button", "box", "field")):
            return RouteDecision(
                intent=IntentCategory.LOCAL_COMPUTER,
                tool="windows_automation",
                should_bypass_web_search=True,
                direct_answer=None,
                reason="local computer action",
                confidence=0.95
            )

        # 7.5 Check for Form Understanding & Form Filling Action
        form_analyze_patterns = [
            r"\b(what('?s|\s+is)\s+missing\s+from\s+this\s+form|tell\s+me\s+what\s+is\s+missing|what\s+info(?:rmation)?\s+is\s+missing)\b",
            r"\b(analyze\s+(?:this|the)?\s*form|inspect\s+(?:this|the)?\s*form|what\s+fields\s+are\s+on\s+this\s+form)\b",
            r"\b(check\s+what\s+fields\s+are\s+missing|check\s+missing\s+info)\b"
        ]
        form_fill_patterns = [
            r"\b(fill\s+this\s+form|fill\s+the\s+form|fill\s+form|fill\s+out\s+(?:the|this)?\s*form)\b",
            r"\b(fill\s+(?:this|the)?\s*(?:job\s+application|application|registration|profile|contact|address))\b",
            r"\b(enter\s+my\s+(?:name|email|phone|details|information))\b",
            r"\b(fill\s+using\s+my\s+resume|use\s+my\s+resume|fill\s+from\s+resume)\b",
            r"\b(select\s+(?:java|python|male|female|[a-z0-9\s]+and[a-z0-9\s]+))\b",
            r"\b(set\s+gender\s+to|choose\s+[a-z\s]+\s+as\s+country|enter\s+today'?s\s+date)\b",
            r"\b(fill\s+all\s+required\s+fields|fill\s+only\s+required\s+fields|fill\s+required\s+fields)\b",
            r"\b(fill\s+the\s+address\s+section|fill\s+everything\s+you\s+can)\b",
            r"\b(fill\s+this\s+registration\s+form\s+but\s+don'?t\s+submit|fill\s+and\s+submit)\b",
            r"\b(set\s+city\s+to|change\s+the\s+email|replace\s+the\s+existing|update\s+this\s+field)\b",
        ]

        if any(re.search(pat, low) or re.search(pat, low_norm) for pat in form_analyze_patterns):
            return RouteDecision(
                intent=IntentCategory.FORM_ANALYZE,
                tool="form_analyze",
                should_bypass_web_search=True,
                direct_answer=None,
                reason="form analysis and missing field query",
                confidence=0.98
            )

        if any(re.search(pat, low) or re.search(pat, low_norm) for pat in form_fill_patterns):
            return RouteDecision(
                intent=IntentCategory.FORM_FILL,
                tool="form_fill",
                should_bypass_web_search=True,
                direct_answer=None,
                reason="form understanding and intelligent form filling",
                confidence=0.98
            )

        # 8. Check for Computer Use Action (Direct Screen & UI Interaction)
        cu_click_patterns = [
            r"^\s*(?:click|tap|press)\s+(?:on\s+)?(?:the\s+)?([a-zA-Z0-9_\-\s@]+?)(?:\s+button|\s+icon|\s+link|\s+tab|\s+menu)?\s*$",
            r"\b(double\s+click|right\s+click)\b",
            r"\bclick\s+(?:on\s+)?(?:the\s+)?(?:address\s+bar|search\s+bar|search\s+button|save\s+button|submit|ok|cancel|close|settings|input|@e[0-9]+)\b"
        ]
        cu_type_patterns = [
            r"^\s*type\s+(.+?)\s+(?:in|into)\s+(?:the\s+)?([a-zA-Z0-9_\-\s@]+)\s*$",
            r"^\s*type\s+(.+?)\s+(?:in|into)\s+notepad\s*$",
            r"\btype\s+this\s+into\s+([a-zA-Z0-9_\-\s]+)\b"
        ]
        cu_observe_patterns = [
            r"\b(observe|inspect|scan|see)\s+(?:the\s+)?(?:screen|desktop|ui|elements)\b",
            r"\b(resolve|find)\s+(?:the\s+)?(?:ui\s+)?target\b",
            r"\bscroll\s+(up|down|left|right)\b"
        ]

        if any(re.search(pat, low) or re.search(pat, low_norm) for pat in cu_click_patterns):
            return RouteDecision(
                intent=IntentCategory.COMPUTER_USE,
                tool="computer_use_click",
                should_bypass_web_search=True,
                direct_answer=None,
                reason="computer use UI click",
                confidence=0.96
            )

        if any(re.search(pat, low) or re.search(pat, low_norm) for pat in cu_type_patterns):
            return RouteDecision(
                intent=IntentCategory.COMPUTER_USE,
                tool="computer_use_type",
                should_bypass_web_search=True,
                direct_answer=None,
                reason="computer use UI type",
                confidence=0.96
            )

        if any(re.search(pat, low) or re.search(pat, low_norm) for pat in cu_observe_patterns):
            tool = "computer_use_observe" if "observe" in low or "inspect" in low else "computer_use_step"
            return RouteDecision(
                intent=IntentCategory.COMPUTER_USE,
                tool=tool,
                should_bypass_web_search=True,
                direct_answer=None,
                reason="computer use screen perception/navigation",
                confidence=0.95
            )

        # 9. Check for Android / Device Control Action
        android_patterns = [
            r"\b(phone|android|mobile|torch|flashlight|whatsapp\s+message|call\s+contact)\b"
        ]
        if any(re.search(pat, low) or re.search(pat, low_norm) for pat in android_patterns) and not ("windows" in low or "desktop" in low):
            return RouteDecision(
                intent=IntentCategory.DEVICE_CONTROL,
                tool="device_tool",
                should_bypass_web_search=True,
                direct_answer=None,
                reason="remote device control",
                confidence=0.92
            )

        # 10. Check for General Knowledge / Static Encyclopedic Inquiries
        if any(re.match(pat, low) or re.match(pat, low_norm) for pat in GENERAL_KNOWLEDGE_PATTERNS):
            return RouteDecision(
                intent=IntentCategory.GENERAL_KNOWLEDGE,
                tool=None,
                should_bypass_web_search=True,
                direct_answer=None,
                reason="stable general knowledge",
                confidence=0.90
            )

        # 11. Fallback: Ambiguous / General Query
        return RouteDecision(
            intent=IntentCategory.AMBIGUOUS,
            tool=None,
            should_bypass_web_search=True,
            direct_answer=None,
            reason="general query without explicit web search indicators",
            confidence=0.70
        )


intent_router = IntentRouter()
