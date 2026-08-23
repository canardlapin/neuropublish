#' Write a staging bundle
#'
#' Writes `manifest.json` and copies every asset that names a `path` under
#' `assets/` of `dir`, rewriting `path` relative to the bundle, so `npub pack`
#' can hash the files into a normalized bundle ([np_pack()]). Assets that
#' already carry a `digest` are left alone.
#'
#' The manifest is serialized with
#' `jsonlite::toJSON(auto_unbox = TRUE, digits = I(17), null = "null", pretty = TRUE)`
#' under the protocol's byte profile: UTF-8 without a byte-order mark, one root
#' object, and finite numbers only. Seventeen significant digits make every
#' double round-trip exactly, so the affine a reader parses is bit for bit the
#' affine that [np_volume_grid_fingerprint()] hashed (the server recomputes the
#' fingerprint from the parsed JSON and refuses a disagreement). A `NaN`, `NA`,
#' `Inf` or `-Inf` anywhere in the manifest is refused with an error naming its
#' JSON Pointer, because JSON cannot represent it and the server would reject
#' the bytes.
#'
#' Asset files are staged as `assets/<id>.<ext>` with every character outside
#' `A-Za-z0-9._-` in the id replaced by `_`; two assets whose ids collapse to
#' the same file name (`"a/b"` and `"a_b"`) are refused rather than silently
#' overwriting each other.
#'
#' @param manifest A manifest list from [np_manifest()] (or any list with the
#'   same shape; unknown members are written as given).
#' @param dir The staging directory (created if needed).
#' @param overwrite Replace an existing `manifest.json`?
#' @return `dir`, invisibly, with attribute `digest` (the SHA-256 of the
#'   manifest bytes as written).
#' @examples
#' m <- np_manifest("Example", "A manifest with no assets.")
#' d <- np_write_bundle(m, tempfile("bundle"))
#' list.files(d)
#' @export
np_write_bundle <- function(manifest, dir, overwrite = FALSE) {
  if (!is.list(manifest) || is.null(names(manifest))) {
    stop("`manifest` must be a named list", call. = FALSE)
  }
  if (!dir.exists(dir)) dir.create(dir, recursive = TRUE)
  manifest_path <- file.path(dir, "manifest.json")
  if (file.exists(manifest_path) && !overwrite) {
    stop(manifest_path, " already exists; pass overwrite = TRUE", call. = FALSE)
  }
  bytes <- np_manifest_bytes(manifest) # refuses non-finite numbers before any file is touched
  assets <- manifest$assets
  if (length(assets)) {
    np_check_asset_names(assets)
    if (!dir.exists(file.path(dir, "assets"))) dir.create(file.path(dir, "assets"))
    manifest$assets <- lapply(assets, function(a) np_stage_asset(a, dir))
    bytes <- np_manifest_bytes(manifest)
  }
  writeBin(bytes, manifest_path)
  invisible(structure(dir, digest = np_digest(manifest_path)))
}

# The bundle-relative file an asset with a `path` is staged at, or NULL.
np_staged_name <- function(a) {
  if (is.null(a$path)) {
    return(NULL)
  }
  src <- a$path
  ext <- if (grepl("\\.nii\\.gz$", src)) ".nii.gz" else paste0(".", tools::file_ext(src))
  if (ext == ".") ext <- ""
  file.path("assets", paste0(np_safe_file_name(a$id), ext))
}

np_check_asset_names <- function(assets) {
  ids <- vapply(assets, function(a) if (is.null(a$id)) NA_character_ else a$id, character(1))
  names <- vapply(assets, function(a) {
    n <- np_staged_name(a)
    if (is.null(n)) NA_character_ else n
  }, character(1))
  dup <- !is.na(names) & duplicated(names)
  if (any(dup)) {
    n <- names[which(dup)[1]]
    clash <- ids[!is.na(names) & names == n]
    stop(
      "assets ", paste0("'", clash, "'", collapse = " and "),
      " would both be staged as ", n, "; give them ids that differ in [A-Za-z0-9._-]",
      call. = FALSE
    )
  }
  invisible(TRUE)
}

np_stage_asset <- function(a, dir) {
  rel <- np_staged_name(a)
  if (is.null(rel)) {
    return(a)
  }
  src <- a$path
  if (!file.exists(src)) stop("asset '", a$id, "': ", src, " does not exist", call. = FALSE)
  dest <- file.path(dir, rel)
  same <- file.exists(dest) &&
    normalizePath(dest, winslash = "/") == normalizePath(src, winslash = "/")
  if (!same && !file.copy(src, dest, overwrite = TRUE)) {
    stop("asset '", a$id, "': could not copy ", src, " to ", dest, call. = FALSE)
  }
  a$path <- rel
  a
}

#' Serialize a manifest under the byte profile
#'
#' @param manifest A manifest list.
#' @return A raw vector: the UTF-8 JSON text with a trailing newline.
#' @keywords internal
#' @noRd
np_manifest_bytes <- function(manifest) {
  np_check_finite(manifest, "")
  text <- jsonlite::toJSON(manifest, auto_unbox = TRUE, digits = I(17), null = "null", pretty = TRUE)
  text <- enc2utf8(as.character(text))
  if (!validUTF8(text)) stop("manifest text is not valid UTF-8", call. = FALSE)
  c(charToRaw(text), charToRaw("\n"))
}

np_check_finite <- function(x, pointer) {
  if (is.numeric(x) || is.complex(x)) {
    bad <- which(!is.finite(x))
    if (length(bad)) {
      at <- if (length(x) > 1L) paste0(pointer, "/", bad[1] - 1L) else pointer
      value <- x[bad[1]]
      shown <- if (is.na(value) && !is.nan(value)) "NA" else format(value)
      stop(
        "manifest contains a non-finite number (", shown, ") at `", at,
        "`; JSON has no representation for it", call. = FALSE
      )
    }
  } else if (is.logical(x) || is.character(x)) {
    if (anyNA(x)) {
      bad <- which(is.na(x))
      at <- if (length(x) > 1L) paste0(pointer, "/", bad[1] - 1L) else pointer
      stop("manifest contains NA at `", at, "`", call. = FALSE)
    }
  } else if (is.list(x)) {
    nms <- names(x)
    for (i in seq_along(x)) {
      token <- if (!is.null(nms) && nzchar(nms[i])) np_pointer_escape(nms[i]) else as.character(i - 1L)
      np_check_finite(x[[i]], paste0(pointer, "/", token))
    }
  }
  invisible(TRUE)
}

np_pointer_escape <- function(s) gsub("/", "~1", gsub("~", "~0", s, fixed = TRUE), fixed = TRUE)

#' Read a bundle's manifest as a value
#'
#' Decodes `manifest.json` with `jsonlite::fromJSON(simplifyVector = FALSE)`,
#' the value-preserving decoding that keeps unknown fields and never turns a
#' one-element array into a scalar-shaped data frame.
#'
#' @param dir A bundle directory (staging or packed).
#' @return The manifest as a nested list, with attribute `digest`.
#' @examples
#' m <- np_manifest("Example", "A manifest with no assets.")
#' d <- np_write_bundle(m, tempfile("bundle"))
#' np_read_manifest(d)$title
#' @export
np_read_manifest <- function(dir) {
  path <- file.path(dir, "manifest.json")
  if (!file.exists(path)) stop(path, " does not exist", call. = FALSE)
  structure(
    jsonlite::fromJSON(path, simplifyVector = FALSE),
    digest = np_digest(path)
  )
}
