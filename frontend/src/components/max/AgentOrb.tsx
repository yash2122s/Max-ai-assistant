import { useState } from "react";
import { useAgentStore } from "@/store/useAgentStore";
import type { OrbPhase } from "@/lib/types";
import { Holographic3DCore } from "./Holographic3DCore";
import { cn } from "@/lib/cn";

const PHASE_CONFIG: Record<
  OrbPhase,
  { label: string; dot: string; ring: string; pulse: boolean }
> = {
  standby: {
    label: "Standby",
    dot: "bg-amber-400",
    ring: "from-amber-400/40 to-yellow-500/40",
    pulse: false,
  },
  listening: {
    label: "Listening",
    dot: "bg-mint",
    ring: "from-emerald-400/50 to-teal-400/50",
    pulse: true,
  },
  thinking: {
    label: "Thinking",
    dot: "bg-violet",
    ring: "from-violet-400/50 to-fuchsia-400/50",
    pulse: true,
  },
  executing: {
    label: "Executing",
    dot: "bg-accent",
    ring: "from-sky-400/60 to-cyan-400/60",
    pulse: true,
  },
  speaking: {
    label: "Speaking",
    dot: "bg-accent",
    ring: "from-cyan-400/50 to-blue-400/50",
    pulse: true,
  },
  waiting_confirmation: {
    label: "Confirmation required",
    dot: "bg-amber",
    ring: "from-amber-400/50 to-orange-400/50",
    pulse: true,
  },
  error: {
    label: "Error",
    dot: "bg-rose",
    ring: "from-rose-400/50 to-red-400/50",
    pulse: false,
  },
};

export function AgentOrb({ size = 88 }: { size?: number }) {
  const phase = useAgentStore((s) => s.orbPhase);
  const reason = useAgentStore((s) => s.orbReason);
  const cfg = PHASE_CONFIG[phase] ?? PHASE_CONFIG.standby;
  const [use3D, setUse3D] = useState(true);

  return (
    <div
      className="flex flex-col items-center gap-1.5 select-none"
      role="status"
      aria-label={`MAX is ${cfg.label}`}
    >
      <div
        className="relative flex items-center justify-center cursor-pointer transition-transform hover:scale-105"
        style={{ width: size, height: size }}
        onClick={() => setUse3D((prev) => !prev)}
        title="Click to toggle 3D Holographic Neural Core / 2D mode"
      >
        {cfg.pulse && (
          <>
            <span
              className={cn("absolute inset-0 rounded-full bg-gradient-to-br animate-pulse-ring", cfg.ring)}
              style={{ width: size, height: size }}
            />
            <span
              className={cn("absolute inset-0 rounded-full bg-gradient-to-br animate-pulse-ring-slow", cfg.ring)}
              style={{ width: size, height: size }}
            />
          </>
        )}

        {use3D ? (
          <div
            className="relative z-10 flex items-center justify-center rounded-full bg-surface-raised/40 backdrop-blur-sm border border-amber-500/30 shadow-glow overflow-hidden"
            style={{ width: size, height: size }}
          >
            <Holographic3DCore width={size} height={size} interactive />
          </div>
        ) : (
          <div
            className="relative z-10 flex items-center justify-center rounded-full border border-accent/30 bg-gradient-to-br from-sky-500/20 via-surface-raised to-indigo-500/20 shadow-glow"
            style={{ width: size * 0.62, height: size * 0.62 }}
          >
            <span className={cn("h-2.5 w-2.5 rounded-full", cfg.dot)} />
          </div>
        )}
      </div>

      <div className="flex items-center gap-1.5">
        <span className={cn("h-1.5 w-1.5 rounded-full", cfg.dot)} />
        <span className="text-[11.5px] font-medium tracking-wide text-ink">{cfg.label}</span>
      </div>
      {reason && <span className="max-w-[180px] truncate font-mono text-[9.5px] text-ink-faint">{reason}</span>}
    </div>
  );
}