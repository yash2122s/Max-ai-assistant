"use client";

import React, { useEffect, useState } from "react";
import {
  MessageSquare,
  Mic,
  Wrench,
  BarChart2
} from "lucide-react";
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  CartesianGrid,
  Cell
} from "recharts";
import { Header } from "@/components/Header";
import { LoadingSpinner } from "@/components/LoadingSpinner";
import { getOverview, OverviewMetrics } from "@/lib/api";
import { formatNumber } from "@/lib/utils";

export default function FeatureAnalyticsPage() {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState<OverviewMetrics | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const loadData = async () => {
    try {
      const res = await getOverview();
      setData(res);
    } catch (err) {
      setData(null);
      console.error("Failed to load feature analytics", err);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  if (loading) {
    return (
      <>
        <Header title="Feature Analytics" subtitle="Subsystem engagement and tool execution distribution" />
        <div className="flex-1 flex items-center justify-center">
          <LoadingSpinner label="Calculating subsystem metrics..." size="lg" />
        </div>
      </>
    );
  }

  if (!data) {
    return (
      <>
        <Header title="Feature Analytics" onRefresh={() => { setRefreshing(true); loadData(); }} isRefreshing={refreshing} />
        <p role="alert" className="p-8 text-sm text-slate-300">Telemetry could not be loaded. Use Refresh Data to retry.</p>
      </>
    );
  }

  const featureBreakdown = [
    {
      name: "Chat / Gemini",
      category: "AI",
      icon: MessageSquare,
      total: data?.totalAiRequests || 0,
      success: data?.successfulAiRequests || 0,
      failed: data?.failedAiRequests || 0,
      color: "#00f0ff"
    },
    {
      name: "Voice Assistant",
      category: "Voice",
      icon: Mic,
      total: data?.totalVoiceSessions || 0,
      success: null,
      failed: null,
      color: "#3b82f6"
    },
    {
      name: "Tool Execution",
      category: "Automation",
      icon: Wrench,
      total: data?.totalToolExecutions || 0,
      success: Math.max((data?.totalToolExecutions || 0) - (data?.failedToolExecutions || 0), 0),
      failed: data?.failedToolExecutions || 0,
      color: "#a855f7"
    },
  ];

  return (
    <>
      <Header
        title="Feature Analytics"
        subtitle="Subsystem engagement and tool execution distribution"
        onRefresh={() => {
          setRefreshing(true);
          loadData();
        }}
        isRefreshing={refreshing}
      />

      <main className="p-8 space-y-8">
        {/* Feature Cards Grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {featureBreakdown.map((f) => {
            const Icon = f.icon;
            const rate = f.total > 0 && f.success !== null ? (f.success * 100 / f.total).toFixed(1) : null;
            return (
              <div key={f.name} className="glass-panel p-6 rounded-2xl border border-jarvis-border space-y-4">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div
                      className="p-2.5 rounded-xl bg-slate-900 border border-slate-800"
                      style={{ color: f.color }}
                    >
                      <Icon className="w-5 h-5" />
                    </div>
                    <div>
                      <h3 className="text-sm font-semibold text-slate-100">{f.name}</h3>
                      <span className="text-[10px] font-mono text-slate-400 uppercase">{f.category}</span>
                    </div>
                  </div>
                  <span className="text-xs font-mono font-bold text-emerald-400">{rate === null ? "—" : `${rate}% OK`}</span>
                </div>

                <div className="space-y-2 pt-2 border-t border-jarvis-border">
                  <div className="flex justify-between text-xs font-mono">
                    <span className="text-slate-400">Total Invocations:</span>
                    <span className="text-slate-100 font-bold">{formatNumber(f.total)}</span>
                  </div>
                  <div className="flex justify-between text-xs font-mono">
                    <span className="text-slate-400">Successful:</span>
                    <span className="text-emerald-400 font-medium">{f.success === null ? "—" : formatNumber(f.success)}</span>
                  </div>
                  <div className="flex justify-between text-xs font-mono">
                    <span className="text-slate-400">Failures:</span>
                    <span className="text-rose-400 font-medium">{f.failed === null ? "—" : formatNumber(f.failed)}</span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {/* Feature Volume Distribution Chart */}
        <div className="glass-panel p-6 rounded-2xl border border-jarvis-border space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-sm font-semibold text-slate-100 flex items-center gap-2">
                <BarChart2 className="w-4 h-4 text-cyan-400" />
                Subsystem Engagement Ranking
              </h2>
              <p className="text-[11px] font-mono text-slate-400">Comparative usage volume by architectural component</p>
            </div>
          </div>

          <div className="h-72 w-full pt-4">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={featureBreakdown}>
                <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" vertical={false} />
                <XAxis dataKey="name" stroke="#64748b" fontSize={11} tickLine={false} />
                <YAxis stroke="#64748b" fontSize={10} tickLine={false} axisLine={false} />
                <Tooltip
                  contentStyle={{
                    backgroundColor: "#0f172a",
                    borderColor: "#334155",
                    borderRadius: "0.5rem",
                    fontSize: "11px",
                    fontFamily: "monospace",
                  }}
                />
                <Bar dataKey="total" name="Total Invocations" radius={[4, 4, 0, 0]}>
                  {featureBreakdown.map((entry, index) => (
                    <Cell key={`cell-${index}`} fill={entry.color} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div>
      </main>
    </>
  );
}
