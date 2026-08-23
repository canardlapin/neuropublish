# Neuropublish technical architecture

Date: 2026-08-22

## Architectural outcome

Neuropublish has a small, stable spatial-results core and an open,
schema-governed scientific semantics layer. The reference product is Scala 3
on the JVM and Scala.js, but the publication contract is independent of Scala.

```text
R / Julia / Python / Scala producer
             |
             | bundle or HTTP control request
             v
       npub publisher CLI
             |
      ┌──────┴──────────┐
      │ control plane   │ direct, signed data upload
      v                 v
Scala/http4s service   object storage <──── ingestion worker
      │                 ▲                   (derives renditions
      v                 │ signed reads       and summaries)
 PostgreSQL          Scala.js browser
                         |
                         v
             ScalaFIM volume and surface viewers
```

The application server handles identity, authorization, metadata validation,
revision commit, saved views, and sharing. Large immutable assets move directly
between producers or browsers and object storage. A separate ingestion worker
reads committed canonical assets from object storage and writes browser
renditions and scalar summaries back; the control plane never streams asset
bytes itself.

## Protocol as the product boundary

### Normative artifacts

The protocol lives under `protocol/` at the repository root:

- JSON Schema 2020-12 documents for the core bundle and each built-in semantic
  record: `protocol/schemas/manifest.schema.json`,
  `protocol/schemas/workspace-state.schema.json`,
  `protocol/schemas/rendition-header.schema.json`, and
  `protocol/schemas/records/volume-grid-v1.schema.json` (`$id`s under
  `https://neuropublish.org/schema/0.1/`);
- a human-readable specification with invariants not expressible in JSON
  Schema, the admission pipeline, compatibility rules, and the route scheme:
  `protocol/SPEC.md`;
- canonical valid and invalid examples and cross-language conformance
  fixtures: `modules/conformance/fixtures/`;
- the snapshot digest procedure (SHA-256 over the manifest bytes as written,
  `protocol/SPEC.md` §2);
- an OpenAPI 3.1 document for the HTTP control plane, generated from
  `modules/api-contract`.

The JVM build validates every manifest against the schema documents (served
from the classpath out of `protocol/`, so there is one copy); the Scala.js
build keeps the decoder, closure, and semantic checks only.

Scala case classes and Circe codecs implement the schemas. They do not define a
Scala-specific wire format. In particular, the wire representation must not
use compiler-derived enum encodings or require a JVM serializer.

### Portable bundle

The normalized directory form is:

```text
speech-model.npub/
├── manifest.json
├── assets/
│   └── sha256/
│       ├── 1a/1a...
│       └── f4/f4...
└── schemas/
    └── sha256-9c....json       # optional extension schemas
```

Package adapters may first write a staging manifest with local paths. `npub
pack` hashes those files and produces the normalized bundle. The committed
manifest contains only content digests or immutable catalog references, never
machine-local paths. Symbolic catalog references are resolved to digests and
written back into the manifest before it is hashed, so a committed bundle is
self-describing without the catalog.

The portable snapshot contains:

```text
core protocol version
snapshot title, synopsis, notes, warnings, sensitivity
axes and selections
finite indexed, spatial, and table domains
exact maps, relations, and spatial-support realizations
canonical assets
analyses and estimands
result fields and representations
provenance entities, activities, and edges
open semantic records
published display recommendations
```

Project membership, parent revision, publication message, share links, and
browser session state are server records and are not part of the portable
scientific snapshot.

Neither are derived representations. Browser renditions, previews, and scalar
summaries (finite range, quantiles, compact histogram, missing count) are
produced by the ingestion worker after commit, recorded on the revision with
their source digests, converter version, and parameters, and excluded from
the snapshot digest. A producer may include precomputed derivatives to shorten
time-to-view; the server treats them as untrusted and recomputes or verifies
them.

### Identity and hashing

