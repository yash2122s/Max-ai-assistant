import type { Config } from "tailwindcss";
import animate from "tailwindcss-animate";

export default {
  darkMode: ["class"],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        background: "hsl(230 42% 4%)",
        surface: {
          DEFAULT: "hsl(228 30% 7%)",
          raised: "hsl(227 28% 10%)",
          hover: "hsl(227 28% 13%)",
        },
        line: {
          DEFAULT: "hsl(226 24% 17%)",
          strong: "hsl(225 22% 24%)",
        },
        accent: {
          DEFAULT: "#38bdf8",
          dim: "rgba(56, 189, 248, 0.14)",
          soft: "#7dd3fc",
        },
        mint: {
          DEFAULT: "#34d399",
          dim: "rgba(52, 211, 153, 0.14)",
        },
        violet: {
          DEFAULT: "#a78bfa",
          dim: "rgba(167, 139, 250, 0.14)",
        },
        amber: {
          DEFAULT: "#fbbf24",
          dim: "rgba(251, 191, 36, 0.14)",
        },
        rose: {
          DEFAULT: "#f87171",
          dim: "rgba(248, 113, 113, 0.14)",
        },
        ink: {
          DEFAULT: "#e6ecf5",
          muted: "#93a0b8",
          faint: "#5b687f",
        },
      },
      fontFamily: {
        sans: [
          "-apple-system",
          "BlinkMacSystemFont",
          "Segoe UI",
          "Inter",
          "Roboto",
          "Helvetica Neue",
          "Arial",
          "sans-serif",
        ],
        mono: [
          "ui-monospace",
          "SFMono-Regular",
          "Menlo",
          "Consolas",
          "Cascadia Code",
          "monospace",
        ],
      },
      borderRadius: {
        xl: "0.875rem",
        "2xl": "1.125rem",
      },
      boxShadow: {
        glow: "0 0 24px -4px rgba(56, 189, 248, 0.45)",
        "glow-soft": "0 0 14px -6px rgba(56, 189, 248, 0.35)",
        panel: "0 10px 34px -16px rgba(0, 0, 0, 0.7)",
      },
      keyframes: {
        "pulse-ring": {
          "0%": { transform: "scale(0.85)", opacity: "0.7" },
          "70%": { transform: "scale(1.6)", opacity: "0" },
          "100%": { transform: "scale(1.6)", opacity: "0" },
        },
        "pulse-ring-slow": {
          "0%": { transform: "scale(0.9)", opacity: "0.5" },
          "100%": { transform: "scale(1.9)", opacity: "0" },
        },
        shimmer: {
          "0%": { backgroundPosition: "-200% 0" },
          "100%": { backgroundPosition: "200% 0" },
        },
        "fade-up": {
          "0%": { opacity: "0", transform: "translateY(6px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
      },
      animation: {
        "pulse-ring": "pulse-ring 1.6s cubic-bezier(0.2, 0.6, 0.4, 1) infinite",
        "pulse-ring-slow": "pulse-ring-slow 2.4s ease-out infinite",
        "fade-up": "fade-up 0.35s ease-out both",
        shimmer: "shimmer 2.2s linear infinite",
      },
      transitionTimingFunction: {
        smooth: "cubic-bezier(0.22, 1, 0.36, 1)",
      },
    },
  },
  plugins: [animate],
} satisfies Config;