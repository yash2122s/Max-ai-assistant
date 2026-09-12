// Exact backend shapes — verified against windows-agent (api_router.py,
// event_bus.py, metrics_manager.py, websocket_server.py, task_engine.py,
// step_runner.py, models.py). Do not rename event types; the backend emits
// these string values verbatim over SSE.

// ---------------------------------------------------------------------------
// HTTP envelope
// ---------------------------------------------------------------------------

export interface ApiEnvelope<T> {
  success: boolean;
  data?: T;
  error?: { code: string; message: string };
}

// ---------------------------------------------------------------------------
// /api/status
// ---------------------------------------------------------------------------

export interface ConnectionInfo {
  device_id: string;
  platform: string;
  device_name: string;
  connected_at: number;
  last_seen: number;
  latency_ms: number;
}

export interface MetricsSnapshot {
  events_sent: number;
  connections: number;
  tool_requests: number;
  avg_latency_ms: number;
  cpu_percent: number;
  cpu_cores: number;
  ram_percent: number;
  ram_used_gb: number;
  ram_total_gb: number;
  disk_percent: number;
  disk_used_gb: number;
  disk_total_gb: number;
  system_uptime: number;
  agent_uptime: number;
  os_info: string;
  hostname: string;
}

export interface AgentStatus {
  agent_version: string;
  protocol_version: number;
  uptime: number;
  connected_devices: number;
  paired_devices: number;
  metrics: MetricsSnapshot;
  connections: ConnectionInfo[];
  http_port: number;
  ws_port: number;
}

// ---------------------------------------------------------------------------
// /api/pairing (localhost only)
// ---------------------------------------------------------------------------

export interface PairingInfo {
  protocol: string;
  device_id: string;
  device_name: string;
  platform: string;
  ip: string;
  port: number;
  pairing_code: string;
  cert_fingerprint: string;
}

// ---------------------------------------------------------------------------
// /api/chat
// ---------------------------------------------------------------------------

export interface ChatResponse {
  user_message: string;
  max_response: string;
}

// ---------------------------------------------------------------------------
// /api/tool
// ---------------------------------------------------------------------------

export interface ToolResult {
  status: "success" | "failed" | "error" | "ok" | "info" | "awaiting_confirmation";
  output?: unknown;
  error?: { code?: string; message?: string } | string;
  message?: string;
}

