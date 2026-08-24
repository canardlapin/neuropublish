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

np_check_element_keys <- function(element_keys, what = "element_keys") {
  if (!is.character(element_keys) || !length(element_keys) || anyNA(element_keys) ||
    any(!nzchar(element_keys))) {
    stop("`", what, "` must be a non-empty character vector of non-empty keys", call. = FALSE)
  }
  duplicate <- anyDuplicated(element_keys)
  if (duplicate) {
    stop("`", what, "` contains duplicate key '", element_keys[[duplicate]], "'", call. = FALSE)
  }
  enc2utf8(element_keys)
}

#' Exact finite-indexed identity preimage
#'
#' Encodes the language-neutral `finite-indexed/v1` identity bytes: the eight
#' bytes `NPUDOM1\0`, length-prefixed UTF-8 descriptor id and version, the
#' element count as UInt64 little-endian, and every stable element key in exact
#' domain order as length-prefixed UTF-8. Write the returned raw vector as the
#' domain's keys asset; its SHA-256 is the structural fingerprint.
#'
#' @param element_keys Non-empty unique stable keys in authoritative order.
#' @param descriptor Trusted descriptor reference; defaults to
#'   [np_finite_indexed_schema].
#' @return A raw vector containing the exact identity preimage.
#' @examples
#' bytes <- np_finite_indexed_preimage(c("parcel-a", "parcel-b"))
#' length(bytes)
#' @export
np_finite_indexed_preimage <- function(element_keys,
                                       descriptor = np_finite_indexed_schema) {
  element_keys <- np_check_element_keys(element_keys)
  con <- rawConnection(raw(0), "wb")
  on.exit(close(con), add = TRUE)
  writeBin(charToRaw("NPUDOM1"), con)
  writeBin(as.raw(0L), con)
  for (s in c(descriptor$id, descriptor$version)) {
    s <- np_check_string(s, "descriptor string")
    b <- charToRaw(enc2utf8(s))
    writeBin(length(b), con, size = 4L, endian = "little")
    writeBin(b, con)
  }
  count <- length(element_keys)
  count_bytes <- as.raw(floor(count / 256^(0:7)) %% 256)
  writeBin(count_bytes, con)
  for (key in element_keys) {
    b <- charToRaw(key)
    writeBin(length(b), con, size = 4L, endian = "little")
    writeBin(b, con)
  }
  rawConnectionValue(con)
}

#' Structural fingerprint of a finite-indexed domain
#'
#' @inheritParams np_finite_indexed_preimage
#' @return A character scalar `"sha256:<64 hex digits>"`.
#' @examples
#' np_finite_indexed_fingerprint(c("parcel-a", "parcel-b"))
#' @export
np_finite_indexed_fingerprint <- function(element_keys,
                                          descriptor = np_finite_indexed_schema) {
  bytes <- np_finite_indexed_preimage(element_keys, descriptor)
  paste0("sha256:", digest::digest(bytes, algo = "sha256", serialize = FALSE))
}

#' Describe an exact finite-indexed domain
#'
#' Builds one `domains[]` record whose identity is the exact ordered key vector.
#' The caller must also write [np_finite_indexed_preimage()] as `keys_asset`
#' and declare it with media type
#' `application/vnd.neuropublish.finite-indexed-keys-v1`.
#'
#' @param element_keys Non-empty unique stable keys in authoritative order.
#' @param id Domain id referenced by result fields and mappings.
#' @param keys_asset Asset id containing the exact identity preimage.
#' @return A plain `domains[]` record.
#' @examples
#' np_domain_finite(c("parcel-a", "parcel-b"), "parcels", "parcel-keys")
#' @export
np_domain_finite <- function(element_keys, id = "finite", keys_asset = "finite-keys") {
  element_keys <- np_check_element_keys(element_keys)
  list(
    id = np_check_id(id),
    key = list(
      descriptor = np_finite_indexed_schema,
      size = length(element_keys),
      structuralFingerprint = np_finite_indexed_fingerprint(element_keys)
    ),
    descriptor = list(
      schema = np_finite_indexed_schema,
      payload = list(
        ordering = "explicit",
        elementKeys = np_array(element_keys),
        keysAsset = np_check_id(keys_asset, "keys_asset")
      )
    )
  )
}

