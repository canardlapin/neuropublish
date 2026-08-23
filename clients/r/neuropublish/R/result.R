#' Describe an R object as a Neuropublish result
#'
#' The generic the domain packages implement. A method receives whatever the
#' package considers a publishable result (an `fmrigds` group reduction, an
#' `rMVPA` searchlight, a `neuromosaic` report, ...) and returns a
#' `neuropublish_result` built with [np_result()]:
#'
#' * `manifest`: a manifest list from [np_manifest()] whose `assets` entries
#'   name their bytes by `path` (see [np_asset_volume()]); everything else is
#'   the core 0.1 vocabulary plus whatever extension fields the package adds;
#' * `assets`: the same asset entries, as a list, so callers can see what will
#'   be uploaded without walking the manifest.
#'
#' The contract is: the manifest must be admitted by `npub validate` after
#' [np_write_bundle()] and [np_pack()]; every `resultFields[].estimand` names
#' an estimand of an `analyses[]` entry; every field and underlay names a
#' declared domain and asset; the method and the provenance activities are
#' open records ([np_record()]) from the package's own namespace. Nothing in
#' the result may be a NaN or infinite number. [np_publish()] does the rest.
#'
#' @section The `list` method:
#'
#' `as_neuropublish.list` is the reference implementation, and it is *not* a
#' fallback for arbitrary lists: it describes one specific input, a **named
#' list of `neuroim2::NeuroVol` objects that all live on the same grid** (same
#' dimensions and same `neuroim2::trans()`). Each name is used as an asset id,
#' as the id of the result field built from it, and as the key into `measures`
#' (and `display`); a volume named by `underlay` becomes the anatomical
#' underlay instead of a field, and every other volume needs an entry in
#' `measures`. From that it builds one volume domain from the first volume's
#' space, one asset per volume, one analysis with one estimand, and one result
#' field per volume with the measure you named for it.
#'
#' A list holding anything else is an error naming the class that was found;
#' that is not a defect to work around but the signal that the object needs its
#' own method. Give the object a class and write `as_neuropublish.<class>()`
#' for it: whatever it holds (a fitted model, a searchlight result, a report),
#' the method's job is to return the `np_result()` described above, and it may
#' call this method for the volume part of the work.
#'
#' @param x The object to describe.
#' @param ... Passed to methods.
#' @return A `neuropublish_result`.
#' @examples
#' sp <- neuroim2::NeuroSpace(c(8L, 8L, 6L), spacing = c(2, 2, 2), origin = c(-7, -7, -5))
#' vols <- list(
#'   "speech-t" = neuroim2::NeuroVol(array(rnorm(384), c(8, 8, 6)), sp)
#' )
#' r <- as_neuropublish(vols,
#'   title = "Example", synopsis = "One t-map.",
#'   measures = c("speech-t" = np_measure$t_statistic), staging = tempdir()
#' )
#' r$manifest$resultFields[[1]]$measure
#' @export
as_neuropublish <- function(x, ...) UseMethod("as_neuropublish")

#' @export
as_neuropublish.neuropublish_result <- function(x, ...) x

#' @export
as_neuropublish.default <- function(x, ...) {
  stop(
    "no as_neuropublish() method for class ", paste(class(x), collapse = "/"),
    "; a domain package must implement one (see ?as_neuropublish)",
    call. = FALSE
  )
}

