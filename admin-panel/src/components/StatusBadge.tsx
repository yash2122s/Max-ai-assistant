import React from "react";
import { cn } from "@/lib/utils";

interface StatusBadgeProps {
  status: string;
  type?: "status" | "plan" | "boolean";
  className?: string;
}

export function StatusBadge({ status, type = "status", className }: StatusBadgeProps) {
  const norm = (status || "").toUpperCase();

  let colorClasses = "bg-slate-800 text-slate-300 border-slate-700";

  if (type === "status") {
    switch (norm) {
      case "SUCCESS":
      case "COMPLETED":
      case "ACTIVE":
        colorClasses = "bg-emerald-950/60 text-emerald-400 border-emerald-500/30";
        break;
      case "FAILURE":
      case "FAILED":
      case "ERROR":
      case "TIMEOUT":
        colorClasses = "bg-rose-950/60 text-rose-400 border-rose-500/30";
        break;
      case "IN_PROGRESS":
      case "START":
        colorClasses = "bg-cyan-950/60 text-cyan-400 border-cyan-500/30 animate-pulse";
        break;
      case "CANCELLED":
      case "PAUSED":
        colorClasses = "bg-amber-950/60 text-amber-400 border-amber-500/30";
        break;
      default:
        colorClasses = "bg-slate-800/80 text-slate-300 border-slate-700";
    }
  } else if (type === "plan") {
    switch (norm) {
      case "LIFETIME":
      case "ENTERPRISE":
        colorClasses = "bg-purple-950/60 text-purple-400 border-purple-500/30";
        break;
      case "PRO":
      case "PREMIUM":
        colorClasses = "bg-cyan-950/60 text-cyan-400 border-cyan-500/30";
        break;
      case "STANDARD":
        colorClasses = "bg-blue-950/60 text-blue-400 border-blue-500/30";
        break;
      default:
        colorClasses = "bg-slate-800/80 text-slate-400 border-slate-700";
    }
  }

  return (
    <span
      className={cn(
        "inline-flex items-center px-2 py-0.5 rounded text-[10px] font-mono font-medium border",
        colorClasses,
        className
      )}
    >
      {norm}
    </span>
  );
}
