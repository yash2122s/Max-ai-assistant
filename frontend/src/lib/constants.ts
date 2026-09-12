import type { OrbPhase, ActivityItem } from "./types";

export const MAX_ACTIVITY_CAP = 400;
export const MAX_CHAT_CAP = 300;
export const MAX_NOTIFICATIONS_CAP = 60;

// Backend listener timing. These are tuned so the UI stays honest: voice /
// Gemini Live states are NOT exposed by the backend, so header indicators are
// driven purely from real events (never fabricated).
export const POLL_INTERVAL_MS = 10_000;
export const STALE_DEVICE_MS = 15_000;
export const RECONNECT_BASE_DELAY_MS = 1_000;
export const RECONNECT_MAX_DELAY_MS = 15_000;
export const SSE_KEEPALIVE_STALL_MS = 30_000;

// Agent-visible state derived only from verified backend events.
// Gemini Live / Vosk are deliberately NOT modeled as live states here: the
// backend does not expose them, so we never claim to know them.
export const ORB_PHASES: Record<string, OrbPhase> = {
  standby: "standby",
  executing: "executing",
  error: "error",
};

export const EVENT_KIND_HINT: Record<string, ActivityItem["level"]> = {
  agent_started: "success",
  agent_shutdown: "warn",
  client_connected: "success",
  client_disconnected: "warn",
  pair_started: "info",
  pair_success: "success",
  pair_failed: "error",
  tool_requested: "info",
  tool_progress: "info",
  tool_completed: "success",
  tool_failed: "error",
  heartbeat: "info",
  chat_message: "info",
  core_state: "info",
  notification_received: "info",
  task_created: "info",
  task_started: "info",
  step_started: "info",
  step_completed: "success",
  step_failed: "error",
  step_retrying: "warn",
  task_completed: "success",
  task_failed: "error",
  task_cancelled: "warn",
  device_registered: "success",
  device_state_changed: "warn",
  command_result_received: "info",
};

// Tool families registered in tools/__init__.py (verified against ToolRegistry).
export const TOOL_FAMILIES: Array<{
  name: string;
  label: string;
  description: string;
  actions: string[];
}> = [
  {
    name: "cmd",
    label: "Command",
    description: "Sandboxed terminal navigation (dir, echo, cd, where)",
    actions: ["dir", "echo", "cd", "where"],
  },
  {
    name: "windows_agent",
    label: "Windows Agent",
    description: "Core agent capabilities: windows, clipboard, vision, apps, filesystem, terminal, UIA, tasks",
    actions: [
      "core.clipboard:get",
      "core.clipboard:set",
      "core.window:list",
      "core.window:minimize",
      "core.window:maximize",
      "core.window:close",
      "core.window:focus",
      "core.vision:capture",
      "core.filesystem:search",
      "core.filesystem:open",
      "core.terminal:run",
      "core.terminal:kill",
      "core.app:list",
      "core.app:launch",
      "core.app:is_running",
      "core.app:close",
      "core.uia:click",
      "core.uia:inspect",
      "core.uia:find",
      "task.create",
      "task.run",
      "task.status",
      "task.cancel",
      "task.list",
      "task.orchestrate",
    ],
  },
  {
    name: "power",
    label: "Power",
    description: "Power state control and battery telemetry",
    actions: ["shutdown", "restart", "sleep", "hibernate", "lock", "logout", "cancel_shutdown", "status", "set_plan"],
  },
  {
    name: "media",
    label: "Media",
    description: "Volume and media playback control",
    actions: ["volume_up", "volume_down", "set_volume", "mute", "unmute", "play_pause", "next_track", "previous_track"],
  },
  {
    name: "notifications",
    label: "Notifications",
    description: "Show desktop notifications",
    actions: ["show"],
  },
  {
    name: "systeminfo",
    label: "System Info",
    description: "Windows hardware, OS and software inventory",
    actions: ["os", "cpu", "gpu", "memory", "disk", "network", "software", "processes", "all", "summary"],
  },
  {
    name: "process",
    label: "Process",
    description: "Process listing and lifecycle management",
    actions: ["list", "start", "stop"],
  },
  {
    name: "network",
    label: "Network",
    description: "Connectivity and network diagnostics",
    actions: ["test", "speedtest", "internet", "latency"],
  },
  {
    name: "service",
    label: "Service",
    description: "Windows service start/stop/query",
    actions: ["start", "stop", "query"],
  },
  {
    name: "input",
    label: "Input",
    description: "Keyboard and mouse automation",
    actions: ["type", "keypress", "hotkey", "click", "scroll"],
  },
  {
    name: "registry",
    label: "Registry",
    description: "Registry read/write (sandboxed)",
    actions: ["read", "write"],
  },
  {
    name: "fileops",
    label: "File Ops",
    description: "Sandboxed file operations",
    actions: ["create", "write", "read", "copy", "move", "delete", "search", "list"],
  },
  {
    name: "env",
    label: "Environment",
    description: "Environment variable inspection",
    actions: ["get", "set", "list"],
  },
];

export const TOOL_ACTION_LABELS: Record<string, string> = {
  "core.window:list": "List windows",
  "core.vision:capture": "Capture screen",
  "core.terminal:run": "Run terminal command",
  "core.terminal:kill": "Kill process",
  "core.app:launch": "Launch app",
  "core.app:list": "List apps",
  "core.clipboard:get": "Read clipboard",
  "core.clipboard:set": "Write clipboard",
  "core.filesystem:search": "Search files",
  "core.filesystem:open": "Open file",
  "core.uia:inspect": "Inspect UI",
  "task.list": "List tasks",
  "task.status": "Task status",
};

export const SYSTEM_STATUS_LABELS: Record<string, string> = {
  agent_started: "Agent started",
  agent_shutdown: "Agent stopped",
  client_connected: "Device connected",
  client_disconnected: "Device disconnected",
  pair_started: "Pairing started",
  pair_success: "Pairing successful",
  pair_failed: "Pairing failed",
  tool_requested: "Tool requested",
  tool_progress: "Tool running",
  tool_completed: "Tool completed",
  tool_failed: "Tool failed",
  heartbeat: "Heartbeat",
  chat_message: "Chat message",
  notification_received: "Notification",
  task_created: "Task created",
  task_started: "Task started",
  step_started: "Step started",
  step_completed: "Step completed",
  step_failed: "Step failed",
  step_retrying: "Step retrying",
  task_completed: "Task completed",
  task_failed: "Task failed",
  task_cancelled: "Task cancelled",
  device_registered: "Device registered",
  device_state_changed: "Device state changed",
  command_result_received: "Command result",
};