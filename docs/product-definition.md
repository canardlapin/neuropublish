# Neuropublish product definition

Date: 2026-08-23

## What the product is

Neuropublish publishes derived neuroimaging results from the environment where
they were computed into a persistent web project. It gives collaborators a
polished scientific workspace without requiring cluster access, local
neuroimaging software, or knowledge of the producer's programming language.

The HPC system remains the compute engine. Neuropublish owns publication,
organization, interactive inspection, provenance, revision history, and
sharing. Static Quarto reports remain useful archival and narrative outputs;
Neuropublish adds an interactive, persistent review layer.

The product must feel closer to a small scientific application than to a file
browser. A user selects an estimand and measure such as “speech coefficient — t
statistic,” not `speech_t.nii.gz`.

## Product boundary

Neuropublish will:

- accept results produced by any language that can write the neutral bundle or
  call the HTTP API;
- preserve immutable scientific revisions and exact asset hashes;
- organize results by scientific identity rather than file location;
- render scalar volume and surface fields through ScalaFIM;
- expose analysis semantics, warnings, and provenance beside the result;
- save and share exact presentation state without changing the science;
- support private, authenticated, link-shared, and eventually public projects.

Neuropublish will not, in its initial product:

- replace FSL, SPM, AFNI, `fmrireg`, `rMVPA`, or workflow code;
- run arbitrary uploaded analysis code;
- host raw BOLD or anatomical data by default;
- infer missing scientific meaning from filenames;
- silently project volume data to a surface;
- treat a changed threshold or camera as a new scientific revision;
- become a generic cloud drive with a viewer attached.

## Users and jobs

### Publisher

A researcher has derived maps, tables, metadata, and provenance on an HPC
system. After a one-time login, the desired interaction is:

```bash
npub validate speech-model.npub
npub push speech-model.npub \
  --project sherlock \
  --message "Group model with articulatory features"
```

The command uploads only missing content, commits one immutable revision
whose parent is the project's current head, and prints the project and
revision URLs. If someone else published first, the push is rejected with the
new head and can simply be re-run. An R package may wrap these calls, but the
bundle and CLI must not require R.

### Scientific reviewer

A reviewer opens a result, selects an estimand and measure, changes display
settings, checks the model and provenance, inspects values and atlas labels,
and compares the result with an earlier revision. The interface never implies
that a display threshold is an inferential decision unless the manifest says
so.

### Nontechnical collaborator

A collaborator opens a curated, read-only link without installing software.
The link may target an exact result, coordinate, threshold, camera, and layout.
The collaborator can explore locally without modifying the saved view or the
scientific revision.

## Product axioms

1. **The project is the durable human-facing unit.** A project contains an
   ordered history of revisions, collaborators, saved views, and sharing
   policy.
2. **A revision is immutable.** Correcting or extending an analysis creates a
   new revision. A committed revision never points at different bytes later.
3. **Scientific content and presentation state are separate.** Scientific
   fields, model semantics, provenance, and warnings belong to the revision.
   Colormaps, thresholds, pane sizes, coordinates, and cameras belong to a
   saved view. Browser renditions and scalar summaries are derived by the
   server from canonical assets and belong to neither the snapshot digest nor
   the view.
4. **The protocol is language-neutral.** Scala is the reference server and
   client implementation, not a requirement imposed on producers. A Julia
   package unknown to Neuropublish must be able to publish a valid result using
   the documented ingredients.
5. **A result field is not a file.** An estimand, measure, axis selection, and
   scientific domain identify the field. A domain may be a volume grid,
   cortical topology, parcel index, or another exact finite scientific set.
   Volume, surface, parcel, table, and preview assets are representations or
   checked views of that field.
6. **The spatial core is small and typed; scientific semantics are open.** The
   core understands axes, domains, scalar fields, tables, representations,
   assets, provenance graphs, and views. Versioned semantic records describe
   methods, estimators, statistics, diagnostics, and future analysis types.
7. **Unknown semantics are preserved without being guessed.** An older server
   may show an unknown method generically and still render a known scalar
   representation. It must disable transformations that require scientific
   interpretation it does not possess.
8. **Provenance is evidence, not decoration.** The interface displays a fact as
   shared across inputs only when the receipts establish that it is shared.
9. **Binary data bypasses the application server.** The server authorizes and
   commits metadata; producers upload large assets directly to object storage.
   A separate ingestion worker, not the control plane, derives browser
   representations from committed assets.
10. **Reusable visualization behavior belongs upstream.** Missing threshold,
    colormap, loading, picking, or lifecycle capabilities should be implemented
    and tested in ScalaFIM, Intaglio, or the appropriate Scala library rather
    than copied into product code.
