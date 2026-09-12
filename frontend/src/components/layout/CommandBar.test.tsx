import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CommandBar } from "@/components/layout/CommandBar";
import { useAgentStore } from "@/store/useAgentStore";
import { useChatStore } from "@/store/useChatStore";
import { api } from "@/lib/api";

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api } };
});

beforeEach(() => {
  vi.restoreAllMocks();
  useAgentStore.getState().setOnline();
});

describe("CommandBar", () => {
  it("sends a command via /api/chat and renders the reply", async () => {
    const user = userEvent.setup();
    const spy = vi
      .spyOn(api, "postChat")
      .mockResolvedValue({ user_message: "open calculator", max_response: "Opening Calculator." });
    render(<CommandBar />);

    const input = screen.getByRole("textbox", { name: /command to max/i });
    await user.type(input, "open calculator");
    await user.keyboard("{Enter}");

    expect(spy).toHaveBeenCalledWith("open calculator");
    await waitFor(() => {
      const msgs = useChatStore.getState().messages;
      expect(msgs.some((m) => m.role === "user" && m.text === "open calculator")).toBe(true);
      expect(msgs.some((m) => m.role === "max" && m.text === "Opening Calculator.")).toBe(true);
    });
  });

  it("shows a readable error when the backend chat fails", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "postChat").mockRejectedValue(new Error("GEMINI_API_KEY missing"));
    render(<CommandBar />);

    await user.type(screen.getByRole("textbox", { name: /command to max/i }), "hi");
    await user.keyboard("{Enter}");

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(/GEMINI_API_KEY missing/);
    });
  });

  it("disables input while the backend is offline", () => {
    useAgentStore.getState().setOffline();
    render(<CommandBar />);
    expect(screen.getByRole("textbox", { name: /command to max/i })).toBeDisabled();
  });
});