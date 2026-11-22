"use client";

import React, { useEffect, useState } from "react";
import {
  Users,
  Radio,
  Zap,
  Wrench,
  Clock,
  CheckCircle2,
  XCircle,
  TrendingUp,
  Activity as ActivityIcon
} from "lucide-react";
import {
  ResponsiveContainer,
  AreaChart,
  Area,
  XAxis,
  YAxis,
  Tooltip,
  CartesianGrid,
  BarChart,
  Bar
} from "recharts";
import { Header } from "@/components/Header";
import { KpiCard } from "@/components/KpiCard";
import { LoadingSpinner } from "@/components/LoadingSpinner";
import { getOverview, OverviewMetrics } from "@/lib/api";
import { formatNumber, formatDuration } from "@/lib/utils";

export default function OverviewPage() {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState<OverviewMetrics | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const loadData = async () => {
    try {
      const res = await getOverview();
      setData(res);
    } catch (err) {
      console.error("Failed to load overview data", err);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const handleRefresh = () => {
    setRefreshing(true);
    loadData();
  };

  if (loading) {
    return (
      <>
        <Header title="Overview & Telemetry" subtitle="Real-time system telemetry and aggregate analytics" />
        <div className="flex-1 flex items-center justify-center">
          <LoadingSpinner label="Collecting aggregate telemetry..." size="lg" />
        </div>
      </>
    );
  }

  const aiTotal = data?.totalAiRequests || 0;
  const aiSuccess = data?.successfulAiRequests || 0;
  const aiSuccessRate = aiTotal > 0 ? ((aiSuccess / aiTotal) * 100).toFixed(1) : "100";

  const toolTotal = data?.totalToolExecutions || 0;
  const toolFailed = data?.failedToolExecutions || 0;
  const toolSuccessRate = toolTotal > 0 ? (((toolTotal - toolFailed) / toolTotal) * 100).toFixed(1) : "100";

  return (
    <>
      <Header
        title="Overview & Telemetry"
        subtitle="Real-time system telemetry and aggregate analytics"
        onRefresh={handleRefresh}
        isRefreshing={refreshing}
      />

      <main className="p-8 space-y-8">
        {/* Top KPI Grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          <KpiCard
            title="Active Sessions"
            value={formatNumber(data?.activeSessionsCount)}
            subtext="Heartbeat < 5m window"
            icon={Radio}
            variant="cyan"
            badge="LIVE"
          />
          <KpiCard
            title="Total Users"
            value={formatNumber(data?.totalUsers)}
            subtext={`DAU: ${data?.activeUsers.dau} | WAU: ${data?.activeUsers.wau}`}
            icon={Users}
            variant="blue"
          />
          <KpiCard
            title="AI Requests"
            value={formatNumber(data?.totalAiRequests)}
            subtext={`${aiSuccessRate}% success rate`}
            icon={Zap}
            variant="emerald"
          />
          <KpiCard
            title="Tool Executions"
            value={formatNumber(data?.totalToolExecutions)}
            subtext={`${toolSuccessRate}% success rate`}
            icon={Wrench}
            variant="purple"
          />
        </div>

        {/* Second Row: Latency & Secondary Metrics */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <KpiCard
            title="Average Latency"
            value={formatDuration(data?.avgLatencyMs || 0)}
            subtext="AI pipeline & tool execution"
            icon={Clock}
            variant="amber"
          />
          <KpiCard
            title="Voice Sessions"
            value={formatNumber(data?.totalVoiceSessions)}
            subtext="Continuous conversation loops"
            icon={ActivityIcon}
            variant="cyan"
          />
          <KpiCard
            title="Failed Operations"
            value={formatNumber((data?.failedAiRequests || 0) + (data?.failedToolExecutions || 0))}
            subtext={`${data?.failedAiRequests || 0} AI, ${data?.failedToolExecutions || 0} Tools`}
            icon={XCircle}
            variant="rose"
          />
        </div>

        {/* Charts Grid */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Main Area Chart: Activity Volume Trends */}
          <div className="glass-panel p-6 rounded-2xl lg:col-span-2 space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-sm font-semibold text-slate-100 flex items-center gap-2">
                  <TrendingUp className="w-4 h-4 text-cyan-400" />
                  Activity & Request Volume
                </h2>
                <p className="text-[11px] font-mono text-slate-400">Daily event volume trends over the past 14 days</p>
              </div>
              <span className="text-[10px] font-mono px-2 py-1 rounded bg-slate-900 border border-slate-800 text-slate-400">
                UTC Days
              </span>
            </div>

            <div className="h-72 w-full pt-4">
              {(!data?.dailyTrends || data.dailyTrends.length === 0) ? (
                <div className="h-full flex items-center justify-center text-xs font-mono text-slate-500">
                  No historical daily trends available yet
                </div>
              ) : (
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={data.dailyTrends}>
                    <defs>
                      <linearGradient id="cyanGrad" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#00f0ff" stopOpacity={0.4} />
                        <stop offset="95%" stopColor="#00f0ff" stopOpacity={0.0} />
                      </linearGradient>
                      <linearGradient id="purpleGrad" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#a855f7" stopOpacity={0.4} />
                        <stop offset="95%" stopColor="#a855f7" stopOpacity={0.0} />
                      </linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" vertical={false} />
                    <XAxis dataKey="date" stroke="#64748b" fontSize={10} tickLine={false} />
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
                    <Area
                      type="monotone"
                      dataKey="totalAiRequests"
                      name="AI Requests"
                      stroke="#00f0ff"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#cyanGrad)"
                    />
                    <Area
                      type="monotone"
                      dataKey="totalToolExecutions"
                      name="Tool Executions"
                      stroke="#a855f7"
                      strokeWidth={2}
                      fillOpacity={1}
                      fill="url(#purpleGrad)"
                    />
                  </AreaChart>
                </ResponsiveContainer>
              )}
            </div>
          </div>

          {/* Operational Health & Ratios */}
          <div className="glass-panel p-6 rounded-2xl flex flex-col justify-between space-y-6">
            <div>
              <h2 className="text-sm font-semibold text-slate-100 flex items-center gap-2">
                <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                Operational Reliability
              </h2>
              <p className="text-[11px] font-mono text-slate-400">AI and tool dispatch execution ratios</p>
            </div>

            <div className="space-y-4">
              <div>
                <div className="flex justify-between text-xs font-mono mb-1.5">
                  <span className="text-slate-300">AI Request Success</span>
                  <span className="text-emerald-400 font-bold">{aiSuccessRate}%</span>
                </div>
                <div className="h-2 w-full rounded-full bg-slate-900 border border-slate-800 overflow-hidden">
                  <div
                    className="h-full bg-gradient-to-r from-emerald-500 to-cyan-400 rounded-full transition-all duration-500"
                    style={{ width: `${Math.min(parseFloat(aiSuccessRate), 100)}%` }}
                  />
                </div>
              </div>

              <div>
                <div className="flex justify-between text-xs font-mono mb-1.5">
                  <span className="text-slate-300">Tool Execution Success</span>
                  <span className="text-cyan-400 font-bold">{toolSuccessRate}%</span>
                </div>
                <div className="h-2 w-full rounded-full bg-slate-900 border border-slate-800 overflow-hidden">
                  <div
                    className="h-full bg-gradient-to-r from-cyan-500 to-blue-500 rounded-full transition-all duration-500"
                    style={{ width: `${Math.min(parseFloat(toolSuccessRate), 100)}%` }}
                  />
                </div>
              </div>
            </div>

            <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800 space-y-2">
              <div className="text-[10px] font-mono uppercase tracking-wider text-slate-400 font-semibold">
                Heartbeat Architecture Note
              </div>
              <p className="text-[11px] text-slate-300 leading-relaxed">
                Active sessions are recorded through Android foreground heartbeats. A session is flagged inactive if no heartbeat is received within 5 minutes.
              </p>
            </div>
          </div>
        </div>
      </main>
    </>
  );
}
