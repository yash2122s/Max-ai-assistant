import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";

const BACKEND_HTTP_PORT = 9001;
const backendOrigin = process.env.MAX_BACKEND || `http://127.0.0.1:${BACKEND_HTTP_PORT}`;

// The backend's /api/tool route only accepts requests whose Origin is in
// the allowlist (localhost:9001, 127.0.0.1:9001, ""). The Vite dev server
// runs on a different origin (e.g. localhost:5173), so all /api traffic is
// proxied through the backend origin to keep Origin allowlist compliance
// and to avoid CORS/preflight problems with POST /api/tool.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: false,
    host: "127.0.0.1",
    proxy: {
      "/api": {
        target: backendOrigin,
        changeOrigin: true,
      },
      "/metrics": {
        target: backendOrigin,
        changeOrigin: true,
      },
      "/health": {
        target: backendOrigin,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: "dist",
    sourcemap: false,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ["react", "react-dom"],
          motion: ["framer-motion"],
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
    include: ["src/**/*.test.{ts,tsx}"],
  },
});