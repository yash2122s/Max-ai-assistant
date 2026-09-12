import { Laptop, Smartphone, Tablet, MonitorSmartphone, ChevronRight, Radio, Wifi, WifiOff } from "lucide-react";
import { useDevicesStore, getConnectedCount } from "@/store/useDevicesStore";
import { useAgentStore } from "@/store/useAgentStore";
import type { DeviceVM } from "@/lib/types";
import { AgentOrb } from "@/components/max/AgentOrb";
import { GlassPanel } from "@/components/common/GlassPanel";
import { Badge } from "@/components/common/Badge";
import { StatusDot } from "@/components/common/StatusDot";
import { formatLatency, formatRelative } from "@/lib/format";
import { useNow } from "@/hooks/useHooks";
import { cn } from "@/lib/cn";

function DeviceIcon({ platform }: { platform: string }) {
  const p = (platform ?? "").toLowerCase();
  if (p.includes("android") || p.includes("phone")) return <Smartphone className="h-4 w-4" aria-hidden />;
  if (p.includes("tablet")) return <Tablet className="h-4 w-4" aria-hidden />;
  if (p.includes("ios")) return <MonitorSmartphone className="h-4 w-4" aria-hidden />;
  return <Laptop className="h-4 w-4" aria-hidden />;
}

export function DeviceFleet() {
  const devices = useDevicesStore((s) => s.devices);
  const selectedId = useDevicesStore((s) => s.selectedId);
  const select = useDevicesStore((s) => s.select);
  const now = useNow();
  const connected = getConnectedCount({ devices });

  const connectedDevices = Object.values(devices).filter((d) => d.connection_state === "connected");
  const offlineDevices = Object.values(devices).filter((d) => d.connection_state === "disconnected");
  const selected = selectedId ? devices[selectedId] : undefined;

  return (
    <GlassPanel
      title={
        <span className="flex items-center gap-1.5">
          Devices
          <Badge tone={connected > 0 ? "mint" : "neutral"}>
            {connected} online
          </Badge>
        </span>
      }
      className="min-h-0 flex-1"
      pad={false}
    >
      <ul className="scroll-thin min-h-0 space-y-1 overflow-y-auto p-2">
        {connectedDevices.length === 0 && offlineDevices.length === 0 && (
          <li className="px-2 py-6 text-center text-[11.5px] leading-relaxed text-ink-faint">
            No companion devices seen yet.
            <br />
            Pair from the Android app to watch it appear here.
          </li>
        )}
        {connectedDevices.map((d) => (
          <DeviceRow key={d.device_id} selected={selectedId === d.device_id} onClick={() => select(d.device_id)} device={d} now={now} />
        ))}
        {offlineDevices.map((d) => (
          <DeviceRow key={d.device_id} selected={selectedId === d.device_id} onClick={() => select(d.device_id)} device={d} now={now} offline />
        ))}
      </ul>

      {selected && (
        <div className="border-t border-line p-3">
          <DeviceDetail deviceId={selected.device_id} />
        </div>
      )}
    </GlassPanel>
  );
}

