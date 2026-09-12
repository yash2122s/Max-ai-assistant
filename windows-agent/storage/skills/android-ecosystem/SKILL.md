---
name: android-ecosystem
description: Instructions for orchestrating paired Android companion devices (Phone 1, Phone 2, Tablet) via DeviceRouter.
version: 1.0.0
platforms: [windows, android]
---

# Android Multi-Device Ecosystem Skill

## Device Resolution
- Targets can be identified by display name ('Phone 1', 'Phone 2', 'Tablet') or model name.
- Single device commands: use `execute_device_action(target='Phone 1', action='FLASHLIGHT_ON')`.
- Broadcast / multi-device commands: use `execute_multi_device_action(targets='all_phones', action='FLASHLIGHT_OFF')`.
- Always check device connectivity via `list_connected_devices` if a device is unresponsive.
