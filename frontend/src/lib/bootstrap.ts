import type { BusEvent } from "./types";
import {
  getOrCreateSseClient,
  type SseClient,
  type StreamStatus,
} from "./sse";
import {
  api,
  fetchActiveTask,
  fetchPowerStatus,
  fetchSystemSummary,
} from "./api";
import { POLL_INTERVAL_MS } from "./constants";
import { useAgentStore } from "@/store/useAgentStore";
import { useSystemStore } from "@/store/useSystemStore";
import { useDevicesStore } from "@/store/useDevicesStore";
import { useActivityStore, handleBusEvent } from "@/store/useActivityStore";
import {
  useTasksStore,
  applyTaskCreated,
  applyTaskStarted,
  applyStepStarted,
  applyStepCompleted,
  applyStepFailed,
  applyStepRetrying,
  applyTaskCompleted,
  applyTaskFailed,
  applyTaskCancelled,
} from "@/store/useTasksStore";
import { useContextStore } from "@/store/useContextStore";
import { extractErrorMessage } from "./format";

// ---------------------------------------------------------------------------
// Event routing (device / task / context / chat events → their stores)
// ---------------------------------------------------------------------------

function routeDeviceEvents(event: BusEvent): void {
  const payload = event.payload ?? {};
  switch (event.type) {
    case "device_registered":
      useDevicesStore.getState().deviceRegistered(payload as never);
      break;
    case "client_connected":
      useDevicesStore.getState().clientConnected(payload as never);
      break;
    case "client_disconnected":
      useDevicesStore.getState().clientDisconnected(payload as never);
      break;
    case "device_state_changed":
      useDevicesStore.getState().deviceStateChanged(payload as never);
      break;
    case "heartbeat":
      if (payload.device_id) {
        useDevicesStore.getState().heartbeat(payload as never);
        useAgentStore.getState().applyHeartbeat();
      }
      break;
    default:
      break;
  }
}

function routeTaskEvents(event: BusEvent): void {
  switch (event.type) {
    case "task_created":
      applyTaskCreated(event.payload as never);
      break;
    case "task_started":
      applyTaskStarted(event.payload as never);
      break;
    case "step_started":
      applyStepStarted(event.payload as never);
      break;
    case "step_completed":
      applyStepCompleted(event.payload as never);
      break;
    case "step_failed":
      applyStepFailed(event.payload as never);
      break;
    case "step_retrying":
      applyStepRetrying(event.payload as never);
      break;
    case "task_completed":
      applyTaskCompleted(event.payload as never);
      break;
    case "task_failed":
      applyTaskFailed(event.payload as never);
      break;
    case "task_cancelled":
      applyTaskCancelled(event.payload as never);
      break;
    default:
      break;
  }
}

function routeContextEvents(event: BusEvent): void {
  const payload = event.payload ?? {};
  if (event.type === "notification_received") {
    useContextStore.getState().addNotification(payload as never);
  }
}

function onEvent(event: BusEvent): void {
  useAgentStore.getState().setLastEventId(event.id);
  handleBusEvent(event);
  routeDeviceEvents(event);
  routeTaskEvents(event);
  routeContextEvents(event);
}

function onStatus(status: StreamStatus, retryCount: number): void {
  const agent = useAgentStore.getState();
  switch (status) {
    case "connecting":
      agent.setConnecting();
      break;
    case "online":
      agent.setOnline();
      break;
    case "reconnecting":
      agent.setReconnecting(retryCount);
      break;
    case "offline":
      agent.setOffline();
      break;
  }
}

// ---------------------------------------------------------------------------
// HTTP snapshot + polling
// ---------------------------------------------------------------------------

