# The `npub` CLI is the transport: this file only locates it, runs it, and
# parses what it prints. The bundle and the CLI never require R.

#' Locate the `npub` command-line tool
#'
#' Resolution order: the `NPUB_BIN` environment variable, then `npub` on the
#' `PATH`, then `scripts/npub` of a Neuropublish checkout found by walking up
#' from the working directory (the launcher that exports the sbt classpath
#' once and runs `neuropublish.npub.Main`).
#'
#' @param required Stop with an informative error when nothing is found
#'   (default); otherwise return `NULL`.
#' @return The path of the executable, or `NULL`.
#' @examples
#' np_npub_bin(required = FALSE)
#' @export
np_npub_bin <- function(required = TRUE) {
  env <- Sys.getenv("NPUB_BIN", unset = "")
  if (nzchar(env)) {
    if (!file.exists(env)) stop("NPUB_BIN=", env, " does not exist", call. = FALSE)
    return(normalizePath(env, winslash = "/"))
  }
  on_path <- Sys.which("npub")
  if (nzchar(on_path)) {
    return(unname(normalizePath(on_path, winslash = "/")))
  }
  dir <- normalizePath(getwd(), winslash = "/")
  repeat {
    candidate <- file.path(dir, "scripts", "npub")
    if (file.exists(candidate) && file.exists(file.path(dir, "build.sbt"))) {
      return(candidate)
    }
    parent <- dirname(dir)
    if (identical(parent, dir)) break
    dir <- parent
  }
  if (required) {
    stop(
      "cannot find `npub`: set NPUB_BIN, put `npub` on the PATH, or run from a Neuropublish checkout",
      call. = FALSE
    )
  }
  NULL
}

#' Is `npub` available?
#'
#' @return `TRUE` when [np_npub_bin()] resolves to an existing file.
#' @examples
#' np_has_npub()
#' @export
np_has_npub <- function() !is.null(tryCatch(np_npub_bin(required = FALSE), error = function(e) NULL))

#' Run `npub` and capture its output
#'
#' Uses processx when installed (so a `bash` launcher and an installed binary
#' behave alike on every platform); otherwise `system2()`.
#'
#' @param args Character vector of arguments.
#' @param echo Print the CLI's output as it arrives (for interactive commands
#'   such as `login`).
#' @return A list with `status` (exit code), `stdout` and `stderr` (character
#'   vectors of lines).
#' @keywords internal
#' @noRd
np_run <- function(args, echo = FALSE) {
  bin <- np_npub_bin()
  cmd <- bin
  argv <- args
  if (!grepl("\\.(exe|cmd|bat)$", bin) && .Platform$OS.type != "windows" && np_is_script(bin)) {
    cmd <- "bash"
    argv <- c(bin, args)
  }
  if (requireNamespace("processx", quietly = TRUE)) {
    res <- processx::run(cmd, argv, error_on_status = FALSE, echo = echo, echo_cmd = FALSE)
    list(
      status = res$status,
      stdout = np_lines(res$stdout),
      stderr = np_lines(res$stderr)
    )
  } else {
    err <- tempfile("npub-stderr")
    on.exit(unlink(err), add = TRUE)
    out <- suppressWarnings(system2(cmd, shQuote(argv), stdout = TRUE, stderr = err))
    status <- attr(out, "status")
    if (is.null(status)) status <- 0L
    if (echo) cat(out, sep = "\n")
    list(
      status = status,
      stdout = as.character(out),
      stderr = if (file.exists(err)) readLines(err, warn = FALSE) else character()
    )
  }
}

np_is_script <- function(path) {
  con <- file(path, "rb")
  on.exit(close(con))
  identical(readBin(con, "raw", 2L), charToRaw("#!"))
}

np_lines <- function(x) {
  if (is.null(x) || !nzchar(x)) {
    return(character())
  }
  strsplit(x, "\r?\n")[[1]]
}

np_field_of <- function(lines, key) {
  hit <- grep(paste0("^", key, " +"), lines, value = TRUE)
  if (!length(hit)) {
    return(NA_character_)
  }
  sub(paste0("^", key, " +"), "", hit[1])
}

