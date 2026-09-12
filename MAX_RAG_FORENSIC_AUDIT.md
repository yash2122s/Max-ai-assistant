# MAX — RAG FORENSIC CODEBASE AUDIT

**Date:** September 1, 2026  
**Auditor:** Senior Android QA, Reliability & AI-Agent Automation Engineer  
**Codebase:** MAX (Native Kotlin + Jetpack Compose)  
**Package:** `com.aistudio.geminilive.abcde`  

---

## 1. Verified Architecture & State

### Database & Storage
- **Room Database:** `AppDatabase.kt` located at `com.example.data.local.AppDatabase`.
- **Current Database Version:** **15** (Confirmed via `AppDatabase.kt` line 29: `version = 15`).
- **Existing Entities:**
  1. `ChatMessage`
  2. `Reminder`
  3. `AutoReplyRule`
  4. `ActionReward`
  5. `InstalledApp`
  6. `ScheduledTask`
  7. `TaskExecutionLog`
  8. `PeriodLog`
  9. `PermanentMemory`
  10. `MemorySyncState`
- **Existing Type Converters:**
  - `SchedulerTypeConverters`
  - `MemoryTypeConverters` (already converts `FloatArray?` ↔ `BLOB`/`ByteArray`).
- **Migration History:**
  - `MIGRATION_9_10`: `period_logs` table.
  - `MIGRATION_10_11`: `permanent_memory` initial table.
  - `MIGRATION_11_12`: `permanent_memory ADD COLUMN embedding BLOB`.
  - `MIGRATION_12_13`: `permanent_memory ADD COLUMN tags TEXT`.
  - `MIGRATION_13_14`: `permanent_memory ADD COLUMN isPrivate INTEGER`.
  - `MIGRATION_14_15`: `permanent_memory ADD COLUMN sourceRecordId, importChecksum, provider, confidence, extractedAt, lastSyncAt, contentHash, metadataJson` + `memory_sync_state` table.
- **Target Migration for RAG:** **15 → 16** (Additive creation of `rag_chunks` table + indexes).

### Canonical Memory Model & Sync
- **Entity:** `com.example.memory.data.PermanentMemory`
  - Primary Key: `memoryId: String` (UUID)
  - Fields: `title`, `content`, `category`, `type`, `source`, `tags`, `pinned`, `enabled`, `usageCount`, `lastUsedAt`, `createdAt`, `updatedAt`, `embedding` (FloatArray?), `isPrivate`, `sourceRecordId`, `importChecksum`, `provider`, `confidence`, `extractedAt`, `lastSyncAt`, `contentHash`, `metadataJson`.
  - Content Hash: `PermanentMemory.computeContentHash(title, content, category, tags)` (SHA-256).
  - Import Checksum: `PermanentMemory.computeImportChecksum(rawContent)` (SHA-256).
- **Repository:** `com.example.memory.data.MemoryRepository`
  - Manages AES-256 encrypted memory records in Room (`MemoryCryptography.encrypt`/`decrypt`).
  - Emits decrypted memories via StateFlow `memoriesMarkdownFlow`.
  - Triggers debounced atomic export to `MAX_DATA/memory/MAX_MEMORY.md` via `MemorySyncService`.
- **Universal Importer:** `com.example.memory.universal.UniversalMemoryImporter`
  - Ingests ChatGPT, Claude, Gemini, Obsidian, and MAX backup data into Room `PermanentMemory` with preview and user confirmation.
- **Health Data Isolation:**
  - Menstrual cycle data in `period_logs` is exported strictly to `MAX_DATA/people/<realPersonId>/period_data.md` and **completely excluded** from `MAX_MEMORY.md` and general RAG.

### AI Assistant & Conversation Flow
- **Domain UseCase:** `com.example.domain.usecase.AssistantUseCase`
  - Orchestrates interactions with singleton `AssistantEngine`.
  - Dispatches offline device commands via `OfflineCommandEngine`.
  - Streams text/audio to `AssistantEngine.sendTextMessage(text)`.