11. **The common path is short.** Login happens once, an ordinary push needs
    one command, and a link-shared view opens without an account.
12. **The MVP uses a designed Volume workspace.** Surface and Hybrid reuse the
    same preset model after the first slice. Arbitrary docking is a later
    capability, not a prerequisite for testing the scientific product.

## Domain language

```text
Workspace
└── Project
    ├── membership and sharing policy
    ├── saved views
    └── Revision record
        ├── parent revision
        ├── immutable scientific snapshot digest
        ├── publication author, time, and message
        └── Scientific snapshot
            ├── axes and finite/spatial domains
            ├── exact maps, relations, and atlas realizations
            ├── analyses and estimands
            ├── result fields
            ├── representations and assets
            ├── provenance graph
            ├── semantic extension records
            └── published display recommendations
```

### Workspace

An identity and administration boundary for a person or lab. It owns projects,
memberships, publisher credentials, quotas, and policy.

### Project

A durable scientific collection such as “Sherlock — Naturalistic Movie
Encoding.” Its metadata and collaborators may evolve. Its revision records do
not.

### Revision record and scientific snapshot

The server creates an immutable revision record when a publication commits. It
contains the project, parent, author, time, message, and content digest. The
digest identifies the portable scientific snapshot. Separating these objects
allows the same snapshot to be published into another project without changing
its scientific identity.

### Analysis

A typed or extension-described scientific unit: a group model, searchlight,
regional RSA, connectivity analysis, or another producer-defined operation.
An analysis may expose estimands, diagnostics, and specialized panels.

### Estimand

The scientific quantity or question, for example `speech coefficient` or
`faces > houses`. It carries a stable identifier and human label. It is not a
filename and is not identical to a statistic.

### Result field

A result field combines:

- an estimand;
- a measure such as effect, standard error, t, z, accuracy, or an open semantic
  record;
- an axis selection such as group, subject, session, or contrast;
- a domain such as a volume grid, cortical topology, or ordered parcel index;
- one or more representations;
- descriptive summaries and a recommended display.

### Domain, atlas realization, and ROI

A domain is the exact ordered set over which field values live. Domain identity
includes element ordering and a structural fingerprint; equal size or similar
labels are insufficient. Human labels and colors are metadata keyed to stable
domain elements.

A parcel result remains a field over its parcel domain. An atlas realization
maps one exact spatial support domain, such as a volume grid or bilateral
surface topology, to those parcels. The same parcel field can therefore be
inspected as a searchable table and displayed on every supplied realization
without being reclassified as a voxelwise or vertexwise scientific result.

One ROI is a region of a support domain. A disjoint labelled atlas is a hard
partial assignment to a parcel domain. An overlapping ROI collection is a
many-to-many relation, and a probabilistic atlas is a weighted relation. The
product preserves these distinctions and never chooses an overlap winner or
hard label implicitly. The full contract and Scala library ownership boundary
are in [ADR 0005](decisions/0005-finite-indexed-domains-and-spatial-support-mappings.md).

### Representation

A concrete way to inspect or download a field. Initial kinds are volume,
surface, parcel-indexed, table, and preview. A spatial representation whose
support differs from the field domain names the exact mapping or derivation
that produced it. A field can have volume and left/right surface views without
pretending that those files are independent results.

### Saved view

A named presentation document owned by its creator. It pins the exact
revision and contains the selected fields, layer order, display windows,
thresholds, opacity, cursor, surface camera, workspace preset, pane sizes, and
open inspector panels. Each save creates a new immutable view version; share
links target one version, so a link never changes meaning after it is sent.
A saved view cannot change or replace scientific assets.

## Information architecture

### Project overview

The overview answers:

1. What scientific question does this project address?
2. Which revision is current?
3. Which analyses and result families are available?
4. What changed recently?
5. What warnings or provenance facts affect interpretation?

Storage counts and filenames are secondary. Analysis type, sample size,
spatial domain, estimand count, warnings, and last changed revision are primary.

### Scientific workspace

The desktop workspace has four stable regions:

```text
┌─────────────────────────────────────────────────────────────────────┐
│ project / revision / analysis / estimand                Share  ••• │
├───────────────┬──────────────────────────────┬──────────────────────┤
│ result tree   │ volume / surface workspace   │ inspector            │
│               │                              │ layers               │
│ estimand      │ scientific canvas            │ analysis             │
│   measure     │                              │ provenance           │
│               │                              │ notes                │
├───────────────┴──────────────────────────────┴──────────────────────┤
│ world coordinate · value · atlas label (atlas after first slice)   │
├─────────────────────────────────────────────────────────────────────┤
│ design · contrast · diagnostics · clusters · provenance graph      │
└─────────────────────────────────────────────────────────────────────┘
```

