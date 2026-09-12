# Memory Subsystem — MAX

## Purpose
Persistent, encrypted, vector-searchable long-term memory for MAX. Stores user facts, preferences, skills, and period data. Injected into every Gemini session as system instruction context.

## Main Classes

| Class | File | Role |
|-------|------|------|
| `MemoryRepository` | `memory/data/MemoryRepository.kt` | CRUD + encryption + embedding orchestration |
| `PermanentMemory` | `memory/data/PermanentMemory.kt` | Room entity: memoryId, title, content, category, type, source, pinned, enabled, embedding (BLOB), tags, isPrivate |
| `PermanentMemoryDao` | `memory/data/PermanentMemoryDao.kt` | Room DAO; queries by category, type, pinned, search |
| `GeminiEmbeddingClient` | `memory/data/GeminiEmbeddingClient.kt` | REST call to text-embedding-004; returns 768-dim FloatArray |
| `MemoryRetrieval` | `memory/retrieval/MemoryRetrieval.kt` | Hybrid search: 0.7*cosine + 0.3*keyword |
| `MemoryFormatter` | `memory/retrieval/MemoryFormatter.kt` | Formats memory list as Markdown for prompt injection |
| `MemoryCryptography` | `memory/security/MemoryCryptography.kt` | AES encryption for title/content/tags; "enc_v1:" prefix |
| `SensitiveDataFilter` | `memory/security/SensitiveDataFilter.kt` | Filters sensitive data before storage |
| `SkillMemoryRepository` | `memory/skill/SkillMemoryRepository.kt` | Stores/retrieves saved skills |
| `SavedSkill` | `memory/skill/SavedSkill.kt` | Skill entity with steps |
| `MemoryViewModel` | `memory/viewmodel/MemoryViewModel.kt` | ViewModel for legacy Compose MemoryScreen |
| `MemoryScreen (Flutter)` | `max_flutter/lib/features/memory/memory_screen.dart` | Primary Flutter Memory Vault UI with CRUD, multi-field search, categories, and pin |
| `MemoryScreen (Compose)` | `memory/ui/MemoryScreen.kt` | Legacy Compose CRUD UI for memories |
| `SaveMemoryTool` | `automation/tools/SaveMemoryTool.kt` | Tool: saves memory via voice |
| `ForgetMemoryTool` | `automation/tools/ForgetMemoryTool.kt` | Tool: deletes memory via voice |

## Data Flow

### Save Memory (voice-triggered)
```
User: "Remember that I prefer morning calls"
  └─ Gemini → save_memory(title="Call preference", content="I prefer morning calls")
        └─ SaveMemoryTool.execute()
              └─ MemoryRepository.addMemory()
                    ├─ MemoryCryptography.encrypt(title, content, tags)
                    ├─ PermanentMemoryDao.insert(PermanentMemory)
                    └─ GeminiEmbeddingClient.getEmbedding(content) → FloatArray[768]
                          └─ PermanentMemoryDao.updateEmbedding(memoryId, embedding)
```

### Memory Retrieval (per session)
```
ChatViewModel.initialize() / GeminiWebSocketClient.connect()
  └─ MemoryRepository.getTopMemories(query) or getAllEnabled()
        └─ MemoryRetrieval.hybridSearch(query, memories):
              score = 0.7 * CosineSimilarity(queryEmbedding, memEmbedding)
                    + 0.3 * KeywordScore(query, memContent)
              └─ Top N memories → MemoryFormatter.formatForPrompt()
                    └─ Injected into PromptBuilder.buildSystemInstruction()
```

### Embedding Backfill
```
ChatViewModel.initialize()
  └─ memoryRepository.backfillMissingEmbeddings()
        └─ Batch: 10 items / 500ms delay
              └─ For each memory without embedding:
                    GeminiEmbeddingClient → embed → store
```

## Room Table: `permanent_memory`
| Column | Type | Notes |
|--------|------|-------|
| `memoryId` | TEXT PK | UUID |
| `title` | TEXT | AES encrypted (enc_v1:...) |
| `content` | TEXT | AES encrypted (enc_v1:...) |
| `category` | TEXT | MemoryCategory enum |
| `type` | TEXT | MemoryType enum |
| `source` | TEXT | MemorySource enum |
| `pinned` | INTEGER | Boolean |
| `enabled` | INTEGER | Boolean |
| `usageCount` | INTEGER | Auto-incremented on use |
| `lastUsedAt` | INTEGER | Epoch ms |
| `createdAt` | INTEGER | Epoch ms |
| `updatedAt` | INTEGER | Epoch ms |
| `embedding` | BLOB | FloatArray (768 dims) |
| `tags` | TEXT | AES encrypted, comma-separated |
| `isPrivate` | INTEGER | Boolean (added v14) |

## Memory Categories (MemoryCategory enum)
USER_PREFERENCE, PERSONAL_FACT, REMINDER, TASK, SKILL, HEALTH, FINANCE, CONTACT, OTHER

## Memory Encryption
- Algorithm: AES (via Android Keystore via `MemoryCryptography`)
- Prefix marker: `enc_v1:`
- Migration: `MemoryRepository.init {}` block handles legacy plaintext migration + normalization

## Chat History (separate from PermanentMemory)
- Table: `chat_messages` (ChatMessage entity)
- Dao: `ChatMessageDao`
- Displayed in `ChatViewModel` via Flow

## Skills Memory
- `SkillMemoryRepository` + `SavedSkill` — stores custom multi-step skills defined by user
- Integrated with `RoutineTool` for routine execution

## Period Data (in AppDatabase)
- Table: `period_logs` (PeriodLog entity)
- Pre-seeded via `PeriodDataSeeder` with 2025/2026 cycle dates
- Accessed by `PeriodTrackerTool`

## Current Status: IMPLEMENTED ✅
Full CRUD, encryption, vector embeddings, hybrid search, and backfill all working.

## Known Issues
- `MemoryRepository.init {}` uses `runBlocking` — potential main-thread issue if called on UI thread
- `isMigrationCompleted` AtomicBoolean only prevents double-migration within same process session
- No memory size limit — could grow unbounded over time
