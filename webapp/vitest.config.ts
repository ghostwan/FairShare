import { defineConfig } from "vitest/config";
import path from "node:path";

// Tests run on jsdom because most code under test is browser-flavoured
// (Web Crypto, IndexedDB via fake-indexeddb, fetch). Pure crypto KATs
// don't need it but the cost of a single environment is negligible and
// keeps the config trivial.
export default defineConfig({
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: ["./tests/setup.ts"],
    include: ["src/**/*.test.ts", "tests/unit/**/*.test.ts"],
  },
});
