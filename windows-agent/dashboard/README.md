# MAX Operations Console

This directory contains the single-page application (SPA) codebase for the MAX Agent Operations Console.

## Directory Structure
- `index.html`: Entrypoint skeleton.
- `assets/css/`: Vanilla styles.
  - `app.css`: Reusable component utilities (buttons, inputs, cards).
  - `layout.css`: Structural Grid/Flexbox boundaries.
  - `themes/`: Theme files mapping CSS variables.
- `assets/js/`: Decoupled scripts.
  - `app.js`: Orchestrator bootstrapper.
  - `state.js`: Global state management (`AppState`).
  - `router.js`: SPA routing manager.
  - `event_stream.js`: SSE subscriber.
  - `notifications.js`: UI Toast alerts manager.
  - `views/`: Module controllers implementing state rendering loops.
