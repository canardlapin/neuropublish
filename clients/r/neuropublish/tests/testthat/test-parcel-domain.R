parcel_keys <- c(
  "schaefer2018-7networks-lh-visual-1",
  "schaefer2018-7networks-lh-default-1",
  "schaefer2018-7networks-rh-visual-1",
  "schaefer2018-7networks-rh-default-1"
)
parcel_fingerprint <- "sha256:7297cb3eb45df97653c90bfb586eee3857912df6fe0bdc789dd6c7ab849c9394"
assignment_digest <- "sha256:daa961c1c1d1aa3cbc2455ed421c8868e2ef5dbab325f2bc403f6f2db66c398c"

test_that("finite-indexed preimage agrees byte-for-byte with the parcel fixture", {
  bytes <- np_finite_indexed_preimage(parcel_keys)
  expect_length(bytes, 219)
  expect_identical(
    paste0("sha256:", digest::digest(bytes, algo = "sha256", serialize = FALSE)),
    parcel_fingerprint
  )
  expect_identical(np_finite_indexed_fingerprint(parcel_keys), parcel_fingerprint)
  expect_identical(as.integer(bytes[1:8]), c(78L, 80L, 85L, 68L, 79L, 77L, 49L, 0L))

  fixture <- np_fixture(
    "parcel", "assets", "sha256", "72",
    "7297cb3eb45df97653c90bfb586eee3857912df6fe0bdc789dd6c7ab849c9394"
  )
  if (!is.null(fixture)) {
    expect_identical(readBin(fixture, "raw", n = file.info(fixture)$size), bytes)
  }
})

test_that("finite identity changes under order and atlas variant changes", {
  reordered <- parcel_keys[c(2, 1, 3, 4)]
  foreign <- parcel_keys
  foreign[[1]] <- "schaefer2018-17networks-lh-visual-1"
  expect_false(identical(np_finite_indexed_fingerprint(reordered), parcel_fingerprint))
  expect_false(identical(np_finite_indexed_fingerprint(foreign), parcel_fingerprint))
  expect_error(np_finite_indexed_preimage(c("a", "a")), "duplicate key 'a'")
  expect_error(np_finite_indexed_preimage(character()), "non-empty character vector")
})

test_that("hard-assignment bytes agree with the parcel fixture", {
  bytes <- np_hard_assignment_bytes(c(0, 0, 1, 1, 2, 2, 3, 3), 4)
  expect_length(bytes, 32)
  expect_identical(
    paste0("sha256:", digest::digest(bytes, algo = "sha256", serialize = FALSE)),
    assignment_digest
  )
  fixture <- np_fixture(
    "parcel", "assets", "sha256", "da",
    "daa961c1c1d1aa3cbc2455ed421c8868e2ef5dbab325f2bc403f6f2db66c398c"
  )
  if (!is.null(fixture)) {
    expect_identical(readBin(fixture, "raw", n = file.info(fixture)$size), bytes)
  }
  expect_error(np_hard_assignment_bytes(c(0, 4), 4), "target_size - 1")
  expect_error(np_hard_assignment_bytes(c(-2, 0), 4), "target_size - 1")
})

test_that("R builders preserve exact parcel and pullback structure", {
  domain <- np_domain_finite(parcel_keys, "schaefer-ordered", "parcel-keys")
  expect_identical(domain$key$size, 4L)
  expect_identical(domain$key$structuralFingerprint, parcel_fingerprint)
  expect_identical(domain$descriptor$payload$elementKeys, I(parcel_keys))
  expect_identical(domain$key$descriptor, np_finite_indexed_schema)

  mapping <- np_domain_mapping_hard(
    "schaefer-volume", "mni-toy", "schaefer-ordered", "parcel-assignment",
    "construct-schaefer-assignment", target_keys = parcel_keys
  )
  expect_identical(mapping$descriptor$schema, np_hard_assignment_schema)
  expect_identical(mapping$descriptor$payload$coverage, "complete")
  expect_length(mapping$descriptor$payload$emptyParcels, 0)

  rep <- np_volume_rep(
    "parcel-pullback",
    domain = "mni-toy",
    mapping = "schaefer-volume",
    derivation = "pullback-parcel-values"
  )
  expect_identical(rep$domain, "mni-toy")
  expect_identical(rep$mapping, "schaefer-volume")
  expect_identical(np_table_rep("parcel-values")$kind, "table")
  expect_error(np_volume_rep("x", domain = "grid"), "requires `derivation`")
  expect_error(np_volume_rep("x", mapping = "m"), "explicit cross-domain")

  m <- np_manifest("Parcel", "Exact ordered parcel field")
  m <- np_add(m, "domains", domain)
  m <- np_add(m, "domainMappings", mapping)
  expect_length(m$domainMappings, 1)
  expect_match(
    jsonlite::toJSON(m, auto_unbox = TRUE, null = "null"),
    '"domainMappings":\\[\\{',
    fixed = FALSE
  )
})

test_that("coverage builders fail closed before publication", {
  expect_error(
    np_domain_mapping_hard("m", "x", "p", "a", "d", "complete", "parcel-a"),
    "requires no"
  )
  expect_error(
    np_domain_mapping_hard("m", "x", "p", "a", "d", "allow-empty"),
    "at least one"
  )
  expect_error(
    np_domain_mapping_hard(
      "m", "x", "p", "a", "d", "allow-empty",
      parcel_keys[c(4, 3)], target_keys = parcel_keys
    ),
    "exact target-domain order"
  )
})
