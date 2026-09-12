import { create } from "zustand";
import type {
  ClientConnectedPayload,
  ClientDisconnectedPayload,
  ConnectionInfo,
  DeviceRegisteredPayload,
  DeviceStateChangedPayload,
  DeviceVM,
  HeartbeatPayload,
} from "@/lib/types";

interface DevicesState {
  devices: Record<string, DeviceVM>;
  selectedId: string | null;

  applyStatusConnections: (connections: ConnectionInfo[]) => void;
  deviceRegistered: (payload: DeviceRegisteredPayload) => void;
  clientConnected: (payload: ClientConnectedPayload) => void;
  clientDisconnected: (payload: ClientDisconnectedPayload) => void;
  deviceStateChanged: (payload: DeviceStateChangedPayload) => void;
  heartbeat: (payload: HeartbeatPayload) => void;
  select: (deviceId: string | null) => void;
}

function nowSeconds(): number {
  return Date.now() / 1000;
}

export const useDevicesStore = create<DevicesState>((set) => ({
  devices: {},
  selectedId: null,

  applyStatusConnections: (connections) =>
    set((state) => {
      const next = { ...state.devices };
      const connected = new Set<string>();
      for (const conn of connections) {
        connected.add(conn.device_id);
        next[conn.device_id] = {
          device_id: conn.device_id,
          display_name: conn.device_name || conn.device_id,
          platform: conn.platform || "unknown",
          connection_state: "connected",
          connected_at: conn.connected_at,
          last_seen: conn.last_seen,
          latency_ms: conn.latency_ms,
          known: true,
        };
      }
      // Anything in the snapshot but no longer connected is left as-is; the
      // disconnect events will update it. Only drop devices we have never seen
      // events for.
      return { devices: next };
    }),

  deviceRegistered: (payload) =>
    set((state) => {
      const prev = state.devices[payload.device_id];
      return {
        devices: {
          ...state.devices,
          [payload.device_id]: {
            device_id: payload.device_id,
            display_name: payload.display_name || prev?.display_name || payload.device_id,
            platform: payload.platform || prev?.platform || "unknown",
            device_type: payload.device_type || prev?.device_type,
            capabilities: payload.capabilities || prev?.capabilities,
            connection_state: prev?.connection_state ?? "connected",
            connected_at: prev?.connected_at,
            last_seen: prev?.last_seen ?? nowSeconds(),
            known: true,
          },
        },
      };
    }),

  clientConnected: (payload) =>
    set((state) => {
      const prev = state.devices[payload.device_id];
      return {
        devices: {
          ...state.devices,
          [payload.device_id]: {
            ...prev,
            device_id: payload.device_id,
            display_name: payload.device_name || prev?.display_name || payload.device_id,
            platform: payload.platform || prev?.platform || "unknown",
            connection_state: "connected",
            connected_at: nowSeconds(),
            last_seen: nowSeconds(),
            known: true,
          },
        },
      };
    }),

  clientDisconnected: (payload) =>
    set((state) => {
      const prev = state.devices[payload.device_id];
      if (!prev) return {};
      return {
        devices: {
          ...state.devices,
          [payload.device_id]: { ...prev, connection_state: "disconnected" },
        },
      };
    }),

  deviceStateChanged: (payload) =>
    set((state) => {
      const prev = state.devices[payload.device_id];
      if (!prev) return {};
      const disconnected = /disconnected/i.test(payload.state);
      return {
        devices: {
          ...state.devices,
          [payload.device_id]: {
            ...prev,
            connection_state: disconnected ? "disconnected" : "connected",
          },
        },
      };
    }),

  heartbeat: (payload) =>
    set((state) => {
      const prev = state.devices[payload.device_id];
      if (!prev) return {};
      return {
        devices: {
          ...state.devices,
          [payload.device_id]: { ...prev, last_seen: nowSeconds() },
        },
      };
    }),

  select: (deviceId) => set({ selectedId: deviceId }),
}));

export function getConnectedCount(state: Pick<DevicesState, "devices">): number {
  return Object.values(state.devices).filter((d) => d.connection_state === "connected").length;
}