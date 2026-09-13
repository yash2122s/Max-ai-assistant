"""
Real Model Tests for MAX Hybrid Search Engine (Dense Sentence Transformers + FTS5).
Verifies 384-dimensional dense vectors, SQLite persistence, and semantic synonym retrieval.
"""

import pytest
from core.hybrid_search import get_embedding_model, hybrid_searcher, EMBEDDING_DIM
from core.memory import remember_fact, hybrid_recall_fact, forget_fact


def test_real_embedding_model_shape():
    model = get_embedding_model()
    vec = model.encode("Machine learning and artificial intelligence", convert_to_numpy=True)
    assert vec.shape == (EMBEDDING_DIM,)
    assert vec.shape == (384,)


def test_real_semantic_synonym_retrieval():
    # Store fact with specific wording
    remember_fact("user_car", "The user drives a red electric Tesla Model 3")

    # Retrieve using a semantic synonym query that shares NO words with the key: "automobile vehicle EV"
    res = hybrid_recall_fact("automobile vehicle battery EV")
    assert res["status"] == "success"
    assert res["count"] >= 1

    top_hit = res["results"][0]
    assert top_hit["key"] == "user_car"
    assert "cosine_similarity" in top_hit
    assert top_hit["cosine_similarity"] > 0.35
    assert "rrf_score" in top_hit

    # Cleanup
    forget_fact("user_car")
