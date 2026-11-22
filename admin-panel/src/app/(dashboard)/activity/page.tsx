"use client";

import React, { useEffect, useState } from "react";
import {
  Activity as ActivityIcon,
  Play,
  Pause,
  Info,
  Clock,
  Filter,
  CheckCircle2,
  XCircle,
  HelpCircle
} from "lucide-react";
import { Header } from "@/components/Header";
import { StatusBadge } from "@/components/StatusBadge";
import { LoadingSpinner } from "@/components/LoadingSpinner";
import { getLiveActivity, ActivityEventItem } from "@/lib/api";
import { formatTimestamp, formatDuration, truncateUid } from "@/lib/utils";

export default function LiveActivityPage() {
  const [loading, setLoading] = useState(true);
  const [events, setEvents] = useState<ActivityEventItem[]>([]);
  const [isPaused, setIsPaused] = useState(false);
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [refreshing, setRefreshing] = useState(false);

  const fetchEvents = async () => {
    try {
      const data = await getLiveActivity(50);
      setEvents(data);
    } catch (err) {
      console.error("Failed to load live events", err);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    fetchEvents();

    const interval = setInterval(() => {
      if (!isPaused) {
        fetchEvents();
      }
    }, 6000);

    return () => clearInterval(interval);
  }, [isPaused]);

  const filteredEvents = events.filter((e) => {
    if (statusFilter === "ALL") return true;
    return e.status.toUpperCase() === statusFilter.toUpperCase();
  });

  return (
    <>
      <Header
        title="Live Activity Stream"
        subtitle="Near-real-time event ingestion and operational telemetry feed"
        onRefresh={() => {
          setRefreshing(true);
          fetchEvents();
        }}
        isRefreshing={refreshing}
      />

      <main className="p-8 space-y-6">
        {/* Presence Disclaimer Banner */}
        <div className="p-4 rounded-xl bg-cyan-950/40 border border-cyan-500/30 flex items-start gap-3 text-cyan-300 text-xs">
          <Info className="w-4 h-4 flex-shrink-0 mt-0.5 text-cyan-400" />
          <div className="leading-relaxed">
            <span className="font-semibold text-cyan-200">Telemetry Observation Notice: </span>
            Online and active statuses are approximate and derived from mobile heartbeat telemetry received within the documented 5-minute window. They do not constitute guaranteed continuous presence.
          </div>
        </div>

        {/* Stream Controls */}
        <div className="flex flex-col sm:flex-row gap-4 items-center justify-between">
          <div className="flex items-center gap-3">
            <button
              onClick={() => setIsPaused(!isPaused)}
              className={`flex items-center gap-2 px-3.5 py-2 rounded-xl text-xs font-mono font-semibold transition-all border ${
                isPaused
                  ? "bg-amber-950/60 border-amber-500/40 text-amber-300 hover:bg-amber-900/60"
                  : "bg-emerald-950/60 border-emerald-500/40 text-emerald-300 hover:bg-emerald-900/60"
              }`}
            >
              {isPaused ? <Play className="w-3.5 h-3.5" /> : <Pause className="w-3.5 h-3.5" />}
              <span>{isPaused ? "RESUME STREAM" : "STREAMING (LIVE)"}</span>
            </button>
            <span className="text-[11px] font-mono text-slate-500">
              {isPaused ? "Polling halted" : "Refreshes every 6s"}
            </span>
          </div>

          <div className="flex items-center gap-2">
            <Filter className="w-3.5 h-3.5 text-cyan-400" />
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="bg-slate-900 border border-slate-800 rounded-xl py-2 px-3 text-xs text-slate-200 font-mono focus:outline-none focus:border-cyan-500"
            >
              <option value="ALL">All Outcomes</option>
              <option value="SUCCESS">Success Only</option>
              <option value="FAILURE">Failures Only</option>
              <option value="IN_PROGRESS">In Progress</option>
            </select>
          </div>
        </div>

        {/* Activity Table */}
        <div className="glass-panel rounded-2xl border border-jarvis-border overflow-hidden">
          {loading ? (
            <LoadingSpinner label="Connecting to live event pipeline..." />
          ) : filteredEvents.length === 0 ? (
            <div className="p-12 text-center text-slate-500 font-mono text-xs">
              No recent activity events recorded in this stream.
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="bg-slate-900/80 text-[10px] font-mono uppercase text-slate-400 border-b border-jarvis-border">
                  <tr>
                    <th className="py-3 px-4">Timestamp</th>
                    <th className="py-3 px-4">User UID</th>
                    <th className="py-3 px-4">Event Type</th>
                    <th className="py-3 px-4">Feature</th>
                    <th className="py-3 px-4">Status</th>
                    <th className="py-3 px-4 text-right">Duration</th>
                    <th className="py-3 px-4">Error Category</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-jarvis-border font-mono text-[11px] text-slate-300">
                  {filteredEvents.map((evt) => (
                    <tr key={evt.eventId} className="hover:bg-slate-900/50 transition-colors">
                      <td className="py-3 px-4 text-slate-400">{formatTimestamp(evt.serverTimestamp)}</td>
                      <td className="py-3 px-4 text-slate-200">{truncateUid(evt.userId)}</td>
                      <td className="py-3 px-4 font-semibold text-slate-100">{evt.eventType}</td>
                      <td className="py-3 px-4 text-cyan-400">{evt.feature}</td>
                      <td className="py-3 px-4">
                        <StatusBadge status={evt.status} type="status" />
                      </td>
                      <td className="py-3 px-4 text-right">{formatDuration(evt.durationMs)}</td>
                      <td className="py-3 px-4">
                        {evt.errorCategory ? (
                          <span className="text-rose-400 font-mono text-[10px] px-1.5 py-0.5 rounded bg-rose-950/40 border border-rose-500/20">
                            {evt.errorCategory}
                          </span>
                        ) : (
                          <span className="text-slate-600">—</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </>
  );
}
