import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

type Tone = "accent" | "mint" | "violet" | "amber" | "rose" | "neutral";

const toneClasses: Record<Tone, string> = {
  accent: "border-accent/25 bg-accent/10 text-accent-soft",
  mint: "border-mint/25 bg-mint/10 text-mint",
  violet: "border-violet/25 bg-violet/10 text-violet",
  amber: "border-amber/25 bg-amber/10 text-amber",
  rose: "border-rose/25 bg-rose/10 text-rose",
  neutral: "border-line-strong bg-surface-raised text-ink-muted",
};

export function Badge({
  tone = "neutral",
  className,
  title,
  children,
}: {
  tone?: Tone;
  className?: string;
  title?: string;
  children: ReactNode;
}) {
  return (
    <span title={title} className={cn("pill", toneClasses[tone], className)}>{children}</span>
  );
}