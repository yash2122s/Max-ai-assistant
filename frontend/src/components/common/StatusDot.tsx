import { cn } from "@/lib/cn";

type StatusTone = "online" | "offline" | "busy" | "warn" | "error";

const toneMap: Record<StatusTone, string> = {
  online: "bg-mint",
  offline: "bg-ink-faint",
  busy: "bg-accent",
  warn: "bg-amber",
  error: "bg-rose",
};

export function StatusDot({
  tone,
  pulse = false,
  className,
  label,
}: {
  tone: StatusTone;
  pulse?: boolean;
  className?: string;
  label?: string;
}) {
  return (
    <span
      role={label ? "status" : undefined}
      aria-label={label}
      title={label}
      className={cn("relative inline-flex h-2 w-2 shrink-0", className)}
    >
      {pulse && (
        <span
          className={cn("absolute inline-flex h-full w-full rounded-full opacity-60 animate-ping", toneMap[tone])}
        />
      )}
      <span className={cn("relative inline-flex h-2 w-2 rounded-full", toneMap[tone])} />
    </span>
  );
}