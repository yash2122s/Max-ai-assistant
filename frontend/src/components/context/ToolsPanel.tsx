import { TOOL_FAMILIES } from "@/lib/constants";
import { GlassPanel } from "@/components/common/GlassPanel";
import { Wrench } from "lucide-react";

export function ToolsPanel() {
  return (
    <GlassPanel
      title={
        <span className="flex items-center gap-1.5">
          <Wrench className="h-3.5 w-3.5 text-violet/70" aria-hidden />
          Tool registry
        </span>
      }
      className="shrink-0"
      pad={false}
    >
      <div className="p-2">
        <p className="mb-2 px-1 text-[10px] leading-relaxed text-ink-faint">
          {TOOL_FILTERS_REFERENCE}
        </p>
        <ul className="grid grid-cols-2 gap-1.5">
          {TOOL_FAMILIES.map((t) => (
            <li
              key={t.name}
              title={`${t.description}\n${t.actions.join(", ")}`}
              className="cursor-help rounded-lg border border-line bg-surface-raised/60 px-2 py-1.5 transition-colors hover:border-line-strong"
            >
              <span className="block font-mono text-[11px] font-medium text-ink">{t.name}</span>
              <span className="block truncate text-[9.5px] text-ink-faint">{t.actions.length} actions</span>
            </li>
          ))}
        </ul>
      </div>
    </GlassPanel>
  );
}

const TOOL_FILTERS_REFERENCE =
  "The 12 tool families registered by the backend. Execution happens through the backend's /api/tool route — this list is a reference of what exists, not a bypass of any security gate.";