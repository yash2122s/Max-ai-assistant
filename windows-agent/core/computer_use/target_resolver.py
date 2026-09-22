"""
MAX AI — Layered Target Matching & Disambiguation Resolver
Resolves natural language queries (e.g. 'click Save', 'address bar', 'search box')
against active UIMap elements with confidence scoring and ambiguity prevention.
"""

import re
import difflib
import logging
from typing import List, Dict, Any, Optional, Tuple

from core.computer_use.models import UIElement, UIMap, TargetMatch, ResolutionResult, ElementSource
from core.computer_use.ui_ref_cache import ui_ref_cache

logger = logging.getLogger("MAX.ComputerUse.TargetResolver")


class TargetResolver:
    """Multi-tiered target resolver with ambiguity guards."""

    # Role keywords to identify intended control type from query
    ROLE_KEYWORDS = {
        "button": ["button", "btn", "click on", "press", "tab"],
        "edit": ["edit", "input", "text box", "textbox", "field", "search bar", "address bar", "type into"],
        "checkbox": ["checkbox", "check box", "toggle"],
        "combobox": ["combobox", "combo box", "dropdown", "drop down", "select"],
        "hyperlink": ["link", "hyperlink", "url"],
        "menuitem": ["menu", "menu item", "option"],
        "tabitem": ["tab"],
        "listitem": ["item", "row"],
    }

    def resolve(
        self,
        query: str,
        ui_map: Optional[UIMap] = None,
        min_confidence: float = 0.50,
        ambiguity_margin: float = 0.08,
        expected_generation: Optional[int] = None,
        expected_window: Optional[str] = None
    ) -> ResolutionResult:
        """
        Resolves a user query against the given or cached UIMap using 8-tier resolution.
        Returns a ResolutionResult with matched element, confidence scoring, or ambiguity rejection.
        """
        active_map = ui_map or ui_ref_cache.current_map
        if not active_map or not active_map.elements:
            return ResolutionResult(
                query=query,
                matched=None,
                status="NOT_FOUND",
                message="No UI elements available in current observation."
            )

        clean_query = query.strip()

        # Check generation staleness if requested
        if expected_generation is not None and active_map.generation != expected_generation:
            logger.warning(f"TargetResolver: Active generation {active_map.generation} differs from expected {expected_generation}")

        # Tier 0: Direct @ref match
        if clean_query.startswith("@") and clean_query in active_map.ref_map:
            el = active_map.ref_map[clean_query]
            # Validate bounds and window
            if el.bounds and (el.bounds.width <= 0 or el.bounds.height <= 0):
                logger.warning(f"Direct ref '{clean_query}' has invalid bounds: {el.bounds}")
            match = TargetMatch(
                element=el,
                ref=el.ref,
                confidence=1.0,
                strategy="DIRECT_REF"
            )
            return ResolutionResult(
                query=query,
                matched=match,
                status="FOUND",
                message=f"Resolved direct ref '{clean_query}' to [{el.role}] '{el.name}'"
            )

        # Tier 0.5: Semantic Ordinal Media Resolution ("first video", "first song", "play first song", "top result")
        ordinal_match = self._resolve_ordinal_media_target(clean_query, active_map)
        if ordinal_match:
            return ResolutionResult(
                query=query,
                matched=ordinal_match,
                status="FOUND",
                message=f"Resolved ordinal query '{query}' to {ordinal_match.ref} [{ordinal_match.element.role}] '{ordinal_match.element.name}' (Ad-Filtered, Strategy: {ordinal_match.strategy})"
            )

        # Normalize query: strip common action verbs like "click", "tap", "press", "type into", "open"
        normalized_query = self._normalize_query(clean_query)
        detected_role = self._detect_intended_role(clean_query)

        scored_candidates: List[Tuple[float, str, UIElement]] = []

        # Pass 1: Try resolving with window scoping if expected_window provided
        for el in active_map.elements:
            # Bounding box & visibility validation
            if el.bounds and (el.bounds.width <= 0 or el.bounds.height <= 0):
                continue
            if el.extra_attributes.get("is_offscreen") is True or not el.visible:
                continue
            # Window validation if expected_window provided
            if expected_window and el.window:
                ew_low = expected_window.lower()
                elw_low = el.window.lower()
                active_win_low = (active_map.active_window or "").lower()
                match_expected = (ew_low in elw_low or elw_low in ew_low)
                is_active_dialog = bool(active_win_low and (active_win_low in elw_low or elw_low in active_win_low))
                # Accept element if it matches expected window OR is part of current active foreground dialog/window
                if not match_expected and not is_active_dialog:
                    continue

            score, strategy = self._score_element(el, normalized_query, clean_query, detected_role)
            if score >= min_confidence:
                scored_candidates.append((score, strategy, el))

        # Pass 2: Fallback across all active elements if expected_window was stale
        if not scored_candidates and expected_window:
            logger.info("TargetResolver: Pass 1 with expected_window='%s' yielded 0 candidates; falling back to all active elements.", expected_window)
            for el in active_map.elements:
                if el.bounds and (el.bounds.width <= 0 or el.bounds.height <= 0):
                    continue
                if el.extra_attributes.get("is_offscreen") is True or not el.visible:
                    continue
                score, strategy = self._score_element(el, normalized_query, clean_query, detected_role)
                if score >= min_confidence:
                    scored_candidates.append((score, f"{strategy}_STALE_WINDOW_FALLBACK", el))

        if not scored_candidates:
            return ResolutionResult(
                query=query,
                matched=None,
                status="NOT_FOUND",
                message=f"Could not resolve any UI element matching '{query}' with confidence >= {min_confidence}."
            )

        # Sort by score descending
        scored_candidates.sort(key=lambda x: x[0], reverse=True)

        top_score, top_strategy, top_element = scored_candidates[0]

        # Check for ambiguity: if 2nd candidate is within ambiguity_margin and not identical element
        is_ambiguous = False
        competing: List[Dict[str, Any]] = []

        if len(scored_candidates) > 1:
            second_score, second_strategy, second_element = scored_candidates[1]
            if (top_score - second_score) < ambiguity_margin:
                # Compare if they have different names or distinct positions
                if top_element.ref != second_element.ref:
                    is_ambiguous = True
                    competing = [
                        {
                            "ref": cand[2].ref,
                            "role": cand[2].role,
                            "name": cand[2].name or cand[2].text,
                            "score": round(cand[0], 3),
                            "strategy": cand[1]
                        }
                        for cand in scored_candidates[:4]
                    ]

        if is_ambiguous and top_score < 0.95:  # Exact name/ID matches bypass soft ambiguity
            logger.warning(f"⚠️ Ambiguous target resolution for query '{query}': {competing}")
            return ResolutionResult(
                query=query,
                matched=TargetMatch(
                    element=top_element,
                    ref=top_element.ref,
                    confidence=top_score,
                    strategy=top_strategy,
                    is_ambiguous=True,
                    competing_candidates=competing
                ),
                status="AMBIGUOUS",
                message=f"Query '{query}' is ambiguous between multiple elements: {[c['ref'] + ':' + c['name'] for c in competing]}. Please specify more clearly or use @ref."
            )

        match = TargetMatch(
            element=top_element,
            ref=top_element.ref,
            confidence=top_score,
            strategy=top_strategy,
            is_ambiguous=False
        )

        return ResolutionResult(
            query=query,
            matched=match,
            status="FOUND",
            message=f"Resolved '{query}' to {top_element.ref} [{top_element.role}] '{top_element.name}' (Confidence: {round(top_score, 2)}, Strategy: {top_strategy})"
        )

    def _resolve_ordinal_media_target(self, query: str, active_map: UIMap) -> Optional[TargetMatch]:
        """
        Resolves semantic ordinal media requests ('first video', 'first song', 'play first song', 'top result').
        Filters out advertisements/sponsored badges, navigation controls, and non-playable containers,
        ranking true playable results by layout coordinates.
        """
        ORDINAL_PATTERN = re.compile(
            r"^(?:play|open|click|start|choose|select)?\s*(?:the\s+)?(first|1st|top|second|2nd|third|3rd|fourth|4th|last)\s+(?:youtube\s+)?(?:video|song|track|result|music|audio|item)\b",
            re.IGNORECASE
        )
        
        m = ORDINAL_PATTERN.search(query.strip())
        if not m:
            # Also catch specific natural phrasing like "play first song", "first result"
            q_clean = query.strip().lower()
            if q_clean not in ("first video", "first song", "first result", "play first song", "play first video", "play first result", "play 1st song", "play 1st video", "1st video", "1st song", "top video", "top song", "top result"):
                return None
            ordinal_word = "first"
        else:
            ordinal_word = m.group(1).lower()

        target_index = 0
        if ordinal_word in ("second", "2nd"):
            target_index = 1
        elif ordinal_word in ("third", "3rd"):
            target_index = 2
        elif ordinal_word in ("fourth", "4th"):
            target_index = 3
        elif ordinal_word == "last":
            target_index = -1

        # Advertisements and sponsored badges to reject
        AD_KEYWORDS = {
            "ad", "sponsored", "promoted", "advertisement", "ad ·", "ads",
            "why this ad", "google ads", "skip ad", "install", "visit site", "shop now"
        }
        
        # Navigation / header controls to reject
        NAV_KEYWORDS = {
            "search", "search button", "voice search", "search with your voice",
            "guide", "youtube", "home", "shorts", "subscriptions", "library", "history",
            "your videos", "watch later", "liked videos", "sign in", "notifications",
            "settings", "create", "apps", "filters", "filter", "all", "gaming", "podcasts",
            "skip navigation", "back", "forward", "refresh", "close", "minimize", "maximize"
        }

        # Step 1: Detect bounding boxes of explicit AD badges to exclude any overlapping cards
        ad_regions = []
        for el in active_map.elements:
            name_low = (el.name or "").lower().strip()
            text_low = (el.text or "").lower().strip()
            if name_low in ("ad", "sponsored", "ad ·", "promoted") or text_low in ("ad", "sponsored", "ad ·", "promoted"):
                if el.bounds:
                    ad_regions.append(el.bounds)

        candidates: List[UIElement] = []

        # Step 2: Enumerate candidate elements
        for el in active_map.elements:
            name = (el.name or "").strip()
            text = (el.text or "").strip()
            label = name or text
            label_low = label.lower()
            role = (el.role or "").lower()

            # Rule A: Must not be an advertisement or overlap an ad badge
            if any(ad_kw == label_low or f" {ad_kw} " in f" {label_low} " for ad_kw in AD_KEYWORDS):
                continue
            
            is_inside_ad = False
            if el.bounds and ad_regions:
                for ad_b in ad_regions:
                    # Check if element center or top is in proximity/inside an ad container (within 80px vertically)
                    if abs(el.bounds.top - ad_b.top) < 80 and abs(el.bounds.left - ad_b.left) < 300:
                        is_inside_ad = True
                        break
            if is_inside_ad:
                continue

            # Rule B: Must not be navigation or non-content header
            if any(nav_kw == label_low or label_low.startswith(f"{nav_kw} ") for nav_kw in NAV_KEYWORDS):
                continue

            # Rule C: Position must be below top navigation/search bar (e.g. Y >= 80)
            if el.bounds and el.bounds.top < 75:
                continue

            # Rule D: Must be interactive or represent a content card / title link
            # Typical video links have length >= 4 and are not purely numerical durations
            if len(label) < 4:
                continue
            if re.match(r"^\d+:\d+(?::\d+)?$", label):  # duration timestamp like 3:45
                continue
            if re.match(r"^[\d\.]+\s*(?:k|m|b)?\s*views$", label_low):  # view count like 10M views
                continue

            # Valid roles for video title link, video card, or button/hyperlink
            if role in ("hyperlink", "button", "custom", "listitem", "pane", "text", "document"):
                # Ensure element has real dimensions
                if el.bounds and el.bounds.width > 20 and el.bounds.height > 10:
                    candidates.append(el)

        if not candidates:
            return None

        # Step 3: Sort candidates by screen layout coordinates
        # Primary: top coordinate (ascending, top-to-bottom)
        # Secondary: left coordinate (ascending, left-to-right)
        candidates.sort(key=lambda e: (e.bounds.top if e.bounds else 0, e.bounds.left if e.bounds else 0))

        # Filter out redundant sub-elements that share almost identical bounding boxes
        unique_candidates: List[UIElement] = []
        for cand in candidates:
            if not unique_candidates:
                unique_candidates.append(cand)
                continue
            prev = unique_candidates[-1]
            # If within 15px vertically and horizontally, prefer hyperlink/button over generic pane/text
            if cand.bounds and prev.bounds and abs(cand.bounds.top - prev.bounds.top) < 15 and abs(cand.bounds.left - prev.bounds.left) < 30:
                if cand.role in ("hyperlink", "button") and prev.role not in ("hyperlink", "button"):
                    unique_candidates[-1] = cand
                continue
            unique_candidates.append(cand)

        if not unique_candidates:
            return None

        idx = target_index if target_index < len(unique_candidates) else 0
        if target_index == -1:
            idx = -1

        chosen = unique_candidates[idx]
        logger.info(f"🎯 Ordinal Media Target Resolver selected [{chosen.role}] '{chosen.name}' at ({chosen.center.x}, {chosen.center.y}) for '{query}'")

        return TargetMatch(
            element=chosen,
            ref=chosen.ref,
            confidence=0.98,
            strategy="SEMANTIC_ORDINAL_MEDIA",
            is_ambiguous=False
        )

    def _normalize_query(self, query: str) -> str:
        q = query.lower()
        # Remove common action words
        patterns = [
            r"^(click|press|tap|select|open|type\s+into|hit|focus|switch\s+to|choose)\s+",
            r"\s+(button|btn|box|field|tab|link|menu|icon)$"
        ]
        for p in patterns:
            q = re.sub(p, "", q).strip()
        return q

    def _detect_intended_role(self, query: str) -> Optional[str]:
        q_lower = query.lower()
        for role, kws in self.ROLE_KEYWORDS.items():
            for kw in kws:
                if kw in q_lower:
                    return role
        return None

    def _score_element(
        self,
        el: UIElement,
        norm_query: str,
        raw_query: str,
        detected_role: Optional[str]
    ) -> Tuple[float, str]:
        el_name = (el.name or "").lower().strip()
        el_text = (el.text or "").lower().strip()
        el_auto_id = (el.automation_id or "").lower().strip()
        el_role = (el.role or "").lower().strip()

        # 0. Semantic Dialog Controls (Save As / Open / Common Dialogs)
        # 0A. Filename Edit Field in Save As / Open dialogs
        if norm_query in ("file name", "filename", "file_name", "name", "file name field", "filename field", "file name box"):
            if el_role in ("edit", "combobox", "custom") and (
                el_auto_id in ("1001", "filenamecontrolhost", "appcontrolhost")
                or el_name in ("file name:", "file name", "file name box", "filename")
                or "file name" in el_name
            ):
                return 0.99, "SEMANTIC_FILENAME_FIELD"

        # 0B. Save Button (distinguish from 'Save as type' combobox)
        if norm_query in ("save", "save button", "save btn", "save file"):
            if el_role in ("button", "splitbutton") and el_name in ("save", "&save", "save as"):
                return 0.98, "SEMANTIC_SAVE_BUTTON"
            elif el_role == "combobox" and "type" in el_name:
                # Demote combobox when looking for save button
                return 0.40, "DEMOTED_COMBOBOX_FOR_SAVE"

        # 0C. Save As Menu Item
        if norm_query in ("save as", "save as...", "save_as", "save as menu"):
            if el_role in ("menuitem", "button") and ("save as" in el_name or "save as" in el_text):
                return 0.99, "SEMANTIC_SAVE_AS_MENUITEM"

        # 0D. Overwrite / Confirmation Dialog (Yes, Replace, Confirm)
        if norm_query in ("yes", "replace", "confirm", "yes button", "ok"):
            if el_role in ("button", "custom") and el_name in ("yes", "&yes", "replace", "ok"):
                return 0.99, "SEMANTIC_CONFIRM_BUTTON"

        # 1. Exact Match on AutomationId
        if el_auto_id and (norm_query == el_auto_id or raw_query.lower() == el_auto_id):
            return 1.0, "EXACT_AUTOMATION_ID"

        # 2. Exact Match on Name / Text
        if el_name and norm_query == el_name:
            return 0.98, "EXACT_NAME"
        if el_text and norm_query == el_text:
            return 0.96, "EXACT_TEXT"

        # 3. Substring / Prefix Match
        if el_name and norm_query in el_name:
            # Score proportional to coverage
            coverage = len(norm_query) / max(len(el_name), 1)
            score = 0.80 + (0.15 * coverage)
            if detected_role and detected_role == el_role:
                score += 0.04
            return min(0.95, score), "SUBSTRING_NAME"

        if el_name and el_name in norm_query:
            coverage = len(el_name) / max(len(norm_query), 1)
            score = 0.75 + (0.15 * coverage)
            return min(0.92, score), "REVERSE_SUBSTRING_NAME"

        # 4. Role Match + Fuzzy Match
        ratio = 0.0
        if el_name:
            ratio = difflib.SequenceMatcher(None, norm_query, el_name).ratio()
        if el_text and not ratio:
            ratio = difflib.SequenceMatcher(None, norm_query, el_text).ratio()

        if ratio >= 0.70:
            score = 0.60 + (0.25 * ratio)
            if detected_role and detected_role == el_role:
                score += 0.08
            return min(0.90, score), f"FUZZY_RATIO_{int(ratio*100)}"

        # 5. Structured UIA relationship / Container
        if el.role in ("pane", "group", "window", "document") and (norm_query in el_name or norm_query in el_text):
            return 0.70, "STRUCTURED_CONTAINER"

        # 6. OCR / Visual text locator match
        if getattr(el, "source", None) == ElementSource.OCR or "ocr" in str(getattr(el, "source", "")).lower():
            if norm_query in el_text or norm_query in el_name:
                return 0.78, "OCR_TEXT_MATCH"

        # 7. Fallback: Role only with weak keyword
        if detected_role and detected_role == el_role:
            # Check if any word from query is in name
            q_words = [w for w in norm_query.split() if len(w) > 2]
            for w in q_words:
                if w in el_name or w in el_text:
                    return 0.65, "ROLE_AND_KEYWORD"

        return 0.0, "NO_MATCH"


target_resolver = TargetResolver()
