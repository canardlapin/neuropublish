# Neuropublish

Neuropublish is a scientific results publication and review system for
neuroimaging. Analyses continue to run in R, Julia, Python, Scala, or another
environment on the HPC system. Neuropublish accepts a portable publication
bundle and turns it into an immutable project revision with interactive volume
and surface views, structured provenance, and durable sharing.

The target is the last mile of an analysis:

```text
analysis code on HPC
        |
        v
language-neutral publication bundle
        |
        v
immutable project revision
        |
        v
interactive review and sharing
```

Neuropublish is not intended to run general neuroimaging analyses in the
browser, host raw imaging data by default, or present uploaded files without
scientific meaning.

## Status

Stages 0–4 of the [implementation plan](docs/implementation-plan.md) are done:
the language-neutral protocol (`protocol/`), the Stage 2 publication spine
(upload sessions, immutable revisions, PostgreSQL read model, S3-compatible
object store, ingestion worker), the `npub` publisher CLI, the volume
workspace, identity, saved views, and share links. The first product slice is
proven end to end: a Julia program with no Neuropublish code publishes a bundle
the server admits, and the result is explored in the browser and opened through
a read-only link. See [DEVELOPMENT.md](DEVELOPMENT.md) for building, testing,
and running it locally.

## Planning documents

- [Product definition](docs/product-definition.md)
- [Technical architecture](docs/architecture.md)
- [Implementation plan](docs/implementation-plan.md)
- [ADR 0001: language-neutral publication protocol](docs/decisions/0001-language-neutral-publication-protocol.md)
- [ADR 0002: preset workspaces before arbitrary docking](docs/decisions/0002-preset-workspaces-before-arbitrary-docking.md)
- [ADR 0003: Typelevel backend stack and Laminar frontend](docs/decisions/0003-typelevel-backend-and-laminar-frontend.md)
- [ADR 0004: single-workspace alpha on a multi-workspace schema](docs/decisions/0004-single-workspace-alpha-on-multi-workspace-schema.md)
- [ADR 0005: finite indexed domains and spatial-support mappings](docs/decisions/0005-finite-indexed-domains-and-spatial-support-mappings.md)
- [UI design concept and adoption table](docs/design/README.md)

The first product proof is deliberately narrow: publish one GDS-derived result
with one anatomical underlay and two statistical overlays, inspect it in the
ScalaFIM volume viewer, read its provenance, save the exact view, and open that
view through a read-only link. The implementation plan front-loads a thin
local spine so that a published map is visible in the browser before identity,
sharing, or package adapters exist.
