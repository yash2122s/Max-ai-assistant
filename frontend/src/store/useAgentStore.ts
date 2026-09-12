import { create } from "zustand";
import type { AgentStatus, ConnectionStatus, OrbPhase } from "@/lib/types";

interface AgentState {
  status: ConnectionStatus;
  agentOnline: boolean;
  agentVersion: string | null;
  protocolVersion: number | null;
  httpPort: number | null;
  wsPort: number | null;
  uptime: number | null;
  lastHeartbeatAt: number | null;
  lastEventId: string | null;
  orbPhase: OrbPhase;
  orbReason: string;
  retryCount: number;

  setConnecting: () => void;
  setOnline: () => void;
  setReconnecting: (retryCount: number) => void;
  setOffline: () => void;
  applyStatus: (data: AgentStatus) => void;
  applyAgentStarted: (wsPort: number, httpPort: number) => void;
  applyAgentShutdown: () => void;
  applyHeartbeat: () => void;
  setOrb: (phase: OrbPhase, reason?: string) => void;
  setLastEventId: (id: string | null) => void;
}

export const useAgentStore = create<AgentState>((set) => ({
  status: "connecting",
  agentOnline: false,
  agentVersion: null,
  protocolVersion: null,
  httpPort: null,
  wsPort: null,
  uptime: null,
  lastHeartbeatAt: null,
  lastEventId: null,
  orbPhase: "standby",
  orbReason: "",
  retryCount: 0,

  setConnecting: () =>
    set({ status: "connecting", orbPhase: "standby", orbReason: "connecting" }),
  setOnline: () => set({ status: "online" }),
  setReconnecting: (retryCount) =>
    set({ status: "reconnecting", retryCount, orbPhase: "standby", orbReason: "reconnecting" }),
  setOffline: () => set({ status: "offline", agentOnline: false }),

  applyStatus: (data) =>
    set({
      agentOnline: true,
      agentVersion: data.agent_version,
      protocolVersion: data.protocol_version,
      httpPort: data.http_port,
      wsPort: data.ws_port,
      uptime: data.uptime,
      status: "online",
    }),

  applyAgentStarted: (wsPort, httpPort) =>
    set({
      agentOnline: true,
      wsPort,
      httpPort,
      orbPhase: "standby",
      orbReason: "online",
    }),

  applyAgentShutdown: () =>
    set({
      agentOnline: false,
      orbPhase: "error",
      orbReason: "agent stopped",
    }),

  applyHeartbeat: () => set({ lastHeartbeatAt: Date.now() / 1000 }),

  setOrb: (phase, reason = "") => set({ orbPhase: phase, orbReason: reason }),

  setLastEventId: (id) => set({ lastEventId: id }),
}));

export function isAgentOnline(state: Pick<AgentState, "status" | "agentOnline">): boolean {
  return state.status === "online" && state.agentOnline;
}