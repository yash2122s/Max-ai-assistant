import { cn } from "@/lib/cn";

export function ProgressBar({
  value,
  tone = "accent",
  className,
  ariaLabel,
}: {
  value: number;
  tone?: "accent" | "mint" | "amber" | "rose" | "violet";
  className?: string;
  ariaLabel?: string;
}) {
  const clamped = Math.max(0, Math.min(100, value));
  const barColor =
    tone === "mint"
      ? "bg-mint"
      : tone === "amber"
        ? "bg-amber"
        : tone === "rose"
          ? "bg-rose"
          : tone === "violet"
            ? "bg-violet"
            : "bg-accent";
  return (
    <div
      role="progressbar"
      aria-valuenow={Math.round(clamped)}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-label={ariaLabel}
      className={cn("h-1.5 w-full overflow-hidden rounded-full bg-line", className)}
    >
      <div
        className={cn("h-full rounded-full transition-[width] duration-500 ease-out", barColor)}
        style={{ width: `${clamped}%` }}
      />
    </div>
  );
}