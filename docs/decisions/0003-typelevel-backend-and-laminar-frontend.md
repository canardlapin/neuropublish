# ADR 0003: Typelevel backend stack and Laminar frontend

Status: accepted (2026-08-22)

Date: 2026-08-22

## Context

Neuropublish needs a Scala 3 server, a Scala.js browser application that
hosts two imperative renderers (ScalaFIM Canvas volume host and Three.js
surface backend), a cross-platform API contract whose OpenAPI document is a
normative protocol artifact, and a test story covering PostgreSQL and
S3-compatible storage. The surrounding Scala imaging ecosystem (ScalaFIM,
Intaglio, Eidolon, image4s and siblings) is Typelevel-shaped: Cats Effect,
FS2, Circe.

The frontend's hardest requirement is lifecycle, not rendering: a mounted
pane must create one controller and one WebGL context, receive resize events
exactly once per layout change, retain caches across ordinary redraws, and
dispose everything exactly once on unmount or route change.

## Decision

### Backend

- **Cats Effect 3** as the effect system; small tagless algebras at storage,
  identity, database, and clock boundaries; concrete `IO` assembly.
- **http4s (Ember)** server.
- **Tapir** for endpoint definitions in the cross-platform `api-contract`
  module. One definition yields the http4s routes, the OpenAPI 3.1 document,
  and a Scala.js sttp (Fetch) client. The Tapir endpoint values are the
  implementation source; the checked-in OpenAPI document is the external
  compatibility contract, regenerated in CI with a failing diff on any
  unreviewed change. OpenAPI describes the control-plane HTTP API only; the
  scientific manifest is described by JSON Schema (ADR 0001), which OpenAPI
  references or transports but does not redefine.
- **Circe** codecs with hand-written or explicitly configured derivation; no
  compiler-shaped enum encodings on the wire.
- **Doobie + Flyway** for PostgreSQL. Doobie over Skunk for JDBC maturity,
  migration tooling, and Testcontainers integration.
- **AWS SDK v2 S3 async client** wrapped behind the object-store algebra;
  MinIO in integration tests.
- **munit, munit-cats-effect, ScalaCheck, Discipline, testcontainers-scala**.

### Frontend

- **Laminar + Airstream** for the Scala.js application, **Waypoint** for
  routing, **Vite** via the scalajs-vite plugin, **Playwright** for browser
  tests.
- Each imperative viewer pane uses one state-carrying lifecycle hook
  (`onMountUnmountCallbackWithState` or an equivalent single mount handle)
  whose cleanup is idempotent, rather than loosely paired mount and unmount
  callbacks. That handle owns the ScalaFIM controller, canvas, listeners,
  `ResizeObserver`, and GPU resources. Airstream signals carry only small
  typed state; arrays and GPU resources live in the content-addressed cache.

### Cross-platform modules

`protocol-core`, `protocol-json`, `viewer-state`, and `api-contract` cross
compile to JVM and Scala.js with deliberately narrow dependencies:

- `protocol-core`: domain primitives only (Cats at most);
- `protocol-json`: Circe and the JSON Schema fixtures;
- `viewer-state`: ScalaFIM/Intaglio plus minimal functional dependencies;
- `api-contract`: Tapir core plus the protocol types.

## Consequences

### Benefits

- One ecosystem across server, shared modules, and upstream imaging
  libraries; no effect-system interop layer.
- The API contract is defined once and produces the normative OpenAPI
  document and a typed browser client.
- Laminar's explicit ownership model matches the renderer lifecycle
  requirement without a reconciliation layer between the app and the canvas.
- Existing Eidolon Laminar/Vite patterns and design tokens transfer.

### Costs

- Laminar has a smaller component ecosystem than React; form controls,
  dividers, and menus are written in-house or wrapped from headless DOM
  libraries.
- Tapir's OpenAPI output must be reviewed for exactly the JSON Schema
  features the protocol uses; the generated document is checked into the
  protocol repository and diffed in CI.
- Doobie requires manual SQL; that is accepted in exchange for control over
  the read-model projections and `reindex`.

## Rejected alternatives

### ZIO stack (zio-http, zio-json, Quill)

Capable, but the upstream imaging libraries and existing workbench are
Cats Effect based. Interop is possible and not worth its ongoing cost.

### Smithy4s

Strong for IDL-first services. Here JSON Schema 2020-12 and OpenAPI are the
normative artifacts and must remain readable to R, Julia, and Python
producers; Tapir maps to that contract more directly.

### React via Slinky or scalajs-react

A large component ecosystem. React does not inherently cause WebGL lifecycle
bugs, but it adds a second framework and a wrapper boundary between the
application and the renderers with little benefit for a product whose
components are almost all custom scientific controls.

### Tyrian or Calico

Both are capable. They are rejected because the project has no local
integration evidence for hosting imperative WebGL backends in either, while
Eidolon already provides working Laminar patterns for exactly that.

## Verification

- The Stage 0 lifecycle spike passes with Laminar owning both renderers.
- The OpenAPI document generated by Tapir validates the hand-written
  reference bundle's HTTP fixtures and is diffed in CI.
- A Scala.js client generated from `api-contract` drives the frontend's
  upload-session and saved-view calls without a second hand-written client.

## References

- [Laminar](https://laminar.dev/)
- [Tapir](https://tapir.softwaremill.com/)
- [http4s](https://http4s.org/)
- [Doobie](https://typelevel.org/doobie/)