function DeviceRow({
  device,
  selected,
  onClick,
  now,
  offline,
}: {
  device: DeviceVM;
  selected: boolean;
  onClick: () => void;
  now: number;
  offline?: boolean;
}) {
  return (
    <li>
      <button
        type="button"
        onClick={onClick}
        aria-pressed={selected}
        className={cn(
          "flex w-full items-center gap-2.5 rounded-lg border px-2 py-1.5 text-left transition-colors",
          selected ? "border-accent/40 bg-accent/10" : "border-transparent hover:bg-surface-raised",
        )}
      >
        <StatusDot tone={offline ? "offline" : "online"} pulse={!offline} label={offline ? "Offline" : "Online"} />
        <span className={cn("flex h-8 w-8 items-center justify-center rounded-lg border", offline ? "border-line bg-surface-raised text-ink-faint" : "border-accent/25 bg-accent/10 text-accent-soft")}>
          <DeviceIcon platform={device.platform} />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[12.5px] font-medium text-ink">{device.display_name || device.device_id}</span>
          <span className="flex items-center gap-1 font-mono text-[10px] text-ink-faint">
            {offline ? (
              <WifiOff className="h-3 w-3" aria-hidden />
            ) : (
              <Wifi className="h-3 w-3" aria-hidden />
            )}
            {offline
              ? device.last_seen
                ? `offline · ${formatRelative(device.last_seen, now)}`
                : "offline"
              : `${device.platform} · ${formatLatency(device.latency_ms ?? 0)}`}
          </span>
        </span>
        <ChevronRight className={cn("h-3.5 w-3.5 shrink-0", selected ? "text-accent" : "text-ink-faint")} aria-hidden />
      </button>
    </li>
  );
}

function DeviceDetail({ deviceId }: { deviceId: string }) {
  const device = useDevicesStore((s) => s.devices[deviceId]);
  if (!device) return null;
  const fields: Array<[string, string]> = [
    ["ID", device.device_id],
    ["Name", device.display_name],
    ["Platform", device.platform],
    ["Type", device.device_type ?? "— (not exposed)"],
    ["State", device.connection_state],
    ...(device.connected_at != null ? [["Connected at", new Date(device.connected_at * 1000).toLocaleTimeString()] as [string, string]] : []),
    ...(device.last_seen != null ? [["Last seen", new Date(device.last_seen * 1000).toLocaleTimeString()] as [string, string]] : []),
    ...(device.latency_ms != null ? [["Latency", `${Math.round(device.latency_ms)} ms`] as [string, string]] : []),
    ["Capabilities", device.capabilities ? `${device.capabilities.length} listed` : "— (not exposed)"],
  ];
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-semibold uppercase tracking-wide text-ink-muted">Device details</h3>
        <Badge tone={device.connection_state === "connected" ? "mint" : "neutral"}>
          <Radio className="h-3 w-3" aria-hidden />
          {device.connection_state}
        </Badge>
      </div>
      <dl className="space-y-1 font-mono text-[10.5px]">
        {fields.map(([k, v]) => (
          <div key={k} className="flex justify-between gap-2">
            <dt className="shrink-0 text-ink-faint">{k}</dt>
            <dd className="min-w-0 truncate text-right text-ink-muted" title={v}>
              {v}
            </dd>
          </div>
        ))}
      </dl>
      <p className="pt-1 text-[10px] leading-relaxed text-ink-faint">
        Battery level, foreground app and device model are not exposed by the backend API; they are omitted rather than fabricated.
      </p>
    </div>
  );
}

export function AgentStatusCard() {
  const status = useAgentStore((s) => s.status);
  const agentOnline = useAgentStore((s) => s.agentOnline);
  const uptime = useAgentStore((s) => s.uptime);
  const retryCount = useAgentStore((s) => s.retryCount);
  const now = useNow();

  return (
    <div className="glass-panel flex items-center justify-between gap-3 p-3">
      <AgentOrb size={64} />
      <div className="min-w-0 flex-1 space-y-1 text-right">
        <div className="flex items-center justify-end gap-1.5">
          <StatusDot tone={status === "online" ? "online" : status === "reconnecting" ? "warn" : "offline"} pulse={status === "online"} />
          <span className="text-[12px] font-semibold text-ink">
            {agentOnline ? "Agent online" : "Agent offline"}
          </span>
        </div>
        <p className="font-mono text-[10px] text-ink-faint">
          {status === "reconnecting" ? `reconnecting${retryCount ? ` (${retryCount})` : ""}` : status}
        </p>
        <p className="font-mono text-[10px] text-ink-faint">
          {uptime != null ? `up ${Math.floor(uptime)}s` : `connected ${formatRelative(now - 3, now)}`}
        </p>
      </div>
    </div>
  );
}