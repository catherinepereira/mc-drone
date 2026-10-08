import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import {
  BLUEMAP_PATH,
  DEV_BLUEMAP_PORT,
  DEV_BRIDGE_PORT,
  DEV_FRONTEND_PORT,
} from "./src/config.ts";

// the dashboard container reaches the game on the host through host.docker.internal
const bridgeHost = process.env.MCDRONE_BRIDGE_HOST ?? "127.0.0.1";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: DEV_FRONTEND_PORT,
    strictPort: true,
    // a Windows folder mounted into the container sends no change events, so edits only show up by polling
    watch: { usePolling: true },
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
      [BLUEMAP_PATH]: {
        target: `http://${bridgeHost}:${DEV_BLUEMAP_PORT}`,
        rewrite: (path) => "/" + path.slice(BLUEMAP_PATH.length),
        // tiles, settings, and the mod's script change while the game runs, revalidate instead of using a cached copy
        configure: (proxy) =>
          proxy.on("proxyRes", (res) => {
            res.headers["cache-control"] = "no-cache";
          }),
      },
    },
  },
});
