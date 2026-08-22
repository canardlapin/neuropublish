import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests", testMatch: /e2e\.spec\.mjs/, timeout: 120_000,
  use: { baseURL: process.env.NP_BASE_URL || "http://127.0.0.1:8090", browserName: "chromium" },
  reporter: [["list"]],
});
