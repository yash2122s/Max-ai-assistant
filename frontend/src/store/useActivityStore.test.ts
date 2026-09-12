import { describe, it, expect } from "vitest";
import { useActivityStore, handleBusEvent } from "@/store/useActivityStore";
import { useAgentStore } from "@/store/useAgentStore";
import type { BusEvent } from "@/lib/types";

function makeEvent(id: string, type: string, payload: Record<string, unknown>, timestamp = 1700000000): BusEvent {
  return { schema_version: 1, id, type, timestamp, payload };
}

describe("useActivityStore / handleBusEvent", () => {
  it("runs the full tool lifecycle and updates the trace", () => {
    useActivityStore.getState().push; // ensure store initialised
    handleBusEvent(makeEvent("e1", "tool_requested", { tool: "systeminfo", action: "summary", arguments: {} }));
    handleBusEvent(makeEvent("e2", "tool_progress", { tool: "systeminfo", action: "summary", message: "Collecting…" }));
    handleBusEvent(makeEvent("e3", "tool_completed", { tool: "systeminfo", action: "summary", status: "success", output: { hostname: "PC" } }));

    const state = useActivityStore.getState();
    const trace = state.toolTraces["systeminfo/summary"];
    expect(trace).toBeDefined();
    expect(trace.status).toBe("SUCCESS");
    expect(trace.output).toEqual({ hostname: "PC" });
    // feed entries exist and are deduped by id
    const titles = state.items.map((i) => i.title);
    expect(titles.some((t) => t.includes("systeminfo/summary"))).toBe(true);
  });

  it("marks failed tools with an error trace", () => {
    handleBusEvent(makeEvent("e1", "tool_requested", { tool: "cmd", action: "cd", arguments: {} }));
    handleBusEvent(
      makeEvent("e2", "tool_failed", { tool: "cmd", action: "cd", status: "error", error: "Access denied" }),
    );
    const trace = useActivityStore.getState().toolTraces["cmd/cd"];
    expect(trace.status).toBe("FAILED");
    expect(trace.error).toContain("Access denied");
    expect(useAgentStore.getState().orbPhase).toBe("error");
  });

  it("agent_started marks the agent online and stores ports", () => {
    handleBusEvent(makeEvent("e1", "agent_started", { ws_port: 9000, http_port: 9001 }));
    const agent = useAgentStore.getState();
    expect(agent.agentOnline).toBe(true);
    expect(agent.wsPort).toBe(9000);
    expect(agent.httpPort).toBe(9001);
    expect(useActivityStore.getState().items.some((i) => i.title === "MAX agent started")).toBe(true);
  });

  it("agent_shutdown marks the agent offline", () => {
    handleBusEvent(makeEvent("e1", "agent_started", { ws_port: 9000, http_port: 9001 }));
    handleBusEvent(makeEvent("e2", "agent_shutdown", {}));
    expect(useAgentStore.getState().agentOnline).toBe(false);
  });

  it("task events drive the orb phase", () => {
    handleBusEvent(makeEvent("e1", "task_started", { task_id: "t1", goal: "Launch Notepad" }));
    expect(useAgentStore.getState().orbPhase).toBe("executing");
    handleBusEvent(makeEvent("e2", "task_completed", { task_id: "t1", goal: "Launch Notepad", duration_ms: 120 }));
    expect(useAgentStore.getState().orbPhase).toBe("standby");
  });

  it("silent keys suppress feed noise but still update traces", () => {
    useActivityStore.getState().markSilent("systeminfo", "summary");
    handleBusEvent(makeEvent("e1", "tool_requested", { tool: "systeminfo", action: "summary", arguments: {} }));
    handleBusEvent(makeEvent("e2", "tool_completed", { tool: "systeminfo", action: "summary", status: "success", output: {} }));
    const items = useActivityStore.getState().items;
    expect(items.some((i) => i.title.includes("systeminfo/summary"))).toBe(false);
    expect(useActivityStore.getState().toolTraces["systeminfo/summary"]).toBeDefined();
  });

  it("dedupes events by id", () => {
    handleBusEvent(makeEvent("dup1", "client_connected", { device_id: "d", device_name: "Phone" }));
    handleBusEvent(makeEvent("dup1", "client_connected", { device_id: "d", device_name: "Phone" }));
    expect(useActivityStore.getState().items.filter((i) => i.id === "dup1")).toHaveLength(1);
  });
});