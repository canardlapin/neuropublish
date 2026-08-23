package neuropublish.backend

import cats.effect.IO
import fs2.io.file.Path
import io.circe.Codec
import io.circe.generic.semiauto.*
import java.time.Instant
import neuropublish.api.ShareLinkSummary

/** Read-only share links: a random bearer secret (only its SHA-256 stored) naming one immutable
  * saved-view version; optional expiry; revocable. `<data>/links/<id>.json` and
  * `<data>/links/by-hash/<sha256>.json`.
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

final class ShareLinks(dir: Path):
  private final case class HashRef(id: String)
  private given Codec[HashRef] = deriveCodec
  private def file(id: String) = dir / s"$id.json"
  private def hashFile(hash: String) = dir / "by-hash" / s"$hash.json"

  /** Returns the record and the clear 32-character secret (never stored). */
  def create(
      view: ViewRecord,
      version: Int,
      createdBy: String,
      expiresInDays: Option[Int]
  ): IO[(ShareLinkRecord, String)] =
    for
      id <- Secrets.token(9).map(t => "l-" + t.filter(_.isLetterOrDigit).take(10))
      secret <- Secrets.token(24) // 24 bytes → 32 url-safe chars
      now <- IO.realTimeInstant
      exp = expiresInDays.map(d => now.plusSeconds(d.toLong * 86400).toString)
      rec = ShareLinkRecord(
        id,
        view.workspace,
        view.project,
        view.id,
        version,
        Secrets.sha256Hex(secret),
        now.toString,
        createdBy,
        exp,
        None
      )
      _ <- JsonFiles.write(file(id), rec)
      _ <- JsonFiles.write(hashFile(rec.secretHash), HashRef(id))
    yield (rec, secret)

  def get(id: String): IO[Option[ShareLinkRecord]] =
    if !Ids.valid(id) then IO.none else JsonFiles.read[ShareLinkRecord](file(id))

  /** The link a presented secret names, whatever its state. */
  def resolve(secret: String): IO[Option[ShareLinkRecord]] =
    if secret.length > 128 then IO.none
    else
      JsonFiles.read[HashRef](hashFile(Secrets.sha256Hex(secret))).flatMap {
        case None => IO.none
        case Some(h) => get(h.id)
      }

  def list(key: ProjectKey): IO[List[ShareLinkRecord]] =
    JsonFiles.list[ShareLinkRecord](dir).map(_.filter(l =>
      ProjectKey(l.workspace, l.project) == key
    ))

  def revoke(id: String): IO[Option[ShareLinkRecord]] =
    get(id).flatMap {
      case None => IO.none
      case Some(l) if l.revokedAt.isDefined => IO.pure(Some(l))
      case Some(l) =>
        IO.realTimeInstant.flatMap { now =>
          val r = l.copy(revokedAt = Some(now.toString))
          JsonFiles.write(file(id), r).as(Some(r))
        }
    }
