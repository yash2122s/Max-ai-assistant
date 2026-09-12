import { describe, it, expect, vi } from "vitest";
import {
  parseSseFrames,
  SseClient,
  type ParsedSseEvent,
} from "@/lib/sse";
import type { BusEvent } from "@/lib/types";

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function makeResponse(body: ReadableStream<Uint8Array>, ok = true, status = 200) {
  return new Response(body, {
    status,
    statusText: ok ? "OK" : "Error",
  });
}

function streamFrom(chunks: string[], opts?: { close?: boolean }): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let i = 0;
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (; i < chunks.length; i++) {
        controller.enqueue(encoder.encode(chunks[i]));
      }
      if (opts?.close) controller.close();
    },
  });
}

function manualTimers() {
  let now = 0;
  let nextId = 1;
  const timers = new Map<number, { cb: () => void; at: number }>();
  return {
    now: () => now,
    setTimeoutImpl: (cb: () => void, delay: number) => {
      const id = nextId++;
      timers.set(id, { cb, at: now + delay });
      return id;
    },
    clearTimeoutImpl: (id: unknown) => {
      timers.delete(id as number);
    },
    advance: async (ms: number) => {
      now += ms;
      const due = [...timers.entries()]
        .filter(([, t]) => t.at <= now)
        .sort((a, b) => a[1].at - b[1].at);
      for (const [id, t] of due) {
        if (timers.has(id)) {
          timers.delete(id);
          t.cb();
        }
        await flushPromises();
      }
    },
    pendingCount: () => timers.size,
  };
}

const validBusEvent: BusEvent = {
  schema_version: 1,
  id: "evt_abc",
  type: "chat_message",
  timestamp: 1700000000,
  payload: { role: "max", text: "hi", timestamp: 1700000000 },
};

describe("parseSseFrames", () => {
  it("parses id/event/data into frames", () => {
    const frames: ParsedSseEvent[] = [];
    let leftover = parseSseFrames(
      "",
      "id: evt_1\nevent: chat_message\ndata: {\"a\":1}\n\nid: evt_2\nevent: heartbeat\ndata: {\"b\":2}\n\n",
      (f) => frames.push(f),
    );
    leftover = parseSseFrames(leftover, "", (f) => frames.push(f));
    expect(frames).toHaveLength(2);
    expect(frames[0]).toEqual({ id: "evt_1", event: "chat_message", data: '{"a":1}' });
    expect(frames[1]).toEqual({ id: "evt_2", event: "heartbeat", data: '{"b":2}' });
  });

  it("handles frames split across chunks", () => {
    const frames: ParsedSseEvent[] = [];
    const leftover = parseSseFrames("", "id: evt_1\nevent: chat_message\ndata: {\"a\"", (f) => frames.push(f));
    expect(frames).toHaveLength(0);
    parseSseFrames(leftover, ":1}\n\n", (f) => frames.push(f));
    expect(frames).toHaveLength(1);
    expect(frames[0].data).toBe('{"a":1}');
  });

  it("ignores keepalive comments", () => {
    const frames: ParsedSseEvent[] = [];
    parseSseFrames("", ": keepalive\n\n", (f) => frames.push(f));
    expect(frames).toHaveLength(0);
  });

  it("joins multiline data with newlines", () => {
    const frames: ParsedSseEvent[] = [];
    parseSseFrames("", "event: x\ndata: line1\ndata: line2\n\n", (f) => frames.push(f));
    expect(frames[0].data).toBe("line1\nline2");
  });
});

