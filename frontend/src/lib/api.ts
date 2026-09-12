import type {
  ApiEnvelope,
  AgentStatus,
  MetricsSnapshot,
  PairingInfo,
  ChatResponse,
  ToolResult,
  ToolRequestPayload,
} from "./types";

// Security: no API keys, tokens or pairing secrets are ever persisted. A
// remote-access token (a paired-device token, used only for non-local
// deployments) lives in memory for the lifetime of the page and is never
// written to localStorage/sessionStorage or logged.

let remoteToken: string | null = null;

export function setRemoteToken(token: string | null) {
  remoteToken = token;
}

export function getRemoteToken(): string | null {
  return remoteToken;
}

async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    Accept: "application/json",
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (remoteToken && (init?.method === "POST" || init?.method === undefined)) {
    headers.Authorization = `Bearer ${remoteToken}`;
  }
  const res = await fetch(url, { ...init, headers });
  let body: ApiEnvelope<T> | null = null;
  try {
    body = (await res.json()) as ApiEnvelope<T>;
  } catch {
    body = null;
  }
  if (!res.ok || !body?.success) {
    const message =
      body?.error?.message ??
      `Request failed (HTTP ${res.status})`;
    const code = body?.error?.code ?? "HTTP_ERROR";
    const err = new Error(message) as Error & { code?: string; status?: number };
    err.code = code;
    err.status = res.status;
    throw err;
  }
  return body.data as T;
}

export const api = {
  async getStatus(): Promise<AgentStatus> {
    return fetchJson<AgentStatus>("/api/status");
  },

  async getMetrics(): Promise<MetricsSnapshot> {
    return fetchJson<MetricsSnapshot>("/metrics");
  },

  async getHealth(): Promise<{ status: string }> {
    return fetchJson<{ status: string }>("/health");
  },

  async getPairing(): Promise<PairingInfo> {
    return fetchJson<PairingInfo>("/api/pairing");
  },

  async postChat(message: string): Promise<ChatResponse> {
    return fetchJson<ChatResponse>("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message }),
    });
  },

  async postTool(req: ToolRequestPayload): Promise<ToolResult> {
    return fetchJson<ToolResult>("/api/tool", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        tool: req.tool,
        action: req.action,
        args: req.args ?? {},
        ...(remoteToken ? { token: remoteToken } : {}),
      }),
    });
  },
};

export interface PolledTaskInfo {
  active?: unknown;
}

export async function fetchActiveTask(): Promise<unknown> {
  try {
    return await api.postTool({ tool: "windows_agent", action: "task.status" });
  } catch {
    return undefined;
  }
}

export async function fetchSystemSummary(): Promise<unknown> {
  try {
    return await api.postTool({ tool: "systeminfo", action: "summary" });
  } catch {
    return undefined;
  }
}

export async function fetchPowerStatus(): Promise<unknown> {
  try {
    return await api.postTool({ tool: "power", action: "status" });
  } catch {
    return undefined;
  }
}