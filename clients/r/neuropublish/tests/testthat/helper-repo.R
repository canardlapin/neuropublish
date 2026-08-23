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

skip_without_npub <- function() {
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
