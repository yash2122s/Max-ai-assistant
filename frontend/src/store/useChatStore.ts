import { create } from "zustand";
import type { ChatMessagePayload, ChatMessageVM } from "@/lib/types";
import { MAX_CHAT_CAP } from "@/lib/constants";

interface ChatState {
  messages: ChatMessageVM[];
  pending: boolean;
  lastError: string | null;

  append: (msg: ChatMessageVM) => void;
  applyChatMessage: (payload: ChatMessagePayload) => void;
  addUser: (text: string) => void;
  addMax: (text: string) => void;
  setPending: (pending: boolean) => void;
  setError: (message: string | null) => void;
  clear: () => void;
}

let chatSeq = 0;
function nextId(prefix: string): string {
  chatSeq += 1;
  return `${prefix}_${Date.now().toString(36)}_${chatSeq}`;
}

export const useChatStore = create<ChatState>((set) => ({
  messages: [],
  pending: false,
  lastError: null,

  append: (msg) =>
    set((state) => ({
      messages: [...state.messages, msg].slice(-MAX_CHAT_CAP),
    })),

  applyChatMessage: (payload) =>
    set((state) => {
      const role: ChatMessageVM["role"] =
        payload.role === "max" || payload.role === "user" ? payload.role : "system";
      const text = payload.text ?? "";
      const ts = payload.timestamp || Date.now() / 1000;
      // Dedupe: an optimistic client add and the echoed backend event may both
      // arrive within seconds for the same message.
      const lastSameRole = [...state.messages].reverse().find((m) => m.role === role);
      if (lastSameRole && lastSameRole.text === text && Math.abs(lastSameRole.ts - ts) < 3) {
        return {};
      }
      const msg: ChatMessageVM = { id: nextId(`chat_${role}`), role, text, ts };
      return { messages: [...state.messages, msg].slice(-MAX_CHAT_CAP) };
    }),

  addUser: (text) =>
    set((state) => {
      const msg: ChatMessageVM = {
        id: nextId("chat_user"),
        role: "user",
        text,
        ts: Date.now() / 1000,
      };
      return { messages: [...state.messages, msg].slice(-MAX_CHAT_CAP) };
    }),

  addMax: (text) =>
    set((state) => {
      const msg: ChatMessageVM = {
        id: nextId("chat_max"),
        role: "max",
        text,
        ts: Date.now() / 1000,
      };
      return { messages: [...state.messages, msg].slice(-MAX_CHAT_CAP) };
    }),

  setPending: (pending) => set({ pending }),
  setError: (message) => set({ lastError: message }),
  clear: () => set({ messages: [] }),
}));