#' @rdname as_neuropublish
#' @param title,synopsis,sensitivity Passed to [np_manifest()].
#' @param measures A named character vector mapping each volume's name to its
#'   measure id (see [np_measure]); a volume without a measure is an underlay
#'   when `underlay` names it and an error otherwise.
#' @param estimand,estimand_label The single estimand every field reports.
#' @param analysis_id,analysis_label The single analysis.
#' @param method An open record describing the method ([np_record()]), or
#'   NULL.
#' @param sample_size Optional number of inputs.
#' @param underlay Optional name of the volume in `x` to declare as the
#'   anatomical underlay (with `underlay_label`).
#' @param underlay_label Label of the underlay.
#' @param domain_id,space_name Passed to [np_domain_volume()].
#' @param display Optional named list of [np_display()] recommendations keyed
#'   by volume name.
#' @param staging Where NIfTI files are written; see [np_asset_volume()].
#' @export
as_neuropublish.list <- function(x, title, synopsis, measures, sensitivity = "group-level",
                                 estimand = "effect", estimand_label = "effect",
                                 analysis_id = "analysis", analysis_label = "Analysis",
                                 method = NULL, sample_size = NULL,
                                 underlay = NULL, underlay_label = "Underlay",
                                 domain_id = "volume", space_name = "MNI152NLin2009cAsym",
                                 display = list(), staging = np_staging_dir(), ...) {
  if (!length(x) || is.null(names(x)) || any(!nzchar(names(x)))) {
    stop("`x` must be a non-empty named list of NeuroVol objects", call. = FALSE)
  }
  bad <- which(!vapply(x, methods::is, logical(1), "NeuroVol"))
  if (length(bad)) {
    i <- bad[1]
    stop(
      "as_neuropublish() for a plain list describes a named list of neuroim2 NeuroVol objects, ",
      "but `x$", names(x)[i], "` is a ", paste(class(x[[i]]), collapse = "/"), ". ",
      "Give the object a class and write an as_neuropublish() method for it (see ?as_neuropublish).",
      call. = FALSE
    )
  }
  if (!is.character(measures) || is.null(names(measures))) {
    stop("`measures` must be a named character vector", call. = FALSE)
  }
  fields <- setdiff(names(x), underlay)
  missing <- setdiff(fields, names(measures))
  if (length(missing)) {
    stop("no measure for volume(s): ", paste(missing, collapse = ", "), call. = FALSE)
  }
  space <- neuroim2::space(x[[1]])
  for (nm in names(x)) {
    if (!isTRUE(all.equal(as.numeric(neuroim2::trans(neuroim2::space(x[[nm]]))), as.numeric(neuroim2::trans(space)))) ||
      !identical(as.integer(dim(x[[nm]])), as.integer(dim(space)))) {
      stop("volume '", nm, "' is not on the same grid as '", names(x)[1], "'", call. = FALSE)
    }
  }
  m <- np_manifest(title, synopsis, sensitivity = sensitivity)
  m <- np_add(m, "domains", np_domain_volume(space, id = domain_id, space_name = space_name))
  assets <- lapply(names(x), function(nm) np_asset_volume(nm, x[[nm]], staging = staging))
  for (a in assets) m <- np_add(m, "assets", a)
  m <- np_add(m, "analyses", np_analysis(
    analysis_id, analysis_label,
    method = method, sample_size = sample_size,
    estimands = list(np_estimand(estimand, estimand_label, order = 1))
  ))
  for (i in seq_along(fields)) {
    nm <- fields[i]
    m <- np_add(m, "resultFields", np_field(
      nm, estimand, unname(measures[[nm]]), domain_id,
      selection = list(level = "group"),
      representations = list(np_volume_rep(nm)),
      order = i,
      published_display = display[[nm]]
    ))
  }
  if (!is.null(underlay)) {
    if (!underlay %in% names(x)) stop("`underlay` must name a volume in `x`", call. = FALSE)
    m <- np_add(m, "underlays", np_underlay(underlay, domain_id, underlay_label))
  }
  np_result(m)
}

#' Construct a `neuropublish_result`
#'
#' @param manifest A manifest list whose `assets` carry `path`s.
#' @param assets The asset entries; defaults to `manifest$assets`.
#' @return A list of class `neuropublish_result` with `manifest` and `assets`.
#' @examples
#' m <- np_manifest("Example", "No assets yet.")
#' class(np_result(m))
#' @export
np_result <- function(manifest, assets = manifest$assets) {
  if (!is.list(manifest) || !identical(manifest$core, "0.1")) {
    stop("`manifest` must be a core 0.1 manifest list (see np_manifest())", call. = FALSE)
  }
  if (is.null(assets)) assets <- list()
  manifest$assets <- assets
  structure(list(manifest = manifest, assets = assets), class = "neuropublish_result")
}

#' @export
print.neuropublish_result <- function(x, ...) {
  m <- x$manifest
  cat("<neuropublish_result> ", m$title, "\n", sep = "")
  cat("  ", length(m$domains), " domain(s), ", length(x$assets), " asset(s), ",
    length(m$analyses), " analysis/analyses, ", length(m$resultFields), " result field(s)\n",
    sep = ""
  )
  invisible(x)
}

#' Publish an R object as one revision
#'
#' `as_neuropublish()` the object, write the staging bundle, `npub pack` it,
#' and `npub push` the packed bundle: the one high-level call after
#' [np_login()].
#'
#' @param x An object with an [as_neuropublish()] method, or a
#'   `neuropublish_result`.
#' @param project `"workspace/project"`.
#' @param server The control-plane URL.
#' @param message Publication message.
#' @param parent Parent revision id, or NULL for the first revision.
#' @param dir Working directory for the staging and packed bundles (default: a
#'   fresh temporary directory). The packed bundle is `<dir>/bundle.npub`.
#' @param ... Passed to [as_neuropublish()].
#' @return The [np_push()] result, with `bundle` (the packed directory) added.
#' @examples
#' \dontrun{
#' np_publish(vols, "rotman/sherlock", server = "http://127.0.0.1:8080",
#'   title = "Example", synopsis = "...", measures = c("speech-t" = np_measure$t_statistic)
#' )
#' }
#' @export
np_publish <- function(x, project, server, message = NULL, parent = NULL,
                       dir = tempfile("neuropublish-"), ...) {
  result <- as_neuropublish(x, ...)
  if (!inherits(result, "neuropublish_result")) {
    stop("as_neuropublish() must return a neuropublish_result", call. = FALSE)
  }
  staging <- file.path(dir, "staging")
  np_write_bundle(result$manifest, staging)
  packed <- np_pack(staging, file.path(dir, "bundle.npub"), force = TRUE)
  pushed <- np_push(packed$dir, project, server, message = message, parent = parent)
  pushed$bundle <- packed$dir
  pushed
}
