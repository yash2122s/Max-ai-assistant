# MAX AI — DYNAMIC UI AGENT PROVIDER ARCHITECTURE

**Version:** 3.1.0  
**Target:** `com.example.automation.agent`  

---

## 1. Provider Abstraction Model

```
                  ┌──────────────────────┐
                  │   UiAgentProvider    │
                  │     (Interface)      │
                  └──────────┬───────────┘
                             │
            ┌────────────────┴────────────────┐
            ▼                                 ▼
┌─────────────────────────┐       ┌─────────────────────────┐
│OpenRouterUiAgentProvider│       │  GeminiUiAgentProvider  │
│(stealth/ox-alpha Brain) │       │(gemini-2.0-flash Backup)│
└─────────────────────────┘       └─────────────────────────┘
```

---

## 2. Dynamic Provider Fallback Lifecycle

```
User triggers UI Goal
          │
          ▼
Is OpenRouter configured with API Key?
          │
   ┌──────┴──────┐
  YES            NO
   │             │
   ▼             │
Query OpenRouter │
   │             │
   ├─ Success ───┼──────────► Return Action Decision
   │             │
   └─ Failure ───┤
                 ▼
         Query Gemini Live UI Agent
                 │
                 ├─ Success ──► Return Action Decision
                 │
                 └─ Failure ──► Return Explicit UI Agent Error
```

---

## 3. Dynamic OpenRouter Model Discovery

`OpenRouterUiAgentProvider` connects to `https://openrouter.ai/api/v1/models` and filters for models supporting tool calling and multimodal input:
- `stealth/ox-alpha` (Primary UI Agent reasoning model)
- `anthropic/claude-3.5-sonnet`
- `openai/gpt-4o`
- `google/gemini-2.0-flash-001`
- `qwen/qwen-2.5-vl-72b-instruct`
- `deepseek/deepseek-chat`
