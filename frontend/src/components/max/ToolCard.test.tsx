import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ToolCard } from "@/components/max/ToolCard";
import type { ToolTrace } from "@/lib/types";

const base: ToolTrace = {
  key: "cmd/echo",
  tool: "cmd",
  action: "echo",
  args: { message: "hello world" },
  status: "RUNNING",
  startedAt: 1700000000,
};

describe("ToolCard", () => {
  it("renders RUNNING state with progress", () => {
    render(<ToolCard trace={{ ...base, progress: "running…" }} />);
    expect(screen.getByRole("status", { name: /cmd\/echo running/i })).toBeInTheDocument();
    expect(screen.getByText("RUNNING")).toBeInTheDocument();
  });

  it("renders SUCCESS state with output", () => {
    render(<ToolCard trace={{ ...base, status: "SUCCESS", output: "hello world", finishedAt: 1700000001 }} />);
    expect(screen.getByRole("status", { name: /cmd\/echo success/i })).toBeInTheDocument();
    expect(screen.getAllByText(/hello world/).length).toBeGreaterThan(0);
  });

  it("renders FAILED state with error", () => {
    render(
      <ToolCard
        trace={{
          ...base,
          status: "FAILED",
          error: "Access denied: Path outside sandbox",
          finishedAt: 1700000001,
        }}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent(/Access denied/);
  });
});