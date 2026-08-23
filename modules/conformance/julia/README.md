# Julia producer (Stage 2 neutrality proof)

`producer.jl` is the foreign producer ADR 0001 names as a release gate: a
standalone program, written without Neuropublish code or a generated client,
that builds a bundle and publishes it through the documented HTTP API. It
exists to show that the protocol is the documents, not the Scala types.

```
julia producer.jl --out DIR                                   # bundle only, offline
julia producer.jl --out DIR --server URL --project WS/PROJ --token T \
                  [--parent REVISION] [--message TEXT]        # bundle, then publish
```

It prints `digest sha256:<hex>`; with `--server` it also prints `revision`,
`server-digest`, `viewUrl`, one `rendition <asset> <status>` line per volume,
and exits non-zero with `error: ...` if anything fails, including a server
digest that differs from its own. Dependencies: Julia 1.12 stdlib (`SHA`,
`Downloads`) plus `JSON3`.

## Ingredients used

Everything the script needs is documented outside the Scala modules:

| Ingredient | Source |
| --- | --- |
| Manifest vocabulary (core 0.1: title, sensitivity, axes, domains, assets, analyses, result fields, `publishedDisplay`, underlays, provenance) | `fixtures/reference/manifest.json` and the JSON Schema; `docs/architecture.md` "Portable bundle" |
| Byte profile (UTF-8, no BOM, one root object, no duplicate keys, finite numbers) | ADR 0001 |
| Manifest digest = SHA-256 over the exact bytes of `manifest.json` as written; asset identity = `sha256:` of the file bytes | ADR 0001; `docs/architecture.md` "Identity and hashing" |
| Domain envelope: exact `key` plus open `{schema, payload}` descriptor; the `volume-grid/v1` structural fingerprint preimage (`NPUDOM1\0`, six length-prefixed strings, three Int32, sixteen Float64, little-endian) | ADR 0005, `fixtures/reference/schemas/volume-grid-v1.schema.json` |
| NIfTI-1 (348-byte header + 4-byte extension flag, datatype 16, `vox_offset` 352, `qform_code` = `sform_code` = 1) | the NIfTI-1 standard; this is what the server's reader expects |
| Upload protocol: `POST .../workspaces/{ws}/projects/{p}/upload-sessions` with the inventory; one `PUT` per returned `missing` instruction (its `method`, `url`, `headers` are followed as given); `PUT` the manifest to `manifestUrl`; `POST .../commit`; `GET /revisions/{id}` for rendition status | `docs/architecture.md` "Upload and commit protocol"; the OpenAPI document generated from `modules/api-contract` |
| Authentication: a bearer token (a publisher credential, or the deprecated `NP_LEGACY_TOKEN` in tests) | `docs/architecture.md` |

The bearer is only sent to URLs on the control-plane origin; an instruction
pointing at a signed object-store URL is sent with its own `headers` instead.

## What the bundle proves

- one underlay and three overlay volumes (effect, t, z) written by the script;
- a volume-grid domain whose `structuralFingerprint` Scala recomputes from the
  ADR 0005 preimage;
- two provenance receipts differing on one facet (`temporalNoise` AR(1) vs
  AR(2)) and one unknown activity record, `org.example.julia/denoise@0.1`;
- one unknown top-level field (`x-julia-producer`) and one unknown field
  inside a known record (`assets[0].x-julia-voxelStats`), which must survive
  admission, storage, and decode/re-encode in Scala and R.

## Round trip

`roundtrip.R` decodes a manifest with jsonlite (`simplifyVector = FALSE`) and
re-encodes it (`auto_unbox = TRUE, digits = NA, null = "null"`). The bytes
change, so the digest changes; the JSON value does not, and the unknown fields
are still there. That is the distinction ADR 0001 draws between byte
preservation (the stored manifest) and value preservation (re-encoding).

## Tests

`JuliaProducerSuite` (munit-cats-effect) runs the script offline, compares its
digest with `ByteProfile.admit` and `Manifest.parse`, starts the backend
in-process on a free port with a static token, runs the script against it,
checks the revision through the API (digest, renditions `ready`, unknown
fields), rejects a stale re-push and accepts one with `--parent`, then runs
the R round trip. The tests skip with a message when `julia` or `Rscript` is
absent. `fixtures/julia/` is the committed output of `producer.jl --out`, so
`FixtureSuite` admits it on every CI run regardless. `scripts/e2e.sh` runs the
producer as a second push against the live server after `npub push`.
