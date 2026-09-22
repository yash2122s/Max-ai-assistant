---
skill_id: com.max.automation.instagram.search_chat
name: Instagram Direct Message Search
description: Opens Instagram, navigates to Search, searches for a target user, opens their profile, taps Message, and verifies conversation composer.
version: 1.0.0
schema_version: 1.0.0
author: MAX Ecosystem
trust_level: LOCAL_USER
checksum: ""
supported_platforms:
  - android
  - windows

requirements:
  common:
    capabilities:
      - network
    permissions: []
  android:
    capabilities:
      - accessibility
    permissions: []
  windows:
    capabilities:
      - uia
    permissions: []

allowed_capabilities:
  - app.open
  - ui.click
  - ui.type_text
  - ui.wait

intents:
  exact_phrases:
    - "open instagram chat"
    - "message someone on instagram"
    - "instagram lo chat open cheyi"
    - "open chat on instagram"
  trigger_keywords:
    - "instagram"
    - "direct message"
    - "insta dm"
    - "chat"
  category: messaging

inputs:
  - name: target_user
    type: string
    required: true
    default_value: ""
    description: Instagram username or contact name to search for

preconditions:
  - app_identifier: Instagram
    window_state: any

steps:
  - step_id: step_open_app
    action: app.open
    target: Instagram
    arguments:
      package_name: com.instagram.android
    depends_on: []
    platform_overrides:
      android:
        package_name: com.instagram.android
      windows:
        process_name: Instagram.exe
    timeout_ms: 6000
    verification:
      type: APP_FOREGROUND
      expected_value: com.instagram.android
      timeout_ms: 4000

  - step_id: step_open_search
    action: ui.click
    target: search
    arguments:
      role: button
      avoid:
        - reels_tab
        - profile_tab
        - home_tab
    depends_on:
      - step_open_app
    timeout_ms: 4000
    verification:
      type: ELEMENT_VISIBLE
      expected_value: search input
      timeout_ms: 3000

  - step_id: step_search_user
    action: ui.type_text
    target: search input
    arguments:
      text: "{{target_user}}"
      press_enter: true
    depends_on:
      - step_open_search
    timeout_ms: 4000
    verification:
      type: TEXT_MATCH
      expected_value: "{{target_user}}"
      timeout_ms: 2500

  - step_id: step_open_profile
    action: ui.click
    target: first_search_result
    arguments:
      role: row
      avoid:
        - avatar
        - story_ring
        - follow_button
        - bottom_navigation
    depends_on:
      - step_search_user
    timeout_ms: 5000
    verification:
      type: ELEMENT_VISIBLE
      expected_value: profile_message_button
      timeout_ms: 3500

  - step_id: step_click_message
    action: ui.click
    target: profile_message_button
    arguments:
      role: button
      text: Message
    depends_on:
      - step_open_profile
    timeout_ms: 4000
    verification:
      type: ELEMENT_VISIBLE
      expected_value: conversation_composer
      timeout_ms: 3500

  - step_id: step_verify_chat
    action: ui.wait
    target: conversation_composer
    arguments:
      role: edit
    depends_on:
      - step_click_message
    timeout_ms: 3000
    verification:
      type: ELEMENT_VISIBLE
      expected_value: conversation_composer
      timeout_ms: 3000

failure_policy: ABORT
---

# Instagram Direct Message Search Skill

This skill opens Instagram on the host device, navigates to the Search tab, performs a user search for the bound `target_user`, opens the first matched user profile, taps the Message button, and confirms the active chat composer.
