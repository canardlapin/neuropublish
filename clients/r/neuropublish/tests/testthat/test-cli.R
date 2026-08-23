test_that("npub is resolved from NPUB_BIN, PATH, then the checkout", {
  np_with_envvar(c(NPUB_BIN = "/nonexistent/npub"), expect_error(np_npub_bin(), "does not exist"))
  np_with_envvar(c(NPUB_BIN = NA), {
  root <- np_repo_root()
  skip_if(is.null(root), "not inside the Neuropublish checkout")
  bin <- np_npub_bin()
  if (!nzchar(Sys.which("npub"))) {
    expect_identical(bin, file.path(root, "scripts", "npub"))
  }
  expect_true(np_has_npub())
  })
})

# A stand-in `npub`: a shell script that prints what the test wants and exits
# with the status it wants. It exercises the same np_run() path as the real
# CLI (NPUB_BIN, the `#!` detection, bash) without needing a JVM.
fake_npub <- function(body, status = 0) {
  skip_on_os("windows")
  path <- file.path(np_tempdir(), "npub")
  writeLines(c("#!/usr/bin/env bash", body, paste("exit", status)), path)
  Sys.chmod(path, "0755")
  path
}

test_that("a subcommand's --json document is decoded from the CLI's stdout", {
  bin <- fake_npub(paste0(
    'echo "progress: hashing" >&2\n',
    'echo \'{"ok":true,"digest":"sha256:ab","assets":{"declared":2,"volume":1}}\''
  ))
  np_with_envvar(c(NPUB_BIN = bin), {
    doc <- np_run_json(c("validate", "/some/bundle"), "validate")
    expect_true(doc$ok)
    expect_identical(doc$digest, "sha256:ab")
    expect_identical(attr(doc, "status"), 0L)
    expect_identical(attr(doc, "stderr"), "progress: hashing") # progress is off stdout
    frame <- np_validate_result(doc)
    expect_identical(nrow(frame), 0L)
    expect_identical(attr(frame, "assets"), "2 declared, 1 volume")
  })
})

test_that("the wrapper passes --json and gets the argument list it expects", {
  bin <- fake_npub('printf \'{"ok":true,"digest":"%s","assets":{"declared":0,"volume":0}}\' "$*"')
  np_with_envvar(c(NPUB_BIN = bin), {
    doc <- np_run_json(c("validate", "/b"), "validate")
    expect_identical(doc$digest, "validate /b --json")
  })
})

test_that("a CLI that dies without a document is an error, not an empty problem set", {
  bin <- fake_npub('echo "Exception in thread \\"main\\" java.lang.Error" >&2', status = 1)
  np_with_envvar(c(NPUB_BIN = bin), {
    expect_error(np_validate(np_tempdir()), "did not return a JSON document \\(exit 1\\)")
    expect_error(np_validate(np_tempdir()), "java.lang.Error")
  })
})

test_that("np_login streams the CLI's output instead of capturing it", {
  # With echo, np_run()'s system2 fallback connects both streams to the console
  # (stdout = ""), so the device code is visible while the CLI waits; nothing
  # comes back captured. Capturing (echo = FALSE) is the other branch.
  bin <- fake_npub('echo "open http://s/device and enter WDJB-MJHT"')
  quiet <- np_run_system2("bash", bin, echo = FALSE)
  expect_identical(quiet$status, 0L)
  expect_identical(quiet$stdout, "open http://s/device and enter WDJB-MJHT")

  streamed <- capture.output(res <- np_run_system2("bash", bin, echo = TRUE))
  expect_identical(res$status, 0L)
  expect_identical(res$stdout, character()) # it went to the console, not into R
  expect_true(any(grepl("WDJB-MJHT", streamed)) || identical(streamed, character()))

  # a failing login says so, with whatever the CLI managed to print
  bin <- fake_npub('echo "error  cannot reach http://s"', status = 2)
  np_with_envvar(c(NPUB_BIN = bin), {
    err <- expect_error(np_login("http://s"), "npub login failed \\(exit 2\\)")
    expect_match(conditionMessage(err), "cannot reach|see the output above")
  })
})

# What the wrapper does with the CLI's `--json` document, without running the
# CLI: these are the parsing contracts the integration tests below exercise for
# real. The fixtures are the exact documents JsonOutputSuite pins in Scala.
test_that("a validate document becomes zero rows with attributes, or problem rows", {
  ok <- np_decode_cli(
    '{"ok":true,"digest":"sha256:ab","assets":{"declared":4,"volume":4}}', "validate"
  )
  frame <- np_validate_result(ok)
  expect_identical(nrow(frame), 0L)
  expect_named(frame, c("pointer", "message"))
  expect_identical(attr(frame, "digest"), "sha256:ab")
  expect_identical(attr(frame, "assets"), "4 declared, 4 volume")

  bad <- np_decode_cli(paste0(
    '{"ok":false,"problems":[{"pointer":"/assets/0/digest","message":"digest is required"},',
    '{"pointer":"","message":"core is required"}]}'
  ), "validate")
  frame <- np_validate_result(bad)
  expect_identical(frame$pointer, c("/assets/0/digest", ""))
  expect_identical(frame$message, c("digest is required", "core is required"))
})

test_that("a runtime failure is an np_cli_error, never a problem row", {
  doc <- np_decode_cli(
    '{"ok":false,"error":{"type":"NoSuchFileException","message":"/tmp/b/manifest.json"}}',
    "validate"
  )
  err <- expect_error(np_validate_result(doc), class = "np_cli_error")
  expect_identical(err$type, "NoSuchFileException")
  expect_match(conditionMessage(err), "manifest.json")

  # a message that looks like a pointer line must not be re-parsed into columns
  doc <- np_decode_cli(
    '{"ok":false,"error":{"type":"cli","message":"/a/b: not a bundle: 2 problem(s)"}}',
    "pack"
  )
  expect_error(np_validate_result(doc), class = "np_cli_error")
})

