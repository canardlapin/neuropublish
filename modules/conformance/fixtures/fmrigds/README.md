# fmrigds producer fixture

This directory is a packed Neuropublish bundle emitted by the producer-owned
`as_neuropublish.gds()` adapter in `fmrigds` 0.1.0.9000. The Neuropublish R
client supplied neutral constructors and packing; it did not infer the GDS
object's measures, sample size, reducer semantics, or provenance.

The pinned occurrence is a six-input, two-contrast `meta:re` reduction over
verified file sources. It exercises:

- `sampleSize = 6` from the pre-collapse reduction receipt;
- trusted effect, standard-error, and z-statistic measures;
- producer-namespaced tau-squared and effective-sample-size measures, which
  must remain semantically unknown to Neuropublish;
- ten content-addressed NIfTI result assets;
- twelve private, non-hosted source entities identified only by byte size and
  SHA-256 digest;
- one portable reduction activity, twelve `used` edges, and ten `generated`
  edges; and
- an explicit warning that synthetic spatial sample labels carry no
  anatomical identity.

The occurrence id and activity timestamp are intentionally not normalized.
Fresh runs must satisfy the semantic, graph, asset-integrity, and privacy
contract in `FmrigdsFixtureSuite`; they are not expected to reproduce the
manifest bytes until the producer exposes an injectable occurrence clock.
`manifest.sha256` pins this reviewed occurrence exactly.
