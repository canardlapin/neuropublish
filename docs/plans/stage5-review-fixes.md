# Stage 5 review fixes

Four fresh-context reviews of `c0ba616..b1b153f` (surface renditions and
admission, surface/hybrid workspace, R client, and a cross-stage pass) returned
two blocking defects and a set of serious ones. This plan records what is being
fixed, who owns it, and what counts as evidence.

Baseline: `main` at `b1b153f`, pushed and green (`npCheck` with Docker and
tools required, local e2e 15/15, full-mode e2e 15/15, lifecycle 4/4). None of
the work below has landed on `main`.

## What the reviews confirmed as sound

Stated here so the fixes do not churn what is already proven: the
`volume-grid/v1` and `surface-vertices/v1` fingerprint preimages agree
byte-for-byte across Scala, Julia, and R against pinned reference digests;
`TriangleMesh.fromArrays` rejects negative, out-of-range, and degenerate faces
and every decode routes through it; `topologyIdentity` is pure integer
MurmurHash3 and therefore identical on the JVM and Scala.js; ScalaFIM applies
`surfaceToWorld` in `SurfaceCompiler` and `SurfaceWorldLink.worldPoint`, so
picking and linking are geometrically correct today; and no code path projects
volume data onto a surface.

## Blocking

**B1 — surface and volume spaces are never compared.** The
`surface-vertices/v1` payload's `space` enters the fingerprint preimage and
nothing else. `ManifestChecks.surfaces`, ingestion, and the workspace all skip
it, so fsaverage surfaces published against an MNI volume link by raw world
millimetres and the readout says "linked to vertex N, 0.8 mm" as though it
meant something. Admission must reject the mismatch, and the workspace must
refuse to link defensively when a rendition header reports a different space.

**B2 — `topology` names different things in the SPEC, the schema, and the
code.** `ManifestChecks` compares against `surfaces[].asset`; SPEC §5 and §6
say `surfaces[].id`. The reference fixture sets the two equal, which is why no
test caught it. The check is the behaviour we intend to keep, so the SPEC, the
schema description, and the error text move to `asset`, and a reference fixture
where `id != asset` keeps the two from silently re-converging.

## Serious

**S1 — duplicate surface layer ids blank the pane.** Layers keyed
`field@hemisphere` collide when a field has two representations on one
hemisphere; `SurfaceViewerModel.make` returns `DuplicateLayerId`, `.toOption`
discards it, and the surface pane renders nothing while the inspector still
says "drawn in: left surface". Key by the placed surface and never swallow a
model error — a failed model is a visible pane message.

**S2 — representations counted against surfaces that were never placed.**
`surfaceAssets` keeps only the first declared surface per hemisphere while
`representationsOf` counts a representation on any decoded surface, so a field
on the non-placed surface is dropped without a word and every slider move
dispatches to a layer id that does not exist. Representations are computed
against placed surfaces under a documented placement rule.

**S3 — sparse GIFTI field arrays are ingested as data.** `readField` takes the
first array that is not `POINTSET` or `TRIANGLE`, so a `.func.gii` whose first
array is `NIFTI_INTENT_NODE_INDEX` — the layout Workbench and nibabel emit —
has its vertex *indices* stored as field values. Rank-2 `TIME_SERIES` arrays
fail with a misleading vertex-count message. Skip `NODE_INDEX` explicitly,
reject sparse fields by name, and require rank 1.

**S4 — the R client cannot publish an oblique grid.** `np_write_bundle` wrote
JSON at 15 significant digits while `np_volume_grid_fingerprint` hashed
full-precision doubles, and the server recomputes from the parsed JSON. Every
rotated or oblique affine was refused; every fixture is axis-aligned, so it
never fired.

**S5 — `coordinateSystem: "RAS+"` is a stamped constant.** The encoder writes
it unconditionally while the GIFTI's `DataSpace` and `TransformedSpace` go
unread, so a `NIFTI_XFORM_UNKNOWN` or Talairach mesh is still labelled RAS+.
The label must follow the parsed spaces, and an unknown space with a
non-identity transform is a rejection, not a guess.

**S6 — hemisphere is never checked against the bytes.** The manifest's
`hemisphere` is fed straight to the reader while the GIFTI's
`AnatomicalStructurePrimary` is ignored. Left and right fsaverage meshes share
a vertex count and a topology, so a swapped hemisphere is undetectable
downstream. Where the GIFTI states its structure, admission requires agreement.

**S7 — surface view state is lost on every preset switch.** Remounting builds
from `SurfaceViewerState.initial`, so zoom, orbit, lighting, and selection
reset on Volume→Hybrid→Surface. Snapshot the state in the store and rebuild
from it.

