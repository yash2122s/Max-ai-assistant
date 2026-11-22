"use client";

import React, { useState } from "react";
import { Download, FileText, ShieldCheck, CheckCircle2, AlertCircle, Users, Activity } from "lucide-react";
import { Header } from "@/components/Header";
import { exportReport } from "@/lib/api";

export default function ExportPage() {
  const [downloadingUsers, setDownloadingUsers] = useState(false);
  const [downloadingActivity, setDownloadingActivity] = useState(false);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);

  const handleDownload = async (type: "users" | "activity") => {
    if (type === "users") setDownloadingUsers(true);
    else setDownloadingActivity(true);
    setStatusMessage(null);

    try {
      const res = await exportReport(type);
      const blob = new Blob([res.csv], { type: "text/csv;charset=utf-8;" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.setAttribute("download", res.filename || `max-${type}-export.csv`);
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);

      setStatusMessage(`Successfully generated and downloaded ${res.filename}`);
    } catch (err: any) {
      setStatusMessage(`Export failed: ${err.message || "Unknown error"}`);
    } finally {
      if (type === "users") setDownloadingUsers(false);
      else setDownloadingActivity(false);
    }
  };

  return (
    <>
      <Header title="Export Reports" subtitle="Secure CSV report extraction for analytics and operational audits" />

      <main className="p-8 space-y-8 max-w-4xl">
        {/* Compliance & Privacy Header */}
        <div className="p-5 rounded-2xl bg-slate-900/80 border border-slate-800 flex items-start gap-4 text-slate-300 text-xs">
          <ShieldCheck className="w-5 h-5 flex-shrink-0 mt-0.5 text-cyan-400" />
          <div className="space-y-1">
            <span className="font-semibold text-slate-100 text-sm">Privileged Data Governance</span>
            <p className="leading-relaxed">
              Exported files contain aggregated system usage and operational telemetry only. In accordance with MAX data minimization standards, conversations, transcripts, memory contents, authentication credentials, and user tokens are excluded from all export pipelines.
            </p>
          </div>
        </div>

        {statusMessage && (
          <div className="p-4 rounded-xl bg-cyan-950/40 border border-cyan-500/30 flex items-center gap-3 text-cyan-300 text-xs font-mono">
            <CheckCircle2 className="w-4 h-4 text-cyan-400 flex-shrink-0" />
            <span>{statusMessage}</span>
          </div>
        )}

        {/* Export Cards */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {/* Users Summary Export */}
          <div className="glass-panel p-6 rounded-2xl border border-jarvis-border flex flex-col justify-between space-y-6">
            <div className="space-y-3">
              <div className="p-3 w-fit rounded-xl bg-blue-950/60 border border-blue-500/30 text-blue-400">
                <Users className="w-6 h-6" />
              </div>
              <div>
                <h3 className="text-base font-semibold text-slate-100">User Telemetry Summary</h3>
                <p className="text-xs text-slate-400 mt-1 leading-relaxed">
                  Export verified user profiles, app versions, license entitlements, last active timestamps, and aggregate interaction counters.
                </p>
              </div>
            </div>

            <button
              onClick={() => handleDownload("users")}
              disabled={downloadingUsers}
              className="w-full py-2.5 px-4 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-medium text-xs font-mono transition-all flex items-center justify-center gap-2 disabled:opacity-50"
            >
              <Download className="w-4 h-4" />
              <span>{downloadingUsers ? "Compiling CSV..." : "Export Users CSV"}</span>
            </button>
          </div>

          {/* Activity Events Export */}
          <div className="glass-panel p-6 rounded-2xl border border-jarvis-border flex flex-col justify-between space-y-6">
            <div className="space-y-3">
              <div className="p-3 w-fit rounded-xl bg-cyan-950/60 border border-cyan-500/30 text-cyan-400">
                <Activity className="w-6 h-6" />
              </div>
              <div>
                <h3 className="text-base font-semibold text-slate-100">Activity Telemetry Log</h3>
                <p className="text-xs text-slate-400 mt-1 leading-relaxed">
                  Export historical activity events, feature classifications, execution outcomes, duration (ms), and classified error categories.
                </p>
              </div>
            </div>

            <button
              onClick={() => handleDownload("activity")}
              disabled={downloadingActivity}
              className="w-full py-2.5 px-4 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-slate-950 font-semibold text-xs font-mono transition-all flex items-center justify-center gap-2 disabled:opacity-50"
            >
              <Download className="w-4 h-4" />
              <span>{downloadingActivity ? "Compiling CSV..." : "Export Activity CSV"}</span>
            </button>
          </div>
        </div>
      </main>
    </>
  );
}
