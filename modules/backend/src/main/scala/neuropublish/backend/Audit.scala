package neuropublish.backend

import cats.effect.IO
import cats.effect.std.Mutex
import fs2.io.file.Path
import io.circe.syntax.*
import neuropublish.api.AuditEvent
import neuropublish.api.Stage4.given

/** Append-only audit log, one JSONL file per workspace: `<data>/audit/<workspace>.jsonl`. */
final class Audit(dir: Path, mutex: Mutex[IO]):
  private def file(ws: String) = dir / s"$ws.jsonl"

  def record(
      actor: String,
      action: String,
      workspace: String,
      project: Option[String] = None,
      subject: Option[String] = None,
      detail: Option[String] = None
  ): IO[Unit] =
    if !Ids.valid(workspace) then IO.unit
    else
      for
        id <- Secrets.token(9).map(t => "a-" + t.filter(_.isLetterOrDigit).take(10))
        now <- IO.realTimeInstant
        ev = AuditEvent(id, now.toString, actor, action, workspace, project, subject, detail)
        _ <- mutex.lock.surround(JsonFiles.appendLine(file(workspace), ev.asJson.noSpaces))
      yield ()

  def list(workspace: String): IO[List[AuditEvent]] =
    if !Ids.valid(workspace) then IO.pure(Nil)
    else
      JsonFiles.readLines(file(workspace)).map(_.flatMap(l =>
        io.circe.parser.decode[AuditEvent](l).toOption
      ))

object Audit:
  def localFs(root: Path): IO[Audit] = Mutex[IO].map(m => new Audit(root / "audit", m))
