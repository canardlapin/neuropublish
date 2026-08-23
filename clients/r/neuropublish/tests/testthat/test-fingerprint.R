# The fingerprints pinned here are the ones in
# modules/conformance/fixtures/reference/manifest.json (24 x 28 x 20, 2 mm,
# origin -22, -30, -18) and fixtures/julia/manifest.json (16 x 16 x 12, 2 mm,
# origin -15, -15, -11). When the checkout is available the fixture files are
# read as well, so a changed fixture fails here rather than drifting.
reference_fp <- "sha256:655d2d778d0cc2fd8f25226d044d65868984fe84510944c86b26bac93b251a37"
julia_fp <- "sha256:aa1d8cdb290b7739ff086b1f380552f59366c8671dc33425716abd5f2bce7687"

fixture_fingerprint <- function(which) {
  p <- np_fixture(which, "manifest.json")
  if (is.null(p)) {
    return(NULL)
  }
  m <- jsonlite::fromJSON(p, simplifyVector = FALSE)
  m$domains[[1]]$key$structuralFingerprint
}

test_that("the reference space fingerprints exactly as the reference fixture", {
  sp <- neuroim2::NeuroSpace(c(24L, 28L, 20L), spacing = c(2, 2, 2), origin = c(-22, -30, -18))
  d <- np_domain_volume(sp, id = "mni-2mm")
  expect_identical(d$key$structuralFingerprint, reference_fp)
  expect_identical(d$key$size, 13440L)
  expect_identical(d$descriptor$payload$shape, c(24L, 28L, 20L))
  expect_equal(d$descriptor$payload$affine[[1]], c(2, 0, 0, -22))
  expect_identical(d$key$descriptor, np_volume_grid_schema)
  fx <- fixture_fingerprint("reference")
  if (!is.null(fx)) expect_identical(fx, reference_fp)
})

test_that("the Julia producer's space fingerprints exactly as its fixture", {
  vols <- np_synthetic_volumes()
  d <- np_domain_volume(vols$t1, id = "grid-2mm")
  expect_identical(d$key$structuralFingerprint, julia_fp)
  expect_identical(d$key$size, 3072L)
  fx <- fixture_fingerprint("julia")
  if (!is.null(fx)) expect_identical(fx, julia_fp)
})

test_that("the fingerprint follows the ADR 0005 preimage (negative zero normalised)", {
  affine <- diag(c(2, 2, 2, 1))
  affine[1:3, 4] <- c(-22, -30, -18)
  a <- np_volume_grid_fingerprint(c(24, 28, 20), affine, "MNI152NLin2009cAsym")
  affine[1, 2] <- -0
  b <- np_volume_grid_fingerprint(c(24, 28, 20), affine, "MNI152NLin2009cAsym")
  expect_identical(a, reference_fp)
  expect_identical(b, reference_fp)
  expect_false(identical(np_volume_grid_fingerprint(c(24, 28, 20), affine, "other"), reference_fp))
  expect_error(np_volume_grid_fingerprint(c(24, 28), affine, "x"), "three integers")
  expect_error(np_volume_grid_fingerprint(c(24, 28, 20), affine[1:3, ], "x"), "4 x 4")
})

test_that("the synthetic volumes reproduce the Julia oracle's probe values", {
  oracle_path <- np_fixture("julia", "oracle.json")
  skip_if(is.null(oracle_path), "fixtures/julia/oracle.json not available")
  oracle <- jsonlite::fromJSON(oracle_path, simplifyVector = FALSE)
  vols <- np_synthetic_volumes()
  for (probe in oracle$probes) {
    v <- unlist(probe$voxel1)
    for (pv in probe$values) {
      got <- as.numeric(vols[[pv$id]][v[1], v[2], v[3]])
      expect_equal(got, pv$value, tolerance = 1e-6, label = paste(pv$id, paste(v, collapse = ",")))
    }
  }
})
