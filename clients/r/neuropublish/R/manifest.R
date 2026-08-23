# Builders for the core 0.1 manifest vocabulary. Every builder returns a plain
# list (no classes, no attributes) whose `jsonlite::toJSON(auto_unbox = TRUE)`
# serialization is exactly one record of `protocol/schemas/manifest.schema.json`.
# Optional members left NULL are dropped, so they are absent from the JSON
# rather than `null`.

np_check_id <- function(id, what = "id") {
  if (!is.character(id) || length(id) != 1L || is.na(id) || !nzchar(id)) {
    stop("`", what, "` must be a single non-empty string", call. = FALSE)
  }
  id
}

np_check_string <- function(x, what) {
  if (!is.character(x) || length(x) != 1L || is.na(x)) {
    stop("`", what, "` must be a single string", call. = FALSE)
  }
  x
}

np_array <- function(x) {
  # A list of records is already a JSON array. An atomic vector would be
  # unboxed to a scalar when it has one element, so wrap it with I().
  if (is.list(x)) x else I(x)
}

np_compact <- function(x) x[!vapply(x, is.null, logical(1))]

#' Start a manifest
#'
#' Creates the top-level manifest list with `core = "0.1"`, the title, synopsis
#' and sensitivity, and empty collections to fill with [np_domain_volume()],
#' [np_asset_volume()], [np_analysis()], [np_field()], [np_underlay()],
#' [np_warning()] and [np_provenance()]. Anything passed through `...` is added
#' verbatim (an extension field such as `` `x-lab-run` ``), and you may also
#' add members to the returned list directly: unknown fields survive
#' [np_write_bundle()], `npub pack`, and the server.
#'
#' @param title The publication title.
#' @param synopsis One or two sentences for the revision list.
#' @param sensitivity `"group-level"` or `"subject-level"`. Only group-level
#'   revisions can be link-shared.
#' @param axes A list of axis records, each `list(id, label, values)`; the
#'   default declares a single `level` axis with the one value `"group"`, which
#'   is what the selection of every field refers to.
#' @param notes Optional free-text notes.
#' @param ... Further members added to the manifest as given.
#' @return A plain list.
#' @examples
#' m <- np_manifest("Speech model", "Group t-map of the speech regressor.")
#' names(m)
#' @export
np_manifest <- function(title, synopsis, sensitivity = "group-level",
                        axes = list(np_axis("level", "Level", "group")),
                        notes = NULL, ...) {
  sensitivity <- match.arg(sensitivity, c("group-level", "subject-level"))
  extra <- list(...)
  if (length(extra) && (is.null(names(extra)) || any(!nzchar(names(extra))))) {
    stop("every extra manifest member must be named", call. = FALSE)
  }
  m <- np_compact(list(
    core = "0.1",
    title = np_check_string(title, "title"),
    synopsis = np_check_string(synopsis, "synopsis"),
    notes = notes,
    sensitivity = sensitivity,
    warnings = list(),
    axes = axes,
    domains = list(),
    assets = list(),
    analyses = list(),
    resultFields = list(),
    underlays = list()
  ))
  c(m, extra)
}

#' Axis record
#'
#' @param id Axis id.
#' @param label Human-readable label.
#' @param values The axis values (character); one value is still an array.
#' @return A plain list serializing to one entry of `axes`.
#' @examples
#' np_axis("level", "Level", "group")
#' @export
np_axis <- function(id, label, values) {
  list(id = np_check_id(id), label = np_check_string(label, "label"), values = np_array(as.character(values)))
}

#' Open record: a schema reference with a payload
#'
#' An open record is how the protocol carries structured content it does not
#' define itself: the `method` of an analysis and provenance activities are
#' open records. The `schema` names the document that defines the payload; a
#' digest is checked only for schemas in the trusted namespace, so a record
#' from your own namespace is retained as-is (the viewer shows it, but grants
#' it no behaviour).
#'
#' @param schema_id Semantic id of the schema, `namespace/name`.
#' @param version Version string `major.minor`.
#' @param payload A named list (may be empty).
#' @param digest Optional `sha256:` digest of the schema document.
#' @return A plain list `list(schema = list(id, version, digest), payload)`.
#' @examples
#' np_record("org.example.lab/smooth", "0.3", list(fwhmMm = 6))
#' @export
np_record <- function(schema_id, version, payload = list(), digest = NULL) {
  if (!is.list(payload)) stop("`payload` must be a list", call. = FALSE)
  if (length(payload) && (is.null(names(payload)) || any(!nzchar(names(payload))))) {
    stop("`payload` must be a named list", call. = FALSE)
  }
  list(
    schema = np_compact(list(
      id = np_check_id(schema_id, "schema_id"),
      version = np_check_string(version, "version"),
      digest = digest
    )),
    payload = payload
  )
}

