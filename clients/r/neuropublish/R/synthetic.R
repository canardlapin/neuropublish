#' Synthetic volumes mirroring the Julia conformance producer
#'
#' Builds the four volumes `modules/conformance/julia/producer.jl` writes
#' (`t1`, `speech-effect`, `speech-t`, `speech-z`) with the same formulas on
#' the same 16 x 16 x 12 grid (2 mm, origin -15, -15, -11), so values at any
#' voxel are known and the volume-grid fingerprint of their space equals the
#' one in `fixtures/julia/manifest.json`. Used by the vignette and the tests;
#' useful as a small, fully known example bundle.
#'
#' @return A named list of `neuroim2::NeuroVol` objects.
#' @examples
#' vols <- np_synthetic_volumes()
#' names(vols)
#' round(vols$`speech-t`[8, 8, 6], 4)
#' @export
np_synthetic_volumes <- function() {
  shape <- c(16L, 16L, 12L)
  space <- neuroim2::NeuroSpace(shape, spacing = c(2, 2, 2), origin = c(-15, -15, -11))
  idx <- expand.grid(i = seq_len(shape[1]), j = seq_len(shape[2]), k = seq_len(shape[3]))
  centre <- (shape + 1) / 2
  r2 <- ((idx$i - centre[1])^2 + (idx$j - centre[2])^2 + (idx$k - centre[3])^2) / 18
  g <- exp(-r2)
  bump <- exp(-((idx$i - 4)^2 + (idx$j - 12)^2 + (idx$k - 6)^2) / 6)
  f32 <- function(v) array(as.numeric(as.single_like(v)), shape)
  list(
    "t1" = neuroim2::NeuroVol(f32(100 * (1 - 0.5 * g) * ifelse(r2 < 3, 1, 0.4)), space),
    "speech-effect" = neuroim2::NeuroVol(f32(0.8 * g - 0.2 * bump), space),
    "speech-t" = neuroim2::NeuroVol(f32(6.0 * g - 3.5 * bump), space),
    "speech-z" = neuroim2::NeuroVol(f32(5.5 * g - 3.2 * bump), space)
  )
}

# Round to float32 precision the way Julia's Float32() does, so the values
# written to NIfTI (float32) equal the producer's.
as.single_like <- function(v) {
  con <- rawConnection(raw(0), "wb")
  on.exit(close(con))
  writeBin(as.numeric(v), con, size = 4L)
  readBin(rawConnectionValue(con), "numeric", n = length(v), size = 4L)
}
