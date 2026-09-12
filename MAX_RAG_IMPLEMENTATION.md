# MAX Native Kotlin RAG System — Implementation Reference

## 1. Package Structure

All RAG components reside in package `com.example.rag`:

```
com.example.rag
├── chunking
│   └── RagChunker.kt                  # Deterministic semantic chunking engine
├── context
│   └── RagContextBuilder.kt           # Injection-safe prompt builder with character budgets
├── data
│   ├── RagChunkDao.kt                 # Room DAO with batch queries and transactions
│   └── RagChunkEntity.kt              # Room entity mapping table 'rag_chunks'
├── embedding
│   ├── GeminiEmbeddingProvider.kt     # Cloud 768-dim text-embedding-004 provider
│   ├── LocalEmbeddingProvider.kt      # On-device 128-dim subword/ngram feature hashing
│   └── RagEmbeddingProvider.kt        # Common pluggable embedding provider interface
├── engine
│   ├── RagEngine.kt                   # Subsystem coordinator & relevance gate
│   └── RagIndexer.kt                  # Incremental, atomic chunk indexer
├── model
│   ├── RagChunk.kt                    # Domain model with SHA-256 chunk ID computation
│   ├── RagConfig.kt                   # Runtime tuning parameters (topK, weights, bounds)
│   ├── RagDiagnostics.kt              # Observable health & index statistics
│   ├── RagFilters.kt                  # Metadata criteria for targeted retrieval
│   └── RagSearchResult.kt             # Ranked retrieval result with attribution
├── repository
│   └── RagRepository.kt               # Room-backed persistent storage with in-memory caching
└── retrieval
    ├── CosineSimilarity.kt            # Vector math with zero-norm & NaN protection
    └── RagRetriever.kt                # Hybrid dense+sparse rank fusion search engine
```

---

## 2. Integration Points

### 1. Room Database (`AppDatabase.kt`)
- Upgraded schema version from **15** to **16**.
- Added `MIGRATION_15_16` creating table `rag_chunks` and associated indexes.
- Registered `ragChunkDao()`.

### 2. Memory Lifecycle (`MemoryRepository.kt`)
- `addMemory()`: Asynchronously indexes new memory chunks.
- `updateMemory()`: Re-indexes modified memories if `contentHash` changes.
- `deleteById()` / `forgetMemory()`: Removes indexed chunks from Room and cache.
- `deleteAll()`: Clears all RAG chunk tables.

### 3. Universal Memory Import (`UniversalMemoryImporter.kt`)
- Post-commit hook indexes all inserted and updated import memories into RAG asynchronously.

### 4. Assistant Pipeline (`AssistantUseCase.kt`)
- Intercepts non-offline user queries and performs RAG context retrieval via `RagEngine.buildContextForQuery(query)`.
- Prepend contextual memory wrapped in `<MAX_RETRIEVED_MEMORY>` block.

### 5. Settings Management (`SettingsManager.kt` & `SettingsViewModel.kt` & `SettingsScreen.kt`)
- User-controllable toggles for RAG Enablement, Semantic Search, Hybrid Search, Embedding Provider (Local vs. Cloud), and manual Index Rebuild/Clear actions.
- Real-time diagnostic status display (`ragMemoriesIndexed`, `ragChunksIndexed`, `ragStatus`).
