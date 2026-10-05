import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { DEV_BRIDGE_PORT, DEV_FRONTEND_PORT } from "./src/config.ts";

// the dashboard container reaches the game on the host through host.docker.internal
const bridgeHost = process.env.MCDRONE_BRIDGE_HOST ?? "127.0.0.1";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: DEV_FRONTEND_PORT,
    strictPort: true,
    proxy: {
      "/api": {
        target: `http://${bridgeHost}:${DEV_BRIDGE_PORT}`,
        changeOrigin: false,
      },
      "/ws": {
        target: `ws://${bridgeHost}:${DEV_BRIDGE_PORT}`,
        ws: true,
        changeOrigin: false,
      },
    },
  },
});
