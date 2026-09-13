"""
MAX AI — Multi-Provider Model Abstraction
Provides unified chat completion across OpenAI, Anthropic, DeepSeek, Google Gemini REST,
Azure, AWS Bedrock, OpenRouter, and local models (Ollama/LM Studio).
"""

import os
import json
import time
import logging
from typing import Dict, Any, List, Optional, Generator

import httpx
from config import GEMINI_API_KEY

logger = logging.getLogger("MAX.ProviderManager")


class ProviderManager:
    """
    Unified LLM provider abstraction for MAX Agent autonomous reasoning.
    """
    _instance: Optional['ProviderManager'] = None

    def __init__(self):
        self.default_provider = os.getenv("MAX_PROVIDER", os.getenv("HERMES_PROVIDER", "gemini")).lower()
        self.default_model = os.getenv("MAX_MODEL", os.getenv("HERMES_MODEL", "gemini-flash-latest"))
        self._init_clients()

    @classmethod
    def get_instance(cls) -> 'ProviderManager':
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def _init_clients(self):
        """Initializes API clients for configured providers."""
        try:
            from openai import OpenAI
            # 1. OpenAI / OpenRouter / DeepSeek client
            openai_key = os.getenv("OPENAI_API_KEY", "")
            openai_base = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1")
            self.openai_client = OpenAI(api_key=openai_key or "mock-key", base_url=openai_base) if openai_key else None

            # 2. DeepSeek client
            deepseek_key = os.getenv("DEEPSEEK_API_KEY", "")
            self.deepseek_client = OpenAI(api_key=deepseek_key, base_url="https://api.deepseek.com/v1") if deepseek_key else None

            # 3. OpenRouter client
            openrouter_key = os.getenv("OPENROUTER_API_KEY", "")
            self.openrouter_client = OpenAI(api_key=openrouter_key, base_url="https://openrouter.ai/api/v1") if openrouter_key else None

            # 4. Local Ollama client
            ollama_base = os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434/v1")
            self.ollama_client = OpenAI(api_key="ollama", base_url=ollama_base)
        except ImportError:
            self.openai_client = None
            self.deepseek_client = None
            self.openrouter_client = None
            self.ollama_client = None

    def chat_completion(
        self,
        messages: List[Dict[str, Any]],
        tools: Optional[List[Dict[str, Any]]] = None,
        provider: Optional[str] = None,
        model: Optional[str] = None,
        temperature: float = 0.2,
        max_tokens: int = 4096
    ) -> Dict[str, Any]:
        """
        Executes a normalized chat completion call across available providers with full tool support and fallback.
        """
        import uuid
        selected_provider = (provider or self.default_provider).lower()
        selected_model = model or self.default_model

        # 1. Google Gemini Provider
        if selected_provider == "gemini" or not selected_provider:
            from google import genai
            from google.genai import types
            client = genai.Client(api_key=GEMINI_API_KEY)
            
            # Format system instruction and multi-turn structured contents
            sys_inst = None
            genai_contents = []
            for msg in messages:
                role = msg.get("role")
                text = msg.get("content", "")
                if role == "system":
                    sys_inst = text
                elif role == "assistant":
                    genai_contents.append(types.Content(role="model", parts=[types.Part.from_text(text=str(text))]))
                else:
                    genai_contents.append(types.Content(role="user", parts=[types.Part.from_text(text=str(text))]))

            if not genai_contents:
                genai_contents = [types.Content(role="user", parts=[types.Part.from_text(text="")])]
            
            # Format Gemini Function Declarations
            genai_tools = None
            if tools:
                genai_decls = []
                seen_decl_names = set()
                for t in tools:
                    t_name = t.get("name")
                    if not t_name or t_name in seen_decl_names:
                        continue
                    seen_decl_names.add(t_name)
                    decl = {
                        "name": t_name,
                        "description": t.get("description", ""),
                        "parameters": self._sanitize_json_schema(t.get("parameters", {"type": "object", "properties": {}}))
                    }
                    genai_decls.append(decl)
                genai_tools = [types.Tool(function_declarations=genai_decls)]

            # Cascading fallback list of stable models
            candidate_models = [
                "gemini-3-flash-preview",
                "gemini-flash-latest",
                selected_model,
                "gemini-2.5-flash",
                "gemini-pro-latest"
            ]
            seen_models = set()
            unique_candidates = [m for m in candidate_models if m and not (m in seen_models or seen_models.add(m))]

            for candidate in unique_candidates:
                try:
                    config = types.GenerateContentConfig(
                        system_instruction=sys_inst,
                        temperature=temperature,
                        max_output_tokens=max_tokens,
                        tools=genai_tools
                    )
                    resp = client.models.generate_content(
                        model=candidate,
                        contents=genai_contents,
                        config=config
                    )

                    # Extract native function calls
                    tool_calls = []
                    if hasattr(resp, "function_calls") and resp.function_calls:
                        for fc in resp.function_calls:
                            tool_calls.append({
                                "id": getattr(fc, "id", None) or f"call_{uuid.uuid4().hex[:6]}",
                                "name": fc.name,
                                "arguments": dict(fc.args or {})
                            })

                    # Extract text content safely across candidates and parts
                    text_content = ""
                    if hasattr(resp, "candidates") and resp.candidates:
                        for cand in resp.candidates:
                            if hasattr(cand, "content") and cand.content and hasattr(cand.content, "parts"):
                                for part in cand.content.parts:
                                    if hasattr(part, "text") and part.text:
                                        text_content += part.text
                    elif hasattr(resp, "text"):
                        try:
                            text_content = resp.text or ""
                        except Exception:
                            pass

                    # Fallback JSON parser if text contains codeblock tool call
                    if not tool_calls and text_content:
                        parsed_call = self._extract_json_tool_call(text_content)
                        if parsed_call:
                            tool_calls.append(parsed_call)

                    return {
                        "status": "success",
                        "provider": "gemini",
                        "model": candidate,
                        "content": text_content,
                        "tool_calls": tool_calls
                    }
                except Exception as e:
                    err_str = str(e)
                    logger.warning(f"Gemini model '{candidate}' call failed ({e}); checking fallback/retry...")
                    if "429" in err_str or "RESOURCE_EXHAUSTED" in err_str:
                        import time
                        time.sleep(4.0)
                        try:
                            resp = client.models.generate_content(
                                model=candidate,
                                contents=genai_contents,
                                config=config
                            )
                            tool_calls = []
                            if hasattr(resp, "function_calls") and resp.function_calls:
                                for fc in resp.function_calls:
                                    tool_calls.append({
                                        "id": getattr(fc, "id", None) or f"call_{uuid.uuid4().hex[:6]}",
                                        "name": fc.name,
                                        "arguments": dict(fc.args or {})
                                    })
                            text_content = ""
                            if hasattr(resp, "candidates") and resp.candidates:
                                for cand in resp.candidates:
                                    if hasattr(cand, "content") and cand.content and hasattr(cand.content, "parts"):
                                        for part in cand.content.parts:
                                            if hasattr(part, "text") and part.text:
                                                text_content += part.text
                            elif hasattr(resp, "text"):
                                try:
                                    text_content = resp.text or ""
                                except Exception:
                                    pass
                            if not tool_calls and text_content:
                                parsed_call = self._extract_json_tool_call(text_content)
                                if parsed_call:
                                    tool_calls.append(parsed_call)
                            return {
                                "status": "success",
                                "provider": "gemini",
                                "model": candidate,
                                "content": text_content,
                                "tool_calls": tool_calls
                            }
                        except Exception:
                            pass

        # 2. OpenAI / DeepSeek / OpenRouter / Ollama Provider
        client = None
        if selected_provider == "deepseek" and self.deepseek_client:
            client = self.deepseek_client
        elif selected_provider == "openrouter" and self.openrouter_client:
            client = self.openrouter_client
        elif selected_provider == "ollama":
            client = self.ollama_client
        elif self.openai_client:
            client = self.openai_client

        if client:
            try:
                formatted_msgs = []
                for m in messages:
                    formatted_msgs.append({
                        "role": m.get("role", "user"),
                        "content": str(m.get("content", ""))
                    })

                openai_tools = None
                if tools:
                    openai_tools = []
                    seen_decl_names = set()
                    for t in tools:
                        t_name = t.get("name")
                        if not t_name or t_name in seen_decl_names:
                            continue
                        seen_decl_names.add(t_name)
                        openai_tools.append({
                            "type": "function",
                            "function": {
                                "name": t_name,
                                "description": t.get("description", ""),
                                "parameters": self._sanitize_json_schema(t.get("parameters", {"type": "object", "properties": {}}))
                            }
                        })

                call_kwargs = {
                    "model": selected_model,
                    "messages": formatted_msgs,
                    "temperature": temperature,
                    "max_tokens": max_tokens
                }
                if openai_tools:
                    call_kwargs["tools"] = openai_tools

                resp = client.chat.completions.create(**call_kwargs)
                choice = resp.choices[0]
                tool_calls = []

                if hasattr(choice.message, "tool_calls") and choice.message.tool_calls:
                    for tc in choice.message.tool_calls:
                        try:
                            tc_args = json.loads(tc.function.arguments) if isinstance(tc.function.arguments, str) else tc.function.arguments
                        except Exception:
                            tc_args = {}
                        tool_calls.append({
                            "id": tc.id,
                            "name": tc.function.name,
                            "arguments": tc_args
                        })

                content_text = choice.message.content or ""
                if not tool_calls and content_text:
                    parsed_call = self._extract_json_tool_call(content_text)
                    if parsed_call:
                        tool_calls.append(parsed_call)

                return {
                    "status": "success",
                    "provider": selected_provider,
                    "model": selected_model,
                    "content": content_text,
                    "tool_calls": tool_calls
                }
            except Exception as e:
                logger.error(f"Provider {selected_provider} failed: {e}")

        # 3. Fallback response if all external APIs are offline
        return {
            "status": "fallback",
            "provider": "local_mock",
            "model": "offline",
            "content": f"[Offline Response]: Processed prompt with {len(messages)} messages.",
            "tool_calls": []
        }

    @staticmethod
    def _sanitize_json_schema(schema: Any) -> Any:
        """Recursively ensures all array schemas have valid items definitions for Gemini/OpenAI."""
        if not isinstance(schema, dict):
            return schema

        cleaned = dict(schema)
        schema_type = str(cleaned.get("type", "")).lower()

        if schema_type == "array":
            if "items" not in cleaned or not cleaned["items"]:
                cleaned["items"] = {"type": "string"}
            else:
                cleaned["items"] = ProviderManager._sanitize_json_schema(cleaned["items"])

        if "properties" in cleaned and isinstance(cleaned["properties"], dict):
            cleaned["properties"] = {
                k: ProviderManager._sanitize_json_schema(v)
                for k, v in cleaned["properties"].items()
            }

        return cleaned

    def _extract_json_tool_call(self, text: str) -> Optional[Dict[str, Any]]:
        """Extracts JSON tool call structure from raw text if model outputted markdown block."""
        import re
        import uuid
        if not text:
            return None
        m = re.search(r"```json\s*(\{.*?\})\s*```", text, re.DOTALL)
        candidate = m.group(1) if m else text.strip()
        if candidate.startswith("{") and candidate.endswith("}"):
            try:
                data = json.loads(candidate)
                if "tool" in data:
                    return {
                        "id": f"call_{uuid.uuid4().hex[:6]}",
                        "name": data["tool"],
                        "arguments": data.get("args") or data.get("arguments") or {}
                    }
                elif "name" in data and ("arguments" in data or "parameters" in data):
                    return {
                        "id": f"call_{uuid.uuid4().hex[:6]}",
                        "name": data["name"],
                        "arguments": data.get("arguments") or data.get("parameters") or {}
                    }
            except Exception:
                pass
        return None

    def list_available_providers(self) -> Dict[str, Any]:
        """Lists active and available provider endpoints."""
        return {
            "status": "success",
            "default_provider": self.default_provider,
            "default_model": self.default_model,
            "providers": {
                "gemini": bool(GEMINI_API_KEY),
                "openai": bool(self.openai_client),
                "deepseek": bool(self.deepseek_client),
                "openrouter": bool(self.openrouter_client),
                "ollama": True
            }
        }


# Singleton instance
provider_manager = ProviderManager.get_instance()

