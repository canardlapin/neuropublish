#' Volume asset from a `NeuroVol` or a NIfTI file
#'
#' Declares one `assets[]` entry for a staging bundle. A `neuroim2::NeuroVol`
#' is written to NIfTI (float32) in `staging` right away; an existing file is
#' referenced as it is. The entry carries `path` (the file) instead of
#' `digest`/`size`, which `npub pack` fills in after hashing the bytes
#' ([np_pack()]); [np_write_bundle()] copies the file under `assets/` of the
#' staging bundle and rewrites `path` relative to it.
#'
#' @param id Asset id, referenced by fields and underlays.
#' @param x A `neuroim2::NeuroVol` or the path of an existing NIfTI file
#'   (`.nii` or `.nii.gz`).
#' @param staging Directory that receives the NIfTI written from a `NeuroVol`
#'   (default: a session-scoped temporary directory).
#' @param catalog Optional catalog reference (informational once a digest is
#'   present, e.g. `"templateflow:MNI152NLin2009cAsym/T1w/res-02"`).
#' @param ... Further members added to the record as given (extension fields).
#' @return A plain list with `id`, `mediaType`, `path` (and `catalog`, `...`).
#' @examples
#' sp <- neuroim2::NeuroSpace(c(4L, 4L, 4L), spacing = c(2, 2, 2), origin = c(-3, -3, -3))
#' vol <- neuroim2::NeuroVol(array(seq_len(64), c(4, 4, 4)), sp)
#' a <- np_asset_volume("tiny", vol, staging = tempdir())
#' a$mediaType
#' file.exists(a$path)
#' @export
np_asset_volume <- function(id, x, staging = np_staging_dir(), catalog = NULL, ...) {
  id <- np_check_id(id)
  if (methods::is(x, "NeuroVol")) {
    if (!dir.exists(staging)) dir.create(staging, recursive = TRUE)
    path <- file.path(staging, paste0(np_safe_file_name(id), ".nii"))
    neuroim2::write_vol(x, path)
  } else if (is.character(x) && length(x) == 1L) {
    path <- x
    if (!file.exists(path) || dir.exists(path)) {
      stop("asset '", id, "': ", path, " is not an existing file", call. = FALSE)
    }
  } else {
    stop("`x` must be a neuroim2 NeuroVol or the path of a NIfTI file", call. = FALSE)
  }
  np_asset_file(id, path, np_nifti_media_type, catalog = catalog, ...)
}

#' Declare an existing file as a staging asset
#'
#' Builds one `assets[]` entry for any protocol media type. The file is not
#' interpreted; [np_write_bundle()] copies it and `npub pack` establishes its
#' content digest and size. Use [np_asset_volume()] when an in-memory
#' `NeuroVol` must first be written as NIfTI.
#'
#' @param id Asset id.
#' @param path Path to an existing regular file.
#' @param media_type IANA or protocol media type.
#' @param catalog Optional informational catalog reference.
#' @param ... Further named members added verbatim.
#' @return A plain staging `assets[]` record.
#' @examples
#' f <- tempfile()
#' writeBin(as.raw(0:3), f)
#' np_asset_file("bytes", f, "application/octet-stream")
#' @export
np_asset_file <- function(id, path, media_type, catalog = NULL, ...) {
  id <- np_check_id(id)
  if (!is.character(path) || length(path) != 1L || is.na(path) ||
    !file.exists(path) || dir.exists(path)) {
    stop("asset '", id, "': `path` must name an existing file", call. = FALSE)
  }
  media_type <- np_check_string(media_type, "media_type")
  extra <- list(...)
  if (length(extra) && (is.null(names(extra)) || any(!nzchar(names(extra))))) {
    stop("every extra asset member must be named", call. = FALSE)
  }
  c(
    np_compact(list(
      id = id,
      mediaType = media_type,
      path = normalizePath(path, winslash = "/", mustWork = TRUE),
      catalog = catalog
    )),
    extra
  )
}

np_safe_file_name <- function(id) gsub("[^A-Za-z0-9._-]+", "_", id)

#' Session staging directory for volumes written from memory
#'
#' @return A directory under `tempdir()`, created on first use.
#' @examples
#' dir.exists(np_staging_dir())
#' @export
np_staging_dir <- function() {
  d <- file.path(tempdir(), "neuropublish-staging")
  if (!dir.exists(d)) dir.create(d, recursive = TRUE)
  d
}

#' SHA-256 digest of a file's bytes
#'
#' The protocol's identity for assets and manifests: `sha256:` followed by the
#' hex SHA-256 of the exact bytes, which is what `shasum -a 256` prints.
#'
#' @param path A file.
#' @return A character scalar `"sha256:<hex>"`.
#' @examples
#' f <- tempfile()
#' writeLines("hello", f)
#' np_digest(f)
#' @export
np_digest <- function(path) {
  if (!file.exists(path)) stop(path, " does not exist", call. = FALSE)
  paste0("sha256:", digest::digest(path, algo = "sha256", file = TRUE))
}
