# ADR 0005: Model parcel and ROI results as finite indexed domains with explicit support mappings

Status: accepted (2026-08-22)

Date: 2026-08-22

Amended: 2026-08-22 (delivery scope, mapping coverage, and binary identity
profiles)

## Context

Neuropublish must publish more than voxelwise and vertexwise fields. A producer
may have one value per parcel, ROI, network, tract, electrode, or another finite
scientific index. For example, a Julia library unknown to Neuropublish should be
able to publish 400 scalar values indexed by an exact Schaefer-Yeo parcellation,
attach labels and metrics, and let the browser display those values in parcel
space without first fabricating a NIfTI result.

The word *domain* is overloaded across the Scala libraries and the product:

- image4s has a complete sampled image space: an ordered spatial grid plus
  non-spatial axes;
- locus4s has the more general mathematical object: a finite, ordered,
  addressable domain with fields, regions, maps, relations, and checked
  alignment;
- ScalaFIM has neuroimaging-specific volume, surface, atlas, and quotient
  semantics;
- Neuropublish needs a portable wire description that none of those Scala
  implementations owns.

An atlas is also not identical to a parcel domain. The parcel domain is the
ordered set of parcel identities over which a field is defined. An atlas adds
scientific identity, labels, hierarchy, provenance, and one or more spatial
realizations that assign voxels or vertices to those parcels. One parcel domain
may have both volume and surface realizations. Conversely, two resources named
"Schaefer 400" are not safely interchangeable merely because both contain 400
rows; release, variant, element identity, ordering, and spatial assignments may
differ.

Finally, the common word *ROI* covers several different mathematical objects:

- one ROI is a region within a support domain;
- a disjoint labelled atlas is a partial assignment from support points to a
  parcel domain;
- an overlapping ROI collection is a many-to-many membership relation;
- a probabilistic atlas is a weighted relation or kernel.

Treating all four as one file kind or one `ROI` class would erase distinctions
that matter for validation, reduction, and visualization.

## Decision

Neuropublish will use one language-neutral result protocol with a general
finite-domain core. Parcel space is a first-class use of that core, not a second
atlas-specific publication protocol.


### 0. Stage 2 freezes the domain hook, not the parcel implementation

Stage 2 freezes the shape shared by every domain entry: a manifest-local ID,
an exact key, and an open descriptor record containing `schema` and `payload`.
It implements the trusted volume-grid descriptor required by the reference
result and proves that unknown descriptors survive round trips without gaining
unsupported behavior.

Stage 2 does not implement trusted finite-indexed interpretation, atlas
realization records, parcel renditions, spatial pullback, or parcel-aware UI.
Those features form the post-MVP parcel track in Stage 5b. This boundary keeps
the initial volume publication slice small while preventing the domain
vocabulary from changing when parcel support arrives.

### 1. A domain is an ordered addressable scientific set

A result field is defined over a domain. Every published domain has:

- a manifest-local ID used by references in that snapshot;
- a non-negative size;
- a versioned, schema-governed descriptor;
- an exact persistent key containing the domain size and structural
  fingerprint;
- an ordering rule that is part of its identity.

The descriptor is an open record, so the protocol does not need a closed enum
of every future scientific domain. Neuropublish ships trusted descriptors for
the common structural cases:

```text
org.neuropublish.domain/finite-indexed
org.neuropublish.domain/volume-grid
org.neuropublish.domain/surface-vertices
```

An unknown descriptor is preserved. It is usable for download and generic
inspection, but it does not gain rendering or alignment behavior until a
trusted module understands its invariants.

For a finite indexed domain, the identity payload contains exactly one stable
element key per row in domain order. Human labels, colors, descriptions, and
other mutable presentation metadata live in separately keyed tables and do not
establish alignment. The structural fingerprint commits to the ordered element
keys and the versioned ordering rules. Merely matching size, labels, or a
human-facing atlas name never establishes domain equality.

Each trusted descriptor defines a versioned binary identity preimage. The
server reconstructs those bytes from the admitted descriptor and hashes them
with SHA-256. This descriptor-specific encoding is not JSON canonicalization:
ADR 0001 still defines snapshot identity as the digest of the original manifest
bytes.

