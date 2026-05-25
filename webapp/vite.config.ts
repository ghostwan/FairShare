import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";
import path from "node:path";

// PWA install target: Safari on iOS (>= 16.4 for Web Push, >= 11.3 for
// generic install). We use the auto-update strategy so users always
// get the latest sync logic without a manual refresh — important when
// the wire format evolves and we can't ask a non-techie iPhone friend
// to clear their site data.
export default defineConfig({
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
      includeAssets: ["favicon.svg", "apple-touch-icon.png"],
      manifest: {
        name: "FairShare",
        short_name: "FairShare",
        description:
          "Partage de dépenses entre amis, paire avec l'app Android via QR-code.",
        theme_color: "#1976d2",
        background_color: "#ffffff",
        display: "standalone",
        orientation: "portrait",
        start_url: "/",
        scope: "/",
        lang: "fr",
        icons: [
          {
            src: "/icons/icon-192.png",
            sizes: "192x192",
            type: "image/png",
          },
          {
            src: "/icons/icon-512.png",
            sizes: "512x512",
            type: "image/png",
          },
          {
            src: "/icons/icon-maskable-512.png",
            sizes: "512x512",
            type: "image/png",
            purpose: "maskable",
          },
        ],
      },
      workbox: {
        // Worker payloads are dynamic and tiny; only precache the static
        // shell. Operations are pulled via fetch and intentionally not
        // cached (they're already persisted in IndexedDB anyway).
        globPatterns: ["**/*.{js,css,html,svg,png,ico,woff2}"],
        navigateFallback: "/index.html",
        navigateFallbackDenylist: [/^\/api\//, /^\/health$/],
      },
      devOptions: {
        enabled: false, // turn on temporarily when iterating on SW
      },
    }),
  ],
  server: {
    port: 5173,
    strictPort: false,
  },
  build: {
    target: "es2022",
    sourcemap: true,
  },
});
