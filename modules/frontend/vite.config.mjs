import { defineConfig } from "vite";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";
import path from "node:path";

const prelinkedScalaJs = process.env.NP_SCALAJS_OUTPUT_DIR;

export default defineConfig({
  plugins: prelinkedScalaJs
    ? []
    : [scalaJSPlugin({ cwd: "../..", projectID: "frontend" })],
  resolve: prelinkedScalaJs
    ? {
        alias: [
          {
            find: "scalajs:main.js",
            replacement: path.resolve(prelinkedScalaJs, "main.js"),
          },
        ],
      }
    : undefined,
  base: "/",
  server: { host: "127.0.0.1", port: 5173, strictPort: true },
  preview: { port: 4173 },
  build: { outDir: "dist", target: "es2022", sourcemap: true, rollupOptions: { input: { main: "index.html", spike: "spike.html" } } }
});
