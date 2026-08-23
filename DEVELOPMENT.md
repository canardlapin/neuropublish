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
| `scripts/e2e.sh` | End-to-end proof: build the frontend, start a backend on a temp data dir, `npub login` through the device flow, mint a project credential and prove it cannot cross projects, `npub push` the reference bundle, assert the stale-parent rejection and digest, wait for ingestion, render in Chromium, then publish a second revision from the Julia producer. `NP_E2E_MODE=full` adds PostgreSQL + MinIO containers and the separate ingestion worker; `NP_KEEP_DATA=1` keeps the temp dir; `NP_PORT` (default 8090) picks the port |

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
`push --parent <revision>` names the head the new revision builds on; omit it for
the first revision of a project. A push whose parent is no longer the head is
rejected with the current head (exit 1), and `push` prints the `--parent` to
re-run with.
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
| `NP_INGESTION` | `inline` | `inline` derives renditions inside the commit (an unreadable asset fails the push); `worker` enqueues and returns — run `scripts/worker.sh` beside the backend. The job-level `ingestion.status` on a revision is `pending` / `running` / `ready` / `failed` (with `error` and `attempts`); each entry of `renditions[]` carries its own `status`, which is only `ready` / `pending` / `failed` (a rendition either exists, is awaited, or its job gave up). A revision is `ready` only when every volume rendition exists; a revision with missing renditions and no job is reported `failed` ("no ingestion job"), never ready by absence. |
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
members, sessions, tokens, credentials, views, links, audit, the read model,
the ingestion queue, upload sessions, and the per-workspace asset registry)
moves from the JSON files to PostgreSQL; unset, the local-fs layout below stays
the default (`scripts/e2e.sh` uses it). Objects and renditions live under
`NP_DATA_DIR` unless `NP_S3_BUCKET` is set, so a PostgreSQL + S3 deployment has
no local state at all except the provenance cache (`<data>/provenance`, a pure
function of the manifest). The ingestion worker (`scripts/worker.sh`) reads the
same `NP_DATABASE_*` variables and therefore consumes the same queue.

| Variable | Default | Meaning |
| --- | --- | --- |
| `NP_DATABASE_URL` | unset | JDBC URL, e.g. `jdbc:postgresql://127.0.0.1:5432/neuropublish`. When set, Flyway runs `modules/persistence/src/main/resources/db/migration/V*.sql` at start. |
| `NP_DATABASE_USER` / `NP_DATABASE_PASSWORD` | `neuropublish` / empty | Connection credentials |
| `NP_DATABASE_POOL` | `8` | Hikari `maximumPoolSize` (applied through `HikariConfig`); the worker opens its own pool of the same size |

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
database-backed server) and the MinIO suite (`S3Suite`) use Testcontainers and
a fresh database / bucket per test. They `assume` Docker: without a reachable
daemon they report as skipped, never failed — unless `NP_TEST_REQUIRE_DOCKER=1`
(or `CI=true`) is set, in which case a missing daemon fails the suite, so a CI
run can never go green by skipping them. Set `NP_TEST_REQUIRE_DOCKER=1` in the
CI workflow.

Schema notes (ADR 0004): every project-scoped table carries `workspace_id`
with a composite foreign key `(workspace_id, project_id) → projects`, and every
revision-scoped table (`analyses`, `result_fields`, `revision_assets`,
`derived_representations`, `saved_views`, `ingestion_jobs`) a composite
`(workspace_id, revision_id) → revisions (workspace_id, id)` (V8), so no row
can claim one workspace while pointing at another's record; `revisions.parent`
is constrained to the same project (`(project_id, parent) → revisions
(project_id, id)`). Bare-id lookups in the store algebras take the workspace
(`revision(workspace, id)`, `views.get(workspace, id)`, …); the only unscoped
lookups are `resolveId` (for the `/revisions/{id}`-style routes, which
authorize on the record's workspace before using it) and `resolveSecret` (a
presented bearer secret). `stored_objects` (physical bytes) is separate from
`workspace_assets` (who may reference a digest) and `catalog_assets` (public
templates). A commit is one transaction: head CAS, revision insert, projections
(`analyses`, `result_fields`, `revision_assets`, pruned to the manifest), and
the ingestion job.

