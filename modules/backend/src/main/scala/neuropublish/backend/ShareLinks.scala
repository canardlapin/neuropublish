package neuropublish.backend

import cats.effect.IO
import fs2.io.file.Path
import io.circe.Codec
import io.circe.generic.semiauto.*

/** Local-fs [[ShareLinks]]: `<data>/links/<id>.json` and `<data>/links/by-hash/<sha256>.json`. */
final class LocalShareLinks(dir: Path) extends ShareLinks:
  private final case class HashRef(id: String)
  private given Codec[HashRef] = deriveCodec
  private def file(id: String) = dir / s"$id.json"
  private def hashFile(hash: String) = dir / "by-hash" / s"$hash.json"

  def create(
      view: ViewRecord,
      version: Int,
      createdBy: String,
      expiresInDays: Option[Int]
  ): IO[(ShareLinkRecord, String)] =
    for
      id <- ShareLinks.newId
      secret <- ShareLinks.newSecret
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

  def resolveId(id: String): IO[Option[ShareLinkRecord]] =
    if !Ids.valid(id) then IO.none else JsonFiles.read[ShareLinkRecord](file(id))
  def get(workspace: String, id: String): IO[Option[ShareLinkRecord]] =
    resolveId(id).map(_.filter(_.workspace == workspace))

  def resolveSecret(secret: String): IO[Option[ShareLinkRecord]] =
    if secret.length > 128 then IO.none
    else
      JsonFiles.read[HashRef](hashFile(Secrets.sha256Hex(secret))).flatMap {
        case None => IO.none
        case Some(h) => resolveId(h.id)
      }

  def list(key: ProjectKey): IO[List[ShareLinkRecord]] =
    JsonFiles.list[ShareLinkRecord](dir).map(_.filter(l =>
      ProjectKey(l.workspace, l.project) == key
    ))

  def revoke(id: String): IO[Option[ShareLinkRecord]] =
    resolveId(id).flatMap {
      case None => IO.none
      case Some(l) if l.revokedAt.isDefined => IO.pure(Some(l))
      case Some(l) =>
        IO.realTimeInstant.flatMap { now =>
          val r = l.copy(revokedAt = Some(now.toString))
          JsonFiles.write(file(id), r).as(Some(r))
        }
    }
