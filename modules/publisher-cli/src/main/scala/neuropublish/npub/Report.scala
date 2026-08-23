package neuropublish.npub

import cats.effect.IO
import io.circe.Json
import neuropublish.protocol.json.Problem

/** The `--json` output mode: every subcommand that supports it prints exactly one JSON document on
  * stdout and nothing else there (progress and warnings go to stderr), so a wrapper in another
  * language parses a value instead of lines. The shape is the same for every command:
  *
  * {{{
  * {"ok": true,  ...command fields...}
  * {"ok": false, "problems": [{"pointer": "/assets/0", "message": "..."}]}   admission problems
  * {"ok": false, "error": {"type": "NoSuchFileException", "message": "..."}}  anything else
  * }}}
  *
  * `error.type` is a stable token: the server's error code for a rejected request (`stale_parent`
  * carries `head` too), `cli` for a `CliError`, `unreachable` for a connection failure, and the
  * exception's class name otherwise. The exit code is the same as in human mode.
  */
object Report:
  def problems(ps: List[Problem]): Json =
    Json.arr(ps.map(p =>
      Json.obj("pointer" -> Json.fromString(p.pointer), "message" -> Json.fromString(p.message))
    )*)

  def success(fields: (String, Json)*): Json = Json.obj(("ok" -> Json.True) +: fields*)

  def rejected(ps: List[Problem], fields: (String, Json)*): Json =
    Json.obj((("ok" -> Json.False) +: ("problems" -> problems(ps)) +: fields)*)

  def failure(tpe: String, message: String, extra: (String, Json)*): Json =
    Json.obj(
      "ok" -> Json.False,
      "error" -> Json.obj(
        (("type" -> Json.fromString(tpe)) +: ("message" -> Json.fromString(message)) +: extra)*
      )
    )

  /** A thrown failure, classified the way [[Api.describe]] words it. */
  def throwable(server: String)(e: Throwable): Json = e match
    case CliError(m) => failure("cli", m)
    case _: java.net.ConnectException | _: java.nio.channels.UnresolvedAddressException =>
      failure("unreachable", s"cannot reach $server")
    case e => failure(e.getClass.getSimpleName, Option(e.getMessage).getOrElse(""))

  def optString(s: Option[String]): Json = s.fold(Json.Null)(Json.fromString)

  /** Where a command's human-readable lines go: stdout normally, stderr under `--json` so stdout
    * holds the one document.
    */
  def progress(json: Boolean, out: String => IO[Unit]): String => IO[Unit] =
    if json then IO.consoleForIO.errorln(_) else out
