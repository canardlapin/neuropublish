package neuropublish.backend

import cats.effect.IO
import cats.effect.std.Mutex
import fs2.io.file.Path
import io.circe.syntax.*
import neuropublish.api.AuditEvent
import neuropublish.api.Stage4.given

/** Local-fs [[Audit]]: one JSONL file per workspace, `<data>/audit/<workspace>.jsonl`. */
final class LocalAudit(dir: Path, mutex: Mutex[IO]) extends Audit:
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
        id <- Audit.newId
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

object LocalAudit:
  def apply(root: Path): IO[Audit] = Mutex[IO].map(m => new LocalAudit(root / "audit", m))
