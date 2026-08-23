#' Built-in measure identifiers
#'
#' The trusted measures of the Neuropublish protocol (`protocol/SPEC.md`,
#' section 5) as semantic ids in the `org.neuropublish.measure` namespace. Use
#' them as the `measure` of [np_field()]; any other semantic id is accepted by
#' the server but shown by its id and grants no inferential behaviour.
#'
#' @format A named list of character scalars: `effect`, `standard_error`,
#'   `t_statistic`, `z_statistic`, `p_value`, `accuracy`, `correlation`.
#' @examples
#' np_measure$t_statistic
#' @export
np_measure <- list(
  effect = "org.neuropublish.measure/effect",
  standard_error = "org.neuropublish.measure/standard-error",
  t_statistic = "org.neuropublish.measure/t-statistic",
  z_statistic = "org.neuropublish.measure/z-statistic",
  p_value = "org.neuropublish.measure/p-value",
  accuracy = "org.neuropublish.measure/accuracy",
  correlation = "org.neuropublish.measure/correlation"
)

#' The trusted volume-grid domain schema reference
#'
#' The schema reference (`id`, `version`, `digest`) of the
#' `org.neuropublish.domain/volume-grid` descriptor, version 1.0, exactly as the
#' conformance fixtures declare it. [np_domain_volume()] uses it for both the
#' open descriptor and the exact key of a volume domain.
#'
#' @format A list with `id`, `version` and `digest`.
#' @examples
#' np_volume_grid_schema$id
#' @export
np_volume_grid_schema <- list(
  id = "org.neuropublish.domain/volume-grid",
  version = "1.0",
  digest = "sha256:69c25b8868349828e41cd6d610ac619af118fb7b807b7306f706b727ed23dfb7"
)

#' The NIfTI media type used for volume assets
#' @keywords internal
#' @noRd
np_nifti_media_type <- "application/x-nifti"
