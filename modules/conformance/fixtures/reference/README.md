# Reference bundle (hand-written, Stage 0)

`manifest.json` is the hand-written stand-in for the `sherlock` reference
result until the real dataset is supplied. The effect and standard-error asset
entries remain placeholders, while the underlay, t, and z assets are real
cropped fixtures used by the rendition tests. The manifest exists to exercise
vocabulary, admission, and digest behaviour.
It already carries the cases the interface must handle: a heterogeneous
first-level cohort (AR(1)/AR(2)), an unknown semantic record
(`org.example.lab/smooth`), a scoped warning, an analysis-level `sampleSize`,
and explicit `order` fields — the five gaps the governing-journey design
surfaced, encoded provisionally pending the Stage 2 freeze.

The reference volume domain already uses the Stage 2 envelope: an exact key
plus an open `{schema, payload}` descriptor. Its 24 by 28 by 20 shape and affine
match the cropped NIfTI oracle in `assets/oracle.json`. The structural
fingerprint is recomputed in `FixtureSuite` from the `volume-grid/v1` binary
identity preimage defined by ADR 0005. The descriptor schema digest is the
SHA-256 of `schemas/volume-grid-v1.schema.json`; it is not a placeholder.

`manifest.sha256` is `shasum -a 256 manifest.json`; the conformance suite
asserts the pure-Scala digest (shared by JVM and Scala.js) equals it.
