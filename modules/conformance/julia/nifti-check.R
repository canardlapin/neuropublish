#!/usr/bin/env Rscript
# Neuropublish independent-reader probe (ADR 0001, Stage 2 neutrality proof): read every
# NIfTI volume the Julia producer wrote with neuroim2 and check what it sees — shape, spacing,
# origin, affine, and the voxel values at the probe indices — against `oracle.json`, which the
# producer writes from the synthetic arrays it knows. A volume that neuroim2 reads differently
# from how Julia wrote it fails here, before any Neuropublish code is involved.
#
# Usage: Rscript nifti-check.R <bundle-dir>     (the --out directory of producer.jl)
args <- commandArgs(trailingOnly = TRUE)
if (length(args) != 1) {
  cat("usage: Rscript nifti-check.R <bundle-dir>\n", file = stderr())
  quit(status = 2)
}
suppressPackageStartupMessages({
  library(jsonlite)
  library(neuroim2)
})
bundle <- args[1]
oracle <- jsonlite::fromJSON(file.path(bundle, "oracle.json"), simplifyVector = FALSE)
num <- function(x) as.numeric(unlist(x))
failures <- character()
expect <- function(ok, what) {
  if (!isTRUE(ok)) failures <<- c(failures, what)
}
near <- function(a, b, tol = 1e-6) length(a) == length(b) && all(abs(a - b) <= tol)

affine <- do.call(rbind, lapply(oracle$affine, num))
checked <- 0L
for (entry in oracle$files) {
  id <- entry$id
  path <- file.path(bundle, "assets", entry$file)
  vol <- neuroim2::read_vol(path)
  expect(
    identical(as.integer(dim(vol)), as.integer(num(oracle$shape))),
    sprintf(
      "%s: shape %s, expected %s", id, paste(dim(vol), collapse = "x"),
      paste(num(oracle$shape), collapse = "x")
    )
  )
  expect(
    near(num(spacing(vol)), num(oracle$spacing)),
    sprintf("%s: spacing %s", id, paste(spacing(vol), collapse = ","))
  )
  expect(
    near(num(origin(vol)), num(oracle$origin)),
    sprintf("%s: origin %s", id, paste(origin(vol), collapse = ","))
  )
  expect(
    near(as.numeric(trans(vol)), as.numeric(affine)),
    sprintf("%s: affine differs from the oracle", id)
  )
  for (probe in oracle$probes) {
    v <- num(probe$voxel1)
    want <- NULL
    for (pv in probe$values) if (identical(pv$id, id)) want <- pv$value
    got <- as.numeric(vol[v[1], v[2], v[3]])
    expect(
      !is.null(want) && near(got, want),
      sprintf(
        "%s[%d,%d,%d] = %s, expected %s", id, v[1], v[2], v[3],
        format(got, digits = 10), format(want, digits = 10)
      )
    )
  }
  checked <- checked + 1L
}
if (length(failures) > 0) {
  cat(paste0("error: ", failures, "\n"), file = stderr(), sep = "")
  quit(status = 1)
}
cat(sprintf("checked %d volumes, %d probes each\n", checked, length(oracle$probes)))