- Binary assets use `sha256:<lowercase hex>` identities.
- The manifest digest is SHA-256 over the exact bytes of `manifest.json` as
  the producer wrote it, after catalog references have been resolved into the
  file. The server stores those bytes immutably. There is no canonicalization
  step; any language that can write a file and hash it computes the same
  digest for the same bytes. The digest is sent in upload-session metadata,
  never inside the manifest. Manifests must meet the byte profile in ADR 0001
  (UTF-8, no BOM, one root object, no duplicate keys, scalar-only Unicode,
  finite numbers) or are rejected at admission. Semantic comparison operates
  on the parsed form and is not an identity operation.
- JSON metadata admits only finite numbers. Large arrays and non-finite values
  belong in typed binary assets with explicit missing-value rules.
- Timestamps use UTC RFC 3339 strings.
- IDs in the portable snapshot are stable strings with a restricted grammar;
  server resources use typed prefixed IDs.
- Symbolic catalog references resolve to immutable asset digests before a
  revision commits.

### Stable core

The core protocol understands:

- axes, selections, and labels;
- finite indexed domains with exact persistent identity and ordering;
- volume grids and coordinate transforms;
- cortical topology and coordinate-system identities;
- regions, exact maps, hard parcellations, and declared relation kinds;
- scalar, label, mask, table, and preview representations, distinguishing
  canonical assets from server-derived renditions;
- immutable assets and derivation relations;
- analyses, estimands, result fields, and warnings;
- provenance graph structure;
- presentation recommendations and saved views;
- sensitivity and publication policy.

These concepts determine whether bytes can be loaded and safely displayed.
They must not depend on an extension's unsupported scientific claims.

### Domains, atlases, and support mappings

`Domain` means the ordered scientific set over which a field is defined. It is
more general than an image grid and is not synonymous with an atlas. The wire
model uses a manifest-local reference plus an exact persistent key:

```scala
final case class DomainKeyV1(
  descriptor: SchemaRef,
  size: NonNegativeInt,
  structuralFingerprint: Sha256
)

final case class DomainV1(
  id: DomainId,
  key: DomainKeyV1,
  descriptor: OpenRecord
)
```

These are illustrative validated-domain types, not permission to derive the
wire encoding from Scala. Each trusted descriptor defines a fixed binary
identity preimage from which every producer and the server recompute the
structural fingerprint. These encodings are descriptor-specific and do not
canonicalize the manifest. A finite indexed domain uses length-prefixed stable
keys in domain order; a volume domain includes its space, grid shape, affine,
coordinate convention, unit, and ordinal layout; a surface domain includes its
surface space, hemisphere, topology, and vertex-order identity but not geometry
coordinates. Labels and colors are keyed metadata and do not establish
alignment. ADR 0005 defines the version-1 byte profiles.

Stage 2 freezes this domain envelope and implements only the trusted
volume-grid descriptor required by the reference result. Trusted
finite-indexed interpretation, atlas mappings, parcel pullbacks, and parcel UI
arrive in the Stage 5b parcel track. Unknown descriptors remain preserved and
inspectable but confer no rendering or alignment behavior.

A parcel field is defined over a finite parcel domain. An atlas realization is
a separate mapping from an exact volume or surface support domain to that
parcel domain. The first mapping kind is a hard assignment represented as a
checked partial map: background is absence and each supported voxel or vertex
has exactly one parcel. Coverage is certified separately. `complete` requires
every parcel to have a non-empty fiber and permits a locus4s
`PartialSurjection`; `allow-empty` retains the full target domain, records the
producer-declared and server-verified empty parcel keys, and produces a
warning. Overlapping ROI collections are Boolean relations, and probabilistic
atlases are future weighted relations; neither may be disguised as a hard
parcellation.

The version-1 hard-assignment asset is one signed int32 little-endian ordinal
per source element. `-1` is background and `0 .. |P| - 1` address the parcel
domain in its authoritative order. A source label image must be converted with
an explicit label-to-parcel-key table and a provenance receipt; the server does
not infer ordinals from label values.

The authoritative parcel asset contains one value per parcel. Volume and
surface views are checked pullbacks through atlas realizations and are recorded
as derived representations. Reducing a spatial field into parcel space is a
different scientific derivation with an explicit reducer and provenance. A
cross-domain representation is invalid without a derivation receipt naming its
source field, realization, converter, parameters, and output digest. See [ADR
0005](decisions/0005-finite-indexed-domains-and-spatial-support-mappings.md).

