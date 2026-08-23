# End-to-end against a running server. Skipped unless NPUB_SERVER is set.
#
# To run it with the backend scripts/e2e.sh uses (local-fs stores, inline
# ingestion), from the checkout root:
#
#   NP_DATA_DIR=$(mktemp -d) NP_PORT=8090 sbt backend/run &        # wait for /api/v1/health
#   NPUB_CONFIG_DIR=$(mktemp -d) scripts/npub login --server http://127.0.0.1:8090
#       # or: scripts/npub credential create --project rotman/sherlock --name r-e2e
#       #     and export its secret as NP_TOKEN
#   NPUB_SERVER=http://127.0.0.1:8090 NPUB_PROJECT=rotman/sherlock \
#     Rscript -e 'testthat::test_local("clients/r/neuropublish", filter = "e2e")'
#
# The test publishes a first revision (or builds on NPUB_PARENT when set),
# then proves the stale-parent rejection by pushing again with the parent the
# first push used, and re-pushes with the reported head.
test_that("np_publish publishes, and a stale parent is reported with the current head", {
  server <- Sys.getenv("NPUB_SERVER", "")
  skip_if(!nzchar(server), "NPUB_SERVER not set")
  skip_without_npub()
  project <- Sys.getenv("NPUB_PROJECT", "rotman/sherlock")
  parent <- Sys.getenv("NPUB_PARENT", "")
  if (!nzchar(parent)) parent <- NULL

  work <- np_tempdir()
  vols <- np_synthetic_volumes()
  first <- np_publish(vols, project,
    server = server, message = "R client e2e: first", parent = parent, dir = file.path(work, "one"),
    title = "R client e2e", synopsis = "Synthetic volumes from neuropublish (R).",
    measures = c("speech-t" = np_measure$t_statistic, "speech-z" = np_measure$z_statistic,
      "speech-effect" = np_measure$effect),
    estimand = "speech", estimand_label = "speech coefficient",
    underlay = "t1", underlay_label = "Synthetic T1", domain_id = "grid-2mm",
    staging = file.path(work, "staging")
  )
  expect_false(first$unchanged)
  expect_match(first$revision, "^\\S+$")
  expect_match(first$digest, "^sha256:[0-9a-f]{64}$")
  expect_match(first$revision_url, "^https?://")
  expect_match(first$view_url, "^https?://")
  expect_identical(first$digest, np_digest(file.path(first$bundle, "manifest.json")))

  # the same bytes again on the same parent: idempotent, not an error
  again <- np_push(first$bundle, project, server, message = "again", parent = parent)
  expect_true(again$unchanged)
  expect_identical(again$revision, first$revision)

  # different bytes on the stale parent: rejected with the current head
  # a packed bundle is itself a valid staging bundle (digest-only assets with
  # their bytes under assets/sha256/), so copy it and change the manifest
  dir.create(file.path(work, "two"))
  file.copy(first$bundle, file.path(work, "two"), recursive = TRUE)
  two <- file.path(work, "two", basename(first$bundle))
  m <- np_read_manifest(two)
  m$synopsis <- "Second revision from R."
  np_write_bundle(m, two, overwrite = TRUE)
  packed <- np_pack(two, file.path(work, "two.npub"))
  err <- expect_error(
    np_push(packed$dir, project, server, message = "stale", parent = parent),
    class = "np_stale_parent"
  )
  expect_identical(err$head, first$revision)

  second <- np_push(packed$dir, project, server, message = "second", parent = err$head)
  expect_false(second$unchanged)
  expect_false(identical(second$revision, first$revision))
})
