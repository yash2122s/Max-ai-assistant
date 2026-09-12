import { create } from "zustand";
import type { ActivityItem, BusEvent, ChatMessagePayload, NotificationPayload, ToolTrace } from "@/lib/types";
import { MAX_ACTIVITY_CAP, EVENT_KIND_HINT, SYSTEM_STATUS_LABELS } from "@/lib/constants";
import { extractErrorMessage, formatToolOutput } from "@/lib/format";
import { useAgentStore } from "./useAgentStore";
import { useChatStore } from "./useChatStore";

interface ActivityState {
  items: ActivityItem[];
  toolTraces: Record<string, ToolTrace>;
  silentKeys: Set<string>;

  push: (item: ActivityItem) => void;
  setTrace: (key: string, trace: ToolTrace) => void;
  markSilent: (tool: string, action: string) => void;
  clear: () => void;
}

const traceKey = (tool: string, action: string) => `${tool}/${action}`;

function isSilent(state: Pick<ActivityState, "silentKeys">, tool: string, action: string): boolean {
  return state.silentKeys.has(traceKey(tool, action));
}

function makeItem(
  event: BusEvent,
  kind: ActivityItem["kind"],
  title: string,
  detail?: string,
  meta?: Record<string, unknown>,
): ActivityItem {
  const level = EVENT_KIND_HINT[event.type] ?? "info";
  return {
    id: event.id,
    kind,
    eventType: event.type,
    title,
    detail,
    ts: event.timestamp,
    level,
    deviceId: (event.payload as Record<string, unknown>).device_id as string | undefined,
    meta,
  };
}

function ensureTrace(
  state: Pick<ActivityState, "toolTraces">,
  tool: string,
  action: string,
  event: BusEvent,
): ToolTrace {
  const key = traceKey(tool, action);
  return (
    state.toolTraces[key] ?? {
      key,
      tool,
      action,
      deviceId: (event.payload as { device_id?: string }).device_id,
      status: "RUNNING",
      startedAt: event.timestamp,
    }
  );
}

