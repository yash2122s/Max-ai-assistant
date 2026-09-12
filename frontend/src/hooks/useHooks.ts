import { useEffect, useRef, useState } from "react";

export function useInterval(callback: () => void, delayMs: number | null): void {
  const savedCallback = useRef(callback);
  savedCallback.current = callback;
  useEffect(() => {
    if (delayMs === null) return;
    const id = setInterval(() => savedCallback.current(), delayMs);
    return () => clearInterval(id);
  }, [delayMs]);
}

/** Ticks once per second; returns current unix time in seconds. */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState<number>(() => Date.now() / 1000);
  useInterval(() => setNow(Date.now() / 1000), intervalMs);
  return now;
}

/** Auto-scroll a container to the bottom when `deps` change and pinned. */
export function useAutoScroll<T extends HTMLElement>(deps: unknown[]): React.RefObject<T> {
  const ref = useRef<T>(null);
  const pinnedRef = useRef(true);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const onScroll = () => {
      const atBottom =
        el.scrollHeight - el.scrollTop - el.clientHeight < 48;
      pinnedRef.current = atBottom;
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => el.removeEventListener("scroll", onScroll);
  }, []);

  useEffect(() => {
    const el = ref.current;
    if (el && pinnedRef.current) {
      el.scrollTop = el.scrollHeight;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return ref;
}