- **Assistant Engine:** `com.example.voice.assistant.AssistantEngine`
  - Manages session state (`SessionStateMachine`), conversation history (`ConversationManager`), audio streaming (`AudioRecorder`, `AudioPlayer`), vision query detection (`ScreenCaptureProvider`), and connection (`ConnectionManager`).
- **Connection Manager:** `com.example.voice.assistant.ConnectionManager`
  - Establishes `GeminiWebSocketClient` for Gemini Live bidirectional voice.
  - Automatically loads memories Markdown from `MemoryRepository` into system instructions.
- **UI Agent / OpenRouter:** `com.example.automation.agent.UiAgentRouter` & `com.example.network.OpenRouterApi`
  - Supports OpenRouter stealth/fallback execution for UI automation.
- **Settings:** `com.example.data.preferences.SettingsManager`
  - Manages hardware-backed `EncryptedSharedPreferences` (`AES256_GCM`) for API keys (`gemini_api_key`, `ui_agent_api_key`) and standard preferences.
- **Master Lifecycle:** `com.example.core.lifecycle.MaxRuntimeManager`
  - Authoritative coordinator for `MAX ON` vs `MAX OFF` state.

---

## 2. RAG Integration Design & Strategy

1. **Storage Layer (`rag_chunks` table in Room):**
   - Linked to `permanent_memory.memoryId`.
   - Stores chunked content, chunk index, deterministic chunk IDs, `contentHash`, embeddings, and metadata.
   - Preserves fast indexing and retrieval without modifying `PermanentMemory` schema.
2. **Embedding Architecture (`RagEmbeddingProvider`):**
   - **`LocalEmbeddingProvider`**: Deterministic 128-dimensional subword ngram feature hashing with term frequency weighting. Runs 100% on-device with zero network requests or token costs.
   - **`GeminiEmbeddingProvider`**: Cloud embedding using `text-embedding-004` (768 dimensions), strictly opt-in via Settings.
3. **Deterministic Chunking (`RagChunker`):**
   - Splits memory at semantic boundaries (headings `# `, paragraphs `\n\n`, bullet points, sentences) with maximum size bounds.
   - Generates deterministic chunk IDs: `SHA256(memoryId + contentHash + chunkIndex)`.
4. **Hybrid Retrieval (`RagRetriever`):**
   - Cosine similarity vector search over cached embeddings + Room keyword token matching with Reciprocal Rank Fusion / weighted score combination (`0.7 * semantic + 0.3 * keyword`).
5. **Prompt Injection Defense (`RagContextBuilder`):**
   - Bounded by `<MAX_RETRIEVED_MEMORY>` tags with strict system guardrails instructing the AI that retrieved facts are background reference data only and must never be executed as instructions.
6. **Relevance Gating (`RagEngine.shouldRetrieveForQuery`):**
   - Bypasses RAG for direct hardware/system actions ("turn flashlight on", "open camera", "turn wifi off", "go home") to maintain instant response times.
7. **Failure Safety:**
   - If RAG retrieval fails or times out, the assistant proceeds with normal generation. RAG is never a single point of failure.

---

## 3. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Main thread blocking during embedding | All chunking, embedding, indexing, and vector math executed strictly on `Dispatchers.IO`. |
| Excessive API calls for unchanged memories | Incremental indexing uses `contentHash` to skip unchanged memories. |
| Prompt injection via imported memories | XML-delimited `<MAX_RETRIEVED_MEMORY>` wrapper with anti-instruction system prompt directives. |
| Sensitive period data leakage | General RAG explicitly ignores `period_logs` and `isPrivate = true` data. |
| MAX OFF background resource leak | RAG engine observes `MaxRuntimeManager.isMaxEnabledFlow` and aborts indexing when MAX is OFF. |
