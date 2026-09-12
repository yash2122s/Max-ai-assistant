import { useAgentStore } from "@/store/useAgentStore";
import { Badge } from "@/components/common/Badge";
import { StatusDot } from "@/components/common/StatusDot";
import { formatUptime } from "@/lib/format";
import { useNow } from "@/hooks/useHooks";
import { cn } from "@/lib/cn";

function ConnBadge({ tone, label, pulse }: { tone: "online" | "offline" | "busy" | "warn"; label: string; pulse?: boolean }) {
  return (
    <Badge tone={tone === "online" ? "mint" : tone === "busy" ? "accent" : tone === "warn" ? "amber" : "neutral"}>
      <StatusDot tone={tone} pulse={pulse} />
      {label}
    </Badge>
  );
}

export function Header() {
  const status = useAgentStore((s) => s.status);
  const agentOnline = useAgentStore((s) => s.agentOnline);
  const agentVersion = useAgentStore((s) => s.agentVersion);
  const uptime = useAgentStore((s) => s.uptime);
  const httpPort = useAgentStore((s) => s.httpPort);
  const wsPort = useAgentStore((s) => s.wsPort);
  const retryCount = useAgentStore((s) => s.retryCount);
  const now = useNow(1000);

  const maxTone = agentOnline && status === "online" ? "online" : status === "reconnecting" ? "warn" : "offline";
  const sseTone = status === "online" ? "online" : status === "reconnecting" ? "busy" : "offline";

  return (
    <header className="flex h-12 shrink-0 items-center gap-3 border-b border-line bg-surface/70 px-4 backdrop-blur-md">
      <div className="flex items-center gap-2.5">
        <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-sky-400 to-violet-500 text-[13px] font-black text-black">
          M
        </div>
        <div className="leading-tight">
          <div className="text-[13px] font-semibold tracking-wide text-ink">
            MAX <span className="text-ink-faint font-normal">Control Center</span>
          </div>
          <div className="font-mono text-[10px] text-ink-faint">
            agent v{agentVersion ?? "—"} · ws {wsPort ?? "—"} · http {httpPort ?? "—"}
          </div>
        </div>
      </div>

      <div className="ml-auto flex flex-wrap items-center justify-end gap-1.5">
        <ConnBadge tone={maxTone} pulse={status === "online"} label={`MAX ${status === "online" ? "Online" : status}`} />
        <ConnBadge tone={sseTone} pulse={status === "online"} label="Live stream" />
        <Badge tone="neutral" className="hidden lg:inline-flex" title="Gemini Live state is not exposed by the backend">
          Gemini Live <span className="text-ink-faint">—</span>
        </Badge>
        <Badge tone="neutral" className="hidden xl:inline-flex" title="Vosk state is not exposed by the backend">
          Vosk <span className="text-ink-faint">—</span>
        </Badge>
        {status === "reconnecting" && (
          <Badge tone="amber">
            Reconnecting{retryCount > 0 ? ` (${retryCount})` : ""}
          </Badge>
        )}
        <span className={cn("font-mono text-[11px] text-ink-faint", agentOnline ? "" : "text-rose/70")}>
          up {formatUptime(uptime ?? now - Date.now() / 1000)}
        </span>
      </div>
    </header>
  );
}