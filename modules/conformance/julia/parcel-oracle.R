#!/usr/bin/env Rscript
# R-client oracle for the same ADR 0005 profiles as parcel_oracle.jl. The
# package source directory is explicit so the conformance suite tests this
# checkout without requiring installation.

args <- commandArgs(trailingOnly = TRUE)
if (length(args) != 1L) {
  stop("usage: Rscript parcel-oracle.R <clients/r/neuropublish>", call. = FALSE)
}
pkg <- normalizePath(args[[1]], winslash = "/", mustWork = TRUE)
source(file.path(pkg, "R", "manifest.R"), local = FALSE)
source(file.path(pkg, "R", "vocabulary.R"), local = FALSE)
source(file.path(pkg, "R", "domain.R"), local = FALSE)

keys <- c(
  "schaefer2018-7networks-lh-visual-1",
  "schaefer2018-7networks-lh-default-1",
  "schaefer2018-7networks-rh-visual-1",
  "schaefer2018-7networks-rh-default-1"
)
finite <- np_finite_indexed_preimage(keys)
assignment <- np_hard_assignment_bytes(c(0, 0, 1, 1, 2, 2, 3, 3), 4)
foreign <- keys
foreign[[1]] <- "schaefer2018-17networks-lh-visual-1"

sha <- function(bytes) {
  paste0("sha256:", digest::digest(bytes, algo = "sha256", serialize = FALSE))
}
b64 <- function(bytes) gsub("[[:space:]]", "", jsonlite::base64_enc(bytes))

cat(jsonlite::toJSON(list(
  finiteFingerprint = sha(finite),
  finiteBytes = b64(finite),
  finiteSize = length(finite),
  assignmentDigest = sha(assignment),
  assignmentBytes = b64(assignment),
  assignmentSize = length(assignment),
  reorderedFingerprint = np_finite_indexed_fingerprint(keys[c(2, 1, 3, 4)]),
  foreignFingerprint = np_finite_indexed_fingerprint(foreign)
), auto_unbox = TRUE), "\n", sep = "")
