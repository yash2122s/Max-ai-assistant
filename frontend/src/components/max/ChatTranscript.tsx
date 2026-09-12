import { useMemo } from "react";
import { Bot, UserRound, MessageSquareDashed } from "lucide-react";
import { useChatStore } from "@/store/useChatStore";
import { useActivityStore } from "@/store/useActivityStore";
import { ToolCard } from "./ToolCard";
import { formatClock } from "@/lib/format";
import { useAutoScroll } from "@/hooks/useHooks";
import { cn } from "@/lib/cn";

const TRACE_WINDOW_S = 30 * 60;

export function ChatTranscript() {
  const messages = useChatStore((s) => s.messages);
  const pending = useChatStore((s) => s.pending);
  const toolTraces = useActivityStore((s) => s.toolTraces);

  const timeline = useMemo(() => {
    const now = Date.now() / 1000;
    const traceItems = Object.values(toolTraces).filter(
      (t) => t.status === "RUNNING" || now - t.startedAt < TRACE_WINDOW_S,
    );
    const entries: Array<
      | { kind: "msg"; ts: number; msg: (typeof messages)[number] }
      | { kind: "tool"; ts: number; trace: (typeof traceItems)[number] }
    > = [
      ...messages.map((m) => ({ kind: "msg" as const, ts: m.ts, msg: m })),
      ...traceItems.map((t) => ({ kind: "tool" as const, ts: t.startedAt, trace: t })),
    ];
    return entries.sort((a, b) => a.ts - b.ts);
  }, [messages, toolTraces]);

  const ref = useAutoScroll<HTMLDivElement>([messages.length, Object.keys(toolTraces).length]);

  return (
    <div ref={ref} className="scroll-thin min-h-0 flex-1 space-y-2.5 overflow-y-auto p-3">
      {timeline.length === 0 && !pending && (
        <div className="flex h-full flex-col items-center justify-center gap-3 text-center">
          <MessageSquareDashed className="h-9 w-9 text-ink-faint/50" aria-hidden />
          <p className="max-w-xs text-sm text-ink-faint">
            No activity yet. Ask MAX something below, or watch tool executions and device events appear here.
          </p>
        </div>
      )}

      {timeline.map((entry) =>
        entry.kind === "msg" ? (
          <ChatBubble key={entry.msg.id} role={entry.msg.role} text={entry.msg.text} ts={entry.msg.ts} />
        ) : (
          <ToolCard key={entry.trace.key} trace={entry.trace} />
        ),
      )}

      {pending && <ThinkingDots />}
    </div>
  );
}

function ChatBubble({ role, text, ts }: { role: string; text: string; ts: number }) {
  const isUser = role === "user";
  return (
    <div className={cn("flex items-start gap-2", isUser && "flex-row-reverse")}>
      <div
        className={cn(
          "flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border",
          isUser ? "border-accent/25 bg-accent/10 text-accent-soft" : "border-violet/25 bg-violet/10 text-violet",
        )}
        aria-hidden
      >
        {isUser ? <UserRound className="h-3.5 w-3.5" /> : <Bot className="h-3.5 w-3.5" />}
      </div>
      <div className={cn("max-w-[80%]", isUser && "text-right")}>
        <div className="mb-0.5 flex items-baseline justify-between gap-2">
          <span className="text-[10px] font-semibold uppercase tracking-wide text-ink-faint">
            {isUser ? "You" : "MAX"}
          </span>
          <span className="font-mono text-[10px] text-ink-faint">{formatClock(ts)}</span>
        </div>
        <div
          className={cn(
            "rounded-2xl border px-3 py-2 text-left text-[13px] leading-relaxed whitespace-pre-wrap",
            isUser
              ? "border-accent/25 bg-accent/10 text-ink"
              : "border-line bg-surface-raised/80 text-ink",
          )}
        >
          {text}
        </div>
      </div>
    </div>
  );
}

function ThinkingDots() {
  return (
    <div className="flex items-center gap-1.5 px-1 py-2" aria-label="MAX is thinking">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className="h-1.5 w-1.5 rounded-full bg-accent/70"
          style={{ animation: `pulse-ring 1.2s ${i * 0.2}s ease-in-out infinite` }}
        />
      ))}
    </div>
  );
}