`ingestion_jobs` is the worker's queue: a worker-mode commit enqueues one
`pending` row per revision; a worker claims with `UPDATE … WHERE id = (SELECT …
WHERE (status = 'pending' AND available_at <= now) OR (status = 'running' AND
locked_at < now - 10 min) … FOR UPDATE SKIP LOCKED) RETURNING …`, counting the
attempt; a failure sets `available_at = now + 2s·2^(attempts-1)` while attempts
< 3 and `failed` after that; success is `ready`. A job still `running` after
the 10-minute lease belongs to a dead worker and is claimed again.
`neuropublish.persistence.IngestionJobs` is that contract in code; the local-fs
queue (`<data>/queue`) implements the same lease with a `<rev>.claim` file
(claimant + timestamp; a stale claim is renamed aside and re-created, so
exactly one reclaimer wins).

Data-dir layout (the default, `NP_DATABASE_URL` unset) — one JSON document per
record, secrets stored as SHA-256 only, passwords as salted PBKDF2-HMAC-SHA256:

```
<data>/projects/<ws>/<project>.json    revisions/<rev>.json    objects/sha256/..    renditions/<rev>/
<data>/users/<userId>.json             users/identities/<issuer>/<sha256(lower-cased subject)>.json
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
scripts/worker.sh          # = sbt ingestion/run; same NP_DATA_DIR, NP_S3_* and NP_DATABASE_* as the backend
scripts/worker.sh --once   # drain the queue and exit
```

The worker claims jobs from whichever queue the backend produces into (the
`ingestion_jobs` table with `NP_DATABASE_URL`, `<data>/queue` without), so the
two processes must share those variables. Several workers may run at once;
a worker that dies mid-job loses its claim after the 10-minute lease.

Direct transfers never write a committed object. In S3 mode the session
response carries presigned PUTs into `staging/<session>/<digest>` (Content-
Length, Content-Type and `x-amz-checksum-sha256` are signed) and a presigned
manifest PUT into the same staging area; at commit the control plane verifies
every staged object's size and SHA-256 (the provider's checksum echo is trusted
only on AWS itself, i.e. when `NP_S3_ENDPOINT` is unset; an S3-compatible
endpoint is always re-hashed by a streamed pass), server-side-copies it onto
its `sha256/<2>/<64>` key, and only then registers the digest to the workspace.
A stale-parent rejection registers nothing. In local mode the control plane
hashes every proxied upload itself, so a verified object is registered as it
lands (a resumed push skips it). `GET /api/v1/upload-sessions/{id}` re-issues
instructions for whatever the session still lacks (signed URLs expire after an
hour). A stored manifest whose bytes no longer hash to the revision's digest
is refused everywhere it is read (`503 integrity`, worker job error, `gc`
refusal) — never used silently.

`npub push` is unchanged: it follows the `UploadInstruction`s the session
returns and the manifest URL, four objects at a time, three attempts each;
rerunning an interrupted push re-negotiates a session and, in local mode,
never retransmits objects the server already verified (in S3 mode a new
session has a new staging area, so only committed objects are skipped). The
viewer receives 15-minute presigned GETs for renditions (fetched without
credentials; they are on another origin); the control-plane rendition routes
answer 307 to the same URLs. Every API response is `Cache-Control: no-store`.

Orphan cleanup, never automatic:

```
sbt "backend/run gc --older-than 24h --dry-run"   # list what would go
sbt "backend/run gc --older-than 24h"             # delete; audit event per workspace
```

`gc` enumerates every revision through the configured store (PostgreSQL or
the local files) and refuses to run when it cannot, or when a stored manifest
fails its integrity check. It first drops upload sessions older than the
threshold (and their staging areas), then deletes an object only when no
committed manifest references it, no remaining session declares it, and the
object itself is older than the threshold — recomputing the reference set
immediately before each deletion, so a commit racing the run keeps its
objects. A deleted digest is unregistered from every workspace; renditions of
revisions that no longer exist are removed. Deletion is immediate; there is no
recycle bin yet. `--dry-run` reports the same and changes nothing (the audit
event is `gc.dry-run`).

Tests: `S3Suite` runs MinIO through Testcontainers (skipped without Docker,
failed with `NP_TEST_REQUIRE_DOCKER=1`); `ingestion/test` and `GcSuite` use
the local queue and store.

Extra data-dir entries in local mode: `queue/<rev>.json` (+ `<rev>.claim`
while a worker holds it), `upload-sessions/<id>.json` (+ `.manifest`),
`workspace-assets/<ws>/<hex>` (which workspace may see a digest as present).
With `NP_DATABASE_URL` these live in `ingestion_jobs`, `upload_sessions` and
`workspace_assets` instead.

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