### Open semantic records

Methods, statistics, estimators, reducers, corrections, diagnostics, and future
analysis types use versioned records:

```scala
opaque type SemanticId = String

final case class SchemaRef(
  id: SemanticId,
  version: String,
  digest: Sha256
)

final case class OpenRecord(
  schema: SchemaRef,
  payload: io.circe.JsonObject
)
```

Identifiers are stable and namespaced, for example:

```text
org.bbuchsbaum.fmrireg/temporal-noise/ar
org.bbuchsbaum.fmrigds/reducer/meta-random-effects
org.neuropublish.measure/t-statistic
```

Known modules provide typed codecs, validation, migrations, summaries, and UI
facts. The core measures the MVP result tree needs (effect, standard error, t,
z, p, accuracy, correlation) ship as a built-in trusted module under
`org.neuropublish.measure/*`, so no external module is required to label a
basic result. Unknown records are retained byte-for-byte and can be displayed through
safe declarative schema annotations. Uploaded schemas contain no executable
code.

Unknown records cannot grant themselves privileged behavior. A record may
declare hints, but the server or browser enables operations such as p-value to
threshold conversion only through a trusted semantic module. Basic scalar
rendering follows the core representation, not an untrusted extension claim.

Interpretation is explicit:

```scala
enum Interpretation[+A]:
  case Understood(value: A)
  case Unsupported(record: OpenRecord)
  case Invalid(record: OpenRecord, errors: cats.data.NonEmptyChain[SemanticError])
```

The enum is closed over application interpretation state, not over the future
universe of scientific methods.

### Admission levels

1. **Transport:** JSON, protocol version, paths, media types, hashes, sizes, and
   reference closure.
2. **Core scientific structure:** axes, dimensions, affine validity, topology
   identity, selections, and representation consistency.
3. **Known semantics:** module-specific constraints, assay relations,
   inferential fields, and migrations.
4. **Publication policy:** sensitivity, subject identifiers, anatomical data,
   local paths, executable content, and project visibility.

Admission returns all useful errors with JSON Pointer paths. A successful
receipt distinguishes fully understood, generically retained, and warning
records.

## Result model

```scala
final case class ResultField(
  id: ResultFieldId,
  label: NonEmptyString,
  estimand: EstimandRef,
  measure: OpenRecord,
  domain: DomainRef,
  selection: AxisSelection,
  representations: cats.data.NonEmptyVector[RepresentationRef],
  summary: ScalarSummary,
  publishedDisplay: Option[DisplayRecommendation]
)
```

The same t-statistic field may have a NIfTI canonical asset, a browser
typed-binary rendition, left and right surface fields, a parcel-indexed asset,
a table, and a preview. Its `domain` identifies where the scientific values
live; a representation on another support must reference an explicit mapping
or derivation rather than silently changing that domain.
Every derived representation records its source assets, conversion parameters,
converter version, and output digest. In the domain model `summary` is
populated by the ingestion worker; the wire DTO for a producer manifest does
not require it.

Published display recommendations are metadata. A saved view stores a user's
current display. Neither is an inferential rule unless a semantic record says
so.

## Upload and commit protocol

### 1. Build and validate locally

The producer adapter creates a staging bundle. `npub pack`:

- validates core and known extension schemas;
- applies privacy checks;
- hashes assets;
- resolves catalog references to digests;
- writes the normalized manifest;
- optionally precomputes renditions and summaries as untrusted hints.

### 2. Create an upload session

```http
POST /api/v1/workspaces/{workspace}/projects/{project}/upload-sessions
```

The project is the path; the request body supplies the parent revision
(required unless the project has no revisions), manifest digest and size, and
the asset digest/size/media-type inventory. The response supplies the session
ID and signed upload instructions for missing objects, and
`GET /api/v1/upload-sessions/{id}` re-issues them for whatever is still
missing. The API declares maximum object size, object count, and total session
size from its first version.

