"""
MAX AI — Parallel Web Search Racing (P0-2)
Runs multiple independent search providers concurrently to dramatically reduce search latency,
validating results defensively and cancelling slower work once a valid result is acquired.
"""

import time
import urllib.parse
import asyncio
import logging
import requests
from typing import Dict, Any, List, Optional, Callable

from core.security import security_guard

logger = logging.getLogger("MAXWindowsAgent.SearchRacer")


class ParallelSearchRacer:
    """
    Coordinates concurrent search racing across independent providers:
    - Primary provider (DuckDuckGo instant answers / API)
    - Secondary provider (Wikipedia API / HTML search fallback)
    - Result validation & sanitization
    - Cancellation of slower runner upon winner selection
    - Passive untrusted data boundaries
    """

    def __init__(self, timeout_sec: float = 6.0):
        self.timeout_sec = timeout_sec

    def validate_result(self, result: Dict[str, Any]) -> bool:
        """
        Validates whether a search result is non-empty, contains meaningful text,
        and is free from obvious errors.
        """
        if not isinstance(result, dict):
            return False

        if result.get("status") != "success":
            return False

        # Must have non-trivial content in summary, snippets, or abstract
        content = (
            result.get("summary")
            or result.get("abstract")
            or result.get("content")
            or result.get("answer")
            or ""
        )
        snippets = result.get("snippets") or result.get("results") or []

        if isinstance(content, str) and len(content.strip()) >= 15:
            return True

        if isinstance(snippets, list) and len(snippets) > 0:
            for s in snippets:
                if isinstance(s, (dict, str)) and len(str(s).strip()) >= 15:
                    return True

        return False

    def sanitize_search_text(self, text: str) -> str:
        """
        Sanitizes search result text, masks sensitive secrets/credentials,
        and ensures passive boundaries.
        """
        if not isinstance(text, str):
            return ""
        # 1. Mask secrets
        masked = security_guard.mask_secrets(text)
        # 2. Defend against prompt injection markers in untrusted text
        sanitized = masked.replace("<PASSIVE_CONTEXT>", "").replace("</PASSIVE_CONTEXT>", "")
        return sanitized.strip()

    async def search_duckduckgo(self, query: str) -> Dict[str, Any]:
        """
        Primary search provider: Queries DuckDuckGo Instant Answer API asynchronously.
        """
        loop = asyncio.get_running_loop()

        def _fetch():
            encoded = urllib.parse.quote_plus(query)
            url = f"https://api.duckduckgo.com/?q={encoded}&format=json&no_html=1&skip_disambig=1"
            headers = {"User-Agent": "MAX-AI-SearchRacer/1.0"}
            resp = requests.get(url, headers=headers, timeout=self.timeout_sec)
            resp.raise_for_status()
            data = resp.json()

            abstract = data.get("AbstractText", "").strip()
            heading = data.get("Heading", "").strip()
            answer = data.get("Answer", "").strip()
            related_topics = data.get("RelatedTopics", [])

            snippets = []
            if abstract:
                snippets.append(abstract)
            if answer:
                snippets.append(answer)
            for rt in related_topics[:3]:
                if isinstance(rt, dict) and rt.get("Text"):
                    snippets.append(rt["Text"])

            if not snippets and not abstract:
                return {"status": "empty", "provider": "DuckDuckGo", "message": "No direct answer found"}

            combined_summary = "\n".join(snippets[:3])
            return {
                "status": "success",
                "provider": "DuckDuckGo",
                "query": query,
                "title": heading,
                "summary": self.sanitize_search_text(combined_summary),
                "snippets": [self.sanitize_search_text(s) for s in snippets],
            }

        try:
            return await loop.run_in_executor(None, _fetch)
        except Exception as e:
            logger.debug(f"DuckDuckGo search provider failed: {e}")
            return {"status": "error", "provider": "DuckDuckGo", "message": str(e)}

    async def search_wikipedia(self, query: str) -> Dict[str, Any]:
        """
        Secondary search provider: Queries Wikipedia REST API asynchronously.
        """
        loop = asyncio.get_running_loop()

        def _fetch():
            encoded = urllib.parse.quote(query.strip())
            url = f"https://en.wikipedia.org/api/rest_v1/page/summary/{encoded}"
            headers = {"User-Agent": "MAX-AI-SearchRacer/1.0"}
            resp = requests.get(url, headers=headers, timeout=self.timeout_sec)
            if resp.status_code == 404:
                # Fallback to search query API
                search_url = f"https://en.wikipedia.org/w/api.php?action=opensearch&search={urllib.parse.quote_plus(query)}&limit=3&namespace=0&format=json"
                s_resp = requests.get(search_url, headers=headers, timeout=self.timeout_sec)
                s_resp.raise_for_status()
                s_data = s_resp.json()
                if len(s_data) >= 3 and s_data[2]:
                    descriptions = [d for d in s_data[2] if d]
                    if descriptions:
                        return {
                            "status": "success",
                            "provider": "Wikipedia",
                            "query": query,
                            "title": s_data[1][0] if s_data[1] else query,
                            "summary": self.sanitize_search_text(descriptions[0]),
                            "snippets": [self.sanitize_search_text(d) for d in descriptions],
                        }
                return {"status": "empty", "provider": "Wikipedia", "message": "Article not found"}

            resp.raise_for_status()
            data = resp.json()
            extract = data.get("extract", "").strip()
            title = data.get("title", query)

            if not extract:
                return {"status": "empty", "provider": "Wikipedia", "message": "Empty extract"}

            return {
                "status": "success",
                "provider": "Wikipedia",
                "query": query,
                "title": title,
                "summary": self.sanitize_search_text(extract),
                "snippets": [self.sanitize_search_text(extract)],
            }

        try:
            return await loop.run_in_executor(None, _fetch)
        except Exception as e:
            logger.debug(f"Wikipedia search provider failed: {e}")
            return {"status": "error", "provider": "Wikipedia", "message": str(e)}

    async def race(
        self,
        query: str,
        custom_providers: Optional[List[Callable[[str], Any]]] = None,
        timeout_sec: Optional[float] = None
    ) -> Dict[str, Any]:
        """
        Executes parallel search across available providers and returns the first valid result.
        Cancels remaining runner tasks once a winner is chosen.
        """
        if not query or not query.strip():
            return {"status": "error", "message": "Search query cannot be empty"}

        clean_query = query.strip()
        eff_timeout = timeout_sec or self.timeout_sec
        start_time = time.time()

        providers = custom_providers or [self.search_duckduckgo, self.search_wikipedia]
        tasks = []

        for p in providers:
            tasks.append(asyncio.create_task(p(clean_query)))

        winner_result = None
        pending = set(tasks)

        while pending:
            done, pending = await asyncio.wait(
                pending,
                return_when=asyncio.FIRST_COMPLETED,
                timeout=eff_timeout
            )

            if not done:
                # Timed out
                for t in pending:
                    t.cancel()
                break

            for finished_task in done:
                try:
                    res = finished_task.result()
                    if self.validate_result(res):
                        winner_result = res
                        # Cancel remaining runners
                        for p_task in pending:
                            p_task.cancel()
                        break
                except Exception as ex:
                    logger.debug(f"Search task exception in race: {ex}")

            if winner_result:
                break

        duration_ms = int((time.time() - start_time) * 1000)

        if winner_result:
            winner_result["duration_ms"] = duration_ms
            logger.info(f"🏆 Search race won by [{winner_result.get('provider')}] in {duration_ms}ms for query='{clean_query}'")
            return winner_result

        # If no valid winner was found from racing
        logger.warning(f"Search race found no valid results for query='{clean_query}' in {duration_ms}ms")
        return {
            "status": "error",
            "error_code": "NO_SEARCH_RESULTS",
            "message": f"No valid search results found for '{clean_query}'.",
            "query": clean_query,
            "duration_ms": duration_ms
        }


# Global singleton instance
search_racer = ParallelSearchRacer()
