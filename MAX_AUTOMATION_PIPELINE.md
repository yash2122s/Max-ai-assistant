# MAX AI — AUTONOMOUS UI AUTOMATION PIPELINE

**Version:** 3.1.0  
**Target:** `com.example.automation.agent`  

---

## 1. Automation Execution Loop

```
┌─────────────────────────────────────────────────────────────┐
│ 1. OBSERVE                                                  │
│    • Capture screen screenshot JPEG (Shizuku / Su / A11y)  │
│    • Extract compact pruned accessibility hierarchy nodes   │
│    • Identify active foreground package                     │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ 2. DECIDE (Provider Pipeline)                               │
│    • Query OpenRouter (stealth/ox-alpha)                    │
│    • On failure/timeout -> Fallback to Gemini UI Agent      │
│    • Parse typed UiAction or finish decision                │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ 3. ACT                                                      │
│    • Dispatch action via UiActionExecutor                   │
│    • TAP, LONG_PRESS, TYPE, SWIPE, SCROLL, LAUNCH, KEYS     │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ 4. ADAPTIVE WAIT                                            │
│    • App launch: poll every 150ms up to 2500ms for pkg      │
│    • Type: 350ms settle                                     │
│    • Swipe / Scroll: 400ms settle                           │
│    • Tap: 300ms settle                                      │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ 5. RE-OBSERVE & VERIFY                                      │
│    • Capture post-action screen state                       │
│    • Verify expected package, text, or visual change        │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ 6. SELF-HEALING RECOVERY                                    │
│    • Detect 2 consecutive identical actions with no change  │
│    • Auto-dismiss keyboard (inject BACK key)                │
│    • Feed recovery context back to reasoning model          │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Action Capabilities

| Action Type | Payload Arguments | Physical Execution Mechanism |
|---|---|---|
| `LAUNCH_APP` | `packageName` | `PackageManager.getLaunchIntentForPackage()` |
| `TAP` | `x`, `y` (normalized or screen coords) | `Shizuku` / `su input tap` / `AccessibilityService.dispatchGesture()` |
| `LONG_PRESS` | `x`, `y` (500ms duration) | `AccessibilityService.dispatchGesture()` |
| `TYPE` | `text`, optional `nodeId` | `Shizuku input text` / `AccessibilityNodeInfo.ACTION_SET_TEXT` |
| `SWIPE` | `x1, y1` -> `x2, y2` | `AccessibilityService.dispatchGesture()` path |
| `SCROLL` | `direction` (`UP`, `DOWN`) | `AccessibilityNodeInfo.ACTION_SCROLL_FORWARD / BACKWARD` |
| `KEY_BACK` | None | `AccessibilityService.GLOBAL_ACTION_BACK` |
| `KEY_HOME` | None | `AccessibilityService.GLOBAL_ACTION_HOME` |
| `FINISH` | `success`, `message` | Complete task & return result |
