# GATES: Forensic Audit Implementation for MAX Outgoing Calls

## Contract
Implement approved P0/P1 fixes in strict order:
- P0-1: User-visible call completion claims depend on CALL_PHONE execution truth.
- P0-2: CallVerifier & CallTool result semantics (CALL_REQUESTED != CALL_PLACED).
- P1-3: Unify contact-call confirmation; reject model-controlled confirmed=true.
- P1-4: Complete typed contact selection in HomeViewModel & ChatViewModel.
- P1-5: Invalidate obsolete pending call targets on failed replacement lookup.

## Gates

### G1: Call Execution Truth and Claim Gating (P0-1)
User-visible call completion claims must be gated against CALL_PHONE execution truth. Streamed chunks and final assembled responses asserting call completion in English, Telugu, or Tinglish must be blocked or corrected if CALL_PHONE has not verified active call placement (CALL_PLACED).
- CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.CallExecutionTruthUnitTest.testTinglishAndTeluguFakeSuccessGated"
- EXPECT: SUCCESS

### G2: CallVerifier & CallTool Result Semantics (P0-2)
When no active telecom/telephony call evidence exists after ACTION_CALL launch, CallVerifier must return non-completed status (CALL_REQUESTED), never completed success. Must distinguish CALL_REQUESTED from CALL_PLACED and propagate through RetryEngine, ExecutionEngine, and ActionDispatcher without redialing.
- CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.CallExecutionTruthUnitTest.testNoEvidenceCallRequestedIsNotCompleted"
- EXPECT: SUCCESS

### G3: Unified Contact-Call Confirmation & Model-Consent Rejection (P1-3)
Model-controlled confirmed=true in payload is rejected without actual user approval. Exactly one task-correlated approval per prepared target; user confirmation consumes that approval.
- CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.CallExecutionTruthUnitTest.testModelControlledConfirmedRejected"
- EXPECT: SUCCESS

### G4: Complete Typed Contact Selection in HomeViewModel & ChatViewModel (P1-4)
When state is AwaitingContactSelection, user typed input (index, label, name) resolves candidate and transitions to AwaitingConfirmation with exact target, requesting final confirmation.
- CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.CallExecutionTruthUnitTest.testTypedContactSelectionDisambiguation"
- EXPECT: SUCCESS

### G5: Invalidate Obsolete Pending Target on Failed Replacement Lookup (P1-5)
A failed contact lookup must invalidate any prior pending call target so subsequent "Yes" cannot approve an older contact.
- CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.CallExecutionTruthUnitTest.testFailedLookupInvalidatesPendingTarget"
- EXPECT: SUCCESS

### G6: Search Success != Call Success (Audit Invariant)
Searching a contact successfully must never be reported as placing or completing a call.
- CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.CallExecutionTruthUnitTest.testSearchSuccessDoesNotImplyCallSuccess"
- EXPECT: SUCCESS

### G7: Full Unit Test Suite Passing
All unit tests in the project (834+ tests) must compile and pass with 0 failures and 0 errors.
- CHECK: .\gradlew.bat testDebugUnitTest
- EXPECT: BUILD SUCCESSFUL

### G8: Real Device Cellular Verification
Execute instrumented validation on real device 3bc00016 to confirm end-to-end phone call flow.
- CHECK: .\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.automation.RealDeviceCallValidationTest
- EXPECT: OK
