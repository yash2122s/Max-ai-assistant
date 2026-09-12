---
name: code-repair
description: Unified diff code patching, traceback diagnosis, and sandbox testing workflows.
version: 1.0.0
platforms: [windows, linux, macos]
---

# Code Repair & Patching Skill

## Patching Protocol
1. Read the target file content and note surrounding context lines.
2. Formulate a clean unified diff patch with @@ line headers and 3 context lines.
3. Apply patch via `apply_patch(target_file=..., diff_content=...)`.
4. Run unit tests (`pytest`) to verify the repair before concluding.
