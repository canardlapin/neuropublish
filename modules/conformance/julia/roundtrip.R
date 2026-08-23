#!/usr/bin/env Rscript
# Neuropublish round-trip probe (ADR 0001, Stage 2 neutrality proof): decode a
# manifest with jsonlite and re-encode it. The guarantee under test is value
# preservation of unknown records and fields after decode/re-encode, not byte
# preservation (the digest covers the original bytes, which the server keeps).
#
# Usage: Rscript roundtrip.R <in.json> <out.json>
#
# `simplifyVector = FALSE` keeps every JSON array a list, so one-element arrays
# are not collapsed to scalars; `auto_unbox = TRUE` writes length-one atomic
# vectors (every JSON scalar) back as scalars; `digits = NA` keeps full
# double precision; `null = "null"` writes JSON null for NULL.
args <- commandArgs(trailingOnly = TRUE)
if (length(args) != 2) {
  cat("usage: Rscript roundtrip.R <in.json> <out.json>\n", file = stderr())
  quit(status = 2)
}
suppressPackageStartupMessages(library(jsonlite))
manifest <- jsonlite::fromJSON(args[1], simplifyVector = FALSE)
text <- jsonlite::toJSON(
  manifest,
  auto_unbox = TRUE,
  digits = NA,
  null = "null",
  pretty = TRUE
)
writeLines(enc2utf8(as.character(text)), args[2], useBytes = TRUE)
