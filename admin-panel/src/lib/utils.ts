import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatNumber(num: number | undefined | null): string {
  if (num === undefined || num === null) return "0";
  return new Intl.NumberFormat().format(num);
}

export function formatDuration(ms: number | undefined | null): string {
  if (ms === undefined || ms === null) return "—";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  const minutes = Math.floor(ms / 60000);
  const seconds = Math.floor((ms % 60000) / 1000);
  return `${minutes}m ${seconds}s`;
}

export function formatTimestamp(ts: number | string | Date | undefined | null): string {
  if (!ts) return "—";
  const date = typeof ts === "number" || typeof ts === "string" ? new Date(ts) : ts;
  if (isNaN(date.getTime())) return "—";
  return date.toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });
}

export function formatRelativeTime(ts: number | string | Date | undefined | null): string {
  if (!ts) return "Never";
  const date = typeof ts === "number" || typeof ts === "string" ? new Date(ts) : ts;
  if (isNaN(date.getTime())) return "Never";
  const now = Date.now();
  const diffSec = Math.floor((now - date.getTime()) / 1000);

  if (diffSec < 60) return `${diffSec}s ago`;
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}

export function truncateUid(uid: string, length = 12): string {
  if (!uid) return "—";
  if (uid.length <= length) return uid;
  return `${uid.substring(0, 6)}...${uid.substring(uid.length - 4)}`;
}
