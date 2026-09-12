import { Radio } from "lucide-react";
import { useActivityStore } from "@/store/useActivityStore";
import { useAutoScroll } from "@/hooks/useHooks";
import { formatRelative } from "@/lib/format";
import { cn } from "@/lib/cn";

const LEVEL_DOT: Record<string, string> = {
  info: "bg-accent/70",
  success: "bg-mint",
  warn: "bg-amber",
  error: "bg-rose",
};

const KIND_ICON: Record<string, string> = {
  chat: "bg-accent/15 text-accent-soft",
  tool: "bg-violet/15 text-violet",
  task: "bg-mint/15 text-mint",
  device: "bg-sky-500/15 text-sky-300",
  pairing: "bg-amber/15 text-amber",
  command: "bg-indigo-400/15 text-indigo-300",
  notification: "bg-pink-500/15 text-pink-300",
  system: "bg-surface-hover text-ink-muted",
  error: "bg-rose/15 text-rose",
  warning: "bg-amber/15 text-amber",
};

export function ActivityFeed() {
  const items = useActivityStore((s) => s.items);
  const now = Date.now() / 1000;
  const visible = items.slice(-100);
  const ref = useAutoScroll<HTMLDivElement>([items.length]);

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex items-center gap-1.5 px-1 pb-1.5">
        <Radio className="h-3 w-3 text-accent/70" aria-hidden />
        <span className="text-[10px] font-semibold uppercase tracking-[0.14em] text-ink-faint">
          Live activity
        </span>
        <span className="ml-auto font-mono text-[10px] text-ink-faint">{items.length} events</span>
      </div>
      <div ref={ref} className="scroll-thin min-h-0 flex-1 overflow-y-auto">
        {visible.length === 0 ? (
          <p className="px-1 py-2 text-[11px] text-ink-faint">
            Waiting for events from the backend event bus…
          </p>
        ) : (
          <ul className="space-y-0.5">
            {visible.map((item) => (
              <li key={item.id} className="flex items-baseline gap-2 rounded-md px-1 py-1 hover:bg-surface-raised/60">
                <span className={cn("mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full", LEVEL_DOT[item.level] ?? LEVEL_DOT.info)} aria-hidden />
                <span className={cn("flex h-5 min-w-[60px] shrink-0 items-center justify-center rounded px-1.5 font-mono text-[9px] uppercase tracking-wide", KIND_ICON[item.kind] ?? KIND_ICON.system)}>
                  {item.kind}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[11.5px] text-ink">{item.title}</span>
                  {item.detail && <span className="block truncate font-mono text-[10px] text-ink-faint">{item.detail}</span>}
                </span>
                <span className="shrink-0 font-mono text-[10px] text-ink-faint" title={new Date(item.ts * 1000).toLocaleTimeString()}>
                  {formatRelative(item.ts, now)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}