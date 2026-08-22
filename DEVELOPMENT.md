# Developing Neuropublish

## Toolchain

Scala 3.7.4, sbt 1.12.14, JDK 21 (temurin in CI), Node ≥ 22 for the Scala.js
test environment and the Vite frontend. `sbt` fetches its own launcher version
from `project/build.properties`.

## Commands

| Command | What it does |
| --- | --- |
| `sbt npCheck` | scalafmt check, compile every module on every platform, run every test — the CI gate |
| `sbt npCompile` / `sbt npTest` | the two halves of `npCheck` |
| `sbt npFormat` | format |

Aliases are `np`-prefixed because ScalaFIM's build, loaded by source pin into the same sbt session, defines its own `checkAll`.
| `sbt backend/run` | control plane on http://127.0.0.1:8080 (`/api/v1/health`) |
| `sbt "publisherCli/run validate modules/conformance/fixtures/reference"` | `npub validate` on the reference bundle |
| `cd modules/frontend && npm install && npm run dev` | Vite dev server with live Scala.js linking |
| `cd modules/frontend && npm run test:browser` | Playwright lifecycle tests in Chromium against `spike.html` (starts Vite itself) |
| `scripts/e2e.sh` | Stage 1 + 3 proof: build frontend, start a backend on a temp data dir, `npub push` the reference bundle, assert the stale-parent rejection and digest, render in Chromium |

## Running the thin spine by hand

```
sbt backend/run                       # http://127.0.0.1:8080, token "dev-token", project rotman/sherlock
NP_TOKEN=dev-token sbt "publisherCli/run push modules/conformance/fixtures/reference --project rotman/sherlock"
cd modules/frontend && npm run dev    # http://127.0.0.1:5173/w/rotman/p/sherlock (talks to :8080)
```

Set `NP_STATIC_DIR=modules/frontend/dist` (after `npm run build`) to have the
backend serve the page itself, which is what the `view` URL printed by `push`
expects.

## Upstream Scala libraries

ScalaFIM (and through it Intaglio, image4s, zarr4s, …) is consumed as an exact
git revision (`project/Versions.scala`, `scalafimRevision`). The first load
clones and compiles that tree; later loads are cached under `~/.sbt/1.0/staging`.

To work against a local checkout while fixing something upstream:

```
sbt -Dneuropublish.scalafim.build=/Users/you/code/scala/scalafim …
```

Land the fix in ScalaFIM, then bump `scalafimRevision`. Never rely on an
implicit sibling checkout; a clean consumer must resolve from the pin alone.

## Module map

See `docs/architecture.md`. Stage 0 scaffolds `protocol-core`, `protocol-json`,
`viewer-state`, `api-contract`, `rendition` (cross JVM/JS), `viewer-laminar`,
`frontend` (JS), and `backend`, `publisher-cli`, `conformance` (JVM). `domain`,
`semantic-registry`, `persistence`, and `ingestion` arrive with Stages 1–2.

## Fixtures

`modules/conformance/fixtures/reference` is the hand-written reference bundle;
`fixtures/invalid/*.json` each pair with a `.expect` file naming the admission
error the manifest must produce.
