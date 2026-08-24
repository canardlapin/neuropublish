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
  labelled <- np_field("tau2", "speech", "org.fmrigds.measure/between-study-heterogeneity", "d",
    representations = list(np_volume_rep("tau2")), label = "Between-study heterogeneity (τ²)"
  )
  expect_identical(labelled$label, "Between-study heterogeneity (τ²)")
  expect_error(np_field("bad", "speech", np_measure$effect, "d",
    representations = list(np_volume_rep("bad")), label = 1
  ), "label.*single string")
  expect_error(np_threshold("two-sided", -1), "non-negative")
  expect_error(np_window(3, 1), "min < max")
  # core 0.1 renders one linear ramp across [min, max]: an off-midpoint centre
  # would be published, accepted by the schema, and then never applied.
  expect_error(np_window(-2, 8, centre = 0), "midpoint 3")
  expect_identical(np_window(-2, 8, centre = 3)$centre, 3)

  w <- np_warning("w", "msg", concerns = list(analysis = "a"))
  expect_identical(w$concerns$analysis, "a")
  expect_error(np_warning("w", "msg", concerns = list(other = "a")), "concerns")
  expect_error(np_add(np_add(m, "warnings", w), "warnings", w), "already has")

  r <- np_record("org.example/x", "1.0", list(a = 1))
  expect_null(r$schema$digest)
  expect_identical(np_activity("act", r, label = "L")$schema$id, "org.example/x")
  expect_error(np_record("org.example/x", "1.0", list(1)), "named list")
})

test_that("an empty payload and an empty selection are JSON objects, not arrays", {
  text <- function(x) as.character(jsonlite::toJSON(x, auto_unbox = TRUE, digits = I(17)))

  expect_match(text(np_record("org.example/x", "1.0")), '"payload":\\{\\}', fixed = FALSE)
  expect_false(grepl('"payload":[]', text(np_record("org.example/x", "1.0")), fixed = TRUE))
  expect_match(text(np_record("org.example/x", "1.0", list())), '"payload":\\{\\}')
  expect_match(text(np_record("org.example/x", "1.0", list(a = 1))), '"payload":\\{"a":1\\}')

  f <- np_field("f", "e", np_measure$effect, "d",
    selection = list(), representations = list(np_volume_rep("a"))
  )
  expect_match(text(f), '"selection":\\{\\}')
  expect_false(grepl('"selection":[]', text(f), fixed = TRUE))

  # and the same through the writer, in the bytes on disk
  out <- np_tempdir()
  m <- np_manifest("T", "S")
  m <- np_add(m, "analyses", np_analysis("a", "A",
    method = np_record("org.example/x", "1.0"),
    estimands = list(np_estimand("e", "E"))
  ))
  m$resultFields <- list(f)
  np_write_bundle(m, out, overwrite = TRUE)
  bytes <- readChar(file.path(out, "manifest.json"), file.size(file.path(out, "manifest.json")), TRUE)
  expect_match(bytes, '"payload":\\s*\\{\\s*\\}')
  expect_match(bytes, '"selection":\\s*\\{\\s*\\}')
  back <- np_read_manifest(out)
  expect_identical(back$analyses[[1]]$method$payload, structure(list(), names = character(0)))
  expect_identical(back$resultFields[[1]]$selection, structure(list(), names = character(0)))
})

test_that("full-precision doubles survive the writer, which 15 digits would not", {
  values <- as.numeric(t(np_oblique_affine()))
  expect_false(identical(
    values, as.numeric(jsonlite::fromJSON(jsonlite::toJSON(values, digits = NA)))
  )) # the defect: the old serialization did not round-trip these

  out <- np_tempdir()
  m <- np_manifest("T", "S")
  m$`x-values` <- values
  np_write_bundle(m, out, overwrite = TRUE)
  expect_identical(unlist(np_read_manifest(out)$`x-values`), values)
})

test_that("a full-precision oblique affine hashes to what the parsed JSON hashes to", {
  affine <- np_oblique_affine()
  shape <- c(24L, 28L, 20L)
  fingerprint <- np_volume_grid_fingerprint(shape, affine, "MNI152NLin2009cAsym")

  # a domain record built by hand, so nothing rounds the affine on the way in
  domain <- list(
    id = "oblique",
    key = list(
      descriptor = np_volume_grid_schema,
      size = prod(as.numeric(shape)),
      structuralFingerprint = fingerprint
    ),
    descriptor = list(
      schema = np_volume_grid_schema,
      payload = list(
        space = "MNI152NLin2009cAsym", coordinateConvention = "RAS+",
        spatialUnit = "mm", ordinalLayout = "x-fastest", shape = shape,
        affine = lapply(seq_len(4), function(r) as.numeric(affine[r, ]))
      )
    )
  )
  out <- np_tempdir()
  np_write_bundle(np_add(np_manifest("Oblique", "A rotated grid."), "domains", domain), out)

  back <- np_read_manifest(out)
  parsed <- do.call(rbind, lapply(back$domains[[1]]$descriptor$payload$affine, unlist))
  expect_identical(as.numeric(parsed), as.numeric(affine)) # hashed == parsed
  expect_identical(
    np_volume_grid_fingerprint(shape, parsed, "MNI152NLin2009cAsym"),
    back$domains[[1]]$key$structuralFingerprint
  )
  # the key really depends on those digits: what 15 of them parse to is another key
  fifteen <- matrix(
    as.numeric(jsonlite::fromJSON(jsonlite::toJSON(as.numeric(t(affine)), digits = NA))),
    4, 4,
    byrow = TRUE
  )
  expect_false(identical(
    np_volume_grid_fingerprint(shape, fifteen, "MNI152NLin2009cAsym"), fingerprint
  ))
})