np_cli_error <- function(res, what) {
  err <- c(
    grep("^error", res$stdout, value = TRUE),
    grep("^(SLF4J|WARNING)", res$stderr, value = TRUE, invert = TRUE)
  )
  stop(
    "npub ", what, " failed (exit ", res$status, ")",
    if (length(err)) paste0(":\n", paste(err, collapse = "\n")) else "",
    call. = FALSE
  )
}

#' Validate a bundle with `npub validate`
#'
#' Runs the server's admission on the bundle's `manifest.json` bytes (schema,
#' reference closure, semantic invariants, trusted-record digests) without a
#' server. A staging bundle whose assets still carry `path` is *not* valid
#' (`digest` and `size` are required): pack it first with [np_pack()], or
#' validate the packed output.
#'
#' @param dir A bundle directory.
#' @return A data frame with one row per problem, columns `pointer` (JSON
#'   Pointer into the manifest, `""` for the whole document) and `message`.
#'   Zero rows means the bundle is admitted; the data frame then carries
#'   attributes `digest` (the manifest digest) and `assets` (the CLI's asset
#'   summary line).
#' @examples
#' \dontrun{
#' problems <- np_validate("speech-model.npub")
#' if (nrow(problems)) print(problems)
#' }
#' @export
np_validate <- function(dir) {
  dir <- normalizePath(dir, winslash = "/", mustWork = TRUE)
  res <- np_run(c("validate", dir))
  errors <- grep("^error  ", res$stdout, value = TRUE)
  if (res$status == 0L && !length(errors)) {
    out <- data.frame(pointer = character(), message = character(), stringsAsFactors = FALSE)
    attr(out, "digest") <- np_field_of(res$stdout, "manifest")
    attr(out, "assets") <- np_field_of(res$stdout, "assets")
    return(out)
  }
  if (!length(errors)) np_cli_error(res, "validate")
  body <- sub("^error  ", "", errors)
  # the first line is the summary "<path>: N problem(s)"
  body <- body[!grepl(": [0-9]+ problem\\(s\\)$", body)]
  pointer <- ifelse(grepl("^(/[^ ]*|): ", body), sub("^((/[^ ]*)?): .*$", "\\1", body), "")
  message <- ifelse(grepl("^(/[^ ]*|): ", body), sub("^((/[^ ]*)?): ", "", body), body)
  data.frame(pointer = pointer, message = message, stringsAsFactors = FALSE)
}

#' Pack a staging bundle with `npub pack`
#'
#' Hashes every asset file a staging manifest names by `path`, writes the
#' normalized bundle (`assets/sha256/xx/<hex>`) and a manifest with `digest`
#' and `size` filled in and `path` removed, and admits the written bytes.
#' Nothing else in the manifest is touched, so unknown fields survive.
#'
#' @param staging The staging bundle written by [np_write_bundle()].
#' @param out The packed bundle directory to create (conventionally `.npub`).
#' @param force Replace an existing `out`.
#' @return A list with `dir` (the packed bundle), `digest` (the packed
#'   manifest's digest) and `assets` (a data frame `id`, `size`, `digest`).
#' @examples
#' \dontrun{
#' packed <- np_pack("staging", "speech-model.npub")
#' packed$digest
#' }
#' @export
np_pack <- function(staging, out, force = FALSE) {
  staging <- normalizePath(staging, winslash = "/", mustWork = TRUE)
  args <- c("pack", staging, out, if (isTRUE(force)) "--force")
  res <- np_run(args)
  if (res$status != 0L) np_cli_error(res, "pack")
  rows <- grep("^  \\S+  [0-9]+ B  sha256:", res$stdout, value = TRUE)
  parts <- regmatches(rows, regexec("^  (\\S+)  ([0-9]+) B  (sha256:[0-9a-f]{64})$", rows))
  assets <- data.frame(
    id = vapply(parts, `[`, character(1), 2),
    size = as.numeric(vapply(parts, `[`, character(1), 3)),
    digest = vapply(parts, `[`, character(1), 4),
    stringsAsFactors = FALSE
  )
  list(
    dir = normalizePath(out, winslash = "/", mustWork = TRUE),
    digest = np_field_of(res$stdout, "manifest"),
    assets = assets
  )
}

