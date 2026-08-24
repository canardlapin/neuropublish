# Stage 5b native-domain dependency spike

Date: 2026-08-24

Decision: **no-go for a direct ScalaFIM pin bump; go for the neutral parcel protocol track.**

The native atlas line now has the scientific abstractions ADR 0005 requires. Neuropublish does not
yet compile against that line, however, and the same baseline drops a lifecycle capability that the
working viewer currently relies on. Parcel-realization adapters therefore remain behind a dedicated
consumer migration. The language-neutral domain envelope and conformance fixtures do not wait for
that migration.

## Baselines

The current Neuropublish build pins ScalaFIM
`2a64eba2e21ce6510d6bf9cf4eaeb3a71e6bba6e`. The spike used an archive of ScalaFIM
`a3c232055b2514865ec286e35d99ff113c611e1c` (`refactor(image): complete native Ravel migration`),
not any developer checkout. That ScalaFIM revision pins:

- locus4s `58c9739be51345ad9adc4bc9c9e7335023254ec9`;
- image4s `6b9016b7aa622df8dec3d887080ee512fa7439c6`;
- Ravel `9c5669399ab8e2a11402e71973dd5f1e2f2c13f4`;
- reframe4s `357426b4fd1e35ddead0068375016f55b082c9e2`.

The live `~/code/scala/scalafim`, `image4s`, and `locus4s` checkouts were inspected read-only and
left untouched because they contain other work and have already advanced beyond the tested
baseline.

## What passed at the dependency boundary

Source inspection and compilation of the upstream modules established that the native line owns
the concepts in the intended layers:

- locus4s supplies the exact finite domains and `PartialSurjection` used for hard assignments;
- image4s supplies exact `GridDomain` identity and canonical Ravel-backed image ownership;
- ScalaFIM's atlas module projects finite-indexed, volume-grid, and surface-vertex domain
  identities without runtime `hashCode` or JSON canonicalization;
- hard assignments serialize signed int32 little-endian target ordinals, use `-1` for background,
  and carry coverage plus label-to-parcel-key provenance;
- `VolumeParcellation` checks both voxel-owner and parcel-owner alignment before construction.

This closes the old architectural prerequisite. It does not by itself admit those records into the
Neuropublish protocol or prove the application adapters.

## Compatibility compile

The exact command was:

```bash
JAVA_HOME=/Users/bbuchsbaum/Library/Java/JavaVirtualMachines/openjdk-22/Contents/Home \
  sbt -Dsbt.global.base=/tmp/neuropublish-sbt-global-native \
  -Dneuropublish.scalafim.build=/tmp/neuropublish-scalafim-spike.K59iRD \
  root/compile
```

The native image, locus, atlas, image-view, and surface-view modules compiled. Neuropublish then
failed at two explicit consumer boundaries:

1. `VolumeRendition.scala` still accepts and returns legacy `NeuroVol[Double]` and constructs a
   legacy `NeuroSpace`; the native line exposes `NeuroVolume`, image4s `GridDomain`, and typed
   sample spaces instead.
2. `VolumeHost.scala` calls `CanvasViewerController.dispose()`. The tested native baseline exposes
   only `close()`, which marks the controller inactive but does not provide the resource-release
   contract already proven by Neuropublish's browser lifecycle gate.

These are genuine migration failures on both JVM and Scala.js, not dependency-resolution noise.

## Required migration gate

Do not patch around either failure with compatibility aliases or downstream resource handling. A
dedicated Neuropublish consumer migration should:

1. replace the rendition boundary with `NeuroVolume` plus exact image4s grid/sample-space identity;
2. prove NIfTI-to-rendition parity and JVM/Scala.js value, affine, coordinate, and layout parity;
3. restore or consume an owning-library controller disposal contract and rerun the mount/unmount,
   listener, scheduled-frame, `ImageBitmap`, and WebGL lifecycle checks;
4. compile and test the volume, surface, hybrid, ingestion, and browser paths against one immutable
   ScalaFIM revision;
5. only then implement the Scala atlas-realization adapter and the ADR 0005 admission fixtures.

Until that gate passes, Stage 5b may advance only through neutral schemas, foreign-producer
fixtures, admission laws, and UI design. The existing volume/surface product remains on its current
admitted ScalaFIM pin.