All version-1 domain identity preimages use little-endian integers and
IEEE-754 values, begin with the eight bytes `NPUDOM1\0`, and encode each string
as an unsigned 32-bit byte length followed by UTF-8 bytes. They then append the
descriptor semantic ID and version before their descriptor-specific values:

- `finite-indexed/v1` appends the unsigned 64-bit element count and each stable
  element key, in domain order, as a length-prefixed UTF-8 string. The
  immutable ordered-key asset contains these same bytes, so producers in
  different languages do not acquire different identities from JSON or CSV
  formatting.
- `volume-grid/v1` appends the coordinate-space ID, coordinate convention,
  spatial unit, and ordinal-layout tag; three signed 32-bit positive shape
  values; and the 4 by 4 affine as sixteen row-major float64 values. Non-finite
  affine values are invalid, and negative zero is encoded as positive zero.
- `surface-vertices/v1` appends the surface-space ID, hemisphere, unsigned
  64-bit vertex and face counts, and every triangle as three unsigned 32-bit
  vertex ordinals in published face-array order. Vertex coordinates are not
  part of this domain key: white, pial, and inflated geometries may realize the
  same ordered vertex domain. Their coordinate arrays remain separately hashed
  geometry assets.

The exact preimage profile is part of each descriptor version. Changing an
ordering, numeric encoding, or included structural component requires a new
descriptor version. A producer-supplied fingerprint with no verifiable source
is insufficient.

Published result domains must be persistable. Process-local runtime identity,
language hash codes, memory addresses, and randomly inferred labels are not
portable domain keys.

### 2. A parcel domain and an atlas realization are separate objects

A parcel domain is a finite indexed domain whose stable element keys identify
parcels. Atlas metadata is indexed by those keys and may contain:

- atlas provider, family, model, release, and variant;
- parcel ID, label, full label, hemisphere, network, color, and ontology terms;
- citations, licenses, source artifacts, and derivation history;
- display order, which may differ from domain order but cannot redefine it.

An atlas realization connects one exact spatial support domain to the parcel
domain. The first hard-partition record, implemented in Stage 5b, has the
semantics of a checked partial map:

```text
a: support domain X  -?>  parcel domain P
```

Each support point is either background or belongs to exactly one parcel. The
version-1 assignment asset contains exactly `|X|` signed 32-bit little-endian
integers in the source domain's ordinal order. `-1` means background; every
other value is a zero-based ordinal in `0 .. |P| - 1`. Its media type is
`application/vnd.neuropublish.hard-assignment-i32le-v1`; transport compression
is declared separately.

Target coverage is a separate certification:

- `complete` requires every parcel to have a non-empty fiber. A validated
  assignment with this coverage can be represented by locus4s as a
  `PartialSurjection[X, P]`.
- `allow-empty` admits a valid partial map when masking, cropping, resampling,
  or subject-specific anatomy removes all support for a parcel. The record
  carries the exact `emptyParcels` keys in parcel-domain order. The server
  recomputes that list, requires an exact match, and surfaces a warning. Empty
  parcels do not disappear from `P`, and their field values do not move.

The wire record references both exact domains, the assignment asset, the
coverage policy, and provenance for how the assignment was obtained. A
label-coded NIfTI is an input, not an assignment asset. A producer converts it
to index coding and records the source asset, the exact source-label-to-parcel-
key table, converter identity and version, parameters, and output digest. The
server never infers that table from names or counts.

The server validates assignment length, ordinal bounds, declared background,
coverage, source layout, and both domain keys before admission.

A volume realization and a surface realization may target the same parcel
domain only when their ordered parcel identities agree exactly or an explicit,
validated bijection aligns them. Neuropublish never infers that alignment from
equal counts or similar labels.

Structural domain alignment says that values address the same ordered parcels.
It does not claim that two atlas metadata releases, derivation histories, or
scientific interpretations are identical; those remain separately versioned
records in the immutable snapshot.

### 3. Parcel values remain authoritative in parcel space