/** Routes a verified backend SSE event to the relevant stores. Pure dispatch. */
export function handleBusEvent(event: BusEvent): void {
  const payload = event.payload ?? {};
  const activity = useActivityStore.getState();
  const kindLabel = SYSTEM_STATUS_LABELS[event.type] ?? event.type;

  switch (event.type) {
    case "chat_message": {
      const role = (payload as { role?: string }).role;
      const text = (payload as { text?: string }).text ?? "";
      activity.push(
        makeItem(event, "chat", role === "user" ? "You" : "MAX", text?.slice(0, 200), { role }),
      );
      useChatStore.getState().applyChatMessage(payload as unknown as ChatMessagePayload);
      break;
    }
    case "tool_requested": {
      const p = payload as { tool?: string; action?: string; arguments?: Record<string, unknown> };
      const tool = p.tool ?? "";
      const action = p.action ?? "";
      const silent = isSilent(activity, tool, action);
      if (!silent) {
        activity.push(makeItem(event, "tool", `Tool: ${tool}/${action}`, undefined, { tool, action }));
        useAgentStore.getState().setOrb("executing", `${tool}/${action}`);
      }
      activity.setTrace(traceKey(tool, action), {
        ...ensureTrace(activity, tool, action, event),
        args: p.arguments,
        status: "RUNNING",
      });
      break;
    }
    case "tool_progress": {
      const p = payload as { tool?: string; action?: string; message?: string };
      const tool = p.tool ?? "";
      const action = p.action ?? "";
      const key = traceKey(tool, action);
      const prev = activity.toolTraces[key];
      if (prev) {
        activity.setTrace(key, { ...prev, status: "RUNNING", progress: p.message });
      }
      if (!isSilent(activity, tool, action)) {
        activity.push(makeItem(event, "tool", `Running: ${tool}/${action}`, p.message));
      }
      break;
    }
    case "tool_completed": {
      const p = payload as {
        tool?: string;
        action?: string;
        output?: unknown;
        result?: { status?: string; output?: unknown };
      };
      const tool = p.tool ?? "";
      const action = p.action ?? "";
      const key = traceKey(tool, action);
      activity.setTrace(key, {
        ...ensureTrace(activity, tool, action, event),
        status: "SUCCESS",
        output: p.output ?? p.result?.output,
        finishedAt: event.timestamp,
        progress: undefined,
      });
      if (!isSilent(activity, tool, action)) {
        const out = formatToolOutput(p.output ?? p.result?.output);
        activity.push(makeItem(event, "tool", `Completed: ${tool}/${action}`, out || "Success"));
        useAgentStore.getState().setOrb("standby");
      }
      break;
    }
    case "tool_failed": {
      const p = payload as { tool?: string; action?: string; error?: string | Record<string, unknown> };
      const tool = p.tool ?? "";
      const action = p.action ?? "";
      const key = traceKey(tool, action);
      const errorText = extractErrorMessage(p.error);
      activity.setTrace(key, {
        ...ensureTrace(activity, tool, action, event),
        status: "FAILED",
        error: errorText,
        finishedAt: event.timestamp,
        progress: undefined,
      });
      if (!isSilent(activity, tool, action)) {
        activity.push(makeItem(event, "tool", `Failed: ${tool}/${action}`, errorText || "Execution failed"));
        useAgentStore.getState().setOrb("error", `${tool}/${action}`);
      }
      break;
    }
    case "task_started": {
      const goal = (payload as { goal?: string }).goal ?? "";
      activity.push(makeItem(event, "task", `Task started: ${goal}`));
      useAgentStore.getState().setOrb("executing", "task");
      break;
    }
    case "task_completed": {
      const goal = (payload as { goal?: string }).goal ?? "";
      activity.push(
        makeItem(event, "task", `Task completed: ${goal}`, undefined, {
          duration_ms: (payload as { duration_ms?: number }).duration_ms,
        }),
      );
      useAgentStore.getState().setOrb("standby");
      break;
    }
    case "task_failed": {
      const err = (payload as { error?: string }).error ?? "Execution failed";
      activity.push(makeItem(event, "task", `Task failed: ${err}`));
      useAgentStore.getState().setOrb("error", "task failed");
      break;
    }
    case "task_cancelled":
      activity.push(makeItem(event, "task", "Task cancelled"));
      useAgentStore.getState().setOrb("standby");
      break;
    case "task_created": {
      const goal = (payload as { goal?: string }).goal ?? "";
      activity.push(makeItem(event, "task", `Task created: ${goal}`));
      break;
    }
    case "step_started":
    case "step_completed":
    case "step_failed":
    case "step_retrying": {
      const p = payload as { action?: string; step_id?: string; reason?: string; error?: string };
      activity.push(
        makeItem(event, "task", kindLabel, p.action ?? p.reason ?? p.error ?? p.step_id ?? ""),
      );
      break;
    }
    case "device_registered": {
      const name = (payload as { display_name?: string }).display_name ?? "";
      activity.push(makeItem(event, "device", `Device registered: ${name}`));
      break;
    }
    case "client_connected": {
      const name = (payload as { device_name?: string }).device_name ?? "";
      activity.push(makeItem(event, "device", `Connected: ${name}`));
      break;
    }
    case "client_disconnected":
      activity.push(makeItem(event, "device", "Device disconnected"));
      break;
    case "device_state_changed": {
      const state = (payload as { state?: string }).state ?? "";
      activity.push(makeItem(event, "device", `Device state: ${state}`));
      break;
    }
    case "notification_received": {
      const p = payload as unknown as NotificationPayload;
      activity.push(
        makeItem(event, "notification", `Notification: ${p.title || p.package_name}`, p.text?.slice(0, 160)),
      );
      break;
    }
    case "pair_started":
    case "pair_success":
    case "pair_failed":
      activity.push(makeItem(event, "pairing", kindLabel, (payload as { reason?: string }).reason));
      break;
    case "command_result_received": {
      const p = payload as { action?: string; status?: string };
      activity.push(makeItem(event, "command", `Command result: ${p.action ?? ""}`, `status=${p.status ?? ""}`));
      break;
    }
    case "agent_started":
      activity.push(makeItem(event, "system", "MAX agent started"));
      useAgentStore.getState().applyAgentStarted(
        (payload as { ws_port?: number }).ws_port ?? 0,
        (payload as { http_port?: number }).http_port ?? 0,
      );
      break;
    case "agent_shutdown":
      activity.push(makeItem(event, "system", "MAX agent stopped"));
      useAgentStore.getState().applyAgentShutdown();
      break;
    case "heartbeat":
      break;
    default:
      activity.push(makeItem(event, "system", kindLabel));
  }
}

export const useActivityStore = create<ActivityState>((set) => ({
  items: [],
  toolTraces: {},
  silentKeys: new Set(),

  push: (item) =>
    set((state) => {
      if (state.items.some((i) => i.id === item.id)) return {};
      return { items: [...state.items, item].slice(-MAX_ACTIVITY_CAP) };
    }),

  setTrace: (key, trace) =>
    set((state) => ({ toolTraces: { ...state.toolTraces, [key]: trace } })),

  markSilent: (tool, action) =>
    set((state) => {
      const next = new Set(state.silentKeys);
      next.add(traceKey(tool, action));
      return { silentKeys: next };
    }),

  clear: () => set({ items: [], toolTraces: {}, silentKeys: new Set() }),
}));