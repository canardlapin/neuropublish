# Neuropublish implementation plan

Date: 2026-08-22 (revised 2026-08-24 after the private-alpha and native-domain spikes)

## Planning rule

Build one end-to-end scientific publication path before expanding the taxonomy
or interface. Every milestone ends in a runnable proof with explicit failure
cases. A green compiler or static render is not enough.

The first time a published map is visible in the browser must come as early as
the dependency graph allows, not after identity, sharing, and package adapters
are complete.

## First milestone in one sentence

Publish one GDS-derived group result through a language-neutral bundle, commit
an immutable revision, inspect one underlay and two overlays in the ScalaFIM
volume viewer, read truthful provenance, save the view, and open it through a
read-only link.

## Repository

The project lives at <https://github.com/canardlapin/neuropublish>. Commits,
pushes, and `gh` calls for this repository use the `canardlapin` identity.

## Reference result

All fixtures, exit criteria, and screenshots use one named reference result.
The working placeholder is the `sherlock` naturalistic-movie group model,
estimand `speech coefficient`, measures effect, standard error, t, and z, on
the MNI152NLin2009cAsym volume grid with one anatomical underlay. The real
dataset, analysis invocation, and asset digests will be supplied once the
repository is set up and are recorded in `conformance/reference/`; until then
the hand-written bundle stands in for it.

The hand-written fixture bundle also carries a heterogeneous first-level
cohort (AR(1) and AR(2) receipts) and one unknown semantic record, so the
provenance and unknown-record interfaces can be built without waiting on R
package changes.

Its metadata and scientific relationships are realistic, but its small volume
and icosphere geometry are synthetic conformance assets. They are suitable for
protocol, interaction, and browser oracles; they are not the final visual-
acceptance corpus. A curated public anatomical underlay and cortical surface
pair with recorded license and provenance is still required before closing the
reference-asset portion of the UI pass.

## Decisions closed by this revision

These were previously open or contradictory across the documents. They are
now fixed; change them only through a new ADR.

1. **Manifest digest is the SHA-256 of the manifest bytes.** The producer
   writes `manifest.json` conforming to a strict byte profile; the digest is
   computed over those exact bytes and sent outside the manifest; the server
   stores those exact bytes immutably. There is no JSON canonicalization
   step. See ADR 0001.
2. **Browser renditions and scalar summaries are derived server-side.** A
   producer supplies canonical assets (NIfTI, GIFTI, tables) only. An
   ingestion worker, separate from the control-plane service, derives
   browser-ready typed-binary renditions, previews, ranges, quantiles,
   histograms, and missing-value counts after commit. Derived representations
   are recorded on the revision, outside the snapshot digest. `npub pack` may
   precompute the same derivatives to shorten time-to-view, but the server
   treats producer-supplied derivatives as untrusted and recomputes or
   verifies them.
3. **The MVP browser rendition is a publication-time typed-binary derivative,
   not in-browser NIfTI/GIFTI decoding.** The Stage 0 spike exists to disprove
   this default, not to choose between equals.
4. **Revision history is linear per project in the MVP.** A commit must name
   the current project head as its parent (or no parent for the first
   revision). A commit whose parent is not the head is rejected with the
   current head so the publisher can re-push. Branching is a post-MVP
   decision.
5. **The relational tables are a derived read model.** The stored manifest
   bytes plus the server-derived rendition records are the source of truth;
   every relational projection must be rebuildable by a `reindex` command.
6. **Core measures ship as a built-in trusted module.** Effect, standard
   error, t, z, p, accuracy, and correlation measures live in
   `org.neuropublish.measure/*` inside `semantic-registry`, so the MVP needs
   no external semantic module to label its result tree.
7. **Atlas lookup is not part of the first slice.** The status bar shows world
   coordinate and per-layer values; atlas labels arrive with the
   templateflow4s catalog work in Stage 3.
8. **R package receipt work is a parallel track**, not a gate on the
   application's provenance interface.
9. **Stack: Cats Effect, http4s, Tapir, Circe, Doobie, Flyway on the
   server; Laminar, Airstream, Waypoint, Vite in the browser.** See ADR 0003.
10. **Tenancy: single-workspace alpha on a multi-workspace schema.** One
    workspace is created for the alpha lab; every table, query, URL, and
    dedup key is workspace-scoped from the first migration so that multisite
    operation is an enablement, not a migration. See ADR 0004.
11. **The MVP freezes an open domain hook; parcel behavior follows in Stage
    5b.** Every domain has an exact key and an open descriptor. Stage 2
    implements the trusted volume-grid descriptor and preserves unknown
    descriptors. The later parcel track adds finite-indexed interpretation,
    atlas realizations, and pullbacks without changing that envelope. See ADR
    0005.

## UI design cadence

A directional concept mockup exists in
[`docs/design/`](design/README.md). It fixes the feel and the coarse layout
(result navigation left, volume-over-surface viewer centre, layer inspector
and facts right, provenance chain beside the result) and is not a
specification.

Design runs one stage ahead of implementation rather than as one pass. The
scientific workflow and state model are designed now; the technical viewer
shell is validated immediately and plainly; expensive visual polish waits
until real assets and renderer behaviour are proven.

### Now — interaction and information design

Produce, with the `/design` skill, one flow of artboards with named states
for the single journey that governs the protocol and infrastructure work:

```text
npub push
    → returned project URL
    → project overview and revision history
    → select analysis, estimand, and measure
    → inspect two overlays on one underlay
    → change threshold and display window (separately)
    → inspect analysis facts and provenance, including heterogeneous inputs
    → save the view
    → share a read-only link; open it without an account
```

The artboards cover the project overview, result navigator, Volume
workspace, layer card, analysis and provenance inspectors, saved-view and
share flows, and the CLI-to-browser handoff, using the reference result and
realistic metadata from the hand-written bundle. The explicit purpose is to
test whether the protocol exposes what the interface needs; every fact shown
on an artboard must trace to a manifest field, a server-derived record, or a
saved-view field, and anything that cannot is a protocol gap to resolve
before the Stage 2 freeze.

This is a flow of artboards, not an interactive build.

### Stage 0–1 — deliberately plain vertical slice

The thin spine uses semantic DOM and default controls. It validates canvas
lifecycle, loading, coordinates, layer controls, and browser performance
with real ScalaFIM rendering. No visual polish beyond basic usability.

### Before Stage 3 — finalize the UI system

Using evidence from the working slice, refine typography, spacing,
interaction states, accessibility, responsive and narrow behaviour, and
component specifications. The Volume workspace is built against this
system, not against the concept mockup.

### Stages 3–5b — one stage ahead

- provenance and identity/sharing design before Stage 4;
- Hybrid workspace, surface picking, and empty/absent-representation states
  before Stage 5;
- parcel-table linking, incomplete-atlas warnings, and pullback provenance
  before Stage 5b;
- loading, error, and pending-ingestion states are designed with the stage
  that introduces them.

Arbitrary docking, public discovery, comments, administration, and full
visual refinement wait. The `docs/design/README.md` table lists which mockup
elements the plan does not adopt, so each design pass starts from the
product definition rather than from the concept image.

## Dependency map

```text
repository bootstrap
      |
      v
thin local spine  (push -> commit -> two overlays visible, static token)
      |
      +----> neutral protocol freeze ----> foreign-producer conformance --+
      |                                                                  |
      +----> viewer prerequisites ----> volume workspace                 |
      |                                      |                           |
      |                                      v                           |
      |                       application provenance + identity/share    |
      |                                      |                           |
      |                                      v                           |
      |                             surface + hybrid                     |
      |                                      |                           |
      +----------------------------> production hardening <--------------+

parallel R track: fmrireg receipts, fmrigds descriptors, adapters
                  (joins at "R clients" in Stage 5; never blocks Stages 1-4)

post-MVP parcel track: Stage 2 domain hook -> finite-indexed + hard assignment
                       -> Scala realization adapters -> Stage 5b parcel UI
                       (does not block the volume MVP)

full arbitrary docking: post-MVP experiment, not an MVP dependency
```

Work may proceed in parallel where the graph permits. The dependency edges are
scientific and technical gates, not estimates of elapsed time.

## Stage 0 — bootstrap and retire the two unknowns

Stage 0 is deliberately smaller than a full scaffold. It establishes the build
and closes the two questions the rest of the plan depends on: browser asset
fidelity and renderer lifecycle.

### Deliverables

- initialize the independent Git repository and add it to the Scala workspace
  catalog through a separate parent-workspace change;
- scaffold the Scala 3 JVM/Scala.js build with the workspace's supported
  toolchain or a documented bounded exception;
- create cross-platform `protocol-core`, `protocol-json`, and `viewer-state`
  modules plus minimal `backend`, `frontend`, `publisher-cli`, and
  `conformance` modules;
- select exact immutable Scala library dependencies and explicit local override
  properties;
- write the hand-written reference bundle (valid) and an invalid-bundle suite
  including the heterogeneous cohort and unknown-record cases;
- spike: JVM reader produces the typed-binary rendition for one NIfTI volume
  and one GIFTI surface field; Scala.js loads it into ScalaFIM-compatible
  objects with exact affine, values, topology, and vertex ordering;
- spike: a mounted Laminar canvas creates, resizes, updates, and disposes both
  a ScalaFIM volume controller and a Three.js surface backend without leaks.

Identity-provider selection moves to Stage 4. Stage 0 through Stage 3 run
with a static, locally configured bearer token.

