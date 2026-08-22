# Reference bundle (hand-written, Stage 0)

`manifest.json` is the hand-written stand-in for the `sherlock` reference
result until the real dataset is supplied. Asset digests are placeholders;
the manifest exists to exercise vocabulary, admission, and digest behaviour.
It already carries the cases the interface must handle: a heterogeneous
first-level cohort (AR(1)/AR(2)), an unknown semantic record
(`org.example.lab/smooth`), a scoped warning, an analysis-level `sampleSize`,
and explicit `order` fields — the five gaps the governing-journey design
surfaced, encoded provisionally pending the Stage 2 freeze.

`manifest.sha256` is `shasum -a 256 manifest.json`; the conformance suite
asserts the pure-Scala digest (shared by JVM and Scala.js) equals it.
