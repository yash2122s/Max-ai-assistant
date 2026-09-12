import { Cpu, MemoryStick, HardDrive, BatteryCharging, BatteryLow, Server } from "lucide-react";
import { useSystemStore } from "@/store/useSystemStore";
import { useAgentStore } from "@/store/useAgentStore";
import { ProgressBar } from "@/components/common/ProgressBar";
import { GlassPanel } from "@/components/common/GlassPanel";
import { Badge } from "@/components/common/Badge";
import { formatBytes, formatPercent } from "@/lib/format";

export function SystemPanel() {
  const metrics = useSystemStore((s) => s.metrics);
  const summary = useSystemStore((s) => s.summary);
  const power = useSystemStore((s) => s.power);
  const agentOnline = useAgentStore((s) => s.status === "online");

  const battery =
    power?.battery?.charge_percent ??
    power?.battery?.estimated_charge_remaining;
  const charging = power?.battery?.is_charging;

  return (
    <GlassPanel title="System" className="min-h-0 shrink-0" pad={false}>
      <div className="space-y-3 p-3">
        <div className="flex items-center gap-2">
          <Server className="h-3.5 w-3.5 text-ink-faint" aria-hidden />
          <span className="min-w-0 truncate font-mono text-[11px] text-ink-muted">
            {metrics?.hostname ?? summary?.hostname ?? "—"}
          </span>
          <span className="ml-auto shrink-0 font-mono text-[10px] text-ink-faint">
            {metrics?.os_info ?? summary?.os ?? ""}
          </span>
        </div>

        {!metrics && !agentOnline && (
          <p className="text-[11px] text-ink-faint">Backend offline — no telemetry.</p>
        )}

        {metrics && (
          <div className="space-y-2.5">
            <MetricRow
              icon={<Cpu className="h-3 w-3" aria-hidden />}
              label="CPU"
              value={`${formatPercent(metrics.cpu_percent)} · ${metrics.cpu_cores} cores`}
              percent={metrics.cpu_percent}
              tone="accent"
            />
            <MetricRow
              icon={<MemoryStick className="h-3 w-3" aria-hidden />}
              label="RAM"
              value={`${metrics.ram_used_gb.toFixed(1)} / ${formatBytes(metrics.ram_total_gb)}`}
              percent={metrics.ram_percent}
              tone="mint"
            />
            <MetricRow
              icon={<HardDrive className="h-3 w-3" aria-hidden />}
              label="Disk"
              value={`${metrics.disk_used_gb.toFixed(1)} / ${formatBytes(metrics.disk_total_gb)}`}
              percent={metrics.disk_percent}
              tone="violet"
            />
          </div>
        )}

        {battery != null && (
          <div className="flex items-center gap-2 rounded-lg border border-line bg-surface-raised px-2 py-1.5">
            {charging ? (
              <BatteryCharging className="h-3.5 w-3.5 text-mint" aria-hidden />
            ) : (
              <BatteryLow className="h-3.5 w-3.5 text-amber" aria-hidden />
            )}
            <span className="font-mono text-[11px] text-ink-muted">
              Battery {Math.round(battery)}%{charging ? " · charging" : ""}
            </span>
            {power?.power_plan && (
              <span className="ml-auto truncate font-mono text-[10px] text-ink-faint" title={power.power_plan}>
                plan: {power.power_plan}
              </span>
            )}
          </div>
        )}

        {!power && (
          <p className="text-[10px] leading-relaxed text-ink-faint">
            Battery telemetry refreshes every 60s via the backend power tool.
          </p>
        )}

        {metrics && (
          <div className="flex flex-wrap items-center gap-1 pt-0.5">
            <Badge tone="accent">{metrics.events_sent} events</Badge>
            <Badge tone="violet">{metrics.tool_requests} tool calls</Badge>
            {metrics.avg_latency_ms > 0 && <Badge tone="mint">{metrics.avg_latency_ms}ms avg</Badge>}
          </div>
        )}
      </div>
    </GlassPanel>
  );
}

function MetricRow({
  icon,
  label,
  value,
  percent,
  tone,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  percent: number;
  tone: "accent" | "mint" | "violet";
}) {
  return (
    <div>
      <div className="mb-1 flex items-center justify-between gap-2">
        <span className="flex items-center gap-1.5 text-[11px] text-ink-muted">
          {icon}
          {label}
        </span>
        <span className="font-mono text-[10.5px] text-ink-faint">{value}</span>
      </div>
      <ProgressBar value={percent} tone={tone} ariaLabel={`${label} usage`} />
    </div>
  );
}