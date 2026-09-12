---
name: windows-automation
description: Expert guidelines for automating Windows applications, system settings, clipboard, and window management.
version: 1.0.0
platforms: [windows]
---

# Windows Workstation Automation Skill

## Guidelines
- Always verify application state before typing text or closing windows.
- Use `switch_to_app` before sending keyboard shortcuts or typing text.
- For volume/brightness adjustments, report current levels to user after setting.
- Destructive operations (shutdown, restart, permanent delete) MUST request confirmation first.