test_that("np_domain_volume's affine survives the writer exactly", {
  # neuroim2::NeuroSpace() stores signif(trans, 7), so an affine that arrives
  # through a NeuroSpace is already short; the writer must still not touch it.
  sp <- neuroim2::NeuroSpace(c(24L, 28L, 20L), trans = np_oblique_affine())
  d <- np_domain_volume(sp, id = "oblique")
  out <- np_tempdir()
  np_write_bundle(np_add(np_manifest("Oblique", "A rotated grid."), "domains", d), out)

  back <- np_read_manifest(out)
  parsed <- do.call(rbind, lapply(back$domains[[1]]$descriptor$payload$affine, unlist))
  hashed <- unname(as.matrix(neuroim2::trans(sp)))
  expect_identical(as.numeric(parsed), as.numeric(hashed))
  expect_false(isTRUE(all.equal(hashed[1, 2], 0))) # genuinely oblique
  expect_identical(
    np_volume_grid_fingerprint(dim(sp), parsed, "MNI152NLin2009cAsym"),
    back$domains[[1]]$key$structuralFingerprint
  )
})

test_that("assets whose ids collapse to one file name are refused", {
  staging <- np_tempdir()
  out <- np_tempdir()
  vols <- np_synthetic_volumes()
  m <- np_manifest("T", "S")
  a <- np_asset_volume("a/b", vols$t1, staging = staging)
  b <- np_asset_volume("a_b", vols$`speech-t`, staging = staging)
  m$assets <- list(a, b)
  expect_error(np_write_bundle(m, out), "would both be staged as assets/a_b.nii")
  expect_error(np_write_bundle(m, out), "'a/b' and 'a_b'")
  expect_false(file.exists(file.path(out, "manifest.json")))

  m$assets <- list(a)
  expect_no_error(np_write_bundle(m, out, overwrite = TRUE))
})

test_that("a voxel count past 2^31 stays exact and is written as an integer literal", {
  sp <- neuroim2::NeuroSpace(c(2000L, 2000L, 1000L), spacing = c(1, 1, 1))
  d <- np_domain_volume(sp, id = "huge")
  expect_identical(d$key$size, 4e9)
  expect_type(d$key$size, "double")

  out <- np_tempdir()
  m <- np_add(np_manifest("Huge", "A grid with 4e9 voxels."), "domains", d)
  np_write_bundle(m, out, overwrite = TRUE)
  bytes <- readChar(file.path(out, "manifest.json"), file.size(file.path(out, "manifest.json")), TRUE)
  expect_match(bytes, '"size":\\s*4000000000\\b')
  expect_false(grepl("4e+09", bytes, fixed = TRUE))
  expect_false(grepl("4000000000.0", bytes, fixed = TRUE))
  expect_identical(np_read_manifest(out)$domains[[1]]$key$size, 4000000000)

  expect_error(np_volume_grid_fingerprint(c(0, 4, 4), diag(4), "x"), "positive integers")
  expect_error(np_volume_grid_fingerprint(c(2.5, 4, 4), diag(4), "x"), "positive integers")
  expect_error(np_volume_grid_fingerprint(c(1e10, 4, 4), diag(4), "x"), "positive integers")
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
    labels = c("speech-effect" = "Speech effect"),
    estimand = "speech", estimand_label = "speech coefficient",
    underlay = "t1", underlay_label = "Synthetic T1", domain_id = "grid-2mm",
    staging = staging
  )
  expect_s3_class(r, "neuropublish_result")
  expect_length(r$assets, 4)
  expect_length(r$manifest$resultFields, 3)
  expect_identical(r$manifest$resultFields[[1]]$label, "Speech effect")
  expect_null(r$manifest$resultFields[[2]]$label)
  expect_identical(r$manifest$underlays[[1]]$asset, "t1")
  expect_identical(r$manifest$domains[[1]]$key$structuralFingerprint,
    "sha256:aa1d8cdb290b7739ff086b1f380552f59366c8671dc33425716abd5f2bce7687")
  expect_identical(as_neuropublish(r), r)
  expect_output(print(r), "neuropublish_result")
  expect_error(as_neuropublish(42), "no as_neuropublish\\(\\) method")
  expect_error(as_neuropublish(list(a = 1)), "`x\\$a` is a numeric")
  expect_error(as_neuropublish(list(a = 1)), "write an as_neuropublish\\(\\) method")
  expect_error(
    as_neuropublish(list("speech-t" = vols$`speech-t`, model = lm(y ~ x, data.frame(x = 1:3, y = 1:3))),
      title = "x", synopsis = "y", measures = c("speech-t" = np_measure$t_statistic),
      staging = staging
    ),
    "`x\\$model` is a lm"
  )
  expect_error(as_neuropublish(vols, title = "x", synopsis = "y", measures = c("speech-t" = "m"), staging = staging),
    "no measure")
})
