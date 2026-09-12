"""
MAX AI — In-Memory Context Store
Thread-safe, TTL-aware in-memory store for runtime ContextRecord objects and point-in-time snapshots.
"""

import time
import uuid
import threading
import logging
from typing import Dict, List, Optional, Set

from .models import ContextRecord, ContextSnapshot, ContextCategory
from .policy import ContextPolicy, context_policy

logger = logging.getLogger("MAXWindowsAgent.ContextStore")

# Max records per category to avoid unbounded memory growth (e.g. notifications buffer)
MAX_RECORDS_PER_CATEGORY = 20


class ContextStore:
    """
    Thread-safe in-memory store holding active ContextRecords.
    """

    def __init__(self, policy: Optional[ContextPolicy] = None):
        self._policy = policy or context_policy
        self._lock = threading.RLock()
        # category_name -> Dict[record_id, ContextRecord]
        self._records: Dict[str, Dict[str, ContextRecord]] = {}

    def put(self, record: ContextRecord) -> bool:
        """
        Validates, sanitizes, and inserts or updates a ContextRecord.
        Rejects stale records (updated_at < existing.updated_at).
        """
        valid, sanitized, err = self._policy.validate_and_sanitize(record)
        if not valid or sanitized is None:
            logger.warning(f"ContextStore rejected record: {err}")
            return False

        cat_key = sanitized.category.value
        with self._lock:
            if cat_key not in self._records:
                self._records[cat_key] = {}

            existing = self._records[cat_key].get(sanitized.record_id)
            if existing is not None and sanitized.updated_at < existing.updated_at:
                logger.debug(f"Rejected stale record update for '{sanitized.record_id}' ({sanitized.updated_at} < {existing.updated_at})")
                return False

            # Insert / update
            self._records[cat_key][sanitized.record_id] = sanitized

            # Bound category size if exceeds limit
            if len(self._records[cat_key]) > MAX_RECORDS_PER_CATEGORY:
                # Evict oldest record
                oldest_id = min(self._records[cat_key].keys(), key=lambda r_id: self._records[cat_key][r_id].updated_at)
                del self._records[cat_key][oldest_id]

            return True

    def get(self, category: ContextCategory) -> List[ContextRecord]:
        """
        Retrieves all active (non-expired) records for a given category.
        """
        cat_key = category.value if isinstance(category, ContextCategory) else str(category)
        with self._lock:
            cat_dict = self._records.get(cat_key, {})
            active = []
            for record in list(cat_dict.values()):
                if not record.is_expired:
                    active.append(record)
            # Sort by updated_at descending (most recent first)
            active.sort(key=lambda r: r.updated_at, reverse=True)
            return active

    def get_record(self, record_id: str) -> Optional[ContextRecord]:
        """
        Retrieves a specific record by ID if not expired.
        """
        with self._lock:
            for cat_dict in self._records.values():
                record = cat_dict.get(record_id)
                if record and not record.is_expired:
                    return record
            return None

    def remove(self, record_id: str) -> bool:
        """
        Removes a specific record by ID.
        """
        with self._lock:
            for cat_dict in self._records.values():
                if record_id in cat_dict:
                    del cat_dict[record_id]
                    return True
            return False

    def prune_expired(self) -> int:
        """
        Removes all expired records across all categories. Returns count of pruned records.
        """
        pruned_count = 0
        with self._lock:
            for cat_key, cat_dict in list(self._records.items()):
                expired_ids = [r_id for r_id, r in cat_dict.items() if r.is_expired]
                for r_id in expired_ids:
                    del cat_dict[r_id]
                    pruned_count += 1
        return pruned_count

    def snapshot(self, categories: Optional[Set[ContextCategory]] = None) -> ContextSnapshot:
        """
        Generates a point-in-time ContextSnapshot of all active (non-expired) records.
        """
        self.prune_expired()
        snapshot_records: Dict[str, List[ContextRecord]] = {}

        target_cats = set(categories) if categories is not None else set(ContextCategory)

        with self._lock:
            for cat in target_cats:
                active_records = self.get(cat)
                if active_records:
                    snapshot_records[cat.value] = active_records

        return ContextSnapshot(
            snapshot_id=f"snap_{uuid.uuid4().hex[:8]}",
            timestamp=time.time(),
            records=snapshot_records
        )

    def clear(self):
        """Clears all records in store."""
        with self._lock:
            self._records.clear()


# Canonical singleton context store instance
context_store = ContextStore()
