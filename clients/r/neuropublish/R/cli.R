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
    if (echo) {
      # stdout = "" streams the CLI's output to the console as it arrives;
      # capturing it (stdout = TRUE) would hold the device-flow URL and code
      # back until the command exits, i.e. until the user has approved them.
      status <- suppressWarnings(system2(cmd, shQuote(argv), stdout = "", stderr = err))
      out <- character()
    } else {
      out <- suppressWarnings(system2(cmd, shQuote(argv), stdout = TRUE, stderr = err))
      status <- attr(out, "status")
      if (is.null(status)) status <- 0L
    }
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

#' Run a subcommand in `--json` mode and decode its one document
#'
#' Every subcommand the wrapper uses (`validate`, `pack`, `push`) prints, under
#' `--json`, exactly one JSON object on stdout: `{"ok": true, ...}`,
#' `{"ok": false, "problems": [{pointer, message}, ...]}` for admission
#' problems, or `{"ok": false, "error": {"type", "message", ...}}` for
#' anything else (a missing file, an unreachable server, a rejected request).
#' Progress lines go to stderr. A `stdout` that is not such a document is an
#' error here, never a problem row.
#'
#' @return The decoded document (`simplifyVector = FALSE`) with the CLI's
#'   `status` and `stderr` attached as attributes.
#' @keywords internal
#' @noRd
np_run_json <- function(args, what) {
  res <- np_run(c(args, "--json"))
  np_decode_cli(res$stdout, what, status = res$status, stderr = res$stderr)
}

#' Decode the `--json` document the CLI printed (pure: takes the text)
#' @noRd
np_decode_cli <- function(stdout, what, status = NA_integer_, stderr = character()) {
  text <- paste(stdout, collapse = "\n")
  doc <- if (nzchar(trimws(text))) {
    tryCatch(jsonlite::fromJSON(text, simplifyVector = FALSE), error = function(e) NULL)
  }
  if (!is.list(doc) || !is.logical(doc$ok)) {
    diag <- c(stdout, grep("^(SLF4J|WARNING)", stderr, value = TRUE, invert = TRUE))
    stop(
      "npub ", what, " did not return a JSON document (exit ", status, ")",
      if (length(diag)) paste0(":\n", paste(diag, collapse = "\n")) else "",
      call. = FALSE
    )
  }
  structure(doc, status = status, stderr = stderr)
}

np_problems_frame <- function(problems) {
  data.frame(
    pointer = vapply(problems, function(p) as.character(p$pointer), character(1)),
    message = vapply(problems, function(p) as.character(p$message), character(1)),
    stringsAsFactors = FALSE
  )
}

#' Raise the CLI's `error` record as an R error
#'
#' The condition has class `np_cli_error` and carries the CLI's `type` in
#' `$type`, so callers can branch on it (`stale_parent` is raised separately as
#' [np_push()]'s `np_stale_parent`). When the document has no `error` (pure
#' admission problems where they are not expected) the problems are listed.
#' @noRd
np_cli_stop <- function(doc, what) {
  err <- doc$error
  if (is.list(err) && !is.null(err$message)) {
    stop(structure(
      list(
        message = paste0("npub ", what, " failed (", err$type, "): ", err$message),
        type = as.character(err$type),
        call = NULL
      ),
      class = c("np_cli_error", "error", "condition")
    ))
  }
  problems <- np_problems_frame(doc$problems)
  stop(
    "npub ", what, " failed: ", nrow(problems), " problem(s)",
    if (nrow(problems)) paste0(":\n", paste(
      ifelse(nzchar(problems$pointer), paste0(problems$pointer, ": ", problems$message), problems$message),
      collapse = "\n"
    )) else "",
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
#'   attributes `digest` (the manifest digest) and `assets` (`"N declared, M
#'   volume"`). A failure that is not an admission problem (the bundle has no
#'   `manifest.json`, `npub` cannot run) is an error of class `np_cli_error`,
#'   never a problem row.
#' @examples
#' \dontrun{
#' problems <- np_validate("speech-model.npub")
#' if (nrow(problems)) print(problems)
#' }
#' @export
np_validate <- function(dir) {
  dir <- normalizePath(dir, winslash = "/", mustWork = TRUE)
  doc <- np_run_json(c("validate", dir), "validate")
  np_validate_result(doc)
}

np_validate_result <- function(doc) {
  if (isTRUE(doc$ok)) {
    out <- data.frame(pointer = character(), message = character(), stringsAsFactors = FALSE)
    attr(out, "digest") <- doc$digest
    attr(out, "assets") <- sprintf("%d declared, %d volume", doc$assets$declared, doc$assets$volume)
    return(out)
  }
  if (is.null(doc$problems)) np_cli_stop(doc, "validate")
  np_problems_frame(doc$problems)
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
  doc <- np_run_json(c("pack", staging, out, if (isTRUE(force)) "--force"), "pack")
  if (!isTRUE(doc$ok)) np_cli_stop(doc, "pack")
  list(
    dir = normalizePath(out, winslash = "/", mustWork = TRUE),
    digest = doc$digest,
    assets = np_pack_assets(doc$assets)
  )
}

np_pack_assets <- function(assets) {
  data.frame(
    id = vapply(assets, function(a) as.character(a$id), character(1)),
    size = vapply(assets, function(a) as.numeric(a$size), numeric(1)),
    digest = vapply(assets, function(a) as.character(a$digest), character(1)),
    stringsAsFactors = FALSE
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
#'   `parent` (as the server recorded it), `unchanged`, and `output` (the
#'   CLI's progress lines). Any other failure is an error of class
#'   `np_cli_error` whose `type` is the server's error code or the CLI's
#'   failure type.
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
  np_push_result(np_run_json(args, "push"), parent)
}

np_push_result <- function(doc, parent = NULL) {
  if (isTRUE(doc$ok)) {
    if (isTRUE(doc$unchanged)) {
      return(list(
        revision = doc$revision, digest = NA_character_, revision_url = NA_character_,
        view_url = NA_character_, parent = parent, unchanged = TRUE, output = attr(doc, "stderr")
      ))
    }
    return(list(
      revision = doc$revision,
      digest = doc$digest,
      revision_url = doc$revisionUrl,
      view_url = doc$viewUrl,
      parent = doc$parent,
      unchanged = FALSE,
      output = attr(doc, "stderr")
    ))
  }
  if (identical(doc$error$type, "stale_parent")) {
    head <- if (is.null(doc$error$head)) NA_character_ else as.character(doc$error$head)
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
  np_cli_stop(doc, "push")
}