#' Estimand record
#'
#' @param id Estimand id, unique within its analysis.
#' @param label Human-readable label.
#' @param order Optional display order (integer); readers must not reorder.
#' @return A plain list.
#' @examples
#' np_estimand("speech", "speech coefficient", order = 1)
#' @export
np_estimand <- function(id, label, order = NULL) {
  np_compact(list(id = np_check_id(id), label = np_check_string(label, "label"), order = np_int(order, "order")))
}

np_int <- function(x, what) {
  if (is.null(x)) {
    return(NULL)
  }
  if (!is.numeric(x) || length(x) != 1L || is.na(x) || x != round(x)) {
    stop("`", what, "` must be a single integer", call. = FALSE)
  }
  as.integer(x)
}

#' Analysis record
#'
#' @param id Analysis id.
#' @param label Human-readable label.
#' @param method An open record from [np_record()] describing the method, or
#'   NULL.
#' @param sample_size Optional number of inputs (non-negative integer).
#' @param estimands A list of [np_estimand()] records (at least one).
#' @return A plain list serializing to one entry of `analyses`.
#' @examples
#' np_analysis("group-model", "Group model",
#'   method = np_record("org.example.lab/reducer/mean", "0.1", list(weights = "equal")),
#'   sample_size = 12,
#'   estimands = list(np_estimand("speech", "speech coefficient", 1))
#' )
#' @export
np_analysis <- function(id, label, method = NULL, sample_size = NULL, estimands) {
  if (!is.list(estimands) || !length(estimands)) {
    stop("`estimands` must be a non-empty list of np_estimand() records", call. = FALSE)
  }
  np_compact(list(
    id = np_check_id(id),
    label = np_check_string(label, "label"),
    method = method,
    sampleSize = np_int(sample_size, "sample_size"),
    estimands = estimands
  ))
}

#' Volume representation of a result field
#'
#' @param asset The id of the asset holding the volume.
#' @return A plain list `list(kind = "volume", asset)`.
#' @examples
#' np_volume_rep("speech-t")
#' @export
np_volume_rep <- function(asset) list(kind = "volume", asset = np_check_id(asset, "asset"))

#' Published display recommendation
#'
#' A display recommendation for a field. It is never an inferential decision:
#' the viewer treats it as the starting threshold and window.
#'
#' @param threshold `list(mode, min)` with mode one of `"two-sided"`,
#'   `"positive"`, `"negative"`, `"off"` and `min >= 0`; see [np_threshold()].
#' @param window `list(min, centre, max)` with `min < max`; see [np_window()].
#' @param colormap Colormap name (a closed URL-safe grammar), e.g. `"cold-hot"`.
#' @param opacity Optional opacity in `[0, 1]`.
#' @return A plain list.
#' @examples
#' np_display(np_threshold("two-sided", 3.1), np_window(-8, 8), "cold-hot")
#' @export
np_display <- function(threshold, window, colormap, opacity = NULL) {
  np_compact(list(
    threshold = threshold, window = window,
    colormap = np_check_string(colormap, "colormap"), opacity = opacity
  ))
}

#' @rdname np_display
#' @param mode Threshold mode.
#' @param min Threshold magnitude (non-negative).
#' @export
np_threshold <- function(mode = c("two-sided", "positive", "negative", "off"), min = 0) {
  mode <- match.arg(mode)
  if (!is.numeric(min) || length(min) != 1L || !is.finite(min) || min < 0) {
    stop("threshold `min` must be a single non-negative finite number", call. = FALSE)
  }
  list(mode = mode, min = as.numeric(min))
}

#' @rdname np_display
#' @param max Window maximum.
#' @param centre Optional window centre.
#' @export
np_window <- function(min, max, centre = NULL) {
  if (!is.numeric(min) || !is.numeric(max) || !is.finite(min) || !is.finite(max) || min >= max) {
    stop("window needs finite `min < max`", call. = FALSE)
  }
  np_compact(list(min = as.numeric(min), centre = if (!is.null(centre)) as.numeric(centre), max = as.numeric(max)))
}

#' Result field record
#'
#' A result field is one measure of one estimand on one domain, with at least
#' one representation (for the volume MVP, a NIfTI asset).
#'
#' @param id Field id.
#' @param estimand The id of an estimand declared by some analysis.
#' @param measure A semantic id; use the constants in [np_measure].
#' @param domain The id of the domain the representations live on.
#' @param selection A named list of axis id to axis value (e.g.
#'   `list(level = "group")`).
#' @param representations A list of representations, e.g.
#'   `list(np_volume_rep("speech-t"))`.
#' @param order Optional display order within the estimand.
#' @param published_display Optional [np_display()] recommendation.
#' @param label Optional label.
#' @return A plain list serializing to one entry of `resultFields`.
#' @examples
#' np_field("speech-t", "speech", np_measure$t_statistic, "mni-2mm",
#'   representations = list(np_volume_rep("speech-t")), order = 1
#' )
#' @export
np_field <- function(id, estimand, measure, domain, selection = list(level = "group"),
                     representations, order = NULL, published_display = NULL, label = NULL) {
  if (!is.list(representations) || !length(representations)) {
    stop("`representations` must be a non-empty list", call. = FALSE)
  }
  if (!is.list(selection)) stop("`selection` must be a named list", call. = FALSE)
  if (length(selection) && (is.null(names(selection)) || any(!nzchar(names(selection))))) {
    stop("`selection` must be a named list", call. = FALSE)
  }
  np_compact(list(
    id = np_check_id(id),
    label = label,
    estimand = np_check_id(estimand, "estimand"),
    measure = np_check_id(measure, "measure"),
    selection = selection,
    domain = np_check_id(domain, "domain"),
    representations = representations,
    order = np_int(order, "order"),
    publishedDisplay = published_display
  ))
}