describe("SseClient", () => {
  it("connects, streams events and reports online", async () => {
    const onEvent = vi.fn();
    const onStatus = vi.fn();
    const client = new SseClient({
      url: "/api/events",
      onEvent,
      onStatus,
      fetchImpl: async () =>
        makeResponse(
          streamFrom([
            "id: evt_1\nevent: agent_started\ndata: {\"id\":\"evt_1\",\"type\":\"agent_started\",\"timestamp\":1700000000,\"payload\":{\"ws_port\":9000,\"http_port\":9001}}\n\n",
          ]),
        ),
    });

    const started = client.start();
    expect(started).toBe(true);
    expect(client.isRunning).toBe(true);
    await flushPromises();

    expect(onStatus).toHaveBeenCalledWith("connecting", 0);
    expect(onStatus).toHaveBeenCalledWith("online", 0);
    expect(onEvent).toHaveBeenCalledTimes(1);
    const evt = onEvent.mock.calls[0][0] as BusEvent;
    expect(evt.id).toBe("evt_1");
    expect(evt.type).toBe("agent_started");
    expect(evt.payload.ws_port).toBe(9000);
    client.stop();
  });

  it("guards against duplicate connections", async () => {
    let openCount = 0;
    const fetchImpl = async () => {
      openCount += 1;
      return makeResponse(streamFrom(["event: x\ndata: {\"id\":\"a\",\"timestamp\":1,\"payload\":{}}\n\n"]));
    };
    const client = new SseClient({ url: "/api/events", onEvent: vi.fn(), fetchImpl });
    client.start();
    const second = client.start();
    await flushPromises();
    expect(second).toBe(false);
    expect(openCount).toBe(1);
    client.stop();
  });

  it("reconnects with backoff after a network error", async () => {
    const timers = manualTimers();
    const onStatus = vi.fn();
    const events: BusEvent[] = [];
    let failFirst = true;
    const fetchImpl = async () => {
      if (failFirst) {
        failFirst = false;
        throw new Error("ECONNREFUSED");
      }
      return makeResponse(
        streamFrom(["event: heartbeat\ndata: {\"id\":\"evt_2\",\"timestamp\":1700000000,\"payload\":{\"device_id\":\"d1\"}}\n\n"]),
      );
    };

    const client = new SseClient({
      url: "/api/events",
      onEvent: (e) => events.push(e),
      onStatus,
      fetchImpl,
      now: timers.now,
      setTimeoutImpl: timers.setTimeoutImpl,
      clearTimeoutImpl: timers.clearTimeoutImpl,
    });
    client.start();
    await flushPromises();

    expect(onStatus).toHaveBeenCalledWith("reconnecting", 0);
    expect(timers.pendingCount()).toBe(1);

    await timers.advance(1000);
    expect(onStatus).toHaveBeenCalledWith("online", 0);
    expect(events).toHaveLength(1);
    expect(events[0].id).toBe("evt_2");
    client.stop();
    expect(timers.pendingCount()).toBe(0);
  });

  it("reconnects when the stream ends and includes last_event_id", async () => {
    const timers = manualTimers();
    const events: BusEvent[] = [];
    let urlSeen = "";
    const fetchImpl = async (input: RequestInfo | URL) => {
      urlSeen = String(input);
      if (urlSeen.includes("last_event_id=evt_1")) {
        return makeResponse(streamFrom([], { close: true })); // ends immediately
      }
      return makeResponse(
        streamFrom(
          [
            "id: evt_1\nevent: chat_message\ndata: {\"id\":\"evt_1\",\"type\":\"chat_message\",\"timestamp\":1700000000,\"payload\":{\"role\":\"user\",\"text\":\"x\"}}\n\n",
          ],
          { close: true },
        ),
      );
    };
    const client = new SseClient({
      url: "/api/events",
      onEvent: (e) => events.push(e),
      fetchImpl,
      now: timers.now,
      setTimeoutImpl: timers.setTimeoutImpl,
      clearTimeoutImpl: timers.clearTimeoutImpl,
    });
    client.start();
    await flushPromises();
    expect(events).toHaveLength(1);

    await timers.advance(1000);
    // Second connection should have replayed from evt_1
    expect(urlSeen).toContain("last_event_id=evt_1");
    client.stop();
  });

  it("ignores malformed event frames without crashing", async () => {
    const onEvent = vi.fn();
    const client = new SseClient({
      url: "/api/events",
      onEvent,
      fetchImpl: async () =>
        makeResponse(
          streamFrom([
            "event: chat_message\ndata: not-json\n\n",
            "event: chat_message\ndata: {\"id\":\"evt_ok\",\"type\":\"chat_message\",\"timestamp\":1700000000,\"payload\":{\"role\":\"user\",\"text\":\"hi\"}}\n\n",
          ]),
        ),
    });
    client.start();
    await flushPromises();
    expect(onEvent).toHaveBeenCalledTimes(1);
    const evt = onEvent.mock.calls[0][0] as BusEvent;
    expect(evt.id).toBe("evt_ok");
    client.stop();
  });

  it("stop() tears everything down", async () => {
    let cancelled = false;
    const onStatus = vi.fn();
    const fetchImpl = async (_url: RequestInfo | URL, init?: RequestInit) => {
      init?.signal?.addEventListener("abort", () => {
        cancelled = true;
      });
      return makeResponse(new ReadableStream<Uint8Array>({ start() {} }));
    };
    const client = new SseClient({
      url: "/api/events",
      onEvent: vi.fn(),
      onStatus,
      fetchImpl,
    });
    client.start();
    await flushPromises();
    client.stop();
    await flushPromises();
    expect(client.isRunning).toBe(false);
    expect(onStatus).toHaveBeenCalledWith("offline", 0);
    expect(cancelled).toBe(true);
    void validBusEvent;
  });
});