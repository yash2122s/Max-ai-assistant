import React from "react";
import { Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

interface LoadingSpinnerProps {
  label?: string;
  className?: string;
  size?: "sm" | "md" | "lg";
}

export function LoadingSpinner({
  label = "Accessing MAX Data Stream...",
  className,
  size = "md"
}: LoadingSpinnerProps) {
  const sizeClasses = {
    sm: "w-4 h-4",
    md: "w-6 h-6",
    lg: "w-10 h-10",
  }[size];

  return (
    <div className={cn("flex flex-col items-center justify-center py-12 gap-3", className)}>
      <div className="relative flex items-center justify-center">
        <Loader2 className={cn("text-cyan-400 animate-spin", sizeClasses)} />
        <span className="absolute inset-0 rounded-full border border-cyan-500/20 animate-ping opacity-25"></span>
      </div>
      {label && (
        <span className="text-xs font-mono text-slate-400 tracking-wider">
          {label}
        </span>
      )}
    </div>
  );
}
