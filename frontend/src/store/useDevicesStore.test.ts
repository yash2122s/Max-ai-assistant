import { describe, it, expect } from "vitest";
import { useDevicesStore } from "@/store/useDevicesStore";

describe("devices store", () => {
  it("registers and connects a device", () => {
    useDevicesStore.getState().deviceRegistered({
      device_id: "dev1",
      display_name: "Redmi K20 Pro",
      device_type: "phone",
      platform: "android",
      capabilities: ["screen_capture", "notifications"],
    });
    useDevicesStore.getState().clientConnected({
      device_id: "dev1",
      platform: "android",
      device_name: "Redmi K20 Pro",
    });
    const dev = useDevicesStore.getState().devices["dev1"];
    expect(dev.connection_state).toBe("connected");
    expect(dev.capabilities).toHaveLength(2);
  });

  it("disconnects a device via client_disconnected", () => {
    useDevicesStore.getState().clientConnected({ device_id: "dev1", platform: "android", device_name: "P" });
    useDevicesStore.getState().clientDisconnected({ device_id: "dev1" });
    expect(useDevicesStore.getState().devices["dev1"].connection_state).toBe("disconnected");
  });

  it("handles device_state_changed", () => {
    useDevicesStore.getState().clientConnected({ device_id: "dev1", platform: "android", device_name: "P" });
    useDevicesStore.getState().deviceStateChanged({ device_id: "dev1", state: "DISCONNECTED" });
    expect(useDevicesStore.getState().devices["dev1"].connection_state).toBe("disconnected");
  });

  it("seeds from /api/status connections", () => {
    useDevicesStore.getState().applyStatusConnections([
      { device_id: "a", platform: "android", device_name: "A", connected_at: 1, last_seen: 1, latency_ms: 5 },
    ]);
    const dev = useDevicesStore.getState().devices["a"];
    expect(dev.connection_state).toBe("connected");
    expect(dev.latency_ms).toBe(5);
  });
});