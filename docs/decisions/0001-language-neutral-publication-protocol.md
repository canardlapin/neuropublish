# ADR 0001: Use a language-neutral publication protocol

Status: accepted (2026-08-22)

Date: 2026-08-22

## Context

Neuropublish will be implemented in Scala 3, but its producers include R
packages, Julia libraries, Python workflows, command-line programs, and future
tools the server has never heard of. Requiring those producers to construct
Scala types or mimic compiler-derived JSON would turn the reference
implementation into a protocol dependency.

The product also needs exact scientific snapshot identity, immutable assets,
unknown-extension preservation, and validation that gives useful paths rather
than a generic decode failure.

## Decision

The publication contract is a documented, portable bundle plus an equivalent
HTTP API.

- JSON Schema 2020-12 is the normative structural schema.
- A prose specification states cross-field and scientific invariants.
- The **manifest digest** is SHA-256 over the exact bytes of `manifest.json`
  as written by the producer; the server stores those bytes immutably. No
  canonicalization is performed. The digest commits transitively to every
  asset (their digests appear in the manifest) but asserts textual identity,
  not semantic equivalence; two manifests differing only in whitespace are
  different snapshots.
- The digest is supplied outside the manifest (upload-session metadata), so
  there is no self-referential field.
- A manifest must conform to a strict byte profile or it is rejected at
  admission: UTF-8 without BOM, exactly one root object, no trailing
  content, no duplicate object keys, only Unicode scalar values (no lone
  surrogates), and only finite numbers. These are the cases where RFC 8259
  parsers disagree or normalize; rejecting them keeps the stored bytes and
  the parsed form from diverging.
- Two preservation guarantees are distinct: the server returns the original
  manifest bytes exactly; a decoded and re-encoded unknown record preserves
  its JSON value, not its whitespace or numeric spelling.
- Binary assets use SHA-256 content identities.
- Open scientific records carry a namespaced semantic ID, semantic version,
  immutable schema digest, and structured JSON payload.
- Known Scala modules provide typed interpretation. Unknown records are
  preserved and shown generically, without enabling unsupported scientific
  operations.
- The reference CLI validates, packs, authenticates, uploads, and commits a
  bundle, but a producer may call the API directly.
- A Julia conformance producer is a release gate for the first protocol.
- R and Scala round trips must preserve unknown fields and original extension
  payloads.

The canonical protocol is not a raw dump of an `fmrigds` object, a Circe enum,
an R `serialize()` stream, a server database row, or a third-party viewer
scene. Adapters translate those objects into the neutral result model.

## Consequences

### Benefits

- A new language can publish without waiting for an SDK.
- Protocol evolution is reviewed independently of Scala refactoring.
- Scientific snapshots can be hashed consistently across languages.
- Unknown methods survive older servers and round trips.
- Client libraries can remain thin and task-oriented.

### Costs

- Scala codecs, JSON Schemas, prose invariants, and fixtures must remain in
  agreement.
- Because the digest is over bytes, two manifests that differ only in
  whitespace or key order have different digests. Semantic comparison is a
  separate, parsed-form operation and never an identity claim.
- Catalog references must be resolved into the file before hashing, so
  `npub pack` (or a foreign producer) owns that step.
- Open records require explicit trust rules; a schema alone cannot authorize a
  scientific transformation.
- Migrations must retain original payloads and explain normalized projections.

## Rejected alternatives

### Scala-derived JSON as the protocol

This would make producers depend on Scala encoding details and would couple
wire compatibility to case-class and enum refactoring.

### One untyped `Map[String, Json]`

This preserves openness but loses stable identity, versioning, validation,
trusted interpretation, and clear failure modes.

### One closed enum of all analyses, statistics, and methods

These taxonomies are open across packages and research groups. A central enum
would force every new producer to wait for a core release or mislabel its
method.

### Canonicalized JSON (RFC 8785) as the hashed form

Considered first. It would let semantically equal manifests share a digest,
but it requires every producer language to implement ES6 shortest-roundtrip
number formatting and Unicode ordering exactly. R has no mature
implementation, affines are floating point, and the benefit is small because
original manifest bytes are retained anyway. Hashing the bytes makes the
cross-language digest proof trivial and moves equality questions to the
parsed form where they belong.

### Make RO-Crate, BIDS Derivatives, NIDM, or PROV the complete core model

These standards should be audited and mapped before protocol freeze. None is
assumed to match the complete interactive result-field and saved-view model
without evidence. Neuropublish should export or reference established
identifiers where they fit rather than create incompatible synonyms.

## Verification

These are the Stage 2 protocol exit criteria in the
[implementation plan](../implementation-plan.md); that document is the
maintained list. The decision is upheld only if:

- a small Julia program publishes the reference result without Scala code;
- Scala, Julia, R, and `shasum -a 256` calculate the same digest for the
  same fixture bytes;
- a Julia producer independently writes a manifest the server admits;
- changing whitespace changes the digest but not the parsed-form semantic
  comparison;
- a manifest violating the byte profile is rejected with a JSON Pointer
  path;
- unknown record fields survive Scala and R round trips;
- a newer unknown statistic remains viewable as a core scalar field but does
  not expose unsupported inferential conversions;
- schema digest substitution and asset substitution are rejected;
- original extension payloads remain downloadable after migration.

## References

- [JSON Schema 2020-12](https://json-schema.org/draft/2020-12)
- [RFC 8785: JSON Canonicalization Scheme](https://www.rfc-editor.org/rfc/rfc8785.html)
  (considered and rejected)
- [RFC 8259: The JSON Data Interchange Format](https://www.rfc-editor.org/info/rfc8259/)
