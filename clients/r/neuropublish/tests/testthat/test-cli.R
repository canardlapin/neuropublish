test_that("npub is resolved from NPUB_BIN, PATH, then the checkout", {
  withr::local_envvar(NPUB_BIN = "/nonexistent/npub")
  expect_error(np_npub_bin(), "does not exist")
  withr::local_envvar(NPUB_BIN = "")
  root <- np_repo_root()
  skip_if(is.null(root), "not inside the Neuropublish checkout")
  bin <- np_npub_bin()
  if (!nzchar(Sys.which("npub"))) {
    expect_identical(bin, file.path(root, "scripts", "npub"))
  }
  expect_true(np_has_npub())
})

test_that("a manifest built with the R builders is admitted by npub validate after packing", {
  skip_without_npub()
  staging <- withr::local_tempdir()
  work <- withr::local_tempdir()
  m <- np_julia_like_manifest(staging)
  m$`x-r-client` <- list(version = "0.1", nested = list(flags = c(TRUE, FALSE), empty = list()))
  np_write_bundle(m, file.path(work, "staging"))

  # a staging bundle is not admitted: digest/size are required
  problems <- np_validate(file.path(work, "staging"))
  expect_s3_class(problems, "data.frame")
  expect_named(problems, c("pointer", "message"))
  expect_gt(nrow(problems), 0)
  expect_true(any(grepl("^/assets/0", problems$pointer)))

  packed <- np_pack(file.path(work, "staging"), file.path(work, "bundle.npub"))
  expect_match(packed$digest, "^sha256:[0-9a-f]{64}$")
  expect_identical(sort(packed$assets$id), sort(c("t1", "speech-effect", "speech-t", "speech-z")))
  expect_true(all(packed$assets$size == 352 + 16 * 16 * 12 * 4))
  expect_identical(packed$digest, np_digest(file.path(packed$dir, "manifest.json")))
  expect_error(np_pack(file.path(work, "staging"), file.path(work, "bundle.npub")), "already exists")

  ok <- np_validate(packed$dir)
  expect_identical(nrow(ok), 0L)
  expect_identical(attr(ok, "digest"), packed$digest)
  expect_match(attr(ok, "assets"), "4 declared, 4 volume")

  # the packed manifest: digests filled in, paths gone, unknown fields intact
  pm <- np_read_manifest(packed$dir)
  expect_null(pm$assets[[1]]$path)
  expect_identical(pm$assets[[1]]$digest, packed$assets$digest[packed$assets$id == "t1"])
  expect_identical(pm$`x-r-client`$nested$flags, list(TRUE, FALSE))
  expect_identical(pm$domains[[1]]$key$structuralFingerprint, m$domains[[1]]$key$structuralFingerprint)
  for (a in pm$assets) {
    hex <- sub("^sha256:", "", a$digest)
    expect_true(file.exists(file.path(packed$dir, "assets", "sha256", substr(hex, 1, 2), hex)))
  }
})

test_that("np_validate reports admission problems as pointer/message rows", {
  skip_without_npub()
  out <- withr::local_tempdir()
  m <- np_manifest("T", "S")
  m$assets <- list(list(id = "a", digest = paste0("sha256:", strrep("0", 64)), size = 1L,
    mediaType = "application/x-nifti"))
  m$resultFields <- list(np_field("f", "missing-estimand", np_measure$effect, "no-such-domain",
    representations = list(np_volume_rep("a"))))
  np_write_bundle(m, out)
  problems <- np_validate(out)
  expect_gt(nrow(problems), 0)
  expect_true(all(grepl("^(/|$)", problems$pointer)))
  expect_true(any(grepl("^/resultFields/0", problems$pointer)))
})

test_that("the reference and Julia fixtures validate through the wrapper", {
  skip_without_npub()
  ref <- np_fixture("reference")
  skip_if(is.null(ref), "fixtures not available")
  expect_identical(nrow(np_validate(ref)), 0L)
  expect_identical(attr(np_validate(ref), "digest"), np_digest(file.path(ref, "manifest.json")))
  expect_identical(nrow(np_validate(np_fixture("julia"))), 0L)
})