Cross-workspace deduplication must not become an asset-existence oracle. The
server may reuse bytes internally while returning a response that does not
reveal whether another tenant owns the same private digest. Public catalog
assets are a separate namespace.

### 3. Upload assets directly

(Two transports behind one CLI flow. With an S3-compatible store configured,
the session response carries presigned PUTs whose signature covers the size,
media type and `x-amz-checksum-sha256`, and a presigned manifest PUT; the
control plane verifies size and checksum at commit. Without one — tests and
`scripts/e2e.sh` — the same URL shape points at the control plane, which
proxies the bytes.)

The CLI streams missing objects to S3-compatible storage with bounded
concurrency (four single PUTs at a time, three attempts each), checksums, and
clear progress; an interrupted push is resumed by re-negotiating a session,
which excludes what the server already holds. The application server does not
proxy the payload. Signed PUTs address a session-scoped staging area, never a
committed content-addressed key: commit verifies size and SHA-256 of every
staged object and copies it server-side, so no client can overwrite bytes a
revision relies on.

### 4. Commit atomically

```http
POST /api/v1/upload-sessions/{id}/commit
```

The server revalidates the manifest, verifies object existence, size, and
provider checksum evidence, checks that the named parent is still the project
head, and commits the revision and relational read model in one transaction.
The response contains the immutable revision URL and default view URL.

Revision history is linear per project in the MVP. A commit whose parent is
no longer the head is rejected with the current head so the publisher can
re-push; two concurrent commits with the same parent yield exactly one new
head. Branching is a post-MVP decision.

Commit enqueues ingestion. Until the worker finishes, the revision is
committed but its browser representations are marked pending; the interface
shows that state honestly rather than a blank canvas.

Unfinished sessions are dropped by `gc` once older than its threshold (their
signed URLs expire after an hour; the session record itself has no other
expiry yet). Objects not referenced by a committed revision are eligible for
garbage collection, never deletion during a failed commit; `gc` runs only when
an operator invokes it and deletes immediately once an object qualifies.

## Identity and sharing

Identity has separate browser, interactive CLI, automation, and sharing
credentials:

- The browser uses an external identity provider and a secure HTTP-only
  application session.
