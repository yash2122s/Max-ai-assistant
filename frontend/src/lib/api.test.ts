import { describe, it, expect, vi, beforeEach } from "vitest";
import { api, setRemoteToken, getRemoteToken } from "@/lib/api";

describe("api client", () => {
  beforeEach(() => {
    setRemoteToken(null);
    vi.restoreAllMocks();
  });

  it("unwraps success envelopes", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ success: true, data: { agent_version: "3.0.0" } }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const status = await api.getStatus();
    expect(status.agent_version).toBe("3.0.0");
    expect(fetchMock).toHaveBeenCalledWith("/api/status", expect.anything());
  });

  it("throws on error envelopes with the backend message", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 403,
        json: async () => ({
          success: false,
          error: { code: "FORBIDDEN", message: "Cross-Origin request blocked" },
        }),
      }),
    );
    await expect(api.getStatus()).rejects.toMatchObject({
      code: "FORBIDDEN",
      message: "Cross-Origin request blocked",
      status: 403,
    });
  });

  it("throws a readable error when the response is not JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        json: async () => {
          throw new Error("not json");
        },
      }),
    );
    await expect(api.getStatus()).rejects.toThrow(/HTTP 500/);
  });

  it("posts chat with the backend schema", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: { user_message: "hi", max_response: "hello" },
      }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const res = await api.postChat("hi");
    expect(res.max_response).toBe("hello");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/chat");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ message: "hi" });
  });

  it("keeps the remote token in memory only — never localStorage", async () => {
    setRemoteToken("device_token_abc123");
    expect(getRemoteToken()).toBe("device_token_abc123");
    expect(localStorage.getItem("max_token")).toBeNull();
    expect(sessionStorage.getItem("max_token")).toBeNull();
    expect(JSON.stringify(Object.keys(window.localStorage))).not.toContain("token");
  });

  it("does not leak the token into chat bodies", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ success: true, data: { user_message: "", max_response: "" } }),
    });
    vi.stubGlobal("fetch", fetchMock);
    setRemoteToken("secret_token_value");
    await api.postChat("hello");
    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body).not.toHaveProperty("token");
    expect(JSON.stringify(body)).not.toContain("secret_token_value");
  });
});