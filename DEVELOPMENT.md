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
sbt backend/run                       # http://127.0.0.1:8080, project rotman/sherlock
sbt "publisherCli/run login"          # prints a URL and code; approve it in the browser, once
sbt "publisherCli/run push modules/conformance/fixtures/reference --project rotman/sherlock"
cd modules/frontend && npm run dev    # http://127.0.0.1:5173/w/rotman/p/sherlock (talks to :8080)
```

`npub login` stores a user token in `$NPUB_CONFIG_DIR/credentials.json`
(default `~/.config/npub/credentials.json`, mode 0600), keyed by `--server`.
`push` resolves its bearer as `--token` (discouraged) → `NP_TOKEN` → that file.
Batch jobs should not use a personal login: create a project-scoped credential
with `npub credential create --project rotman/sherlock --name hpc-nightly` and
export its one-time secret as `NP_TOKEN`. `npub whoami` and `npub logout`
inspect and clear the stored entry.

Set `NP_STATIC_DIR=modules/frontend/dist` (after `npm run build`) to have the
backend serve the page itself, which is what the `view` URL printed by `push`
expects.

### Backend environment (Stage 4)

| Variable | Default | Meaning |
| --- | --- | --- |
| `NP_DATA_DIR` | `data` | Root of every local store (layout below) |
| `NP_PORT` | `8080` | Listen port |
| `NP_BASE_URL` | `http://127.0.0.1:$NP_PORT` | The public origin the server is reached at: used for share URLs (`{base}/s/{secret}`), the device-flow `verificationUri*`, and rendition URLs. Set it behind a reverse proxy; an `https://` value marks the session cookie `Secure`. |
| `NP_WORKSPACE` / `NP_PROJECT` | `rotman` / `sherlock` | Bootstrap workspace and project (ADR 0004: one workspace in the alpha) |
| `NP_OWNER_EMAIL` / `NP_OWNER_PASSWORD` | `owner@example.org` / `owner-dev-password` | Local identity-provider user created as `owner` of the bootstrap workspace on first start; `scripts/e2e.sh` signs in with these |
| `NP_STATIC_DIR` | unset | Built frontend to serve with SPA fallback |
| `NP_S3_BUCKET` | unset | S3 mode when set: objects and renditions live in this bucket, uploads and rendition reads are presigned. With `NP_S3_ENDPOINT` (MinIO or any S3-compatible service; unset = AWS), `NP_S3_REGION` (`us-east-1`), `NP_S3_ACCESS_KEY` / `NP_S3_SECRET_KEY` (unset = SDK default chain), `NP_S3_PATH_STYLE=true` (MinIO). Unset = objects under `<data>/objects`, proxied through the control plane. |
| `NP_INGESTION` | `inline` | `inline` derives renditions inside the commit (an unreadable asset fails the push); `worker` enqueues and returns — run `scripts/worker.sh` beside the backend, the revision shows `ingestion.status` pending/running/ready/failed |
| `NP_LEGACY_TOKEN` | unset | **Deprecated.** The Stage 1 static bearer token (server side; the CLI's `NP_TOKEN` is unrelated). When set it still publishes and reads everywhere with no identity; leave it unset except for legacy clients. Removed once every client uses `npub login` or a publisher credential. |

Projects are private: every read needs a signed-in workspace member (session
cookie `np_session`, 24 h, HttpOnly, SameSite=Lax; or an `npub login` user
token, 30 days, revoked by `npub logout` / `POST auth/logout` or all at once by
`DELETE auth/tokens`) or that project's publisher credential. Viewers read only:
saving views and minting share links need owner/admin/member. Credential and
member management (`POST/GET workspaces/{ws}/members`) and the audit log need an
owner or admin. A non-member asking about a specific revision, view, link, or
credential gets the same 404 as for one that does not exist. Share links can
only be minted for `sensitivity: group-level` revisions and carry a presentation
subset of the manifest (no provenance, no method payloads, no open records).
`/api/v1/share/{secret}` and its rendition routes are the only anonymous reads. Sessions and device codes live in the
server that issued them (device codes in memory).

### PostgreSQL (Stage 2)

Set `NP_DATABASE_URL` and every record store (projects, revisions, users,
members, sessions, tokens, credentials, views, links, audit, the read model)
moves from the JSON files to PostgreSQL; unset, the local-fs layout below stays
the default (`scripts/e2e.sh` uses it). Objects and renditions remain under
`NP_DATA_DIR` either way until the S3 object store lands.

| Variable | Default | Meaning |
| --- | --- | --- |
| `NP_DATABASE_URL` | unset | JDBC URL, e.g. `jdbc:postgresql://127.0.0.1:5432/neuropublish`. When set, Flyway runs `modules/persistence/src/main/resources/db/migration/V*.sql` at start. |
| `NP_DATABASE_USER` / `NP_DATABASE_PASSWORD` | `neuropublish` / empty | Connection credentials |
| `NP_DATABASE_POOL` | `8` | Hikari pool size |

A local database for development, no compose file needed:

```
docker run -d --name np-postgres -p 5432:5432 \
  -e POSTGRES_USER=neuropublish -e POSTGRES_PASSWORD=neuropublish -e POSTGRES_DB=neuropublish \
  postgres:16-alpine
export NP_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/neuropublish
export NP_DATABASE_USER=neuropublish NP_DATABASE_PASSWORD=neuropublish
sbt backend/run                       # migrates, then serves over PostgreSQL
sbt "backend/run reindex"             # rebuild analyses / result_fields / revision_assets from the stored manifests, then exit
```

`reindex` walks every revision, reads its manifest bytes from the object store
by digest, and re-projects the read model (the manifest is the source of truth;
`derived_representations`, the worker's output, is left alone). It exits 1 and
names the revisions whose manifest bytes were missing.

The PostgreSQL suites (`persistence/test`, and `PgRoutesSuite` /
`PgStage4Suite` in `backend/test`, which run the route suites over the
database-backed server) use Testcontainers and a fresh database per test. They
`assume` Docker: without a reachable daemon they report as skipped, never failed.

Schema notes (ADR 0004): every child table carries `workspace_id` with a
composite foreign key `(workspace_id, project_id) → projects`; `stored_objects`
(physical bytes) is separate from `workspace_assets` (who may reference a
digest) and `catalog_assets` (public templates); `ingestion_jobs` is the
worker's queue — a commit enqueues one `pending` row per revision, a worker
claims with `UPDATE … WHERE id = (SELECT … WHERE status = 'pending' … FOR UPDATE
SKIP LOCKED) RETURNING …` and ends on `ready` or `failed` (or back to `pending`
to retry). `neuropublish.persistence.IngestionJobs` is that contract in code.

Data-dir layout (the default, `NP_DATABASE_URL` unset) — one JSON document per
record, secrets stored as SHA-256 only, passwords as salted PBKDF2-HMAC-SHA256:

```
<data>/projects/<ws>/<project>.json    revisions/<rev>.json    objects/sha256/..    renditions/<rev>/
<data>/users/<userId>.json             users/identities/<issuer>/<sha256(subject)>.json
<data>/members/<ws>.json               workspace members with roles owner|admin|member|viewer
<data>/sessions/<sha256(cookie)>.json  tokens/<sha256(user token)>.json
<data>/credentials/<id>.json           credentials/by-hash/<sha256(secret)>.json
<data>/views/<viewId>.json             links/<id>.json    links/by-hash/<sha256(secret)>.json
<data>/provenance/<rev>.json           cached provenance read model (a pure function of the manifest)
<data>/audit/<ws>.jsonl                append-only audit log (login, publish, share, credential, device approve)
```

### Object store, ingestion worker, and cleanup (Stage 2)

Local MinIO:

```
docker run --rm -p 9000:9000 -e MINIO_ROOT_USER=minio -e MINIO_ROOT_PASSWORD=minio-secret minio/minio server /data
export NP_S3_BUCKET=neuropublish NP_S3_ENDPOINT=http://127.0.0.1:9000 NP_S3_ACCESS_KEY=minio \
       NP_S3_SECRET_KEY=minio-secret NP_S3_PATH_STYLE=true NP_INGESTION=worker
sbt backend/run            # creates the bucket if absent; commit enqueues ingestion
scripts/worker.sh          # = sbt ingestion/run; same NP_DATA_DIR and NP_S3_* as the backend
scripts/worker.sh --once   # drain the queue and exit
```

`npub push` is unchanged: it follows the `UploadInstruction`s the session
returns (presigned PUTs in S3 mode) and the presigned manifest URL, four
objects at a time, three attempts each; rerunning an interrupted push never
retransmits objects the server already holds. The viewer receives 15-minute
presigned GETs for renditions; the control-plane rendition routes answer 307
to the same URLs.

Orphan cleanup, never automatic:

```
sbt "backend/run gc --older-than 24h --dry-run"   # list what would go
sbt "backend/run gc --older-than 24h"             # delete; audit event per workspace
```

An object is removed only when no committed manifest references it, no
unfinished upload session younger than the threshold declares it, and the
object itself is older than the threshold.

Tests: `S3Suite` runs MinIO through Testcontainers and is skipped when Docker
is unavailable; `ingestion/test` and `GcSuite` use the local queue and store.

Extra data-dir entries: `queue/<rev>.json` (ingestion jobs),
`upload-sessions/<id>.json` (+ `.manifest` in local mode),
`workspace-assets/<ws>/<hex>` (which workspace may see a digest as present).

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
`frontend` (JS), and `backend`, `publisher-cli`, `conformance` (JVM). Stage 2
adds `domain` (the store algebras and records, package `neuropublish.backend`,
so the http4s `backend` and the Doobie `persistence` module share them without a
cycle) and `persistence`; `semantic-registry` and `ingestion` are still to come.

## Fixtures

`modules/conformance/fixtures/reference` is the hand-written reference bundle;
`fixtures/invalid/*.json` each pair with a `.expect` file naming the admission
error the manifest must produce.
