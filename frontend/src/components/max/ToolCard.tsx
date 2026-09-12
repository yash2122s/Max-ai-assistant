import { Loader2, CheckCircle2, XCircle, Terminal, Wrench } from "lucide-react";
import type { ToolTrace } from "@/lib/types";
import { formatArgs, formatClock, formatToolOutput } from "@/lib/format";
import { cn } from "@/lib/cn";

export function ToolCard({ trace }: { trace: ToolTrace }) {
  const running = trace.status === "RUNNING";
  const failed = trace.status === "FAILED";
  return (
    <div
      className={cn(
        "animate-fade-up rounded-xl border bg-surface-raised/70 p-3",
        running && "border-accent/30",
        trace.status === "SUCCESS" && "border-mint/25",
        failed && "border-rose/30",
      )}
      role="status"
      aria-label={`Tool ${trace.tool}/${trace.action} ${trace.status.toLowerCase()}`}
    >
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          {running ? (
            <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-accent" aria-hidden />
          ) : failed ? (
            <XCircle className="h-3.5 w-3.5 shrink-0 text-rose" aria-hidden />
          ) : (
            <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-mint" aria-hidden />
          )}
          <Terminal className="h-3.5 w-3.5 shrink-0 text-ink-faint" aria-hidden />
          <span className="truncate font-mono text-xs text-ink">
            {trace.tool}/{trace.action}
          </span>
        </div>
        <span className="shrink-0 font-mono text-[10px] text-ink-faint">
          {formatClock(trace.startedAt)}
        </span>
      </div>

      {trace.args && Object.keys(trace.args).length > 0 && (
        <p className="mt-1.5 truncate font-mono text-[11px] text-ink-muted">
          {formatArgs(trace.args)}
        </p>
      )}

      {running && trace.progress && (
        <div className="mt-2 flex items-center gap-2">
          <span className="shimmer-line h-px flex-1 rounded-full" />
          <span className="shrink-0 text-[11px] text-ink-muted">{trace.progress}</span>
        </div>
      )}

      {trace.status === "SUCCESS" && trace.output != null && (
        <p className="mt-1.5 line-clamp-4 whitespace-pre-wrap break-words font-mono text-[11px] leading-relaxed text-ink-muted">
          {formatToolOutput(trace.output)}
        </p>
      )}

      {failed && trace.error && (
        <p className="mt-1.5 break-words font-mono text-[11px] leading-relaxed text-rose" role="alert">
          {trace.error}
        </p>
      )}

      {running && (
        <p className="mt-1.5 flex items-center gap-1 font-mono text-[10px] text-accent-soft">
          <Wrench className="h-3 w-3" aria-hidden /> RUNNING
        </p>
      )}
    </div>
  );
}