A parcel metric is a scalar field whose scientific domain is `P`. Its canonical
asset contains one value per parcel in exact domain order. It is not primarily
a volume whose voxels happen to repeat parcel values.

To display the field on a volume or surface, the viewer pulls the parcel field
back along an atlas realization:

```text
parcel field f: P -> A
hard assignment q: X -?> P
display field on support: f after q
```

This expansion is a derived representation. It records the parcel field, atlas
realization, converter version, parameters, and output digest. It does not
change the field's scientific domain or create a new estimand.

A producer-declared cross-domain representation without that derivation
receipt fails admission. A server-generated rendition is subject to the same
invariant when the ingestion worker records it outside the immutable snapshot.

The reverse operation is scientifically different. Reducing a voxelwise or
vertexwise field into parcel space requires an explicit reducer, missing-value
and weighting policies, input field, atlas realization, and provenance. A
display pullback must never be presented as evidence that such a reduction was
performed.

### 4. Hard partitions, ROI relations, and probabilistic membership do not share one encoding

The initial implementation supports hard parcellations. Other cases use
separate, explicit relation kinds:

- one ROI over `X` is a region or mask on `X`;
- an overlapping ROI collection is a Boolean membership relation between an
  ROI-index domain and `X`;
- a hierarchy or network assignment is a checked map from a parcel domain to a
  coarser indexed domain. It never reorders the source parcel domain; a
  presentation-specific hierarchy order is metadata;
- a probabilistic atlas is a future weighted relation with an explicit
  normalization and missingness policy.

An overlapping or probabilistic atlas cannot be admitted as a hard
parcellation by choosing a winner silently. Rendering overlapping values also
requires an explicit overlap policy; until that policy exists, Neuropublish may
show the ROI table and individual ROI supports but must not synthesize one
scalar support field.

### 5. Parcel-space interaction reuses the scientific workspace

Parcel space is a field/domain capability, not a fifth window manager layout.
This follows the preset-workspace decision in ADR 0002. The same result can
appear in Volume, Surface, or Hybrid presets through its available atlas
realizations. The workspace adds parcel-aware interaction:

- a searchable parcel table with keys, labels, metrics, and warnings;
- linked table selection, slice crosshair, and surface picking;
- parcel label and scalar value on hover or pick;
- optional boundaries and selected-parcel emphasis;
- discrete spatial sampling across parcel boundaries;
- continuous or categorical color mapping according to the field measure.

If no trusted spatial realization is present, the parcel field remains
inspectable as a table and downloadable. The client does not guess an atlas
from the field length.

### 6. Ownership follows the narrowest reusable abstraction

The implementation boundary is:

| Repository/module | Owns | Does not own |
| --- | --- | --- |
| locus4s | Generic finite domains, indices, fields, regions, partial maps and surjections, relations, alignment, and fiberwise aggregation | Atlas names, neuroimaging spaces, labels, files, or provenance |
| image4s | Exact sampled image/grid identity, ordinal layout, and checked bridges from categorical image values | Atlas families or parcel metadata |
| mesh4s and ScalaFIM surface modules | Exact mesh topology and vertex-order identity, plus neuroimaging surface policy | Generic parcel identity |
| `scalafim-atlas` | Atlas identity, region metadata, provenance, volume/surface realizations, hierarchy, and adapters to locus4s/image4s | A second generic `Domain`, `Field`, `Region`, or assignment algebra |
| Neuropublish protocol | Portable domain, mapping, asset, provenance, and representation records plus admission rules | Scala runtime owners or library-specific serialization |

ScalaFIM should eventually expose a rich atlas-parcellation value that pairs
atlas metadata and provenance with authoritative locus4s assignments and exact
image4s or surface support identity. That work belongs in `scalafim-atlas`, but
only after the tracked ScalaFIM migration from its local parcellation facade to
locus4s is complete. It must not add a parallel foundational domain hierarchy.

The current ScalaFIM atlas adapter also constructs a volume domain key using a
runtime `hashCode`. That value is suitable neither for a publication protocol
nor for persistent cross-process identity. Its replacement belongs to the
already tracked VolumeDomain/image4s identity migration, not to an ad hoc
Neuropublish patch.

