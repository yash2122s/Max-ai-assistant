# MAX UI/UX Audit

## Architecture truth

The current source is Jetpack Compose: `MainActivity` calls `setContent { MaxApp(...) }`, `MaxApp` uses Compose navigation/drawer, and `app/build.gradle.kts` enables Compose. `MAX_ARCHITECTURE.md` and `.agents/rules/max-project.md` still describe Flutter as sole UI authority. This documentation conflict is a production risk.

## Strengths

- Compose shell with navigation, onboarding, settings, permissions, memory, routines, reminders, and capabilities screens.
- State flows exist for connection, listening, thinking, speaking, errors, and MAX on/off.
- `AgentActivityTracker` exposes planning, executing, observing, verifying, failed, and completed traces.
- Chat and scheduled-task records use Room.

## Gaps

- `HomeViewModel` leaves `ToolProgress` and `ThoughtSummaryDelta` largely unrendered.
- Approval is presented conversationally, without a guaranteed parent-task progress card or remaining-step view.
- An 8-second thinking timeout conflicts with 25–60 second tool/plan execution and can make the UI appear idle while work continues.
- Completion/failure can be rendered from chat text or process-local events with no durable task identity.
- Multiple ViewModels independently consume approval and assistant events, creating duplication and race risk.
- Waiting, recovering, blocked, paused, timed-out, and verified-completed states are not one unified task lifecycle.

## Recommended direction (not implemented)

Use a task-centric workspace: each active task shows status, step list, current action, evidence, approval card, recovery explanation, cancel/pause controls, and final verification receipt. Chat remains conversational, not authoritative.

Visible states should include Listening, Thinking, Planning, Executing, Waiting for approval, Waiting for UI, Verifying, Recovering, Paused, Completed (verified), Failed, Timed out, and Cancelled.
