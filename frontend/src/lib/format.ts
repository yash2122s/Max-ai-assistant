export function formatUptime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return "—";
  const s = Math.floor(seconds);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m ${s % 60}s`;
}

export function formatClock(ts: number): string {
  if (!Number.isFinite(ts)) return "—";
  return new Date(ts * 1000).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export function formatRelative(ts: number, now = Date.now() / 1000): string {
  if (!Number.isFinite(ts)) return "—";
  const diff = Math.max(0, Math.floor(now - ts));
  if (diff < 5) return "just now";
  if (diff < 60) return `${diff}s ago`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  return `${Math.floor(diff / 86400)}d ago`;
}

export function formatLatency(ms: number): string {
  if (!Number.isFinite(ms)) return "—";
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${Math.round(ms)}ms`;
}

export function formatBytes(gb: number): string {
  if (!Number.isFinite(gb)) return "—";
  return `${gb.toFixed(1)} GB`;
}

export function formatPercent(value: number): string {
  if (!Number.isFinite(value)) return "—";
  return `${Math.round(value)}%`;
}

export function truncate(text: string, max = 140): string {
  if (!text) return "";
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

export function formatArgs(args: Record<string, unknown> | undefined): string {
  if (!args) return "";
  const safe: Record<string, string> = {};
  for (const [k, v] of Object.entries(args)) {
    if (v == null || v === "") continue;
    if (typeof v === "string") {
      safe[k] = v.length > 60 ? `${v.slice(0, 60)}…` : v;
    } else if (typeof v === "object") {
      safe[k] = "[object]";
    } else {
      safe[k] = String(v);
    }
  }
  const entries = Object.entries(safe)
    .map(([k, v]) => `${k}=${v}`)
    .join("  ");
  return entries ? ` ${entries}` : "";
}

export function formatToolOutput(output: unknown): string {
  if (output == null) return "";
  if (typeof output === "string") return truncate(output, 600);
  if (typeof output === "number" || typeof output === "boolean") return String(output);
  try {
    return truncate(JSON.stringify(output, null, 0), 600);
  } catch {
    return "[unprintable]";
  }
}

export function maskSecretsLikeBackend(input: unknown): unknown {
  if (typeof input !== "string") return input;
  return input
    .replace(/(api[_-]?key["']?\s*[:=]\s*["']?)([^"'\s]+)/gi, "$1***")
    .replace(/(?<![\w.-])(sk-[A-Za-z0-9_-]{8,})(?![\w])/g, "sk-***")
    .replace(/(token["']?\s*[:=]\s*["']?)([A-Za-z0-9._-]{12,})/gi, "$1***")
    .replace(/(bearer\s+)[A-Za-z0-9._-]+/gi, "$1***");
}

export function extractErrorMessage(error: unknown): string {
  if (error == null) return "Unknown error";
  if (typeof error === "string") return error;
  if (typeof error === "object") {
    const e = error as Record<string, unknown>;
    if (typeof e.message === "string") return e.message;
    if (typeof e.code === "string") return `${e.code}: unknown error`;
    try {
      return JSON.stringify(error);
    } catch {
      return "Unknown error";
    }
  }
  return String(error);
}