**S8 — a failed transform reports "linked, 0.0 mm".** `Link.Linked` defaults
its distance to `0.0` when `worldPoint` fails, which reads as a perfect match.
Carry the distance from `nearestVertex` and make a failed transform an honest
not-linked state.

**S9 — the icosphere oracle is not independent.** `oracle.json` is written from
the same Julia arrays that the fixtures serialize, so the fidelity suites prove
round-tripping rather than correctness. The genuinely cross-implementation
evidence is the fingerprint comparison. Add hand-derived assertions: vertex
counts and face counts from `V = 10·4^n + 2`, `F = 20·4^n`, and one vertex
position derived from the icosahedron construction rather than read back.

**S10 — the R client parses human CLI output.** `np_validate` turned runtime
failures into one-row problem frames, a pointer containing a space
mis-split, and a lower-case `warning` line was appended to every token error.
Replaced by a `--json` report mode on the CLI that the client parses as one
document.

**S11 — `topologyIdentity` pins stored renditions to ScalaFIM's hash seeds.** A
64-bit non-cryptographic key the decoder hard-fails on. A ScalaFIM bump that
changes the seeds invalidates every stored `surface-mesh@0` with no migration.
Carry a SHA-256 `faceDigest` as the stable key and document `topologyIdentity`
as the reference implementation's.

## Deliberately not changed

`derivation` stays optional. Admission cannot verify that a surface field was
measured rather than projected, so requiring a receipt would only move the lie
one field over. The SPEC and the plan's exit-criterion wording change instead:
an absent `derivation` declares a native surface measurement, and the workspace
states that rather than implying a receipt exists.

## Tracks and remaining work

Three worktrees hold partial, uncompiled work from an interrupted first
attempt. Each track resumes from its own state.

### Track R — renditions and admission (`stage5/rendition-review-fixes`)

Landed in the worktree: rendition header carries `space`, `faceDigest`,
`sourceTransform`, and `anatomicalStructurePrimary`; `rendition-header` and
`surface-vertices-v1` schemas updated; `VolumeGrid` NUL literal replaced;
`VertexFieldRendition` size checks made Long-safe.

Remaining: `ManifestChecks` for B1, B2, and the asset-sharing rules; backend
`Ingestion` for S3, S5, S6, and the transform decision; `SPEC.md` §5 and §6;
conformance invalid fixtures with exact `.expect` lines; a reference fixture
with `id != asset`; S9's hand-derived assertions; S11 documentation.

### Track W — surface and hybrid workspace (`stage5/workspace-review-fixes`)

Landed in the worktree: `SurfacePlacement` with the placement rule, layer-id
construction, and a `SpaceGuard` decision type; `SurfaceHost` distance,
device-pixel-ratio, and disposal-comment fixes; `RendererHost`;
`WorkspaceLayout` and its suite; a placement suite.

Remaining: wire `SurfacePlacement` and `SpaceGuard` into `WorkspaceStore`
(S1, S2, S7, B1's client half, cursor-source reset, selection dedupe);
`WorkspacePage` for the splitter pointer capture and unmount cleanup, the
per-hemisphere absence line, and the provenance line; `ViewUrl` split-fraction
quantisation; e2e scenarios for a right-hemisphere pick driving the volume
cursor and for a threshold change hiding a known value.

### Track C — R client and publisher CLI (`worktree-agent-a2759c949fe3a9132`)

Landed in the worktree: `digits = I(17)`; `cli.R` rewritten onto the `--json`
report; `domain.R`, `manifest.R`, `bundle.R`, `DESCRIPTION`; CLI `Report`,
`Validate`, `JsonOutputSuite`, and the `Main`, `Pack`, `Push` rewiring.

Remaining: no R test has been touched yet — a rotated-affine round trip, the
empty-object payload, the asset-name collision, and the size-overflow cases all
need tests; asset collision detection; `NOT_CRAN`/`NPUB_TESTS` gating; and the
whole track is unverified.

## Verification

Every track: `npFormat`, `npCompile`, its own suites, and a commit before any
long-running check so an interruption cannot lose work again.

Merge order is Track R, then Track W rebased onto it — the workspace consumes
the `space` header field the rendition track adds — then Track C, which touches
only the R package and the publisher CLI and is independent of both.

Gate before pushing `main`: `npCheck` with `NP_TEST_REQUIRE_TOOLS=1` and
`NP_TEST_REQUIRE_DOCKER=1`, local e2e, full-mode e2e with PostgreSQL, MinIO and
the ingestion worker, the lifecycle suite, `R CMD check --as-cran` on a built
tarball, and a clean browser-automation audit with the worktrees removed.