export interface ToolRequestPayload {
  tool: string;
  action: string;
  args?: Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// SSE event envelope
// ---------------------------------------------------------------------------

export interface BusEvent<T = Record<string, unknown>> {
  schema_version: number;
  id: string;
  type: string;
  timestamp: number;
  payload: T;
}

// Event type strings (verbatim from protocol/event_types.py)
export const EventType = {
  AGENT_STARTED: "agent_started",
  AGENT_SHUTDOWN: "agent_shutdown",
  CLIENT_CONNECTED: "client_connected",
  CLIENT_DISCONNECTED: "client_disconnected",
  PAIR_STARTED: "pair_started",
  PAIR_SUCCESS: "pair_success",
  PAIR_FAILED: "pair_failed",
  TOOL_REQUESTED: "tool_requested",
  TOOL_PROGRESS: "tool_progress",
  TOOL_COMPLETED: "tool_completed",
  TOOL_FAILED: "tool_failed",
  HEARTBEAT: "heartbeat",
  CHAT_MESSAGE: "chat_message",
  CORE_STATE: "core_state",
  NOTIFICATION_RECEIVED: "notification_received",
  TASK_CREATED: "task_created",
  TASK_STARTED: "task_started",
  STEP_STARTED: "step_started",
  STEP_COMPLETED: "step_completed",
  STEP_FAILED: "step_failed",
  STEP_RETRYING: "step_retrying",
  TASK_COMPLETED: "task_completed",
  TASK_FAILED: "task_failed",
  TASK_CANCELLED: "task_cancelled",
  DEVICE_REGISTERED: "device_registered",
  DEVICE_STATE_CHANGED: "device_state_changed",
  COMMAND_RESULT_RECEIVED: "command_result_received",
} as const;

export type EventTypeName = (typeof EventType)[keyof typeof EventType];

// ---------------------------------------------------------------------------
// Event payload shapes
// ---------------------------------------------------------------------------

export interface AgentStartedPayload {
  ws_port: number;
  http_port: number;
}

export interface DeviceRegisteredPayload {
  device_id: string;
  display_name: string;
  device_type: string;
  platform: string;
  capabilities: unknown[];
}

export interface ClientConnectedPayload {
  device_id: string;
  platform: string;
  device_name: string;
}

export interface ClientDisconnectedPayload {
  device_id: string;
}

export interface DeviceStateChangedPayload {
  device_id: string;
  state: string;
}

export interface HeartbeatPayload {
  device_id: string;
}

export interface ChatMessagePayload {
  role: "user" | "max" | string;
  text: string;
  timestamp: number;
}

export interface ToolRequestedPayload {
  device_id?: string;
  tool: string;
  action: string;
  arguments?: Record<string, unknown>;
  args?: Record<string, unknown>;
}

export interface ToolProgressPayload {
  device_id?: string;
  tool: string;
  action: string;
  message: string;
}

export interface ToolCompletedPayload {
  device_id?: string;
  tool: string;
  action: string;
  status: string;
  output?: unknown;
  result?: ToolResult;
}

export interface ToolFailedPayload {
  device_id?: string;
  tool: string;
  action: string;
  status?: string;
  error?: string | Record<string, unknown>;
}

export interface NotificationPayload {
  device_id: string;
  package_name: string;
  title: string;
  text: string;
  timestamp: number;
}

export interface PairEventPayload {
  device_id: string;
  device_name?: string;
  reason?: string;
}

export interface TaskCreatedPayload {
  task_id: string;
  goal: string;
  total_steps: number;
}

export interface TaskStartedPayload {
  task_id: string;
  goal: string;
  total_steps: number;
}

export interface StepStartedPayload {
  task_id: string;
  step_id: string;
  sequence: number;
  action: string;
  attempt: number;
}

export interface StepCompletedPayload {
  task_id: string;
  step_id: string;
  sequence: number;
  duration_ms: number;
  attempts: number;
}

export interface StepFailedPayload {
  task_id: string;
  step_id: string;
  sequence?: number;
  reason?: string;
  error?: string;
}

export interface StepRetryingPayload {
  task_id: string;
  step_id: string;
  attempt: number;
  max_attempts: number;
}

export interface TaskCompletedPayload {
  task_id: string;
  goal: string;
  duration_ms: number;
}

export interface TaskFailedPayload {
  task_id: string;
  error?: string;
}

export interface TaskCancelledPayload {
  task_id: string;
  reason?: string;
}

export interface CommandResultPayload {
  device_id: string;
  command_id: string;
  status: string;
  action: string;
  data?: unknown;
  error?: unknown;
  execution_time_ms?: number;
}

// ---------------------------------------------------------------------------
// Task model (engine/models.py Task.to_dict / Step.to_dict)
// ---------------------------------------------------------------------------

export type TaskStateValue =
  | "CREATED"
  | "PLANNING"
  | "READY"
  | "RUNNING"
  | "VERIFYING"
  | "RETRYING"
  | "RECOVERING"
  | "WAITING"
  | "PAUSED"
  | "BLOCKED"
  | "FAILED"
  | "CANCELLED"
  | "COMPLETED"
  | "EXPIRED"
  | "INTERRUPTED";

export type StepStateValue =
  | "PENDING"
  | "RUNNING"
  | "VERIFYING"
  | "RETRYING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED"
  | "SKIPPED"
  | "BLOCKED"
  | "INTERRUPTED";

export interface TaskStepDto {
  step_id: string;
  task_id: string;
  sequence: number;
  action: string;
  arguments: Record<string, unknown>;
  status: StepStateValue;
  attempts: number;
  max_attempts: number;
  timeout_s: number;
  risk_level: string;
  retryable: boolean;
  verification: { type: string; target?: string | null; timeout_seconds: number };
  result?: unknown;
  error?: { code?: string; message?: string } | null;
  started_at?: number | null;
  completed_at?: number | null;
}

export interface TaskDto {
  schema_version: string;
  task_id: string;
  goal: string;
  status: TaskStateValue;
  created_at: number;
  updated_at: number;
  priority: number;
  source_device: string;
  target_device: string;
  risk_level: string;
  current_step: number;
  total_steps: number;
  steps: TaskStepDto[];
  parent_task_id?: string | null;
  context: Record<string, unknown>;
  error?: { code?: string; message?: string } | null;
  result?: Record<string, unknown> | null;
}

// ---------------------------------------------------------------------------
// Client-side view models
// ---------------------------------------------------------------------------

export type ConnectionStatus = "connecting" | "online" | "reconnecting" | "offline";

export type OrbPhase =
  | "standby"
  | "listening"
  | "thinking"
  | "executing"
  | "speaking"
  | "waiting_confirmation"
  | "error";

export interface DeviceVM {
  device_id: string;
  display_name: string;
  platform: string;
  device_type?: string;
  capabilities?: unknown[];
  connection_state: "connected" | "disconnected";
  connected_at?: number;
  last_seen?: number;
  latency_ms?: number;
  known: boolean;
}

export interface ChatMessageVM {
  id: string;
  role: "user" | "max" | "system";
  text: string;
  ts: number;
}

export type ActivityKind =
  | "system"
  | "chat"
  | "tool"
  | "task"
  | "device"
  | "pairing"
  | "command"
  | "notification"
  | "error";

export interface ActivityItem {
  id: string;
  kind: ActivityKind;
  eventType: string;
  title: string;
  detail?: string;
  ts: number;
  level: "info" | "success" | "warn" | "error";
  deviceId?: string;
  meta?: Record<string, unknown>;
}

export interface ToolTrace {
  key: string;
  tool: string;
  action: string;
  args?: Record<string, unknown>;
  deviceId?: string;
  status: "RUNNING" | "SUCCESS" | "FAILED";
  progress?: string;
  output?: unknown;
  error?: string;
  startedAt: number;
  finishedAt?: number;
}

export interface NotificationVM {
  id: string;
  deviceId: string;
  packageName: string;
  title: string;
  text: string;
  ts: number;
}

export interface SystemInfoSummary {
  hostname?: string;
  os?: string;
  os_version?: string;
  cpu?: string;
  cores?: number;
  ram?: number;
  ram_used?: number;
  disks?: number;
  uptime?: string;
}

export interface PowerStatus {
  on_battery?: boolean;
  battery?: {
    estimated_charge_remaining?: number;
    charge_percent?: number;
    battery_status?: number;
    is_charging?: boolean;
    estimated_minutes?: number;
  } | null;
  ups?: unknown;
  power_plan?: string;
}