### Exit criteria

- JVM and Scala.js shared modules compile and test;
- a clean external consumer resolves the selected Scala dependencies without
  implicit sibling checkouts;
- the browser reads the exact affine, values, topology, and vertex ordering of
  the two canonical fixtures from the typed-binary rendition;
- resize and mount/unmount tests leave no live listeners, scheduled frames, or
  WebGL resources;
- the repository documents lifecycle, build commands, and dependency status;
- the rendition decision is either confirmed or overturned by a recorded
  measurement, not deferred.

### Status (2026-08-22)

| Criterion | Evidence |
| --- | --- |
| modules compile and test on JVM and JS | `sbt npCheck` green on JVM and Scala.js |
| clean external resolution | ScalaFIM and its pinned tree cloned from GitHub by SHA on first load; no sibling checkout consulted |
| volume fidelity | `rendition` suite on JVM and Scala.js: exact shape, affine, every oracle probe value and world coordinate, sums, and `ViewerModel` cursor readouts for underlay + two overlays against an R/neuroim2 oracle |
| surface fidelity | **closed in Stage 5** (carried from here through Stages 1–4): `SurfaceFidelitySuite` on JVM and Scala.js decodes the `surface-mesh@0` and `vertex-field-f32@0` renditions of the Julia producer's icosphere hemispheres (642 vertices / 1280 faces each) to the exact oracle coordinates, faces, field values, and sums; `GiftiToRenditionSuite` proves the GIFTI → rendition encode byte for byte and the ADR 0005 `surface-vertices/v1` key against the manifest |
| lifecycle | Playwright in Chromium: volume pane mount/resize/unmount disposes exactly once and cancels outstanding frames; a sentinel surface pane stays mounted through 24 further surface mount/unmount cycles (more than Chrome's live-context budget) and keeps `contextState = Available` with zero `webglcontextlost` events on any canvas; every `EventTarget.addEventListener` is matched by a remove |
| documentation | `DEVELOPMENT.md`, `docs/architecture.md`, this file |
| rendition decision | **confirmed**: typed-binary derivative (JSON header + float32) decoded into ScalaFIM objects on JS exactly for float32 sources (the fixtures); float64 and wide-integer sources lose precision in the rendition, documented in the profile, and the canonical asset remains the record. In-browser NIfTI decoding not attempted (image4s-nifti JS is Node-only) |

Upstream findings are recorded as motes in the owning stores. Neuropublish's
working pin, ScalaFIM `2a64eba`, contains the controller-disposal and forced-
context-loss fixes used by the admitted browser lifecycle gate. The native
image4s/locus4s atlas line at ScalaFIM `a3c2320` now closes the old exact-domain
prerequisites, but cannot replace that pin directly: its image API has migrated
from `NeuroVol`/`NeuroSpace`, and its canvas controller exposes only `close()`.
The exact compatibility evidence and migration gate are in
[`docs/plans/stage5b-dependency-spike.md`](plans/stage5b-dependency-spike.md).

- `CanvasViewerController.close()` on the tested native line only flips a flag;
  it does not release the viewer/raster caches or `ImageBitmap` handles. The
  previously proven `dispose()` contract must be merged forward before the pin
  moves.
- `ThreeJsRuntime.dispose()` calls `renderer.dispose()` but never forces
  context loss, so disposed canvases keep counting against the browser's live
  WebGL budget until garbage collection; 24 mount/unmount cycles evicted older
  contexts until the host forced loss via `WEBGL_lose_context` itself. Upstream
  should do this in `dispose()`.
- `scalafim-atlas` at `a3c2320` has completed the direct locus4s and image4s
  migration: persistent domain identity no longer depends on runtime
  `hashCode`. This closes the owning-library design prerequisite, not the
  Neuropublish consumer migration or admission work.
- `CanvasScrollCoordinator` cannot cancel a pending scheduled flush; a host that unmounts mid-burst has no way to stop it.
- `image4s-nifti` abstracts its filesystem behind a `private[nifti]` trait, so a browser backend must be added inside image4s if ever wanted.
- The locus4s `PartialSurjection`, image4s categorical bridge, persistent grid
  identity, and ScalaFIM atlas/publication adapter motes are closed upstream.
  Neuropublish now owns the remaining rendition/API migration and parcel
  admission adapters.
- `ThreeJsRuntime.clearFrame` hard-codes an opaque white clear colour. The
  dark scientific workspace therefore cannot theme the surface canvas without
  an owning-library API. Keep this as a viewer prerequisite rather than a
  downstream WebGL workaround.
- ScalaFIM links Scala.js as CommonJS; consumers linking ESModule work fine, but ScalaFIM's own browser pages import Three.js from a hardcoded sibling path. Neuropublish pins `three` 0.185.1 in its own `package.json`.
- Intaglio is at Scala 3.4.2 and sbt 1.10.5 (workspace outlier); compiles fine as a TASTy dependency.

## Stage 1 — thin local spine

The first moment a published map is visible in the browser. Everything here is
intentionally provisional except the bundle layout and the revision model.

### Deliverables

- `npub push` against a local server: hash assets, create an upload session,
  upload missing objects to a local S3-compatible store, commit;
- one workspace and project created by an operator bootstrap command; static
  bearer token; no user table yet, but every query already carries the
  workspace scope;
- plain semantic DOM and default controls; no visual system yet;
- server stores manifest bytes, verifies object digests and sizes, writes the
  revision record, and runs the ingestion worker to derive renditions;
- a single route that opens the latest revision and renders the anatomical
  underlay plus two statistical overlays through the ScalaFIM volume host;
- layer visibility and opacity only; no inspector, no saved views.

### Exit criteria

- pushing the hand-written reference bundle displays two overlays on an
  underlay in the browser within one command after the server starts;
- a second push with the same parent as the first is rejected with the
  current head;
- a push with a substituted asset fails at commit;
- the revision digest equals `sha256(manifest.json)` as computed by
  `shasum -a 256` on the bundle file.

### Status (2026-08-22)

All four criteria pass via `scripts/e2e.sh` (builds the frontend, starts a
backend on a fresh data directory, runs `npub push`, runs Playwright) and the
backend's in-process suite. Provisional choices, to be replaced in Stage 2:
objects flow through the control plane rather than signed object-store URLs;
the revision store and object store are local-filesystem implementations of
the algebras; ingestion runs in-process immediately after commit; identity is
one static token; the viewer has visibility and opacity controls only (layer
reordering needs a model rebuild and lands with the Stage 3 controls) and no
visual system. The surface (GIFTI) rendition check is still open and moves
to Stage 3 alongside the surface pane, since Stage 2 has no browser work.

## Stage 2 — language-neutral protocol and publication spine

### Protocol

- define core JSON Schema 2020-12 documents and freeze the vocabulary needed
  by the reference result — `protocol/schemas/manifest.schema.json`,
  `protocol/schemas/workspace-state.schema.json`,
  `protocol/schemas/rendition-header.schema.json`,
  `protocol/schemas/records/volume-grid-v1.schema.json`, with the prose
  invariants in `protocol/SPEC.md`;
- implement stable semantic IDs, schema references, open records, axes,
  the common domain envelope, the trusted volume-grid descriptor, result
  fields, representations, assets, provenance graphs, warnings, and
  sensitivity;
- require every domain entry to carry an exact key and an open descriptor
  record (`schema` plus `payload`); preserve unknown descriptors without
  granting rendering, alignment, or transformation behavior;
- specify and test the version-1 volume-grid binary identity preimage so the
  server can recompute its structural fingerprint without JSON
  canonicalization;
- reserve finite-indexed descriptors, atlas realizations, hard assignments,
  parcel pullbacks, and other relation kinds for the Stage 5b parcel track;
- distinguish canonical assets (in the digest) from derived representations
  (recorded by the server);
- keep wire DTOs separate from validated Scala domain types;
- return accumulated errors with JSON Pointer paths (`Manifest.parse` →
  `Either[List[Problem], …]`; `ApiError.problems`; `npub validate` /
  `inspect` print `error  <pointer>: <message>`; pointers come from the
  decoder's cursor history, so keys containing `.` or `[` are exact);
- create compatibility rules, migrations, and unknown-record preservation
  (`protocol/SPEC.md` §7–8; `Migrations` 0.0 → 0.1; `TrustedSchemas`);
- specify the route scheme: project, revision, view, explore, and presentation
  URLs, since saved views and share links depend on it (`protocol/SPEC.md`
  §9).

Gaps surfaced by the governing-journey design
([canvas](https://claude.ai/code/artifact/b4e22461-776e-4553-a62a-d0c21d051af8)),
to close before the freeze:

- an analysis-level sample-size field in the core (or a trusted-module
  summary fact), since the overview leads with `n` — `analyses[].sampleSize`;
- a project-level description/question as a server record, distinct from
  the snapshot synopsis;
- explicit, normative ordering of estimands and measures under an analysis —
  `order`, ties by array position, unique per analysis/estimand (SPEC §5);
- an optional scope pointer on each warning (field, analysis, or provenance
  node), since warnings surface in three places — `warnings[].concerns`,
  resolved at admission;
- confirmation that compatibility groups are server-computed from receipt
  fingerprints, and which receipt fields participate.

### Publisher CLI

- `npub validate`;
- `npub inspect`;
- `npub pack` for local-path staging bundles;
- `npub push` with hashing, missing-object negotiation, bounded upload,
  resumption, commit, and URL output;
- credentials outside manifests and command-line arguments.

Status (2026-08-23), `push`: done. Each missing object follows its
`UploadInstruction` verbatim (method, URL, signed headers — validated against
the HTTP token/CR-LF grammar), so the same flow serves the control-plane PUT
(local mode) and a presigned object-store PUT (S3 mode); the bearer is sent
only to URLs on the control plane's origin (scheme, host, port), and plain
`http` to a non-loopback host prints a warning. Files are hashed and uploaded
as streams with an explicit `Content-Length`; a declared `size` that disagrees
with the file is refused before negotiation. Four objects in flight, three
attempts per object with back off, one progress line per object. Resumption is
a protocol property: a rerun negotiates a new session whose `missing` list
excludes objects the workspace already holds (`ResumeSuite`: interrupted after
≥1 object, the second session asks for exactly the objects the interruption
left behind). A re-push of the bundle that is already the head prints
`already published as <head>` and exits 0; any other stale parent advises
`--parent`. Commit rejections print `ApiError.problems` one per line.
`pack` copies digest-only assets from the staging layout, never overwrites a
declared `digest`/`size`, refuses directory paths and non-array `assets`, and
keeps an existing output unless `--force`. Credentials are keyed by normalized
origin (case, default port).

Protocol status (2026-08-23), edges closed: the server recomputes every
trusted volume-grid key (`size`, `structuralFingerprint`, `key.descriptor ==
descriptor.schema`) from the payload on JVM and JS and validates the payload
against the records schema on the JVM; the decoder enforces the wire grammar
the JS build would otherwise miss (`selection` required, `sha256:` prefix,
schema `version` pattern, non-negative sizes; SPEC §3 "JS-lenient subset");
`migratedFrom` written by a producer is refused and an unsupported major is
named; each invalid fixture pins the exact problem list (`.expect`, one line
per problem). The records schema `$id` moved under
`https://neuropublish.org/schema/0.1/`, which changed its digest and every
fixture that names it.

### Backend and persistence

- upload-session create, status, and commit endpoints with declared limits on
  object size, object count, and total bundle size from the first version of
  the API;
- PostgreSQL migrations and repositories as a derived read model with a
  `reindex` command; workspace-scoped from the first migration per ADR 0004;
- atomic revision commit; the stored manifest bytes are immutable;
- ingestion worker as a separate process with its own queue, retry, and
  failure state visible on the revision;
- audit events and delayed orphan cleanup.

#### Status (2026-08-23)

Persistence landed: `modules/domain` holds the store algebras and records
(`RevisionStore`, `Identity`, `Members`, `Sessions`, `UserTokens`,
`Credentials`, `Views`, `ShareLinks`, `Audit`); `modules/persistence` holds the
Flyway migrations (`V1__workspaces` … `V7__ingestion_jobs`, the tables of
architecture "Persistence" with ADR 0004's composite foreign keys and the
`stored_objects` / `workspace_assets` / `catalog_assets` split), Doobie
implementations of every algebra, and a `Reindex` service. `Server.build`
selects PostgreSQL when `NP_DATABASE_URL` is set (migrating at start) and the
local-fs stores otherwise; `neuropublish.backend.Main reindex` rebuilds the
read model from stored manifests. Commit is one transaction with a row lock on
the project (parent-must-be-head CAS) that also enqueues an `ingestion_jobs`
row (`pending|running|ready|failed`; claim by `FOR UPDATE SKIP LOCKED`) for the
worker. Verified under Testcontainers PostgreSQL (suites skip without Docker):
composite FK rejects a cross-workspace revision; two concurrent commits with the
same parent yield one head and one stale rejection; `reindex` on an emptied
projection reproduces it row for row; two workspaces never cross at the store
level, and `RoutesSuite`/`Stage4Suite` (including the two-workspace isolation
test) run unchanged against the database-backed server. Row-level security
stays deferred per ADR 0004.

Status (2026-08-23), object store / ingestion / cleanup: done. Upload
sessions are now durable on the local fs (`<data>/upload-sessions/`); the
PostgreSQL `upload_sessions`/`ingestion_jobs` adapters for `UploadSessions`
and `IngestionQueue` are marked in `Server.localStorage`.

- `ObjectStore.S3` (AWS SDK v2 async client, `NP_S3_*`) keys objects
  `sha256/<2>/<64>`; upload sessions return presigned PUTs whose signature
  covers `Content-Length`, `Content-Type` and `x-amz-checksum-sha256`, plus a
  presigned manifest PUT. Commit verifies every object by HEAD size and by the
  provider checksum when reported, else by streaming the object once; a
  mismatch rejects the commit and deletes nothing. Renditions live in the
  bucket under `renditions/<rev>/` and are served as 15-minute presigned GETs
  (`RevisionDetail.renditions[]`, the share response, and 307 redirects from
  the member and share rendition routes); the control plane never streams
  rendition bytes in S3 mode. Per ADR 0004 the session response gates
  "missing" on `workspace-assets` membership, so another tenant's identical
  bytes are still reported missing (`S3Suite`, MinIO via Testcontainers).
- Ingestion: `IngestionQueue` (local-fs `<data>/queue/`, the PostgreSQL
  `ingestion_jobs` adapter point is marked) and `modules/ingestion`
  (`neuropublish.ingestion.Main`, `scripts/worker.sh`). `NP_INGESTION=worker`
  makes commit enqueue and return; the revision reports
  `ingestion.status = pending` until the worker derives, with three attempts,
  exponential back off and the error recorded on the job (`WorkerSuite`).
  `inline` (default) keeps the Stage 1 fail-before-commit behaviour.
- `neuropublish.backend.Main gc --older-than 24h [--dry-run]` deletes objects
  referenced by no committed manifest and no young unfinished session, and
  rendition sets of vanished revisions; every run writes an audit event
  (`GcSuite`).

### Neutrality proof

A tiny Julia program that does not use Neuropublish Scala classes must create a
valid bundle and publish it through the documented HTTP or CLI boundary. The
same fixture is decoded and re-encoded by Scala and R without losing unknown
extension fields.

Status (2026-08-23): done. `modules/conformance/julia/producer.jl` (Julia
stdlib `SHA`/`Downloads` plus `JSON3`, no Neuropublish code) writes four
float32 NIfTI volumes, a core 0.1 manifest with the ADR 0005 domain envelope,
an unknown activity record, an unknown top-level field and an unknown field
inside a known record, plus an `oracle.json` of what an independent reader
must see, then publishes through the documented
upload-session/object/manifest/commit endpoints (bearer only to the
`--server` origin, libcurl redirects off). Its output is deterministic (no
Julia version in the manifest), and `JuliaProducerSuite` regenerates it and
compares byte for byte with the committed `modules/conformance/fixtures/julia/`.
The suite also asserts the Scala digest equals Julia's, drives the script
against an in-process backend (stale re-push rejected, `--parent` accepted,
every rendition `ready`), checks that `roundtrip.R` (jsonlite) re-encodes to a
value-equal manifest with the unknown fields intact, and that `nifti-check.R`
(neuroim2) reads back every volume with the shape, affine, and probe voxel
values the producer wrote. CI installs Julia 1.12 and R and sets
`NP_TEST_REQUIRE_TOOLS=1`, so these suites fail rather than skip there;
`scripts/e2e.sh` runs the producer as a second push against the live server.

### Exit criteria

- valid, invalid, old-version, new-extension, and schema-digest-mismatch golden
  fixtures pass;
- the reference volume domain uses the open descriptor and exact-key envelope;
  Scala and an independent fixture oracle recompute the same volume-grid
  fingerprint, while an unknown domain descriptor is preserved but cannot be
  rendered or aligned;
- Scala, Julia, R, and `shasum` calculate the same digest for the same
  fixture bytes; a Julia producer independently writes a manifest the server
  admits; a byte-profile violation is rejected with a JSON Pointer path;
- an upload interrupted after at least one object resumes without retransmitting
  completed objects;
- commit fails for a missing or substituted asset;
- two concurrent commits with the same parent produce exactly one new head and
  one rejection, with no partial relational projection;
- `reindex` on an empty database reproduces the read model from stored
  manifests;
- a two-workspace integration test proves no project, asset, or credential
  lookup crosses workspaces, and composite foreign keys reject a
  cross-workspace revision;
- sensitivity and public-sharing policy are enforced at commit from this
  stage on;
- a successful push returns a durable project/revision URL.

### Status (2026-08-23)

Done after Stages 3–4 (deliberately), so the schemas froze against a working
product. CI runs `sbt npCheck` with Julia, R, and Docker required
(`NP_TEST_REQUIRE_TOOLS=1`, `NP_TEST_REQUIRE_DOCKER=1`: the Julia/R and
PostgreSQL/MinIO suites fail rather than skip there; locally they skip when a
tool is absent). The end-to-end modes are run locally per stage:
`scripts/e2e.sh` (local store) and `NP_E2E_MODE=full scripts/e2e.sh`
(PostgreSQL + MinIO + the separate ingestion worker, presigned transfers, all
eight browser scenarios, and the Julia second revision).

| Criterion | Evidence |
| --- | --- |
| golden fixtures (valid, invalid, old-version, new-extension, schema-digest-mismatch) | `protocol/schemas/*.schema.json` + `protocol/SPEC.md`; JVM admission with JSON Pointer problems; 27 invalid fixtures each pinned to its exact problem list; 0.0→0.1 migration keeping original bytes; unknown fields round-trip value-for-value; trusted-record digest mismatch rejected, unknown retained as unsupported; volume-grid keys recomputed |
| same digest from Scala, Julia, R, `shasum` | `producer.jl` (no Neuropublish code) prints the digest the server then commits; R re-encoding changes bytes, not values |
| Julia producer independently admitted | local store in `JuliaProducerSuite` (and the committed fixture in `FixtureSuite`); S3 mode via `NP_E2E_MODE=full scripts/e2e.sh` |
| resumable upload | `ResumeSuite`: interrupted after ≥1 object, re-negotiated session excludes completed objects; bounded concurrency and retries in `npub push` |
| missing/substituted asset fails at commit | S3 mode verifies size and SHA-256 (provider checksum or a streamed pass); local mode unchanged |
| concurrent same-parent commits | PostgreSQL row lock on the project: exactly one succeeds, one `StaleParent`, one pending job |
| `reindex` reproduces the read model | `backend/run reindex` rebuilds analyses/result_fields/revision_assets from stored manifests (persistence test) |
| two-workspace isolation | under PostgreSQL: composite FK rejects a cross-workspace revision; credentials, links, revisions never cross |
| sensitivity required at commit; group-level enforced at share creation | `sensitivity` is required by schema and decoder, so commit rejects a manifest without it; share-link creation refuses a non-`group-level` revision |
| durable URL | unchanged from Stage 1 |

Also landed: `domain` module holding the store algebras; `persistence`
(Flyway V1–V7, Doobie stores, `ingestion_jobs`); S3-compatible object store
with presigned PUT/GET and per-workspace asset registry (no cross-tenant
existence oracle); `ingestion` worker process with retries and per-revision
status; `gc --older-than`; `npub inspect`/`pack`.

Spine fixes (2026-08-23): the ingestion queue, upload sessions and the
workspace asset registry are PostgreSQL tables in PostgreSQL mode (V8: leased
`ingestion_jobs` with `available_at`/`locked_at`, composite
`(workspace_id, revision_id)` keys on every revision child, `parent` kept in
its project); the local queue has the same 10-minute lease through a
`.claim` file; commit + projections + job are one transaction (or one mutex
section locally); `ingestion.status` is derived from evidence and never
`ready` by job absence; share links refuse revisions whose ingestion is not
ready; the viewer shows "Deriving browser renditions…" and polls every 2 s,
and "Ingestion failed" with the error; `gc` enumerates revisions through the
store, refuses without a complete reference set, drops abandoned sessions,
re-checks references before each delete, and unregisters deleted digests;
every temp-file replacement is an atomic move and every read tolerates a
vanished file; unhandled exceptions map to 404/503/500 `ApiError`s;
`NP_DATABASE_POOL` is applied; store lookups are workspace-scoped. Object
store: signed PUTs go to a session-scoped staging area and commit verifies
(size + SHA-256; the provider checksum echo is trusted only on AWS) and
server-side-copies — no client writes a committed key; a digest is registered
to a workspace only by a successful commit; stored manifests that no longer
hash to their digest are refused on every read (`503 integrity`);
`GET /upload-sessions/{id}` re-issues instructions; API responses are
`no-store`; presigned rendition GETs are fetched without credentials;
`maxObjectBytes` is 1 GiB; objects are streamed to disk for derivation.

Carried / watch: bucket CORS is still to be configured for a real
deployment (MinIO reflects any origin, which is why `NP_E2E_MODE=full`
renders); in S3 mode a re-negotiated session re-uploads objects the previous
session only staged (committed ones are skipped); upload-session expiry is
`gc`'s threshold, not a TTL of its own; `NP_TEST_REQUIRE_DOCKER=1` must be
set in CI so the Docker suites cannot pass by skipping.
The ScalaFIM `NArray` migration remains queued before Stage 5.

## Stage 3 — volume scientific workspace

### Product shell

- Laminar/Airstream application shell and routing per the Stage 2 route scheme;
- project overview and revision page;
- semantic result navigator ordered by analysis, estimand, and measure, using
  the built-in measure module for labels;
- revision-aware URL and loading/error states;
- metadata facts with pointers back to manifest sources.

### Volume viewer

- thin Laminar adapter around ScalaFIM `CanvasViewerHost.controller`;
- standard anatomical underlay resolved by immutable catalog digest;
- at least two simultaneous scalar overlays;
- world-coordinate crosshair and per-layer value readout;
- atlas lookup behind a typed service boundary (may ship empty in this stage);
- content-addressed resource cache and bounded browser memory policy.

### Layer controls

- deterministic order and visibility;
- opacity and blend mode;
- independent display window and threshold;
- scientific sequential and diverging colormaps;
- published recommendation versus current view;
- reset control, layer, and workspace;
- compact scalar histogram and missing-value summary from the server-derived
  scalar summary.

The richer threshold and colormap primitives land in Intaglio/ScalaFIM with
JVM/Scala.js conformance tests before the app consumes them.

### Exit criteria

- the reference result displays one underlay and two overlays;
- affine and click/readout values match independent fixture oracles;
- threshold changes never change the display window and vice versa;
- changing window, threshold, or opacity does not reread the source asset;
- layer order and presentation survive URL round trips;
- keyboard users can reach all non-canvas controls and resize preset panes;
- Playwright screenshots cover ordinary, narrow, error, and loading states;
- all controllers and resources dispose on navigation and unmount.

### Status (2026-08-22)

Built on the Stage 1 spine (Stage 2 deferred by decision; its schemas, stores,
and foreign-producer proof slot in behind the same contract). Verified by
`scripts/e2e.sh` (four Playwright scenarios) and `sbt npCheck`:

| Criterion | Evidence |
| --- | --- |
| reference result: underlay + two overlays | workspace renders t and z per the published recommendation; colour-pixel check |
| affine and click/readout values | cursor set from the URL at two oracle probes; status bar shows the oracle's world coordinate and per-layer values exactly |
| threshold vs window independence | editing one leaves the other unchanged (separate `ViewerAction`s) |
| no reread on control change | display actions dispatch into the existing controller; only reorder/colormap rebuild the `ViewerModel` from already-decoded volumes |
| URL round trip | `?l=…&c=…&p=…&i=…` (`ViewUrl`, ids percent-encoded) encodes order and presentation; property-tested round trip with reserved characters; after reload the inspector *and the canvas* reflect order, threshold, window, and colormap from the first frame (colour-pixel check) |
| keyboard reach | navigator measures are buttons with `aria-pressed`; Tab/Enter toggles a layer; numeric fields commit on Enter/blur and flag rejected values with `aria-invalid`; layer cards are keyed so reorder keeps focus |
| screenshots | overview, workspace, narrow (900 px), error; loading is a text state |
| disposal | navigating to the overview unmounts the pane with no page errors (lifecycle suite covers the host) |
| published vs current, reset | each layer card shows "published" or "modified · reset"; per-layer and whole-view reset |
| scalar histogram / missing summary | server-derived `ScalarSummary` in the rendition header, drawn in the layer card |

Not done, carried: atlas lookup (plan decision 7); the UI system is applied as
`--np-*` tokens and components but not yet the full visual pass of the
Components artboard; surface rendition fidelity (now Stage 5 with the surface
pane).

Upstream items found: ScalaFIM has no reorder or colormap action (the app
rebuilds the model, carrying state); `ColorRamp` is two-stop (the "viridis
(2-stop)" choice is honest about that). One-sided and bounded two-sided
thresholds now exist in Intaglio (`Below`/`Above`/`TwoSided`) and render here
(`positive`/`negative` modes verified by the e2e as strict subsets of two-sided).

## Stage 4 — application provenance, identity, and sharing

This stage completes the first vertical slice. It depends only on the
hand-written fixture for provenance content; it does not wait for the R track.

### Application provenance

- readable pipeline view;
- operation DAG and node detail;
- exact parameters, inputs, outputs, versions, hashes, warnings, and source
  pointers;
- heterogeneous-input summaries that never invent a shared setting;
- unknown operations shown generically, downloadable, never executable.

### Identity and sharing

- choose the alpha identity provider after a device-flow spike behind the
  identity boundary;
- browser login and secure sessions;
- device-code CLI login replacing the static token;
- revocable project publisher credentials;
- private project authorization;
- saved views: owned by their creator, named, each save producing a new
  immutable view version; share links target one immutable view version;
- expiring, revocable read-only links;
- short-lived signed object GETs and bucket CORS for unauthenticated
  link-shared viewers — deferred to Stage 2 with the object store itself;
  link viewers read renditions through the control plane's share routes until
  then;
- explore and presentation routes over the same revision and view;
- audit log for publish/share/revoke.

### Exit criteria

- the heterogeneous AR(1)/AR(2) fixture displays both groups and no false
  shared AR order;
- an unknown provenance operation remains downloadable and generically visible;
- an unsupported operation cannot be executed or trigger privileged UI;
- a saved-view update does not change the revision or snapshot digest;
- layer order and presentation survive saved-view round trips;
- revoking a link invalidates it without affecting project members;
- a headless CLI login can be approved in another browser session;
- project-scoped credentials cannot read or publish to another project;
- the complete definition-of-done flow in the product definition passes
  locally.

### Status (2026-08-22)

Built on the Stage 1/3 spine (Stage 2 still deferred). Identity is the
built-in **local provider** (email + PBKDF2 password, bootstrapped from
`NP_OWNER_EMAIL`/`NP_OWNER_PASSWORD`) behind the `Identity` algebra; the
device-code flow, sessions, user tokens, credentials, and share links are
provider-independent, so an OIDC provider is a swap, not a redesign. Verified
by `scripts/e2e.sh` (device login approved from a separate session, credential
scoping, push with the stored token) and eight Playwright scenarios:

| Criterion | Evidence |
| --- | --- |
| heterogeneous AR(1)/AR(2) cohort | provenance endpoint groups receipts per facet (`shared=false`, 22 × AR(2), 4 × AR(1)); inspector shows "Inputs differ on temporalNoise" with one row per group; a receipt lacking a key forms a `null` group so `shared` is never falsely true |
| unknown operation | `org.example.lab/smooth` rendered "retained, not interpreted" with a payload download and no actions; `interpretation = unsupported` in the read model |
| unsupported op cannot execute | no execution surface exists; unsupported nodes carry no buttons (asserted) |
| saved view leaves the digest unchanged | save + reopen; `GET /revisions/{id}` digest identical before and after |
| order and presentation survive a saved-view round trip | threshold and layer order restored from `org.neuropublish.view/workspace-state@1` via the same path as the URL |
| revoking a link | anonymous viewer sees the read-only presentation, explores locally, returns to the saved version; after revoke the link answers 410 while members keep access |
| headless CLI login approved elsewhere | `npub login` prints URL + code; a separate session approves via `auth/device/approve`; the token lands in `~/.config/npub/credentials.json` (0600) |
| project-scoped credentials | a credential for `sherlock` used on another project → 403 before any existence check |
| definition of done | all ten steps of the product definition's first slice pass locally (overview → measures → controls → readouts → facts → save → share → reset → digest) |

Principal rules live in `backend/Auth.scala`; secrets are stored only as
SHA-256/PBKDF2 hashes (a test walks the data dir for leaks); the deprecated
static token is `NP_LEGACY_TOKEN` (deliberately not `NP_TOKEN`, which the CLI
reads).

Review follow-ups landed after the first slice: user tokens expire after 30
days and are revoked by `npub logout` (`POST auth/logout` on a bearer) or all at
once (`DELETE auth/tokens`); viewers cannot save views or mint links; the share
route returns a *presentation subset* (`SharedProjection`: the addressed
version only, the manifest reduced to what the page renders — no provenance,
method payloads, open records, or saver identity) and a link can only be minted
for a `sensitivity: group-level` revision (policy checked at share creation);
provenance `interpretation = understood` is an allow-list of schema ids the
server reads, with a `computeVersion` stamped into the cache; non-members get
404 on any addressed record; `NP_BASE_URL` names the public origin for share,
device, and rendition URLs and turns on `Secure` cookies under https; a members
API (`workspaces/{ws}/members`) lets an owner/admin attach users, and a
two-workspace test pins the isolation. Deferred, not built: signed object GETs
and bucket CORS (Stage 2, with the object store); rate limiting on login and
the device flow (Stage 6 hardening). Carried: the Components-artboard visual
pass; atlas lookup; surface rendition fidelity (Stage 5).

## Stage 5 — surface, hybrid workspace, and R clients

### Surface and hybrid display

- thin Laminar adapter around ScalaFIM `SurfaceRenderPlan` and Three.js backend;
- bilateral surface geometry from immutable catalog assets;
- explicit left/right scalar fields supplied by the publication bundle;
- shared application layer identity and presentation mapping;
- surface pick to RAS+ world coordinate;
- volume-to-surface coordinate linking with an explicit distance/failure state;
- Volume, Surface, and Hybrid preset persistence;
- no automatic projection when a representation is absent.

### R clients (joins the R track here)

- `fmrigds::describe_result()` and `as_neuropublish()`;
- `fmrireg` receipt bridge;
- `neuromosaic::publish()` wrapper that preserves static Quarto output as a
  companion artifact;
- `rMVPA` adapter over its existing maps, tables, manifests, runtime context,
  session information, and Git identity;
- R-to-Scala golden fixtures and privacy checks.

Status (2026-08-23), R client core: done in this repository as
`clients/r/neuropublish` (CRAN-clean `R CMD check --as-cran`). It holds the
neutral half of the R track: plain-list builders for the core 0.1 vocabulary,
`np_domain_volume()` with the ADR 0005 fingerprint recomputed in R (tests pin
it to the reference and Julia fixtures), `np_write_bundle()` under the byte
profile, `np_pack`/`np_validate`/`np_login`/`np_push` over the `npub` CLI
(`scripts/npub` launcher; stale parent → `np_stale_parent` error with the
head), the `as_neuropublish()` generic with its contract and a reference
method for a list of `NeuroVol`s, `np_publish()` as the one high-level call,
and a vignette that rebuilds the Julia producer's bundle from `neuroim2`
volumes. Not here, by design: the `fmrigds`, `fmrireg`, `rMVPA`, and
`neuromosaic` methods (they live in those packages and implement
`as_neuropublish()`; the vignette shows what each returns), and the
R-to-Scala golden fixtures and privacy checks, which land with those methods.

Review fixes (2026-08-23), from a fresh-context review of the R client. Each
item names what changed and the evidence.

- **Numbers are written at 17 significant digits.** `np_write_bundle()`
  serialized with jsonlite's default (`digits = NA`, 15 significant digits)
  while `np_volume_grid_fingerprint()` hashed full-precision doubles, and
  admission recomputes the volume-grid key from the *parsed* payload
  (`ManifestChecks.domains`), so a value the writer rounded was a rejected
  revision. `digits = I(17)` round-trips every double exactly (verified for
  `cos(1°) = 0.9998476951563913`, which 15 digits does not preserve). Scope
  correction to the review: through `np_domain_volume()` the bug could not
  fire, because `neuroim2::NeuroSpace()` stores `signif(trans, 7)`; it fires
  for an affine hashed at full precision (the exported
  `np_volume_grid_fingerprint()` plus a hand-built descriptor) and for any
  full-precision number anywhere else in a manifest. Tests: hashed values ==
  parsed values for a rotated affine, both hand-built and through a
  `NeuroSpace`; the same bundle packed and admitted by `npub validate`
  (`NPUB_TESTS=1`).
- **`npub` grew a `--json` output mode** (`validate`, `pack`, `push`): one
  document on stdout, progress on stderr, `{"ok":…}` / `{"problems":[…]}` /
  `{"error":{"type","message"}}`, human output unchanged as the default. The R
  client parses that document; every line regex is gone, so a runtime failure
  ("no such file", "cannot reach") is now an `np_cli_error` condition carrying
  the CLI's `type` instead of a one-row problem frame, and a message
  containing `": "` can no longer masquerade as a pointer. `Push.run` was
  split into an `Outcome` ADT with human and JSON renderers. Evidence:
  `JsonOutputSuite` (8 tests) in `publisherCli/test`, R fixture-string tests
  for every document shape, and a fake-`npub` shell script driving the real
  `np_run()` path.
- **Empty payloads are objects.** `np_record(payload = list())` and
  `np_field(selection = list())` serialized as `[]`; they now emit a named
  empty list, asserted against the JSON text and the written bytes.
- **`np_login()` streams the device flow.** The `system2()` fallback captured
  stdout, which buffers until exit — i.e. until after the user needed the URL
  and code. With `echo` it connects both streams to the console.
- **Asset file-name collisions are refused.** `np_safe_file_name()` maps
  `"a/b"` and `"a_b"` to one file; `np_write_bundle()` now names both ids and
  refuses before writing anything.
- **`key$size` is numeric, not `as.integer()`** (which returned `NA` past
  2^31 while the server reads a `Long`), written as an integer literal with no
  exponent and no `.0`; `shape` must be three positive whole numbers.
- **Hermetic checks.** CLI-backed tests skip unless `NPUB_TESTS=1` (or the
  `NOT_CRAN=true` that `devtools::test()` sets) *and* `npub` resolves; the
  vignette's `npub` chunks follow the same gate, and the vignette documents
  how to run each group. `tools` is declared in Imports; the tests no longer
  need `withr`.
- **`as_neuropublish.list`'s contract is documented** in `?as_neuropublish`
  (a named list of same-grid `NeuroVol`s, what the names mean), and a list
  holding anything else fails naming the offending element's class and
  pointing at writing a method.
- Verified: `publisherCli/test` 35 tests / 0 failures; `R CMD check --as-cran`
  on the built tarball with `[ FAIL 0 | WARN 0 | SKIP 7 | PASS 147 ]` and two
  environmental NOTEs (CRAN incoming feasibility: new submission and a
  not-yet-public GitHub URL; local HTML Tidy too old); the same suite with
  `NPUB_TESTS=1` runs the CLI tests, 0 failures.

### Surface rendition track (2026-08-23)

Protocol: `surfaces[]` (`{id, asset, domain, hemisphere, kind, label}`), surface
representations (`{kind: "surface", asset, surface, hemisphere, derivation?}`),
the trusted `org.neuropublish.domain/surface-vertices@1.0` descriptor with the
ADR 0005 `surface-vertices/v1` key (`records/surface-vertices-v1.schema.json`,
pinned by digest), and the admission split recorded in SPEC §6: admission
checks what the manifest determines (payload, key agreement, `size` =
`vertexCount`, the topology surface's hemisphere); ingestion recomputes the
fingerprint from the GIFTI triangles and fails the revision on disagreement.
Renditions: `surface-mesh@0` (float32 positions + int32 faces, `topologyIdentity`
= ScalaFIM's stable key) and `vertex-field-f32@0`, decoded on JVM and Scala.js
by `SurfaceRendition.decode`/`VertexFieldRendition.decode`; `RenditionRef`
carries `kind` and `surface`. Fixtures: the Julia producer writes two icosphere
hemispheres (642/1280, ±30 mm) as GIFTI with t and z vertex fields and an
oracle; the reference bundle carries the same assets. Verified: fidelity on both
platforms, fingerprint against the manifest, ingestion refusing a
foreign-topology surface and a wrong-length field (inline and worker, local and
S3 stores), the admission fixtures `invalid/surface-*` and
`invalid/representation-*`.

| Criterion | Evidence |
| --- | --- |
| surface rendition fidelity | `SurfaceFidelitySuite` (JVM + JS): exact coordinates, faces, topology identity, field values, sums against the Julia oracle; decode → encode identity |
| topology admission | `GiftiToRenditionSuite`: triangles hash to the manifest key; `IngestionSurfaceSuite`: a permuted-face surface and a 4-vertex field are refused with messages naming the asset and both fingerprints |
| coordinate system | the mesh payload is world positions (the GIFTI transform applied once at ingestion, `surfaceToWorld` the identity, `sourceTransform` kept); a header with another system is refused by the decoder |
| explicit left/right fields | `speech-t`/`speech-z` carry one surface representation per hemisphere; `renditionTargets` lists both vertex fields on their surfaces |
| honest empty state | a field without a surface representation has no vertex-field target (`speech-effect`, `speech-se`); the workspace shows it as absent (workspace track) |
| no projection without a receipt | the server never projects: a vertex field is the producer's GIFTI; the reference and Julia representations name `derivation: project-to-surface`, an activity admission resolves. `derivation` stays optional — admission cannot tell a measured field from a projected one — and its absence declares a native surface measurement (SPEC §5) |

Not done here: the surface/hybrid workspace itself (the workspace track builds
it against the decoders above); picking and volume–surface linking.

#### Review fixes (2026-08-23)

From the fresh-context reviews of `c0ba616..b1b153f`
(`docs/plans/stage5-review-fixes.md`, Track R). Every item has a test that
fails without it.

- **B1, surface and volume spaces (`ManifestChecks.spaces`).** A
  `surface-vertices` domain's `space` must equal every `volume-grid` domain's
  `space` in the same manifest, refused at
  `/domains/i/descriptor/payload/space` (`invalid/surface-space-mismatch`).
  The mesh rendition header now carries `space` so a reader can refuse to link
  across spaces defensively. The Julia producer and the reference bundle put
  the icospheres in the volume's space, where they always were geometrically
  (±30 mm in the volume's RAS+ world), which moved the two surface
  fingerprints and both bundle digests.
- **B2, `topology` names an asset.** The check always compared
  `surfaces[].asset`; SPEC §5/§6, the records schema description, and the
  error text now say so, and the reference bundle's surfaces are
  `lh-pial-surface` / `rh-pial-surface` on assets `lh-pial` / `rh-pial`, so
  the two spellings can no longer coincide. The Julia bundle keeps `id ==
  asset`, so a check that confused them fails on one bundle or the other.
  `invalid/surface-topology-surface-id` covers the confusion directly. Editing
  the records schema repinned its digest in `TrustedSchemas` and every fixture
  that names it.
- **S3, sparse and rank-2 vertex fields (`Derivation.fieldValues`).** A
  `.func.gii` carrying `NIFTI_INTENT_NODE_INDEX` is refused by name instead of
  having its vertex indices stored as values; the field array must be rank 1
  or `Dim1 = 1`, so a V×T `TIME_SERIES` is refused for what it is rather than
  through a vertex-count message.
- **S5/S6, the bytes decide the placement and the hemisphere
  (`Derivation.worldGeometry`).** The GIFTI's
  `CoordinateSystemTransformMatrix` is applied to the positions at ingestion
  when its `TransformedSpace` is one this build can place; the payload is
  therefore world positions, the header's `surfaceToWorld` is the identity —
  so `SurfaceHost.pick` composing through it stays correct and applies nothing
  twice — and the matrix is kept as `sourceTransform` for provenance. A
  non-identity transform into an unplaceable space is refused rather than
  labelled `RAS+`. Where the GIFTI states `AnatomicalStructurePrimary` it must
  agree with the declared hemisphere, and the header records it.
- **S9, evidence that is not the producer's own arithmetic.**
  `SurfaceFidelitySuite` derives 642 = 10·4³ + 2 and 1280 = 20·4³ and vertex
  0's position from the icosahedron construction, never from `oracle.json`.
- **S11, a stable topology key.** `faceDigest` (SHA-256 of the payload's face
  bytes) is carried and verified on decode; `topologyIdentity` stays as the
  reference implementation's key and is documented as such, so a ScalaFIM hash
  change would be a profile change rather than a silent invalidation.
- **Asset roles.** An asset backs at most one `surfaces[]` entry
  (`invalid/surface-asset-shared`) and is not both a geometry and a volume or
  vertex-field asset (`invalid/surface-asset-two-roles`); the ingestion cache
  is keyed by asset, so a geometry is read, placed, and proven once.
- **A stored-rendition format break.** `space` is required on a
  `surface-mesh@0` header and the decoder refuses one without it (strict on
  purpose: absent-means-compatible would reproduce B1 on every rendition
  written before this change). Renditions derived before this change must be
  re-derived after deploy — and no command does that today: `reindex` rebuilds
  the read model from the stored manifests and deliberately keeps
  `derived_representations`, and `gc` deletes rendition sets only for
  revisions that are no longer live. Re-deriving an existing revision needs a
  new subcommand (or a job re-enqueue); it is not in this track.
- **Smaller.** Float32-only GIFTI sources are stated in SPEC §5 and refused
  with the type to write instead (float64 support is filed upstream against
  ScalaFIM); the domain fingerprint is compared as parsed digests, not as
  strings; a surface on a trusted domain whose payload does not read no longer
  claims the domain "is not a trusted surface-vertices domain" (the payload's
  own problems say it); `VolumeGrid`'s preimage magic uses an escape instead
  of a raw NUL byte; `SurfaceRendition.decode` and
  `VertexFieldRendition.decode` do their size arithmetic in `Long`.

| Criterion | Evidence |
| --- | --- |
| one space per revision | `invalid/surface-space-mismatch`; `FixtureSuite` asserts the reference surface domains carry the volume domain's space; the mesh header's `space` is asserted end to end in `IngestionSurfaceSuite` |
| `topology` is an asset | `invalid/surface-topology-surface-id`; `GiftiToRenditionSuite` asserts `topology == asset` and `topology != surfaces[].id` on a bundle where they differ |
| GIFTI decisions | `DerivationGiftiSuite` (10 tests on hand-written ASCII GIFTI): sparse, rank-2, float64, hemisphere disagreement, transform applied, unplaceable transform refused |
| hemisphere against the bytes | `IngestionSurfaceSuite`: `lh-pial` declared `right` admits and then fails ingestion naming `AnatomicalStructurePrimary=CortexLeft` |
| world positions | `SurfaceFidelitySuite`: identity `surfaceToWorld`, `sourceTransform` recorded, and an encode of a geometry with a non-identity transform is refused |
| independent geometry | `SurfaceFidelitySuite`: counts and vertex 0 derived from the icosphere construction |

### Exit criteria

- one result switches among volume, surface, and hybrid layouts without
  changing its scientific field identity;
- a pick returns the same world coordinate within the declared tolerance in
  both panes;
- mismatched topology or coordinate-system identity is rejected;
- an absent surface representation produces an honest empty state;
- no projection is performed without a recorded derivation receipt;
- an R user can publish the reference result with one high-level call after
  login;
- static Quarto export and interactive publication can coexist from the same
  source description.

### Status (2026-08-23) — surface and hybrid display

Workspace track, built against the parallel surface-rendition track's
signatures (`SurfaceRendition` / `VertexFieldRendition` decoders; `kind` and
`surface` on `RenditionRef`; manifest `surfaces[]` and `{kind: "surface"}`
representations, read from `Manifest.raw` until the typed projection lands).

- `SurfaceHost` (viewer-laminar) is a real ScalaFIM host: it owns the
  `ThreeJsRuntime` + `ThreeSurfaceBackend`, a `SurfaceViewerModel` with its
  reducer state, renders the compiled `SurfaceRenderPlan` on coalesced
  animation frames tracked by the same `LifecycleProbe` as `VolumeHost`, picks
  to `(surface, vertex, world)` through `SurfaceWorldLink.worldPoint`, links a
  world cursor with an explicit `Linked(vertex, distance) | OutOfRange(distance)
  | NoGeometry` result at the product's 3 mm radius, and disposes idempotently
  (the runtime forces context loss). A pane creates its canvas per mount
  because a canvas whose context was deliberately lost cannot take a new one.
- One result field is one layer wherever it has a representation:
  `WorkspaceLayer.representations` (volume · left/right surface) is a fact of
  the revision — the reducer never changes it, a saved view cannot change it,
  and the card says "drawn in: volume · left surface · right surface". The
  same threshold/window/colormap controls drive both renderers
  (`SetLayerThreshold` etc. per hemisphere layer `field@hemisphere`).
- Presets are wired: Volume = triplanar, Surface = bilateral (left/right
  slots, `SurfaceLayout.Bilateral`), Hybrid = both behind a CSS-grid divider
  (`role=separator`, pointer drag or arrow keys; `splitFraction` in
  `workspace-layout@1`). A pick in either pane sets the shared world cursor;
  the other pane shows the link state ("linked to vertex 1234, 0.8 mm" /
  "no vertex within 3 mm" / "… has no surface representation"). The status bar
  reads out the volume voxel value, the surface vertex value per hemisphere,
  and the link distance. `ViewUrl` adds `sc=viewpoint,projection` and
  `sf=fraction` (omitted at the defaults); the saved-view record adds
  `surfaceCamera`, `splitFraction`, and `representations`, all optional on read
  so Stage 4 records still decode (version stays 1).
- No projection: a visible field without a surface representation is an empty
  state naming the field; the geometry is drawn bare, never sampled from the
  volume. A revision without surfaces says so.
- Tests: viewer-state property suites cover the new state (reducer
  invariants, URL and saved-view round trips, Stage 4 record compatibility,
  a saved view cannot smuggle representations or an invalid camera); the
  lifecycle suite mounts a real two-hemisphere model through `SurfaceHost`
  (24-cycle sentinel still green) and pins the link/pick contract (0.5 mm
  links, 10 mm does not, a pick links back at 0 mm); `e2e-stage5.spec.mjs`
  covers preset switching without identity change, linked picks both ways,
  the empty state, URL/saved-view/presentation round trips of proportions and
  camera, disposal across preset cycles and navigation, narrow layouts, and —
  against the merged reference bundle (`lh-pial`/`rh-pial` icospheres,
  `speech-t`/`speech-z` surface representations) — a URL cursor at a Julia
  oracle probe vertex links at 0.0 mm and reads the oracle's vertex values in
  the status bar, while the origin (5 mm off the nearest sphere) reports "no
  vertex within 3 mm". `scripts/e2e.sh` runs the browser scenarios green
  through the Julia producer gate (15 at this point; 18 after the review fixes
  below).

Not done here: atlas lookup; the Hybrid artboard visual pass (design README);
topology/coordinate-identity rejection and the derivation-receipt gate live
with the rendition/admission track.

#### Review fixes (2026-08-23) — workspace track

From the fresh-context review of `c0ba616..b1b153f`
(`docs/plans/stage5-review-fixes.md`, Track W). Each item is verified by a
unit test where the logic is pure, by an e2e assertion where only the page
shows it.

- **Placement rule (S2).** The surface pane has one slot per hemisphere.
  `SurfacePlacement.place` fills a slot with the first surface in manifest
  order that some result field targets with a decoded surface representation,
  and with the first declared surface when no field targets any. Deterministic,
  and a field lands on the surface it was published for whenever one surface
  per hemisphere carries data. Representations are counted against the *placed*
  surfaces only: a decoded representation on an unplaced surface is named on
  the card ("not drawn on lh-white") instead of being dropped, the navigator
  never claims "surf L" for a field that is not drawn, and no display action
  can name a layer id the model does not contain.
- **Layer identity (S1).** Surface layers are keyed `field@surface`, not
  `field@hemisphere`, so `lh-pial` and `lh-white` cannot collide. A rejected
  `SurfaceViewerModel` — or a rejected layer — is no longer swallowed by
  `.toOption`: it becomes a visible pane message (`surface-error`) and a
  console error.
- **View state across presets (S7).** `SurfaceHost` keeps the reducer state of
  a disposed pane and rebuilds a remounted pane from it, so zoom, orbit,
  lighting, and the selection survive Volume → Hybrid → Surface. The store
  reads the camera back from the renderer on detach, so a saved view's
  `surfaceCamera` records what the pane showed.
- **Honest link distance (S8).** `Link.Linked` carries the measured distance
  from the picked vertex; a failed transform is a not-linked state, never
  "0.0 mm". An unchanged selection no longer schedules a frame, and the
  cursor-source badge is cleared when the pane that claimed it is gone.
- **Space guard (B1, client half).** A placed surface and the underlay are
  compared through their declared spaces (the surface-mesh header's `space`,
  the underlay's `volume-grid` domain payload). Different spaces never link:
  the readout says which two, and a surface pick selects the vertex without
  moving the shared cursor. An absent space on either side still links —
  admission is where a mismatch is rejected.
- **Per-hemisphere absence (product rule).** A field with one hemisphere says
  so ("left surface only; no right-hemisphere representation"). Surface values
  state their derivation activity, or — the deliberate non-change — "native
  surface measurement; no projection receipt declared".
- **Divider and canvas details.** The splitter captures the pointer and
  releases its window listeners on unmount mid-drag (keyboard handling
  unchanged); panes resize on a device-pixel-ratio change, not only on a box
  change; `splitFraction` is quantised to the URL's four decimals so a dragged
  divider round-trips exactly (property test); the dead "cannot be rendered
  yet" threshold branch is gone.
- **Tests.** viewer-state gains the URL round-trip property; the frontend
  gains `SurfacePlacementSuite`, `SpaceGuardSuite`, and `LoadedSurfaceSuite`
  (a revision declaring `lh-pial` and `lh-white`, which the reference fixture
  cannot express cheaply); `e2e-stage5.spec.mjs` gains a right-hemisphere pick
  asserted against the hand-derived icosphere radius, a threshold that
  repaints the surface pane and a reset that restores it pixel for pixel, and
  the card's absence/derivation lines. `scripts/e2e.sh` runs 18 browser
  scenarios green, the lifecycle suite 4.

## Stage 5b — post-MVP finite-indexed and parcel track

This track depends on the Stage 2 domain envelope but does not block the
volume-only MVP. The locus4s/image4s/ScalaFIM exact-domain prerequisite is now
present at the immutable baseline tested in the dependency spike. Neutral
protocol work may proceed while a dedicated Neuropublish consumer migration
adopts the native image API and restores the proven resource-lifecycle
contract; Scala realization adapters wait for that migration, not for a new
domain abstraction.

**Implemented neutral slice (2026-08-24).** The trusted finite-indexed and
hard-assignment schemas, exact key preimage, declaration checks, ingestion-time
ordinal/coverage checks, cross-domain mapping-plus-derivation rule, and a
packed volume parcel fixture are present and tested on JVM and Scala.js.
Same-sized reordered and foreign Schaefer-style identities fail closed. The
fixture's volume is still a producer-authored derivative: server-derived
pullbacks, a surface realization, `allow-empty` user warnings, label-to-key
conversion records, linked parcel selection/table UI, and the ScalaFIM
consumer/adapter migration remain open. The R client and standalone Julia
oracle do agree with Scala on the finite preimage and hard-assignment bytes.
The Stage 5b exit criteria below therefore remain the completion bar rather
than a claim about this slice.

### Protocol and admission

- trusted `finite-indexed/v1` descriptor with a deterministic ordered-key
  binary identity preimage;
- hard-assignment records as checked partial maps from an exact volume or
  surface support domain to an exact parcel domain;
- version-1 assignment assets encoded as one signed int32 little-endian value
  per support element, with `-1` for background and `0 .. |P| - 1` for parcel
  ordinals;
- explicit `complete` and `allow-empty` coverage policies; the server computes
  the empty parcel keys, requires the `allow-empty` declaration to match, and
  surfaces a warning for admitted incomplete realizations;
- label-coded source images converted through a recorded label-to-parcel-key
  table, converter identity and version, parameters, and output digest;
- hierarchy maps that preserve the authoritative parcel-domain order;
- distinct reserved records for overlapping Boolean ROI relations and future
  probabilistic membership; neither is admitted as a hard assignment.

### Realization and workspace proof

- one scalar field whose scientific domain is an exact parcel index;
- one volume and one surface atlas realization targeting that parcel domain;
- a searchable parcel table linked to slice and surface selection;
- server-derived spatial pullbacks that retain the parcel field as the
  scientific authority and record source field, realization, converter,
  parameters, and output digest;
- ScalaFIM atlas adapters built only after Neuropublish adopts the native image
  API and the admitted lifecycle contract is merged forward;
- no inferred atlas from parcel count and no overlapping/probabilistic
  composite rendering policy in this stage.

### Exit criteria

- the correct ordered parcel domain admits, while a same-sized reordered or
  foreign domain fails with a path-specific error;
- a complete hard assignment upgrades to a certified partial surjection;
  an assignment with empty targets is admitted only under `allow-empty`, keeps
  the full parcel domain, and reports the exact empty parcel keys;
- a label-coded atlas without an explicit label-to-key conversion receipt is
  rejected;
- an overlapping ROI relation cannot pass the hard-assignment validator;
- a cross-domain pullback without a complete derivation receipt is rejected;
- parcel selection resolves to the same stable key and value in the table,
  volume, and surface views;
- JVM, Scala.js, R, and Julia fixtures agree on finite-domain and assignment
  identities.

## Stage 6 — hardening and first hosted release

### Deliverables

- production object-store and PostgreSQL deployment;
- backups, migration rehearsal, metrics, structured logging, and alerts;
- content-security policy, signed-URL review, rate limits, and upload quotas;
- sensitivity and identifier scanning with safe refusal messages;
- protocol migration, compatibility, fuzz, and adversarial reference tests;
- an adversarial producer in the conformance suite: a client that declares
  digests it does not own, reuses another tenant's object keys, swaps
  manifests after commit, tampers presigned transfers, and polls out of
  interval — the Stage 2 review found the cross-tenant manifest overwrite
  precisely because the honest Julia producer could never exercise it;
- Playwright browser matrix and fixed visual fixtures;
- accessibility audit;
- browser memory and large-map load budgets;
- canonical static fallback previews;
- operator runbook for credential rotation, link revocation, restore,
  reindex, and recoverable project deletion;
- release and rollback procedure with exact Scala dependency evidence.

### Exit criteria

- the complete definition-of-done flow passes against the hosted deployment;
- backup restore recreates project, revision, manifest, saved view, and access
  policy in a clean environment;
- private assets cannot be fetched after URL expiry or authorization loss;
- a failed deployment rolls back without changing committed revision identity;
- R, Julia, Scala, and HTTP-only fixtures remain compatible;
- all upstream Scala dependencies are reproducibly resolvable from exact
  artifacts or revisions;
- known privacy, browser, and scientific limitations are visible in the product
  and release notes.

## Parallel R track — portable receipts and descriptors

This work lives in the owning R repositories with their own release cycles.
It informs the protocol vocabulary but never blocks Stages 1–4, because the
hand-written fixture already exercises every provenance interface.

- add a structured `fmrireg::analysis_receipt()` containing design identity,
  contrasts, estimation, temporal noise, diagnostics, runtime, and hashes;
- give `fmrigds` portable semantic descriptors for reducers, post-hoc methods,
  assays, maps, and alignment;
- preserve unknown plan operations in inspect mode while refusing to execute
  them;
- replace closed assay-role admission with namespaced semantic descriptors and
  lawful relations;
- retain all first-level receipt references and calculate compatibility groups;
- export a portable provenance graph rather than relying on R-serialized
  alignment objects.

Exit: the real `fmrigds` reference result, published through
`as_neuropublish()`, reproduces the same provenance interface behaviour the
hand-written fixture established in Stage 4.

## Tracker work items

The cross-repository coordination store uses these stable IDs. Child repository
implementation should be linked from these issues and tracked in each owning
repository once those stores and paths exist.

| Mote ID | Work item | Principal proof |
| --- | --- | --- |
| `neuropublish-v0-1` | MVP coordination epic | End-to-end hosted definition of done |
| `neuropublish-v0-1-bootstrap` | Repository, dependency bootstrap, rendition and lifecycle spikes | Clean JVM/JS scaffold, external resolution, fidelity measurements |
| `neuropublish-v0-1-protocol` | Neutral core protocol | Versioned schemas, typed validation, byte digest, route scheme |
| `neuropublish-v0-1-parcel-domains` | Stage 5b finite-indexed and parcel-domain track | Ordered identity, coverage-aware hard assignments, mismatch fixtures, linked parcel-space proof |
| `neuropublish-v0-1-foreign-producer` | Foreign-producer conformance | Julia bundle publishes without Scala SDK |
| `neuropublish-v0-1-viewer-prereqs` | Reusable Scala viewer prerequisites | Thresholds and lifecycle are consumed at the current pin; remaining work is multi-stop diverging palettes with a movable neutral stop, a typed surface clear colour, and merging lifecycle contracts forward onto the native image line |
| `neuropublish-v0-1-publication-spine` | Thin spine, ingestion, upload, and immutable revision commit | Push → commit → derived renditions → two overlays visible; resumable direct upload, linear history, reindex |
| `neuropublish-v0-1-volume` | Volume scientific workspace | Underlay plus two overlays with truthful controls |
| `neuropublish-v0-1-identity-sharing` | Identity, saved views, and sharing | Device login and revocable read-only view |
| `neuropublish-v0-1-provenance` | Portable provenance and application provenance UI | Heterogeneous fixture represented truthfully; real `fmrireg`/`fmrigds` receipts follow |
| `neuropublish-v0-1-surface` | Surface and hybrid workspace | Linked RAS+ picking with exact topology admission |
| `neuropublish-v0-1-r-clients` | `fmrigds`, `fmrireg`, `neuromosaic`, and `rMVPA` receipts and adapters | One-call R publication through the neutral bundle |
| `neuropublish-v0-1-hardening` | Hosted release hardening | Security, restore, browser, and compatibility gates |
| `neuropublish-post-mvp-docking` | Evidence-led docking experiment | Presets versus typed DOM versus vendor spike |

## Risk register

| Risk | Early signal | Response |
| --- | --- | --- |
| Protocol becomes Scala-shaped | A foreign producer needs generated Scala knowledge | Keep JSON Schema normative and require Julia conformance before freeze. |
| Open semantics become stringly | UI branches on labels or arbitrary keys | Namespaced IDs, schema digests, typed known modules, preserved unknown records. |
| Parcel values attach to the wrong atlas | Equal length or label text is treated as alignment | Exact ordered domain fingerprints, explicit atlas mappings, and foreign/reordered fixtures. |
| Extensions claim unsafe behavior | Unknown statistic enables inferential conversions | Core controls rendering; only trusted modules grant privileged transformations. |
| Browser cannot ingest canonical assets faithfully | Affine/topology mismatch in the typed-binary rendition | Stage 0 fidelity measurement; retain canonical assets; server-side derivation keeps readers on the JVM. |
| Ingestion worker becomes a hidden second server | Control plane starts touching asset bytes | Worker is a separate process with its own queue; control plane only records its results. |
| First map appears too late | Identity or sharing work precedes a visible overlay | Stage 1 thin spine is the first gate; static token until Stage 4. |
| Product duplicates ScalaFIM | Viewer-specific state or color logic appears in frontend | Move reusable behavior upstream and require JVM/JS conformance. |
| Pre-release dependency graph blocks deployment | Local siblings compile but clean consumer fails | Exact pins, explicit overrides, provider release plan, external consumer gate. |
| Native ScalaFIM migration breaks the admitted consumer | `NeuroVol`/`NeuroSpace` no longer compile or the volume controller offers only `close()` | Keep the current admitted pin; migrate the rendition API and merge the owning-library lifecycle contracts forward as one JVM/JS/browser-gated change. |
| Docking destabilizes canvases | Reparenting loses state or WebGL contexts | Presets first; lifecycle harness before any docking engine. |
| Login is harder than Here.Now | Tokens copied manually for normal pushes | One-time device flow, project credentials only for automation, direct URL output. |
| Dedup leaks private asset existence | API reports another tenant's digest | Hide cross-tenant existence and separate public catalog assets. |
| Provenance presents modal settings as shared | Mixed receipts collapse to one value | Fingerprint groups and compatibility assessment; no inferred consensus. |
| R package release cadence gates the product | Stage 4 waits on an `fmrireg` CRAN release | R track is parallel; hand-written fixture covers every provenance interface. |
| Surface canvas cannot follow the product theme | `ThreeJsRuntime.clearFrame` keeps an opaque white clear colour | Add a typed clear-colour/background option in ScalaFIM and consume it through the thin Laminar host; do not reach into Three.js from the product. |
| Public sharing leaks sensitive metadata | Subject IDs or local paths enter manifest | Local and server admission policy; conservative default sensitivity. |
| Browser memory grows without bound | Revision switching retains arrays/GPU resources | Hash-keyed bounded cache, explicit resource ownership, disposal tests. |

## Decisions that remain deliberately open

These choices have a decision deadline rather than an implicit default:

1. **Alpha identity provider:** select in Stage 4 after the device-flow spike.
   The domain remains provider-neutral.
2. **Hosted object store and region:** select before Stage 6 after data-policy
   review. Keep the S3-compatible boundary.
3. **Multi-workspace enablement timing:** the schema is ready from Stage 2;
   enable creation and invitation flows when a second lab asks or at Stage 6,
   whichever is first.
4. **Compare layout timing:** include only if Volume and Hybrid state reuse make
   it a small extension.
5. **Branching revision history:** revisit only if a real workflow needs two
   live heads in one project.
6. **Arbitrary docking:** revisit only after users encounter a concrete limit in
   the preset workspaces.
7. **PostgreSQL row-level security:** decide by follow-up ADR before a second
   workspace is admitted (ADR 0004).

## Action outcomes (2026-08-24)

1. [x] The bounded threshold/display-window slice and editable Stage 5
   artboards passed JVM/JS, R, PostgreSQL/S3, Julia, and Playwright gates.
2. [x] Generated CI is deterministic, the dependency-submission job has its
   required repository feature/permissions, and the hosted workflow has passed.
   Every pushed release candidate, including the private-alpha change set,
   remains gated on its own hosted run; local evidence is not a substitute.
3. [x] The artboard hierarchy has wide and narrow browser evidence, and a
   curated, licensed anatomical volume and cortical surface pair now supplements
   the deliberately synthetic protocol fixture. `AnatomicalCorpusSuite` pins
   source commits, licenses, sizes, and digests, then exercises the production
   NIfTI/GIFTI rendition paths. The differently spaced samples are explicitly
   not co-registered and the existing protocol screenshots remain synthetic,
   not anatomically realistic.
4. [x] The smallest private-alpha topology is packaged and locally rehearsed:
   one control plane, one worker, PostgreSQL, MinIO/S3 presigned transfers,
   login throttling, CSP/security headers, password rotation, a complete
   database-plus-object backup, isolated restore, reindex, and restored volume
   and surface fetches. No external hosting provider has been selected or
   mutated.
5. [x] The Stage 5b spike compiled against the native atlas baseline far enough
   to prove exact-domain ownership and identify the two real consumer breaks.
   Direct pin bump: no-go. Neutral parcel protocol: go. See the spike record
   for the migration gate.