#' Underlay record
#'
#' @param asset The id of the anatomical asset.
#' @param domain The domain id it lives on.
#' @param label Human-readable label.
#' @return A plain list serializing to one entry of `underlays`.
#' @examples
#' np_underlay("t1", "mni-2mm", "MNI152NLin2009cAsym T1")
#' @export
np_underlay <- function(asset, domain, label) {
  list(asset = np_check_id(asset, "asset"), domain = np_check_id(domain, "domain"), label = np_check_string(label, "label"))
}

#' Warning record
#'
#' @param id Warning id.
#' @param message The warning text.
#' @param concerns Optional named list naming what the warning is about:
#'   `analysis`, `field`, and/or `provenance` ids.
#' @return A plain list serializing to one entry of `warnings`.
#' @examples
#' np_warning("heterogeneous-noise", "Noise models differ across inputs.",
#'   concerns = list(analysis = "group-model")
#' )
#' @export
np_warning <- function(id, message, concerns = NULL) {
  if (!is.null(concerns)) {
    bad <- setdiff(names(concerns), c("analysis", "field", "provenance"))
    if (!is.list(concerns) || length(bad)) {
      stop("`concerns` may only name `analysis`, `field`, `provenance`", call. = FALSE)
    }
  }
  np_compact(list(id = np_check_id(id), message = np_check_string(message, "message"), concerns = concerns))
}

#' Provenance graph
#'
#' @param entities A list of entity records: `list(id, label, hosted, asset)`;
#'   see [np_entity()].
#' @param activities A list of activity records; see [np_activity()].
#' @param edges A list of edges; see [np_edge()].
#' @return A plain list serializing to `provenance`.
#' @examples
#' smooth <- np_record("org.example.lab/smooth", "0.3", list(fwhmMm = 6))
#' np_provenance(
#'   entities = list(np_entity("raw", "Raw data")),
#'   activities = list(np_activity("smooth", smooth)),
#'   edges = list(np_edge("raw", "smooth"), np_edge("smooth", "speech-t"))
#' )
#' @export
np_provenance <- function(entities = list(), activities = list(), edges = list()) {
  list(entities = entities, activities = activities, edges = edges)
}

#' @rdname np_provenance
#' @param id Record id.
#' @param label Optional label.
#' @param hosted Whether the entity's bytes are part of the bundle.
#' @param asset For a hosted entity, the asset id.
#' @export
np_entity <- function(id, label = NULL, hosted = FALSE, asset = NULL) {
  np_compact(list(id = np_check_id(id), label = label, hosted = isTRUE(hosted), asset = asset))
}

#' @rdname np_provenance
#' @param record An open record from [np_record()]: its `schema` and `payload`
#'   become the activity's.
#' @export
np_activity <- function(id, record, label = NULL) {
  np_compact(list(id = np_check_id(id), label = label, schema = record$schema, payload = record$payload))
}

#' @rdname np_provenance
#' @param from,to Ids of provenance entities, activities, assets, or result
#'   fields.
#' @param role Optional edge role.
#' @export
np_edge <- function(from, to, role = NULL) {
  np_compact(list(from = np_check_id(from, "from"), to = np_check_id(to, "to"), role = role))
}

#' Append a record to a manifest collection
#'
#' Convenience for building a manifest incrementally: appends `record` to
#' `manifest[[collection]]` and returns the manifest. Records are checked for
#' duplicate ids within their collection.
#'
#' @param manifest A manifest from [np_manifest()].
#' @param collection One of `domains`, `assets`, `analyses`, `resultFields`,
#'   `underlays`, `warnings`.
#' @param record A record built with the matching `np_*()` builder.
#' @return The updated manifest.
#' @examples
#' m <- np_manifest("Speech model", "Group t-map.")
#' m <- np_add(m, "warnings", np_warning("w1", "An example warning."))
#' length(m$warnings)
#' @export
np_add <- function(manifest, collection, record) {
  collection <- match.arg(collection, c("domains", "assets", "analyses", "resultFields", "underlays", "warnings"))
  existing <- manifest[[collection]]
  if (is.null(existing)) existing <- list()
  if (!is.null(record$id)) {
    ids <- vapply(existing, function(r) if (is.null(r$id)) NA_character_ else r$id, character(1))
    if (record$id %in% ids) {
      stop("`", collection, "` already has a record with id '", record$id, "'", call. = FALSE)
    }
  }
  manifest[[collection]] <- c(existing, list(record))
  manifest
}
