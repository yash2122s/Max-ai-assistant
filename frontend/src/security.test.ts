import { describe, it, expect } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, resolve } from "node:path";

const SRC = resolve(process.cwd(), "src");

function listTsFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry === "test") continue;
      out.push(...listTsFiles(full));
    } else if (/\.(ts|tsx)$/.test(entry) && !/\.test\.(ts|tsx)$/.test(entry)) {
      out.push(full);
    }
  }
  return out;
}

describe("client security posture", () => {
  const files = listTsFiles(SRC);

  it("never references the backend API key", () => {
    for (const f of files) {
      const content = readFileSync(f, "utf8");
      expect(content, `GEMINI_API_KEY referenced in ${f}`).not.toContain("GEMINI_API_KEY");
    }
  });

  it("never persists tokens or secrets to storage", () => {
    for (const f of files) {
      const content = readFileSync(f, "utf8");
      expect(content, `localStorage usage in ${f}`).not.toMatch(/localStorage\./);
      expect(content, `sessionStorage usage in ${f}`).not.toMatch(/sessionStorage\./);
    }
  });

  it("does not hardcode bearer-style secret patterns", () => {
    for (const f of files) {
      const content = readFileSync(f, "utf8");
      expect(content, `suspicious secret literal in ${f}`).not.toMatch(/sk-[A-Za-z0-9]{20,}/);
    }
  });

  it("contains no reference to the .env file", () => {
    for (const f of files) {
      const content = readFileSync(f, "utf8");
      expect(content, `.env reference in ${f}`).not.toContain(".env");
    }
  });
});