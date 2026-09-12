import { create } from "zustand";
import type { NotificationPayload, NotificationVM } from "@/lib/types";
import { MAX_NOTIFICATIONS_CAP } from "@/lib/constants";

interface ContextState {
  notifications: NotificationVM[];
  windows: unknown[] | null;
  windowsAt: number | null;
  windowsError: string | null;

  addNotification: (payload: NotificationPayload) => void;
  setWindows: (windows: unknown[] | null) => void;
  setWindowsError: (message: string | null) => void;
  clear: () => void;
}

let notifSeq = 0;
function nextNotifId(): string {
  notifSeq += 1;
  return `notif_${Date.now().toString(36)}_${notifSeq}`;
}

export const useContextStore = create<ContextState>((set) => ({
  notifications: [],
  windows: null,
  windowsAt: null,
  windowsError: null,

  addNotification: (payload) =>
    set((state) => {
      const vm: NotificationVM = {
        id: nextNotifId(),
        deviceId: payload.device_id,
        packageName: payload.package_name,
        title: payload.title,
        text: payload.text,
        ts: payload.timestamp ? payload.timestamp / 1000 : Date.now() / 1000,
      };
      return {
        notifications: [vm, ...state.notifications].slice(0, MAX_NOTIFICATIONS_CAP),
      };
    }),

  setWindows: (windows) =>
    set({ windows, windowsAt: Date.now(), windowsError: null }),

  setWindowsError: (message) => set({ windowsError: message }),

  clear: () => set({ notifications: [], windows: null, windowsAt: null, windowsError: null }),
}));