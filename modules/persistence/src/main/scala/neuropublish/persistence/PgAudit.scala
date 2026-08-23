package neuropublish.persistence

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.time.Instant
import neuropublish.api.AuditEvent
import neuropublish.backend.Audit

/** `audit_events`: append-only, listed per workspace in insertion order. */
final class PgAudit(xa: Transactor[IO]) extends Audit:
  def record(
      actor: String,
      action: String,
      workspace: String,
      project: Option[String],
      subject: Option[String],
      detail: Option[String]
  ): IO[Unit] =
    for
      id <- Audit.newId
      now <- IO.realTimeInstant
      // an unknown workspace is dropped, as the local store does, rather than failing the request
      _ <-
        sql"""INSERT INTO audit_events (id, at, actor, action, workspace_id, project, subject, detail)
                 SELECT $id, $now, $actor, $action, $workspace, $project, $subject, $detail
                 WHERE EXISTS (SELECT 1 FROM workspaces WHERE id = $workspace)"""
          .update.run.void.transact(xa)
    yield ()

  def list(workspace: String): IO[List[AuditEvent]] =
    sql"""SELECT id, at, actor, action, workspace_id, project, subject, detail FROM audit_events
          WHERE workspace_id = $workspace ORDER BY seq"""
      .query[(
          String,
          Instant,
          String,
          String,
          String,
          Option[String],
          Option[String],
          Option[
            String
          ]
      )]
      .to[List].map(_.map((id, at, actor, action, ws, p, s, d) =>
        AuditEvent(id, Db.render(at), actor, action, ws, p, s, d)
      )).transact(xa)
