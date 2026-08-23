package neuropublish.backend

import cats.effect.IO
import io.circe.Codec
import io.circe.generic.semiauto.*
import java.time.Instant
import neuropublish.api.ShareLinkSummary

/** Read-only share links: a random bearer secret (only its SHA-256 stored) naming one immutable
  * saved-view version; optional expiry; revocable.
  */
final case class ShareLinkRecord(
    id: String,
    workspace: String,
    project: String,
    view: String,
    version: Int,
    secretHash: String,
    createdAt: String,
    createdBy: String,
    expiresAt: Option[String],
    revokedAt: Option[String]
):
  def summary: ShareLinkSummary =
    ShareLinkSummary(id, view, version, createdAt, createdBy, expiresAt, revokedAt)
  def usable(now: Instant): Boolean =
    revokedAt.isEmpty && expiresAt.forall(e => Instant.parse(e).isAfter(now))
object ShareLinkRecord:
  given Codec[ShareLinkRecord] = deriveCodec

trait ShareLinks:
  /** Returns the record and the clear 32-character secret (never stored). */
  def create(
      view: ViewRecord,
      version: Int,
      createdBy: String,
      expiresInDays: Option[Int]
  ): IO[(ShareLinkRecord, String)]
  def get(id: String): IO[Option[ShareLinkRecord]]

  /** The link a presented secret names, whatever its state. */
  def resolve(secret: String): IO[Option[ShareLinkRecord]]
  def list(key: ProjectKey): IO[List[ShareLinkRecord]]
  def revoke(id: String): IO[Option[ShareLinkRecord]]

object ShareLinks:
  def newId: IO[String] = Secrets.token(9).map(t => "l-" + t.filter(_.isLetterOrDigit).take(10))

  /** 24 random bytes → 32 url-safe characters. */
  def newSecret: IO[String] = Secrets.token(24)
