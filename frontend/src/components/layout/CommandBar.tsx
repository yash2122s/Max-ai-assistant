import { useState } from "react";
import { Send, Loader2, Sparkles } from "lucide-react";
import { api } from "@/lib/api";
import { useChatStore } from "@/store/useChatStore";
import { useAgentStore } from "@/store/useAgentStore";
import { useActivityStore } from "@/store/useActivityStore";
import { extractErrorMessage } from "@/lib/format";

export function CommandBar() {
  const [input, setInput] = useState("");
  const pending = useChatStore((s) => s.pending);
  const lastError = useChatStore((s) => s.lastError);
  const online = useAgentStore((s) => s.status === "online");

  async function submit(text: string) {
    const message = text.trim();
    if (!message || pending) return;
    const chat = useChatStore.getState();
    chat.setPending(true);
    chat.setError(null);
    chat.addUser(message);
    setInput("");
    try {
      const res = await api.postChat(message);
      chat.addMax(res.max_response || "Done.");
    } catch (err) {
      const msg = extractErrorMessage(err);
      chat.setError(msg);
      useActivityStore.getState().push({
        id: `chat_err_${Date.now()}`,
        kind: "error",
        eventType: "chat_error",
        title: `Chat failed: ${msg}`,
        ts: Date.now() / 1000,
        level: "error",
      });
    } finally {
      chat.setPending(false);
    }
  }

  return (
    <div className="shrink-0 border-t border-line bg-surface/70 px-3 py-2 backdrop-blur-md">
      {lastError && (
        <p className="mb-1.5 px-1 font-mono text-[11px] text-rose" role="alert">
          {lastError}
        </p>
      )}
      <form
        className="flex items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          void submit(input);
        }}
      >
        <div className="flex min-h-[38px] flex-1 items-center gap-2 rounded-xl border border-line bg-surface-raised px-3 focus-within:border-accent/50">
          <Sparkles className="h-4 w-4 shrink-0 text-accent/70" aria-hidden />
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                void submit(input);
              }
            }}
            rows={1}
            placeholder={
              online
                ? "Ask MAX something… (Enter to send, Shift+Enter for newline)"
                : "Backend offline — command unavailable"
            }
            disabled={!online || pending}
            aria-label="Command to MAX"
            className="max-h-24 min-h-[22px] flex-1 resize-none bg-transparent py-1 text-sm text-ink placeholder:text-ink-faint focus:outline-none disabled:opacity-50"
          />
        </div>
        <button
          type="submit"
          disabled={!online || pending || !input.trim()}
          aria-label="Send command"
          className="flex h-[38px] w-[38px] shrink-0 items-center justify-center rounded-xl border border-accent/30 bg-accent/15 text-accent-soft transition-colors hover:bg-accent/25 disabled:opacity-40 disabled:hover:bg-accent/15"
        >
          {pending ? (
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          ) : (
            <Send className="h-4 w-4" aria-hidden />
          )}
        </button>
      </form>
    </div>
  );
}