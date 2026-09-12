import { Monitor, Bell, Info } from "lucide-react";
import { useContextStore } from "@/store/useContextStore";
import { GlassPanel } from "@/components/common/GlassPanel";
import { formatRelative } from "@/lib/format";
import { useNow } from "@/hooks/useHooks";

export function ContextPanel() {
  const notifications = useContextStore((s) => s.notifications);
  const windows = useContextStore((s) => s.windows);
  const windowsError = useContextStore((s) => s.windowsError);
  const windowsAt = useContextStore((s) => s.windowsAt);
  const now = useNow();

  const topWindows = Array.isArray(windows) ? windows.slice(0, 8) : [];

  return (
    <div className="flex shrink-0 flex-col gap-2">

      <GlassPanel
        title={
          <span className="flex items-center gap-1.5">
            <Monitor className="h-3.5 w-3.5 text-accent/70" aria-hidden />
            Windows
          </span>
        }
        className="shrink-0"
        pad={false}
      >
        <div className="space-y-1 p-2">
          {windowsError && (
            <p className="px-1 font-mono text-[10px] text-rose">{windowsError}</p>
          )}
          {!windowsError && windows == null && (
            <p className="px-1 text-[11px] leading-relaxed text-ink-faint">
              Open windows are fetched from the backend (core.window:list). The "active window" is not exposed by the API, so it is not shown.
            </p>
          )}
          {topWindows.length > 0 && (
            <ul className="space-y-1">
              {topWindows.map((w, i) => {
                const title = String((w as { title?: string }).title ?? (w as { window_title?: string }).window_title ?? "Window");
                const hwnd = (w as { hwnd?: number | string }).hwnd;
                return (
                  <li key={String(hwnd ?? i)} className="flex items-center gap-2 rounded-md px-1 py-1 hover:bg-surface-raised/60">
                    <span className="h-1 w-1 shrink-0 rounded-full bg-accent/60" aria-hidden />
                    <span className="min-w-0 flex-1 truncate text-[11.5px] text-ink-muted" title={title}>
                      {title}
                    </span>
                  </li>
                );
              })}
            </ul>
          )}
          {windowsAt && (
            <p className="px-1 pt-1 font-mono text-[10px] text-ink-faint">
              snapshot {formatRelative(windowsAt / 1000, now)} · refreshed every 60s
            </p>
          )}
        </div>
      </GlassPanel>

      <GlassPanel
        title={
          <span className="flex items-center gap-1.5">
            <Bell className="h-3.5 w-3.5 text-amber/70" aria-hidden />
            Notifications
          </span>
        }
        className="min-h-0 flex-1"
        pad={false}
      >
        {notifications.length === 0 ? (
          <p className="p-3 text-[11.5px] leading-relaxed text-ink-faint">
            No synced notifications yet. Notifications pushed by paired devices (notification_received) appear here.
          </p>
        ) : (
          <ul className="scroll-thin max-h-64 space-y-1.5 overflow-y-auto p-2">
            {notifications.map((n) => (
              <li key={n.id} className="rounded-lg border border-line bg-surface-raised/70 px-2 py-1.5">
                <div className="flex items-baseline justify-between gap-2">
                  <span className="truncate text-[11.5px] font-medium text-ink">{n.title || n.packageName}</span>
                  <span className="shrink-0 font-mono text-[10px] text-ink-faint">{formatRelative(n.ts, now)}</span>
                </div>
                {n.text && <p className="mt-0.5 line-clamp-2 text-[10.5px] text-ink-muted">{n.text}</p>}
                <p className="mt-0.5 truncate font-mono text-[9.5px] text-ink-faint">{n.packageName}</p>
              </li>
            ))}
          </ul>
        )}
      </GlassPanel>

      <p className="flex items-start gap-1.5 px-1 text-[10px] leading-relaxed text-ink-faint">
        <Info className="mt-0.5 h-3 w-3 shrink-0" aria-hidden />
        Active window, focused UI and device context records are internal to the backend Context Engine and not exposed over HTTP.
      </p>
    </div>
  );
}