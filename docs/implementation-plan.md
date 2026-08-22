# Neuropublish implementation plan

Date: 2026-08-22 (revised after plan review, same day)

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
| surface fidelity | **open** — GIFTI fixture (`tetra_lh_midthickness.surf.gii` from ScalaFIM) not yet run through a rendition; carried into Stage 1 alongside the surface pane |
| lifecycle | Playwright in Chromium: volume pane mount/resize/unmount disposes exactly once and cancels outstanding frames; a sentinel surface pane stays mounted through 24 further surface mount/unmount cycles (more than Chrome's live-context budget) and keeps `contextState = Available` with zero `webglcontextlost` events on any canvas; every `EventTarget.addEventListener` is matched by a remove |
| documentation | `DEVELOPMENT.md`, `docs/architecture.md`, this file |
| rendition decision | **confirmed**: typed-binary derivative (JSON header + float32) decoded into ScalaFIM objects on JS exactly for float32 sources (the fixtures); float64 and wide-integer sources lose precision in the rendition, documented in the profile, and the canonical asset remains the record. In-browser NIfTI decoding not attempted (image4s-nifti JS is Node-only) |

Upstream findings recorded for dogfooding (fix in the owning library, then bump the pin). Filed 2026-08-22 as motes in the owning stores; the first three are implemented on branches (ScalaFIM `neuropublish/lifecycle-dispose-on-97c7ff1` = `2a64eba`, Intaglio `neuropublish/threshold-modes` = `cdf1562`) and pinned here pending merge to their mains: scalafim `bd-01M0NS3CM5…` (controller dispose), `bd-01M0NS3EFB…` (Three context loss), `bd-01M0NS3ERV…` (reorder/colorizer actions), `bd-01M0NS3F26…` (scroll cancel), `bd-01M0NS3FBV…` (atlas keys / locus4s), `bd-01M0NS3FNR…` (Three pin); image4s `bd-01M0NS3FZ8…` (NiftiFileSystem); workspace root, tagged `intaglio`: `bd-01M0NS3G6H…` (threshold modes), `bd-01M0NS3G95…` (multi-stop colormaps).

- `CanvasViewerController.close()` only flips a flag; it does not release the viewer/raster caches or `ImageBitmap` handles. Add a real `dispose()`.
- `ThreeJsRuntime.dispose()` calls `renderer.dispose()` but never forces
  context loss, so disposed canvases keep counting against the browser's live
  WebGL budget until garbage collection; 24 mount/unmount cycles evicted older
  contexts until the host forced loss via `WEBGL_lose_context` itself. Upstream
  should do this in `dispose()`.
- `scalafim-atlas` derives its volume domain key from a runtime `hashCode` and
  has not completed its locus4s migration; both gate ADR 0005's Scala
  realization adapters, not its protocol.
- `CanvasScrollCoordinator` cannot cancel a pending scheduled flush; a host that unmounts mid-burst has no way to stop it.
- `image4s-nifti` abstracts its filesystem behind a `private[nifti]` trait, so a browser backend must be added inside image4s if ever wanted.
- ScalaFIM's atlas path still depends on its local parcellation facade. The
  direct locus4s `PartialSurjection` migration is tracked by
  `bd-01KZ6BCHPJEFGJYEVWQZKMBT8V`; the image4s categorical-image bridge is
  `bd-01KZ6B8RCZ1P8DJG42W2SRCGNR`.
- The current ScalaFIM atlas adapter derives a `VolumeDomain` key from runtime
  `hashCode`. Persistent image4s-backed volume identity is tracked by
  `bd-01KZ6BDWJDSZ34YD0QY573N1V0`. The downstream atlas/publication adapter is
  `bd-01M0NDTJ145M5NH0V7VD5R9ED9` and waits on those migrations; the neutral
  Neuropublish descriptor and conformance work does not.
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
  by the reference result;
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
- return accumulated errors with JSON Pointer paths;
- create compatibility rules, migrations, and unknown-record preservation;
- specify the route scheme: project, revision, view, explore, and presentation
  URLs, since saved views and share links depend on it.

Gaps surfaced by the governing-journey design
([canvas](https://claude.ai/code/artifact/b4e22461-776e-4553-a62a-d0c21d051af8)),
to close before the freeze:

- an analysis-level sample-size field in the core (or a trusted-module
  summary fact), since the overview leads with `n`;
- a project-level description/question as a server record, distinct from
  the snapshot synopsis;
- explicit, normative ordering of estimands and measures under an analysis;
- an optional scope pointer on each warning (field, analysis, or provenance
  node), since warnings surface in three places;
- confirmation that compatibility groups are server-computed from receipt
  fingerprints, and which receipt fields participate.

### Publisher CLI

- `npub validate`;
- `npub inspect`;
- `npub pack` for local-path staging bundles;
- `npub push` with hashing, missing-object negotiation, bounded upload,
  resumption, commit, and URL output;
- credentials outside manifests and command-line arguments.

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

### Neutrality proof

A tiny Julia program that does not use Neuropublish Scala classes must create a
valid bundle and publish it through the documented HTTP or CLI boundary. The
same fixture is decoded and re-encoded by Scala and R without losing unknown
extension fields.

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
  link-shared viewers;
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

## Stage 5b — post-MVP finite-indexed and parcel track

This track depends on the Stage 2 domain envelope but does not block the
volume-only MVP. Its neutral protocol work may proceed while the ScalaFIM
realization adapters wait for the locus4s and image4s migrations recorded in
the upstream findings.

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
- ScalaFIM atlas adapters built only after their tracked locus4s and image4s
  identity migrations land;
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
| `neuropublish-v0-1-thin-spine` | Thin local spine | Push → commit → two overlays visible with a static token |
| `neuropublish-v0-1-protocol` | Neutral core protocol | Versioned schemas, typed validation, byte digest, route scheme |
| `neuropublish-v0-1-parcel-domains` | Stage 5b finite-indexed and parcel-domain track | Ordered identity, coverage-aware hard assignments, mismatch fixtures, linked parcel-space proof |
| `neuropublish-v0-1-foreign-producer` | Foreign-producer conformance | Julia bundle publishes without Scala SDK |
| `neuropublish-v0-1-ingestion` | Server-side rendition and summary worker | Derived representations recorded outside the digest |
| `neuropublish-v0-1-viewer-prereqs` | Reusable Scala viewer prerequisites | Thresholds, colormaps, lifecycle |
| `neuropublish-v0-1-publication-spine` | Upload and immutable revision spine | Resumable direct upload, atomic commit, linear history, reindex |
| `neuropublish-v0-1-volume` | Volume scientific workspace | Underlay plus two overlays with truthful controls |
| `neuropublish-v0-1-identity-sharing` | Identity, saved views, and sharing | Device login and revocable read-only view |
| `neuropublish-v0-1-provenance-ui` | Application provenance | Heterogeneous fixture represented truthfully |
| `neuropublish-v0-1-surface` | Surface and hybrid workspace | Linked RAS+ picking with exact topology admission |
| `neuropublish-r-track` | `fmrigds`, `fmrireg`, `neuromosaic`, and `rMVPA` receipts and adapters | One-call R publication through the neutral bundle |
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
| Scala atlas adapters inherit unstable domain identity | An adapter still uses ScalaFIM's local parcellation facade or a runtime `hashCode` key | Let the neutral protocol and conformance fixtures proceed; block only Scala realization adapters on `bd-01KZ6BCHPJEFGJYEVWQZKMBT8V` and `bd-01KZ6BDWJDSZ34YD0QY573N1V0`, then verify through `bd-01M0NDTJ145M5NH0V7VD5R9ED9`. |
| Docking destabilizes canvases | Reparenting loses state or WebGL contexts | Presets first; lifecycle harness before any docking engine. |
| Login is harder than Here.Now | Tokens copied manually for normal pushes | One-time device flow, project credentials only for automation, direct URL output. |
| Dedup leaks private asset existence | API reports another tenant's digest | Hide cross-tenant existence and separate public catalog assets. |
| Provenance presents modal settings as shared | Mixed receipts collapse to one value | Fingerprint groups and compatibility assessment; no inferred consensus. |
| R package release cadence gates the product | Stage 4 waits on an `fmrireg` CRAN release | R track is parallel; hand-written fixture covers every provenance interface. |
| ScalaFIM atlas migration gates parcel space | `scalafim-atlas` still on its local parcellation facade; `VolumeDomain` key uses `hashCode` | ADR 0005 protocol work proceeds independently; only the Scala realization adapters (Stage 5b) wait on the locus4s migration and the persistent-key fix. |
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

## Immediate next actions

1. ~~Accept ADRs 0001–0005.~~ Accepted 2026-08-22.
2. Initialize the repository at `canardlapin/neuropublish` and the child Mote
   store.
3. Scaffold the cross JVM/JS build and protocol conformance module.
4. Write the hand-written reference bundle, including the heterogeneous
   cohort and unknown-record cases, and record the reference result identity.
5. Run the rendition-fidelity and viewer-lifecycle spikes.
6. Build the Stage 1 thin spine before any protocol freeze or dashboard work.
7. Design the governing journey as artboards (`/design`) now, in parallel
   with bootstrap and the thin spine; log any protocol gaps it exposes.
8. Freeze only the core vocabulary needed by the reference result.
