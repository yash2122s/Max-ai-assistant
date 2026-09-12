import type { BusEvent } from "./types";
import {
  RECONNECT_BASE_DELAY_MS,
  RECONNECT_MAX_DELAY_MS,
  SSE_KEEPALIVE_STALL_MS,
} from "./constants";

export type StreamStatus = "connecting" | "online" | "reconnecting" | "offline";

export interface ParsedSseEvent {
  id?: string;
  event?: string;
  data?: string;
}

export type EventListener = (event: BusEvent) => void;
export type StatusListener = (status: StreamStatus, retryCount: number) => void;
export type SetTimeoutFn = (callback: () => void, delay: number) => unknown;
export type ClearTimeoutFn = (handle: unknown) => void;

export interface SseClientOptions {
  url: string;
  onEvent: EventListener;
  onStatus?: StatusListener;
  fetchImpl?: typeof fetch;
  getLastEventId?: () => string | null;
  now?: () => number;
  setTimeoutImpl?: SetTimeoutFn;
  clearTimeoutImpl?: ClearTimeoutFn;
}

// Pure SSE frame parser. Splits an incoming chunk into complete frames and
// returns leftovers to be prepended to the next chunk.
export function parseSseFrames(
  buffer: string,
  chunk: string,
  onFrame: (frame: ParsedSseEvent) => void,
): string {
  const combined = buffer + chunk.replace(/\r\n/g, "\n");
  const parts = combined.split("\n\n");
  const leftover = parts.pop() ?? "";
  for (const part of parts) {
    if (part.trim().length === 0) continue;
    let id: string | undefined;
    let eventName: string | undefined;
    const dataLines: string[] = [];
    for (const line of part.split("\n")) {
      if (line.startsWith(":")) continue; // comment / keepalive
      if (line.startsWith("id:")) {
        id = line.slice(3).trim();
      } else if (line.startsWith("event:")) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).replace(/^ /, ""));
      }
    }
    if (eventName || dataLines.length > 0) {
      onFrame({ id, event: eventName, data: dataLines.join("\n") });
    }
  }
  return leftover;
}

interface ActiveConnection {
  status: StreamStatus;
  onEvent: EventListener;
  onStatus?: StatusListener;
  url: string;
  lastEventId: string | null;
  startedAt: number;
}

/**
 * Singleton-managed SSE client. Connects to /api/events, replays missed
 * events via ?last_event_id=, reconnects with exponential backoff and emits
 * connection status transitions. A single instance guards against duplicate
 * connections; stop() tears down timers and listeners so nothing leaks.
 */
export class SseClient {
  private url: string;
  private onEvent: EventListener;
  private onStatus?: StatusListener;
  private fetchImpl: typeof fetch;
  private getLastEventId: () => string | null;
  private now: () => number;
  private setTimeoutImpl: SetTimeoutFn;
  private clearTimeoutImpl: ClearTimeoutFn;

  private running = false;
  private aborted = false;
  private retryDelay = RECONNECT_BASE_DELAY_MS;
  private retryCount = 0;
  private retryTimer: unknown = null;
  private stallTimer: unknown = null;
  private controller: AbortController | null = null;
  private lastActivityAt = 0;
  private lastEventId: string | null = null;

  constructor(opts: SseClientOptions) {
    this.url = opts.url;
    this.onEvent = opts.onEvent;
    this.onStatus = opts.onStatus;
    this.fetchImpl = opts.fetchImpl ?? window.fetch.bind(window);
    this.getLastEventId = opts.getLastEventId ?? (() => null);
    this.now = opts.now ?? (() => Date.now());
    this.setTimeoutImpl = opts.setTimeoutImpl ?? ((cb, delay) => window.setTimeout(cb, delay));
    this.clearTimeoutImpl = opts.clearTimeoutImpl ?? ((h) => window.clearTimeout(h as number));
  }

  get isRunning(): boolean {
    return this.running;
  }

  private setStatus(status: StreamStatus) {
    this.onStatus?.(status, this.retryCount);
  }

  start(): boolean {
    if (this.running) return false; // no duplicate connections
    this.running = true;
    this.aborted = false;
    this.lastEventId = this.getLastEventId();
    this.setStatus("connecting");
    void this.open();
    return true;
  }

