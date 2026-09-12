import { describe, it, expect } from "vitest";
import { formatUptime, formatRelative, formatArgs, maskSecretsLikeBackend, extractErrorMessage } from "@/lib/format";

describe("format helpers", () => {
  it("formats uptime", () => {
    expect(formatUptime(45)).toBe("0m 45s");
    expect(formatUptime(3725)).toBe("1h 2m");
    expect(formatUptime(93784)).toBe("1d 2h 3m");
    expect(formatUptime(NaN)).toBe("—");
  });

  it("formats relative time", () => {
    expect(formatRelative(1000, 1002)).toBe("just now");
    expect(formatRelative(1000, 1030)).toBe("30s ago");
    expect(formatRelative(1000, 1000 + 3600)).toBe("1h ago");
  });

  it("masks secrets-like strings", () => {
    const masked = maskSecretsLikeBackend("api_key=\"sk-abcDEF1234567890\" bearer abcd.efgh.ijkl");
    expect(masked).not.toContain("sk-abcDEF1234567890");
    expect(masked).not.toContain("abcd.efgh.ijkl");
    expect(masked).toContain("***");
  });

  it("extracts error messages", () => {
    expect(extractErrorMessage("boom")).toBe("boom");
    expect(extractErrorMessage({ message: "x" })).toBe("x");
    expect(extractErrorMessage(null)).toBe("Unknown error");
  });

  it("renders args compactly", () => {
    expect(formatArgs({ path: "C:/temp", quiet: true })).toContain("path=C:/temp");
    expect(formatArgs({})).toBe("");
  });
});