- `npub login` uses the OAuth 2.0 Device Authorization Grant
  ([RFC 8628](https://www.rfc-editor.org/info/rfc8628/)). It prints a short
  URL and code suitable for a headless login node; approval happens in a normal
  browser.
- Batch workflows use revocable, project-scoped publisher credentials with
  narrowly defined permissions.
- Read-only share links use random bearer secrets; only their hashes are stored.
  They target an immutable revision or one immutable saved-view version, can
  expire, and can be revoked.
- Link-shared viewers fetch private assets through short-lived signed GETs
  issued by the control plane; the bucket's CORS policy permits only the
  application origin.

The default user experience is one login followed by one-command pushes. A
link-shared read-only view opens without account creation. Private project URLs
require an authenticated member.

Identity provider choice is a deployment decision behind a small boundary. The
domain model should not depend on a vendor's user object.

## Tenancy

The alpha serves one workspace, but the schema, queries, URLs, and
deduplication keys are workspace-scoped from the first migration so that
adding labs and sites is an enablement rather than a migration. Details and
verification are in
[ADR 0004](decisions/0004-single-workspace-alpha-on-multi-workspace-schema.md).

## Scala 3 repository structure

```text
modules/
├── domain/              # validated project, revision, asset, and result model
├── protocol-core/       # cross JVM/JS wire-independent core types
├── protocol-json/       # cross JVM/JS codecs, byte-profile admission, schema fixtures
├── semantic-registry/   # trusted interpreters, schemas, migrations, facts
├── viewer-state/        # cross JVM/JS workspace, layers, and saved views (no app deps)
├── viewer-laminar/      # Laminar hosts for ScalaFIM volume/surface (no app deps)
├── api-contract/        # cross JVM/JS Tapir endpoints, OpenAPI, Scala.js client
├── ingestion/           # rendition and summary worker over canonical assets
├── frontend/            # Scala.js, Laminar, Airstream, Vite
├── backend/             # Cats Effect, http4s, Tapir assembly
├── persistence/         # PostgreSQL, Doobie, Flyway
├── publisher-cli/       # validation, login, hashing, upload, commit
└── conformance/         # golden bundles and foreign-producer harnesses
```

The stack is Scala 3, Cats Effect 3, FS2, http4s (Ember), Tapir, Circe,
Doobie, Flyway, PostgreSQL, AWS SDK v2 S3 behind an algebra, Laminar,
Airstream, Waypoint, Vite, and Playwright; the rationale and rejected
alternatives are in [ADR 0003](decisions/0003-typelevel-backend-and-laminar-frontend.md).
Exact dependency versions are selected during the scaffold spike and
recorded in the Scala workspace catalog; this planning document does not turn
current versions into a compatibility promise.

Use opaque IDs, checked domain scalars, precise ADTs for truly closed state,
and accumulated validation at trust boundaries. Keep domain transformations,
manifest validation, revision comparison, and workspace reducers pure. Use
small effect algebras at storage, identity, database, and clock boundaries;
assemble the application concretely in `IO`.

Wire DTOs and validated domain types remain distinct. Schema compatibility
must not force invalid internal states, and internal refactoring must not
silently change the wire contract.

## Scala library dogfooding

Neuropublish is a consumer and proving ground for the existing Scala imaging
ecosystem. Dependency direction remains explicit: generic fixes land in the
owning library, and product policy stays in Neuropublish.

| Library | Neuropublish use | Current planning consequence |
| --- | --- | --- |
| ScalaFIM `image-view` and `image-view-canvas` | Orthogonal volume model, reducer, picking, caching, Canvas host | Wrap from Laminar; do not reimplement slice geometry or viewer state. |
| ScalaFIM `surface-view` and `surface-view-three` | Typed surface state, render plans, WebGL, picking, world linking | Wrap from Laminar; application owns DOM lifecycle and cross-view actions. |
| Intaglio | Display windows, thresholding, opacity, blending, renderer-neutral chrome | Extend reusable colormap and threshold semantics upstream. |
| locus4s | Finite domains, fields, regions, partial maps and surjections, relations, alignment, and aggregation | Use as the Scala algebra behind validated protocol values; keep atlas metadata out of it. |
| image4s | Exact sampled image/grid identity and ordinal layout | Use checked grid-domain bridges; never derive persistent identity from a runtime hash. |
| mesh4s and ScalaFIM surface modules | Exact topology and vertex-order identity plus neuroimaging surface policy | Require topology identity for every surface realization. |
| ScalaFIM `atlas` | Atlas identity, parcel metadata, provenance, hierarchy, and volume/surface realization adapters | Add the rich neuroimaging layer after direct locus4s adoption; do not add another generic domain algebra. |
| spatial4s | Coordinate and spatial foundations where its public contract fits | Adopt directly only after an ownership review demonstrates the needed boundary. |
| zarr4s and ScalaFIM archive modules | Candidate browser-ready chunked renditions | The MVP rendition is a typed-binary derivative produced by the ingestion worker; zarr4s is a candidate format for it, validated by the Stage 0 fidelity spike. |
| templateflow4s | Resolve curated standard templates and surfaces | Resolve server-side or in the publisher and pin exact digests. |
| bids4s | BIDS entities and provenance references | Reuse typed parsing; do not upload raw BIDS datasets by implication. |
| scaladock | Typed layout tree and persistence ideas | Current implementation is JVM/JavaFX; cross-compile the core only if later docking evidence justifies it. |
| Eidolon | A separate product that may later consume Neuropublish viewer modules | Not a dependency. Keep `viewer-state` and `viewer-laminar` free of application stores, routing, and server types so Eidolon can depend on them without taking the product. |

Most of these libraries are pre-release or source-only in the live workspace.
Neuropublish must use exact artifacts or full Git revisions as defaults, with
explicit local checkout overrides. An implicit sibling checkout is not a
reproducible dependency. A clean external consumer test is required before a
library release is treated as available to deployment builds.

## Viewer integration and upstream gaps

The live ScalaFIM code already provides the important architecture:

- pure volume and surface reducers;
- RAS+ world-coordinate contracts;
- Canvas and Three.js browser hosts;
- independent display window and transparent-band threshold;
- layer visibility, opacity, and deterministic order;
- surface scene serialization with external SHA-256 references;
- volume and surface picking with world coordinates;
- resource lifecycle and conformance receipts.

The first implementation must not assume capabilities that are not present:

1. NIfTI and full GIFTI/FreeSurfer readers are currently JVM-side. The plan
   keeps them there: the ingestion worker uses the JVM readers to emit a
   typed-binary browser rendition. The Stage 0 spike confirmed this for
   volumes (`modules/rendition`: JSON header + little-endian float32, decoded
   on Scala.js into `NeuroVol`/`ViewerModel` with exact affine and values);
   the surface rendition is proven with the surface pane in Stage 1.
2. `DisplayThreshold` currently supports disabled and one transparent finite
   band. Positive, negative, bounded interval, and optional outer two-sided
   policies need a shared typed extension.
3. `ColorRamp` currently provides two-stop grayscale and heat ramps. Scientific
   multi-stop sequential, diverging, and categorical palettes need a reusable
   upstream implementation with deterministic JVM/JS fixtures.
4. The application still needs a shared `WorkspaceLayer` adapter that maps one
   product layer to the appropriate ScalaFIM volume and surface actions without
   making either renderer authoritative for the other.

These are explicit upstream work items, not hidden product glue.

## Viewer modularity

The viewer is built as two reusable modules plus the application that uses
them:

- `viewer-state` (JVM/JS): pure workspace model — application layers, result
  selection, linked world coordinate, layout preset, `WorkspaceLayout`, and
  the reducers over them. Depends on ScalaFIM/Intaglio core types only.
- `viewer-laminar` (JS): Laminar components that host a ScalaFIM volume
  controller or Three.js surface backend, own their lifecycle, translate
  events to typed actions, and render layer controls. Depends on
  `viewer-state`, ScalaFIM browser hosts, and Laminar; knows nothing about
  projects, revisions, routes, identity, or the HTTP API.
- `frontend`: the Neuropublish application — stores, routing, API client,
  and the pages that compose `viewer-laminar` components.

Eidolon or any other product can depend on the first two without the third.
Anything that is not Neuropublish-specific goes in one of the first two or
upstream in ScalaFIM/Intaglio.

## Frontend state and lifecycle

The frontend uses bounded stores rather than one global mutable object:

```text
SessionStore
ProjectStore
RevisionStore
WorkspaceStore
ShareStore
NotificationStore
AssetCache
```

`WorkspaceStore` owns selected result identity, application layers, linked
world coordinate, layout preset, and inspector state. ScalaFIM reducers own
renderer-specific state. Large arrays and GPU resources live in a
content-addressed resource cache, not in Airstream values.

Laminar volume and surface components:

- create one canvas and ScalaFIM controller/backend on mount;
- translate pointer, wheel, keyboard, and resize events;
- dispatch typed workspace actions from picks;
- retain caches and GPU resources across ordinary redraws;
- use `ResizeObserver` when dividers move;
- close listeners, backends, WebGL resources, and subscriptions exactly once
  on unmount.

Display controls redraw locally. The server is contacted only for metadata,
signed asset URLs, saved views, sharing, notes, and new revisions.

## Workspace and docking decision

The MVP uses semantic DOM, CSS Grid, and accessible resizable dividers for the
Volume, Surface, Hybrid, and Compare presets. The saved-view wire record is a
Neuropublish open record, `org.neuropublish.view/workspace-layout@1`, not a
third-party library's serialized config; the domain type is `WorkspaceLayout`.

Full docking is reassessed after the volume and hybrid workspaces have real
users. If it becomes necessary, the preferred sequence is:

1. cross-compile the existing pure `scaladock-core` model and add a DOM/Laminar
   interpreter;
2. if delivery pressure requires a browser engine, evaluate Dockview behind a
   narrow Scala.js facade and canonical layout adapter;
3. consider GoldenLayout only after a fresh maintenance, accessibility, popout,
   and WebGL lifecycle spike.

The detailed rationale is in
[ADR 0002](decisions/0002-preset-workspaces-before-arbitrary-docking.md).

## Persistence

Initial relational tables:

```text
users
identities                 # (issuer, subject) per external login
workspaces                 # first-class from migration 1; every row below is workspace-scoped
workspace_members          # owner / admin / member / viewer
projects
revisions
analyses
result_fields
stored_objects             # internal physical bytes by digest; never user-queryable
workspace_assets           # which workspace may reference which digest
revision_assets
derived_representations    # ingestion-worker output, outside the digest
saved_views
saved_view_versions
share_links
catalog_assets             # public template/atlas namespace
publisher_credentials      # non-human principals with project scopes
user_tokens                # hashed `npub login` bearers
sessions                   # hashed browser session cookies
upload_sessions            # negotiated uploads until commit (or gc)
ingestion_jobs             # the worker's leased queue, enqueued in the commit transaction
audit_events
```

PostgreSQL is the searchable read model and authorization store. The manifest
bytes are stored as an immutable object and are the source of truth for
scientific content; `revision_assets` and the derived-representation records
are the source of truth for what the worker produced. Every other projection
(`analyses`, `result_fields`, search columns) must be rebuildable by a
`reindex` command from those two sources. JSONB may hold analysis-specific
payloads for indexing, but it is not the only surviving representation and
does not replace the versioned schemas.

## Privacy and security

The first deployment policy is conservative:

- no raw BOLD or anatomical assets by default;
- no direct subject identifiers in link-shared or public metadata;
- explicit sensitivity classification;
- visible warning for subject-level results;
- project policy checked again at commit and share creation;
- signed URLs with short lifetimes for private assets;
- share and publisher secrets stored only as hashes where possible;
- no executable extension schemas or uploaded UI code;
- audit events for publish, share, revoke, and delete;
- deletion workflows for scientific revisions and assets that are operator-
  driven and auditable (today `gc` deletes unreferenced objects immediately;
  delayed, recoverable deletion is still to be designed).

Object storage remains behind an S3-compatible algebra. Public cloud and
institutional deployments can select different regions or providers without
changing asset identity or project semantics.

## Verification classes

Different tests establish different claims:

- schema and codec golden fixtures establish wire compatibility;
- ScalaCheck laws establish reducers, reference closure, and view round trips;
- R and Julia fixtures establish producer neutrality;
- ScalaFIM JVM/JS conformance establishes rendering semantics;
- browser asset fixtures establish affine, topology, and value fidelity;
- Testcontainers establishes PostgreSQL and object-store transactions;
- Playwright establishes mounted browser interaction and screenshot behavior;
- privacy/admission fixtures establish refusal policy;
- deployment smoke tests establish hosted routing and signed upload/download;
- a successful local render does not establish hosted, scientific, or privacy
  correctness by itself.

## Planning evidence from live repositories

This plan was checked against the current local source rather than only the
product proposal:

- the Neuropublish target was empty and was not a Git repository when the
  plan was written on 2026-08-22;
- ScalaFIM exposes cross-platform image/surface models and browser hosts;
- browser NIfTI/GIFTI ingestion is not yet equivalent to JVM ingestion;
- the shared threshold and color-ramp types are narrower than the proposed UI;
- `scaladock` is currently a typed JavaFX framework with a pure JVM core, not a
  Scala.js docking dependency;
- `fmrigds::register_assay()` currently closes roles with `match.arg()`;
- `fmrigds` provenance is an R list graph/log and its HDF5 alignment families
  use R serialization;
- `rMVPA::save_results()` already writes manifests and runtime/Git context;
- `neuromosaic` already has an `fmrigds` render-manifest adapter;
- no exported `fmrireg::analysis_receipt()` was found in the current source.

These findings determine the prerequisite work in the implementation plan.