  private buildUrl(): string {
    const base = this.url.split("?")[0];
    if (this.lastEventId) {
      return `${base}?last_event_id=${encodeURIComponent(this.lastEventId)}`;
    }
    return base;
  }

  private async open(): Promise<void> {
    if (!this.running || this.aborted) return;
    const controller = new AbortController();
    this.controller = controller;
    try {
      const res = await this.fetchImpl(this.buildUrl(), {
        method: "GET",
        headers: { Accept: "text/event-stream" },
        signal: controller.signal,
      });
      if (!this.running || this.aborted) return;
      if (!res.ok || !res.body) {
        throw new Error(`SSE HTTP ${res.status}`);
      }
      this.retryDelay = RECONNECT_BASE_DELAY_MS;
      this.retryCount = 0;
      this.lastActivityAt = this.now();
      this.setStatus("online");
      this.scheduleStallWatch();

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        this.lastActivityAt = this.now();
        const text = decoder.decode(value, { stream: true });
        buffer = parseSseFrames(buffer, text, (frame) => {
          this.handleFrame(frame);
        });
      }
      if (this.running) throw new Error("stream ended");
    } catch (err) {
      if (this.aborted) return;
      if (this.running) {
        this.scheduleReconnect();
      }
    } finally {
      this.clearStallWatch();
      if (this.controller === controller) this.controller = null;
    }
  }

  private handleFrame(frame: ParsedSseEvent) {
    if (frame.id) this.lastEventId = frame.id;
    if (!frame.event || !frame.data) return; // comments/keepalive only
    let parsed: unknown;
    try {
      parsed = JSON.parse(frame.data);
    } catch {
      return; // malformed frame — ignore, do not crash the stream
    }
    const event = parsed as BusEvent;
    if (typeof event !== "object" || event === null || typeof event.id !== "string") {
      return;
    }
    event.type = frame.event;
    if (!Number.isFinite(event.timestamp)) {
      event.timestamp = this.now() / 1000;
    }
    this.onEvent(event);
  }

  private scheduleStallWatch() {
    this.clearStallWatch();
    this.stallTimer = this.setTimeoutImpl(() => {
      const idle = this.now() - this.lastActivityAt;
      if (idle > SSE_KEEPALIVE_STALL_MS) {
        if (this.running && !this.aborted) {
          // Force a reconnect: server may have gone silent.
          this.controller?.abort();
          this.scheduleReconnect();
        }
      } else {
        this.scheduleStallWatch();
      }
    }, SSE_KEEPALIVE_STALL_MS);
  }

  private clearStallWatch() {
    if (this.stallTimer !== null) {
      this.clearTimeoutImpl(this.stallTimer);
      this.stallTimer = null;
    }
  }

  private scheduleReconnect() {
    if (!this.running || this.aborted) return;
    this.setStatus("reconnecting");
    const delay = Math.min(this.retryDelay, RECONNECT_MAX_DELAY_MS);
    this.retryCount += 1;
    this.retryDelay = Math.min(this.retryDelay * 2, RECONNECT_MAX_DELAY_MS);
    if (this.retryTimer !== null) this.clearTimeoutImpl(this.retryTimer);
    this.retryTimer = this.setTimeoutImpl(() => {
      this.retryTimer = null;
      if (this.running && !this.aborted) void this.open();
    }, delay);
  }

  stop() {
    this.aborted = true;
    this.running = false;
    if (this.retryTimer !== null) {
      this.clearTimeoutImpl(this.retryTimer);
      this.retryTimer = null;
    }
    this.clearStallWatch();
    this.controller?.abort();
    this.controller = null;
    this.setStatus("offline");
  }
}

let activeClient: SseClient | null = null;

/** Module-level singleton — ensures a single SSE connection across the app. */
export function getSseClient(opts?: SseClientOptions): SseClient {
  if (!activeClient && opts) {
    activeClient = new SseClient(opts);
  }
  return activeClient as SseClient;
}

export function getOrCreateSseClient(opts: SseClientOptions): SseClient {
  if (!activeClient) activeClient = new SseClient(opts);
  return activeClient;
}

export function resetSseClientForTests() {
  if (activeClient) {
    activeClient.stop();
    activeClient = null;
  }
}

export type { ActiveConnection };