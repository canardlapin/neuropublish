import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests", testMatch: /lifecycle\.spec\.mjs/,
  timeout: 120_000,
  use: { baseURL: "http://127.0.0.1:5173", browserName: "chromium" },
  webServer: { command: "npm run dev", url: "http://127.0.0.1:5173/spike.html", reuseExistingServer: true, timeout: 600_000 },
  reporter: [["list"]],
});