The result tree is ordered as analysis → estimand → measure. Representation is
selected within the workspace or layer inspector.

### Workspace presets

- **Volume:** orthogonal slices.
- **Surface:** bilateral cortical surfaces.
- **Hybrid:** volume and surface panes with linked result and coordinates.
- **Compare:** two linked result or revision views; this may land immediately
  after the first MVP.

All presets allow resizable dividers. Arbitrary tab docking and popout windows
are deferred until observed use shows that they improve scientific review.

### Layer inspector

The layer inspector keeps threshold and color mapping separate:

```text
Visible values
  mode                 two-sided
  minimum magnitude    3.10
  maximum magnitude    none

Colour scale
  minimum             -8.00
  centre               0.00
  maximum              8.00
```

Every layer distinguishes the producer's recommendation from the viewer's
current state. The user can reset one control, one layer, or the complete view.

Two of these values are narrower than they look, and the interface says so
rather than implying more. `maximum magnitude` belongs to the two-sided mode
with a positive minimum, the one shape the renderer can express, and is
unavailable elsewhere. `centre` is derived and read-only while the colour scale
is a single linear ramp: it reports the window midpoint, which is what the
canvas actually draws. Both become settable when multi-stop diverging palettes
land upstream; until then the protocol rejects a published centre that is not
the midpoint instead of accepting one and ignoring it.

### Analysis and provenance inspectors

The analysis panel presents a designed scientific synopsis. It does not dump
JSON. The provenance panel provides both a readable pipeline and the exact
operation graph. Every displayed fact should be traceable to a manifest field
or receipt node.

If 22 first-level receipts use AR(2) and four use AR(1), the interface says the
inputs differ. It does not display `AR order: 2` as a shared property.

## Publication experience

Neuropublish exposes three equivalent publication boundaries:

1. **Bundle:** an offline, portable directory described by public schemas.
2. **HTTP API:** a versioned control-plane API with OpenAPI documentation.
3. **Reference CLI:** `npub`, which handles login, hashing, upload resumption,
   validation, and commit.

R, Julia, Python, and Scala libraries may build bundles or call the API. None
is required to embed the Scala implementation. The CLI is the shortest path
for package authors because they can write a bundle and delegate transport.

For headless HPC login, `npub login` should print a short verification URL and
code. The user approves it in an ordinary browser. Batch jobs use an explicit,
project-scoped publisher credential rather than a personal browser session.

## MVP scope

Included:

- owner login and a one-time CLI login;
- one workspace and project;
- immutable revisions;
- neutral bundle validation and direct asset upload;
- one GDS-derived group result;
- volume scalar fields with one underlay and two overlays;
- named estimands and effect, standard error, t, and z measures;
- layer visibility, order, opacity, display window, threshold, and colormap;
- coordinate and value readout (atlas labels follow the first slice);
- analysis and provenance summaries;
- saved view and read-only link share;
- revision history;
- a Julia or other non-Scala producer conformance fixture.

Deferred:

- trusted finite-indexed domains, atlas realizations, and parcel-space
  visualization (the open domain-descriptor hook ships in the MVP protocol);
- arbitrary docking and popout windows;
- comments and concurrent collaboration;
- full institutional RBAC and billing;
- runtime code plugins;
- raw four-dimensional BOLD streaming;
- server-side resampling and undocumented projection;
- complete TemplateFlow mirroring;
- browser map arithmetic;
- voxelwise revision differencing;
- mobile scientific editing.

## Definition of the first vertical slice

The first slice is complete when a producer can publish one immutable revision
containing one anatomical underlay and two statistical overlays, and a
collaborator can:

1. open the returned URL;
2. understand the analysis and estimand;
3. select the two measures;
4. change layer order, visibility, opacity, colormap, display window, and
   threshold;
5. click a world coordinate and read values;
6. inspect sample size, model facts, warnings, software identity, and source
   revision;
7. save the current view;
8. open that saved view through a revocable read-only link;
9. return to the original published recommendation;
10. verify that the view changes did not alter the revision digest.

This slice proves the protocol, upload path, revision model, ScalaFIM browser
integration, provenance read model, identity boundary, and sharing model. It
does not require every future analysis type or workspace arrangement, atlas
lookup, or any R package change: the hand-written reference bundle carries
the provenance cases the slice must display.

The reference result for the slice is named in the implementation plan.
