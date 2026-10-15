# Plan: Phase 3 local account isolation

Scope: phase3
Mode: orchestrated, shallow native implementation split
Contract revision: 1

## Shared interfaces fixed before implementation
- T1 adds `com.example.memory.account.MemorySession(ownerKey: String, generation: Long)` and singleton `MemoryAccountScope`.
- Scope API: constants `GUEST = "guest"`, `LEGACY = "legacy"`, `UNAVAILABLE = "unavailable"`; `sessions: StateFlow<MemorySession>`; `capture(): MemorySession`; `isCurrent(session): Boolean`; `requireCurrent(session)` throwing CancellationException on stale/unavailable; `directoryName(session): String` (safe hash); `bind(auth: FirebaseAuthManager)`; `unbind()` for lifecycle/tests.
- Scope uses existing authManager currentUid + isCurrentSession(sessionState.value), not supplied UID/email/device ID. Unbound/transition state is UNAVAILABLE and cannot read/write; confirmed signed-out is GUEST. Bind from MyApplication after maxLicenseManager creation. Notify before exposing new account and detect A->B->A.
- T1 adds ownerKey column, default legacy, to PermanentMemory, RagChunkEntity, MemorySyncState, ChatMessage, PeriodLog. Memory/RAG/sync composite primary keys include ownerKey; chat/period retain global auto IDs with owner-qualified predicates/indices. Room version 20 -> 21 with data-preserving table copy/ALTER and migration tests; no deletion of legacy records.
- All DAO SELECT/UPDATE/DELETE methods require `ownerKey: String` last. Existing positional args remain first; inserts/bulk insert/update use entities stamped with ownerKey. No overload/default that reads globally or silently reads guest. Explicit legacy review may use LEGACY only via reviewed UI.
- Domain RagChunk carries ownerKey to prevent returning/indexing mismatched chunks. Repository mutations reject foreign-owned input; newly constructed memory with ownerKey LEGACY can be stamped ONLY for a fresh creation. Updating a persisted legacy record requires consent path, never generic update.
- Async operations capture `session: MemorySession = MemoryAccountScope.capture()` before withContext/launch; use explicit owner for all subsequent DB/file work, validate before commit/publication and after awaits. A stale lease cannot become the new account's lease.
- Repository flows flatMapLatest on sessions and emit empty at transition before new data. Caches keyed by owner and generation; clear pending UI state and preview data on switch.
- T2 may append optional MemorySession arguments to repository APIs; T3/T4 must pass captured leases from UI/task entry before launching queued work.
- File outputs go under account-hashed directories (using directoryName); never auto-import old global files. Existing legacy files remain untouched for explicit review. Export is explicit plaintext disclosure, not private encrypted backup.
- Legacy consent implementation owned by T3 via separate `LegacyMemoryReview` in memory/account (NOT scope file). Snapshot target session at preview, verify at commit, list selected records; transactionally associate only selected legacy records without overwrite/data loss. Unselected rows/files stay unassigned.
- Full backups include period data, therefore period DAO/export/context consumers must be scoped too. Do not alter health algorithms/licensing/execution routing.

## Inventory and ownership
| ID | Outcome | Owner | Observing check |
|---|---|---|---|
| C1 | Canonical UID, guest, unavailable state, session generation | T1 | scope tests + auth integration review |
| C2 | Data-preserving version 21 migration, legacy quarantine | T1 | real SQLite/Room migration tests |
| C3 | Owner-qualified CRUD/search and foreign-ID rejection | T1/T2 | account A/B/guest repository tests |
| C4 | RAG/embeddings/skills cannot cross owners | T2 | RAG/skill isolation and delayed-index tests |
| C5 | Import/export/backup/Markdown scoped paths and previews | T3 | transfer + stale-preview tests |
| C6 | Explicit legacy review/consent, no auto assignment | T3 | consent and account-switch commit tests + UI review |
| C7 | Chat/AI context and queued consumers owner-bound | T4 | chat/context and delayed-callback tests |
| C8 | Period records in backups/context scoped | T1/T4 | period isolation and preservation tests |
| C9 | Existing features/encryption/licensing preserved | parent | meaningful Android regression selection/full suite measured |
| C10 | No cloud sync/rule deployment/destructive reset | parent | scoped diff review |
| C11 | Kotlin compile/debug APK and precise evidence report | parent | Gradle commands and test XML |

## Native implementation ownership
| Leaf | Owns | Needs | Tier | Planned wave | State |
|---|---|---|---|---|---|
| T1 | AppDatabase; PermanentMemory/Dao; MemorySyncState/Dao; RagChunkEntity/Dao; ChatMessage/Dao; PeriodLog/Dao; MemoryAccountScope; new storage tests | fixed contract | judgment | 1 | READY |
| T2 | MemoryRepository; memory/retrieval; memory/skill; rag except data entities/DAO; memory/security; new repository/RAG tests | fixed contract | judgment | 1 | READY |
| T3 | memory/sync; memory/universal; memory/viewmodel; ui/memory; new LegacyMemoryReview and consent tests | fixed contract | judgment | 1 | READY |
| T4 | MyApplication wiring; chat callers/repositories/ViewModels; voice/context consumers; PeriodMarkdownExporter and period callers/seeder; minimal task-entry snapshot integration; new consumer tests | fixed contract | judgment | 1 | READY |

Host launch mode: task calls batched in one parallel tool message. Native task handles are returned with completed results rather than a separate nonblocking start receipt. Do not fabricate CLI dispatch start/seal timestamps or claim a CLI-verified launch wave. Parent verifies all returned work with sequential Gradle runs; leaves do not run Gradle concurrently.

No hooks/global tool changes/worktrees/commits requested. Existing GATES.md and unrelated .unlazy scopes remain untouched.
