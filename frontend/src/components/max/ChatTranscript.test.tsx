import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ChatTranscript } from "@/components/max/ChatTranscript";
import { useChatStore } from "@/store/useChatStore";
import { useActivityStore, handleBusEvent } from "@/store/useActivityStore";
import type { BusEvent } from "@/lib/types";

function ev(id: string, type: string, payload: Record<string, unknown>): BusEvent {
  return { schema_version: 1, id, type, timestamp: 1700000000, payload };
}

describe("ChatTranscript", () => {
  it("renders user and max messages", () => {
    useChatStore.getState().addUser("open notepad");
    useChatStore.getState().addMax("Opening Notepad now.");
    render(<ChatTranscript />);
    expect(screen.getByText("open notepad")).toBeInTheDocument();
    expect(screen.getByText("Opening Notepad now.")).toBeInTheDocument();
  });

  it("renders a running tool card inline", () => {
    handleBusEvent(ev("e1", "tool_requested", { tool: "cmd", action: "echo", arguments: { message: "hi" } }));
    handleBusEvent(ev("e2", "tool_progress", { tool: "cmd", action: "echo", message: "running…" }));
    render(<ChatTranscript />);
    expect(screen.getByRole("status", { name: /cmd\/echo running/i })).toBeInTheDocument();
  });

  it("shows an empty state when there is nothing yet", () => {
    render(<ChatTranscript />);
    expect(screen.getByText(/No activity yet/i)).toBeInTheDocument();
  });

  it("surfaces chat events arriving over SSE", () => {
    handleBusEvent(ev("e1", "chat_message", { role: "max", text: "streamed reply", timestamp: 1700000000 }));
    render(<ChatTranscript />);
    expect(screen.getByText("streamed reply")).toBeInTheDocument();
    void useActivityStore;
  });
});