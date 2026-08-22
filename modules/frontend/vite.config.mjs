import { defineConfig } from "vite";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";

export default defineConfig({
  plugins: [scalaJSPlugin({ cwd: "../..", projectID: "frontend" })],
  base: "./",
  server: { host: "127.0.0.1", port: 5173, strictPort: true },
  preview: { port: 4173 },
  build: { outDir: "dist", target: "es2020", sourcemap: true, rollupOptions: { input: { main: "index.html", spike: "spike.html" } } }
});