#' Encode hard-assignment ordinals
#'
#' Encodes one signed Int32 little-endian target ordinal per source element.
#' `-1` is background; all other values must index the finite target.
#'
#' @param ordinals Integer-like vector in `-1 .. target_size - 1`.
#' @param target_size Positive target-domain size.
#' @return A raw vector suitable for a
#'   `application/vnd.neuropublish.hard-assignment-i32le-v1` asset.
#' @examples
#' np_hard_assignment_bytes(c(0, 0, 1, -1), 2)
#' @export
np_hard_assignment_bytes <- function(ordinals, target_size) {
  if (!is.numeric(target_size) || length(target_size) != 1L || is.na(target_size) ||
    target_size != round(target_size) || target_size < 1 || target_size > .Machine$integer.max) {
    stop("`target_size` must be one positive Int32-sized integer", call. = FALSE)
  }
  if (!is.numeric(ordinals) || !length(ordinals) || anyNA(ordinals) || any(!is.finite(ordinals)) ||
    any(ordinals != round(ordinals)) || any(ordinals < -1) || any(ordinals >= target_size)) {
    stop("`ordinals` must be non-empty integers from -1 through target_size - 1", call. = FALSE)
  }
  con <- rawConnection(raw(0), "wb")
  on.exit(close(con), add = TRUE)
  writeBin(as.integer(ordinals), con, size = 4L, endian = "little")
  rawConnectionValue(con)
}

#' Describe a trusted hard assignment between exact domains
#'
#' Builds one `domainMappings[]` entry. The assignment asset must contain
#' [np_hard_assignment_bytes()] and the derivation must name a provenance
#' activity that constructed those zero-based ordinals.
#'
#' @param id Mapping id.
#' @param source Exact volume-grid or surface-vertices domain id.
#' @param target Exact finite-indexed domain id.
#' @param asset Hard-assignment asset id.
#' @param derivation Provenance activity id that constructed the assignment.
#' @param coverage `"complete"` or `"allow-empty"`.
#' @param empty_parcels Exact empty target keys in target order. Must be empty
#'   for complete coverage and non-empty for allow-empty.
#' @param target_keys Optional complete target key vector used to check subset
#'   and order before publication.
#' @return A plain `domainMappings[]` record.
#' @examples
#' np_domain_mapping_hard("atlas", "grid", "parcels", "assignment", "build-atlas")
#' @export
np_domain_mapping_hard <- function(id, source, target, asset, derivation,
                                   coverage = c("complete", "allow-empty"),
                                   empty_parcels = character(), target_keys = NULL) {
  coverage <- match.arg(coverage)
  if (!is.character(empty_parcels) || anyNA(empty_parcels) ||
    any(!nzchar(empty_parcels)) || anyDuplicated(empty_parcels)) {
    stop("`empty_parcels` must be unique non-empty target keys", call. = FALSE)
  }
  if (coverage == "complete" && length(empty_parcels)) {
    stop("complete coverage requires no `empty_parcels`", call. = FALSE)
  }
  if (coverage == "allow-empty" && !length(empty_parcels)) {
    stop("allow-empty coverage requires at least one empty parcel", call. = FALSE)
  }
  if (!is.null(target_keys)) {
    target_keys <- np_check_element_keys(target_keys, "target_keys")
    expected <- target_keys[target_keys %in% empty_parcels]
    if (!identical(expected, empty_parcels)) {
      stop("`empty_parcels` must be target keys in exact target-domain order", call. = FALSE)
    }
  }
  list(
    id = np_check_id(id),
    source = np_check_id(source, "source"),
    target = np_check_id(target, "target"),
    descriptor = list(
      schema = np_hard_assignment_schema,
      payload = list(
        asset = np_check_id(asset, "asset"),
        coverage = coverage,
        emptyParcels = np_array(empty_parcels),
        derivation = np_check_id(derivation, "derivation")
      )
    )
  )
}
