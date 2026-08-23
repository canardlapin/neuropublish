test_that("builders serialize to the schema's records", {
  m <- np_manifest("T", "S")
  expect_identical(m$core, "0.1")
  expect_identical(m$sensitivity, "group-level")
  json <- jsonlite::toJSON(m, auto_unbox = TRUE, digits = NA, null = "null")
  v <- jsonlite::fromJSON(json, simplifyVector = FALSE)
  expect_identical(v$axes[[1]]$values, list("group")) # one value is still an array
  expect_identical(v$assets, list())
  expect_error(np_manifest("T", "S", sensitivity = "secret"))

  f <- np_field("speech-t", "speech", np_measure$t_statistic, "d",
    representations = list(np_volume_rep("speech-t")), order = 3,
    published_display = np_display(np_threshold("two-sided", 3.1), np_window(-8, 8, centre = 0), "cold-hot")
  )
  fv <- jsonlite::fromJSON(jsonlite::toJSON(f, auto_unbox = TRUE, digits = NA), simplifyVector = FALSE)
  expect_identical(fv$measure, "org.neuropublish.measure/t-statistic")
  expect_identical(fv$representations[[1]], list(kind = "volume", asset = "speech-t"))
  expect_identical(fv$publishedDisplay$threshold, list(mode = "two-sided", min = 3.1))
  expect_identical(fv$order, 3L)
  expect_null(fv$label)
  expect_error(np_threshold("two-sided", -1), "non-negative")
  expect_error(np_window(3, 1), "min < max")

  w <- np_warning("w", "msg", concerns = list(analysis = "a"))
  expect_identical(w$concerns$analysis, "a")
  expect_error(np_warning("w", "msg", concerns = list(other = "a")), "concerns")
  expect_error(np_add(np_add(m, "warnings", w), "warnings", w), "already has")

  r <- np_record("org.example/x", "1.0", list(a = 1))
  expect_null(r$schema$digest)
  expect_identical(np_activity("act", r, label = "L")$schema$id, "org.example/x")
  expect_error(np_record("org.example/x", "1.0", list(1)), "named list")
})

test_that("np_write_bundle writes a staging bundle with copied assets and relative paths", {
  staging <- np_tempdir()
  out <- np_tempdir()
  m <- np_julia_like_manifest(staging)
  expect_true(all(file.exists(vapply(m$assets, `[[`, character(1), "path"))))
  written <- np_write_bundle(m, out)
  expect_identical(as.character(written), out)
  expect_match(attr(written, "digest"), "^sha256:[0-9a-f]{64}$")
  expect_true(file.exists(file.path(out, "manifest.json")))
  back <- np_read_manifest(out)
  expect_identical(back$assets[[1]]$path, "assets/t1.nii")
  expect_true(file.exists(file.path(out, "assets", "speech-t.nii")))
  expect_null(back$assets[[1]]$digest)
  expect_identical(back$assets[[1]]$catalog, "synthetic:r/t1")
  expect_identical(back$domains[[1]]$key$size, 3072L)
  expect_equal(back$resultFields[[2]]$publishedDisplay$window, list(min = -6, centre = 0, max = 6))
  expect_error(np_write_bundle(m, out), "already exists")
  expect_no_error(np_write_bundle(m, out, overwrite = TRUE))
})

test_that("the manifest digest equals shasum's / the bytes are UTF-8 without BOM", {
  out <- np_tempdir()
  m <- np_manifest("Dígest — ünïcode", "S")
  m$`x-note` <- "é中"
  np_write_bundle(m, out)
  path <- file.path(out, "manifest.json")
  bytes <- readBin(path, "raw", file.size(path))
  expect_false(identical(bytes[1:3], as.raw(c(0xef, 0xbb, 0xbf))))
  expect_identical(bytes[1], charToRaw("{"))
  expect_identical(bytes[length(bytes)], charToRaw("\n"))
  expect_true(validUTF8(rawToChar(bytes)))
  expected <- paste0("sha256:", digest::digest(bytes, algo = "sha256", serialize = FALSE))
  expect_identical(np_digest(path), expected)
  shasum <- Sys.which("shasum")
  if (nzchar(shasum)) {
    line <- system2(shasum, c("-a", "256", shQuote(path)), stdout = TRUE)
    expect_identical(paste0("sha256:", sub(" .*$", "", line)), np_digest(path))
  }
})

test_that("unknown fields added by the user survive writing (value-level)", {
  out <- np_tempdir()
  m <- np_manifest("T", "S")
  m$`x-lab-producer` <- list(
    version = "0.1",
    nested = list(flags = c(TRUE, FALSE), empty = list(), ratio = 0.125, ids = I("one"))
  )
  m$assets <- list(list(id = "a", digest = paste0("sha256:", strrep("0", 64)), size = 1L,
    mediaType = "application/x-nifti", `x-extra` = list(min = 0, max = 100)))
  np_write_bundle(m, out)
  back <- np_read_manifest(out)
  expect_identical(back$`x-lab-producer`$version, "0.1")
  expect_identical(back$`x-lab-producer`$nested$flags, list(TRUE, FALSE))
  expect_identical(back$`x-lab-producer`$nested$empty, list())
  expect_identical(back$`x-lab-producer`$nested$ratio, 0.125)
  expect_identical(back$`x-lab-producer`$nested$ids, list("one"))
  expect_identical(back$assets[[1]]$`x-extra`, list(min = 0L, max = 100L))
})

test_that("NaN, NA and infinite numbers are refused with a pointer", {
  out <- np_tempdir()
  m <- np_manifest("T", "S")
  m$`x-bad` <- list(value = NaN)
  expect_error(np_write_bundle(m, out), "non-finite.*`/x-bad/value`")
  m$`x-bad` <- list(values = c(1, Inf))
  expect_error(np_write_bundle(m, out), "`/x-bad/values/1`")
  m$`x-bad` <- NA_real_
  expect_error(np_write_bundle(m, out), "`/x-bad`")
  m$`x-bad` <- NA
  expect_error(np_write_bundle(m, out), "NA at `/x-bad`")
  m$`x-bad` <- NULL
  expect_no_error(np_write_bundle(m, out))
})

test_that("as_neuropublish() reference implementation builds a complete result", {
  staging <- np_tempdir()
  vols <- np_synthetic_volumes()
  r <- as_neuropublish(vols,
    title = "Synthetic", synopsis = "Four volumes.",
    measures = c(
      "speech-effect" = np_measure$effect, "speech-t" = np_measure$t_statistic,
      "speech-z" = np_measure$z_statistic
    ),
    estimand = "speech", estimand_label = "speech coefficient",
    underlay = "t1", underlay_label = "Synthetic T1", domain_id = "grid-2mm",
    staging = staging
  )
  expect_s3_class(r, "neuropublish_result")
  expect_length(r$assets, 4)
  expect_length(r$manifest$resultFields, 3)
  expect_identical(r$manifest$underlays[[1]]$asset, "t1")
  expect_identical(r$manifest$domains[[1]]$key$structuralFingerprint,
    "sha256:aa1d8cdb290b7739ff086b1f380552f59366c8671dc33425716abd5f2bce7687")
  expect_identical(as_neuropublish(r), r)
  expect_output(print(r), "neuropublish_result")
  expect_error(as_neuropublish(42), "no as_neuropublish\\(\\) method")
  expect_error(as_neuropublish(vols, title = "x", synopsis = "y", measures = c("speech-t" = "m"), staging = staging),
    "no measure")
})
