"""
MAX AI — Canonical JSON Serializer & SHA-256 Checksum Engine (Python)
Produces byte-equivalent canonical JSON representations across Kotlin and Python.
"""

import json
import hashlib
from typing import Dict, Any, Union
from .skill_definition import SkillDefinition


class CanonicalJsonSerializer:

    @classmethod
    def to_canonical_json(cls, skill_data: Union[SkillDefinition, Dict[str, Any]]) -> str:
        """
        Serializes skill data into deterministic canonical JSON:
        - Strips `checksum` and `signature`
        - Recursively sorts dictionary keys lexicographically
        - Compact representation (no insignificant whitespace)
        - Normalizes line endings to \n
        """
        raw_dict = skill_data.to_dict() if isinstance(skill_data, SkillDefinition) else skill_data
        stripped = cls._strip_checksum_and_signature(raw_dict)
        sorted_data = cls._canonicalize_element(stripped)
        return json.dumps(sorted_data, separators=(',', ':'), ensure_ascii=False)

    @classmethod
    def compute_checksum(cls, skill_data: Union[SkillDefinition, Dict[str, Any]]) -> str:
        """Computes the SHA-256 hex digest over canonical JSON bytes."""
        canonical_str = cls.to_canonical_json(skill_data)
        return hashlib.sha256(canonical_str.encode("utf-8")).hexdigest()

    @classmethod
    def verify_checksum(cls, skill_data: Union[SkillDefinition, Dict[str, Any]]) -> bool:
        """
        Verifies declared checksum against computed canonical SHA-256 digest.
        Returns True if valid or unsigned, False if mismatched.
        """
        declared = skill_data.checksum if isinstance(skill_data, SkillDefinition) else skill_data.get("checksum", "")
        if not declared or not declared.strip():
            return True
        computed = cls.compute_checksum(skill_data)
        return declared.strip().lower() == computed.lower()

    @classmethod
    def _strip_checksum_and_signature(cls, item: Any) -> Any:
        if isinstance(item, dict):
            return {
                k: cls._strip_checksum_and_signature(v)
                for k, v in item.items()
                if k not in ("checksum", "signature")
            }
        elif isinstance(item, list):
            return [cls._strip_checksum_and_signature(x) for x in item]
        return item

    @classmethod
    def _canonicalize_element(cls, item: Any) -> Any:
        if isinstance(item, dict):
            # Sort keys lexicographically, skip None values to match GSON default serialization
            return {
                k: cls._canonicalize_element(item[k])
                for k in sorted(item.keys())
                if item[k] is not None
            }
        elif isinstance(item, list):
            return [cls._canonicalize_element(x) for x in item]
        elif isinstance(item, str):
            return item.replace("\r\n", "\n").replace("\r", "\n")
        return item
