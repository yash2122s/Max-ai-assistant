"use client";

import React from "react";
import { RefreshCw, Radio } from "lucide-react";
import { cn } from "@/lib/utils";

interface HeaderProps {
  title: string;
  subtitle?: string;
  onRefresh?: () => void;
  isRefreshing?: boolean;
}

export function Header({ title, subtitle, onRefresh, isRefreshing }: HeaderProps) {
  return (
    <header className="h-16 px-8 border-b border-jarvis-border flex items-center justify-between bg-jarvis-dark/80 backdrop-blur-md sticky top-0 z-20">
      <div>
        <h1 className="text-base font-semibold text-slate-100 flex items-center gap-2">
          {title}
        </h1>
        {subtitle && (
          <p className="text-xs text-slate-400 mt-0.5">{subtitle}</p>
        )}
      </div>

      <div className="flex items-center gap-4">
        {/* Heartbeat Status Indicator */}
        <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-slate-900/80 border border-slate-800 text-[11px] font-mono text-slate-300">
          <span className="relative flex h-2 w-2">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500"></span>
          </span>
          <Radio className="w-3 h-3 text-cyan-400 ml-0.5" />
          <span>Active Window: 5m</span>
        </div>

        {/* Refresh Action */}
        {onRefresh && (
          <button
            onClick={onRefresh}
            disabled={isRefreshing}
            className={cn(
              "p-2 rounded-lg bg-slate-900 border border-slate-800 text-slate-300 hover:text-cyan-400 hover:border-cyan-500/40 transition-all",
              isRefreshing && "opacity-50 cursor-not-allowed"
            )}
            title="Refresh Data"
          >
            <RefreshCw className={cn("w-4 h-4", isRefreshing && "animate-spin text-cyan-400")} />
          </button>
        )}
      </div>
    </header>
  );
}
