# MAX Agent Behaviors and Rules

## Project Knowledge System
This project has a structured knowledge architecture. All AI agents MUST use it:

```
MAX_PROJECT_STATE.md    — Current feature status (what works, what's broken)
MAX_PROJECT_MAP.md      — Navigation index (feature → file path)
MAX_ARCHITECTURE.md     — Full architecture and data-flow diagrams
docs/max/               — Subsystem-level deep dives
.agents/rules/max-project.md   — Development rules and invariants
.agents/agents/max-architect/agent.md  — Primary coding agent definition
```

**ALWAYS read `MAX_PROJECT_STATE.md` and `MAX_PROJECT_MAP.md` before any task.**  
**NEVER scan the full repository for routine tasks.**

---

## Original Rules (Preserved)

### Permissions and Safety
- Do not suggest or assume opening WhatsApp without the user's explicit permission.
- If a task would involve opening or interacting with WhatsApp, wait for the user to clearly authorize it first.

---

## Coding Standards

- Never change Room DB version without writing a migration
- Never write secrets or API key values in any file
- Never redesign core pipeline (`GeminiWebSocketClient → ToolDispatcher → ActionDispatcher → ExecutionEngine`) without approval
- Prefer minimal, targeted changes to existing working code
- Always report which files were changed after completing a task

---

## Subsystem Docs Index

| Subsystem | Doc |
|-----------|-----|
| AI / Gemini Live | `docs/max/AI.md` |
| Voice / Audio | `docs/max/VOICE.md` |
| Accessibility | `docs/max/ACCESSIBILITY.md` |
| Automation Engine | `docs/max/AUTOMATION.md` |
| WhatsApp | `docs/max/WHATSAPP.md` |
| Telegram | `docs/max/TELEGRAM.md` |
| Calls | `docs/max/CALLS.md` |
| Memory | `docs/max/MEMORY.md` |
| Data / DB | `docs/max/DATA.md` |
| UI | `docs/max/UI.md` |
| Permissions | `docs/max/PERMISSIONS.md` |
| Onboarding | `docs/max/ONBOARDING.md` |
| Build | `docs/max/BUILD.md` |
| Known Issues | `docs/max/KNOWN_ISSUES.md` |