#' Sign in to a Neuropublish server with `npub login`
#'
#' Runs the device flow: the CLI prints a verification URL and a short code,
#' you approve it in any browser, and the user token is stored by the CLI
#' (`$NPUB_CONFIG_DIR/credentials.json`, keyed by server). Batch jobs should
#' use a project-scoped publisher credential in `NP_TOKEN` instead.
#'
#' @param server The control-plane URL (e.g. `"http://127.0.0.1:8080"`).
#' @return `TRUE` invisibly on success.
#' @examples
#' \dontrun{
#' np_login("http://127.0.0.1:8080")
#' }
#' @export
np_login <- function(server) {
  res <- np_run(c("login", "--server", server), echo = TRUE)
  if (res$status != 0L) np_cli_error(res, "login")
  invisible(TRUE)
}

#' Publish a packed bundle with `npub push`
#'
#' Uploads the assets the server lacks and commits one immutable revision whose
#' parent is the project's current head. Authentication is the CLI's: an
#' explicit `token`, else `NP_TOKEN`, else the credential stored by
#' [np_login()].
#'
#' A push rejected because `parent` is no longer the head raises an error of
#' class `np_stale_parent` whose `head` field is the current head revision id;
#' re-run with `parent = head` (after deciding the new revision still makes
#' sense on top of it). Re-pushing bytes that already are the head is not an
#' error: the result has `unchanged = TRUE`.
#'
#' @param bundle A packed bundle directory (from [np_pack()]).
#' @param project `"workspace/project"`.
#' @param server The control-plane URL.
#' @param message Publication message.
#' @param parent Parent revision id; `NULL` for a project's first revision.
#' @param token Optional bearer (discouraged on the command line; prefer
#'   `NP_TOKEN` or [np_login()]).
#' @return A list with `revision` (id), `digest`, `revision_url`, `view_url`,
#'   `parent`, `unchanged`, and `output` (every line the CLI printed).
#' @examples
#' \dontrun{
#' r <- np_push("speech-model.npub", "rotman/sherlock",
#'   server = "http://127.0.0.1:8080", message = "Group model"
#' )
#' r$view_url
#' }
#' @export
np_push <- function(bundle, project, server, message = NULL, parent = NULL, token = NULL) {
  bundle <- normalizePath(bundle, winslash = "/", mustWork = TRUE)
  if (!grepl("/", project, fixed = TRUE)) {
    stop("`project` must be \"workspace/project\"", call. = FALSE)
  }
  args <- c(
    "push", bundle, "--server", server, "--project", project,
    if (!is.null(parent)) c("--parent", parent),
    if (!is.null(message)) c("--message", message),
    if (!is.null(token)) c("--token", token)
  )
  res <- np_run(args)
  out <- res$stdout
  rejected <- grep("^rejected ", out, value = TRUE)
  if (length(rejected)) {
    head <- sub(".*Re-run with --parent (\\S+).*", "\\1", rejected[1], perl = TRUE)
    if (identical(head, rejected[1])) head <- NA_character_
    stop(structure(
      list(
        message = paste0(
          "push rejected: the parent is no longer the project head; current head is ", head,
          ". Re-run with parent = \"", head, "\"."
        ),
        head = head,
        call = NULL
      ),
      class = c("np_stale_parent", "error", "condition")
    ))
  }
  unchanged <- grep("^unchanged ", out, value = TRUE)
  if (length(unchanged)) {
    id <- sub("^unchanged +already published as +", "", unchanged[1])
    return(list(
      revision = id, digest = NA_character_, revision_url = NA_character_,
      view_url = NA_character_, parent = parent, unchanged = TRUE, output = out
    ))
  }
  if (res$status != 0L) np_cli_error(res, "push")
  committing <- np_field_of(out, "committing")
  revision <- if (is.na(committing)) NA_character_ else sub("^parent \\S+ -> (\\S+).*$", "\\1", committing)
  list(
    revision = revision,
    digest = np_field_of(out, "digest"),
    revision_url = np_field_of(out, "revision"),
    view_url = np_field_of(out, "view"),
    parent = parent,
    unchanged = FALSE,
    output = out
  )
}
