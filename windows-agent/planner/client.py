"""
MAX AI — Gemini REST Planning Client
Generates structured task plans via Gemini REST generateContent API with
strict schema enforcement, dynamic tool catalog injection, and validation gate.
"""

import json
import logging
import time
from typing import Optional, Dict, Any

import requests
from config import GEMINI_API_KEY
from core.security import security_guard
from .schemas import Plan, PlannerError, PlanValidationResult
from .prompt import get_planner_system_prompt
from .validator import PlanValidator, plan_validator

logger = logging.getLogger("MAXWindowsAgent.GeminiPlanner")

DEFAULT_PLANNING_MODEL = "gemini-3.7-flash"
GEMINI_REST_ENDPOINT_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

# Schema definition for Gemini REST API responseSchema parameter
PLAN_RESPONSE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "goal": {
            "type": "STRING",
            "description": "Summary of the user goal"
        },
        "steps": {
            "type": "ARRAY",
            "description": "Sequential steps to accomplish the goal (max 8 steps)",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "id": {
                        "type": "STRING",
                        "description": "Unique step identifier (e.g. step_1, step_2)"
                    },
                    "tool": {
                        "type": "STRING",
                        "description": "Exact name of the tool to execute from the allowed tools catalog"
                    },
                    "args": {
                        "type": "OBJECT",
                        "description": "Key-value arguments matching the tool parameters",
                        "properties": {}
                    },
                    "description": {
                        "type": "STRING",
                        "description": "Human-readable description of this action"
                    },
                    "requires_confirmation": {
                        "type": "BOOLEAN",
                        "description": "Whether this step requires user confirmation"
                    }
                },
                "required": ["id", "tool", "args", "description"]
            }
        }
    },
    "required": ["goal", "steps"]
}


class GeminiPlanningClient:
    """
    HTTP REST Client for Gemini Task Planning.
    """

    def __init__(
        self,
        api_key: Optional[str] = None,
        model_name: str = DEFAULT_PLANNING_MODEL,
        validator: Optional[PlanValidator] = None,
        timeout_seconds: float = 15.0
    ):
        self._api_key = api_key or GEMINI_API_KEY
        self.model_name = model_name
        self.validator = validator or plan_validator
        self.timeout_seconds = timeout_seconds

    def _resolve_api_key(self) -> str:
        """Returns active API key."""
        return self._api_key or GEMINI_API_KEY or ""

    def plan(
        self,
        user_goal: str,
        context: Optional[Dict[str, Any]] = None,
        session_http: Optional[Any] = None
    ) -> PlanValidationResult:
        """
        Decomposes a user goal into a validated Plan object via Gemini REST.
        """
        if not user_goal or not user_goal.strip():
            return PlanValidationResult(
                valid=False,
                error=PlannerError(code="EMPTY_GOAL", message="User goal cannot be empty.")
            )

        api_key = self._resolve_api_key()
        if not api_key or api_key == "your_api_key_here":
            return PlanValidationResult(
                valid=False,
                error=PlannerError(
                    code="AUTH_ERROR",
                    message="Missing Gemini API key. Set GEMINI_API_KEY in environment or .env."
                )
            )

        system_instruction = get_planner_system_prompt()
        user_content = f"User Request: {user_goal.strip()}"
        
        # Inject canonical runtime context block if available
        try:
            from context.builder import build_context_block
            context_block = build_context_block()
            if context_block:
                user_content += f"\n{context_block}"
        except Exception as e:
            logger.debug(f"Planner could not retrieve context block: {e}")

        if context:
            safe_context = json.dumps(context, ensure_ascii=False)
            user_content += f"\n\nExplicit Context:\n{safe_context}"


        # Construct standard Google Gemini REST generateContent payload
        payload = {
            "systemInstruction": {
                "parts": [
                    {"text": system_instruction}
                ]
            },
            "contents": [
                {
                    "parts": [
                        {"text": user_content}
                    ]
                }
            ],
            "generationConfig": {
                "responseMimeType": "application/json",
                "responseSchema": PLAN_RESPONSE_SCHEMA,
                "temperature": 0.1,
                "maxOutputTokens": 1024
            }
        }

        url = GEMINI_REST_ENDPOINT_TEMPLATE.format(model=self.model_name)
        headers = {
            "Content-Type": "application/json",
            "x-goog-api-key": api_key  # Pass key securely in header, never in URL
        }

        client_session = session_http or requests

        start_time = time.time()
        logger.info(f"Sending REST plan request for goal: '{security_guard.mask_secrets(user_goal)}'...")

        try:
            response = client_session.post(
                url,
                json=payload,
                headers=headers,
                timeout=self.timeout_seconds
            )
        except requests.exceptions.Timeout:
            logger.error(f"Planning request timed out after {self.timeout_seconds}s")
            return PlanValidationResult(
                valid=False,
                error=PlannerError(code="TIMEOUT", message=f"Planning request timed out after {self.timeout_seconds}s.")
            )
        except Exception as e:
            logger.error(f"Network error during planning request: {e}")
            return PlanValidationResult(
                valid=False,
                error=PlannerError(code="NETWORK_ERROR", message=f"Failed to communicate with Gemini API: {e}")
            )

        duration_ms = round((time.time() - start_time) * 1000, 2)

        if response.status_code != 200:
            err_msg = f"Gemini API returned HTTP {response.status_code}"
            try:
                err_data = response.json()
                if "error" in err_data:
                    err_msg = f"Gemini API error ({err_data['error'].get('status', response.status_code)}): {err_data['error'].get('message', '')}"
            except Exception:
                pass

            logger.error(f"Planning API error: {err_msg}")
            return PlanValidationResult(
                valid=False,
                error=PlannerError(code=f"HTTP_{response.status_code}", message=err_msg)
            )

        try:
            resp_json = response.json()
            candidates = resp_json.get("candidates", [])
            if not candidates:
                return PlanValidationResult(
                    valid=False,
                    error=PlannerError(code="EMPTY_RESPONSE", message="No planning candidates returned by Gemini.")
                )

            candidate = candidates[0]
            parts = candidate.get("content", {}).get("parts", [])
            if not parts:
                return PlanValidationResult(
                    valid=False,
                    error=PlannerError(code="EMPTY_CONTENT", message="Empty content parts in Gemini response.")
                )

            raw_plan_text = parts[0].get("text", "")
            if not raw_plan_text:
                return PlanValidationResult(
                    valid=False,
                    error=PlannerError(code="EMPTY_PLAN_TEXT", message="Empty text in Gemini response candidate.")
                )

            raw_plan_dict = json.loads(raw_plan_text)

        except json.JSONDecodeError as e:
            logger.error(f"Failed to parse Gemini output as JSON: {e}")
            return PlanValidationResult(
                valid=False,
                error=PlannerError(code="MALFORMED_JSON", message=f"Gemini output is not valid JSON: {e}")
            )
        except Exception as e:
            logger.error(f"Unexpected response parsing error: {e}")
            return PlanValidationResult(
                valid=False,
                error=PlannerError(code="RESPONSE_PARSE_ERROR", message=f"Could not parse Gemini response: {e}")
            )

        # Pass through PlanValidator security & schema gate
        validation_result = self.validator.validate(raw_plan_dict)
        if validation_result.valid:
            logger.info(f"Successfully generated and validated plan ({len(validation_result.plan.steps)} steps) in {duration_ms}ms")
        else:
            logger.warning(f"Generated plan failed validation: {validation_result.error.message}")

        return validation_result


# Canonical singleton planning client instance
planning_client = GeminiPlanningClient()