## Consequences

### Benefits

- A Julia, R, Python, or Scala producer can publish parcel metrics using the
  same core ingredients and conformance rules.
- Parcel results retain their true dimensionality and can acquire volume or
  surface views without duplicating scientific fields.
- Same-sized but incompatible atlases fail closed.
- Volume and surface atlas realizations can share one result field when exact
  parcel alignment is established.
- The protocol has a direct extension path to networks, overlapping ROI sets,
  and probabilistic membership without weakening hard-partition invariants.
- The Scala library boundary reuses existing generic algebra instead of
  introducing another meaning of `Domain` inside the atlas module.

### Costs

- The manifest needs explicit domain and mapping records instead of relying on
  filenames or array length.
- Producers must retain stable parcel keys and order, not only labels and
  values.
- Atlas catalog entries need exact realization digests and versioned ordering
  rules.
- The ingestion worker needs a parcel-field rendition and a checked pullback
  path in addition to volume and surface scalar renditions.
- Overlapping and probabilistic atlases remain unavailable as single composite
  maps until their separate semantics and rendering policies are implemented.

## Rejected alternatives

### Define a separate ROI or parcellation publication protocol

This would duplicate assets, provenance, revisions, fields, and sharing while
making cross-domain analyses harder to express. Parcel space is one domain in
the common result model.

### Publish an atlas name, parcel count, and value vector

Names and counts do not establish element identity or order. This can silently
attach values to the wrong parcels.

### Expand every parcel vector to NIfTI before publication

This hides the authoritative reduced-space result, duplicates values, loses
parcel ordering and metadata, and makes volume geometry appear scientifically
primary. Expanded volumes and surfaces are derived representations.

### Put the general domain abstraction in `scalafim-atlas`

The abstraction already exists at the proper generality in locus4s, with
sampled spatial identity in image4s. A second atlas-local foundation would
create competing alignment and persistence rules.

### Treat every ROI resource as a hard parcellation

Overlapping and probabilistic memberships violate hard-partition laws. Silent
winner selection would change the science.

## Verification

The decision is upheld only if:

- a foreign producer publishes a finite indexed parcel field without Scala
  classes or an atlas-specific endpoint;
- a 400-value field is rejected when attached to a different 400-element
  ordering or structural fingerprint;
- Schaefer variants with different ordered parcel identities do not align
  implicitly;
- assignment length, ordinal bounds, background semantics, and declared target
  coverage are validated with path-specific errors;
- an `allow-empty` realization retains its full target domain, records the
  server-computed empty parcel keys, and surfaces a warning, while the same
  assignment fails under `complete` coverage;
- a parcel field round-trips through table, volume, and surface views without
  changing its field or domain identity;
- volume and surface picks resolve to the same parcel key when their
  realizations claim a shared target domain;
- reduction provenance and display-pullback provenance remain distinguishable;
- a cross-domain pullback representation without a derivation receipt naming
  its source field, realization, converter, parameters, and output digest is
  rejected;
- an overlapping ROI relation is rejected by the hard-parcellation validator;
- no persistent domain key emitted by ScalaFIM depends on JVM/Scala.js runtime
  `hashCode` behavior;
- JVM, Scala.js, R, and Julia conformance fixtures agree on all domain and
  assignment references.

## References

- [ADR 0001: language-neutral publication protocol](0001-language-neutral-publication-protocol.md)
- [ADR 0002: preset workspaces before arbitrary docking](0002-preset-workspaces-before-arbitrary-docking.md)
- [Neuropublish technical architecture](../architecture.md)
- [locus4s](https://github.com/canardlapin/locus4s)
- [image4s](https://github.com/canardlapin/image4s)
- [ScalaFIM](https://github.com/canardlapin/scalafim)
- [Schaefer et al. 2018 parcellation paper](https://pubmed.ncbi.nlm.nih.gov/28981612/)
- [Official CBIG Schaefer2018 resource documentation](https://github.com/ThomasYeoLab/CBIG/tree/master/stable_projects/brain_parcellation/Schaefer2018_LocalGlobal)
