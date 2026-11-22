"use client";

import React, { useEffect, useState } from "react";
import { X, ShieldAlert, Cpu, Layers, Clock, Zap, AlertCircle } from "lucide-react";
import { getUserDetail, setAdminRole } from "@/lib/api";
import { StatusBadge } from "./StatusBadge";
import { LoadingSpinner } from "./LoadingSpinner";
import { formatTimestamp, formatDuration } from "@/lib/utils";

interface UserDetailModalProps {
  userId: string | null;
  onClose: () => void;
}

export function UserDetailModal({ userId, onClose }: UserDetailModalProps) {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [adminActionLoading, setAdminActionLoading] = useState(false);

  useEffect(() => {
    if (!userId) return;
    setLoading(true);
    setError(null);
    getUserDetail(userId)
      .then((res) => {
        setData(res);
      })
      .catch((err) => {
        setError(err.message || "Failed to load user detail");
      })
      .finally(() => {
        setLoading(false);
      });
  }, [userId]);

  if (!userId) return null;

  const handleToggleAdmin = async (makeAdmin: boolean) => {
    setAdminActionLoading(true);
    try {
      await setAdminRole(userId, makeAdmin);
      alert(`Admin role ${makeAdmin ? "granted" : "revoked"} successfully for ${userId}`);
    } catch (err: any) {
      alert(`Failed to set admin role: ${err.message}`);
    } finally {
      setAdminActionLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-fadeIn">
      <div className="glass-panel w-full max-w-4xl max-h-[90vh] rounded-2xl flex flex-col border border-cyan-500/30 shadow-2xl overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-jarvis-border bg-jarvis-darkest/60">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-cyan-950/60 border border-cyan-500/40 text-cyan-400">
              <Cpu className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-sm font-semibold text-slate-100 flex items-center gap-2">
                User Diagnostics: <span className="font-mono text-cyan-400 text-xs">{userId}</span>
              </h2>
              <p className="text-[11px] font-mono text-slate-400">Detailed session telemetry and execution history</p>
            </div>
          </div>

          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Content Body */}
        <div className="flex-1 overflow-y-auto p-6 space-y-6">
          {loading ? (
            <LoadingSpinner label="Decrypting user telemetry stream..." />
          ) : error ? (
            <div className="p-4 rounded-xl bg-rose-950/40 border border-rose-500/30 flex items-center gap-3 text-rose-300 text-xs">
              <AlertCircle className="w-5 h-5 flex-shrink-0" />
              <span>{error}</span>
            </div>
          ) : (
            <>
              {/* Profile & License Header Card */}
              <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
                <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800">
                  <span className="text-[10px] font-mono text-slate-400 uppercase">License Plan</span>
                  <div className="mt-1 flex items-center gap-2">
                    <StatusBadge status={data?.license?.plan || "UNKNOWN"} type="plan" />
                    <StatusBadge status={data?.license?.status || "UNKNOWN"} type="status" />
                  </div>
                </div>
                <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800">
                  <span className="text-[10px] font-mono text-slate-400 uppercase">App Version</span>
                  <div className="mt-1 font-mono text-slate-200 text-sm">
                    {data?.user?.appVersion || "1.0.0"}
                  </div>
                </div>
                <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800">
                  <span className="text-[10px] font-mono text-slate-400 uppercase">Last Active</span>
                  <div className="mt-1 font-mono text-slate-200 text-xs">
                    {formatTimestamp(data?.user?.lastActiveAt)}
                  </div>
                </div>
                <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800">
                  <span className="text-[10px] font-mono text-slate-400 uppercase">Last Feature</span>
                  <div className="mt-1 font-mono text-cyan-400 text-xs truncate">
                    {data?.user?.lastFeatureUsed || "None"}
                  </div>
                </div>
              </div>

              {/* Usage Stats Row */}
              <div className="grid grid-cols-3 gap-4">
                <div className="p-4 rounded-xl bg-slate-900/40 border border-slate-800/80 flex items-center gap-3">
                  <div className="p-2.5 rounded-lg bg-cyan-950/50 text-cyan-400 border border-cyan-500/20">
                    <Zap className="w-4 h-4" />
                  </div>
                  <div>
                    <div className="text-[10px] font-mono text-slate-400 uppercase">AI Requests</div>
                    <div className="text-lg font-bold font-mono text-slate-100">{data?.user?.totalAiRequests || 0}</div>
                  </div>
                </div>
                <div className="p-4 rounded-xl bg-slate-900/40 border border-slate-800/80 flex items-center gap-3">
                  <div className="p-2.5 rounded-lg bg-blue-950/50 text-blue-400 border border-blue-500/20">
                    <Layers className="w-4 h-4" />
                  </div>
                  <div>
                    <div className="text-[10px] font-mono text-slate-400 uppercase">Tool Executions</div>
                    <div className="text-lg font-bold font-mono text-slate-100">{data?.user?.totalToolExecutions || 0}</div>
                  </div>
                </div>
                <div className="p-4 rounded-xl bg-slate-900/40 border border-slate-800/80 flex items-center gap-3">
                  <div className="p-2.5 rounded-lg bg-purple-950/50 text-purple-400 border border-purple-500/20">
                    <Clock className="w-4 h-4" />
                  </div>
                  <div>
                    <div className="text-[10px] font-mono text-slate-400 uppercase">Voice Sessions</div>
                    <div className="text-lg font-bold font-mono text-slate-100">{data?.user?.totalVoiceSessions || 0}</div>
                  </div>
                </div>
              </div>

              {/* Recent Sessions */}
              <div>
                <h3 className="text-xs font-semibold text-slate-200 mb-2 flex items-center gap-2">
                  <Clock className="w-3.5 h-3.5 text-cyan-400" />
                  Recent Foreground Sessions (Last 20)
                </h3>
                <div className="border border-jarvis-border rounded-xl overflow-hidden bg-slate-950/40">
                  <table className="w-full text-left text-xs">
                    <thead className="bg-slate-900/60 text-[10px] font-mono uppercase text-slate-400 border-b border-jarvis-border">
                      <tr>
                        <th className="py-2.5 px-4">Session ID</th>
                        <th className="py-2.5 px-4">Started</th>
                        <th className="py-2.5 px-4">Duration</th>
                        <th className="py-2.5 px-4">Status</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-jarvis-border font-mono text-[11px] text-slate-300">
                      {data?.sessions?.length === 0 ? (
                        <tr>
                          <td colSpan={4} className="py-4 text-center text-slate-500">No session records found</td>
                        </tr>
                      ) : (
                        data?.sessions?.map((s: any) => (
                          <tr key={s.sessionId} className="hover:bg-slate-900/40">
                            <td className="py-2 px-4 truncate max-w-[150px]">{s.sessionId}</td>
                            <td className="py-2 px-4 text-slate-400">{formatTimestamp(s.startedAt)}</td>
                            <td className="py-2 px-4">{formatDuration(s.durationMs)}</td>
                            <td className="py-2 px-4">
                              <StatusBadge status={s.isActive ? "ACTIVE" : "COMPLETED"} type="status" />
                            </td>
                          </tr>
                        ))
                      )}
                    </tbody>
                  </table>
                </div>
              </div>

              {/* Recent Activity Events */}
              <div>
                <h3 className="text-xs font-semibold text-slate-200 mb-2 flex items-center gap-2">
                  <Zap className="w-3.5 h-3.5 text-cyan-400" />
                  Recent Activity Telemetry (Last 50)
                </h3>
                <div className="border border-jarvis-border rounded-xl overflow-hidden bg-slate-950/40 max-h-60 overflow-y-auto">
                  <table className="w-full text-left text-xs">
                    <thead className="bg-slate-900/60 text-[10px] font-mono uppercase text-slate-400 border-b border-jarvis-border sticky top-0">
                      <tr>
                        <th className="py-2.5 px-4">Timestamp</th>
                        <th className="py-2.5 px-4">Event Type</th>
                        <th className="py-2.5 px-4">Feature</th>
                        <th className="py-2.5 px-4">Status</th>
                        <th className="py-2.5 px-4">Duration</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-jarvis-border font-mono text-[11px] text-slate-300">
                      {data?.events?.length === 0 ? (
                        <tr>
                          <td colSpan={5} className="py-4 text-center text-slate-500">No event records found</td>
                        </tr>
                      ) : (
                        data?.events?.map((e: any) => (
                          <tr key={e.eventId} className="hover:bg-slate-900/40">
                            <td className="py-2 px-4 text-slate-400">{formatTimestamp(e.serverTimestamp)}</td>
                            <td className="py-2 px-4 text-slate-200">{e.eventType}</td>
                            <td className="py-2 px-4 text-cyan-400">{e.feature}</td>
                            <td className="py-2 px-4">
                              <StatusBadge status={e.status} type="status" />
                            </td>
                            <td className="py-2 px-4">{formatDuration(e.durationMs)}</td>
                          </tr>
                        ))
                      )}
                    </tbody>
                  </table>
                </div>
              </div>

              {/* Admin Role Management Card */}
              <div className="p-4 rounded-xl bg-slate-900/70 border border-jarvis-border flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <ShieldAlert className="w-5 h-5 text-amber-400" />
                  <div>
                    <div className="text-xs font-semibold text-slate-200">Privileged Custom Claims</div>
                    <div className="text-[10px] text-slate-400 font-mono">Grant or revoke administrator access for this user</div>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handleToggleAdmin(true)}
                    disabled={adminActionLoading}
                    className="px-3 py-1.5 rounded-lg bg-cyan-950/80 border border-cyan-500/40 text-cyan-300 hover:bg-cyan-900 text-xs font-medium transition-all"
                  >
                    Grant Admin
                  </button>
                  <button
                    onClick={() => handleToggleAdmin(false)}
                    disabled={adminActionLoading}
                    className="px-3 py-1.5 rounded-lg bg-rose-950/80 border border-rose-500/40 text-rose-300 hover:bg-rose-900 text-xs font-medium transition-all"
                  >
                    Revoke Admin
                  </button>
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
