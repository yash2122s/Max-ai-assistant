# MAX Screen Context + Memory Save Runtime Audit

## SCREEN CONTEXT AUDIT

### ScreenContextProvider.kt
| Issue | Line | Severity |
|-------|------|----------|
| `rootInActiveWindow` used without `refresh()` — returns stale/recycled nodes | L38 | **CRITICAL** |
| No timeout on entire `captureScreenContext()` — callers can hang forever | L31 | **CRITICAL** |
| No retry when rootNode is null — single shot | L38 | HIGH |
| No `IllegalStateException` catch around `rootNode.packageName` | L41 | MEDIUM |
| `pruneTree(rootNode)` catches generic `Exception` but not `IllegalStateException` specifically | L50-54 | MEDIUM |

### ScreenContextTool.kt
| Issue | Line | Severity |
|-------|------|----------|
| No timeout wrapper around `captureScreenContext()` | L32 | **CRITICAL** |
| Tool blocks entire `ExecutionEngine` 25s timeout budget | L27 | HIGH |

### ScreenCaptureProvider.kt
| Issue | Line | Severity |
|-------|------|----------|
| `CountDownLatch.await(2, SECONDS)` — bounded at 2s | L112 | OK |
| Shizuku `screencap -p` can hang if process stalls — no timeout | L52 | MEDIUM |
| Root `su -c screencap -p` can hang — no timeout | L67 | MEDIUM |

### DeviceContext.kt
| Issue | Line | Severity |
|-------|------|----------|
| `extractNodes()` recursive traversal has no `IllegalStateException` guard | L102-121 | MEDIUM |
| `node.getChild(i)` can throw on recycled node — not caught | L116 | MEDIUM |

---

## MEMORY SAVE AUDIT

### SaveMemoryTool.kt
| Issue | Line | Severity |
|-------|------|----------|
| Creates new `MemoryRepository(context)` per call — leaks init coroutines | L69 | **CRITICAL** |

### MemoryRepository.kt — Init Block
| Issue | Line | Severity |
|-------|------|----------|
| `init` block launches `CoroutineScope(Dispatchers.IO).launch {}` — unstructured scope, never cancelled | L44 | **CRITICAL** |
| `dao.getTopMemoriesForPromptFlow(30).collect {}` — never-terminating collector on every new instance | L89 | **CRITICAL** |
| Second `CoroutineScope(Dispatchers.IO).launch {}` for RAG indexing — also orphaned | L97 | HIGH |

### MemoryRepository.kt — addMemory() Path
| Issue | Line | Severity |
|-------|------|----------|
| `isDuplicate()` calls `getAll()` — decrypts entire table (scan 1) | L240 | HIGH |
| `findExistingTopicMemory()` calls `getAll()` again — decrypts entire table (scan 2) | L245 | HIGH |
| `GeminiEmbeddingClient.generateEmbedding()` — synchronous blocking HTTP up to 10s | L258 | **CRITICAL** |
| `triggerDebouncedMarkdownSync()` creates new `CoroutineScope` per call | L307 | HIGH |
| RAG indexing launches another orphan `CoroutineScope` | L266 | MEDIUM |

### GeminiEmbeddingClient.kt
| Issue | Line | Severity |
|-------|------|----------|
| `httpClient.newCall(request).execute()` — synchronous blocking OkHttp | L49 | **CRITICAL** |
| 10s connect + 10s read = up to 20s total blocking | L16-17 | **CRITICAL** |

### Instantiation Count: 14 sites creating `MemoryRepository()` across codebase
Each leaks a never-terminating Flow collector and orphan RAG indexing coroutine.
