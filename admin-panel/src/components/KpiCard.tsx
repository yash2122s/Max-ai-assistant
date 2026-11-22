import React from "react";
import { LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";

interface KpiCardProps {
  title: string;
  value: string | number;
  subtext?: string;
  icon: LucideIcon;
  variant?: "cyan" | "emerald" | "blue" | "amber" | "rose" | "purple";
  badge?: string;
}

const VARIANT_STYLES = {
  cyan: {
    border: "border-cyan-500/20 hover:border-cyan-500/40",
    bgIcon: "bg-cyan-950/50 text-cyan-400 border border-cyan-500/30",
    glow: "hover:shadow-[0_0_15px_rgba(0,240,255,0.1)]",
  },
  emerald: {
    border: "border-emerald-500/20 hover:border-emerald-500/40",
    bgIcon: "bg-emerald-950/50 text-emerald-400 border border-emerald-500/30",
    glow: "hover:shadow-[0_0_15px_rgba(16,185,129,0.1)]",
  },
  blue: {
    border: "border-blue-500/20 hover:border-blue-500/40",
    bgIcon: "bg-blue-950/50 text-blue-400 border border-blue-500/30",
    glow: "hover:shadow-[0_0_15px_rgba(59,130,246,0.1)]",
  },
  amber: {
    border: "border-amber-500/20 hover:border-amber-500/40",
    bgIcon: "bg-amber-950/50 text-amber-400 border border-amber-500/30",
    glow: "hover:shadow-[0_0_15px_rgba(245,158,11,0.1)]",
  },
  rose: {
    border: "border-rose-500/20 hover:border-rose-500/40",
    bgIcon: "bg-rose-950/50 text-rose-400 border border-rose-500/30",
    glow: "hover:shadow-[0_0_15px_rgba(244,63,94,0.1)]",
  },
  purple: {
    border: "border-purple-500/20 hover:border-purple-500/40",
    bgIcon: "bg-purple-950/50 text-purple-400 border border-purple-500/30",
    glow: "hover:shadow-[0_0_15px_rgba(168,85,247,0.1)]",
  },
};

export function KpiCard({
  title,
  value,
  subtext,
  icon: Icon,
  variant = "cyan",
  badge
}: KpiCardProps) {
  const styles = VARIANT_STYLES[variant];

  return (
    <div
      className={cn(
        "glass-panel rounded-xl p-5 transition-all duration-300 relative overflow-hidden group",
        styles.border,
        styles.glow
      )}
    >
      <div className="flex items-start justify-between">
        <div className="flex-1">
          <div className="flex items-center gap-2">
            <span className="text-[11px] font-mono tracking-wider uppercase text-slate-400">
              {title}
            </span>
            {badge && (
              <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 text-slate-300 font-mono">
                {badge}
              </span>
            )}
          </div>
          <div className="text-2xl font-bold font-mono text-slate-100 mt-2 tracking-tight">
            {value}
          </div>
          {subtext && (
            <p className="text-xs text-slate-400 mt-1 flex items-center gap-1">
              {subtext}
            </p>
          )}
        </div>

        <div className={cn("p-2.5 rounded-lg transition-transform group-hover:scale-110", styles.bgIcon)}>
          <Icon className="w-5 h-5" />
        </div>
      </div>
    </div>
  );
}
