import { create } from "zustand";
import type { MetricsSnapshot, PowerStatus, SystemInfoSummary } from "@/lib/types";

interface SystemState {
  metrics: MetricsSnapshot | null;
  hostname: string | null;
  osInfo: string | null;
  summary: SystemInfoSummary | null;
  power: PowerStatus | null;
  summaryError: string | null;
  powerError: string | null;
  lastMetricsAt: number | null;
  lastSummaryAt: number | null;
  lastPowerAt: number | null;

  applyMetrics: (metrics: MetricsSnapshot) => void;
  applySummary: (summary: SystemInfoSummary | null) => void;
  applyPower: (power: PowerStatus | null) => void;
  setSummaryError: (message: string | null) => void;
  setPowerError: (message: string | null) => void;
  clear: () => void;
}

export const useSystemStore = create<SystemState>((set) => ({
  metrics: null,
  hostname: null,
  osInfo: null,
  summary: null,
  power: null,
  summaryError: null,
  powerError: null,
  lastMetricsAt: null,
  lastSummaryAt: null,
  lastPowerAt: null,

  applyMetrics: (metrics) =>
    set({
      metrics,
      hostname: metrics.hostname ?? null,
      osInfo: metrics.os_info ?? null,
      lastMetricsAt: Date.now(),
    }),

  applySummary: (summary) =>
    set({
      summary,
      summaryError: null,
      lastSummaryAt: Date.now(),
    }),

  applyPower: (power) =>
    set({
      power,
      powerError: null,
      lastPowerAt: Date.now(),
    }),

  setSummaryError: (message) => set({ summaryError: message }),
  setPowerError: (message) => set({ powerError: message }),
  clear: () =>
    set({
      metrics: null,
      hostname: null,
      osInfo: null,
      summary: null,
      power: null,
      summaryError: null,
      powerError: null,
      lastMetricsAt: null,
      lastSummaryAt: null,
      lastPowerAt: null,
    }),
}));