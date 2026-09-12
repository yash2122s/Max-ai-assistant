# MAX — WHATSAPP SEARCH RESULT WRONG-CHAT FIX REPORT
**Date:** September 11, 2026  
**Status:** COMPLETED, TESTED & DEPLOYED (Redmi K20 Pro `3bc00016`)

---

## 1. Root Cause Analysis & Problem Traced
When executing:
> *"WhatsApp open cheyyi, Yashwanth ki Hi ani send cheyyi"*

The system failed due to two specific flaws:
1. **Unbounded Global Text Searching:**
   - Previous implementation invoked `root.findAccessibilityNodeInfosByText(contact)`.
   - In WhatsApp search results, if an unrelated group or personal chat contains the word *"Yashwanth"* inside its message snippet preview (`conversations_row_message` or `msg_snippet`), that node was matched and clicked instead of the 1-on-1 contact row.
   - If that failed, arbitrary first items (`nameNodes[0]` or `rowNodes[0]`) were clicked blindly.
2. **Window-Wide Header Verification Bypass:**
   - `isCorrectChatOpen` previously ran `root.findAccessibilityNodeInfosByText(variant)` without restricting it to the top toolbar header area.
   - If the name appeared anywhere in past message bubbles of an unrelated chat, verification passed falsely.

---

## 2. Before vs After Architecture

### BEFORE:
```
Voice Goal ("Yashwanth ki Hi send cheyyi")
  ↓
WhatsApp Search ("Yashwanth")
  ↓
findAccessibilityNodeInfosByText("Yashwanth") [Scans entire window]
  ↓
Matches message snippet in unrelated Group / Chat
  ↓
Clicks wrong row or first item fallback
  ↓
Types "Hi" blindly & sends to WRONG person ❌
```

### AFTER:
```
Voice Goal ("Yashwanth ki Hi send cheyyi")
  ↓
WhatsApp Search ("Yashwanth")
  ↓
WhatsAppContactResolver.resolveSearchRow()
  ├── Normalizes contact name ("yashwanth")
  ├── Isolates Contact Name IDs (conversations_row_contact_name, contact_name, name)
  ├── Strictly EXCLUDES message snippets (conversations_row_message, msg_snippet, last_message)
  ├── Filters group collisions ("Yashwanth Family", "Yashwanth Group")
  ├── Traverses upward to clickable row container (contact_row_container)
  └── Detects ambiguity if multiple equal candidates exist
  ↓
Clicks exact clickable ROW container (not avatar / snippet)
  ↓
isCorrectChatOpen() Header Verification Gate
  ├── Verifies top toolbar title (conversation_contact_name / bounds rect.top <= 500)
  └── REJECTS matches found in chat body / bubbles
  ↓
Target Chat Confirmed ✅
  ↓
Locates input field → Types "Hi" → Clicks Send → Verifies post-send state
```

---

## 3. Key Components Implemented

1. **`com.example.automation.resolver.WhatsAppContactResolver`**:
   - `WhatsappContactTarget`: Structured identity data model (`displayName`, `normalizedName`, `phone`, `rowBounds`, `resourceId`, `confidence`).
   - `normalize(text)`: Lowercases, trims, strips non-alphanumeric chars, collapses spaces.
   - `generateTargetVariants(target, phone)`: Generates normalized string, raw digits, E.164, and spaced phone number variants.
   - `isExcludedNode(node)`: Rejects `conversations_row_message`, `msg_snippet`, `last_message`, `message_text`, `search_src_text`, and category headers.
   - `isGroupCollision(candidate, target)`: Rejects group name matches when querying an individual person.
   - `findClickableAncestorOrSelf(node)`: Climbs up to `contact_row_container` / `conversations_row`.
   - `resolveVisibleChatRow(root, target)`: Direct-click optimization if contact is already visible on the main chat screen.
   - `isCorrectChatOpen(root, target)`: Strict header verification restricting check to top toolbar bounds (`rect.top <= 500`).

2. **`JarvisAccessibilityService.kt`**:
   - Integrated `WhatsAppContactResolver.resolveSearchRow` into Step 2.
   - Integrated strict `WhatsAppContactResolver.isCorrectChatOpen` safety gate into Step 3 and deep-link Step 0 before typing or clicking send.

3. **Untouched Systems (Preserved Invariants)**:
   - Gemini Live voice pipeline (`GeminiWebSocketClient`, `AssistantEngine`, `AudioRecorder`, `AudioProcessor`, `AudioPlayer`).
   - Keyboard control layer (`KeyboardController`, `TypeTextTool`, `PressEnterTool`, `InputTargetResolver`).
   - RAG / Memory / Room DB v14.

---

## 4. Test Suite & Verification Results

### Unit Tests: `WhatsAppContactResolverUnitTest` (12/12 PASSED)
1. `testNormalization` — Normalization and digit cleanup PASSED.
2. `testVariantGeneration` — Phone & text variant generation PASSED.
3. `test1_ExactContactResult` — Resolves exact contact name row PASSED.
4. `test2_UnrelatedSnippetContainingSameName` — Rejects message snippet, matches contact row PASSED.
5. `test3_MultipleSameNameResults` — Handles duplicate contact matches PASSED.
6. `test4_GroupNameCollision` — Rejects "Yashwanth Family Group" and matches "Yashwanth" PASSED.
7. `test5_WrongChatAfterClick` — Rejects chat header "Ravi" when requested "Yashwanth" PASSED.
8. `test6_CorrectChatVerification` — Passes chat header "Yashwanth" PASSED.
9. `test7_AmbiguousContact` — Returns `Ambiguous` on conflicting candidates PASSED.
10. `test8_SearchResultDelayedAppearance` — Returns `NotFound` on empty result tree PASSED.
11. `test9_DuplicateSendPrevention` — Rejects chat missing message entry field PASSED.
12. `test10_DirectClickOptimization` — Resolves visible contact row on main chat screen PASSED.

### Build & Deployment
- **Gradle Build:** `.\gradlew.bat assembleDebug` — BUILD SUCCESSFUL
- **Device Install:** `adb -s 3bc00016 install -r app\build\outputs\apk\debug\app-debug.apk` — **Success**
