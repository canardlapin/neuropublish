# The Neuropublish checkout, when the package is tested in place (or under
# R CMD check from within the repository): walk up from the working directory
# to the first directory holding build.sbt. NULL when the package is tested
# from a tarball elsewhere; the tests that need the fixtures then skip.
np_repo_root <- function() {
  dir <- normalizePath(getwd(), winslash = "/")
  repeat {
    if (file.exists(file.path(dir, "build.sbt")) && dir.exists(file.path(dir, "modules", "conformance"))) {
      return(dir)
    }
    parent <- dirname(dir)
    if (identical(parent, dir)) {
      return(NULL)
    }
    dir <- parent
  }
}

np_fixture <- function(...) {
  root <- np_repo_root()
  if (is.null(root)) {
    return(NULL)
  }
  p <- file.path(root, "modules", "conformance", "fixtures", ...)
  if (file.exists(p)) p else NULL
}

# A rotated, full-precision affine (2 mm voxels, 1 degree about z). The affine
# that is hashed must be the affine the JSON parses back to: the server
# recomputes the volume-grid key from the parsed descriptor payload, so a
# number the writer rounds is a rejected revision. jsonlite's default
# (`digits = NA`, 15 significant digits) does not round-trip cos/sin of one
# degree; `digits = I(17)` round-trips every double.
np_oblique_affine <- function() {
  a <- 1 * pi / 180
  affine <- diag(4)
  affine[1:3, 1:3] <- matrix(
    c(cos(a), sin(a), 0, -sin(a), cos(a), 0, 0, 0, 1), 3, 3
  ) %*% diag(c(2, 2, 2))
  affine[1:3, 4] <- c(-22.5, -30.25, -18.125)
  affine
}

# A temporary directory for one test. Base R only (withr is a Suggests): the
# session's tempdir() is removed when R exits, so nothing is left behind.
np_tempdir <- function() {
  d <- tempfile("np-")
  dir.create(d, recursive = TRUE)
  normalizePath(d, winslash = "/")
}

# Run `code` with environment variables set, restoring them afterwards. `NA`
# unsets. The base equivalent of withr::local_envvar().
np_with_envvar <- function(vars, code) {
  keys <- names(vars)
  old <- Sys.getenv(keys, unset = NA, names = TRUE)
  set <- vars[!is.na(vars)]
  if (length(set)) do.call(Sys.setenv, as.list(set))
  if (any(is.na(vars))) Sys.unsetenv(keys[is.na(vars)])
  on.exit({
    keep <- old[!is.na(old)]
    if (length(keep)) do.call(Sys.setenv, as.list(keep))
    gone <- names(old)[is.na(old)]
    if (length(gone)) Sys.unsetenv(gone)
  }, add = TRUE)
  force(code)
}

# The tests that run the `npub` CLI are integration tests: they need a built
# CLI (through `scripts/npub`, a working sbt and a JVM) and take minutes on a
# cold cache, so they are opt-in. They run when NPUB_TESTS=1, or under the
# NOT_CRAN=true that devtools::test() and testthat::test_local() set, and only
# when `npub` is actually resolvable. `R CMD check --as-cran` on a tarball
# leaves both unset, so the check is hermetic wherever it is run.
np_run_cli_tests <- function() {
  nzchar(Sys.getenv("NPUB_TESTS")) || identical(tolower(Sys.getenv("NOT_CRAN")), "true")
}

skip_without_npub <- function() {
  if (!np_run_cli_tests()) {
    skip("CLI integration tests are opt-in: set NPUB_TESTS=1 (or NOT_CRAN=true)")
  }
  if (!np_has_npub()) skip("npub not found (set NPUB_BIN or run inside the Neuropublish checkout)")
}

# A manifest mirroring fixtures/julia/manifest.json (values, not bytes), with
# assets staged from the synthetic volumes.
np_julia_like_manifest <- function(staging) {
  vols <- np_synthetic_volumes()
  receipt <- function(subject, noise) {
    np_record("org.bbuchsbaum.fmrireg/analysis-receipt", "1.0",
      list(subject = subject, temporalNoise = noise, drift = "cosine-128s", hrf = "spmg1"),
      digest = "sha256:aa00000000000000000000000000000000000000000000000000000000000001"
    )
  }
  m <- np_manifest(
    "Julia producer — synthetic speech group model",
    "Written by the R client from the same synthetic volumes."
  )
  m <- np_add(m, "domains", np_domain_volume(neuroim2::space(vols$t1), id = "grid-2mm"))
  m <- np_add(m, "assets", np_asset_volume("t1", vols$t1, staging = staging, catalog = "synthetic:r/t1"))
  for (id in c("speech-effect", "speech-t", "speech-z")) {
    m <- np_add(m, "assets", np_asset_volume(id, vols[[id]], staging = staging))
  }
  m <- np_add(m, "analyses", np_analysis("group-model", "Group model · speech (synthetic)",
    method = np_record("org.example.r/reducer/mean", "0.1", list(weights = "equal", inputs = 12)),
    sample_size = 12,
    estimands = list(np_estimand("speech", "speech coefficient", order = 1))
  ))
  m <- np_add(m, "resultFields", np_field("speech-effect", "speech", np_measure$effect, "grid-2mm",
    representations = list(np_volume_rep("speech-effect")), order = 1
  ))
  m <- np_add(m, "resultFields", np_field("speech-t", "speech", np_measure$t_statistic, "grid-2mm",
    representations = list(np_volume_rep("speech-t")), order = 2,
    published_display = np_display(np_threshold("two-sided", 2.5), np_window(-6, 6, centre = 0), "cold-hot")
  ))
  m <- np_add(m, "resultFields", np_field("speech-z", "speech", np_measure$z_statistic, "grid-2mm",
    representations = list(np_volume_rep("speech-z")), order = 3,
    published_display = np_display(np_threshold("two-sided", 2.3), np_window(-5, 5, centre = 0), "cold-hot")
  ))
  m <- np_add(m, "underlays", np_underlay("t1", "grid-2mm", "Synthetic T1"))
  m$provenance <- np_provenance(
    entities = list(np_entity("raw", "Synthetic raw data")),
    activities = list(
      np_activity("first-level-01", receipt("01", "AR(2)")),
      np_activity("first-level-02", receipt("02", "AR(1)")),
      np_activity("denoise", np_record("org.example.r/denoise", "0.1",
        list(method = "wavelet", levels = 3, threshold = 0.05)
      ))
    ),
    edges = list(
      np_edge("raw", "first-level-01"), np_edge("raw", "first-level-02"),
      np_edge("first-level-01", "denoise"), np_edge("first-level-02", "denoise"),
      np_edge("denoise", "speech-t")
    )
  )
  m
}