async function initialSnapshot(): Promise<void> {
  try {
    const status = await api.getStatus();
    useAgentStore.getState().applyStatus(status);
    useSystemStore.getState().applyMetrics(status.metrics);
    useDevicesStore.getState().applyStatusConnections(status.connections);
  } catch (err) {
    const message = extractErrorMessage(err);
    useAgentStore.getState().setOffline();
    useActivityStore.getState().push({
      id: `err_${Date.now()}`,
      kind: "error",
      eventType: "http_snapshot_failed",
      title: `Backend unreachable: ${message}`,
      ts: Date.now() / 1000,
      level: "error",
    });
  }
}

async function pollMetrics(): Promise<void> {
  try {
    const metrics = await api.getMetrics();
    useSystemStore.getState().applyMetrics(metrics);
  } catch {
    // transient — leave last known metrics in place
  }
}

// Hydrate task state through the real backend path (windows_agent task tools).
// These /api/tool calls publish TOOL_REQUESTED/TOOL_COMPLETED events; mark them
// silent so they don't clutter the activity feed.
async function hydrateTasks(): Promise<void> {
  const activity = useActivityStore.getState();
  activity.markSilent("windows_agent", "task.status");
  activity.markSilent("windows_agent", "task.list");
  try {
    const res = await fetchActiveTask();
    const data = (res as { output?: unknown } | undefined)?.output;
    if (data && typeof data === "object" && (data as { task_id?: string }).task_id) {
      useTasksStore.getState().upsert(data as never);
    }
  } catch {
    // backend task engine unavailable — keep current state
  }
  try {
    const res = await api.postTool({ tool: "windows_agent", action: "task.list", args: { limit: 10 } });
    const data = (res as { output?: { tasks?: unknown[] } } | undefined)?.output;
    if (data && Array.isArray(data.tasks)) {
      useTasksStore.getState().setTasks(data.tasks as never);
    }
  } catch {
    // ignore
  }
}

async function hydrateSystem(): Promise<void> {
  const activity = useActivityStore.getState();
  activity.markSilent("systeminfo", "summary");
  activity.markSilent("power", "status");
  activity.markSilent("windows_agent", "core.window:list");
  try {
    const res = await fetchSystemSummary();
    const data = (res as { output?: unknown } | undefined)?.output;
    if (data && typeof data === "object") {
      useSystemStore.getState().applySummary(data as never);
    }
  } catch (err) {
    useSystemStore.getState().setSummaryError(extractErrorMessage(err));
  }
  try {
    const res = await fetchPowerStatus();
    const data = (res as { output?: unknown } | undefined)?.output;
    if (data && typeof data === "object") {
      useSystemStore.getState().applyPower(data as never);
    }
  } catch (err) {
    useSystemStore.getState().setPowerError(extractErrorMessage(err));
  }
  try {
    const res = await api.postTool({ tool: "windows_agent", action: "core.window:list" });
    const data = (res as { output?: unknown } | undefined)?.output;
    if (Array.isArray(data)) {
      useContextStore.getState().setWindows(data);
    }
  } catch (err) {
    useContextStore.getState().setWindowsError(extractErrorMessage(err));
  }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

let timers: ReturnType<typeof setInterval>[] = [];
let client: SseClient | null = null;

/** Starts the single SSE connection + HTTP snapshot + polling loops. */
export function startAgentConnection(): void {
  client = getOrCreateSseClient({
    url: "/api/events",
    onEvent,
    onStatus,
    getLastEventId: () => useAgentStore.getState().lastEventId,
  });
  client.start();

  void initialSnapshot();
  void hydrateTasks();
  void hydrateSystem();

  timers.push(setInterval(() => void pollMetrics(), POLL_INTERVAL_MS));
  timers.push(setInterval(() => void hydrateTasks(), 30_000));
  timers.push(setInterval(() => void hydrateSystem(), 60_000));
}

/** Tears down SSE connection, timers and resets runtime state. */
export function stopAgentConnection(): void {
  for (const t of timers) {
    clearInterval(t);
  }
  timers = [];
  client?.stop();
  client = null;
  useAgentStore.getState().setOffline();
}

export const connectionInternals = { onEvent, onStatus };