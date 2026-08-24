# Exact parcel-domain fixture

This packed bundle pins the neutral parcel contract from ADR 0005 without giving the core any
atlas-specific scientific behavior.

- `schaefer-ordered` is a trusted `finite-indexed@1.0` domain. Its identity is the exact ordered
  vector of four stable Schaefer-style keys, not its label, atlas family, or element count.
- `schaefer-volume` is a trusted `hard-assignment@1.0` partial map from the exact eight-voxel
  `mni-toy` grid to that finite target. The i32le bytes are bounds-checked and prove complete target
  coverage during ingestion.
- `parcel-effect-field` is scientifically defined on the finite domain. Its table asset carries the
  four canonical scalar values; its NIfTI is a producer-authored spatial pullback.
- Assignment construction and scalar pullback are separate provenance activities. The latter names
  the source field, mapping, converter, parameters, and output digest.

The conformance suites also reorder the keys and substitute a different Schaefer network variant;
both must fail against the pinned structural fingerprint.
