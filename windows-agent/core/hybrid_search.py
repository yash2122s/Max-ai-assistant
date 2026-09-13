"""
MAX AI — Real Semantic Hybrid Search Engine (BM25 + Dense Sentence Transformers)
Uses local all-MiniLM-L6-v2 model (384-dimensional dense vectors) combined with
SQLite FTS5 BM25 ranking via Reciprocal Rank Fusion (RRF, k=60).
Persists dense vectors in SQLite 'memory_vectors' table for instant retrieval.
"""

import os
import math
import sqlite3
import logging
import numpy as np
from typing import Dict, Any, List, Optional, Tuple

from config import STATE_DB_PATH

logger = logging.getLogger("MAXWindowsAgent.HybridSearch")

RRF_K = 60
EMBEDDING_DIM = 384

_model_instance = None


def get_embedding_model():
    """Lazily loads local all-MiniLM-L6-v2 SentenceTransformer model."""
    global _model_instance
    if _model_instance is None:
        from sentence_transformers import SentenceTransformer
        logger.info("Loading local SentenceTransformer ('all-MiniLM-L6-v2')...")
        _model_instance = SentenceTransformer("all-MiniLM-L6-v2", local_files_only=True)
        logger.info("✅ Dense embedding model ready (384-dim).")
    return _model_instance


class RealHybridSearcher:
    """
    Production-grade hybrid searcher combining FTS5 BM25 and 384-dim dense vector similarity.
    """

    def __init__(self, db_path: Optional[str] = None):
        self.db_path = db_path or STATE_DB_PATH
        self._init_vector_table()

    def _init_vector_table(self):
        """Ensures persistent vector store table exists in SQLite database."""
        try:
            with sqlite3.connect(self.db_path, timeout=5.0) as conn:
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS memory_vectors (
                        key TEXT PRIMARY KEY,
                        embedding BLOB NOT NULL,
                        updated_at REAL NOT NULL
                    );
                """)
                conn.commit()
        except Exception as e:
            logger.error(f"Error initializing memory_vectors table: {e}")

    def embed_and_store(self, key: str, text: str) -> None:
        """
        Computes 384-dim semantic embedding vector for text and stores it in SQLite.
        """
        if not key or not text:
            return
        try:
            model = get_embedding_model()
            vec = model.encode(text, convert_to_numpy=True, normalize_embeddings=True)
            blob = vec.astype(np.float32).tobytes()

            with sqlite3.connect(self.db_path, timeout=5.0) as conn:
                conn.execute(
                    """
                    INSERT INTO memory_vectors (key, embedding, updated_at)
                    VALUES (?, ?, strftime('%s', 'now'))
                    ON CONFLICT(key) DO UPDATE SET
                        embedding = excluded.embedding,
                        updated_at = excluded.updated_at;
                    """,
                    (key, blob)
                )
                conn.commit()
        except Exception as e:
            logger.error(f"Failed to embed and store memory for '{key}': {e}")

    def search(
        self,
        query: str,
        limit: int = 5,
        min_score: float = 0.01
    ) -> List[Dict[str, Any]]:
        """
        Executes hybrid search:
        1. Queries FTS5 for BM25 text rank
        2. Queries stored 384-dim vectors for dense cosine similarity
        3. Fuses both rankings via Reciprocal Rank Fusion (k=60)
        """
        if not query or not query.strip():
            return []

        query = query.strip()
        results_by_key: Dict[str, Dict[str, Any]] = {}
        bm25_ranks: Dict[str, int] = {}
        vector_ranks: Dict[str, int] = {}

        try:
            with sqlite3.connect(self.db_path, timeout=5.0) as conn:
                conn.row_factory = sqlite3.Row

                # 1. Sparse BM25 via FTS5
                import re
                tokens = re.findall(r'\b[a-zA-Z0-9_-]+\b', query)
                clean_query = " OR ".join(f'"{t}"' for t in tokens if len(t) > 1)
                if clean_query:
                    try:
                        cursor = conn.execute(
                            """
                            SELECT m.key, m.original_key, m.value, m.updated_at, bm25(memories_fts) as rank_score
                            FROM memories_fts f
                            JOIN memories m ON m.key = f.key
                            WHERE memories_fts MATCH ?
                            ORDER BY rank_score ASC
                            LIMIT 50
                            """,
                            (clean_query,)
                        )
                        for rank_idx, row in enumerate(cursor.fetchall(), start=1):
                            key = row["key"]
                            bm25_ranks[key] = rank_idx
                            results_by_key[key] = {
                                "key": key,
                                "original_key": row["original_key"],
                                "value": row["value"],
                                "updated_at": row["updated_at"],
                                "bm25_rank": rank_idx
                            }
                    except Exception as fts_err:
                        logger.debug(f"FTS5 match error: {fts_err}")

                # 2. Dense Semantic Vector Search
                cursor = conn.execute("""
                    SELECT v.key, v.embedding, m.original_key, m.value, m.updated_at
                    FROM memory_vectors v
                    JOIN memories m ON m.key = v.key
                """)
                rows = cursor.fetchall()

                if rows:
                    model = get_embedding_model()
                    query_vec = model.encode(query, convert_to_numpy=True, normalize_embeddings=True).astype(np.float32)

                    keys = []
                    matrix_list = []
                    row_map = {}

                    for r in rows:
                        k = r["key"]
                        blob = r["embedding"]
                        vec = np.frombuffer(blob, dtype=np.float32)
                        if len(vec) == EMBEDDING_DIM:
                            keys.append(k)
                            matrix_list.append(vec)
                            row_map[k] = r

                    if matrix_list:
                        mat = np.stack(matrix_list)  # (N, 384)
                        # Cosine similarity on normalized vectors is dot product
                        sims = np.dot(mat, query_vec)

                        sorted_indices = np.argsort(-sims)
                        for rank_idx, idx in enumerate(sorted_indices[:50], start=1):
                            k = keys[idx]
                            sim = float(sims[idx])
                            vector_ranks[k] = rank_idx

                            if k not in results_by_key:
                                r = row_map[k]
                                results_by_key[k] = {
                                    "key": k,
                                    "original_key": r["original_key"],
                                    "value": r["value"],
                                    "updated_at": r["updated_at"]
                                }
                            results_by_key[k]["cosine_similarity"] = round(sim, 4)
                            results_by_key[k]["vector_rank"] = rank_idx

        except Exception as e:
            logger.error(f"Hybrid search execution error: {e}")
            return []

        # 3. Reciprocal Rank Fusion (RRF)
        fused: List[Dict[str, Any]] = []
        for k, item in results_by_key.items():
            bm25_r = bm25_ranks.get(k, 999)
            vec_r = vector_ranks.get(k, 999)
            rrf_score = (1.0 / (RRF_K + bm25_r)) + (1.0 / (RRF_K + vec_r))
            item["rrf_score"] = round(rrf_score, 6)

            if rrf_score >= min_score:
                fused.append(item)

        fused.sort(key=lambda x: x["rrf_score"], reverse=True)
        return fused[:limit]


HybridSearchEngine = RealHybridSearcher
hybrid_searcher = RealHybridSearcher()
