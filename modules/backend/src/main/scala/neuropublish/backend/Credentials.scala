package neuropublish.backend

import cats.effect.IO
import fs2.io.file.Path
import io.circe.Codec
import io.circe.generic.semiauto.*

/** Local-fs [[Credentials]]: `<data>/credentials/<id>.json` keeps the hash and
  * `<data>/credentials/by-hash/<sha256>.json` maps a presented secret back to the id.
  */
final class LocalCredentials(dir: Path) extends Credentials:
  private final case class HashRef(id: String)
  private given Codec[HashRef] = deriveCodec
  private def file(id: String) = dir / s"$id.json"
  private def hashFile(hash: String) = dir / "by-hash" / s"$hash.json"

  def create(key: ProjectKey, name: String, createdBy: String): IO[(CredentialRecord, String)] =
    for
      id <- Credentials.newId
      secret <- Credentials.newSecret
      now <- IO.realTimeInstant
      hash = Secrets.sha256Hex(secret)
      rec = CredentialRecord(
        id,
        name,
        key.workspace,
        key.project,
        hash,
        now.toString,
        createdBy,
        None
      )
      _ <- JsonFiles.write(file(id), rec)
      _ <- JsonFiles.write(hashFile(hash), HashRef(id))
    yield (rec, secret)

  def get(id: String): IO[Option[CredentialRecord]] =
    if !Ids.valid(id) then IO.none else JsonFiles.read[CredentialRecord](file(id))

  def resolve(secret: String): IO[Option[CredentialRecord]] =
    JsonFiles.read[HashRef](hashFile(Secrets.sha256Hex(secret))).flatMap {
      case None => IO.none
      case Some(h) => get(h.id)
    }

  def list(key: ProjectKey): IO[List[CredentialRecord]] =
    JsonFiles.list[CredentialRecord](dir).map(_.filter(c => c.key == key && c.revokedAt.isEmpty))

  def revoke(id: String): IO[Option[CredentialRecord]] =
    get(id).flatMap {
      case None => IO.none
      case Some(c) if c.revokedAt.isDefined => IO.pure(Some(c))
      case Some(c) =>
        IO.realTimeInstant.flatMap { now =>
          val r = c.copy(revokedAt = Some(now.toString))
          JsonFiles.write(file(id), r).as(Some(r))
        }
    }
