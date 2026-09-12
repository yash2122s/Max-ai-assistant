# Calls Subsystem — MAX

## Purpose
Detect incoming calls, identify callers, announce via MAX voice, and allow user to answer/reject by voice command.

## Main Classes

| Class | File | Role |
|-------|------|------|
| `IncomingCallReceiver` | `receiver/IncomingCallReceiver.kt` | BroadcastReceiver for PHONE_STATE |
| `IncomingCallManager` | `automation/IncomingCallManager.kt` | Resolves caller identity; triggers announcement |
| `CallController` | `automation/CallController.kt` | Higher-level call control |
| `AnswerCallTool` | `automation/tools/AnswerCallTool.kt` | Tool: answers incoming call |
| `RejectCallTool` | `automation/tools/RejectCallTool.kt` | Tool: rejects incoming call |
| `AnswerCallAction` | `automation/actions/AnswerCallAction.kt` | Executes ANSWER_PHONE_CALLS permission action |
| `RejectCallAction` | `automation/actions/RejectCallAction.kt` | Executes call rejection |
| `PendingCallManager` | `automation/engine/PendingCallManager.kt` | Tracks pending call state for agentic decision |
| `SmsTool` | `automation/tools/SmsTool.kt` | Send SMS after call rejection |

## Incoming Call Flow
```
Phone rings (CALL_STATE_RINGING)
  └─ IncomingCallReceiver.onReceive()
        └─ Detects: lastState==IDLE && newState==RINGING
              └─ IncomingCallManager.handleIncomingCall(context, incomingNumber)
                    └─ scope.launch(IO):
                          resolveIdentity(context, number):
                            1. ContactsContract.PhoneLookup → contact name (if number known)
                            2. If no name: return raw number
                            3. If number null: scrapeCallerInfoWithRetry()
                               → JarvisAccessibilityService.dumpScreenFlat()
                               → search for "name"/"number" node or phone-pattern text
                               → retry 4x with 500ms delay
                            4. Fallback: "Unknown caller"

                          AssistantEngine.announceIncomingCall(identity)
                            └─ "Yash, [identity] is calling. Do you want me to answer or reject?"
                                 └─ Sent as text to Gemini Live via connectionManager.sendText()
                                       └─ Gemini responds (voice) and waits for user command
```

## Answer/Reject Flow
```
User says: "Answer" or "Reject"
  └─ Gemini calls: answer_call or reject_call tool
        └─ AnswerCallTool / RejectCallTool → AnswerCallAction / RejectCallAction
              └─ Android ANSWER_PHONE_CALLS permission call
                    OR JarvisAccessibilityService gesture on call screen
```

## Permissions Required
- `READ_PHONE_STATE` — receive PHONE_STATE broadcast
- `READ_CALL_LOG` — query latest incoming ringing call metadata
- `READ_PHONE_NUMBERS` — read incoming number on Android 9+
- `READ_CONTACTS` — look up contact name in PhoneLookup and CommonDataKinds.Phone
- `ANSWER_PHONE_CALLS` — programmatic answer (API 26+) / reject (API 28+)
- `SEND_SMS` — send SMS busy replies after rejection

## Status: COMPLETE & VERIFIED ✅

### What is implemented:
- `IncomingCallReceiver` registered in `AndroidManifest.xml` with `PHONE_STATE` & `PHONE_STATE_CHANGED`.
- `IncomingCallFlowState` state machine tracking `RINGING`, `ANNOUNCING`, `LISTENING`, `PROCESSING`, `ANSWERED`, `REJECTED`, `MESSAGE_PENDING`, `COMPLETED`, `IDLE`.
- Layered caller identity resolution:
  1. Primary `ContactsContract.PhoneLookup` lookup
  2. Fallback `ContactsContract.CommonDataKinds.Phone` normalized digits lookup
  3. `JarvisAccessibilityService` 6-retry scraping window (3.0s) across InCall UI nodes
  4. `CallLog.Calls` recent ringing lookup
  5. Fallback to `"Unknown caller"`
- Single-announcement guard during active call.
- `AssistantEngine.announceIncomingCall()` formats natural prompt, ensures Gemini Live connection, and immediately starts microphone audio recording (`startListening()`).
- `GeminiWebSocketClient.kt` function declarations and `ToolDispatcher.kt` routing for `answer_call`, `reject_call`, and `send_sms`.
- `CallController.kt` with TelecomManager API + `JarvisAccessibilityService` gesture fallback (`performAnswerCallGesture` / `performRejectCallGesture`).
- Busy message follow-up prompts supporting SMS (`SmsTool` / `SendSmsAction`) and WhatsApp (`WhatsAppController`).
- Clean terminal state cleanup on `CALL_STATE_IDLE` broadcast (`IncomingCallManager.handleCallEnded()`).
- Targeted debug logging across all lifecycle events.

## Extension Points
- Add auto-reject for unknown numbers
- Add "call back later" reminder on rejection
- Send WhatsApp message instead of SMS on rejection
- Play audio announcement when screen is locked
