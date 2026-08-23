#' Structural fingerprint of a volume grid (ADR 0005)
#'
#' Computes the `structuralFingerprint` of a `volume-grid/v1` domain key from
#' the documented preimage: the eight bytes `NPUDOM1\0`, six length-prefixed
#' UTF-8 strings (descriptor id, descriptor version, space, coordinate
#' convention, spatial unit, ordinal layout), the three shape entries as
#' little-endian Int32, and the sixteen affine entries in row-major order as
#' little-endian Float64 with negative zero normalised to zero; the fingerprint
#' is `sha256:` followed by the hex SHA-256 of those bytes. This is the same
#' computation the Julia producer (`modules/conformance/julia/producer.jl`) and
#' the Scala server perform, so the three agree byte for byte.
#'
#' @param shape Integer vector of length 3: the grid dimensions.
#' @param affine A 4 x 4 numeric matrix mapping voxel indices to world
#'   coordinates (as `neuroim2::trans()` returns for a `NeuroSpace`).
#' @param space Coordinate space name (e.g. `"MNI152NLin2009cAsym"`).
#' @param convention Coordinate convention; the protocol defines `"RAS+"`.
#' @param unit Spatial unit; `"mm"`.
#' @param layout Ordinal layout of voxels in the volume; `"x-fastest"`.
#' @param descriptor The descriptor schema reference; defaults to
#'   [np_volume_grid_schema].
#' @return A character scalar `"sha256:<64 hex digits>"`.
#' @examples
#' affine <- diag(c(2, 2, 2, 1))
#' affine[1:3, 4] <- c(-22, -30, -18)
#' np_volume_grid_fingerprint(c(24L, 28L, 20L), affine, "MNI152NLin2009cAsym")
#' @export
np_volume_grid_fingerprint <- function(shape, affine, space, convention = "RAS+",
                                       unit = "mm", layout = "x-fastest",
                                       descriptor = np_volume_grid_schema) {
  shape <- np_check_shape(shape)
  affine <- as.matrix(affine)
  if (!identical(dim(affine), c(4L, 4L)) || !is.numeric(affine)) {
    stop("`affine` must be a 4 x 4 numeric matrix", call. = FALSE)
  }
  con <- rawConnection(raw(0), "wb")
  on.exit(close(con), add = TRUE)
  writeBin(charToRaw("NPUDOM1"), con)
  writeBin(as.raw(0L), con)
  for (s in c(descriptor$id, descriptor$version, space, convention, unit, layout)) {
    if (!is.character(s) || length(s) != 1L || is.na(s)) {
      stop("every fingerprint string must be a single non-missing string", call. = FALSE)
    }
    b <- charToRaw(enc2utf8(s))
    writeBin(length(b), con, size = 4L, endian = "little")
    writeBin(b, con)
  }
  writeBin(shape, con, size = 4L, endian = "little")
  values <- as.numeric(t(affine)) # row-major
  if (!all(is.finite(values))) {
    stop("`affine` must be finite", call. = FALSE)
  }
  values[values == 0] <- 0 # no negative zero
  writeBin(values, con, size = 8L, endian = "little")
  paste0("sha256:", digest::digest(rawConnectionValue(con), algo = "sha256", serialize = FALSE))
}

np_check_shape <- function(shape) {
  if (!is.numeric(shape) || length(shape) != 3L || anyNA(shape) ||
    any(shape != round(shape)) || any(shape < 1) || any(shape > .Machine$integer.max)) {
    stop("`shape` must be three positive integers (each at most 2^31 - 1)", call. = FALSE)
  }
  as.integer(shape)
}

#' Describe a volume domain from a `NeuroSpace`
#'
#' Builds the `domains[]` record for a regular 3-D grid: the open descriptor
#' (`{schema, payload}` with the grid's shape, affine, space, convention, unit
#' and layout) and the exact `key` (`descriptor`, `size`, and the ADR 0005
#' `structuralFingerprint` computed by [np_volume_grid_fingerprint()]).
#'
#' @param space A `neuroim2::NeuroSpace` (or a `NeuroVol`, whose space is
#'   used), three-dimensional.
#' @param id The domain id referenced by result fields and underlays.
#' @param space_name The coordinate space name recorded in the payload.
#' @param convention,unit,layout Payload values; the defaults are the only ones
#'   the protocol currently defines for a `volume-grid` domain.
#' @return A plain list that serializes to one entry of `domains`. `key$size`
#'   is the voxel count as a double (it may exceed R's integer range; it is
#'   written as an integer literal).
#' @examples
#' sp <- neuroim2::NeuroSpace(c(24L, 28L, 20L), spacing = c(2, 2, 2), origin = c(-22, -30, -18))
#' d <- np_domain_volume(sp, id = "mni-2mm")
#' d$key$structuralFingerprint
#' @export
np_domain_volume <- function(space, id = "volume", space_name = "MNI152NLin2009cAsym",
                             convention = "RAS+", unit = "mm", layout = "x-fastest") {
  if (methods::is(space, "NeuroVol")) space <- neuroim2::space(space)
  if (!methods::is(space, "NeuroSpace")) {
    stop("`space` must be a neuroim2 NeuroSpace or NeuroVol", call. = FALSE)
  }
  shape <- dim(space)
  if (length(shape) != 3L) {
    stop("a volume-grid domain needs a three-dimensional space, got ", length(shape), " dimensions",
      call. = FALSE
    )
  }
  shape <- np_check_shape(shape)
  affine <- unname(as.matrix(neuroim2::trans(space)))
  fingerprint <- np_volume_grid_fingerprint(
    shape, affine, space_name, convention, unit, layout, np_volume_grid_schema
  )
  list(
    id = np_check_id(id),
    key = list(
      descriptor = np_volume_grid_schema,
      size = prod(as.numeric(shape)),
      structuralFingerprint = fingerprint
    ),
    descriptor = list(
      schema = np_volume_grid_schema,
      payload = list(
        space = space_name,
        coordinateConvention = convention,
        spatialUnit = unit,
        ordinalLayout = layout,
        shape = shape,
        affine = lapply(seq_len(4), function(r) as.numeric(affine[r, ]))
      )
    )
  )
}