test_that("output that is not one JSON document is an error naming what ran", {
  expect_error(np_decode_cli("Exception in thread \"main\"", "validate"), "did not return a JSON")
  expect_error(np_decode_cli(character(), "pack"), "did not return a JSON document")
  expect_error(np_decode_cli("{\"problems\":[]}", "push"), "did not return a JSON")
})

test_that("pack and push documents decode into the wrapper's values", {
  assets <- np_pack_assets(list(
    list(id = "t1", size = 3424, digest = paste0("sha256:", strrep("a", 64))),
    list(id = "speech-t", size = 12648430, digest = paste0("sha256:", strrep("b", 64)))
  ))
  expect_identical(assets$id, c("t1", "speech-t"))
  expect_identical(assets$size, c(3424, 12648430))
  expect_type(assets$size, "double")

  pushed <- np_push_result(np_decode_cli(paste0(
    '{"ok":true,"unchanged":false,"revision":"r2","parent":"r1","digest":"sha256:cd",',
    '"revisionUrl":"http://s/api/v1/revisions/r2","viewUrl":"http://s/v/r2"}'
  ), "push"))
  expect_false(pushed$unchanged)
  expect_identical(pushed$revision, "r2")
  expect_identical(pushed$parent, "r1")
  expect_identical(pushed$view_url, "http://s/v/r2")

  unchanged <- np_push_result(
    np_decode_cli('{"ok":true,"unchanged":true,"revision":"r1"}', "push"), parent = "r1"
  )
  expect_true(unchanged$unchanged)
  expect_identical(unchanged$revision, "r1")

  stale <- np_decode_cli(
    '{"ok":false,"error":{"type":"stale_parent","message":"parent is not the head","head":"r9"}}',
    "push"
  )
  err <- expect_error(np_push_result(stale, parent = "r1"), class = "np_stale_parent")
  expect_identical(err$head, "r9")

  denied <- np_decode_cli(
    '{"ok":false,"error":{"type":"forbidden","message":"not a member of rotman"}}', "push"
  )
  err <- expect_error(np_push_result(denied), class = "np_cli_error")
  expect_identical(err$type, "forbidden")
  expect_match(conditionMessage(err), "not a member")

  rejected <- np_decode_cli(paste0(
    '{"ok":false,"problems":[{"pointer":"/title","message":"title is required"}],',
    '"error":{"type":"manifest_rejected","message":"manifest rejected: 1 problem(s)"}}'
  ), "push")
  expect_error(np_push_result(rejected), "manifest_rejected")
})

test_that("a manifest built with the R builders is admitted by npub validate after packing", {
  skip_without_npub()
  staging <- np_tempdir()
  work <- np_tempdir()
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
  out <- np_tempdir()
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

test_that("a rotated affine is admitted: the server recomputes the key from the bytes", {
  skip_without_npub()
  work <- np_tempdir()
  staging <- np_tempdir()

  # full precision, not through a NeuroSpace (which stores signif(trans, 7)):
  # this is the affine the writer must not round, because admission recomputes
  # the volume-grid key from the parsed payload and compares.
  affine <- np_oblique_affine()
  shape <- c(16L, 16L, 12L)
  sp <- neuroim2::NeuroSpace(shape, trans = affine)
  vol <- neuroim2::NeuroVol(array(as.numeric(seq_len(prod(shape))), shape), sp)

  m <- np_manifest("Oblique grid", "A rotated affine at full precision.")
  m <- np_add(m, "domains", list(
    id = "oblique",
    key = list(
      descriptor = np_volume_grid_schema,
      size = prod(as.numeric(shape)),
      structuralFingerprint = np_volume_grid_fingerprint(shape, affine, "MNI152NLin2009cAsym")
    ),
    descriptor = list(
      schema = np_volume_grid_schema,
      payload = list(
        space = "MNI152NLin2009cAsym", coordinateConvention = "RAS+",
        spatialUnit = "mm", ordinalLayout = "x-fastest", shape = shape,
        affine = lapply(seq_len(4), function(r) as.numeric(affine[r, ]))
      )
    )
  ))
  m <- np_add(m, "assets", np_asset_volume("speech-t", vol, staging = staging))
  m <- np_add(m, "analyses", np_analysis("a", "Analysis",
    estimands = list(np_estimand("speech", "speech coefficient", order = 1))
  ))
  m <- np_add(m, "resultFields", np_field("speech-t", "speech", np_measure$t_statistic, "oblique",
    representations = list(np_volume_rep("speech-t")), order = 1
  ))

  np_write_bundle(m, file.path(work, "staging"))
  packed <- np_pack(file.path(work, "staging"), file.path(work, "bundle.npub"))
  problems <- np_validate(packed$dir)
  expect_identical(nrow(problems), 0L) # a rounded affine would be a key mismatch here
  expect_identical(
    np_read_manifest(packed$dir)$domains[[1]]$key$structuralFingerprint,
    m$domains[[1]]$key$structuralFingerprint
  )
})

test_that("the reference and Julia fixtures validate through the wrapper", {
  skip_without_npub()
  ref <- np_fixture("reference")
  skip_if(is.null(ref), "fixtures not available")
  expect_identical(nrow(np_validate(ref)), 0L)
  expect_identical(attr(np_validate(ref), "digest"), np_digest(file.path(ref, "manifest.json")))
  expect_identical(nrow(np_validate(np_fixture("julia"))), 0L)
})
