package neuropublish.backend

import cats.effect.IO
import neuropublish.api.AuditEvent

/** Append-only, per-workspace audit log. */
trait Audit:
  def record(
      actor: String,
      action: String,
      workspace: String,
      project: Option[String] = None,
      subject: Option[String] = None,
      detail: Option[String] = None
  ): IO[Unit]
  def list(workspace: String): IO[List[AuditEvent]]

object Audit:
  def newId: IO[String] = Secrets.token(9).map(t => "a-" + t.filter(_.isLetterOrDigit).take(10))
